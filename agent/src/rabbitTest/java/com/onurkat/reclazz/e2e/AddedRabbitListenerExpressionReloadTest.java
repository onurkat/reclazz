/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import com.onurkat.reclazz.e2e.harness.RabbitBroker;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
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

/** Bean-backed queue expressions across real broker reloads, errors and removal. */
class AddedRabbitListenerExpressionReloadTest {
    @TempDir Path tmp;
    static RabbitBroker broker;
    @BeforeAll static void startBroker() throws Exception { broker = RabbitBroker.start(); }
    @AfterAll static void stopBroker() throws Exception { if (broker != null) broker.close(); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void queueExpressionsMoveConsumersAndRecoverAfterErrors(boolean child) throws Exception {
        String one = "reclazz-expression-one-" + UUID.randomUUID();
        String two = "reclazz-expression-two-" + UUID.randomUUID();
        var producer = new RabbitTemplate(broker.connectionFactory());
        var admin = new RabbitAdmin(broker.connectionFactory());
        for (String queue : List.of(one, two))
            admin.declareQueue(new org.springframework.amqp.core.Queue(queue, true, false, false));
        var builder = WatchedApp.in(tmp).classpath(rabbitClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder
                .jvmArgs("-Dtest.rabbit.port=" + broker.port(), "-Dtest.rabbit.password=" + broker.password(),
                        "-Dapp.one=" + one, "-Dapp.two=" + two)
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(0)).with("Routing", ROUTING).with("App", APP).start()) {
            app.awaitOrFail("PORT=", "Rabbit app did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "child loader missing");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            for (int version = 1; version <= 5; version++) {
                if (version == 2) {
                    assertEquals("moved", http(port, "/move"));
                    assertEquals(one, http(port, "/queues"), "routing changes take effect on the next save");
                }
                long before = reloads(app);
                app.rewrite("Listener", listener(version));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (reloads(app) == before && System.nanoTime() < until) Thread.sleep(25);
                assertEquals(before + 1, reloads(app), app.tail());
                assertEquals("false", http(port, "/original"), "the added callback must use the companion path");
                if (version == 3 || version == 5) {
                    assertEquals("none", http(port, "/queues"), app.tail());
                    assertFalse(http(port, "/state").contains("added:"), app.tail());
                } else {
                    waitState(port, "added:assigned", app);
                    List<String> queues = version == 1 ? List.of(one) : version == 2 ? List.of(two) : List.of(one, two);
                    assertEquals(String.join(",", queues), http(port, "/queues"), app.tail());
                    for (int i = 0; i < queues.size(); i++) {
                        String message = "v" + version + "-q" + i;
                        producer.convertAndSend(queues.get(i), message);
                        assertTrue(app.awaits("ADDED" + version + ":" + message, 20), app.tail());
                    }
                }
                System.out.println("[rabbit-expression] child=" + child + " version=" + version
                        + " queues=" + http(port, "/queues") + " originalMethod=false");
            }
            assertEquals(4, app.output().stream().filter(s -> s.startsWith("ADDED")).count(), app.tail());
            assertTrue(app.output().stream().anyMatch(s -> s.contains("can't resolve")), app.tail());
        }
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
    private static String rabbitClasspath() {
        return String.join(File.pathSeparator, Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(s -> {
                    String n = Path.of(s).getFileName().toString();
                    return n.endsWith(".jar") && (n.startsWith("spring-") && !n.startsWith("spring-kafka") && !n.startsWith("spring-jms")
                            || n.startsWith("amqp-client-") || n.startsWith("slf4j-api-") || n.startsWith("spring-retry-"));
                }).toList());
    }
    private static String listener(int version) {
        String expression = version == 3 ? "#{42}" : version == 4 ? "#{@routing.all}" : "#{@routing.queue}";
        String method = version == 0 || version == 5 ? "" : """
                @org.springframework.amqp.rabbit.annotation.RabbitListener(id="added", queues="%s")
                public void added(String value) { System.out.println("ADDED%d:" + value); }
                """.formatted(expression, version);
        return """
                package app;
                @org.springframework.stereotype.Component public class Listener {
                    public int version() { return %d; }
                    %s
                }
                """.formatted(version, method);
    }
    private static final String ROUTING = """
            package app;
            @org.springframework.stereotype.Component public class Routing {
                private volatile String queue = System.getProperty("app.one");
                public String getQueue() { return queue; }
                public java.util.List<String> getAll() { return java.util.List.of(System.getProperty("app.one"), System.getProperty("app.two")); }
                public void move() { queue = System.getProperty("app.two"); }
            }
            """;
    private static final String APP = """
            package app;
            import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
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
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                    server.createContext("/", exchange -> {
                        var b = new StringBuilder();
                        String path = exchange.getRequestURI().getPath();
                        if (path.equals("/move")) { ctx.getBean(Routing.class).move(); b.append("moved"); }
                        else if (path.equals("/original")) {
                            b.append(java.util.Arrays.stream(Listener.class.getDeclaredMethods()).anyMatch(m -> m.getName().equals("added")));
                        } else if (path.equals("/queues")) {
                            var c = (SimpleMessageListenerContainer) registry.getListenerContainer("added");
                            b.append(c == null ? "none" : String.join(",", c.getQueueNames()));
                        } else for (String id : registry.getListenerContainerIds()) {
                            var c = (SimpleMessageListenerContainer) registry.getListenerContainer(id);
                            b.append(id).append(c.getActiveConsumerCount() > 0 ? ":assigned;" : ":waiting;");
                        }
                        byte[] bytes = b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
                    });
                    server.start(); System.out.println("PORT=" + server.getAddress().getPort());
                }
            }
            """;
}
