/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.util.function.BiConsumer;

/** JDK-only bridge: explicit startup property levels, never actuator/runtime calls. */
public final class BootLoggingBaselineRecorder {
    private static final String LISTENER = "org.springframework.boot.context.logging.LoggingApplicationListener";
    private static final ThreadLocal<int[]> scopes = ThreadLocal.withInitial(() -> new int[2]);
    private static volatile BiConsumer<Object, String> recorder;
    private BootLoggingBaselineRecorder() { }

    public static void install(BiConsumer<Object, String> callback) {
        LookupCapture.requireTrusted(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass(),
                "installing the Boot logging baseline recorder");
        recorder = callback;
    }
    private static boolean listener(String method) {
        return StackWalker.getInstance().walk(frames -> frames.skip(2).findFirst()
                .map(f -> f.getClassName().equals(LISTENER) && f.getMethodName().equals(method)).orElse(false));
    }
    public static void beginStartup() {
        if (listener("initializeFinalLoggingLevels")) scopes.get()[0]++;
    }
    public static void endStartup() {
        if (!listener("initializeFinalLoggingLevels")) return;
        int[] scope = scopes.get();
        if (--scope[0] <= 0) scopes.remove();
    }
    public static void beginExplicit() {
        if (listener("setLogLevels") && scopes.get()[0] > 0) scopes.get()[1]++;
    }
    public static void endExplicit() {
        if (!listener("setLogLevels")) return;
        int[] scope = scopes.get();
        if (scope[1] > 0) scope[1]--;
        if (scope[0] == 0) scopes.remove();
    }
    public static void beforeSet(Object system, String name) {
        int[] scope = scopes.get();
        if (scope[0] == 0 || scope[1] == 0) { if (scope[0] == 0) scopes.remove(); return; }
        BiConsumer<Object, String> callback = recorder;
        if (callback != null) {
            // Diagnostics must never change Boot's startup or logger setter outcome.
            try { callback.accept(system, name); } catch (Throwable ignored) { }
        }
    }
}
