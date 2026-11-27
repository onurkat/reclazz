/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the console says while the agent starts is the first thing a
 * developer reads about Reclazz, and most of it was the agent talking to
 * itself: which transformer it registered, where it put its bootstrap
 * classes, which JDK-internal patch it skipped. Those lines belong under
 * {@code verbose=true}. What stays is what a developer can act on: what is
 * watched, in which mode, where the IDE finds it, and that it is ready.
 */
class StartupSaysWhatMattersTest {

    private static final Pattern IMPLEMENTATION_DETAIL = Pattern.compile(
            "transformer registered|Bootstrap classes installed|Reflection patching|retransform-capable"
                    + "|Content-hash baseline|Last-known-good bytecode cache");

    @TempDir
    Path tmp;

    @Test
    void withoutVerboseTheStartUpIsShortAndActionable() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp).with("App", driver()).start()) {
            app.awaitOrFail("APP_STARTED", "the app did not start under the agent");
            app.awaitOrFail("] Watching 1 director", "the watcher never said what it watches");
            Thread.sleep(1000);
            List<String> agentLines = app.output().stream()
                    .filter(l -> l.contains("[Reclazz]"))
                    .toList();
            System.out.println("[diag] start-up lines=" + agentLines.size());
            agentLines.forEach(l -> System.out.println("[diag]   " + l));

            List<String> details = agentLines.stream().filter(l -> IMPLEMENTATION_DETAIL.matcher(l).find()).toList();
            assertEquals(List.of(), details, "implementation detail printed without verbose=true");
            assertTrue(agentLines.size() <= 12, "start-up should fit on a screen without verbose, printed "
                    + agentLines.size() + ":\n" + String.join("\n", agentLines));
            assertTrue(agentLines.stream().anyMatch(l -> l.contains("Watching") && l.contains("director")),
                    "what is watched is said: " + agentLines);
        }
    }

    @Test
    void withVerboseTheDetailIsThere() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp).agentArgs("startupDelaySec=1,debounceMs=200,verbose=true")
                .with("App", driver()).start()) {
            app.awaitOrFail("] Watching 1 director", "the watcher never said what it watches");
            assertTrue(app.awaits("transformer registered", 10), "verbose says what was registered");
            assertTrue(app.awaits("Content-hash baseline", 10), "and what the watcher primed");
        }
    }

    @Test
    void aMissingWatchRootCannotAnnounceReadiness() throws Exception {
        Path missing = tmp.resolve("missing-classes");
        try (WatchedApp app = WatchedApp.in(tmp).agentArgs("platform=spring,startupDelaySec=1,watchDirs=" + missing)
                .with("App", driver()).start()) {
            app.awaitOrFail("Watching 0 directories", "registration did not report zero roots");
            assertTrue(app.output().stream().anyMatch(line -> line.contains(missing.toString())));
            assertTrue(app.output().stream().anyMatch(line -> line.contains("nothing will reload")));
            assertFalse(app.output().stream().anyMatch(line -> line.contains("Reclazz is active")));
        }
    }

    @Test
    void mixedRootsOnlyAnnounceReadinessAfterRegistration() throws Exception {
        Path classes = tmp.resolve("classes");
        Path missing = tmp.resolve("missing");
        String value = "package app; public class Value { public static String text() { return \"before\"; } }";
        String reader = "package app; public class App { public static void main(String[] args) throws Exception {"
                + " while (true) { System.out.println(Value.text()); Thread.sleep(200); } } }";
        try (WatchedApp app = WatchedApp.in(tmp).agentArgs("platform=spring,startupDelaySec=1,watchDirs=" + classes + ";" + missing)
                .with("Value", value).with("App", reader).start()) {
            app.awaitOrFail("] Watching 1 director", "valid root was not registered");
            app.awaitOrFail("Reclazz is active", "watcher never became ready");
            app.awaitOrFail("before", "baseline value unavailable");
            app.rewrite("Value", value.replace("before", "after-edit"));
            app.awaitOrFail("after-edit", "class edit under the registered root did not reload");
            var lines = app.output();
            int ready = java.util.stream.IntStream.range(0, lines.size()).filter(i -> lines.get(i).contains("Reclazz is active")).findFirst().orElseThrow();
            assertTrue(lines.get(ready).endsWith("Reclazz is active. Watching for changes..."), lines.get(ready));
            assertTrue(lines.subList(0, ready).stream().anyMatch(line -> line.contains("] Watching 1 director")));
        }
    }

    private static String driver() {
        return """
                package app;
                public class App {
                    public static void main(String[] args) throws Exception {
                        System.out.println("APP_STARTED");
                        while (true) { Thread.sleep(1000); }
                    }
                }
                """;
    }
}
