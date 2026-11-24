/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.command.ActiveMQTopic;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jms.core.JmsTemplate;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * R36: @JmsListener methods added, edited, removed and re-added after startup on a topic factory, plain and durable.
 * A durable subscription keeps the name a restart would give it ({@code app.Listener.added}), so messages published
 * while the listener is being replaced or removed are delivered once to the next body, and no extra subscription is left.
 */
class AddedJmsTopicListenerReloadTest {
    @TempDir Path tmp;
    static BrokerService broker;
    static String brokerUrl;
    @BeforeAll static void startBroker() throws Exception {
        broker = new BrokerService(); broker.setBrokerName("reclazz-jms-topic-" + UUID.randomUUID());
        broker.setPersistent(false); broker.setUseJmx(false); broker.setUseShutdownHook(false);
        var connector = broker.addConnector("tcp://127.0.0.1:0"); broker.start(); broker.waitUntilStarted();
        brokerUrl = connector.getPublishableConnectString();
    }
    @AfterAll static void stopBroker() throws Exception { if (broker != null) { broker.stop(); broker.waitUntilStopped(); } }

    @Test void plainTopicListenerFollowsEditsAndRemoval() throws Exception { exercise(false); }
    @Test void durableTopicListenerKeepsItsSubscriptionAcrossSavesAndRemoval() throws Exception { exercise(true); }
    @Test void anExplicitPlaceholderSubscriptionNamesTheDurableSubscription() throws Exception { exercise(true, "${test.sub}"); }

    private void exercise(boolean durable) throws Exception { exercise(durable, null); }
    private void exercise(boolean durable, String subscription) throws Exception {
        String topic = "reclazz-topic-" + UUID.randomUUID(), base = "reclazz-base-" + UUID.randomUUID();
        var cf = new ActiveMQConnectionFactory(brokerUrl);
        var topics = new JmsTemplate(cf); topics.setPubSubDomain(true);
        var queues = new JmsTemplate(cf);
        try (var app = WatchedApp.in(tmp).classpath(jmsClasspath())
                .jvmArgs("-Dtest.brokers=" + brokerUrl, "-Dtest.durable=" + durable, "-Dtest.sub=r36-custom-sub")
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(topic, base, null, subscription)).with("App", APP).start()) {
            app.awaitOrFail("READY", "JMS app did not start");
            app.awaitOrFail("Last-known-good bytecode cache:", "watcher did not finish initialization");
            AtomicBoolean sending = new AtomicBoolean(true);
            AtomicInteger sent = new AtomicInteger(), published = new AtomicInteger();
            // The startup queue listener on the same bean is replaced on every save and must lose nothing.
            Thread load = new Thread(() -> {
                while (sending.get()) {
                    queues.convertAndSend(base, "b" + sent.incrementAndGet());
                    // Once a durable subscription exists, topic messages published during saves must survive too.
                    if (durable && published.get() > 0) topics.convertAndSend(topic, "d" + published.getAndIncrement());
                    try { Thread.sleep(20); } catch (InterruptedException e) { return; }
                }
            });
            load.start();
            String[] stages = {"ADDED", "EDITED", null, "AGAIN"};
            try {
                for (int i = 0; i < stages.length; i++) {
                    long before = reloads(app);
                    app.rewrite("Listener", listener(topic, base, stages[i], subscription));
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (reloads(app) == before && System.nanoTime() < until) Thread.sleep(25);
                    assertEquals(before + 1, reloads(app), app.tail());
                    assertFalse(app.output().stream().anyMatch(s -> s.contains("could not apply") || s.contains("deferred")), app.tail());
                    if (stages[i] == null) continue;
                    // A plain topic only delivers to subscribers that exist: probe until this body is subscribed.
                    String probe = probe(app, topics, topic, stages[i] + i);
                    if (durable && published.get() == 0) published.set(1);
                    topics.convertAndSend(topic, "t" + i);
                    awaitOnce(app, "t" + i, stages[i]);
                    assertTrue(probe.startsWith("ITEM:" + stages[i] + ":"), probe);
                }
            } finally { sending.set(false); load.join(); }
            for (int k = 1; k <= sent.get(); k++) awaitOnce(app, "b" + k, "BASE");
            if (durable) {
                for (int k = 1; k < published.get(); k++) awaitOnce(app, "d" + k, null);
                Map<?,?> subs = ((org.apache.activemq.broker.region.Topic) broker.getRegionBroker().getDestinationMap()
                        .get(new ActiveMQTopic(topic))).getDurableTopicSubs();
                assertEquals(List.of(subscription == null ? "app.Listener.added" : "r36-custom-sub"), subs.keySet().stream()
                        .map(k -> ((org.apache.activemq.util.SubscriptionKey) k).getSubscriptionName()).toList(), app.tail());
            }
        }
    }
    private static String probe(WatchedApp app, JmsTemplate topics, String topic, String tag) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        for (int n = 0; System.nanoTime() < until; n++) {
            topics.convertAndSend(topic, "p" + tag + "-" + n);
            Thread.sleep(200);
            var hit = app.output().stream().filter(s -> s.startsWith("ITEM:") && s.contains(":p" + tag + "-")).findFirst();
            if (hit.isPresent()) return hit.get();
        }
        fail("no subscriber for " + tag + ": " + app.tail());
        return null;
    }
    private static void awaitOnce(WatchedApp app, String value, String prefix) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < until && count(app, value) == 0) Thread.sleep(25);
        Thread.sleep(50);
        assertEquals(1, count(app, value), "message " + value + " must be seen exactly once\n" + app.tail());
        if (prefix != null) assertTrue(app.output().contains("ITEM:" + prefix + ":" + value), "message " + value + " must reach " + prefix + "\n" + app.tail());
    }
    private static long count(WatchedApp app, String value) {
        return app.output().stream().filter(s -> s.startsWith("ITEM:") && s.endsWith(":" + value)).count();
    }
    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Listener") || s.contains("Structural reload: app.Listener ")).count();
    }
    private static String jmsClasspath() {
        return String.join(File.pathSeparator, Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(s -> {
                    String n = Path.of(s).getFileName().toString();
                    return n.endsWith(".jar") && (n.startsWith("spring-") && !n.startsWith("spring-kafka")
                            || n.startsWith("activemq-client-") || n.startsWith("slf4j-api-")
                            || n.startsWith("jakarta.jms-api-") || n.startsWith("hawtbuf-"));
                }).toList());
    }
    private static String listener(String topic, String base, String prefix, String subscription) {
        String sub = subscription == null ? "" : ", subscription=\"" + subscription + "\"";
        String added = prefix == null ? "" : """
                    @org.springframework.jms.annotation.JmsListener(id="added", destination="%s", containerFactory="topicFactory"%s)
                    public void added(String value) { System.out.println("ITEM:%s:" + value); }
                """.formatted(topic, sub, prefix);
        return """
                package app;
                @org.springframework.stereotype.Component public class Listener {
                    public String use() { return "used"; }
                    @org.springframework.jms.annotation.JmsListener(id="base", destination="%s")
                    public void base(String value) { System.out.println("ITEM:BASE:" + value); }
                %s}
                """.formatted(base, added);
    }
    private static final String APP = """
            package app;
            @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
            @org.springframework.context.annotation.ComponentScan("app")
            @org.springframework.jms.annotation.EnableJms
            public class App {
                @org.springframework.context.annotation.Bean
                public org.springframework.jms.config.DefaultJmsListenerContainerFactory jmsListenerContainerFactory() {
                    var factory = new org.springframework.jms.config.DefaultJmsListenerContainerFactory();
                    factory.setConnectionFactory(new org.apache.activemq.ActiveMQConnectionFactory(System.getProperty("test.brokers")));
                    factory.setReceiveTimeout(100L); factory.setSessionTransacted(true);
                    return factory;
                }
                @org.springframework.context.annotation.Bean
                public org.springframework.jms.config.DefaultJmsListenerContainerFactory topicFactory() {
                    var factory = new org.springframework.jms.config.DefaultJmsListenerContainerFactory();
                    factory.setConnectionFactory(new org.apache.activemq.ActiveMQConnectionFactory(System.getProperty("test.brokers")));
                    factory.setReceiveTimeout(100L); factory.setSessionTransacted(true);
                    factory.setPubSubDomain(true);
                    if (Boolean.getBoolean("test.durable")) { factory.setSubscriptionDurable(true); factory.setClientId("reclazz-durable"); }
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
