package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModernAddedKafkaAnnotationEnhancerReloadTest {
    @TempDir Path tmp;
    static EmbeddedKafkaBroker broker;

    @BeforeAll static void startBroker() {
        broker = new org.springframework.kafka.test.EmbeddedKafkaZKBroker(1, false, 1);
        broker.afterPropertiesSet();
    }

    @AfterAll static void stopBroker() {
        if (broker != null) broker.destroy();
    }

    @Test void enhancerAppliesToAddedListenerAndExistingListenerAfterRefresh() throws Exception {
        String root = "reclazz-" + UUID.randomUUID();
        String original = root + "-original";
        String added = root + "-added";
        broker.addTopics(original, original + "-enhanced", added, added + "-enhanced");
        var producerFactory = new DefaultKafkaProducerFactory<Integer, String>(KafkaTestUtils.producerProps(broker));
        var producer = new KafkaTemplate<Integer, String>(producerFactory);
        try (var app = WatchedApp.in(tmp)
                .classpath(WatchedApp.springClasspath() + File.pathSeparator + kafkaClasspath())
                .jvmArgs("-Dtest.brokers=" + broker.getBrokersAsString())
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(original, added, false))
                .with("App", APP).start()) {
            app.awaitOrFail("PORT=", "Kafka app did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            awaitState(port, "original:assigned", app);
            deliver(producer, app, original + "-enhanced", "BASE:initial");

            long before = reloads(app);
            app.rewrite("Listener", listener(original, added, true));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (reloads(app) == before && System.nanoTime() < until) Thread.sleep(25);
            assertEquals(before + 1, reloads(app), app.tail());
            assertTrue(app.output().stream().noneMatch(s -> s.contains("could not apply") || s.contains("deferred")), app.tail());

            awaitState(port, "original:assigned", app);
            awaitState(port, "added:assigned", app);
            deliver(producer, app, original + "-enhanced", "BASE:after-refresh");
            deliver(producer, app, added + "-enhanced", "ADDED:after-refresh");
        } finally {
            producerFactory.destroy();
        }
    }

    @Test void helperRefreshRebindsAddedRetryListener() throws Exception {
        AddedKafkaRetryTopicRefusalTest.retryDependency(tmp, broker.getBrokersAsString(), false);
    }

    @Test void lateAttachPreservesUnobservedStartupRetryGroup() throws Exception {
        AddedKafkaRetryTopicRefusalTest.retryDependency(tmp, broker.getBrokersAsString(), true, true);
    }

    @Test void helperRefreshRebindsStartupRetryListener() throws Exception {
        AddedKafkaRetryTopicRefusalTest.retryDependency(tmp, broker.getBrokersAsString(), true);
    }

    @Test void partialRetryRegistrationLeavesNoContainersOrProducts() throws Exception {
        AddedKafkaRetryTopicRefusalTest.partialRegistration(tmp, broker.getBrokersAsString());
    }

    @Test void startFailureRollsBackTheWholeRetryGroup() throws Exception {
        AddedKafkaRetryTopicRefusalTest.startFailure(tmp, broker.getBrokersAsString(), false);
    }

    @Test void stopTimeoutQuarantinesRetryGroupAndRecordsRestart() throws Exception {
        AddedKafkaRetryTopicRefusalTest.startFailure(tmp, broker.getBrokersAsString(), true);
    }

    @Test void retryDeliveryMatchesNativeStartupAcrossEditAndRemoval() throws Exception {
        AddedKafkaRetryTopicRefusalTest.retryLifecycle(tmp, broker.getBrokersAsString());
    }

    @Test void unrelatedRetryTopicAllowsAddedListenerWithoutRetryContainers() throws Exception {
        String topic = "reclazz-unrelated-" + UUID.randomUUID();
        String destination = topic + "-enhanced";
        broker.addTopics(destination, topic + "-base-enhanced");
        var pf = new DefaultKafkaProducerFactory<Integer, String>(KafkaTestUtils.producerProps(broker));
        var producer = new KafkaTemplate<Integer, String>(pf);
        try (var app = WatchedApp.in(tmp)
                .classpath(WatchedApp.springClasspath() + File.pathSeparator + kafkaClasspath())
                .jvmArgs("-Dtest.brokers=" + broker.getBrokersAsString(), "-Dtest.retry.topic=" + topic + "-other")
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(topic + "-base", topic, false)).with("App", appWithRetryConfiguration()).start()) {
            app.awaitOrFail("PORT=", "Kafka app did not start");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            app.awaitOrFail("] Watching ", "watcher did not start");
            app.rewrite("Listener", listener(topic + "-base", topic, true));
            app.awaitOrFail("added @KafkaListener registered", "listener registration");
            producer.send(destination, "g1").get(10, TimeUnit.SECONDS);
            app.awaitOrFail("ADDED:g1", "delivery to the added listener");
            awaitState(port, "added:assigned", app);
            awaitState(port, "original:assigned", app);
            assertEquals(java.util.List.of("added:assigned", "original:assigned"),
                    Arrays.stream(currentState(port).split(";")).sorted().toList());
        } finally {
            pf.destroy();
        }
    }

    @Test void matchingRetryTopicConfigurationInstallsTheNativeGraph() throws Exception {
        String topic = "reclazz-retry-" + UUID.randomUUID();
        String original = topic + "-base";
        broker.addTopics(original, original + "-enhanced");
        try (var app = WatchedApp.in(tmp)
                .classpath(WatchedApp.springClasspath() + File.pathSeparator + kafkaClasspath())
                .jvmArgs("-Dtest.brokers=" + broker.getBrokersAsString(), "-Dtest.retry.topic=" + topic + "-enhanced")
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(original, topic, false))
                .with("App", appWithRetryConfiguration()).start()) {
            app.awaitOrFail("PORT=", "Kafka app did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            long before = reloads(app);
            app.rewrite("Listener", listener(original, topic, true));
            app.awaitOrFail("added @KafkaListener registered", "matched retry configuration must register");
            assertEquals(before + 1, reloads(app), app.tail());
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            awaitState(port, "added:assigned", app);
            awaitState(port, "original:assigned", app);
            var ids = Arrays.stream(currentState(port).split(";")).map(value -> value.split(":")[0]).toList();
            assertEquals(4, ids.size(), ids.toString());
            assertTrue(ids.stream().anyMatch(id -> id.startsWith("added-retry")), ids.toString());
            assertTrue(ids.stream().anyMatch(id -> id.startsWith("added-dlt")), ids.toString());
        }
    }

    private static String appWithRetryConfiguration() {
        return APP.replace("public static void main", """
                @org.springframework.context.annotation.Bean
                public org.springframework.kafka.core.KafkaTemplate<Integer,String> retryTemplate() {
                    var props = new java.util.HashMap<String,Object>();
                    props.put("bootstrap.servers", System.getProperty("test.brokers"));
                    props.put("key.serializer", org.apache.kafka.common.serialization.IntegerSerializer.class);
                    props.put("value.serializer", org.apache.kafka.common.serialization.StringSerializer.class);
                    return new org.springframework.kafka.core.KafkaTemplate<>(new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(props));
                }
                @org.springframework.context.annotation.Bean
                public org.springframework.kafka.retrytopic.RetryTopicConfiguration retryConfiguration(
                        org.springframework.kafka.core.KafkaTemplate<Integer,String> retryTemplate) {
                    return org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder.newInstance()
                            .includeTopic(System.getProperty("test.retry.topic")).maxAttempts(2).create(retryTemplate);
                }
                public static void main
                """);
    }

    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Listener ") || s.contains("Structural reload: app.Listener ")).count();
    }

    private static void deliver(KafkaTemplate<Integer, String> producer, WatchedApp app, String topic, String line) throws Exception {
        producer.send(topic, line.substring(line.indexOf(':') + 1)).get(10, TimeUnit.SECONDS);
        assertTrue(app.awaits(line, 20), app.tail());
        Thread.sleep(200);
        assertEquals(1, app.output().stream().filter(line::equals).count(), app.tail());
    }

    private static void awaitState(int port, String value, WatchedApp app) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        String state;
        do {
            state = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/state"))
                    .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            if (state.contains(value)) return;
            Thread.sleep(50);
        } while (System.nanoTime() < until);
        throw new AssertionError("Missing " + value + " in " + state + "\n" + app.tail());
    }

    private static String currentState(int port) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/state"))
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String listener(String original, String added, boolean withAdded) {
        return """
                package app;
                @org.springframework.stereotype.Component public class Listener {
                    @org.springframework.kafka.annotation.KafkaListener(id="original", topics="%s", groupId="%s-group")
                    public void original(String value) { System.out.println("BASE:" + value); }
                    %s
                }
                """.formatted(original, original, withAdded ? """
                    @org.springframework.kafka.annotation.KafkaListener(id="added", topics="%s", groupId="%s-group")
                    public void added(String value) { System.out.println("ADDED:" + value); }
                    """.formatted(added, added) : "");
    }

    private static String kafkaClasspath() {
        return String.join(File.pathSeparator, Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(s -> {
                    String name = Path.of(s).getFileName().toString();
                    return name.endsWith(".jar") && (name.startsWith("spring-") && !name.startsWith("spring-kafka-test")
                            || name.startsWith("kafka-clients-") || name.startsWith("slf4j-api-")
                            || name.startsWith("micrometer-") || name.startsWith("lz4-java-")
                            || name.startsWith("snappy-java-") || name.startsWith("zstd-jni-"));
                }).toList());
    }

    private static final String APP = """
            package app;
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
                public static void main(String[] args) throws Exception {
                    var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext(App.class);
                    var registry = ctx.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class);
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                    server.createContext("/state", exchange -> {
                        var b = new StringBuilder();
                        for (String id : registry.getListenerContainerIds()) {
                            var c = registry.getListenerContainer(id); var partitions = c.getAssignedPartitions();
                            b.append(id).append(partitions != null && !partitions.isEmpty() ? ":assigned;" : ":waiting;");
                        }
                        byte[] bytes = b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
                    });
                    server.start(); System.out.println("PORT=" + server.getAddress().getPort());
                }
            }
            """;
}
