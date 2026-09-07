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
 * A parent method calls another of its own methods that the child overrides,
 * and the parent is reloaded. Plain Java keeps dispatching the inner call to
 * the child's override; so must the reloaded body.
 */
class SelfInvocationAfterParentReloadTest {

    @TempDir
    Path tmp;

    @Test
    void aReloadedParentStillReachesTheChildsOverrideFromItsOwnBody() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .with("Base", base("b1"))
                .with("Derived", derived())
                .with("App", driver())
                .start()) {

            app.awaitOrFail("APP_STARTED", "the app did not start under the agent");
            app.awaitOrFail("VALUE=b1:dh", "the first version never served with the override");

            app.rewrite("Base", base("b2"));
            app.awaitOrFail("VALUE=b2:", "the reload of the parent never reached the app");
            Thread.sleep(700);
            assertEquals("VALUE=b2:dh", app.latest("VALUE="),
                    () -> "the reloaded parent body no longer reaches the child's override:\n" + app.tail());
        }
    }

    private static String base(String tag) {
        return """
                package app;
                public class Base {
                    public String describe() { return "%s:" + hook(); }
                    public String hook() { return "bh"; }
                }
                """.formatted(tag);
    }

    private static String derived() {
        return """
                package app;
                public class Derived extends Base {
                    @Override public String hook() { return "dh"; }
                }
                """;
    }

    private static String driver() {
        return """
                package app;
                public class App {
                    public static void main(String[] args) throws Exception {
                        Base d = new Derived();
                        System.out.println("APP_STARTED");
                        while (true) {
                            Thread.sleep(200);
                            System.out.println("VALUE=" + d.describe());
                        }
                    }
                }
                """;
    }
}
