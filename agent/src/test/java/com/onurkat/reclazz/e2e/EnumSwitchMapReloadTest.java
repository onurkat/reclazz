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

class EnumSwitchMapReloadTest {
    @TempDir Path tmp;
    @Test void newlyUsedConstantGetsItsBranch() throws Exception { check(1); }
    @Test void reorderedCasesKeepTheirMeaning() throws Exception { check(2); }
    @Test void addedSecondMapLinksAndReturnsItsBranch() throws Exception { check(3); }
    private void check(int shape) throws Exception {
        try (var app = start()) {
            app.awaitOrFail("RESULT=bank-v1|card-v1|other-v1", "initial branches");
            app.rewrite("Svc", source(shape));
            assertTrue(app.awaits("RESULT=" + (shape == 3 ? "fast|" : "") + "bank-v2|card-v2|wallet-v2", 12), app::tail);
        }
    }
    private WatchedApp start() throws Exception { return start(false); }
    private WatchedApp start(boolean verbose) throws Exception {
        return WatchedApp.in(tmp).agentArgs("startupDelaySec=0,debounceMs=100,verbose=" + verbose)
                .with("Channel", "package app; public enum Channel { BANK, CARD, WALLET }")
                .with("Mode", "package app; public enum Mode { FAST, SLOW }")
                .with("Svc", source(0)).with("App", """
                    package app;
                    public class App {
                        public static void main(String[] args) throws Exception {
                            var svc = new Svc(); boolean printed = false;
                            while (true) {
                                try { System.out.println("RESULT=" + svc.all()); }
                                catch (Throwable failure) { System.out.println("RESULT_ERROR=" + failure); if (!printed) { failure.printStackTrace(System.out); printed = true; } }
                                Thread.sleep(100);
                            }
                        }
                    }
                    """).start();
    }
    @Test void appendingEnumConstantAndCaseTogetherRefreshesTheNewSlot() throws Exception {
        try (var app = start(true)) {
            app.awaitOrFail("RESULT=bank-v1|card-v1|other-v1", "initial branches");
            app.rewrite("Svc", source(1));
            assertTrue(app.awaits("RESULT=bank-v2|card-v2|wallet-v2", 12), app::tail);
            app.rewriteAll(java.util.Map.of("Channel", "package app; public enum Channel { BANK, CARD, WALLET, EXTRA }",
                    "Svc", source(5)));
            assertTrue(app.awaits("RESULT=bank-v2|card-v2|wallet-v2|extra-v2", 12), () -> app.tail() + "\n" +
                    app.output().stream().filter(line -> line.contains("[Reclazz]")).limit(80).toList());
            app.recompile("Channel");
            assertTrue(app.awaits("Unchanged applied class skipped: app.Channel", 12), app::tail);
            assertEquals(1, app.output().stream().filter(line -> line.contains("Enum app.Channel gained [EXTRA]")).count(),
                    "the late event must not append the constant again");
        }
    }
    private static String source(int shape) {
        String cases = switch (shape) {
            case 0 -> "case BANK -> \"bank-v1\"; case CARD -> \"card-v1\"; default -> \"other-v1\";";
            case 2 -> "case WALLET -> \"wallet-v2\"; case BANK -> \"bank-v2\"; case CARD -> \"card-v2\";";
            default -> "case BANK -> \"bank-v2\"; case CARD -> \"card-v2\"; case WALLET -> \"wallet-v2\";";
        };
        if (shape == 5) cases += " case EXTRA -> \"extra-v2\";";
        String mode = shape == 3 ? "mode(Mode.FAST) + \"|\" + " : "";
        return "package app; public class Svc { public String label(Channel c) { return switch(c) { " + cases
                + " }; } private String safe(Channel c) { try { return label(c); } catch(Throwable f) { return f.getClass().getSimpleName(); } }"
                + " public String all() { return " + mode + "safe(Channel.BANK) + \"|\" + safe(Channel.CARD) + \"|\" + safe(Channel.WALLET)"
                + (shape == 5 ? " + \"|\" + safe(Channel.EXTRA)" : "") + "; } "
                + (shape == 3 ? "public String mode(Mode m) { return switch(m) { case FAST -> \"fast\"; case SLOW -> \"slow\"; }; }" : "") + " }";
    }
}
