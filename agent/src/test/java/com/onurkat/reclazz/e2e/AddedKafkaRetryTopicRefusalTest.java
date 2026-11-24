package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.test.EmbeddedKafkaBroker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddedKafkaRetryTopicRefusalTest {
    @TempDir Path tmp;
    static EmbeddedKafkaBroker broker;

    @BeforeAll static void startBroker() {
        broker = new EmbeddedKafkaBroker(1, false, 1);
        broker.afterPropertiesSet();
    }

    @AfterAll static void stopBroker() {
        if (broker != null) broker.destroy();
    }

    @Test void unrelatedRetryTopicAllowsAddedListenerWithoutRetryContainers() throws Exception {
        String topic = "reclazz-unrelated-" + UUID.randomUUID();
        String destination = topic;
        broker.addTopics(destination);
        var pf = new DefaultKafkaProducerFactory<Integer, String>(KafkaTestUtils.producerProps(broker));
        var producer = new KafkaTemplate<Integer, String>(pf);
        try (var app = WatchedApp.in(tmp)
                .classpath(WatchedApp.springClasspath() + File.pathSeparator + kafkaClasspath())
                .jvmArgs("-Dtest.brokers=" + broker.getBrokersAsString(), "-Dtest.retry.topic=" + topic + "-other")
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(topic, false)).with("App", appWithRetryConfiguration()).start()) {
            app.awaitOrFail("PORT=", "Kafka app did not start");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            app.awaitOrFail("] Watching ", "watcher did not start");
            app.rewrite("Listener", listener(topic, true));
            app.awaitOrFail("added @KafkaListener registered", "listener registration");
            producer.send(destination, "g1").get(10, TimeUnit.SECONDS);
            app.awaitOrFail("ADDED:g1", "delivery to the added listener");
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ids")).GET().build();
            assertEquals("retry-added", HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body());
        } finally {
            pf.destroy();
        }
    }

    @Test void matchingRetryTopicConfigurationIsRefusedBeforeContainerRegistration() throws Exception {
        String topic = "reclazz-retry-" + UUID.randomUUID();
        try (var app = WatchedApp.in(tmp)
                .classpath(WatchedApp.springClasspath() + File.pathSeparator + kafkaClasspath())
                .jvmArgs("-Dtest.brokers=" + broker.getBrokersAsString(), "-Dtest.retry.topic=" + topic)
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(topic, false)).with("App", appWithRetryConfiguration()).start()) {
            app.awaitOrFail("PORT=", "Kafka app did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            long before = reloads(app);
            app.rewrite("Listener", listener(topic, true));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (reloads(app) == before && !app.output().stream().anyMatch(s -> s.contains("matching Kafka retry-topic configuration requires a restart"))
                    && System.nanoTime() < until) Thread.sleep(25);
            assertEquals(before + 1, reloads(app), app.tail());
            assertTrue(app.output().stream().anyMatch(s -> s.contains("matching Kafka retry-topic configuration requires a restart")), app.tail());
            assertFalse(app.output().stream().anyMatch(s -> s.contains("@KafkaListener registered")), app.tail());
            assertFalse(app.output().stream().anyMatch(s -> s.contains("retry-added:assigned")), app.tail());
        }
    }

    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Listener ") || s.contains("Structural reload: app.Listener ")).count();
    }

    private static String listener(String topic, boolean added) {
        return """
                package app;
                @org.springframework.stereotype.Component public class Listener {
                    public String use() { return "used"; }
                    %s
                }
                """.formatted(added ? """
                    @org.springframework.kafka.annotation.KafkaListener(id="retry-added", topics="%s", groupId="retry-group")
                    public void added(String value) { System.out.println("ADDED:" + value); }
                    """.formatted(topic) : "");
    }

    private static String kafkaClasspath() {
        return String.join(File.pathSeparator, Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(s -> {
                    String name = Path.of(s).getFileName().toString();
                    return name.endsWith(".jar") && (name.startsWith("spring-") && !name.startsWith("spring-kafka-test")
                            || name.startsWith("kafka-clients-") || name.startsWith("slf4j-api-")
                            || name.startsWith("lz4-java-") || name.startsWith("snappy-java-") || name.startsWith("zstd-jni-"));
                }).toList());
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
                public static void main(String[] args) throws Exception {
                    var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext(App.class);
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                    server.createContext("/ids", exchange -> {
                        var registry = ctx.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class);
                        var ids = String.join(",", registry.getListenerContainerIds()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, ids.length);
                        exchange.getResponseBody().write(ids);
                        exchange.close();
                    });
                    server.start(); System.out.println("PORT=" + server.getAddress().getPort());
                }
            }
            """;
}
