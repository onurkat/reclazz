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
 * The way out, and what it costs.
 *
 * <p>When instrumentation itself is the problem, and it happens, the only
 * escape used to be detaching the agent: a developer who hit one bad class
 * commented out the whole thing and lost reloading for every other class in the
 * project. `excludePatterns` could not have helped, because it excludes files
 * from being watched and instrumentation is not watching; it happens at load
 * time whether the file is ever edited or not.
 *
 * <p>So an excluded class is left exactly as it would be without Reclazz, and
 * this is the test of what that leaves working. Method body changes still
 * reload, because that is the JVM's own redefinition and needs nothing from the
 * agent. Adding a member does not, and says which setting is the reason rather
 * than one of the other ways a class ends up uninstrumented.
 */
class ExcludedClassStillReloadsBodiesTest {

    @TempDir
    Path tmp;

    @Test
    void anExcludedClassKeepsBodyReloadAndSaysWhatItLost() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .agentArgs("startupDelaySec=1,debounceMs=200,excludeClasses=app.Awkward*")
                .with("Awkward", awkward("v1"))
                .with("Ordinary", ordinary("v1"))
                .with("App", """
                        package app;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                Awkward awkward = new Awkward();
                                Ordinary ordinary = new Ordinary();
                                System.out.println("APP_STARTED");
                                while (true) {
                                    Thread.sleep(400);
                                    System.out.println("SAW awkward=" + awkward.tag()
                                            + " ordinary=" + ordinary.tag());
                                }
                            }
                        }
                        """)
                .start()) {

            app.awaitOrFail("awkward=v1 ordinary=v1", "app did not start under the agent");

            // The half that has to survive being excluded.
            app.rewrite("Awkward", awkward("v2"));
            app.awaitOrFail("awkward=v2",
                    "an excluded class stopped reloading method bodies, which is the JVM's own "
                            + "redefinition and has nothing to do with the agent");

            // The half that does not, and the reason has to be this one rather
            // than the several other ways a class ends up uninstrumented.
            app.rewrite("Awkward", withAddedMethod("v3"));
            app.awaitOrFail("excludeClasses",
                    "adding a member to an excluded class did not say why it could not");

            String refusal = app.latest("excludeClasses");
            System.out.println("[diag] " + refusal);
            assertTrue(refusal.contains("Awkward"),
                    () -> "the refusal should name the class: " + refusal);

            // And nothing else was disabled by the exclusion.
            app.rewrite("Ordinary", ordinary("v9"));
            app.awaitOrFail("ordinary=v9",
                    "excluding one class stopped another one from reloading");
        }
    }

    private static String awkward(String tag) {
        return """
                package app;
                public class Awkward {
                    public String tag() { return "%s"; }
                }
                """.formatted(tag);
    }

    private static String withAddedMethod(String tag) {
        return """
                package app;
                public class Awkward {
                    public String tag() { return "%s"; }
                    public String added() { return "added"; }
                }
                """.formatted(tag);
    }

    private static String ordinary(String tag) {
        return """
                package app;
                public class Ordinary {
                    public String tag() { return "%s"; }
                }
                """.formatted(tag);
    }
}
