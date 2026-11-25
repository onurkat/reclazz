/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** R31b audit F1: reflection follows the accepted source declaration, not the first-load one. */
class PreparedFinalModifiersReloadTest {
    @TempDir Path tmp;

    @Test void removingFinalAndReAddingMutableAreReportedAsDeclared() throws Exception {
        try (var app = WatchedApp.in(tmp).jvmArgs("-Dtest.dir=" + tmp)
                .agentArgs("resetStatics=true,startupDelaySec=1,debounceMs=200")
                .with("App", APP).with("Holder", holder("public static final Object VALUE = java.util.List.of(\"a\");", 0)).start()) {
            app.awaitWatcherReady();
            probe(app, 0, "STATE0=final:true:set:refused");
            reload(app, holder("public static Object VALUE = java.util.List.of(\"a\");", 1), 1);
            probe(app, 1, "STATE1=final:false:set:ok");
            reload(app, holder("", 2), 2);
            probe(app, 2, "STATE2=missing");
            reload(app, holder("public static Object VALUE = java.util.List.of(\"b\");", 3), 3);
            probe(app, 3, "STATE3=final:false:set:ok");
            reload(app, holder("public static final Object VALUE = java.util.List.of(\"c\");", 4), 4);
            probe(app, 4, "STATE4=final:true:set:refused");
            // Retained, warmed Field handles follow the declaration both ways (audit round 2 F1a).
            assertEquals("RETAINED1=final:false:set:ok", app.latest("RETAINED1="), app.tail());
            assertEquals("RETAINED4=final:true:set:refused", app.latest("RETAINED4="), app.tail());
        }
    }

    private void reload(WatchedApp app, String source, int expected) throws Exception {
        app.rewrite("Holder", source);
        long until = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < until && app.output().stream().filter(s -> s.contains("Reloaded app.Holder") || s.contains("Structural reload: app.Holder")).count() < expected) Thread.sleep(25);
    }
    private void probe(WatchedApp app, int n, String expected) throws Exception {
        Files.createFile(tmp.resolve("probe" + n)); app.awaitOrFail("STATE" + n + "=", "probe " + n);
        assertEquals(expected, app.latest("STATE" + n + "="), app.tail());
    }
    private static String holder(String field, int version) {
        return "package app; public class Holder { " + field + " public static int version() { return " + version + "; } }";
    }
    private static final String APP = """
            package app;
            import java.nio.file.*;
            public class App {
                static String state(java.lang.reflect.Field field) {
                    boolean fin = java.lang.reflect.Modifier.isFinal(field.getModifiers());
                    String set;
                    try { field.set(null, java.util.List.of("written")); set = "ok"; }
                    catch (IllegalAccessException refused) { set = "refused"; }
                    return "final:" + fin + ":set:" + set;
                }
                public static void main(String[] args) throws Exception {
                    java.lang.reflect.Field startup = null, mutable = null;
                    for (int n = 0; n < 5; n++) {
                        while (!Files.exists(Path.of(System.getProperty("test.dir"), "probe" + n))) Thread.sleep(10);
                        String state;
                        try {
                            var field = Holder.class.getField("VALUE");
                            field.get(null);
                            if (n == 0) startup = field;
                            if (n == 3) mutable = field;
                            state = state(field);
                        } catch (NoSuchFieldException missing) { state = "missing"; }
                        System.out.println("STATE" + n + "=" + state);
                        if (n == 1) System.out.println("RETAINED1=" + state(startup));
                        if (n == 4) System.out.println("RETAINED4=" + state(mutable));
                    }
                }
            }
            """;
}
