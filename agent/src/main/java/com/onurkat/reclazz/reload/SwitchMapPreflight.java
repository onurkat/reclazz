/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import java.util.*;

/** Parse the whole captured save before any companion target is published. */
public final class SwitchMapPreflight {
    private SwitchMapPreflight() { }
    public record Result(Map<String, SwitchMapPlan> plans, Map<String, String> refused) { }
    public static Result check(Map<String, byte[]> bytes) {
        Map<String, SwitchMapPlan> plans = new LinkedHashMap<>();
        Map<String, String> refused = new LinkedHashMap<>();
        Set<String> referenced = new HashSet<>();
        bytes.values().forEach(value -> SwitchMapPlan.references(value).forEach(name -> referenced.add(name.replace('/', '.'))));
        bytes.forEach((name, value) -> {
            if (SwitchMapPlan.candidate(value) || referenced.contains(name)) {
                var parsed = SwitchMapPlan.parse(value);
                if (parsed.plan() == null) refused.put(name, parsed.refusal());
                else plans.put(name, parsed.plan());
            }
        });
        bytes.forEach((name, value) -> {
            for (String holder : SwitchMapPlan.references(value)) {
                String reason = refused.get(holder.replace('/', '.'));
                if (reason != null) refused.put(name, reason);
            }
        });
        refused.keySet().forEach(plans::remove);
        return new Result(Map.copyOf(plans), Map.copyOf(refused));
    }
}
