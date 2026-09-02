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
 * cache = new ArrayList<>();} to a class and reloading gives every live
 * instance of it a null, and the first method to touch it fails with a
 * NullPointerException on a line that reads as though it cannot produce one.
 * For a Spring singleton, and that is most of what a developer edits, every
 * instance is one that already existed.
 *
 * <p>Nothing can be done about the value: running the initialiser would mean
 * re-running the constructor on a live object, which would also reset the
 * fields it already has. What can be done is saying so, at the moment the class
 * is reloaded rather than when a request happens to hit the line, and this pins
 * that the warning is there and names the field.
 */class AddedFieldInitialiserTest {

    @TempDir
    Path tmp;

    @Test
    void addingAnInitialisedFieldWarnsThatLiveInstancesDoNotGetTheValue() throws Exception {
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

            app.awaitOrFail("SAW=no field yet", "app did not serve the original");

            // The edit a developer makes without thinking about it: a new
            // field, with an initialiser, read by an existing method.
            app.rewrite("Holder", """
                    package app;
                    import java.util.*;
                    public class Holder {
                        private final List<String> cache = new ArrayList<>();
                        public String describe() { return "size " + cache.size(); }
                    }
                    """);
            Thread.sleep(5000);

            String reached = app.latest("SAW=");
            System.out.println("[diag] live instance answered: " + reached);
            List<String> warnings = warnings(app.output());
            System.out.println("[diag] agent said: " + String.join(" / ", warnings));

            assertNotNull(reached, () -> "nothing was served after the reload:\n" + app.tail());

            // Whatever the value turns out to be, the developer has to have
            // been told: a null they did not write is not something to find
            // from a stack trace an hour later.
            assertFalse(warnings.isEmpty(),
                    () -> "adding an initialised field to a class with live instances said "
                            + "nothing:\n" + app.tail());
            assertTrue(warnings.stream().anyMatch(w -> w.contains("cache")),
                    () -> "the warning did not name the field that will be empty: " + warnings);
        }
    }

    /** Lines the agent printed about added fields, whatever it decided to call them. */
    private static List<String> warnings(List<String> output) {
        List<String> found = new ArrayList<>();
        for (String line : output) {
            String lower = line.toLowerCase();
            boolean aboutFields = lower.contains("field");
            boolean aboutInstances = lower.contains("existing") || lower.contains("live")
                    || lower.contains("instance") || lower.contains("initialis")
                    || lower.contains("initializ") || lower.contains("default");
            if (aboutFields && aboutInstances) found.add(line);
        }
        return found;
    }
}
