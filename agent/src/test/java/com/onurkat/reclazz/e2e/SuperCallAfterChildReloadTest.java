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
 * A child's edited method calls super, and the parent has never been
 * reloaded: the ordinary case of editing an override.
 *
 * <p>The edited body runs in a companion class, whose super call resolves
 * through the parent's trampoline. That trampoline's call site resolved the
 * parent's renamed copy virtually, and the child's own renamed copy has the
 * same name, so the call landed on the child's previous body: {@code d2/d1/b1}
 * where {@code d2/b1} was written, on every call, for as long as the parent
 * went unreloaded. Under load the same thing showed as a handful of torn
 * values during the reload; single-threaded it is simply the answer.
 */
class SuperCallAfterChildReloadTest {

    @TempDir
    Path tmp;

    @Test
    void anEditedOverrideStillCallsTheParentItExtends() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .with("Base", base("b1"))
                .with("Derived", derived("d1"))
                .with("App", driver())
                .start()) {

            app.awaitOrFail("APP_STARTED", "the app did not start under the agent");
            app.awaitOrFail("VALUE=d1/b1", "the first version never served");

            // The child alone. The parent keeps the body it was loaded with.
            app.rewrite("Derived", derived("d2"));
            app.awaitOrFail("VALUE=d2/", "the reload of the child never reached the app");
            settle(app);
            assertEquals("VALUE=d2/b1", app.latest("VALUE="),
                    () -> "super.describe() from the edited child did not reach the parent:\n" + app.tail());

            // Then the parent, then the child again: both routes to the
            // parent's body, its renamed copy and its companion, must agree.
            app.rewrite("Base", base("b2"));
            app.awaitOrFail("VALUE=d2/b2", "the reload of the parent never reached the child's super call");
            app.rewrite("Derived", derived("d3"));
            app.awaitOrFail("VALUE=d3/", "the second reload of the child never reached the app");
            settle(app);
            assertEquals("VALUE=d3/b2", app.latest("VALUE="),
                    () -> "super.describe() went astray after the parent had been reloaded too:\n" + app.tail());

            assertFalse(app.output().stream().anyMatch(l -> l.startsWith("VALUE=") && l.split("/").length != 2),
                    () -> "a value with the wrong number of halves was served:\n" + app.tail());
        }
    }

    /** A few more prints, so a value that only settles after a moment is caught. */
    private static void settle(WatchedApp app) throws InterruptedException {
        Thread.sleep(700);
    }

    private static String base(String tag) {
        return """
                package app;
                public class Base {
                    public String describe() { return "%s"; }
                }
                """.formatted(tag);
    }

    private static String derived(String tag) {
        return """
                package app;
                public class Derived extends Base {
                    @Override public String describe() { return "%s/" + super.describe(); }
                }
                """.formatted(tag);
    }

    private static String driver() {
        return """
                package app;
                public class App {
                    public static void main(String[] args) throws Exception {
                        Derived derived = new Derived();
                        System.out.println("APP_STARTED");
                        while (true) {
                            Thread.sleep(200);
                            System.out.println("VALUE=" + derived.describe());
                        }
                    }
                }
                """;
    }
}
