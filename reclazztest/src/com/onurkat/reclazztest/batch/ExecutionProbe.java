package com.onurkat.reclazztest.batch;

import java.util.concurrent.ConcurrentHashMap;

/** Stable nonce-owned observation, separate from classes whose bodies are edited. */
public final class ExecutionProbe {
    private static final ConcurrentHashMap<String, String> versions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> calls = new ConcurrentHashMap<>();
    private ExecutionProbe() {}
    public static void record(String code, String version) {
        versions.put(code, version); calls.merge(code, 1, Integer::sum);
    }
    public static String version(String code) { return versions.getOrDefault(code, "absent"); }
    public static int calls(String code) { return calls.getOrDefault(code, 0); }
    public static void clear(String code) { versions.remove(code); calls.remove(code); }
}
