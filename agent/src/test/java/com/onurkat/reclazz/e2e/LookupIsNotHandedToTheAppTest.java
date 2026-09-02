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
 * Application code asking the agent for a capability, in a real JVM.
 *
 * <p>The engine keeps each watched class's own {@code MethodHandles.lookup()},
 * which carries private access to that class, and on a classpath application
 * {@code privateLookupIn} turns one of those into private access to everything
 * else on the classpath. The holder sits on the bootstrap classloader, so the
 * method that returns it is callable from every line of code in the process:
 * an expression language evaluating a submitted string, a deserialization
 * gadget that can reach a static method, ordinary application code.
 *
 * <p>None of those can write a class file into a watched directory, which is
 * the boundary the README draws around what running under Reclazz costs. So
 * handing them the same reach through a public method was quietly drawing it
 * somewhere else, and this is the test that it is no longer drawn there.
 *
 * <p>The unit tests cover the rule. This covers the part they cannot: that the
 * rule is switched on in a real agent start-up, and that switching it on did
 * not cost the engine the access it needs, which the reload in the second half
 * of the run is there to show.
 */class LookupIsNotHandedToTheAppTest {

    @TempDir
    Path tmp;

    @Test
    void theRunningApplicationCannotAskForAWatchedClassesLookup() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .with("Work", source("v1"))
                .with("App", """
                        package app;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                Work work = new Work();
                                System.out.println("APP_STARTED");

                                // Exactly what an injected expression would
                                // evaluate: a public static method on the boot
                                // classloader, called with a Class it can name.
                                try {
                                    Class<?> holder = Class.forName(
                                            "com.onurkat.reclazz.bootstrap.LookupCapture");
                                    Object lookup = holder.getMethod("get", Class.class)
                                            .invoke(null, Work.class);
                                    System.out.println("ASK=" + (lookup == null
                                            ? "NOTHING_CAPTURED" : "GOT_LOOKUP"));
                                } catch (java.lang.reflect.InvocationTargetException refused) {
                                    System.out.println("ASK=REFUSED "
                                            + refused.getCause().getClass().getSimpleName());
                                } catch (ClassNotFoundException notThere) {
                                    System.out.println("ASK=NO_SUCH_CLASS");
                                }

                                while (true) {
                                    Thread.sleep(400);
                                    System.out.println("SAW=" + work.tag());
                                }
                            }
                        }
                        """)
                .start()) {

            app.awaitOrFail("ASK=", "the app never got as far as asking");
            String answer = app.latest("ASK=");
            System.out.println("[diag] " + answer);

            assertTrue(answer.contains("REFUSED"),
                    () -> "application code was handed a watched class's private-access "
                            + "lookup: " + answer);
            assertTrue(answer.contains("SecurityException"),
                    () -> "refused, but not as a refusal: " + answer);

            // And the engine still has the access it needs: a reload after the
            // refusal has to work, or the door was shut on the wrong side.
            app.rewrite("Work", source("v2"));
            app.awaitOrFail("SAW=v2", "the guard cost the engine its own access");
        }
    }

    /** Structural, so the engine genuinely needs the lookup it captured. */
    private static String source(String value) {
        return """
                package app;
                public class Work {
                    private final String added = "%s";
                    public String tag() { return helper(); }
                    private String helper() { return added == null ? "%s" : added; }
                }
                """.formatted(value, value);
    }
}
