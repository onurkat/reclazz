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

/** Added plain and type-annotated getters remain visible without a false restart warning. */
class AddedMethodIsAnnouncedTest {

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
                .with("TypeMark", """
                        package app;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE)
                        public @interface TypeMark {}
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
                                    String token;
                                    try {
                                        Method tokenMethod=Dto.class.getMethod("getToken");
                                        token = "VISIBLE:" + tokenMethod.invoke(dto) + ":annotated=" + tokenMethod.getAnnotatedReturnType().isAnnotationPresent(TypeMark.class);
                                    } catch (NoSuchMethodException absent) {
                                        token = "INVISIBLE";
                                    }
                                    System.out.println("STATE name=" + dto.getName()
                                            + " getEmail=" + seen + " getToken=" + token);
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
            app.awaitOrFail("name=after getEmail=VISIBLE:e@x getToken=INVISIBLE",
                    "the plain added getter was not visible and invokable");
            assertTrue(app.latest("STATE ").contains("getEmail=VISIBLE:e@x"), app.tail());

            // Type-use metadata must be visible on the same logical Method root.
            app.rewrite("Dto", """
                    package app;
                    public class Dto {
                        public String getName() { return "after2"; }
                        public String getEmail() { return "e@x"; }
                        public @TypeMark String getToken() { return "token"; }
                    }
                    """);
            app.awaitOrFail("name=after2 getEmail=VISIBLE:e@x getToken=VISIBLE:token:annotated=true",
                    "the added type-use annotation must be visible and invokable");
            assertNull(app.latest("getToken() was added"),"a visible annotated getter must not warn");
            assertNull(app.latest("getEmail() was added"),
                    "a visible getter must not receive a false restart warning:\n" + app.tail());
        }
    }
}
