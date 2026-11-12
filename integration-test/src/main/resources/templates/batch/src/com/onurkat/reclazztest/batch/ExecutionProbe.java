package com.onurkat.reclazztest.batch;

import java.util.concurrent.ConcurrentHashMap;

/** Stable nonce-owned observation, separate from classes whose bodies are edited. */
public final class ExecutionProbe {
    private static final ConcurrentHashMap<String, String> versions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> calls = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> times = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> threads = new ConcurrentHashMap<>();
    private ExecutionProbe() {}
    public static void record(String code, String version) {
        versions.put(code, version); times.put(code, System.currentTimeMillis());
        threads.put(code, Thread.currentThread().getName()); calls.merge(code, 1, Integer::sum);
    }
    public static String version(String code) { return versions.getOrDefault(code, "absent"); }
    public static int calls(String code) { return calls.getOrDefault(code, 0); }
    public static long time(String code) { return times.getOrDefault(code, 0L); }
    public static String thread(String code) { return threads.getOrDefault(code, "absent"); }
    public static void clear(String code) { versions.remove(code); calls.remove(code); times.remove(code); threads.remove(code); }
}
