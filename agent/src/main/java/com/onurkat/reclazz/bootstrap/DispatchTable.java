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
            return new ClassDispatch(type);
        }
    };

    public static ClassDispatch getOrCreate(Class<?> ownerClass) {
        return dispatches.get(ownerClass);
    }

    /**
     * Re-target all call sites for a class to new method handles from a companion class.
     * Thread-safe: uses per-class lock.
     *
     * Always creates the dispatch entry if it doesn't exist yet, the latest
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
        private final Class<?> owner;
        private ClassDispatch(Class<?> owner){this.owner=owner;}
        private final ReentrantLock lock = new ReentrantLock();
        private static final class RecordBinding {
            final RecordSchema.Slot slot;
            final RecordSchema.CallerSite site;
            boolean needsBaseline;
            RecordBinding(RecordSchema.Slot slot,RecordSchema.CallerSite site,boolean needsBaseline){
                this.slot=slot;this.site=site;this.needsBaseline=needsBaseline;
            }
        }
        // Both metadata and handles are owned by the caller class, never by the record Slot.
        private final Map<String,RecordBinding> recordBindings=new java.util.HashMap<>();
        private RecordBinding versionBinding;
        private RecordSchema.State versionAttempt;
        private volatile MethodHandle recordVersion;
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
        // effect, matching the reported bug).
        private record PublishedMethod(MethodHandle target, boolean removed) { }
        private final ConcurrentHashMap<String, PublishedMethod> latestMethodTargets = new ConcurrentHashMap<>();

        private MethodHandle latestTarget(String key) {
            PublishedMethod published = latestMethodTargets.get(key);
            return published == null ? null : published.target();
        }

        // A cached declaration can outlive its override until reflection metadata catches up.
        // Read the removal flag and the callable body from the same publication.
        MethodHandle virtualOverrideTarget(String key, MethodHandle initial) {
            PublishedMethod published = latestMethodTargets.get(key);
            if (published == null) return initial;
            return published.removed() ? null : published.target();
        }

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
            // Under the same lock as retarget, and the latest target applied
            // before the site is handed out. Both orders were racy without
            // it: a second thread bootstrapping the same key could take the
            // site out of the map between its publication and setTarget and
            // run its throwing initial target once (measured: one failed call
            // in a caller-and-callee save, callee reloaded first); and a
            // retarget landing between this thread's read of the latest
            // target and its publication missed the site for good. Once per
            // call site, so the lock costs nothing that recurs.
            lock.lock();
            try {
                MutableCallSite site = methodSites.computeIfAbsent(key, k -> initial);
                // If a reload already installed a newer target for this key,
                // retarget the freshly created site before anyone can reach it,
                // so the first invocation goes to the latest companion method,
                // not the v0 renamed one.
                if (site == initial) {
                    RecordBinding binding=recordBindings.get(key);
                    if(binding!=null&&binding.needsBaseline) {
                        binding.site.installBaseline(initial.getTarget());binding.needsBaseline=false;
                    }
                    MethodHandle latest = latestTarget(key);
                    if (latest != null) {
                        try {
                            // A first external call can arrive after the reload.
                            // It needs the same proxy/override guard as a warm site.
                            site.setTarget(respectingOverrides(key, latest));
                        } catch (Exception ignored) {
                            // type mismatch, leave the v0 handle in place
                        }
                    }
                }
                return site;
            } finally {
                lock.unlock();
            }
        }

        public MutableCallSite getOrCreateFieldGetSite(String key, MutableCallSite initial) {
            return fieldGetSites.computeIfAbsent(key, k -> initial);
        }

        public MutableCallSite getOrCreateFieldSetSite(String key, MutableCallSite initial) {
            return fieldSetSites.computeIfAbsent(key, k -> initial);
        }

        public int getVersion() {
            MethodHandle selected=recordVersion;if(selected==null)return version;
            try{return (int)selected.invokeExact();}
            catch(Throwable failure){throw new IllegalStateException("Cannot select record caller version",failure);}
        }
        /** Called only through the preparation's trusted, synchronized stageDispatch entry. */
        void stageRecord(RecordSchema.Preparation preparation,RecordSchema.Slot slot,Map<String,MethodHandle> targets) {
            lock.lock();
            try {
                // Validate the complete map before exposing even an old-behaviour dispatcher.
                for(var entry:targets.entrySet()) {
                    if(owner.isRecord())RecordSchema.validateTargetOwner(owner,entry.getValue());
                    RecordBinding binding=recordBindings.get(entry.getKey());
                    if(binding!=null&&binding.slot!=slot&&binding.site.hasPending())
                        throw new IllegalStateException("Caller already belongs to another pending record cohort");
                    MethodHandle old=latestTarget(entry.getKey());
                    MutableCallSite site=methodSites.get(entry.getKey());
                    if(old==null&&site!=null)old=site.getTarget();
                    if(old!=null&&!old.type().equals(entry.getValue().type()))
                        throw new IllegalArgumentException("Record caller target type changed: "+entry.getKey());
                }
                if(versionBinding!=null&&versionBinding.slot!=slot&&versionBinding.site.hasPending())
                    throw new IllegalStateException("Caller version belongs to another pending record cohort");
                var changed=new java.util.ArrayList<MutableCallSite>();
                for(var entry:targets.entrySet()) {
                    String key=entry.getKey();RecordBinding binding=recordBindings.get(key);
                    if(binding==null||binding.slot!=slot) {
                        MethodHandle before;boolean unresolved=false;
                        if(binding!=null) {
                            var selected=binding.site.selected();before=selected.target();
                            unresolved=binding.needsBaseline&&selected.baseline();
                        } else {
                            before=latestTarget(key);
                            MutableCallSite existing=methodSites.get(key);
                            if(before==null&&existing!=null)before=existing.getTarget();
                            if(before==null){before=missing(entry.getValue().type(),key);unresolved=true;}
                        }
                        var selected=RecordSchema.callerSiteInternal(owner,slot,"dispatch:"+key,before);
                        selected.rebase(before);
                        binding=new RecordBinding(slot,selected,unresolved);recordBindings.put(key,binding);
                    }
                    preparation.stageTarget(binding.site,entry.getValue());
                    MethodHandle dispatcher=binding.site.dispatcherInternal();
                    latestMethodTargets.put(key,new PublishedMethod(dispatcher, false));
                    MutableCallSite site=methodSites.get(key);
                    if(site!=null){site.setTarget(respectingOverrides(key,dispatcher));changed.add(site);}
                }
                if(versionAttempt!=preparation.attempt()) {
                    int before=getVersion();
                    if(versionBinding==null||versionBinding.slot!=slot) {
                        var site=RecordSchema.callerSiteInternal(owner,slot,InjectedNames.DISPATCH_VERSION_KEY,MethodHandles.constant(int.class,before));
                        site.rebase(MethodHandles.constant(int.class,before));versionBinding=new RecordBinding(slot,site,false);
                    }
                    preparation.stageTarget(versionBinding.site,MethodHandles.constant(int.class,before+1));
                    recordVersion=versionBinding.site.dispatcherInternal();versionAttempt=preparation.attempt();
                }
                if(!changed.isEmpty())MutableCallSite.syncAll(changed.toArray(MutableCallSite[]::new));
            } finally {lock.unlock();}
        }
        private static MethodHandle missing(java.lang.invoke.MethodType type,String key) {
            var target=MethodHandles.throwException(type.returnType(),NoSuchMethodError.class)
                    .bindTo(new NoSuchMethodError("Record caller baseline not bootstrapped: "+key));
            return MethodHandles.dropArguments(target,0,type.parameterList());
        }

        public void retarget(Map<String, MethodHandle> newTargets) {
            retarget(newTargets, Map.of());
        }

        public void retarget(Map<String, MethodHandle> newTargets, Map<String, MethodHandle> removalTargets) {
            // A preparation takes Slot before dispatch. Cancel outside our lock to keep that order.
            RecordSchema.cancelPendingForOwner(owner);
            lock.lock();
            try {
                int count = 0;
                MutableCallSite[] sites = new MutableCallSite[newTargets.size()];

                for (var entry : newTargets.entrySet()) {
                    // Always remember the latest target so a future first-touch
                    // bootstrap can install it instead of the v0 renamed handle.
                    MethodHandle target = respectingOverrides(entry.getKey(), entry.getValue());
                    boolean removed = removalTargets.get(entry.getKey()) == entry.getValue();
                    latestMethodTargets.put(entry.getKey(), new PublishedMethod(target, removed));
                    recordBindings.remove(entry.getKey());
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

                version=getVersion()+1;recordVersion=null;versionBinding=null;versionAttempt=null;
            } finally {
                lock.unlock();
            }
        }

    }
}
