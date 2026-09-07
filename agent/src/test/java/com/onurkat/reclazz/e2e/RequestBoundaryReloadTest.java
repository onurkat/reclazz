/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RequestBoundaryReloadTest {
    @TempDir Path tmp;

    @Test
    void requestModeKeepsTheHeldRequestOnOldCode() throws Exception {
        exercise(true, false, false);
    }

    @Test
    void defaultModeStillReloadsDuringARequest() throws Exception {
        exercise(false, false, false);
    }

    @Test
    void theBoundaryCoversTheWholeClassBatch() throws Exception {
        exercise(true, true, false);
    }

    @Test
    void autoCompileWaitsOnlyWhenApplyingItsResult() throws Exception {
        exercise(true, false, true);
    }

    private void exercise(boolean boundary, boolean batch, boolean autoCompile) throws Exception {
        String servlet = Path.of(javax.servlet.Servlet.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        Path release = tmp.resolve("release");
        Path probe = tmp.resolve("probe");
        WatchedApp.Builder builder = WatchedApp.in(tmp);
        if (autoCompile) builder.mavenLayout();
        try (WatchedApp app = builder
                .classpath(WatchedApp.springClasspath() + File.pathSeparator + servlet)
                .agentArgs("startupDelaySec=1,debounceMs=100" + (boundary ? ",reloadBoundary=request" : "")
                        + (autoCompile ? ",autoCompile=true" : ""))
                .jvmArgs("-Dtest.release=" + release, "-Dtest.probe=" + probe, "-Dtest.batch=" + batch)
                .with("App", APP).with("Controller", CONTROLLER).with("Rules", rules(1))
                .with("Fees", fees(1)).start()) {
            app.awaitOrFail("HELD=1", "first request never entered");
            app.awaitOrFail("] Watching ", "watcher never started");
            if (autoCompile) Files.writeString(tmp.resolve("src/main/java/app/Rules.java"), rules(2));
            else if (batch) app.rewriteAll(java.util.Map.of("Rules", rules(2), "Fees", fees(2)));
            else app.rewrite("Rules", rules(2));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline && app.output().stream().noneMatch(line ->
                    line.contains("Reloaded app.Rules") || line.contains("Request boundary deferred"))) {
                Thread.sleep(20);
            }
            assertTrue(app.output().stream().anyMatch(line ->
                    line.contains("Reloaded app.Rules") || line.contains("Request boundary deferred")), app.tail());
            // A timed-out drain must reopen admission while retaining the edit.
            Files.createFile(probe);
            app.awaitOrFail("PROBE=", "new requests stayed blocked after the drain deadline");
            Files.createFile(release);
            app.awaitOrFail("RESULT=", "held request did not finish");
            String result = app.output().stream().filter(line -> line.startsWith("RESULT=")).findFirst().orElseThrow();
            System.out.println("[request-boundary] mode=" + boundary + " batch=" + batch
                    + " autoCompile=" + autoCompile + " " + result);
            assertEquals(boundary ? "RESULT=1:1" : "RESULT=1:2", result, app.tail());
            if (boundary) {
                assertTrue(app.output().contains("PROBE=1:1"), app.tail());
            }
            app.awaitOrFail("NEXT=2:2", "retained edit never reached the next request");
            if (batch) app.awaitOrFail("FEES=2", "second class in the batch was not applied");
            app.output().stream().filter(line -> line.startsWith("P95_US=") || line.startsWith("RELOAD_AFTER_RELEASE_MS="))
                    .forEach(line -> System.out.println("[request-boundary] mode=" + boundary + " " + line));
        }
    }

    private static String rules(int version) {
        return "package app;\npublic class Rules { public static int first() { return " + version
                + "; } public static int second() { return " + version + "; } }";
    }

    private static String fees(int version) {
        return "package app;\npublic class Fees { public static int value() { return " + version + "; } }";
    }

    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.mock.web.MockServletContext;
            import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
            import org.springframework.context.annotation.*;
            import org.springframework.web.servlet.config.annotation.EnableWebMvc;
            import org.springframework.test.web.servlet.setup.MockMvcBuilders;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
            @Configuration @EnableWebMvc @ComponentScan("app")
            public class App {
                public static void main(String[] args) throws Exception {
                    var context = new AnnotationConfigWebApplicationContext();
                    context.setServletContext(new MockServletContext());
                    context.register(App.class); context.refresh();
                    var mvc = MockMvcBuilders.webAppContextSetup(context).build();
                    System.out.println("FEES_BEFORE=" + Fees.value());
                    // An exception escaping the servlet must release its boundary too.
                    try { mvc.perform(get("/failure")); }
                    catch (Exception expected) { System.out.println("EXCEPTION_RETURNED"); }
                    long[] times = new long[100];
                    for (int i = 0; i < 150; i++) {
                        long start = System.nanoTime();
                        mvc.perform(get("/fast")).andReturn();
                        if (i >= 50) times[i - 50] = (System.nanoTime() - start) / 1000;
                    }
                    java.util.Arrays.sort(times);
                    System.out.println("P95_US=" + times[94]);
                    Thread held = new Thread(() -> {
                        try { System.out.println("RESULT=" + mvc.perform(get("/held")).andReturn().getResponse().getContentAsString()); }
                        catch (Throwable t) { t.printStackTrace(); }
                    });
                    held.start();
                    while (!Files.exists(Path.of(System.getProperty("test.probe")))) Thread.sleep(10);
                    System.out.println("PROBE=" + mvc.perform(get("/fast")).andReturn().getResponse().getContentAsString());
                    held.join();
                    long released = System.nanoTime();
                    while (true) {
                        String value = mvc.perform(get("/fast")).andReturn().getResponse().getContentAsString();
                        if (value.equals("2:2")) {
                            System.out.println("RELOAD_AFTER_RELEASE_MS=" + (System.nanoTime() - released) / 1_000_000);
                            System.out.println("NEXT=" + value); break;
                        }
                        Thread.sleep(10);
                    }
                    System.out.println("FEES=" + Fees.value());
                    Thread.sleep(60000);
                }
            }
            """;

    private static final String CONTROLLER = """
            package app;
            import java.nio.file.*;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class Controller {
                private int second() { return Boolean.getBoolean("test.batch") ? Fees.value() : Rules.second(); }
                @GetMapping("/failure") public String failure() { throw new IllegalStateException("expected test failure"); }
                @GetMapping("/fast") public String fast() { return Rules.first() + ":" + second(); }
                @GetMapping("/held") public String held() throws Exception {
                    int first = Rules.first();
                    System.out.println("HELD=" + first);
                    while (!Files.exists(Path.of(System.getProperty("test.release")))) Thread.sleep(10);
                    return first + ":" + second();
                }
            }
            """;
}
