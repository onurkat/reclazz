/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every reload builds a class. This is the test that they go away again.
 *
 * <p>A method body lives in a companion class, so reloading one class thirty
 * times builds thirty companions. Anything in the agent that held on to one of
 * them, a cache keyed by version, a MethodHandle kept for the previous
 * dispatch, a list of what has been reloaded, would turn a day of editing into
 * a metaspace exhaustion, and it would do it invisibly: the developer would see
 * an OutOfMemoryError pointing at whatever code happened to load the next
 * class.
 *
 * <p>The JVM's unloaded-class counter is what says they go away, and it is a
 * strong signal precisely because a class is only unloaded when nothing
 * references it. Measured over 30 reloads, it rises one for one with them.
 *
 * <p>What this deliberately does not assert is that memory stops growing. It
 * does grow, and not because of Reclazz: redefining a class costs metaspace
 * that the JVM never returns, since it keeps the previous version around for
 * threads still running the old code. Measured on stock JDK 21 with a bare
 * {@code redefineClasses} loop and no agent involved, 10.6 KB per redefinition,
 * against Reclazz's 8.5 KB per reload. Asserting on the total would be
 * asserting on HotSpot's behaviour; MetaspaceWatch is what tells the developer
 * about it when it starts to matter.
 *
 * <p>Skipped when the agent jar has not been built.
 */class CompanionsAreCollectedTest {

    private static final int RELOADS = 30;

    /** Unloading is the GC's business, so a few may still be pending at the end. */
    private static final int SLACK = 5;

    @TempDir
    Path tmp;

    @Test
    void everyReloadsCompanionIsCollectedAgain() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .with("Work", source(0))
                .with("App", """
                        package app;
                        import java.lang.management.*;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                Work work = new Work();
                                System.out.println("APP_STARTED");
                                while (true) {
                                    Thread.sleep(500);
                                    // The companions are only collectable, not
                                    // collected, until something asks.
                                    System.gc();
                                    ClassLoadingMXBean classes =
                                            ManagementFactory.getClassLoadingMXBean();
                                    System.out.println("MEM tag=" + work.tag()
                                            + " loaded=" + classes.getLoadedClassCount()
                                            + " unloaded=" + classes.getUnloadedClassCount());
                                }
                            }
                        }
                        """)
                .start()) {

            app.awaitOrFail("tag=v0 ", "app did not start under the agent");
            long unloadedBefore = readCount(app.latest("MEM "), "unloaded=");

            for (int i = 1; i <= RELOADS; i++) {
                app.rewrite("Work", source(i));
                app.awaitOrFail("tag=v" + i + " ", "reload " + i + " never reached the app");
            }
            Thread.sleep(3000);

            String finalState = app.latest("MEM ");
            System.out.println("[diag] " + finalState);
            long unloaded = readCount(finalState, "unloaded=") - unloadedBefore;

            assertTrue(unloaded >= RELOADS - SLACK,
                    () -> "only " + unloaded + " classes were unloaded across " + RELOADS
                            + " reloads, so the agent is holding companions it built: "
                            + finalState);
        }
    }

    /** Structural on every save, so a companion is built every time. */
    private static String source(int version) {
        return """
                package app;
                public class Work {
                    private final String added = "v%d";
                    public String tag() { return helper(); }
                    private String helper() { return added == null ? "v%d" : added; }
                }
                """.formatted(version, version);
    }

    private static long readCount(String line, String key) {
        assertNotNull(line, "the app never reported its class counts");
        int at = line.indexOf(key) + key.length();
        int end = line.indexOf(' ', at);
        return Long.parseLong(end < 0 ? line.substring(at) : line.substring(at, end));
    }
}
