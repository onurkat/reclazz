/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InheritedProtectedMembersReloadTest {
    @TempDir Path tmp;

    @Test void fieldsAndMethodsWithAnInstrumentedParent() throws Exception { exercise(true); }
    @Test void fieldsAndMethodsWithAnExternalParent() throws Exception { exercise(false); }

    private void exercise(boolean watchedParent) throws Exception {
        var builder = WatchedApp.in(tmp).with("Child", child("v1"))
                .with("App", driver());
        if (watchedParent) {
            builder.with("Base", base("b1"));
        } else {
            Path external = Files.createDirectories(tmp.resolve("external"));
            Path source = tmp.resolve("Base.java");
            Files.writeString(source, base("b1"));
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-proc:none", "-d", external.toString(), source.toString()));
            builder.classpath(external.toString());
        }
        try (WatchedApp app = builder.start()) {
            app.awaitOrFail(expected("v1", "b1"), "baseline did not serve");
            app.awaitOrFail("BENCH=v1", "baseline measurement did not complete");
            app.rewrite("Child", child("v2"));
            app.awaitOrFail(expected("v2", "b1"), "inherited protected access failed after a body edit");
            app.awaitOrFail("BENCH=v2", "edited body measurement did not complete");
            if (watchedParent) {
                app.rewrite("Base", base("b2"));
                app.awaitOrFail(expected("v2", "b2"), "parent reload did not reach super dispatch");
            }
            app.rewrite("Child", child("v3"));
            app.awaitOrFail(expected("v3", watchedParent ? "b2" : "b1"), "second body edit failed");
            app.awaitOrFail("BENCH=v3", "field-heavy body measurement did not complete");
            app.output().stream().filter(l -> l.startsWith("BENCH=")).forEach(System.out::println);
            assertFalse(app.output().stream().anyMatch(l -> l.startsWith("FAIL=")), app::tail);
            assertEquals(1, app.output().stream().filter(l -> l.startsWith("INSTANCE=")).count(),
                    "the driver must keep its original receiver");
        }
    }

    private static String expected(String tag, String parent) {
        return "VALUE=" + tag + "|7|9|11|13.5|ref|17|19|23|private|public|child|" + parent + "|static";
    }

    private static String base(String tag) {
        return """
                package app.base;
                public class Base {
                    protected int number;
                    protected long wide;
                    protected double decimal;
                    protected Object reference;
                    protected static int shared;
                    protected volatile int visible;
                    public int publicNumber;
                    protected String method() { return "%s"; }
                    protected static String staticMethod() { return "static"; }
                }
                """.formatted(tag);
    }

    private static String child(String tag) {
        return """
                package app.child;
                public class Child extends app.base.Base {
                    private String own = "private";
                    public String exposed = "public";
                    @Override protected String method() { return "child"; }
                    public String describe() {
                        number = 7; super.number = 9;
                        int first = number; number = 7;
                        wide = 11L; decimal = 13.5; reference = "ref";
                        shared = 17; super.shared = 19;
                        int staticFirst = shared; shared = 17;
                        visible = 23; publicNumber = 19;
                        return "%s|" + number + "|" + first + "|" + wide + "|" + decimal
                                + "|" + reference + "|" + shared + "|" + staticFirst + "|" + visible
                                + "|" + own + "|" + exposed + "|" + method() + "|" + super.method()
                                + "|" + staticMethod();
                    }
                }
                """.formatted(tag);
    }

    private static String driver() {
        return """
                package app;
                public class App {
                    public static void main(String[] args) throws Exception {
                        app.child.Child value = new app.child.Child();
                        System.out.println("INSTANCE=" + System.identityHashCode(value));
                        String previous = "";
                        while (true) {
                            Thread.sleep(200);
                            try {
                                String result = value.describe();
                                System.out.println("VALUE=" + result);
                                String version = result.substring(0, 2);
                                if (!version.equals(previous)) {
                                    int calls = 100000;
                                    long start = System.nanoTime();
                                    int checksum = 0;
                                    for (int i = 0; i < calls; i++) checksum += value.describe().length();
                                    System.out.println("BENCH=" + version + " calls=" + calls
                                            + " nanos=" + (System.nanoTime() - start) + " checksum=" + checksum);
                                    previous = version;
                                }
                            } catch (Throwable failure) {
                                System.out.println("FAIL=" + failure);
                                failure.printStackTrace(System.out);
                            }
                        }
                    }
                }
                """;
    }
}
