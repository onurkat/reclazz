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

/** An added @KafkaListener whose topic is a ${property} placeholder resolves against
 *  the application's environment and consumes from the real broker. */
class AddedKafkaListenerPlaceholderReloadTest {
    @TempDir Path tmp;
    static EmbeddedKafkaBroker broker;
    @BeforeAll static void startBroker() { broker = new EmbeddedKafkaBroker(1, false, 1); broker.afterPropertiesSet(); }
    @AfterAll static void stopBroker() { if (broker != null) broker.destroy(); }

    @Test
    void placeholderTopicResolvesAgainstTheEnvironmentAndConsumes() throws Exception {
        String topic = "reclazz-ph-" + UUID.randomUUID();
        broker.addTopics(topic);
        var producerFactory = new DefaultKafkaProducerFactory<Integer,String>(KafkaTestUtils.producerProps(broker));
        var producer = new KafkaTemplate<Integer,String>(producerFactory);
        try (var app = WatchedApp.in(tmp).classpath(kafkaClasspath())
                .jvmArgs("-Dtest.brokers=" + broker.getBrokersAsString(), "-Dapp.topic=" + topic)
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(false)).with("App", APP).start()) {
            app.awaitOrFail("PORT=", "Kafka app did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));

            // Add a listener whose topic is "${app.topic}".
            long before = reloads(app);
            app.rewrite("Listener", listener(true));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (reloads(app) == before && System.nanoTime() < until) Thread.sleep(25);
            assertEquals(before + 1, reloads(app), app.tail());
            waitState(port, "added:assigned", app);

            producer.send(topic, "hello").get(10, TimeUnit.SECONDS);
            assertTrue(app.awaits("ADDED:hello", 20), app.tail());
            assertFalse(app.output().stream().anyMatch(s -> s.contains("@KafkaListener is found by scanning")), app.tail());

            // Remove it: the container is gone.
            app.rewrite("Listener", listener(false));
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (reloads(app) < before + 2 && System.nanoTime() < until) Thread.sleep(25);
            assertEquals(before + 2, reloads(app), app.tail());
            long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (http(port, "/state").contains("added:") && System.nanoTime() < stop) Thread.sleep(50);
            assertFalse(http(port, "/state").contains("added:"), app.tail());
            System.out.println("[kafka-placeholder] ${app.topic} resolved to " + topic + ", consumed, removed");
        } finally { producerFactory.destroy(); }
    }

    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Listener ") || s.contains("Structural reload: app.Listener ")).count();
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
    private static String listener(boolean added) {
        String method = added ? """
                @org.springframework.kafka.annotation.KafkaListener(id="added", topics="${app.topic}", groupId="ph-group")
                public void added(String value) { System.out.println("ADDED:" + value); }
                """ : "";
        return """
                package app;
                @org.springframework.stereotype.Component public class Listener {
                    public String use() { return "used"; }
                    %s
                }
                """.formatted(method);
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
                    var registry = ctx.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class);
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                    server.createContext("/", exchange -> {
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
