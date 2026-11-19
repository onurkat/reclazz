package com.onurkat.reclazztest.inherited;

import org.springframework.stereotype.Service;

import de.hybris.platform.servicelayer.internal.service.AbstractBusinessService;
import java.util.LinkedHashMap;
import java.util.Map;

/** Inherited platform setters and lifecycle callbacks belong to the parent bean definition. */
@Service("reclazzKeepStateInheritedService")
public class KeepStateInheritedService extends AbstractBusinessService {
    private final Map<String, Integer> visits = new LinkedHashMap<>();
    public String version() { return "inherited-v1"; }
    public boolean configured() { return modelService != null && sessionService != null && txManager != null && getCurrentTenant() != null; }
    public synchronized Map<String, Integer> hit() {
        visits.put("visits", visits.getOrDefault("visits", 0) + 1);
        return new LinkedHashMap<>(visits);
    }
}
