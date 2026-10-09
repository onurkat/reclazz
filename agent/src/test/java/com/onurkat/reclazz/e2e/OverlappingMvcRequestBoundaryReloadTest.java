/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** A completed/expired request must not release another request's worker or code. */
class OverlappingMvcRequestBoundaryReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest(name = "oneCompletedRequestCannotReleaseAnotherWorkersCode [{index}] {arguments}")
    @ValueSource(booleans = {false, true})
    void oneCompletedRequestCannotReleaseAnotherWorkersCode(boolean child) throws Exception { exercise(child, false); }

    @ParameterizedTest(name = "timedOutWorkerCannotReleaseAnotherWorkersCode [{index}] {arguments}")
    @ValueSource(booleans = {false, true})
    void timedOutWorkerCannotReleaseAnotherWorkersCode(boolean child) throws Exception { exercise(child, true); }

    private void exercise(boolean child, boolean timeout) throws Exception {
        var builder = WatchedApp.in(tmp).classpath(ValidationConstraintReloadTest.nativeClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100,reloadBoundary=request")
                .jvmArgs("-Dprobe.dir=" + tmp).with("App", APP).with("Controller", CONTROLLER).with("Rules", rules(1));
        if (child) builder.childClassLoader();
        var client = HttpClient.newHttpClient();
        try (var app = builder.start()) {
            app.awaitOrFail("PORT=", "native servlet missing"); app.awaitOrFail("] Watching ", "watcher missing");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "child loader missing");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            String nonce = get(client, port, "/nonce").body();
            assertEquals("1:1", get(client, port, "/fast").body());
            var a = client.sendAsync(request(port, timeout ? "/timeout" : "/a"), HttpResponse.BodyHandlers.ofString());
            app.awaitOrFail("HELD=a:1", "first worker did not enter");
            var b = client.sendAsync(request(port, "/b"), HttpResponse.BodyHandlers.ofString());
            app.awaitOrFail("HELD=b:1", "second worker did not enter");
            if (timeout) assertEquals(503, a.get(15, TimeUnit.SECONDS).statusCode());
            app.rewrite("Rules", rules(2));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (System.nanoTime() < until && app.output().stream().noneMatch(s ->
                    s.contains("Request boundary deferred") || s.contains("Reloaded app.Rules"))) Thread.sleep(20);
            assertTrue(app.output().stream().anyMatch(s -> s.contains("Request boundary deferred")
                    || s.contains("Reloaded app.Rules")), app.tail());
            assertEquals("1:1", get(client, port, "/fast").body(), app.tail());
            app.rewrite("Rules", rules(3));
            if (timeout) {
                // B can finish, but expired A's interrupt-ignoring worker still owns old code.
                Files.createFile(tmp.resolve("release-b"));
                assertResponse(b.get(15, TimeUnit.SECONDS), "1:1");
                assertEquals("1:1", get(client, port, "/fast").body(), app.tail());
                Files.createFile(tmp.resolve("release-a"));
            } else {
                Files.createFile(tmp.resolve("release-a"));
                assertResponse(a.get(15, TimeUnit.SECONDS), "1:1");
                assertEquals("1:1", get(client, port, "/fast").body(), app.tail());
                Files.createFile(tmp.resolve("release-b"));
                assertResponse(b.get(15, TimeUnit.SECONDS), "1:1");
            }
            app.awaitOrFail("WORKER=a:1:1", "first worker saw mixed code");
            app.awaitOrFail("WORKER=b:1:1", "second worker saw mixed code");
            String live = null; until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < until) {
                live = get(client, port, "/fast").body();
                assertTrue(live.equals("1:1") || live.equals("2:2") || live.equals("3:3"), "mixed live response: " + live);
                if (live.equals("3:3")) break;
                Thread.sleep(25);
            }
            assertEquals("3:3", live, app.tail());
            assertEquals(nonce, get(client, port, "/nonce").body(), "fixture silently restarted");
            System.out.println("[overlapping-mvc] child=" + child + " timeout=" + timeout + " old workers=1:1 latest=3:3 nonce preserved");
        }
    }
    private static void assertResponse(HttpResponse<String> result, String expected) {
        assertEquals(200, result.statusCode(), result.body()); assertEquals(expected, result.body());
    }
    private static HttpRequest request(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(45)).GET().build();
    }
    private static HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        var result = client.send(request(port, path), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode(), result.body()); return result;
    }
    private static String rules(int version) {
        return "package app; public class Rules { public static int first() { return " + version
                + "; } public static int second() { return " + version + "; } }";
    }
    private static final String CONTROLLER = """
            package app;
            import java.nio.file.*;
            import org.springframework.web.bind.annotation.*;
            import org.springframework.web.context.request.async.WebAsyncTask;
            @RestController public class Controller {
                private final String nonce = java.util.UUID.randomUUID().toString();
                @GetMapping("/nonce") public String nonce() { return nonce; }
                @GetMapping("/fast") public String fast() { return Rules.first() + ":" + Rules.second(); }
                private String work(String name) {
                    int first = Rules.first(); System.out.println("HELD=" + name + ":" + first);
                    while (!Files.exists(Path.of(System.getProperty("probe.dir"), "release-" + name))) {
                        try { Thread.sleep(10); } catch (InterruptedException ignored) { }
                    }
                    String answer = first + ":" + Rules.second(); System.out.println("WORKER=" + name + ":" + answer); return answer;
                }
                @GetMapping("/a") public java.util.concurrent.Callable<String> a() { return () -> work("a"); }
                @GetMapping("/b") public java.util.concurrent.Callable<String> b() { return () -> work("b"); }
                @GetMapping("/timeout") public WebAsyncTask<String> timeout() { return new WebAsyncTask<>(500L, () -> work("a")); }
            }
            """;
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            import org.springframework.web.servlet.config.annotation.EnableWebMvc;
            import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
            import org.springframework.web.servlet.DispatcherServlet;
            @Configuration @EnableWebMvc @ComponentScan("app") public class App {
                public static void main(String[] args) throws Exception {
                    Path base = Files.createDirectories(Path.of(System.getProperty("probe.dir"), "tomcat"));
                    var tomcat = new org.apache.catalina.startup.Tomcat(); tomcat.setBaseDir(base.toString()); tomcat.setPort(0);
                    tomcat.getConnector().setProperty("address", "127.0.0.1");
                    var context = tomcat.addContext("", base.toString()); context.setParentClassLoader(App.class.getClassLoader());
                    var spring = new AnnotationConfigWebApplicationContext(); spring.setClassLoader(App.class.getClassLoader()); spring.register(App.class);
                    var servlet = org.apache.catalina.startup.Tomcat.addServlet(context, "mvc", new DispatcherServlet(spring));
                    servlet.setAsyncSupported(true); servlet.setLoadOnStartup(1); context.addServletMappingDecoded("/", "mvc");
                    tomcat.start(); System.out.println("PORT=" + tomcat.getConnector().getLocalPort());
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { tomcat.stop(); tomcat.destroy(); } catch (Exception ignored) { } }));
                    Thread.sleep(180000);
                }
            }
            """;
}
