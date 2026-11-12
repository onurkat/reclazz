package com.onurkat.reclazztest.batch;

import java.util.*;

/** Bounded observations of real ticks, enabled only for one synthetic run. */
public final class ProbeScheduledMetadataState {
    private static String run;
    private static int phase;
    private static long total;
    private static long windowStart;
    private static final IdentityHashMap<Object, Long> receivers = new IdentityHashMap<>();
    private static final ArrayDeque<Map<String, Object>> samples = new ArrayDeque<>();
    private ProbeScheduledMetadataState() {}
    private static void validate(String nonce) {
        if (!nonce.matches("[a-z0-9-]{1,60}")) throw new IllegalArgumentException("Invalid synthetic nonce");
    }
    private static void requireRun(String nonce) {
        validate(nonce);
        if (!nonce.equals(run)) throw new IllegalStateException("Wrong owned scheduling run");
    }
    private static long receiver(Object target) {
        return receivers.computeIfAbsent(target, ignored -> (long) receivers.size() + 1);
    }
    public static synchronized void begin(String nonce) {
        validate(nonce);
        if (run != null) throw new IllegalStateException("An owned scheduling run is active");
        run = nonce; phase = 0; total = 0; windowStart = System.nanoTime();
        samples.clear(); receivers.clear();
    }
    public static synchronized void phase(String nonce, int next) {
        requireRun(nonce);
        if (next != phase + 1 || next > 3) throw new IllegalArgumentException("Wrong phase order");
        phase = next; windowStart = System.nanoTime(); samples.clear();
    }
    public static synchronized void record(Object target) {
        if (run == null || phase == 0) return;
        samples.addLast(Map.of("sequence", ++total, "nano", System.nanoTime(), "receiver", receiver(target), "phase", phase));
        while (samples.size() > 32) samples.removeFirst();
    }
    public static synchronized Map<String, Object> snapshot(String nonce, Object current) {
        requireRun(nonce);
        return Map.of("nonce", nonce, "active", true, "phase", phase, "total", total,
                "windowStart", windowStart, "receiver", receiver(current), "samples", List.copyOf(samples));
    }
    public static synchronized Map<String, Object> cleanup(String nonce) {
        validate(nonce);
        if (run != null && !run.equals(nonce)) throw new IllegalStateException("Foreign cleanup refused");
        run = null; phase = 0; total = 0; windowStart = 0; samples.clear(); receivers.clear();
        return Map.of("nonce", nonce, "active", false, "samples", samples.size(), "retainedReceivers", receivers.size(), "total", total);
    }
}
