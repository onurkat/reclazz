/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
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

/** An added @JmsListener whose destination is a ${property} placeholder resolves
 *  against the application's environment and consumes from the real broker. */
class AddedJmsListenerPlaceholderReloadTest {
    @TempDir Path tmp;
    static BrokerService broker;
    static String brokerUrl;
    @BeforeAll static void startBroker() throws Exception {
        broker = new BrokerService(); broker.setBrokerName("reclazz-jms-ph-" + UUID.randomUUID());
        broker.setPersistent(false); broker.setUseJmx(false); broker.setUseShutdownHook(false);
        var connector = broker.addConnector("tcp://127.0.0.1:0"); broker.start(); broker.waitUntilStarted();
        brokerUrl = connector.getPublishableConnectString();
    }
    @AfterAll static void stopBroker() throws Exception { if (broker != null) { broker.stop(); broker.waitUntilStopped(); } }

    @Test
    void placeholderDestinationResolvesAgainstTheEnvironmentAndConsumes() throws Exception {
        String dest = "reclazz-jms-ph-" + UUID.randomUUID();
        var producer = new JmsTemplate(new ActiveMQConnectionFactory(brokerUrl));
        try (var app = WatchedApp.in(tmp).classpath(jmsClasspath())
                .jvmArgs("-Dtest.brokers=" + brokerUrl, "-Dapp.dest=" + dest)
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(false)).with("App", APP).start()) {
            app.awaitOrFail("PORT=", "JMS app did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));

            long before = reloads(app);
            app.rewrite("Listener", listener(true));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (reloads(app) == before && System.nanoTime() < until) Thread.sleep(25);
            assertEquals(before + 1, reloads(app), app.tail());
            waitState(port, "added:assigned", app);

            producer.convertAndSend(dest, "hello");
            assertTrue(app.awaits("ADDED:hello", 20), app.tail());
            assertFalse(app.output().stream().anyMatch(s -> s.contains("@JmsListener is found by scanning")), app.tail());

            app.rewrite("Listener", listener(false));
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (reloads(app) < before + 2 && System.nanoTime() < until) Thread.sleep(25);
            assertEquals(before + 2, reloads(app), app.tail());
            long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (http(port, "/state").contains("added:") && System.nanoTime() < stop) Thread.sleep(50);
            assertFalse(http(port, "/state").contains("added:"), app.tail());
            System.out.println("[jms-placeholder] ${app.dest} resolved to " + dest + ", consumed, removed");
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
    private static String jmsClasspath() {
        return String.join(File.pathSeparator, Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(s -> {
                    String n = Path.of(s).getFileName().toString();
                    return n.endsWith(".jar") && (n.startsWith("spring-") && !n.startsWith("spring-kafka")
                            || n.startsWith("activemq-client-") || n.startsWith("slf4j-api-")
                            || n.startsWith("jakarta.jms-api-") || n.startsWith("hawtbuf-"));
                }).toList());
    }
    private static String listener(boolean added) {
        String method = added ? """
                @org.springframework.jms.annotation.JmsListener(id="added", destination="${app.dest}")
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
            import org.springframework.jms.listener.DefaultMessageListenerContainer;
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
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                    server.createContext("/", exchange -> {
                        var b = new StringBuilder();
                        for (String id : registry.getListenerContainerIds()) {
                            var c = (DefaultMessageListenerContainer) registry.getListenerContainer(id);
                            b.append(id).append(c.isRegisteredWithDestination() ? ":assigned;" : ":waiting;");
                        }
                        byte[] bytes = b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
                    });
                    server.start(); System.out.println("PORT=" + server.getAddress().getPort());
                }
            }
            """;
}
