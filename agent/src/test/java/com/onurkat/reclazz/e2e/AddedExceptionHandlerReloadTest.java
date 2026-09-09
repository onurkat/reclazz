/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AddedExceptionHandlerReloadTest {
    @TempDir Path tmp;
    @Test void addedLocalHandlerCompetesWithExistingHandlersAndFollowsSaves() throws Exception { exercise(false); }
    @Test void addedAdviceHandlerKeepsSelectorsLocalPriorityAndFollowsSaves() throws Exception { exercise(true); }

    private void exercise(boolean advice) throws Exception {
        String servlet = Path.of(javax.servlet.Servlet.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath() + File.pathSeparator + servlet)
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("App", APP).with("Api", api(advice, 0)).with("Advice", advice(0))
                .with("Fallback", FALLBACK).with("Other", OTHER).with("Local", LOCAL).start()) {
            app.awaitOrFail("PORT=", "MVC HTTP bridge did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            var client = HttpClient.newHttpClient();
            check(client, port, "/boom", advice ? 400 : 409, advice ? "fallback:bad" : "local-base:bad");
            for (int stage = 1; stage <= 5; stage++) {
                String name = advice ? "Advice" : "Api";
                if (advice && stage == 1) app.rewriteAll(java.util.Map.of("Advice", advice(stage), "Api", api(true, stage)));
                else app.rewrite(name, advice ? advice(stage) : api(false, stage));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                int expected = stage;
                while (reloads(app, name) < expected && System.nanoTime() < until) Thread.sleep(25);
                assertEquals(expected, reloads(app, name), app.tail());
                if (advice && stage == 1) {
                    while (reloads(app, "Api") < 1 && System.nanoTime() < until) Thread.sleep(25);
                    assertEquals(1, reloads(app, "Api"), app.tail());
                }
                boolean handles = stage != 3 && stage != 5;
                check(client, port, "/boom", handles ? 421 + stage : advice ? 400 : 409,
                        handles ? (advice ? "advice" : "local") + stage + ":bad" : advice ? "fallback:bad" : "local-base:bad");
                check(client, port, "/added", handles ? 421 + stage : advice ? 400 : 409,
                        handles ? (advice ? "advice" : "local") + stage + ":bad" : advice ? "fallback:bad" : "local-base:bad");
                check(client, port, "/other", 400, "fallback:other");
                check(client, port, "/local", 409, "local-priority");
            }
            assertFalse(app.output().stream().anyMatch(s -> s.contains("@ExceptionHandler is found by scanning")), app.tail());
            System.out.println("[added-exceptions] " + (advice ? "advice" : "local")
                    + " add/edit/remove/restore/unannotate=passed selectors/local-priority=preserved");
        }
    }
    private static long reloads(WatchedApp app, String name) {
        return app.output().stream().filter(s -> s.contains("Reloaded app." + name + " ")
                || s.contains("Structural reload: app." + name + " ")).count();
    }
    private static void check(HttpClient client, int port, String path, int status, String body) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(body, response.body());
    }
    private static String handler(int stage, String label) {
        if (stage == 0 || stage == 3) return "";
        return """
                %s
                %s org.springframework.http.ResponseEntity<String> added(IllegalArgumentException failure) {
                    return org.springframework.http.ResponseEntity.status(%d).header("X-Handler", "%s")
                            .body(prefix + "%d:" + failure.getMessage());
                }
                """.formatted(stage == 5 ? "" : "@org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)",
                stage == 4 ? "private" : "public", 421 + stage, label, stage);
    }
    private static String api(boolean advice, int stage) {
        return """
                package app;
                import org.springframework.web.bind.annotation.*;
                @RestController public class Api {
                    private final String prefix = new String("local");
                    @GetMapping("/boom") public String boom() { throw new IllegalArgumentException("bad"); }
                    %s
                    %s
                    %s
                }
                """.formatted(advice ? "" : """
                    @ExceptionHandler(RuntimeException.class)
                    public org.springframework.http.ResponseEntity<String> base(RuntimeException failure) {
                        return org.springframework.http.ResponseEntity.status(409).body("local-base:" + failure.getMessage());
                    }
                    """, advice ? "" : handler(stage, "local"), stage == 0 ? "" : """
                    @GetMapping("/added") public String addedEndpoint() { throw new IllegalArgumentException("bad"); }
                    """);
    }
    private static String advice(int stage) {
        return """
                package app;
                @org.springframework.web.bind.annotation.RestControllerAdvice(assignableTypes=Api.class)
                @org.springframework.core.annotation.Order(-10)
                public class Advice {
                    private final String prefix = new String("advice");
                    public String marker() { return "ready"; }
                    %s
                }
                """.formatted(handler(stage, "advice"));
    }
    private static final String FALLBACK = """
            package app;
            @org.springframework.web.bind.annotation.RestControllerAdvice
            @org.springframework.core.annotation.Order(10)
            public class Fallback {
                @org.springframework.web.bind.annotation.ExceptionHandler(RuntimeException.class)
                public org.springframework.http.ResponseEntity<String> fallback(RuntimeException failure) {
                    return org.springframework.http.ResponseEntity.status(400).body("fallback:" + failure.getMessage());
                }
            }
            """;
    private static final String OTHER = """
            package app;
            @org.springframework.web.bind.annotation.RestController public class Other {
                @org.springframework.web.bind.annotation.GetMapping("/other")
                public String fail() { throw new IllegalArgumentException("other"); }
            }
            """;
    private static final String LOCAL = """
            package app;
            @org.springframework.web.bind.annotation.RestController public class Local {
                @org.springframework.web.bind.annotation.GetMapping("/local")
                public String fail() { throw new IllegalArgumentException("local"); }
                @org.springframework.web.bind.annotation.ExceptionHandler(RuntimeException.class)
                public org.springframework.http.ResponseEntity<String> local(RuntimeException failure) {
                    return org.springframework.http.ResponseEntity.status(409).body("local-priority");
                }
            }
            """;
    private static final String APP = """
            package app;
            @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
            @org.springframework.web.servlet.config.annotation.EnableWebMvc
            @org.springframework.context.annotation.ComponentScan("app")
            public class App {
                public static void main(String[] args) throws Exception {
                    var context = new org.springframework.web.context.support.AnnotationConfigWebApplicationContext();
                    context.setServletContext(new org.springframework.mock.web.MockServletContext());
                    context.register(App.class); context.refresh();
                    var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).build();
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                    server.createContext("/", exchange -> {
                        int status; byte[] body;
                        try {
                            var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                    exchange.getRequestURI().toString())).andReturn().getResponse();
                            status = response.getStatus(); body = response.getContentAsByteArray();
                        } catch (Throwable failure) {
                            status = 500; body = failure.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        }
                        exchange.sendResponseHeaders(status, body.length);
                        exchange.getResponseBody().write(body); exchange.close();
                    });
                    server.start(); System.out.println("PORT=" + server.getAddress().getPort());
                }
            }
            """;
}
