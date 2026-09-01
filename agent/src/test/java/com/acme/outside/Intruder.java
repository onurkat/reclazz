/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.acme.outside;

import com.onurkat.reclazz.bootstrap.LookupCapture;

import java.lang.invoke.MethodHandles;

/**
 * Application code, as far as the JVM is concerned: a class outside the engine
 * asking for a capability the engine holds. Stands in for the expression
 * evaluator or the gadget chain that can call a static method.
 */
public final class Intruder {

    public static MethodHandles.Lookup steal(Class<?> target) {
        return LookupCapture.get(target);
    }

    public static void poison(Class<?> target, MethodHandles.Lookup mine) {
        LookupCapture.store(target, mine);
    }
}
