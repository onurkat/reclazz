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

/** Native self-referencing topic/group expressions across real broker reloads. */
class AddedKafkaListenerExpressionReloadTest {
    @TempDir Path tmp;
    static EmbeddedKafkaBroker broker;
    @BeforeAll static void startBroker() { broker = new EmbeddedKafkaBroker(1, false, 1); broker.afterPropertiesSet(); }
    @AfterAll static void stopBroker() { if (broker != null) broker.destroy(); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void selfReferencingExpressionsRouteTopicsAndGroupsAcrossSaves(boolean child) throws Exception {
        String suffix = UUID.randomUUID().toString();
        String one = "reclazz-expression-one-" + suffix, two = "reclazz-expression-two-" + suffix;
        String group = "reclazz-expression-group-" + suffix;
        broker.addTopics(one, two);
        var producerFactory = new DefaultKafkaProducerFactory<Integer,String>(KafkaTestUtils.producerProps(broker));
        var producer = new KafkaTemplate<Integer,String>(producerFactory);
        var builder = WatchedApp.in(tmp).classpath(kafkaClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder
                .jvmArgs("-Dtest.brokers=" + broker.getBrokersAsString(), "-Dapp.topic=" + one,
                        "-Dapp.one=" + one, "-Dapp.two=" + two, "-Dapp.group=" + group + "-1",
                        "-Dapp.group.two=" + group + "-2", "-Dapp.group.three=" + group + "-3")
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(0)).with("App", APP).start()) {
            app.awaitOrFail("PORT=", "Kafka app did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "child loader missing");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            for (int version = 1; version <= 5; version++) {
                if (version == 2) {
                    assertEquals("moved", http(port, "/move"));
                    assertEquals(one, http(port, "/topics"));
                    assertEquals(group + "-1", http(port, "/group"), "property changes alone do not reroute the consumer");
                }
                if (version == 4) assertEquals("recovery", http(port, "/recover"));
                long before = reloads(app);
                app.rewrite("Listener", listener(version));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (reloads(app) == before && System.nanoTime() < until) Thread.sleep(25);
                assertEquals(before + 1, reloads(app), app.tail());
                assertEquals("false", http(port, "/original"), "added callback must use the companion path");
                if (version == 3 || version == 5) {
                    assertEquals("none", http(port, "/topics"), app.tail());
                    assertEquals("none", http(port, "/group"), app.tail());
                } else {
                    waitState(port, "added:assigned", app);
                    List<String> topics = version == 1 ? List.of(one) : version == 2 ? List.of(two) : List.of(one, two);
                    assertEquals(String.join(",", topics), http(port, "/topics"), app.tail());
                    assertEquals(group + "-" + (version == 4 ? 3 : version), http(port, "/group"), app.tail());
                    if (version == 4) {
                        // A fresh group correctly replays the retained records on both topics.
                        assertTrue(app.awaits("ADDED4:v1-q0", 20), app.tail());
                        assertTrue(app.awaits("ADDED4:v2-q0", 20), app.tail());
                    }
                    for (int i = 0; i < topics.size(); i++) {
                        String message = "v" + version + "-q" + i;
                        producer.send(topics.get(i), message).get(10, TimeUnit.SECONDS);
                        assertTrue(app.awaits("ADDED" + version + ":" + message, 20), app.tail());
                    }
                }
                System.out.println("[kafka-expression] child=" + child + " version=" + version
                        + " topics=" + http(port, "/topics") + " group=" + http(port, "/group") + " originalMethod=false");
            }
            assertEquals(6, app.output().stream().filter(line -> line.startsWith("ADDED")).count(), app.tail());
            assertTrue(app.output().stream().anyMatch(line -> line.contains("must resolve to a String")), app.tail());
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
    private static String listener(int version) {
        String topics = version == 4 ? "#{__listener.topics}" : "#{__listener.topic}";
        String group = version == 3 ? "#{42}" : "#{__listener.group}";
        String method = version == 0 || version == 5 ? "" : """
                @org.springframework.kafka.annotation.KafkaListener(id="added", topics="%s", groupId="%s")
                public void added(String value) { System.out.println("ADDED%d:" + value); }
                """.formatted(topics, group, version);
        return """
                package app;
                @org.springframework.stereotype.Component public class Listener {
                    public int version() { return %d; }
                    public String getTopic() { return System.getProperty("app.topic"); }
                    public String getGroup() { return System.getProperty("app.group"); }
                    public java.util.List<String> getTopics() { return java.util.List.of(System.getProperty("app.one"), System.getProperty("app.two")); }
                    %s
                }
                """.formatted(version, method);
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
                        String path = exchange.getRequestURI().getPath();
                        if (path.equals("/move")) {
                            System.setProperty("app.topic", System.getProperty("app.two"));
                            System.setProperty("app.group", System.getProperty("app.group.two")); b.append("moved");
                        } else if (path.equals("/recover")) {
                            System.setProperty("app.group", System.getProperty("app.group.three")); b.append("recovery");
                        } else if (path.equals("/original")) {
                            b.append(java.util.Arrays.stream(Listener.class.getDeclaredMethods()).anyMatch(m -> m.getName().equals("added")));
                        } else if (path.equals("/topics")) {
                            var c = registry.getListenerContainer("added");
                            b.append(c == null ? "none" : String.join(",", c.getContainerProperties().getTopics()));
                        } else if (path.equals("/group")) {
                            var c = registry.getListenerContainer("added");
                            b.append(c == null ? "none" : c.getGroupId());
                        } else for (String id : registry.getListenerContainerIds()) {
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
