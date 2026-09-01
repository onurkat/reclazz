/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.MethodHandles;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>What it holds is a capability, which is why who may ask for it is
 * checked. A class's own {@code MethodHandles.lookup()} carries private access
 * to that class, and on a classpath application {@code privateLookupIn} turns
 * one of those into private access to every other class on the classpath. This
 * class is appended to the bootstrap classloader, so before the check every
 * line of code in the JVM could call {@code get} and be handed that: an
 * expression language evaluating a submitted string, a deserialization gadget
 * that can call a static method, anything. None of them can write to a watched
 * directory, which is the boundary the README draws, so handing them the same
 * reach through a public method was drawing it somewhere else.
 *
 * <p>The engine registers the classes allowed to ask, by identity, before the
 * application starts, and the list is then sealed. Identity rather than package
 * name because the agent jar sits on the system classpath next to the
 * application: a name check would be passed by any class the application itself
 * declares in {@code com.onurkat.reclazz}.
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

    private static final StackWalker WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /** The engine's own classes, by identity, that may read a captured lookup. */
    private static final Set<Class<?>> trusted = ConcurrentHashMap.newKeySet();

    private static volatile boolean sealed = false;

    private LookupCapture() {}

    /**
     * Register a class of the engine's as allowed to read captured lookups.
     * Called during agent start-up, before any application class runs.
     */
    public static void trust(Class<?> engineClass) {
        if (sealed) {
            throw new SecurityException("Reclazz: the trusted-caller list is sealed");
        }
        if (engineClass != null) trusted.add(engineClass);
    }

    /** Close the list. Nothing may be added afterwards, by anyone. */
    public static void seal() {
        sealed = true;
    }

    /**
     * Reopen the list, for tests that need to seal it and carry on.
     *
     * <p>Package-private, and that is the whole protection: a runtime package
     * is its name and its classloader together, so a class the application
     * declares in this package is in a different runtime package and cannot
     * reach this. Only code loaded beside this class on the bootstrap loader
     * can, and getting code there needs instrumentation already.
     */
    static void unsealForTests() {
        sealed = false;
    }

    private static void requireTrusted(Class<?> caller, String action) {
        // Before sealing, the only code running is the agent's own start-up:
        // the engine registers its classes and closes the list within premain,
        // which finishes before the application's main method begins. So the
        // open window is one in which no application code exists to walk
        // through it, and leaving it open is what lets the engine be driven
        // directly by its own tests without handing tests a key that works in
        // production.
        if (!sealed) return;
        if (caller != null && trusted.contains(caller)) return;
        throw new SecurityException("Reclazz: " + action + " is engine-internal. A captured "
                + "lookup carries private access to the class it belongs to, so it is not "
                + "handed to callers outside the reload engine"
                + (caller == null ? "" : "; asked for by " + caller.getName()));
    }

    /** Remember a class's own full-privilege lookup. Last write wins; the
     *  value never changes for a given class, so that is a no-op in practice. */
    public static void store(Class<?> clazz, MethodHandles.Lookup lookup) {
        requireTrusted(WALKER.getCallerClass(), "storing a lookup");
        if (lookup != null) {
            captured.get(clazz).set(lookup);
        }
    }

    /** The captured lookup, or null when nothing captured one for this class. */
    public static MethodHandles.Lookup get(Class<?> clazz) {
        requireTrusted(WALKER.getCallerClass(), "reading a captured lookup");
        return captured.get(clazz).get();
    }
}
