package com.onurkat.reclazztest.batch;

import java.util.Map;

/** Synthetic first-load state; no Spring lifecycle or persistent data. */
public class StaticProbe {
    public static final Map<String, String> CODES = Map.of("key", "v1");
    public static int initializerCalls = 0;
    public static final String REFUSED = applicationValue("call-v1");
    public static final int COUNT = Integer.parseInt("7");
    public static String runtimeValue = "startup";
    public static final Object IDENTITY = new Object();

    private static String applicationValue(String value) { initializerCalls++; return value; }
    public static String version() { return "body-v1"; }
}
