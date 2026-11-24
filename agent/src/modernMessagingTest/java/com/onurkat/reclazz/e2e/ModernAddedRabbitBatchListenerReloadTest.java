/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.RabbitBroker;
import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * R36, Spring Framework 6 / Spring AMQP 3: @RabbitListener methods added, edited, removed and re-added after startup on a batch factory (consumer batching,
 * or batchListener alone), next to a startup batch listener on the same bean. Messages keep arriving during every save
 * and each one is seen exactly once: none is lost or duplicated while consumers are replaced. (A partial consumer batch
 * at cancel is unacknowledged and requeued by the broker; this test observes the outcome, not which consumer ran it.)
 */
class ModernAddedRabbitBatchListenerReloadTest {
    @TempDir Path tmp;
    static RabbitBroker broker;
    @BeforeAll static void startBroker() throws Exception { broker = RabbitBroker.start(); }
    @AfterAll static void stopBroker() throws Exception { if (broker != null) broker.close(); }

    @Test void addedConsumerBatchListenerFollowsEditsAndRemovalWithoutLosingMessages() throws Exception { exercise(true); }
    @Test void addedBatchListenerWithoutConsumerBatchingFollowsEditsAndRemoval() throws Exception { exercise(false); }

    private void exercise(boolean consumerBatch) throws Exception {
        String root = "reclazz-batch-" + UUID.randomUUID(), queue = root + "-added", base = root + "-base";
        var producer = new RabbitTemplate(broker.connectionFactory());
        var admin = new RabbitAdmin(broker.connectionFactory());
        for (String name : List.of(queue, base)) admin.declareQueue(new org.springframework.amqp.core.Queue(name, true, false, false));
        try (var app = WatchedApp.in(tmp).classpath(rabbitClasspath())
                .jvmArgs("-Dtest.rabbit.port=" + broker.port(), "-Dtest.rabbit.password=" + broker.password(), "-Dtest.consumerBatch=" + consumerBatch)
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(queue, base, null)).with("App", APP).start()) {
            app.awaitOrFail("READY", "Rabbit app did not start");
            app.awaitOrFail("Last-known-good bytecode cache:", "watcher did not finish initialization");
            AtomicBoolean sending = new AtomicBoolean(true);
            AtomicInteger sent = new AtomicInteger();
            Thread load = new Thread(() -> {
                while (sending.get()) {
                    producer.convertAndSend(base, "b" + sent.incrementAndGet());
                    try { Thread.sleep(20); } catch (InterruptedException e) { return; }
                }
            });
            load.start();
            String[] stages = {"ADDED", "EDITED", null, "AGAIN"};
            int queued = 0, pending = 0;
            try {
                for (int i = 0; i < stages.length; i++) {
                    long before = reloads(app);
                    app.rewrite("Listener", listener(queue, base, stages[i]));
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (reloads(app) == before && System.nanoTime() < until) Thread.sleep(25);
                    assertEquals(before + 1, reloads(app), app.tail());
                    assertFalse(app.output().stream().anyMatch(s -> s.contains("could not apply") || s.contains("deferred")), app.tail());
                    // While removed, messages wait in the queue and the re-added listener drains them.
                    for (int k = 0; k < 5; k++) producer.convertAndSend(queue, "q" + (queued++));
                    // Sent after the save completed, so only the current body may receive them.
                    if (stages[i] != null) { for (int k = pending; k < queued; k++) awaitOnce(app, "q" + k, stages[i]); pending = queued; }
                    else { Thread.sleep(1500); String last = ":q" + (queued - 1); assertTrue(app.output().stream().noneMatch(s -> s.startsWith("ITEM:") && s.endsWith(last)), app.tail()); }
                }
            } finally { sending.set(false); load.join(); }
            for (int k = 1; k <= sent.get(); k++) awaitOnce(app, "b" + k, "BASE");
        }
    }
    private static void awaitOnce(WatchedApp app, String value, String prefix) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < until && count(app, value) == 0) Thread.sleep(25);
        Thread.sleep(50);
        assertEquals(1, count(app, value), "message " + value + " must be seen exactly once\n" + app.tail());
        assertTrue(app.output().contains("ITEM:" + prefix + ":" + value), "message " + value + " must reach " + prefix + "\n" + app.tail());
    }
    private static long count(WatchedApp app, String value) {
        return app.output().stream().filter(s -> s.startsWith("ITEM:") && s.endsWith(":" + value)).count();
    }
    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Listener") || s.contains("Structural reload: app.Listener ")).count();
    }
    private static String rabbitClasspath() {
        return String.join(File.pathSeparator, Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(s -> {
                    String n = Path.of(s).getFileName().toString();
                    return n.endsWith(".jar") && (n.startsWith("spring-") && !n.startsWith("spring-kafka") && !n.startsWith("spring-jms")
                            || n.startsWith("amqp-client-") || n.startsWith("slf4j-api-") || n.startsWith("spring-retry-") || n.startsWith("micrometer-"));
                }).toList());
    }
    private static String listener(String queue, String base, String prefix) {
        String added = prefix == null ? "" : """
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(id="added", queues="%s")
                    public void added(java.util.List<String> values) { for (String v : values) System.out.println("ITEM:%s:" + v); }
                """.formatted(queue, prefix);
        return """
                package app;
                @org.springframework.stereotype.Component public class Listener {
                    public String use() { return "used"; }
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(id="base", queues="%s")
                    public void base(java.util.List<String> values) { for (String v : values) System.out.println("ITEM:BASE:" + v); }
                %s}
                """.formatted(base, added);
    }
    private static final String APP = """
            package app;
            @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
            @org.springframework.context.annotation.ComponentScan("app")
            @org.springframework.amqp.rabbit.annotation.EnableRabbit
            public class App {
                @org.springframework.context.annotation.Bean
                public org.springframework.amqp.rabbit.connection.CachingConnectionFactory rabbitConnectionFactory() {
                    var cf = new org.springframework.amqp.rabbit.connection.CachingConnectionFactory("127.0.0.1", Integer.parseInt(System.getProperty("test.rabbit.port")));
                    cf.setUsername("reclazz"); cf.setPassword(System.getProperty("test.rabbit.password")); return cf;
                }
                @org.springframework.context.annotation.Bean
                public org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(org.springframework.amqp.rabbit.connection.CachingConnectionFactory cf) {
                    var factory = new org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory();
                    factory.setConnectionFactory(cf);
                    factory.setBatchListener(true);
                    if (Boolean.getBoolean("test.consumerBatch")) { factory.setConsumerBatchEnabled(true); factory.setBatchSize(10); factory.setReceiveTimeout(500L); }
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
