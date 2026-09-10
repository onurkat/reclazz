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

/** An added @RabbitListener whose queue is a ${property} placeholder resolves
 *  against the application's environment and consumes from the real broker. */
class AddedRabbitListenerPlaceholderReloadTest {
    @TempDir Path tmp;
    static RabbitBroker broker;
    @BeforeAll static void startBroker() throws Exception { broker = RabbitBroker.start(); }
    @AfterAll static void stopBroker() throws Exception { if (broker != null) broker.close(); }

    @Test
    void placeholderQueueResolvesAgainstTheEnvironmentAndConsumes() throws Exception {
        String queue = "reclazz-rabbit-ph-" + UUID.randomUUID();
        var producer = new RabbitTemplate(broker.connectionFactory());
        var admin = new RabbitAdmin(broker.connectionFactory());
        admin.declareQueue(new org.springframework.amqp.core.Queue(queue, true, false, false));
        try (var app = WatchedApp.in(tmp).classpath(rabbitClasspath())
                .jvmArgs("-Dtest.rabbit.port=" + broker.port(), "-Dtest.rabbit.password=" + broker.password(), "-Dapp.queue=" + queue)
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(false)).with("App", APP).start()) {
            app.awaitOrFail("PORT=", "Rabbit app did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));

            long before = reloads(app);
            app.rewrite("Listener", listener(true));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (reloads(app) == before && System.nanoTime() < until) Thread.sleep(25);
            assertEquals(before + 1, reloads(app), app.tail());
            waitState(port, "added:assigned", app);

            producer.convertAndSend(queue, "hello");
            assertTrue(app.awaits("ADDED:hello", 20), app.tail());
            assertFalse(app.output().stream().anyMatch(s -> s.contains("@RabbitListener is found by scanning")), app.tail());

            app.rewrite("Listener", listener(false));
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (reloads(app) < before + 2 && System.nanoTime() < until) Thread.sleep(25);
            assertEquals(before + 2, reloads(app), app.tail());
            long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (http(port, "/state").contains("added:") && System.nanoTime() < stop) Thread.sleep(50);
            assertFalse(http(port, "/state").contains("added:"), app.tail());
            System.out.println("[rabbit-placeholder] ${app.queue} resolved to " + queue + ", consumed, removed");
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
    private static String listener(boolean added) {
        String method = added ? """
                @org.springframework.amqp.rabbit.annotation.RabbitListener(id="added", queues="${app.queue}")
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
                        for (String id : registry.getListenerContainerIds()) {
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
