/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The {@code __reclazz$lookup} value of each transformed class, captured while
 * reflection could still see the field.
 *
 * <p>Root-level reflection filtering (see ReflectionRootFilter in the agent)
 * hides {@code __reclazz$} members from {@code getDeclaredField} for everyone,
 * and "everyone" includes Reclazz: the structural reloader reads the lookup on
 * every reload, and {@link ProtectedCallResolver} reads it when a companion
 * makes a cross-package call. Both used to reach it reflectively, which stops
 * working the moment the filter is registered. The value is captured here
 * first, so hiding the field from frameworks does not hide it from the engine
 * that put it there.
 *
 * <p>A ClassValue rather than a map for the same reason as {@link DispatchTable}:
 * the Lookup holds its class, so a map entry would pin the class and its
 * classloader for the life of the JVM, while a ClassValue entry lives on the
 * Class and dies with it.
 *
 * BOOTSTRAP CLASS: Must have ZERO dependencies outside java.* packages.
 */
public final class LookupCapture {

    private static final ClassValue<AtomicReference<MethodHandles.Lookup>> captured =
            new ClassValue<>() {
                @Override
                protected AtomicReference<MethodHandles.Lookup> computeValue(Class<?> type) {
                    return new AtomicReference<>();
                }
            };

    private LookupCapture() {}

    /** Remember a class's own full-privilege lookup. Last write wins; the
     *  value never changes for a given class, so that is a no-op in practice. */
    public static void store(Class<?> clazz, MethodHandles.Lookup lookup) {
        if (lookup != null) {
            captured.get(clazz).set(lookup);
        }
    }

    /** The captured lookup, or null when nothing captured one for this class. */
    public static MethodHandles.Lookup get(Class<?> clazz) {
        return captured.get(clazz).get();
    }
}
