package com.onurkat.reclazztest.batch;

import de.hybris.platform.core.model.ReclazzSchemaProbeModel;
import java.util.*;

/** Unedited, bounded ownership and evidence across interceptor replacement. */
public final class ProbeInitDefaultsState {
    public static final class Scope {
        public final String run; public final int step;
        private ReclazzSchemaProbeModel model;
        private Scope(String run, int step) { this.run = run; this.step = step; }
    }
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private static String owner;
    private static int scopes;
    private static final Map<Integer, ReclazzSchemaProbeModel> HELD = new LinkedHashMap<>();
    private static final Map<Integer, Map<String,Object>> CALLS = new LinkedHashMap<>();
    private ProbeInitDefaultsState() {}
    public static synchronized void begin(String run, int step) {
        if (ACTIVE.get() != null || scopes != 0 || (owner != null && !owner.equals(run)) ||
                step < 0 || step > 3 || HELD.containsKey(step) || CALLS.containsKey(step))
            throw new IllegalStateException("Conflicting or repeated create scope");
        owner = run; scopes++; ACTIVE.set(new Scope(run, step));
    }
    public static synchronized void end() { if (ACTIVE.get() != null) { ACTIVE.remove(); scopes--; } }
    public static Scope scope() { return ACTIVE.get(); }
    public static synchronized void record(Scope scope, ReclazzSchemaProbeModel model, String version) {
        if (scope != ACTIVE.get() || (scope.model != null && scope.model != model))
            throw new IllegalStateException("Nested or foreign model");
        scope.model = model;
        var old = CALLS.get(scope.step);
        int count = old == null ? 0 : ((Number) old.get("calls")).intValue();
        CALLS.put(scope.step, Map.of("calls", count + 1, "version", version));
        // Capture even a partially initialized model for failure cleanup.
        HELD.put(scope.step, model);
    }
    public static synchronized void retain(String run, int step, ReclazzSchemaProbeModel model) {
        require(run);
        if (HELD.containsKey(step) && HELD.get(step) != model) throw new IllegalStateException("Create returned another model");
        HELD.put(step, model);
    }
    private static void require(String run) { if (owner != null && !owner.equals(run)) throw new IllegalArgumentException("Foreign run"); }
    public static synchronized ReclazzSchemaProbeModel model(String run, int step) {
        require(run); var model = HELD.get(step);
        if (model == null) throw new IllegalStateException("Missing owned model"); return model;
    }
    public static synchronized List<ReclazzSchemaProbeModel> models(String run) { require(run); return new ArrayList<>(HELD.values()); }
    public static synchronized Map<String,Object> snapshot(String run) {
        require(run); var result = new LinkedHashMap<String,Object>();
        CALLS.forEach((step, proof) -> result.put(step.toString(), Map.copyOf(proof))); return result;
    }
    public static synchronized int held() { return HELD.size(); }
    public static synchronized int scopes() { return scopes; }
    public static synchronized int entries() { return CALLS.size(); }
    public static synchronized void clear(String run) {
        require(run); end(); HELD.clear(); CALLS.clear(); owner = null;
    }
}
