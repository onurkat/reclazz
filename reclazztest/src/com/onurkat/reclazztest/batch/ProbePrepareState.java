package com.onurkat.reclazztest.batch;

import java.util.LinkedHashMap;
import java.util.Map;

/** Separate from the edited bean so a refresh cannot erase duplicate calls. */
public final class ProbePrepareState {
    private static final Map<String, Map<Integer, Map<String, Object>>> CALLS = new LinkedHashMap<>();
    private ProbePrepareState() {}

    public static synchronized void record(String run, int step, String version) {
        var steps = CALLS.computeIfAbsent(run, key -> new LinkedHashMap<>());
        var previous = steps.get(step);
        int count = previous == null ? 0 : ((Number) previous.get("calls")).intValue();
        steps.put(step, Map.of("calls", count + 1, "version", version));
    }
    public static synchronized Map<String, Object> snapshot(String run) {
        var result = new LinkedHashMap<String, Object>();
        CALLS.getOrDefault(run, Map.of()).forEach((step, proof) -> result.put(step.toString(), Map.copyOf(proof)));
        return result;
    }
    public static synchronized int entries() { return CALLS.size(); }
    public static synchronized void clear(String run) { CALLS.remove(run); }
}
