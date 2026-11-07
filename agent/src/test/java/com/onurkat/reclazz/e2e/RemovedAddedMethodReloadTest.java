/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real watcher/companion calls, including a Method captured before removal. */
class RemovedAddedMethodReloadTest {
    @TempDir Path dir;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void heldAddedMethodAfterRemovalThrowsAndReadditionRestoresHeldAndDirectCallers(boolean statik) throws Exception {
        String modifier = statik ? "static " : "";
        try (var app = WatchedApp.in(dir).jvmArgs("-Dprobe.dir=" + dir).agentArgs("startupDelaySec=1,debounceMs=200,portFile=" + dir.resolve("agent.port"))
                .with("Holder", holder(""))
                .with("Caller", "package app; public class Caller { public static String call(Holder h) { return \"none\"; } }")
                .with("App", driver()).start()) {
            ready(app);
            rewrite(app, Map.of("Holder", holder("public " + modifier + "String foo() { return \"first\"; }"),
                    "Caller", "package app; public class Caller { public static String call(Holder h) { return h.foo(); } }"));
            probe(app, 0, "first:first");
            rewrite(app, Map.of("Holder", holder("")));
            probe(app, 1, "NoSuchMethodError:NoSuchMethodError");
            assertTrue(app.output().stream().anyMatch(s -> s.contains("'java.lang.String app.Holder.foo()' was removed by a reload. Restore the method or update its callers.")), app.tail());
            rewrite(app, Map.of("Holder", holder("public " + modifier + "String foo() { return \"restored\"; }")));
            probe(app, 2, "restored:restored");
            // Removal after re-add must invalidate the same already-captured Method again.
            rewrite(app, Map.of("Holder", holder("")));
            probe(app, 3, "NoSuchMethodError:NoSuchMethodError");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void removedAddedOverrideFallsBackToPhysicalOrAddedParentImplementation(boolean addedParent) throws Exception {
        try (var app = WatchedApp.in(dir).jvmArgs("-Dprobe.dir=" + dir).agentArgs("startupDelaySec=1,debounceMs=200,portFile=" + dir.resolve("agent.port"))
                .with("Parent", parent(addedParent ? "" : "public String foo() { return \"parent\"; }"))
                .with("Holder", "package app; public class Holder extends Parent {}")
                .with("Caller", "package app; public class Caller { public static String call(Holder h) { return \"none\"; } }")
                .with("App", driver()).start()) {
            ready(app);
            if (addedParent) rewrite(app, Map.of("Parent", parent("public String foo() { return \"parent\"; }")));
            rewrite(app, Map.of("Holder", "package app; public class Holder extends Parent { public String foo() { return \"child\"; } }",
                    "Caller", "package app; public class Caller { public static String call(Holder h) { return h.foo(); } }"));
            // On the existing physical-parent path, a direct invokevirtual
            // currently resolves the parent's slot before the added override.
            // This pre-existing addition limitation is outside the removal fix.
            probe(app, 0, addedParent ? "child:child" : "child:parent");
            rewrite(app, Map.of("Holder", "package app; public class Holder extends Parent {}"));
            probe(app, 1, "parent:parent");
            // Following a later parent save requires a live dispatch handle, not a frozen body.
            rewrite(app, Map.of("Parent", parent("public String foo() { return \"parent-new\"; }")));
            probe(app, 2, "parent-new:parent-new");
        }
    }

    private static String holder(String method) { return "package app; public class Holder { " + method + " }"; }
    private static String parent(String method) { return "package app; public class Parent { " + method + " }"; }
    private static void ready(WatchedApp app) {
        app.awaitOrFail("READY", "held receiver constructed");
        app.awaitOrFail("] Watching 1 director", "watcher ready");
    }
    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Structural reload: app.") || s.contains("Reloaded app.")).count();
    }
    private static void rewrite(WatchedApp app, Map<String, String> sources) throws Exception {
        long count = reloads(app) + sources.size();
        app.rewriteAll(sources);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (reloads(app) < count && System.nanoTime() < end) Thread.sleep(25);
        assertEquals(count, reloads(app), app.tail());
    }
    private void probe(WatchedApp app, int stage, String expected) throws Exception {
        Files.createFile(dir.resolve("probe" + stage));
        app.awaitOrFail("CHECK" + stage + "=", "held and direct calls executed");
        assertEquals("CHECK" + stage + "=" + expected, app.latest("CHECK" + stage + "="), app.tail());
    }
    private static String driver() {
        return """
            package app;
            import java.lang.reflect.*;
            import java.nio.file.*;
            public class App {
                public static void main(String[] args) throws Exception {
                    Holder held = new Holder();
                    Method method = null;
                    System.out.println("READY=" + Caller.call(held));
                    for (int stage = 0; stage < 4; stage++) {
                        while (!Files.exists(Path.of(System.getProperty("probe.dir"), "probe" + stage))) Thread.sleep(10);
                        if (method == null) method = Holder.class.getMethod("foo");
                        Method captured = method;
                        System.out.println("CHECK" + stage + "=" + safely(() -> captured.invoke(held)) + ":" + safely(() -> Caller.call(held)));
                    }
                }
                static String safely(java.util.concurrent.Callable<Object> call) {
                    try { return String.valueOf(call.call()); }
                    catch (Throwable failure) {
                        while (failure instanceof InvocationTargetException) failure = failure.getCause();
                        if (failure instanceof NoSuchMethodError) System.out.println("ERROR=" + failure.getMessage());
                        return failure.getClass().getSimpleName();
                    }
                }
            }
            """;
    }
}
