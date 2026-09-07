/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One save adds a method to a class and a call to it in another. Reloaded
 * caller first, the caller's new body reaches for a method the callee does
 * not have yet, and every call in between throws. Callee first, no call
 * ever meets a shape it was not written against.
 */
class CallerAndCalleeSavedTogetherTest {

    @TempDir
    Path tmp;

    @Test
    void aCallToAMethodAddedInTheSameSaveNeverFails() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .with("Caller", caller("value"))
                .with("Target", target(""))
                .with("App", driver())
                .start()) {

            app.awaitOrFail("APP_STARTED", "the app did not start under the agent");
            app.awaitOrFail("last=t1 ", "the first version never served");
            // Through the watcher, not the start-up catch-up: a save that lands
            // while the agent is still starting is re-dispatched by a different
            // route, and this test is about the route a working session uses.
            app.awaitOrFail("Content-hash baseline", "the watcher never took its baseline");

            Map<String, String> save = new LinkedHashMap<>();
            save.put("Caller", caller("extra"));
            save.put("Target", target("    public String extra() { return \"extra\"; }\n"));
            app.rewriteAll(save);

            app.awaitOrFail("last=extra ", "the save never reached the app");
            Thread.sleep(500);
            String state = app.latest("STATE ");
            System.out.println("[diag] " + state);
            assertTrue(state.contains("errors=0"),
                    () -> "a call from the reloaded caller failed before the callee was reloaded:\n" + app.tail());
            assertTrue(app.output().stream().anyMatch(l -> l.contains("2 class files changed together")),
                    () -> "the two files were expected to arrive as one batch:\n" + app.tail());
        }
    }

    private static String caller(String method) {
        return """
                package app;
                public class Caller {
                    public String go() { return new Target().%s(); }
                }
                """.formatted(method);
    }

    private static String target(String extra) {
        return """
                package app;
                public class Target {
                    public String value() { return "t1"; }
                %s}
                """.formatted(extra);
    }

    private static String driver() {
        return """
                package app;
                import java.util.*;
                import java.util.concurrent.atomic.*;
                public class App {
                    static final AtomicLong calls = new AtomicLong();
                    static final List<String> errors = Collections.synchronizedList(new ArrayList<>());
                    static volatile String last = "none";
                    public static void main(String[] args) throws Exception {
                        Caller caller = new Caller();
                        for (int i = 0; i < 4; i++) {
                            Thread t = new Thread(() -> {
                                while (true) {
                                    try {
                                        last = caller.go();
                                        calls.incrementAndGet();
                                    } catch (Throwable f) {
                                        if (errors.size() < 5) errors.add(f.getClass().getSimpleName() + ": " + f.getMessage());
                                    }
                                }
                            });
                            t.setDaemon(true); t.start();
                        }
                        System.out.println("APP_STARTED");
                        while (true) {
                            Thread.sleep(200);
                            System.out.println("STATE last=" + last + " calls=" + calls.get()
                                    + " errors=" + errors.size() + (errors.isEmpty() ? "" : " first=" + errors.get(0)));
                        }
                    }
                }
                """;
    }
}
