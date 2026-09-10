/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ConfigFileRemovalReloadTest {
    @TempDir Path tmp;

    @Test void removedPropertiesRevealDefaultsAndRestoreAcrossSaves() throws Exception {
        exercise(false);
    }

    @Test void yamlNestedValuesListsRemovalAndSyntaxRecoveryReachExistingBeans() throws Exception {
        exercise(true);
    }

    private void exercise(boolean yaml) throws Exception {
        Path file = Files.createDirectories(tmp.resolve("classes")).resolve(yaml ? "application.yaml" : "application.properties");
        Files.writeString(file, content(yaml, "10", "old", "a"));
        String snake = java.util.Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .filter(p -> Path.of(p).getFileName().toString().startsWith("snakeyaml-")).findFirst().orElseThrow();
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath() + java.io.File.pathSeparator + snake)
                .agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dconfig.file=" + file).with("App", APP).with("Settings", SETTINGS).start()) {
            app.awaitOrFail("PORT=", "Boot application did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            String portLine = app.latest("PORT=");
            int port = Integer.parseInt(portLine.substring(portLine.lastIndexOf("PORT=") + 5));
            var client = HttpClient.newHttpClient();
            assertEquals("10:old:[a]:10:old:10:true", get(client, port));
            Files.writeString(file, yaml ? "marker: ready\n" : "marker=ready\n");
            awaitBody(client, port, "5:fallback:[default]:5:fallback:5:true");
            Files.writeString(file, content(yaml, "broken", "bad", "bad"));
            app.awaitOrFail("Rejected: the running configuration is unchanged", "invalid removal candidate was not rejected");
            assertEquals("5:fallback:[default]:5:fallback:5:true", get(client, port));
            if (yaml) {
                Files.writeString(file, "svc: [broken\n");
                app.awaitOrFail("Uncheckable: the running configuration is unchanged", "malformed YAML was not held");
                assertEquals("5:fallback:[default]:5:fallback:5:true", get(client, port));
            }
            Files.writeString(file, content(yaml, "30", "restored", "z"));
            awaitBody(client, port, "30:restored:[z]:30:restored:30:true");
            Files.delete(file);
            awaitBody(client, port, "5:fallback:[default]:5:fallback:5:true");
            Files.writeString(file, content(yaml, "40", "again", "q"));
            awaitBody(client, port, "40:again:[q]:40:again:40:true");
        }
    }

    private static String content(boolean yaml, String timeout, String note, String item) {
        return yaml ? "svc:\n  timeout: " + timeout + "\n  note: " + note + "\n  items:\n    - " + item + "\nmarker: ready\n"
                : "svc.timeout=" + timeout + "\nsvc.note=" + note + "\nsvc.items[0]=" + item + "\nmarker=ready\n";
    }

    private static String get(HttpClient client, int port) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }
    private static void awaitBody(HttpClient client, int port, String expected) throws Exception {
        String actual = null; long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            actual = get(client, port);
            if (expected.equals(actual)) { System.out.println("[config-file] " + actual); return; }
            Thread.sleep(50);
        }
        assertEquals(expected, actual);
    }
    private static final String SETTINGS = """
            package app;
            @org.springframework.boot.context.properties.ConfigurationProperties("svc")
            public class Settings {
                private int timeout;
                private String note = "fallback";
                private java.util.List<String> items = java.util.List.of("default");
                public int getTimeout() { return timeout; }
                public void setTimeout(int value) { timeout = value; }
                public String getNote() { return note; }
                public void setNote(String value) { note = value; }
                public java.util.List<String> getItems() { return items; }
                public void setItems(java.util.List<String> value) { items = value; }
            }
            """;
    private static final String APP = """
            package app;
            @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
            @org.springframework.boot.context.properties.EnableConfigurationProperties(Settings.class)
            public class App {
                @org.springframework.beans.factory.annotation.Value("${svc.timeout}") int timeout;
                @org.springframework.beans.factory.annotation.Value("${svc.note:fallback}") String note;
                @org.springframework.beans.factory.annotation.Autowired Settings held;
                public static void main(String[] args) throws Exception {
                    var boot = new org.springframework.boot.SpringApplication(App.class);
                    boot.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
                    boot.setDefaultProperties(java.util.Map.of("svc.timeout", "5"));
                    var context = boot.run("--spring.config.location=file:" + System.getProperty("config.file"));
                    App app = context.getBean(App.class);
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                    server.createContext("/", exchange -> {
                        Settings settings = context.getBean(Settings.class);
                        String text = settings.getTimeout() + ":" + settings.getNote() + ":" + settings.getItems()
                                + ":" + app.timeout + ":" + app.note + ":"
                                + context.getEnvironment().getProperty("svc.timeout") + ":" + (app.held == settings);
                        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        try (var out = exchange.getResponseBody()) { out.write(bytes); }
                    });
                    server.start(); System.out.println("PORT=" + server.getAddress().getPort());
                }
            }
            """;
}
