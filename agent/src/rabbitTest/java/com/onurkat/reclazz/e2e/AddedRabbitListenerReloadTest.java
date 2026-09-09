/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.onurkat.reclazz.e2e.harness.RabbitBroker;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import java.io.File;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AddedRabbitListenerReloadTest {
    @TempDir Path tmp;
    static RabbitBroker broker;
    @BeforeAll static void startBroker() throws Exception { broker = RabbitBroker.start(); }
    @AfterAll static void stopBroker() throws Exception { if (broker != null) broker.close(); }
    @Test void firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore() throws Exception { exercise(false); }
    @Test void addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone() throws Exception { exercise(true); }

    @Test void addingBesideAnIdlessExistingListenerPreservesItsConsumerAcrossSaves() throws Exception { exercise(true, true); }

    private void exercise(boolean existing) throws Exception { exercise(existing, false); }
    private void exercise(boolean existing, boolean idless) throws Exception {
        String root = "reclazz-" + UUID.randomUUID();
        String first = root + "-first", second = root + "-second", base = root + "-base", other = root + "-other";
        var producer = new RabbitTemplate(broker.connectionFactory());
        var admin = new RabbitAdmin(broker.connectionFactory());
        for (String name : List.of(first, second, base, other)) admin.declareQueue(new org.springframework.amqp.core.Queue(name, true, false, false));
        try (var app = WatchedApp.in(tmp).classpath(rabbitClasspath())
                .jvmArgs("-Dtest.rabbit.port=" + broker.port(), "-Dtest.rabbit.password=" + broker.password(), "-Dtest.rabbit.originalQueue=" + base, "-Dtest.rabbit.idless=" + idless)
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(existing, idless, 0, first, base)).with("Other", other(other)).with("App", APP).start()) {
            app.awaitOrFail("PORT=", "Rabbit app did not start");
            app.awaitOrFail("Last-known-good bytecode cache:", "watcher did not finish initialization");
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
                app.rewrite("Listener", listener(existing, idless, stage, topic, base));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (reloads(app) == before && System.nanoTime() < until) Thread.sleep(25);
                assertEquals(before + 1, reloads(app), app.tail());
                boolean active = stage != 4 && stage != 6;
                if (active) {
                    waitState(port, "added:assigned", app);
                    String msg = "save" + stage;
                    deliver(producer, app, topic, "ADDED:" + stage + ":owner:" + topic + ":" + msg, msg);
                    if (stage == 1) {
                        deliver(producer, app, topic, "ADDED:1:owner:" + topic + ":retry", "retry");
                        assertEquals(1, app.output().stream().filter("ROLLBACK:retry"::equals).count(), app.tail());
                    }
                } else assertFalse(http(port, "/state").contains("added:"), app.tail());
                if (existing) {
                    waitState(port, "existing:assigned", app);
                    assertTrue(http(port, "/state").contains("existingCount=1;"), app.tail());
                    deliver(producer, app, base, "BASE:save" + stage, "save" + stage);
                }
                deliver(producer, app, other, "OTHER:save" + stage, "save" + stage);
                assertTrue(http(port, "/state").contains("retiredRunning=0"), "old containers must stop: " + app.tail());
                assertTrue(http(port, "/state").contains("otherSame=true"), "unrelated container must retain identity");
                if (stage >= 3) {
                    producer.convertAndSend(first, "old-topic-" + stage);
                    Thread.sleep(300);
                    assertFalse(app.output().stream().anyMatch(s -> s.startsWith("ADDED:") && s.contains("old-topic-")), app.tail());
                }
            }
            assertEquals("allStopped=true", http(port, "/close"));
            assertFalse(app.output().stream().anyMatch(s -> s.contains("@RabbitListener is found by scanning")), app.tail());
            System.out.println("[added-rabbit] existing=" + existing + " idless=" + idless + " add/edit/topic/remove/private-restore/unannotate passed; single delivery, retired consumers, unrelated identity, context close verified");
        }
    }
    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Listener ") || s.contains("Structural reload: app.Listener ")).count();
    }
    private static void deliver(RabbitTemplate producer, WatchedApp app, String topic, String line, String msg) throws Exception {
        producer.convertAndSend(topic, msg, m -> { m.getMessageProperties().setHeader("label", topic); return m; });
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
    private static String rabbitClasspath() {
        return String.join(File.pathSeparator, Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(s -> {
                    String n = Path.of(s).getFileName().toString();
                    return n.endsWith(".jar") && (n.startsWith("spring-") && !n.startsWith("spring-kafka") && !n.startsWith("spring-jms")
                            || n.startsWith("amqp-client-") || n.startsWith("slf4j-api-") || n.startsWith("spring-retry-"));
                }).toList());
    }
    private static String listener(boolean existing, boolean idless, int stage, String topic, String base) {
        String added = stage == 0 || stage == 4 ? "" : """
                %s
                %s void added(@org.springframework.messaging.handler.annotation.Payload String value,
                        @org.springframework.messaging.handler.annotation.Header("label") String topic, org.springframework.amqp.core.Message message) {
                    if (value.equals("retry") && !Boolean.TRUE.equals(message.getMessageProperties().getRedelivered())) {
                        System.out.println("ROLLBACK:retry"); throw new IllegalStateException("first attempt rolls back");
                    }
                    System.out.println("ADDED:%d:" + prefix + ":" + topic + ":" + value);
                }
                """.formatted(stage == 6 ? "" : "@org.springframework.amqp.rabbit.annotation.RabbitListener(id=\"added\", queues=\"" + topic + "\")",
                        stage == 5 ? "private" : "public", stage);
        return """
                package app;
                @org.springframework.stereotype.Component public class Listener {
                    private final String prefix = new String("owner");
                    public String use() { return "used"; }
                    %s
                    %s
                }
                """.formatted(existing ? """
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(%s queues="%s")
                    public void original(String value) { System.out.println("BASE:" + value); }
                    """.formatted(idless ? "" : "id=\"existing\",", base) : "", added);
    }
    private static String other(String topic) {
        return """
                package app;
                @org.springframework.stereotype.Component public class Other {
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(id="other", queues="%s")
                    public void original(String value) { System.out.println("OTHER:" + value); }
                }
                """.formatted(topic);
    }
    private static final String APP = """
            package app;
            import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
            import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
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
                    factory.setConnectionFactory(cf); factory.setPrefetchCount(1); factory.setChannelTransacted(true);
                    return factory;
                }
                public static void main(String[] args) throws Exception {
                    var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext(App.class);
                    var registry = ctx.getBean(org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry.class);
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
                            text = "allStopped=" + seen.stream().noneMatch(c -> ((SimpleMessageListenerContainer)c).isActive() || c.isRunning() || ((SimpleMessageListenerContainer)c).getActiveConsumerCount() != 0);
                        } else {
                            var current = registry.getListenerContainers(); var b = new StringBuilder();
                            int existingCount = 0;
                            for (String id : registry.getListenerContainerIds()) {
                                var c = (SimpleMessageListenerContainer) registry.getListenerContainer(id);
                                boolean original = java.util.Arrays.asList(c.getQueueNames()).contains(System.getProperty("test.rabbit.originalQueue"));
                                if (original) existingCount++;
                                b.append(original && Boolean.getBoolean("test.rabbit.idless") ? "existing" : id).append(c.getActiveConsumerCount() > 0 ? ":assigned;" : ":waiting;");
                            }
                            b.append("existingCount=").append(existingCount).append(';');
                            b.append("retiredRunning=").append(previous.stream().filter(c -> !current.contains(c) && (((SimpleMessageListenerContainer)c).isActive() || c.isRunning() || ((SimpleMessageListenerContainer)c).getActiveConsumerCount() != 0)).count());
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
