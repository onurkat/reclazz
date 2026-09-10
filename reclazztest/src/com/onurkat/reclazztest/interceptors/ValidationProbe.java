package com.onurkat.reclazztest.interceptors;

/** Request-local evidence: unrelated traffic and earlier saves cannot satisfy the test. */
public final class ValidationProbe {
    private static final ThreadLocal<Observation> CURRENT = new ThreadLocal<>();
    private static final class Observation {
        final String code;
        String last = "none";
        int calls;
        Observation(String code) { this.code = code; }
    }
    public static void begin(String code) { CURRENT.set(new Observation(code)); }
    public static void record(String code, String value) {
        Observation observation = CURRENT.get();
        if (observation != null && observation.code.equals(code)) {
            observation.last = value;
            observation.calls++;
        }
    }
    public static String result() {
        Observation observation = CURRENT.get();
        return observation == null ? "none|calls=0" : observation.last + "|calls=" + observation.calls;
    }
    public static void clear() { CURRENT.remove(); }
    private ValidationProbe() {}
}
