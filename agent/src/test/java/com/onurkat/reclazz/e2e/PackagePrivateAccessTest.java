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
 * Package-private access from a body that has moved into a companion.
 *
 * <p>A structural reload copies a method's bytecode into a companion class, and
 * the companion is a hidden class rather than the original. Instructions that
 * passed an access check in the original have to still pass it there. Protected
 * access is known not to, which is why cross-package calls are rewritten
 * through the target's own lookup; package-private access is assumed to,
 * because a hidden class defined through a class's lookup lands in that class's
 * runtime package.
 *
 * <p>That assumption is written in a comment and load-bearing: package-private
 * helpers, fields and classes are ordinary Java, and if the assumption were
 * wrong every codebase using them would get IllegalAccessError from the first
 * structural reload. So it is measured here rather than reasoned about, across
 * the four shapes: calling a package-private method, reading and writing a
 * package-private field, and touching a package-private class.
 */
class PackagePrivateAccessTest {

    @TempDir
    Path tmp;

    @Test
    void aCompanionStillReachesThePackagePrivateThingsItsBodyUses() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .with("Helper", """
                        package app;
                        class Helper {
                            String token = "token-v1";
                            String secret() { return "secret-v1"; }
                            static String staticSecret() { return "static-v1"; }
                        }
                        """)
                .with("Caller", caller("v1"))
                .with("App", """
                        package app;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                Caller caller = new Caller();
                                System.out.println("APP_STARTED");
                                while (true) {
                                    Thread.sleep(400);
                                    String seen;
                                    try {
                                        seen = caller.describe();
                                    } catch (Throwable failure) {
                                        seen = failure.getClass().getSimpleName() + ": "
                                                + failure.getMessage();
                                    }
                                    System.out.println("SAW=" + seen);
                                }
                            }
                        }
                        """)
                .start()) {

            app.awaitOrFail("SAW=v1|secret-v1|token-v1|static-v1|written-v1",
                    "the app did not start with package-private access working");

            // A structural change: the body moves into a companion, and every
            // one of those accesses goes with it.
            app.rewrite("Caller", callerWithAddedMember("v2"));
            app.awaitOrFail("SAW=v2|secret-v1|token-v1|static-v1|written-v2",
                    "package-private access broke once the body moved to a companion");

            String seen = app.latest("SAW=");
            System.out.println("[diag] " + seen);
            assertFalse(seen.contains("IllegalAccessError"),
                    () -> "the companion could not reach what its body reaches: " + seen);

            // And the package-private class itself is still usable from there.
            app.rewrite("Helper", """
                    package app;
                    class Helper {
                        String token = "token-v2";
                        String secret() { return "secret-v2"; }
                        static String staticSecret() { return "static-v2"; }
                    }
                    """);
            app.awaitOrFail("secret-v2",
                    "reloading the package-private class did not reach its caller");
        }
    }

    private static String caller(String tag) {
        return """
                package app;
                public class Caller {
                    private final Helper helper = new Helper();
                    public String describe() {
                        helper.token = helper.token;              // package-private write
                        return "%s|" + helper.secret()            // package-private call
                                + "|" + helper.token              // package-private read
                                + "|" + Helper.staticSecret()     // package-private static
                                + "|written-%s";
                    }
                }
                """.formatted(tag, tag);
    }

    /** Adds a member, which is what forces the body into a companion. */
    private static String callerWithAddedMember(String tag) {
        return """
                package app;
                public class Caller {
                    private final Helper helper = new Helper();
                    private String added = "added";
                    public String describe() {
                        helper.token = helper.token;
                        return "%s|" + helper.secret()
                                + "|" + helper.token
                                + "|" + Helper.staticSecret()
                                + "|written-%s";
                    }
                    public String addedMethod() { return added; }
                }
                """.formatted(tag, tag);
    }
}
