/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.RabbitBroker;
import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import java.io.File;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One bean listening on Kafka, Rabbit and JMS at once, against three real
 * brokers on Spring 6. Every save validates all three brokers before any of
 * them retires a consumer: an accepted save replaces all three consumers, a
 * refused one leaves all three running untouched.
 */
class ModernMixedBrokerListenerReloadTest {
    @TempDir Path tmp;

    static EmbeddedKafkaBroker kafka;
    static RabbitBroker rabbit;
    static BrokerService jms;
    static String jmsUrl;

    @BeforeAll static void startBrokers() throws Exception {
        kafka = new org.springframework.kafka.test.EmbeddedKafkaZKBroker(1, false, 1);
        kafka.afterPropertiesSet();
        rabbit = RabbitBroker.start();
        jms = new BrokerService(); jms.setBrokerName("reclazz-mixed-" + UUID.randomUUID());
        jms.setPersistent(false); jms.setUseJmx(false); jms.setUseShutdownHook(false);
        var connector = jms.addConnector("tcp://127.0.0.1:0"); jms.start(); jms.waitUntilStarted();
        jmsUrl = connector.getPublishableConnectString();
    }
    @AfterAll static void stopBrokers() throws Exception {
        if (kafka != null) kafka.destroy();
        if (rabbit != null) rabbit.close();
        if (jms != null) { jms.stop(); jms.waitUntilStopped(); }
    }

    /** Destinations of one run. Each broker gets its own name for each role. */
    record Names(String root) {
        String k(String role) { return root + "-k-" + role; }
        String r(String role) { return root + "-r-" + role; }
        String j(String role) { return root + "-j-" + role; }
    }

    @Test void oneBeanOnThreeBrokersIsRetiredWholeOrRefusedWhole() throws Exception {
        var n = new Names("mixed-" + UUID.randomUUID());
        List<String> roles = List.of("base", "added", "other");
        for (String role : roles) kafka.addTopics(n.k(role));
        var admin = new RabbitAdmin(rabbit.connectionFactory());
        for (String role : roles) admin.declareQueue(new org.springframework.amqp.core.Queue(n.r(role), true, false, false));
        var kafkaProducers = new DefaultKafkaProducerFactory<Integer,String>(KafkaTestUtils.producerProps(kafka));
        var k = new KafkaTemplate<Integer,String>(kafkaProducers);
        var r = new RabbitTemplate(rabbit.connectionFactory());
        var j = new JmsTemplate(new ActiveMQConnectionFactory(jmsUrl));
        Map<String, Sender> send = Map.of(
                "K", (role, msg) -> k.send(n.k(role), msg).get(10, TimeUnit.SECONDS),
                "R", (role, msg) -> r.convertAndSend("", n.r(role), msg),
                "J", (role, msg) -> j.convertAndSend(n.j(role), msg));
        try (var app = WatchedApp.in(tmp).classpath(classpath())
                .jvmArgs("-Dtest.kafka=" + kafka.getBrokersAsString(), "-Dtest.rabbit.port=" + rabbit.port(),
                        "-Dtest.rabbit.password=" + rabbit.password(), "-Dtest.jms=" + jmsUrl)
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(n, "v0", false, false)).with("Other", other(n)).with("App", APP).start()) {
            app.awaitOrFail("PORT=", "mixed app did not start");
            app.awaitOrFail("Last-known-good bytecode cache:", "watcher did not finish initialization");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            assertEquals("used", http(port, "/use"));
            for (String id : List.of("k", "r", "j", "ok", "or", "oj")) waitState(port, ";" + id + ":ready", app);
            for (String b : send.keySet()) {
                deliver(send.get(b), app, "base", b + ":v0:initial", "initial");
                deliver(send.get(b), app, "other", "O" + b + ":initial", "initial");
            }

            // 1. Body edit: all three consumers of the bean are replaced, the
            // other bean's three keep their identity. A message sent to each
            // broker just before the save arrives exactly once.
            save(app, port, send, listener(n, "v1", false, false), "pre1");
            assertAllReplaced(port, app, List.of("k", "r", "j"));
            for (String b : send.keySet()) {
                assertExactlyOnce(app, b, "pre1");
                deliver(send.get(b), app, "base", b + ":v1:save1", "save1");
            }

            // 2. Added listeners on all three brokers in one save.
            save(app, port, send, listener(n, "v2", true, false), "pre2");
            for (String id : List.of("k", "r", "j", "ka", "ra", "ja")) waitState(port, ";" + id + ":ready", app);
            assertAllReplaced(port, app, List.of("k", "r", "j"));
            for (String b : send.keySet()) {
                assertExactlyOnce(app, b, "pre2");
                deliver(send.get(b), app, "base", b + ":v2:save2", "save2");
                deliver(send.get(b), app, "added", "A" + b + ":v2:save2", "save2");
            }

            // 3. One broker refuses (the added Rabbit listener claims the other
            // bean's container id). Nothing retires on any broker: Kafka and
            // JMS keep the very same running containers and old bodies.
            http(port, "/snapshot");
            long before = reloads(app);
            app.rewrite("Listener", listener(n, "v3", true, true));
            awaitReload(app, before);
            assertTrue(app.awaits("listener id already belongs to an existing container: or", 20), app.tail());
            String state = http(port, "/state");
            for (String id : List.of("k", "r", "j", "ka", "ra", "ja", "ok", "or", "oj"))
                assertTrue(state.contains(";" + id + ":ready:same;"), id + " must keep its consumer: " + state + "\n" + app.tail());
            for (String b : send.keySet()) deliver(send.get(b), app, "base", b + ":v2:refused", "refused");

            // 4. Fixing the save retires all three together again.
            save(app, port, send, listener(n, "v4", true, false), "pre4");
            for (String id : List.of("k", "r", "j", "ka", "ra", "ja")) waitState(port, ";" + id + ":ready", app);
            assertAllReplaced(port, app, List.of("k", "r", "j", "ka", "ra", "ja"));
            for (String b : send.keySet()) {
                assertExactlyOnce(app, b, "pre4");
                deliver(send.get(b), app, "base", b + ":v4:save4", "save4");
                deliver(send.get(b), app, "added", "A" + b + ":v4:save4", "save4");
            }

            // 5. Removing the added listeners removes them from all three registries.
            save(app, port, send, listener(n, "v5", false, false), "pre5");
            for (String id : List.of("k", "r", "j")) waitState(port, ";" + id + ":ready", app);
            state = http(port, "/state");
            for (String id : List.of("ka", "ra", "ja")) assertFalse(state.contains(";" + id + ":"), state);
            assertAllReplaced(port, app, List.of("k", "r", "j"));
            for (String b : send.keySet()) {
                assertExactlyOnce(app, b, "pre5");
                deliver(send.get(b), app, "base", b + ":v5:save5", "save5");
                deliver(send.get(b), app, "other", "O" + b + ":save5", "save5");
            }

            assertTrue(http(port, "/state").contains("retiredRunning=0"), app.tail());
            assertEquals("allStopped=true", http(port, "/close"));
            assertFalse(app.output().stream().anyMatch(s -> s.contains("stay retired")), "no save may leave a broker half retired: " + app.tail());
            System.out.println("[mixed-brokers] Kafka+Rabbit+JMS on one bean: edit/add/refuse-whole/fix/remove passed; "
                    + "single delivery across saves, unrelated identity, context close verified");
        } finally { kafkaProducers.destroy(); }
    }

    @FunctionalInterface interface Sender { void send(String role, String msg) throws Exception; }

    /** Sends one message per broker to the base listeners, then saves, so each lands in the retirement window. */
    private static void save(WatchedApp app, int port, Map<String, Sender> send, String source, String pre) throws Exception {
        http(port, "/snapshot");
        long before = reloads(app);
        for (var e : send.entrySet()) e.getValue().send("base", pre);
        app.rewrite("Listener", source);
        awaitReload(app, before);
    }
    private static void awaitReload(WatchedApp app, long before) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (reloads(app) == before && System.nanoTime() < until) Thread.sleep(25);
        assertEquals(before + 1, reloads(app), app.tail());
    }
    private static void assertAllReplaced(int port, WatchedApp app, List<String> ids) throws Exception {
        String state = http(port, "/state");
        for (String id : ids) assertTrue(state.contains(";" + id + ":ready:new;"), id + " must be a new consumer: " + state + "\n" + app.tail());
        for (String id : List.of("ok", "or", "oj")) assertTrue(state.contains(";" + id + ":ready:same;"), id + " must keep identity: " + state);
        assertTrue(state.contains("retiredRunning=0"), "retired consumers must stop: " + state);
    }
    private static void assertExactlyOnce(WatchedApp app, String broker, String msg) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (count(app, broker, msg) == 0 && System.nanoTime() < until) Thread.sleep(25);
        Thread.sleep(300);
        assertEquals(1, count(app, broker, msg), broker + " " + msg + " must arrive exactly once: " + app.tail());
    }
    private static long count(WatchedApp app, String broker, String msg) {
        return app.output().stream().filter(s -> s.startsWith(broker + ":") && s.endsWith(":" + msg)).count();
    }
    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Listener ") || s.contains("Structural reload: app.Listener ")).count();
    }
    private static void deliver(Sender sender, WatchedApp app, String role, String line, String msg) throws Exception {
        sender.send(role, msg);
        assertTrue(app.awaits(line, 20), line + "\n" + app.tail());
        Thread.sleep(200);
        assertEquals(1, app.output().stream().filter(line::equals).count(), app.tail());
    }
    private static void waitState(int port, String text, WatchedApp app) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        String state;
        do { state = http(port, "/state"); if (state.contains(text)) return; Thread.sleep(50); } while (System.nanoTime() < until);
        fail("Missing " + text + " in " + state + "\n" + app.tail());
    }
    private static String http(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }
    private static String classpath() {
        return String.join(File.pathSeparator, Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(s -> {
                    String n = Path.of(s).getFileName().toString();
                    return n.endsWith(".jar") && (n.startsWith("spring-") && !n.startsWith("spring-kafka-test")
                            || n.startsWith("kafka-clients-") || n.startsWith("lz4-java-") || n.startsWith("snappy-java-") || n.startsWith("zstd-jni-")
                            || n.startsWith("amqp-client-") || n.startsWith("spring-retry-")
                            || n.startsWith("activemq-client-") || n.startsWith("jakarta.jms-api-") || n.startsWith("hawtbuf-")
                            || n.startsWith("slf4j-api-") || n.startsWith("micrometer-"));
                }).toList());
    }

    private static String listener(Names n, String version, boolean added, boolean conflict) {
        String extra = !added ? "" : """
                @org.springframework.kafka.annotation.KafkaListener(id="ka", topics="%s", groupId="%s-g")
                public void ka(String v) { System.out.println("AK:" + version + ":" + v); }
                @org.springframework.amqp.rabbit.annotation.RabbitListener(id="%s", queues="%s")
                public void ra(String v) { System.out.println("AR:" + version + ":" + v); }
                @org.springframework.jms.annotation.JmsListener(id="ja", destination="%s")
                public void ja(String v) { System.out.println("AJ:" + version + ":" + v); }
                """.formatted(n.k("added"), n.k("added"), conflict ? "or" : "ra", n.r("added"), n.j("added"));
        return """
                package app;
                @org.springframework.stereotype.Component public class Listener {
                    private final String version = new String("%s");
                    public String use() { return "used"; }
                    @org.springframework.kafka.annotation.KafkaListener(id="k", topics="%s", groupId="%s-g")
                    public void k(String v) { System.out.println("K:" + version + ":" + v); }
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(id="r", queues="%s")
                    public void r(String v) { System.out.println("R:" + version + ":" + v); }
                    @org.springframework.jms.annotation.JmsListener(id="j", destination="%s")
                    public void j(String v) { System.out.println("J:" + version + ":" + v); }
                    %s
                }
                """.formatted(version, n.k("base"), n.k("base"), n.r("base"), n.j("base"), extra);
    }
    private static String other(Names n) {
        return """
                package app;
                @org.springframework.stereotype.Component public class Other {
                    @org.springframework.kafka.annotation.KafkaListener(id="ok", topics="%s", groupId="%s-g")
                    public void k(String v) { System.out.println("OK:" + v); }
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(id="or", queues="%s")
                    public void r(String v) { System.out.println("OR:" + v); }
                    @org.springframework.jms.annotation.JmsListener(id="oj", destination="%s")
                    public void j(String v) { System.out.println("OJ:" + v); }
                }
                """.formatted(n.k("other"), n.k("other"), n.r("other"), n.j("other"));
    }

    private static final String APP = """
            package app;
            import java.util.*;
            @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
            @org.springframework.context.annotation.ComponentScan("app")
            @org.springframework.kafka.annotation.EnableKafka
            @org.springframework.amqp.rabbit.annotation.EnableRabbit
            @org.springframework.jms.annotation.EnableJms
            public class App {
                @org.springframework.context.annotation.Bean
                public org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Integer,String> kafkaListenerContainerFactory() {
                    var props = new HashMap<String,Object>();
                    props.put("bootstrap.servers", System.getProperty("test.kafka"));
                    props.put("key.deserializer", org.apache.kafka.common.serialization.IntegerDeserializer.class);
                    props.put("value.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class);
                    props.put("auto.offset.reset", "earliest"); props.put("enable.auto.commit", false);
                    var factory = new org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Integer,String>();
                    factory.setConsumerFactory(new org.springframework.kafka.core.DefaultKafkaConsumerFactory<>(props));
                    factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
                    factory.getContainerProperties().setPollTimeout(100);
                    return factory;
                }
                @org.springframework.context.annotation.Bean
                public org.springframework.amqp.rabbit.connection.CachingConnectionFactory rabbitConnectionFactory() {
                    var cf = new org.springframework.amqp.rabbit.connection.CachingConnectionFactory("127.0.0.1", Integer.parseInt(System.getProperty("test.rabbit.port")));
                    cf.setUsername("reclazz"); cf.setPassword(System.getProperty("test.rabbit.password")); return cf;
                }
                @org.springframework.context.annotation.Bean
                public org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(org.springframework.amqp.rabbit.connection.CachingConnectionFactory cf) {
                    var factory = new org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory();
                    factory.setConnectionFactory(cf); factory.setPrefetchCount(1);
                    return factory;
                }
                @org.springframework.context.annotation.Bean
                public org.springframework.jms.config.DefaultJmsListenerContainerFactory jmsListenerContainerFactory() {
                    var factory = new org.springframework.jms.config.DefaultJmsListenerContainerFactory();
                    factory.setConnectionFactory(new org.apache.activemq.ActiveMQConnectionFactory(System.getProperty("test.jms")));
                    factory.setReceiveTimeout(100L); factory.setSessionTransacted(true);
                    return factory;
                }
                interface Probe { boolean ready(); boolean running(); }
                static Map<String, Object> containers(org.springframework.context.ApplicationContext ctx) {
                    var all = new TreeMap<String, Object>();
                    var k = ctx.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class);
                    for (String id : k.getListenerContainerIds()) all.put(id, k.getListenerContainer(id));
                    var r = ctx.getBean(org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry.class);
                    for (String id : r.getListenerContainerIds()) all.put(id, r.getListenerContainer(id));
                    var j = ctx.getBean(org.springframework.jms.config.JmsListenerEndpointRegistry.class);
                    for (String id : j.getListenerContainerIds()) all.put(id, j.getListenerContainer(id));
                    return all;
                }
                static boolean ready(Object c) {
                    if (c instanceof org.springframework.kafka.listener.MessageListenerContainer m) {
                        var p = m.getAssignedPartitions(); return m.isRunning() && p != null && !p.isEmpty();
                    }
                    if (c instanceof org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer m)
                        return m.isRunning() && m.getActiveConsumerCount() > 0;
                    var m = (org.springframework.jms.listener.DefaultMessageListenerContainer) c;
                    return m.isRunning() && m.getActiveConsumerCount() > 0;
                }
                static boolean active(Object c) {
                    if (c instanceof org.springframework.kafka.listener.MessageListenerContainer m) return m.isRunning();
                    if (c instanceof org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer m)
                        return m.isRunning() || m.isActive() || m.getActiveConsumerCount() != 0;
                    var m = (org.springframework.jms.listener.DefaultMessageListenerContainer) c;
                    return m.isRunning() || m.isActive() || m.getActiveConsumerCount() != 0;
                }
                public static void main(String[] args) throws Exception {
                    var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext(App.class);
                    var previous = new HashMap<String, Object>();
                    var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                    server.createContext("/", exchange -> {
                        String path = exchange.getRequestURI().getPath(); String text;
                        if (path.equals("/use")) text = ctx.getBean(Listener.class).use();
                        else if (path.equals("/snapshot")) {
                            previous.clear(); previous.putAll(containers(ctx)); seen.addAll(previous.values()); text = "saved";
                        } else if (path.equals("/close")) {
                            seen.addAll(containers(ctx).values()); ctx.close();
                            text = "allStopped=" + seen.stream().noneMatch(App::active);
                        } else {
                            var current = containers(ctx); var b = new StringBuilder(";");
                            for (var e : current.entrySet()) {
                                b.append(e.getKey()).append(ready(e.getValue()) ? ":ready" : ":waiting")
                                 .append(previous.get(e.getKey()) == e.getValue() ? ":same;" : ":new;");
                            }
                            var live = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()); live.addAll(current.values());
                            b.append("retiredRunning=").append(previous.values().stream().filter(c -> !live.contains(c) && active(c)).count());
                            text = b.toString();
                        }
                        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
                    });
                    server.start(); System.out.println("PORT=" + server.getAddress().getPort());
                }
            }
            """;
}
