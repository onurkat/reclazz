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
 * A package-private method is not overridden from another package, and it has
 * to stay that way under the agent.
 *
 * <p>The JVM's rule is one of the quieter ones: a subclass in a different
 * package declaring the same signature does not override a package-private
 * method, it declares an unrelated one, and a call made inside the parent's
 * package keeps reaching the parent's body. Frameworks rely on it, and so does
 * anyone who has ever named a helper the same thing twice.
 *
 * <p>Reclazz turns every method into a dispatch and a renamed body, and
 * rewrites call sites to invokedynamic that resolves through a lookup. Any of
 * those steps could resolve by name and signature and quietly start calling the
 * subclass's method instead, which would be the tool changing what the program
 * does while reporting a successful reload. Measured here without a reload,
 * where the trampolines alone could change it, and again after a structural one
 * that moves both bodies into companions.
 */
class PackagePrivateOverrideTest {

    @TempDir
    Path tmp;

    @Test
    void aSubclassInAnotherPackageDoesNotTakeOverAPackagePrivateMethod() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .with("Base", base("a.Base-v1"))
                .with("Sub", sub("b.Sub-v1"))
                .with("App", """
                        package app;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                a.Base asSub = new b.Sub();
                                System.out.println("APP_STARTED");
                                while (true) {
                                    Thread.sleep(400);
                                    String seen;
                                    try {
                                        seen = asSub.call();
                                    } catch (Throwable failure) {
                                        seen = failure.getClass().getSimpleName();
                                    }
                                    System.out.println("SAW=" + seen);
                                }
                            }
                        }
                        """)
                .start()) {

            // What javac and the JVM do without any of this: the call inside
            // Base reaches Base's own who(), because Sub is in another package.
            app.awaitOrFail("SAW=a.Base-v1",
                    "the agent changed which method a package-private call reaches, before any "
                            + "reload, with trampolines alone");

            // And after a structural reload, where both bodies are in companions.
            app.rewrite("Base", baseWithAddedMember("a.Base-v2"));
            app.awaitOrFail("SAW=a.Base-v2",
                    "the reload moved the body to a companion and the call started reaching the "
                            + "subclass instead");

            String seen = app.latest("SAW=");
            System.out.println("[diag] " + seen);
            assertFalse(seen.contains("b.Sub"),
                    () -> "a subclass in another package took over a package-private method: "
                            + seen);
        }
    }

    private static String base(String tag) {
        return """
                package a;
                public class Base {
                    public String call() { return who(); }
                    String who() { return "%s"; }
                }
                """.formatted(tag);
    }

    private static String baseWithAddedMember(String tag) {
        return """
                package a;
                public class Base {
                    private String added = "added";
                    public String call() { return who(); }
                    String who() { return "%s"; }
                    public String addedMethod() { return added; }
                }
                """.formatted(tag);
    }

    /** Same signature, different package, so not an override. */
    private static String sub(String tag) {
        return """
                package b;
                public class Sub extends a.Base {
                    String who() { return "%s"; }
                }
                """.formatted(tag);
    }
}
