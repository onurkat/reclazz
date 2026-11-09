package com.onurkat.reclazztest.interceptors;

/** Request-owned observations; unrelated model saves cannot satisfy the proof. */
public final class AddedMappingProbe {
    private static final ThreadLocal<Observation> CURRENT = new ThreadLocal<>();
    private static final class Observation {
        final String code;
        int calls;
        Object interceptor;
        final java.util.Map<Object,Integer> byInstance = new java.util.IdentityHashMap<>();
        Observation(String code) { this.code = code; }
    }
    public static void begin(String code) { CURRENT.set(new Observation(code)); }
    public static void record(String code, Object interceptor) {
        Observation observation = CURRENT.get();
        if (observation != null && observation.code.equals(code)) {
            observation.calls++;
            observation.byInstance.merge(interceptor, 1, Integer::sum);
            observation.interceptor = interceptor;
        }
    }
    public static int calls() { return CURRENT.get() == null ? 0 : CURRENT.get().calls; }
    public static int callsBy(Object interceptor) {
        return CURRENT.get() == null ? 0 : CURRENT.get().byInstance.getOrDefault(interceptor, 0);
    }
    public static boolean calledBy(Object expected) {
        return CURRENT.get() != null && expected != null && CURRENT.get().interceptor == expected;
    }
    public static void clear() { CURRENT.remove(); }
    private AddedMappingProbe() {}
}
