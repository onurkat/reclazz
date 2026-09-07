/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a field with an initialiser holds on an object that already existed.
 *
 * <p>A field's initialiser is constructor code, and the object was constructed
 * before the field was written. So adding {@code private final List<String>
 * cache = new ArrayList<>();} to a class and reloading used to give every live
 * instance of it a null, and the first method to touch it failed with a
 * NullPointerException on a line that reads as though it cannot produce one.
 * For a Spring singleton, and that is most of what a developer edits, every
 * instance is one that already existed.
 *
 * <p>Now the initialiser's own instructions are lifted out of the constructor
 * and run for the object on the field's first read, so the live instance reads
 * the value the developer wrote. What cannot be lifted, an initialiser that
 * needs a constructor argument the object no longer has, is still named at the
 * reload rather than found from a stack trace.
 */
class AddedFieldInitialiserTest {

    @TempDir
    Path tmp;

    @Test
    void anAddedFieldReadsItsInitialiserValueOnAnObjectThatAlreadyExisted() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .with("Holder", """
                        package app;
                        public class Holder {
                            public String describe() { return "no field yet"; }
                        }
                        """)
                .with("App", """
                        package app;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                Holder holder = new Holder();
                                System.out.println("APP_STARTED");
                                while (true) {
                                    Thread.sleep(400);
                                    String answer;
                                    long start = System.nanoTime();
                                    try {
                                        answer = holder.describe();
                                    } catch (Throwable failure) {
                                        answer = failure.getClass().getSimpleName();
                                    }
                                    long micros = (System.nanoTime() - start) / 1000;
                                    System.out.println("SAW=" + answer + " took=" + micros + "us");
                                }
                            }
                        }
                        """)
                .start()) {

            app.awaitOrFail("SAW=no field yet", "app did not serve the original");

            // The edit a developer makes without thinking about it: new
            // fields, with initialisers, read by an existing method. One is
            // an object, one a primitive, one computed from the object's own
            // state.
            app.rewrite("Holder", """
                    package app;
                    import java.util.*;
                    public class Holder {
                        private final String name = "holder";
                        private final List<String> cache = new ArrayList<>();
                        private int retries = 3;
                        private final String label = name.toUpperCase() + "!";
                        public String describe() {
                            cache.add("hit");
                            return "size " + cache.size() + " retries " + retries + " label " + label;
                        }
                    }
                    """);

            app.awaitOrFail("SAW=size 1 retries 3 label HOLDER!",
                    "the live instance did not read the initialisers' values");
            app.awaitOrFail("SAW=size 2 retries 3 label HOLDER!",
                    "the second read did not see the list the first read created");

            String first = firstLineContaining(app.output(), "SAW=size 1");
            String later = firstLineContaining(app.output(), "SAW=size 2");
            System.out.println("[diag] first read after the reload: " + first);
            System.out.println("[diag] a later read:               " + later);

            List<String> said = linesAboutAddedFields(app.output());
            System.out.println("[diag] agent said: " + String.join(" / ", said));
            assertTrue(said.stream().anyMatch(line -> line.contains("first read")
                            && line.contains("cache") && line.contains("retries")
                            && line.contains("label")),
                    () -> "the reload did not say the fields get their value on first read:\n"
                            + app.tail());
            assertTrue(said.stream().noneMatch(line -> line.contains("WARN")),
                    () -> "nothing here needs a warning: " + said);
        }
    }

    /**
     * A field assigned from a constructor argument cannot be given a value
     * later: the argument is gone. That is the field that still reads null on
     * a live object, and the reload has to name it and say why.
     */
    @Test
    void aFieldSetFromAConstructorArgumentIsStillNamed() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .with("Holder", """
                        package app;
                        public class Holder {
                            private final String name;
                            public Holder(String name) { this.name = name; }
                            public String describe() { return "name " + name; }
                        }
                        """)
                .with("App", """
                        package app;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                Holder holder = new Holder("x");
                                System.out.println("APP_STARTED");
                                while (true) {
                                    Thread.sleep(400);
                                    String answer;
                                    try {
                                        answer = holder.describe();
                                    } catch (Throwable failure) {
                                        answer = failure.getClass().getSimpleName();
                                    }
                                    System.out.println("SAW=" + answer);
                                }
                            }
                        }
                        """)
                .start()) {

            app.awaitOrFail("SAW=name x", "app did not serve the original");

            app.rewrite("Holder", """
                    package app;
                    public class Holder {
                        private final String name;
                        private final String upper;
                        public Holder(String name) { this.name = name; this.upper = name.toUpperCase(); }
                        public String describe() { return "name " + name + " upper " + upper; }
                    }
                    """);

            app.awaitOrFail("SAW=name x upper null",
                    "the live instance should keep answering, with the field it cannot have");

            List<String> said = linesAboutAddedFields(app.output());
            System.out.println("[diag] agent said: " + String.join(" / ", said));
            assertTrue(said.stream().anyMatch(line -> line.contains("WARN")
                            && line.contains("upper") && line.contains("constructor parameter")),
                    () -> "the reload did not name the field and the reason:\n" + app.tail());
        }
    }

    private static String firstLineContaining(List<String> output, String needle) {
        for (String line : output) {
            if (line.contains(needle)) return line;
        }
        return null;
    }

    /** Lines the agent printed about added fields. */
    private static List<String> linesAboutAddedFields(List<String> output) {
        List<String> found = new ArrayList<>();
        for (String line : output) {
            String lower = line.toLowerCase();
            if (lower.contains("[reclazz]") && lower.contains("field")
                    && (lower.contains("initialis") || lower.contains("existed"))) {
                found.add(line);
            }
        }
        return found;
    }
}
