/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Live mappers, including standalone/copy instances, without retaining their loaders. */
public final class JacksonMapperRegistry {
    private static final List<WeakReference<Object>> mappers = new ArrayList<>();
    private JacksonMapperRegistry() { }

    public static synchronized void track(Object mapper) {
        if (mapper == null) return;
        mappers.removeIf(ref -> ref.get() == null);
        for (var ref : mappers) if (ref.get() == mapper) return;
        mappers.add(new WeakReference<>(mapper));
    }

    public static synchronized List<Object> live() {
        var result = new ArrayList<Object>();
        mappers.removeIf(ref -> ref.get() == null);
        for (var ref : mappers) {
            Object mapper = ref.get();
            if (mapper != null) result.add(mapper);
        }
        return result;
    }
}
