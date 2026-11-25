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

    @Test void serviceReceiverFollowsBrokerDeliveryAndRepeatedReloads() throws Exception { exercise(true, "Service"); }
    @Test void repositoryReceiverFollowsBrokerDeliveryAndRepeatedReloads() throws Exception { exercise(true, "Repository"); }
    @Test void nativeAnnotationEnhancerAppliesToAddedAndRescannedListeners() throws Exception {
        exercise(true, "Component", true);
    }

    private void exercise(boolean existing) throws Exception { exercise(existing, "Component", false); }
    private void exercise(boolean existing, String stereotype) throws Exception {
        exercise(existing, stereotype, false);
    }
    private void exercise(boolean existing, String stereotype, boolean enhanced) throws Exception {
        String root = "reclazz-" + UUID.randomUUID();
        String first = root + "-first", second = root + "-second", base = root + "-base", other = root + "-other";
        broker.addTopics(first, second, base, other, first + "-enhanced", second + "-enhanced", base + "-enhanced");
        var producerFactory = new DefaultKafkaProducerFactory<Integer,String>(KafkaTestUtils.producerProps(broker));
        var producer = new KafkaTemplate<Integer,String>(producerFactory);
        try (var app = WatchedApp.in(tmp).classpath(kafkaClasspath())
                .jvmArgs("-Dtest.brokers=" + broker.getBrokersAsString())
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(existing, 0, first, base, stereotype)).with("Other", other(other)).with("App", app(enhanced)).start()) {
            app.awaitOrFail("PORT=", "Kafka app did not start");
            app.awaitWatcherReady();
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            assertEquals("used", http(port, "/use"));
            waitState(port, "other:assigned", app);
            if (existing) waitState(port, "existing:assigned", app);
            deliver(producer, app, other, "OTHER:initial", "initial");
            if (existing) deliver(producer, app, enhanced ? base + "-enhanced" : base, "BASE:initial", "initial");
            for (int stage = 1; stage <= 6; stage++) {
                http(port, "/snapshot");
                String topic = stage >= 3 ? second : first;
                long before = reloads(app);
                app.rewrite("Listener", listener(existing, stage, topic, base, stereotype));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (reloads(app) == before && System.nanoTime() < until) Thread.sleep(25);
                assertEquals(before + 1, reloads(app), app.tail());
                boolean active = stage != 4 && stage != 6;
                if (active) {
                    waitState(port, "added:assigned", app);
                    String msg = "save" + stage;
                    String effectiveTopic = enhanced ? topic + "-enhanced" : topic;
                    deliver(producer, app, effectiveTopic, "ADDED:" + stage + ":owner:" + effectiveTopic + ":" + msg, msg);
                } else assertFalse(http(port, "/state").contains("added:"), app.tail());
                if (existing) {
                    waitState(port, "existing:assigned", app);
                    deliver(producer, app, enhanced ? base + "-enhanced" : base, "BASE:save" + stage, "save" + stage);
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
    @Test void nativeFactoryPoliciesSurviveRegistrationAndRemoval() throws Exception {
        nativeFactoryPolicies(tmp, broker.getBrokersAsString(), true);
    }

    static void nativeFactoryPolicies(Path root, String brokers, boolean legacy) throws Exception {
        // One native record factory and one native batch-to-record factory, for each pinned Kafka version.
        for (boolean batch : new boolean[]{false, true}) {
            String policy = """
                    factory.setRecordFilterStrategy(record -> {
                        if (!"drop".equals(record.value())) return false;
                        System.out.println("FILTERED:drop");
                        return true;
                    });
                    factory.setContainerCustomizer(container -> {
                        container.getContainerProperties().setPollTimeout(123);
                        System.out.println("CUSTOMIZED:" + container.getListenerId());
                    });
                    """;
            if (batch) policy += """
                    factory.setBatchListener(true);
                    factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.BATCH);
                    factory.setBatchToRecordAdapter((messages, records, acknowledgment, consumer, callback) -> {
                        System.out.println("BATCH-ADAPTER:" + records.size());
                        new org.springframework.kafka.listener.adapter.DefaultBatchToRecordAdapter<Integer,String>()
                            .adapt(messages, records, acknowledgment, consumer, callback);
                    });
                    """;
            else if (legacy) policy += "factory.setRetryTemplate(new org.springframework.retry.support.RetryTemplate());";
            if (!batch) policy = policy.replace("container.getContainerProperties().setPollTimeout(123);", """
                    container.getContainerProperties().setPollTimeout(123);
                    var original = (org.springframework.kafka.listener.MessageListener<Integer,String>) container.getContainerProperties().getMessageListener();
                    var wrapper = new org.springframework.kafka.listener.adapter.FilteringMessageListenerAdapter<Integer,String>(original, record -> {
                        System.out.println("WRAPPER:" + record.value());
                        return false;
                    }) { };
                    container.setupMessageListener(wrapper);
                    """);
            String fixture = APP.replace("return factory;", policy + "return factory;");
            for (boolean nativeStart : new boolean[]{true, false}) {
                String topic = "factory-" + UUID.randomUUID(), base = topic + "-base", otherTopic = topic + "-other";
                String initial = listener(true, 0, topic, base, "Component");
                String added = listener(true, 1, topic, base, "Component");
                if (legacy && !batch) {
                    added = added.replace("System.out.println(\"ADDED:", """
                            if (value.equals("keep")) {
                                int attempt = org.springframework.retry.support.RetrySynchronizationManager.getContext().getRetryCount();
                                System.out.println("RETRY:" + attempt);
                                if (attempt == 0) throw new IllegalStateException("first invocation");
                            }
                            System.out.println("ADDED:""");
                }
                var builder = WatchedApp.in(root.resolve(topic)).classpath(System.getProperty("java.class.path"))
                        .jvmArgs("-Dtest.brokers=" + brokers)
                        .with("Listener", nativeStart ? added : initial).with("Other", other(otherTopic)).with("App", fixture);
                if (nativeStart) builder.withoutAgent();
                else builder.agentArgs("startupDelaySec=1,debounceMs=100,verbose=true");
                var props = Map.<String,Object>of("bootstrap.servers", brokers,
                        "key.serializer", org.apache.kafka.common.serialization.IntegerSerializer.class,
                        "value.serializer", org.apache.kafka.common.serialization.StringSerializer.class);
                try (var producer = new org.apache.kafka.clients.producer.KafkaProducer<Integer,String>(props);
                     var app = builder.start()) {
                    app.awaitOrFail("PORT=", "Kafka factory startup");
                    int port = Integer.parseInt(app.latest("PORT=").substring(5));
                    if (!nativeStart) {
                        app.awaitWatcherReady();
                        app.rewrite("Listener", added);
                    }
                    waitState(port, "added:assigned", app);
                    app.awaitOrFail("CUSTOMIZED:added", "native factory customization");
                    for (String value : List.of("drop", "keep"))
                        producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic, 0, 0, value)).get(10, TimeUnit.SECONDS);
                    String delivered = "ADDED:1:owner:" + topic + ":keep";
                    app.awaitOrFail(delivered, "factory callback delivery");
                    assertEquals(1, app.output().stream().filter(delivered::equals).count(), app.tail());
                    assertEquals(1, app.output().stream().filter("FILTERED:drop"::equals).count(), app.tail());
                    assertFalse(app.output().contains("ADDED:1:owner:" + topic + ":drop"), app.tail());
                    if (batch) assertTrue(app.output().stream().anyMatch(line -> line.startsWith("BATCH-ADAPTER:")), app.tail());
                    else assertEquals(1, app.output().stream().filter("WRAPPER:keep"::equals).count(), app.tail());
                    if (legacy && !batch) {
                        assertEquals(1, app.output().stream().filter("RETRY:0"::equals).count(), app.tail());
                        assertEquals(1, app.output().stream().filter("RETRY:1"::equals).count(), app.tail());
                    }
                    if (!nativeStart) {
                        http(port, "/snapshot");
                        long before = reloads(app);
                        app.rewrite("Listener", initial);
                        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                        while (reloads(app) == before && System.nanoTime() < until) Thread.sleep(25);
                        assertEquals(before + 1, reloads(app), app.tail());
                        String state = http(port, "/state");
                        assertFalse(state.contains("added:"), state);
                        assertTrue(state.contains("retiredRunning=0") && state.contains("otherSame=true"), state);
                    }
                    assertEquals("allStopped=true", http(port, "/close"));
                }
            }
        }
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
    private static String app(boolean enhancer) {
        if (!enhancer) return APP;
        String bean = """
                @org.springframework.context.annotation.Bean
                public org.springframework.kafka.annotation.KafkaListenerAnnotationBeanPostProcessor.AnnotationEnhancer topicEnhancer() {
                    return (attributes, element) -> {
                        if (element instanceof java.lang.reflect.Method method
                                && method.getDeclaringClass().getName().startsWith("app.Listener")) {
                            String[] topics = (String[]) attributes.get("topics");
                            attributes.put("topics", java.util.Arrays.stream(topics).map(topic -> topic + "-enhanced").toArray(String[]::new));
                        }
                        return attributes;
                    };
                }
                """;
        return APP.replace("public static void main", bean + "\npublic static void main");
    }
    private static String listener(boolean existing, int stage, String topic, String base, String stereotype) {
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
                @org.springframework.stereotype.%s public class Listener {
                    private final String prefix = new String("owner");
                    public String use() { return "used"; }
                    %s
                    %s
                }
                """.formatted(stereotype, existing ? """
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
