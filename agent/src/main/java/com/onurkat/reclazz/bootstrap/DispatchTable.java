/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MutableCallSite;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Central dispatch table for invokedynamic call sites.
 *
 * Held per Class rather than per class name, so two Class instances with the
 * same internal name, loaded by separate classloaders or by test fixtures
 * sharing a name, get distinct dispatch entries.
 *
 * A ClassValue rather than a map, because the entry has to die with the class
 * it belongs to. This was a WeakHashMap on the theory that a weak key would let
 * an unloaded class go, and it would not have: the value holds the key. An
 * override guard stores the owning Class outright, and every call site target
 * is a MethodHandle bound to a method of that class, so each entry pinned its
 * own key and, through it, the classloader that defined it. Nothing showed up
 * in a reload loop, where the class stays loaded anyway; the cost lands on a
 * classloader that gets discarded, a redeployed web application being the usual
 * one, whose whole loader would then be held by an agent that is supposed to be
 * helping. A ClassValue lives on the Class itself and cannot outlive it.
 *
 * BOOTSTRAP CLASS: Must have ZERO dependencies outside java.* packages.
 */
public final class DispatchTable {

    private static final ClassValue<ClassDispatch> dispatches = new ClassValue<>() {
        @Override
        protected ClassDispatch computeValue(Class<?> type) {
            return new ClassDispatch();
        }
    };

    public static ClassDispatch getOrCreate(Class<?> ownerClass) {
        return dispatches.get(ownerClass);
    }

    /**
     * Whether a reload has ever installed a companion target for this site
     * key. Read-only, for reporting: what a removed method's existing
     * callers meet depends on it. A site with a companion target keeps
     * dispatching there, so the throwing stub the redefinition put under the
     * renamed fallback is never reached; a site without one falls back to
     * exactly that renamed method. Both were measured, the first on the SAP
     * Commerce integration run (method reloaded, then removed: callers kept
     * the reloaded body), the second on Spring Boot (method removed without
     * ever being reloaded: callers threw).
     */
    public static boolean hasCompanionTarget(Class<?> ownerClass, String siteKey) {
        return getOrCreate(ownerClass).latestMethodTargets.containsKey(siteKey);
    }

    /**
     * Re-target all call sites for a class to new method handles from a companion class.
     * Thread-safe: uses per-class lock.
     *
     * Always creates the dispatch entry if it doesn't exist yet — the latest
     * target map needs to be populated so that lazy bootstrap of call sites
     * (which happens on first invocation, possibly AFTER a reload) picks up
     * the latest companion handle instead of the v0 renamed method.
     */
    public static void retargetAll(Class<?> ownerClass,
                                    MethodHandles.Lookup companionLookup,
                                    Map<String, MethodHandle> newTargets) {
        ClassDispatch dispatch = getOrCreate(ownerClass);
        dispatch.retarget(newTargets);
    }

    public static final class ClassDispatch {
        private final ReentrantLock lock = new ReentrantLock();
        private volatile int version = 0;
        private final ConcurrentHashMap<String, MutableCallSite> methodSites = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, MutableCallSite> fieldGetSites = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, MutableCallSite> fieldSetSites = new ConcurrentHashMap<>();
        // Latest companion targets installed by {@link #retarget}. Stored even
        // when no MutableCallSite has been bootstrapped yet so that the lazy
        // first-invocation bootstrap of a call site picks up the latest
        // companion handle instead of the v0 renamed method. Without this,
        // the first reload that happens before any user request to the class
        // is silently lost (the user sees v0 behavior on the first request,
        // and only the SECOND reload after that request actually takes
        // effect — matching the reported bug).
        private final ConcurrentHashMap<String, MethodHandle> latestMethodTargets = new ConcurrentHashMap<>();

        /**
         * What a call site needs to keep respecting an override after a
         * reload. A reload re-points sites straight at the new implementation,
         * and a receiver that is a Spring proxy would be walked past: the bean
         * would keep its transaction, cache or aspect only until its first
         * reload. See ReclazzBootstrap.guardWithOverride.
         */
        private final ConcurrentHashMap<String, OverrideGuard> overrideGuards = new ConcurrentHashMap<>();

        private record OverrideGuard(Class<?> ownerClass, String publicName, MethodHandle publicCall) { }

        /** Registered by the bootstrap for call sites outside the owning class. */
        public void registerOverrideGuard(String key, Class<?> ownerClass,
                                          String publicName, MethodHandle publicCall) {
            overrideGuards.put(key, new OverrideGuard(ownerClass, publicName, publicCall));
        }

        private MethodHandle respectingOverrides(String key, MethodHandle target) {
            OverrideGuard guard = overrideGuards.get(key);
            if (guard == null) return target;
            try {
                return ReclazzBootstrap.guardWithOverride(
                        guard.ownerClass(), guard.publicName(), guard.publicCall(), target);
            } catch (Throwable t) {
                // A guard that cannot be rebuilt must not cost the reload; the
                // call still reaches the new code, just not through a proxy.
                return target;
            }
        }

        public MutableCallSite getOrCreateMethodSite(String key, MutableCallSite initial) {
            MutableCallSite site = methodSites.computeIfAbsent(key, k -> initial);
            // If a reload already installed a newer target for this key, retarget
            // the freshly created site immediately so the first invocation goes
            // to the latest companion method, not the v0 renamed one.
            if (site == initial) {
                MethodHandle latest = latestMethodTargets.get(key);
                if (latest != null) {
                    try {
                        site.setTarget(latest);
                    } catch (Exception ignored) {
                        // type mismatch — leave the v0 handle in place
                    }
                }
            }
            return site;
        }

        public MutableCallSite getOrCreateFieldGetSite(String key, MutableCallSite initial) {
            return fieldGetSites.computeIfAbsent(key, k -> initial);
        }

        public MutableCallSite getOrCreateFieldSetSite(String key, MutableCallSite initial) {
            return fieldSetSites.computeIfAbsent(key, k -> initial);
        }

        public int getVersion() { return version; }

        public void retarget(Map<String, MethodHandle> newTargets) {
            lock.lock();
            try {
                int count = 0;
                MutableCallSite[] sites = new MutableCallSite[newTargets.size()];

                for (var entry : newTargets.entrySet()) {
                    // Always remember the latest target so a future first-touch
                    // bootstrap can install it instead of the v0 renamed handle.
                    MethodHandle target = respectingOverrides(entry.getKey(), entry.getValue());
                    latestMethodTargets.put(entry.getKey(), target);
                    MutableCallSite site = methodSites.get(entry.getKey());
                    if (site != null) {
                        site.setTarget(target);
                        sites[count++] = site;
                    }
                }

                // Atomic JIT invalidation for all re-targeted sites
                if (count > 0) {
                    MutableCallSite[] trimmed = new MutableCallSite[count];
                    System.arraycopy(sites, 0, trimmed, 0, count);
                    MutableCallSite.syncAll(trimmed);
                }

                version++;
            } finally {
                lock.unlock();
            }
        }

    }
}
