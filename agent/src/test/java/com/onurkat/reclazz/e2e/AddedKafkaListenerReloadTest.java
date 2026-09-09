/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import java.io.File;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AddedKafkaListenerReloadTest {
    @TempDir Path tmp;
    static EmbeddedKafkaBroker broker;
    @BeforeAll static void startBroker() {
        broker = new EmbeddedKafkaBroker(1, false, 1);
        broker.afterPropertiesSet();
    }
    @AfterAll static void stopBroker() { if (broker != null) broker.destroy(); }
    @Test void firstListenerOnAnAlreadyUsedBeanFollowsEditsTopicsRemovalAndRestore() throws Exception { exercise(false); }
    @Test void addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone() throws Exception { exercise(true); }

    private void exercise(boolean existing) throws Exception {
        String root = "reclazz-" + UUID.randomUUID();
        String first = root + "-first", second = root + "-second", base = root + "-base", other = root + "-other";
        broker.addTopics(first, second, base, other);
        var producerFactory = new DefaultKafkaProducerFactory<Integer,String>(KafkaTestUtils.producerProps(broker));
        var producer = new KafkaTemplate<Integer,String>(producerFactory);
        try (var app = WatchedApp.in(tmp).classpath(kafkaClasspath())
                .jvmArgs("-Dtest.brokers=" + broker.getBrokersAsString())
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(existing, 0, first, base)).with("Other", other(other)).with("App", APP).start()) {
            app.awaitOrFail("PORT=", "Kafka app did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            assertEquals("used", http(port, "/use"));
            waitState(port, "other:assigned", app);
            if (existing) waitState(port, "existing:assigned", app);
            deliver(producer, app, other, "OTHER:initial", "initial");
            if (existing) deliver(producer, app, base, "BASE:initial", "initial");
            for (int stage = 1; stage <= 6; stage++) {
                http(port, "/snapshot");
                String topic = stage >= 3 ? second : first;
                long before = reloads(app);
                app.rewrite("Listener", listener(existing, stage, topic, base));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (reloads(app) == before && System.nanoTime() < until) Thread.sleep(25);
                assertEquals(before + 1, reloads(app), app.tail());
                boolean active = stage != 4 && stage != 6;
                if (active) {
                    waitState(port, "added:assigned", app);
                    String msg = "save" + stage;
                    deliver(producer, app, topic, "ADDED:" + stage + ":owner:" + topic + ":" + msg, msg);
                } else assertFalse(http(port, "/state").contains("added:"), app.tail());
                if (existing) {
                    waitState(port, "existing:assigned", app);
                    deliver(producer, app, base, "BASE:save" + stage, "save" + stage);
                }
                deliver(producer, app, other, "OTHER:save" + stage, "save" + stage);
                assertTrue(http(port, "/state").contains("retiredRunning=0"), "old containers must stop: " + app.tail());
                assertTrue(http(port, "/state").contains("otherSame=true"), "unrelated container must retain identity");
                if (stage >= 3) {
                    producer.send(first, "old-topic-" + stage).get(10, TimeUnit.SECONDS);
                    Thread.sleep(300);
                    assertFalse(app.output().stream().anyMatch(s -> s.startsWith("ADDED:") && s.contains("old-topic-")), app.tail());
                }
            }
            assertEquals("allStopped=true", http(port, "/close"));
            assertFalse(app.output().stream().anyMatch(s -> s.contains("@KafkaListener is found by scanning")), app.tail());
            System.out.println("[added-kafka] existing=" + existing + " add/edit/topic/remove/private-restore/unannotate passed; single delivery, retired consumers, unrelated identity, context close verified");
        } finally { producerFactory.destroy(); }
    }
    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Listener ") || s.contains("Structural reload: app.Listener ")).count();
    }
    private static void deliver(KafkaTemplate<Integer,String> producer, WatchedApp app, String topic, String line, String msg) throws Exception {
        producer.send(topic, msg).get(10, TimeUnit.SECONDS);
        assertTrue(app.awaits(line, 20), app.tail());
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
    private static String kafkaClasspath() {
        return String.join(File.pathSeparator, Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(s -> {
                    String n = Path.of(s).getFileName().toString();
                    return n.endsWith(".jar") && (n.startsWith("spring-") && !n.startsWith("spring-kafka-test")
                            || n.startsWith("kafka-clients-") || n.startsWith("slf4j-api-") || n.startsWith("lz4-java-")
                            || n.startsWith("snappy-java-") || n.startsWith("zstd-jni-"));
                }).toList());
    }
    private static String listener(boolean existing, int stage, String topic, String base) {
        String added = stage == 0 || stage == 4 ? "" : """
                %s
                %s void added(@org.springframework.messaging.handler.annotation.Payload String value,
                        @org.springframework.messaging.handler.annotation.Header(org.springframework.kafka.support.KafkaHeaders.RECEIVED_TOPIC) String topic) {
                    System.out.println("ADDED:%d:" + prefix + ":" + topic + ":" + value);
                }
                """.formatted(stage == 6 ? "" : "@org.springframework.kafka.annotation.KafkaListener(id=\"added\", topics=\"" + topic + "\", groupId=\"" + base + "-added-group\")",
                        stage == 5 ? "private" : "public", stage);
        return """
                package app;
                @org.springframework.stereotype.Component public class Listener {
                    private final String prefix = new String("owner");
                    public String use() { return "used"; }
                    %s
                    %s
                }
                """.formatted(existing ? """
                    @org.springframework.kafka.annotation.KafkaListener(id="existing", topics="%s", groupId="%s-group")
                    public void original(String value) { System.out.println("BASE:" + value); }
                    """.formatted(base, base) : "", added);
    }
    private static String other(String topic) {
        return """
                package app;
                @org.springframework.stereotype.Component public class Other {
                    @org.springframework.kafka.annotation.KafkaListener(id="other", topics="%s", groupId="%s-group")
                    public void original(String value) { System.out.println("OTHER:" + value); }
                }
                """.formatted(topic, topic);
    }
    private static final String APP = """
            package app;
            import org.springframework.kafka.listener.MessageListenerContainer;
            @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
            @org.springframework.context.annotation.ComponentScan("app")
            @org.springframework.kafka.annotation.EnableKafka
            public class App {
                @org.springframework.context.annotation.Bean
                public org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Integer,String> kafkaListenerContainerFactory() {
                    var props = new java.util.HashMap<String,Object>();
                    props.put("bootstrap.servers", System.getProperty("test.brokers"));
                    props.put("key.deserializer", org.apache.kafka.common.serialization.IntegerDeserializer.class);
                    props.put("value.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class);
                    props.put("auto.offset.reset", "earliest"); props.put("enable.auto.commit", false);
                    var factory = new org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Integer,String>();
                    factory.setConsumerFactory(new org.springframework.kafka.core.DefaultKafkaConsumerFactory<>(props));
                    factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
                    factory.getContainerProperties().setPollTimeout(100);
                    return factory;
                }
                public static void main(String[] args) throws Exception {
                    var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext(App.class);
                    var registry = ctx.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class);
                    var other = registry.getListenerContainer("other");
                    var previous = new java.util.ArrayList<MessageListenerContainer>();
                    var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<MessageListenerContainer,Boolean>());
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                    server.createContext("/", exchange -> {
                        String path = exchange.getRequestURI().getPath(); String text;
                        if (path.equals("/use")) text = ctx.getBean(Listener.class).use();
                        else if (path.equals("/snapshot")) {
                            previous.clear(); previous.addAll(registry.getListenerContainers()); seen.addAll(previous); text = "saved";
                        } else if (path.equals("/close")) {
                            seen.addAll(registry.getListenerContainers()); ctx.close();
                            text = "allStopped=" + seen.stream().noneMatch(MessageListenerContainer::isRunning);
                        } else {
                            var current = registry.getListenerContainers(); var b = new StringBuilder();
                            for (String id : registry.getListenerContainerIds()) {
                                var c = registry.getListenerContainer(id); var partitions = c.getAssignedPartitions();
                                b.append(id).append(partitions != null && !partitions.isEmpty() ? ":assigned;" : ":waiting;");
                            }
                            b.append("retiredRunning=").append(previous.stream().filter(c -> !current.contains(c) && c.isRunning()).count());
                            b.append(";otherSame=").append(other == registry.getListenerContainer("other")); text = b.toString();
                        }
                        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
                    });
                    server.start(); System.out.println("PORT=" + server.getAddress().getPort());
                }
            }
            """;
}
