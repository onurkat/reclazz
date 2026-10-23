/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

/** Broker delivery through watched additions, with ordinary native late-registration controls. */
class AddedKafkaListenerOptionsReloadTest {
    @TempDir Path tmp;
    static EmbeddedKafkaBroker broker;
    @BeforeAll static void startBroker() { broker = new EmbeddedKafkaBroker(1, false, 2); broker.afterPropertiesSet(); }
    @AfterAll static void stopBroker() { if (broker != null) broker.destroy(); }

    @Test void explicitFactoryAndConcurrencyFollowDeliveryReloadAndRetirement() throws Exception {
        exercise("factory", true, true, false);
    }

    @ParameterizedTest
    @CsvSource({"true,true,true", "true,true,false", "true,false,true", "true,false,false",
            "false,true,true", "false,true,false", "false,false,true", "false,false,false"})
    void lateStartupAndIdGroupMatchNativeControls(boolean alwaysStart, boolean autoStart, boolean idIsGroup) throws Exception {
        exercise("omitted", alwaysStart, autoStart, idIsGroup);
    }

    @ParameterizedTest
    @CsvSource({"null,true", "null,false", "override,true", "override,false"})
    void nullAndDefaultGroupExpressionsMatchNativeControls(String group, boolean idIsGroup) throws Exception {
        exercise(group, false, true, idIsGroup);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void explicitEmptyGroupMatchesNativeAcrossBrokerReloads(boolean idIsGroup) throws Exception {
        exercise("empty", false, true, idIsGroup);
    }

    private void exercise(String mode, boolean alwaysStart, boolean autoStart, boolean idIsGroup) throws Exception {
        String root = "reclazz-options-" + UUID.randomUUID();
        String one = root + "-one", two = root + "-two", control = root + "-control";
        String factoryGroup = root + "-factory", override = root + "-override";
        String addedId = root + "-added", controlId = root + "-control-id";
        broker.addTopics(one, two, control);
        boolean factoryCase = mode.equals("factory");
        String group = switch (mode) {
            case "null" -> ", groupId=\"#{null}\"";
            case "empty" -> ", groupId=\"\"";
            case "override" -> ", groupId=\"" + override + "\"";
            default -> "";
        };
        String options = ", autoStartup=\"" + autoStart + "\", idIsGroup=" + idIsGroup + group;
        String expectedAddedGroup = mode.equals("override") ? override : idIsGroup ? addedId : factoryGroup;
        String expectedControlGroup = mode.equals("override") ? override : idIsGroup ? controlId : factoryGroup;
        var pf = new DefaultKafkaProducerFactory<Integer,String>(KafkaTestUtils.producerProps(broker));
        var producer = new KafkaTemplate<Integer,String>(pf);
        try (var app = WatchedApp.in(tmp).classpath(kafkaClasspath())
                .jvmArgs("-Dtest.brokers=" + broker.getBrokersAsString(), "-Dtest.group=" + factoryGroup,
                        "-Dtest.always=" + alwaysStart, "-Dtest.added.id=" + addedId, "-Dtest.control.id=" + controlId)
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(0, one, options, factoryCase, addedId))
                .with("Control", control(control, options, factoryCase, controlId)).with("App", APP).start()) {
            app.awaitOrFail("PORT=", "Kafka options app did not start");
            app.awaitOrFail("Last-known-good bytecode cache:", "watcher did not finish initialization");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            assertEquals("used", http(port, "/use"));
            boolean expectedRunning = alwaysStart || autoStart;
            assertContainer(port, "control", expectedRunning, autoStart, expectedControlGroup,
                    factoryCase ? 2 : 1, factoryCase ? 125 : 100, app);
            if (!expectedRunning) assertEquals("started", http(port, "/start-control"));
            awaitAssigned(port, "control", 2, factoryCase ? 2 : 1, app);
            deliver(producer, app, control, "CONTROL:initial", "initial", 0);
            String previousTopic = null;
            for (int stage = 1; stage <= 4; stage++) {
                String topic = stage == 2 ? two : one;
                assertEquals("saved", http(port, "/snapshot"));
                long before = reloads(app);
                app.rewrite("Listener", listener(stage, topic, options, factoryCase, addedId));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (reloads(app) == before && System.nanoTime() < until) Thread.sleep(25);
                assertEquals(before + 1, reloads(app), app.tail());
                assertEquals("false", http(port, "/original"), "added callback must remain on the companion path");
                if (stage < 4) {
                    int concurrency = factoryCase && stage != 2 ? 2 : 1;
                    int poll = factoryCase && stage != 2 ? 125 : 100;
                    assertContainer(port, "added", expectedRunning, autoStart, expectedAddedGroup, concurrency, poll, app);
                    if (!expectedRunning) {
                        int save = stage;
                        String held = "held-" + stage;
                        producer.send(topic, 0, 0, held).get(10, TimeUnit.SECONDS);
                        Thread.sleep(300);
                        assertFalse(app.output().stream().anyMatch(s -> s.equals("ADDED:" + save + ":" + held)), app.tail());
                        assertEquals("started", http(port, "/start-added"));
                        assertTrue(app.awaits("ADDED:" + stage + ":" + held, 20), app.tail());
                    }
                    awaitAssigned(port, "added", 2, concurrency, app);
                    for (int partition = 0; partition < 2; partition++) {
                        String message = "save" + stage + "-p" + partition;
                        deliver(producer, app, topic, "ADDED:" + stage + ":" + message, message, partition);
                    }
                    if (previousTopic != null && !topic.equals(previousTopic)) {
                        String stale = "stale-" + stage;
                        producer.send(previousTopic, stale).get(10, TimeUnit.SECONDS);
                        Thread.sleep(300);
                        assertFalse(app.output().stream().anyMatch(s -> s.startsWith("ADDED:") && s.endsWith(":" + stale)), app.tail());
                    }
                    previousTopic = topic;
                } else {
                    assertEquals("none", http(port, "/added"), app.tail());
                    producer.send(topic, "removed").get(10, TimeUnit.SECONDS);
                    Thread.sleep(300);
                    assertFalse(app.output().stream().anyMatch(s -> s.startsWith("ADDED:") && s.endsWith(":removed")), app.tail());
                }
                assertEquals("retiredRunning=0;controlSame=true", http(port, "/lifecycle"), app.tail());
                awaitAssigned(port, "control", 2, factoryCase ? 2 : 1, app);
                deliver(producer, app, control, "CONTROL:save" + stage, "save" + stage, 1);
            }
            assertEquals("allStopped=true", http(port, "/close"), app.tail());
            System.out.println("[kafka-options] " + mode + " always=" + alwaysStart + " auto=" + autoStart
                    + " idIsGroup=" + idIsGroup + "; delivery, native control, two partitions, edits, retirement and close observed");
        } finally { pf.destroy(); }
    }

    private static void assertContainer(int port, String id, boolean running, boolean auto, String group,
                                        int concurrency, int poll, WatchedApp app) throws Exception {
        assertEquals("running=" + running + ";auto=" + auto + ";group=" + group + ";concurrency=" + concurrency
                + ";poll=" + poll, http(port, "/" + id), app.tail());
    }
    private static void awaitAssigned(int port, String id, int partitions, int workers, WatchedApp app) throws Exception {
        String expected = "partitions=" + partitions + ";workers=" + workers + ";eachAssigned=true";
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        String state;
        do { state = http(port, "/assigned-" + id); if (state.equals(expected)) return; Thread.sleep(50); } while (System.nanoTime() < until);
        fail("Missing " + expected + " in " + state + "\n" + app.tail());
    }
    private static void deliver(KafkaTemplate<Integer,String> producer, WatchedApp app, String topic,
                                String line, String value, int partition) throws Exception {
        producer.send(topic, partition, 0, value).get(10, TimeUnit.SECONDS);
        assertTrue(app.awaits(line, 20), app.tail());
        Thread.sleep(200);
        assertEquals(1, app.output().stream().filter(line::equals).count(), app.tail());
    }
    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Listener ") || s.contains("Structural reload: app.Listener ")).count();
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
    private static String listener(int stage, String topic, String options, boolean factoryCase, String id) {
        String extra = factoryCase ? ", containerFactory=\"" + (stage == 2 ? "kafkaListenerContainerFactory" : "selectedFactory")
                + "\", concurrency=\"" + (stage == 2 ? 1 : 2) + "\"" : "";
        String method = stage == 0 || stage == 4 ? "" : """
                @org.springframework.kafka.annotation.KafkaListener(id="%s", topics="%s"%s%s)
                public void added(String value) { System.out.println("ADDED:%d:" + value); }
                """.formatted(id, topic, options, extra, stage);
        return """
                package app;
                @org.springframework.stereotype.Component public class Listener {
                    public String use() { return "used"; }
                    %s
                }
                """.formatted(method);
    }
    private static String control(String topic, String options, boolean factoryCase, String id) {
        return """
                package app;
                public class Control {
                    @org.springframework.kafka.annotation.KafkaListener(id="%s", topics="%s"%s%s)
                    public void original(String value) { System.out.println("CONTROL:" + value); }
                }
                """.formatted(id, topic, options, factoryCase ? ", containerFactory=\"selectedFactory\", concurrency=\"2\"" : "");
    }
    private static final String APP = """
            package app;
            import org.springframework.kafka.listener.MessageListenerContainer;
            import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
            @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
            @org.springframework.context.annotation.ComponentScan("app")
            @org.springframework.kafka.annotation.EnableKafka
            public class App {

                // A reflected companion Method is visible now. Native lookup still
                // proves whether the VM really declares the callback/factory slot.
                static boolean physicallyDeclared(Class<?> owner,String name) {
                    try {
                        var lookup=java.lang.invoke.MethodHandles.privateLookupIn(owner,java.lang.invoke.MethodHandles.lookup());
                        for(var method:owner.getDeclaredMethods()) if(method.getName().equals(name)) {
                            var signature=java.lang.invoke.MethodType.methodType(method.getReturnType(),method.getParameterTypes());
                            try {
                                if(java.lang.reflect.Modifier.isStatic(method.getModifiers()))lookup.findStatic(owner,name,signature);
                                else lookup.findVirtual(owner,name,signature);
                                return true;
                            } catch(NoSuchMethodException absent) { }
                        }
                        return false;
                    } catch(IllegalAccessException denied) {throw new AssertionError("native method probe was denied",denied);}
                }
                private static String id(String value) { return System.getProperty("test." + value + ".id", value); }
                @org.springframework.context.annotation.Bean
                public org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Integer,String> kafkaListenerContainerFactory() { return factory(100); }
                @org.springframework.context.annotation.Bean
                public org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Integer,String> selectedFactory() { return factory(125); }
                private org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Integer,String> factory(int poll) {
                    var props = new java.util.HashMap<String,Object>();
                    props.put("bootstrap.servers", System.getProperty("test.brokers"));
                    props.put("group.id", System.getProperty("test.group"));
                    props.put("key.deserializer", org.apache.kafka.common.serialization.IntegerDeserializer.class);
                    props.put("value.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class);
                    props.put("auto.offset.reset", "earliest"); props.put("enable.auto.commit", false);
                    var factory = new org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Integer,String>();
                    factory.setConsumerFactory(new org.springframework.kafka.core.DefaultKafkaConsumerFactory<>(props));
                    factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
                    factory.getContainerProperties().setPollTimeout(poll);
                    return factory;
                }
                public static void main(String[] args) throws Exception {
                    if(!physicallyDeclared(App.class,"main"))throw new AssertionError("native method probe missed a physical control");
                    var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext(App.class);
                    var registry = ctx.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class);
                    registry.setAlwaysStartAfterRefresh(Boolean.getBoolean("test.always"));
                    // Ordinary native annotation scanning after context refresh; no Reclazz callback registration API.
                    ctx.getBean(org.springframework.kafka.annotation.KafkaListenerAnnotationBeanPostProcessor.class)
                            .postProcessAfterInitialization(new Control(), "control");
                    var control = registry.getListenerContainer(id("control"));
                    var previous = new java.util.ArrayList<MessageListenerContainer>();
                    var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<MessageListenerContainer,Boolean>());
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                    server.createContext("/", exchange -> {
                        String path = exchange.getRequestURI().getPath(); String text;
                        if (path.equals("/use")) text = ctx.getBean(Listener.class).use();
                        else if (path.equals("/original")) text = "" + physicallyDeclared(Listener.class,"added");
                        else if (path.equals("/snapshot")) {
                            previous.clear(); previous.addAll(registry.getListenerContainers()); seen.addAll(previous); text = "saved";
                        } else if (path.startsWith("/start-")) {
                            registry.getListenerContainer(id(path.substring(7))).start(); text = "started";
                        } else if (path.equals("/close")) {
                            seen.addAll(registry.getListenerContainers()); ctx.close();
                            text = "allStopped=" + seen.stream().noneMatch(c -> c.isRunning() || c.isChildRunning());
                        } else if (path.equals("/lifecycle")) {
                            var current = registry.getListenerContainers();
                            text = "retiredRunning=" + previous.stream().filter(c -> !current.contains(c) && (c.isRunning() || c.isChildRunning())).count()
                                    + ";controlSame=" + (control == registry.getListenerContainer(id("control")));
                        } else if (path.startsWith("/assigned-")) {
                            var c = (ConcurrentMessageListenerContainer<?,?>) registry.getListenerContainer(id(path.substring(10)));
                            var partitions = c.getAssignedPartitions(); var workers = c.getContainers();
                            text = "partitions=" + (partitions == null ? 0 : partitions.size()) + ";workers=" + workers.size()
                                    + ";eachAssigned=" + workers.stream().allMatch(w -> w.getAssignedPartitions() != null && !w.getAssignedPartitions().isEmpty());
                        } else {
                            var c = (ConcurrentMessageListenerContainer<?,?>) registry.getListenerContainer(id(path.substring(1)));
                            text = c == null ? "none" : "running=" + c.isRunning() + ";auto=" + c.isAutoStartup()
                                    + ";group=" + c.getGroupId() + ";concurrency=" + c.getConcurrency() + ";poll=" + c.getContainerProperties().getPollTimeout();
                        }
                        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
                    });
                    server.start(); System.out.println("PORT=" + server.getAddress().getPort());
                }
            }
            """;
}
