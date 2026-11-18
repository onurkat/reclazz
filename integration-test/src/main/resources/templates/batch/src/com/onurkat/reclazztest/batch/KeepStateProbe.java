package com.onurkat.reclazztest.batch;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Mutable state owned by the singleton-retention live regression. */
@Component("reclazzKeepStateProbe")
public class KeepStateProbe {
    private final Map<String, Integer> visits = new LinkedHashMap<>();
    public String version() { return "service-v1"; }
    public synchronized Map<String, Integer> hit() {
        visits.put("visits", visits.getOrDefault("visits", 0) + 1);
        return new LinkedHashMap<>(visits);
    }
}
