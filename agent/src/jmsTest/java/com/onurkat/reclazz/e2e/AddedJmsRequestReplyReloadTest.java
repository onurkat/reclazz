/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.springframework.jms.core.JmsTemplate;
import java.io.File;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AddedJmsRequestReplyReloadTest {
    @TempDir Path tmp;
    static BrokerService broker;
    static String brokerUrl;
    @BeforeAll static void startBroker() throws Exception {
        broker = new BrokerService(); broker.setBrokerName("reclazz-jms-" + UUID.randomUUID());
        broker.setPersistent(false); broker.setUseJmx(false); broker.setUseShutdownHook(false);
        var connector = broker.addConnector("tcp://127.0.0.1:0"); broker.start(); broker.waitUntilStarted();
        brokerUrl = connector.getPublishableConnectString();
    }
    @AfterAll static void stopBroker() throws Exception { if (broker != null) { broker.stop(); broker.waitUntilStopped(); } }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void addedRepliesFollowSavesDestinationsRetirementAndRestore(boolean child) throws Exception { exercise(child); }

    private void exercise(boolean child) throws Exception {
        boolean existing = true;
        String root = "reclazz-" + UUID.randomUUID();
        String first = root + "-first", second = root + "-second", base = root + "-base", other = root + "-other";
        var producer = new JmsTemplate(new ActiveMQConnectionFactory(brokerUrl));
        producer.setReceiveTimeout(5000);
        var builder = WatchedApp.in(tmp).classpath(jmsClasspath())
                .jvmArgs("-Dtest.brokers=" + brokerUrl, "-Dtest.reply=" + root + "-configured")
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(existing, 0, first, base)).with("Other", other(other)).with("App", APP);
        if (child) builder.childClassLoader();
        try (var app = builder.start()) {
            app.awaitOrFail("PORT=", "JMS app did not start");
            app.awaitOrFail("Last-known-good bytecode cache:", "watcher did not finish initialization");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "child loader missing");
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
                    String defaultReply = stage == 5 ? root + "-configured" : topic + "-reply";
                    String chosenReply = stage == 2 ? root + "-reply-to" : defaultReply;
                    reply(producer, app, topic, "ADDED:" + stage + ":owner:" + topic + ":" + msg,
                            msg, defaultReply, chosenReply, stage == 2);
                    if (stage == 1) {
                        reply(producer, app, topic, "ADDED:1:owner:" + topic + ":retry", "retry",
                                defaultReply, defaultReply, false);
                        assertEquals(1, app.output().stream().filter("ROLLBACK:retry"::equals).count(), app.tail());
                    }
                } else assertFalse(http(port, "/state").contains("added:"), app.tail());
                if (existing) {
                    waitState(port, "existing:assigned", app);
                    deliver(producer, app, base, "BASE:save" + stage, "save" + stage);
                }
                deliver(producer, app, other, "OTHER:save" + stage, "save" + stage);
                assertTrue(http(port, "/state").contains("retiredRunning=0"), "old containers must stop: " + app.tail());
                assertTrue(http(port, "/state").contains("otherSame=true"), "unrelated container must retain identity");
                assertTrue(http(port, "/state").contains("reflected=false"), app.tail());
                if (stage >= 3) {
                    producer.convertAndSend(first, "old-topic-" + stage);
                    Thread.sleep(300);
                    assertFalse(app.output().stream().anyMatch(s -> s.startsWith("ADDED:") && s.contains("old-topic-")), app.tail());
                }
            }
            assertEquals("allStopped=true", http(port, "/close"));
            assertFalse(app.output().stream().anyMatch(s -> s.contains("@JmsListener is found by scanning")), app.tail());
            System.out.println("[jms-reply] child=" + child + " reply/correlation/replyTo/rollback/private-restore/placeholder passed; retired consumers, unrelated identity, reflection and context close verified");
        }
    }
    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Listener ") || s.contains("Structural reload: app.Listener ")).count();
    }
    private static void deliver(JmsTemplate producer, WatchedApp app, String topic, String line, String msg) throws Exception {
        producer.convertAndSend(topic, msg, m -> { m.setStringProperty("label", topic); return m; });
        assertTrue(app.awaits(line, 20), app.tail());
        Thread.sleep(200);
        assertEquals(1, app.output().stream().filter(line::equals).count(), app.tail());
    }
    private static void reply(JmsTemplate producer, WatchedApp app, String input, String bodyLine,
                              String value, String fallback, String destination, boolean replyTo) throws Exception {
        producer.send(input, session -> {
            var request = session.createTextMessage(value); request.setStringProperty("label", input);
            request.setJMSCorrelationID("correlation-" + value);
            if (replyTo) request.setJMSReplyTo(session.createQueue(destination));
            return request;
        });
        var response = producer.receive(destination);
        assertNotNull(response, app.tail());
        assertEquals(bodyLine.replace("ADDED:", "REPLY:"), ((javax.jms.TextMessage) response).getText(), app.tail());
        assertEquals("correlation-" + value, response.getJMSCorrelationID());
        assertTrue(app.awaits(bodyLine, 10), app.tail());
        assertEquals(1, app.output().stream().filter(bodyLine::equals).count(), app.tail());
        producer.setReceiveTimeout(200);
        try {
            assertNull(producer.receive(destination), "duplicate reply");
            if (replyTo) assertNull(producer.receive(fallback), "JMSReplyTo must override SendTo");
        } finally { producer.setReceiveTimeout(5000); }
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
    private static String jmsClasspath() {
        return String.join(File.pathSeparator, Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(s -> {
                    String n = Path.of(s).getFileName().toString();
                    return n.endsWith(".jar") && (n.startsWith("spring-") && !n.startsWith("spring-kafka")
                            || n.startsWith("activemq-client-") || n.startsWith("slf4j-api-")
                            || n.startsWith("jakarta.jms-api-") || n.startsWith("hawtbuf-"));
                }).toList());
    }
    private static String listener(boolean existing, int stage, String topic, String base) {
        String added = stage == 0 || stage == 4 ? "" : """
                %s
                @org.springframework.messaging.handler.annotation.SendTo("%s")
                %s String added(@org.springframework.messaging.handler.annotation.Payload String value,
                        @org.springframework.messaging.handler.annotation.Header("label") String topic, javax.jms.Message message) throws javax.jms.JMSException {
                    if (value.equals("retry") && !message.getJMSRedelivered()) {
                        System.out.println("ROLLBACK:retry"); throw new IllegalStateException("first attempt rolls back");
                    }
                    String result = "%d:" + prefix + ":" + topic + ":" + value;
                    System.out.println("ADDED:" + result); return "REPLY:" + result;
                }
                """.formatted(stage == 6 ? "" : "@org.springframework.jms.annotation.JmsListener(id=\"added\", destination=\"" + topic + "\")",
                        stage == 5 ? "${test.reply}" : topic + "-reply", stage == 5 ? "private" : "public", stage);
        return """
                package app;
                @org.springframework.stereotype.Component public class Listener {
                    private final String prefix = new String("owner");
                    public String use() { return "used"; }
                    %s
                    %s
                }
                """.formatted(existing ? """
                    @org.springframework.jms.annotation.JmsListener(id="existing", destination="%s")
                    public void original(String value) { System.out.println("BASE:" + value); }
                    """.formatted(base) : "", added);
    }
    private static String other(String topic) {
        return """
                package app;
                @org.springframework.stereotype.Component public class Other {
                    @org.springframework.jms.annotation.JmsListener(id="other", destination="%s")
                    public void original(String value) { System.out.println("OTHER:" + value); }
                }
                """.formatted(topic);
    }
    private static final String APP = """
            package app;
            import org.springframework.jms.listener.DefaultMessageListenerContainer;
            import org.springframework.jms.listener.MessageListenerContainer;
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
                public static void main(String[] args) throws Exception {
                    var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext(App.class);
                    var registry = ctx.getBean(org.springframework.jms.config.JmsListenerEndpointRegistry.class);
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
                            text = "allStopped=" + seen.stream().noneMatch(c -> ((DefaultMessageListenerContainer)c).isActive() || c.isRunning());
                        } else {
                            var current = registry.getListenerContainers(); var b = new StringBuilder();
                            for (String id : registry.getListenerContainerIds()) {
                                var c = (DefaultMessageListenerContainer) registry.getListenerContainer(id);
                                b.append(id).append(c.isRegisteredWithDestination() ? ":assigned;" : ":waiting;");
                            }
                            b.append("retiredRunning=").append(previous.stream().filter(c -> !current.contains(c) && (((DefaultMessageListenerContainer)c).isActive() || c.isRunning())).count());
                            b.append(";otherSame=").append(other == registry.getListenerContainer("other"));
                            b.append(";reflected=").append(java.util.Arrays.stream(Listener.class.getDeclaredMethods()).anyMatch(m -> m.getName().equals("added")));
                            text = b.toString();
                        }
                        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
                    });
                    server.start(); System.out.println("PORT=" + server.getAddress().getPort());
                }
            }
            """;
}
