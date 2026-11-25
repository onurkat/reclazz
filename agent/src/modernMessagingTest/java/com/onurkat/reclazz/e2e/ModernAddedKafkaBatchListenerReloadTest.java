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

/** R36, Spring Framework 6 / Kafka 3: @KafkaListener methods added, edited and removed after startup on a batch container factory, next to a startup batch listener. */
class ModernAddedKafkaBatchListenerReloadTest {
    @TempDir Path tmp;
    static EmbeddedKafkaBroker broker;
    @BeforeAll static void startBroker() { broker = new org.springframework.kafka.test.EmbeddedKafkaZKBroker(1, false, 1); broker.afterPropertiesSet(); }
    @AfterAll static void stopBroker() { broker.destroy(); }

    @Test void addedListenerOnABatchFactoryReceivesBatchesThroughEditsAndRemoval() throws Exception {
        String topic = "reclazz-batch-" + UUID.randomUUID(), base = "reclazz-batch-base-" + UUID.randomUUID();
        broker.addTopics(topic, base);
        var producerFactory = new DefaultKafkaProducerFactory<Integer,String>(KafkaTestUtils.producerProps(broker));
        var producer = new KafkaTemplate<Integer,String>(producerFactory);
        try (var app = WatchedApp.in(tmp).classpath(kafkaClasspath()).jvmArgs("-Dtest.brokers=" + broker.getBrokersAsString())
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(topic, base, null)).with("App", APP).start()) {
            app.awaitOrFail("READY", "Kafka app did not start");
            app.awaitWatcherReady();
            deliver(producer, app, base, "BASE", "initial");
            // stage: prefix printed by the added listener, or null when it is removed
            String[] stages = {"ADDED", "EDITED", null, "AGAIN"};
            for (int i = 0; i < stages.length; i++) {
                long before = reloads(app);
                app.rewrite("Listener", listener(topic, base, stages[i]));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (reloads(app) == before && System.nanoTime() < until) Thread.sleep(25);
                assertEquals(before + 1, reloads(app), app.tail());
                assertFalse(app.output().stream().anyMatch(s -> s.contains("could not apply") || s.contains("deferred")), app.tail());
                String msg = "s" + i;
                if (stages[i] != null) deliver(producer, app, topic, stages[i], msg);
                else {
                    // Removed: the record waits at the group's committed offset for the re-added listener (same group).
                    producer.send(topic, msg).get(10, TimeUnit.SECONDS);
                    Thread.sleep(3000);
                    assertEquals(0, seen(app, null, msg), app.tail());
                }
                deliver(producer, app, base, "BASE", "b" + i);
            }
            assertEquals(1, seen(app, "AGAIN", "s2"), "the record sent while removed reaches the re-added body once: " + app.tail());
            for (String value : List.of("s0", "s1", "s2", "s3")) assertEquals(1, seen(app, null, value), value + ": " + app.tail());
        } finally { producerFactory.destroy(); }
    }
    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Listener") || s.contains("Structural reload: app.Listener ")).count();
    }
    /** Sends until the value has been seen under the prefix (each element of each printed batch counts). */
    private static void deliver(KafkaTemplate<Integer,String> producer, WatchedApp app, String topic, String prefix, String msg) throws Exception {
        producer.send(topic, msg).get(10, TimeUnit.SECONDS);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < until && seen(app, prefix, msg) == 0) Thread.sleep(50);
        Thread.sleep(200);
        assertEquals(1, seen(app, prefix, msg), "batch " + prefix + " must see " + msg + " once: " + app.tail());
    }
    /** How often a value appears among the elements of printed batches, under one prefix or (null) any added-listener prefix. */
    private static long seen(WatchedApp app, String prefix, String value) {
        return app.output().stream().filter(s -> prefix == null ? !s.startsWith("BASE:") && s.matches("[A-Z]+:\\d+:.*") : s.startsWith(prefix + ":"))
                .flatMap(s -> Arrays.stream(s.substring(s.indexOf(':', s.indexOf(':') + 1) + 1).split(",")))
                .filter(value::equals).count();
    }
    private static String kafkaClasspath() {
        return String.join(File.pathSeparator, Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(s -> {
                    String n = Path.of(s).getFileName().toString();
                    return n.endsWith(".jar") && (n.startsWith("spring-") && !n.startsWith("spring-kafka-test")
                            || n.startsWith("kafka-clients-") || n.startsWith("slf4j-api-") || n.startsWith("micrometer-") || n.startsWith("lz4-java-")
                            || n.startsWith("snappy-java-") || n.startsWith("zstd-jni-"));
                }).toList());
    }
    private static String listener(String topic, String base, String prefix) {
        String added = prefix == null ? "" : """
                    @org.springframework.kafka.annotation.KafkaListener(id="added", topics="%s", groupId="%s-group")
                    public void added(java.util.List<String> values) { System.out.println("%s:" + values.size() + ":" + String.join(",", values)); }
                """.formatted(topic, topic, prefix);
        return """
                package app;
                @org.springframework.stereotype.Component public class Listener {
                    public String use() { return "used"; }
                    @org.springframework.kafka.annotation.KafkaListener(id="base", topics="%s", groupId="%s-group")
                    public void base(java.util.List<String> values) { System.out.println("BASE:" + values.size() + ":" + String.join(",", values)); }
                %s}
                """.formatted(base, base, added);
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
                    factory.setBatchListener(true);
                    factory.getContainerProperties().setPollTimeout(100);
                    return factory;
                }
                public static void main(String[] args) throws Exception {
                    var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext(App.class);
                    ctx.getBean(Listener.class).use();
                    System.out.println("READY");
                    Thread.sleep(Long.MAX_VALUE);
                }
            }
            """;
}
