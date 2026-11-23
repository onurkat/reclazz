/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * R35: a Gson instance caches one reflective type adapter per type, holding the fields it found then. After a reload
 * changes a class's fields, every live Gson drops its cached adapters, so the next use discovers the current fields.
 */
final class GsonCaches {
    private GsonCaches() { }

    static int flush() {
        int flushed = 0;
        for (Object gson : com.onurkat.reclazz.bootstrap.TrackedInstances.live("gson")) {
            try {
                Field cache = gson.getClass().getDeclaredField("typeTokenCache");
                cache.setAccessible(true);
                Object value = cache.get(gson);
                if (value instanceof Map<?, ?> map) { map.clear(); flushed++; }
            } catch (Throwable unreadable) {
                com.onurkat.reclazz.ui.StatusReporter.warn("A Gson instance's type adapter cache could not be cleared ("
                        + unreadable.getClass().getSimpleName() + "); a new Gson sees the changed fields");
            }
        }
        return flushed;
    }
}
