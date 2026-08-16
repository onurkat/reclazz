/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The template engines this JVM has built.
 *
 * A template engine caches parsed templates, which is the whole reason it is
 * fast and the whole reason editing a template does nothing until a restart.
 * Clearing that cache is a one-line call, but only if you can reach the
 * engine, and nothing hands it to an agent: it is built by application code,
 * or by Spring, and then kept privately.
 *
 * So the engines register themselves, from a constructor rewritten at load
 * time, and this holds them weakly. Weakly because an application may build
 * several and discard most of them, and a hot-reload tool has no business
 * keeping any of them alive.
 *
 * BOOTSTRAP CLASS: no dependencies outside java.*.
 */
public final class TemplateEngineRegistry {

    private static final Set<Object> engines =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    private TemplateEngineRegistry() {
    }

    /** Called from rewritten constructors. Never throws into the caller. */
    public static void register(Object engine) {
        if (engine == null) return;
        try {
            engines.add(engine);
        } catch (Throwable ignored) {
            // An agent must not break the application it is watching.
        }
    }

    /** A snapshot; the live set is weak and may change underneath a caller. */
    public static Object[] snapshot() {
        synchronized (engines) {
            return engines.toArray();
        }
    }

    public static int size() {
        synchronized (engines) {
            return engines.size();
        }
    }

    /** Test seam: the registry outlives individual tests inside one JVM. */
    public static void clearForTests() {
        synchronized (engines) {
            engines.clear();
        }
    }
}
