/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What one save costs to read.
 *
 * <p>A reload of a Spring bean used to be several lines: the swap, then one
 * line per framework step that did something, in the order the steps ran.
 * Reading them answers "did it actually reload, and what did it touch" only
 * after collecting them, and by the next save they have scrolled. The reload
 * line now carries the answer itself: what was re-created, re-scanned or
 * evicted, and whether anything new is waiting on a restart. The step lines
 * are still there under {@code verbose=true}.
 */
class ReloadSaysWhatChangedTest {

    private static final long RELOAD_TIMEOUT_SEC = 45;

    @TempDir
    Path tmp;

    @Test
    void theReloadLineNamesWhatTheFrameworkStepsDid() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .classpath(WatchedApp.springClasspath())
                .with("App", appSource())
                .with("Greeter", greeterSource("v1"))
                .start()) {
            app.awaitOrFail("APP_STARTED", "app did not start under the agent");
            app.awaitOrFail("GREET=v1", "app did not serve v1");
            app.awaitOrFail("] Watching 1 director", "the watcher never said what it watches");

            int before = app.output().size();
            app.rewrite("Greeter", greeterSource("v2"));
            assertTrue(app.awaits("GREET=v2", RELOAD_TIMEOUT_SEC),
                    () -> "hot reload did not reach the running app:\n" + app.tail());

            assertReloadSummary(app, before);
        }
    }

    @Test
    void summaryAssertionWaitsWhenNewBehaviorPrecedesSummaryOutput() throws Exception {
        Path release = tmp.resolve("release-summary");
        var executor = Executors.newSingleThreadExecutor();
        try (WatchedApp app = WatchedApp.in(tmp)
                .classpath(WatchedApp.springClasspath())
                .jvmArgs("-Dtest.summary.release=" + release.toAbsolutePath())
                .with("App", appSource())
                .with("Greeter", greeterSource("v1"))
                .start()) {
            app.awaitOrFail("APP_STARTED", "app did not start under the agent");
            app.awaitOrFail("GREET=v1", "app did not serve v1");
            app.awaitOrFail("] Watching 1 director", "the watcher never said what it watches");
            int before = app.output().size();
            app.rewrite("Greeter", greeterSource("v2"));
            app.awaitOrFail("GREET=v2", "new behavior did not arrive while the summary was gated");
            app.awaitOrFail("SUMMARY_BLOCKED", "the fixture did not reach the summary gate");
            assertTrue(app.output().stream().noneMatch(line -> line.contains("Reloaded app.Greeter")),
                    "the fixture must withhold the summary while exposing v2");

            var started = new java.util.concurrent.CountDownLatch(1);
            var assertion = executor.submit(() -> {
                started.countDown();
                assertReloadSummary(app, before);
            });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS), "assertion worker did not start");
                assertThrows(TimeoutException.class, () -> assertion.get(200, TimeUnit.MILLISECONDS),
                        "the summary assertion must wait instead of reading an incomplete snapshot");
            } finally {
                Files.writeString(release, "release");
            }
            assertion.get(RELOAD_TIMEOUT_SEC, TimeUnit.SECONDS);
        } finally {
            Files.writeString(release, "release");
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "assertion worker did not stop");
        }
    }

    private static void assertReloadSummary(WatchedApp app, int before) {
        // A redefined method is callable before Spring refresh and summary output finish.
        // Wait for the event being asserted, not just the application's new return value.
        assertTrue(app.awaits("Reloaded app.Greeter", RELOAD_TIMEOUT_SEC),
                () -> "no reload line for the save:\n" + app.tail());
        // Snapshot the live output before slicing it: the reader thread keeps
        // appending, and a subList view over a CopyOnWriteArrayList throws
        // ConcurrentModificationException when the backing list grows under it
        // (seen intermittently on slower Windows CI).
        List<String> snapshot = new ArrayList<>(app.output());
        List<String> agentLines = agentLines(snapshot.subList(before, snapshot.size()));
        System.out.println("[diag] agent lines for one save: " + agentLines.size());
        for (String line : agentLines) System.out.println("[diag]   " + line);

        String reloadLine = agentLines.stream()
                .filter(line -> line.contains("Reloaded app.Greeter"))
                .findFirst().orElse(null);
        assertNotNull(reloadLine, () -> "no reload line for the save:\n" + app.tail());
        assertTrue(reloadLine.contains("bean greeter re-created"),
                () -> "the reload line does not say what the save touched: " + reloadLine);
        assertTrue(agentLines.stream().noneMatch(line -> line.contains("Spring bean refreshed")),
                "the step's own line is detail, and detail is for verbose");
    }

    /** The agent's own lines, without colour codes. */
    private static List<String> agentLines(List<String> output) {
        List<String> found = new ArrayList<>();
        for (String line : output) {
            if (line.contains("[Reclazz]")) {
                found.add(line.replaceAll("\\[[0-9;]*m", ""));
            }
        }
        return found;
    }

    private static String appSource() {
        return """
                package app;
                import org.springframework.context.annotation.AnnotationConfigApplicationContext;
                import org.springframework.context.annotation.ComponentScan;
                import org.springframework.context.annotation.Configuration;
                @Configuration
                @ComponentScan("app")
                public class App {
                    public static void main(String[] args) throws Exception {
                        String release = System.getProperty("test.summary.release");
                        if (release != null) {
                            // Hold only the summary, after the agent's framework work. The app
                            // remains free to print the new behavior, reproducing the CI ordering.
                            System.setOut(new java.io.PrintStream(System.out, true) {
                                @Override public void println(String line) {
                                    if (line.contains("Reloaded app.Greeter")) {
                                        super.println("SUMMARY_BLOCKED");
                                        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
                                        while (!java.nio.file.Files.exists(java.nio.file.Path.of(release))) {
                                            if (System.nanoTime() >= deadline) throw new AssertionError("summary gate timed out");
                                            try { Thread.sleep(10); }
                                            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                                        }
                                    }
                                    super.println(line);
                                }
                            });
                        }
                        var ctx = new AnnotationConfigApplicationContext(App.class);
                        System.out.println("APP_STARTED");
                        while (true) {
                            Thread.sleep(500);
                            System.out.println("GREET=" + ctx.getBean(Greeter.class).greet());
                        }
                    }
                }
                """;
    }

    private static String greeterSource(String value) {
        return """
                package app;
                import org.springframework.stereotype.Service;
                @Service
                public class Greeter {
                    public String greet() { return "%s"; }
                }
                """.formatted(value);
    }
}
