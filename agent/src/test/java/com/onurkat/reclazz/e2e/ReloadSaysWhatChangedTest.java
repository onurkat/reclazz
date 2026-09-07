/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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

            List<String> agentLines = agentLines(app.output().subList(before, app.output().size()));
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
