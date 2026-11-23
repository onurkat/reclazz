/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import java.util.Map;

/**
 * R35: SAP Commerce OCC computes the BASIC, DEFAULT and FULL field sets of a DTO from its declared fields once and keeps
 * them in DefaultFieldSetLevelHelper.levelMap, next to the levels configured in XML. After a reload that changes a DTO's
 * members, the entries of that class and its subclasses go back to what the XML configured (recorded when the helper was
 * configured), so the next {@code fields=FULL} request lists the current fields and configured levels stay.
 */
final class OccFieldSetCaches {
    private OccFieldSetCaches() { }

    @SuppressWarnings("unchecked")
    static void flush(Class<?> owner) {
        for (Object helper : com.onurkat.reclazz.bootstrap.TrackedInstances.live("occ")) {
            try {
                Map<Object, Object> levels = (Map<Object, Object>) helper.getClass().getMethod("getLevelMap").invoke(helper);
                if (levels == null) continue;
                Map<Object, Object> configured = (Map<Object, Object>) com.onurkat.reclazz.bootstrap.TrackedInstances.baselineOf(helper);
                synchronized (levels) {
                    for (Object key : new java.util.ArrayList<>(levels.keySet())) {
                        if (!(key instanceof Class<?> dto) || !owner.isAssignableFrom(dto)) continue;
                        if (configured == null) {
                            // No recorded configuration (a subclass that configures itself): drop only the computed levels.
                            if (levels.get(dto) instanceof Map<?, ?> computed) for (String level : new String[]{"BASIC", "DEFAULT", "FULL"}) computed.remove(level);
                            continue;
                        }
                        Object xml = configured.get(dto);
                        if (xml instanceof Map<?, ?> fromXml) levels.put(dto, new java.util.HashMap<>(fromXml));
                        else levels.remove(dto);
                    }
                }
            } catch (Throwable failure) {
                String reason = "SAP Commerce OCC field set levels could not be refreshed ("
                        + failure.getClass().getSimpleName() + "); a restart shows added DTO fields to fields=FULL";
                com.onurkat.reclazz.ui.StatusReporter.warn(reason);
                com.onurkat.reclazz.ui.RestartLedger.note("SAP Commerce OCC field sets", reason);
            }
        }
    }
}
