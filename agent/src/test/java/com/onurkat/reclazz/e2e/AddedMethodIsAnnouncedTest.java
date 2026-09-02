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
 * Adding a getter, and being told what it will and will not do.
 *
 * <p>A method added by a reload lives in the companion class, because a stock
 * JDK will not put a new method on a loaded one. Calls from the developer's own
 * code reach it, since those call sites are rewritten. Reflection does not, and
 * reflection is how frameworks find methods, so an added getter is not
 * serialised, an added {@code @Bean} method is not a bean and an added
 * {@code @Scheduled} method never runs.
 *
 * <p>What made that worth a round of work is not the wall, which is the JDK's,
 * but the silence in front of it: the reload succeeded, the log said so, and
 * the thing the developer had just written did nothing at all.
 *
 * <p>Both halves are measured here, on a running JVM: the wall is real, and it
 * is now announced by name.
 */class AddedMethodIsAnnouncedTest {

    @TempDir
    Path tmp;

    @Test
    void anAddedGetterIsNamedRatherThanSilentlyIgnored() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .with("Dto", """
                        package app;
                        public class Dto {
                            public String getName() { return "before"; }
                        }
                        """)
                .with("App", """
                        package app;
                        import java.lang.reflect.Method;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                Dto dto = new Dto();
                                System.out.println("APP_STARTED");
                                while (true) {
                                    Thread.sleep(400);
                                    String seen;
                                    try {
                                        Method added = Dto.class.getMethod("getEmail");
                                        seen = "VISIBLE:" + added.invoke(dto);
                                    } catch (NoSuchMethodException absent) {
                                        seen = "INVISIBLE";
                                    }
                                    System.out.println("STATE name=" + dto.getName()
                                            + " getEmail=" + seen);
                                }
                            }
                        }
                        """)
                .start()) {

            app.awaitOrFail("name=before", "app did not start under the agent");

            // One save that does two things: changes a body, which is the
            // control that says the reload landed, and adds a getter, which is
            // the case under test.
            app.rewrite("Dto", """
                    package app;
                    public class Dto {
                        public String getName() { return "after"; }
                        public String getEmail() { return "e@x"; }
                    }
                    """);
            app.awaitOrFail("name=after",
                    "the reload never landed, so nothing here is about added methods");

            String state = app.latest("STATE ");
            System.out.println("[diag] " + state);
            assertTrue(state.contains("getEmail=INVISIBLE"),
                    () -> "reflection found an added method, so this JDK is not the one the "
                            + "warning is for: " + state);

            String warning = app.latest("getEmail()");
            System.out.println("[diag] " + warning);
            assertNotNull(warning,
                    () -> "the getter was added, did nothing, and nothing was said:\n" + app.tail());
            assertTrue(warning.contains("serialisation"),
                    () -> "warned, but not about what will not happen: " + warning);
        }
    }
}
