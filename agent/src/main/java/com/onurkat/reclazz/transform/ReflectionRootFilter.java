/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.bootstrap.FieldStore;
import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Hides Reclazz's injected members at the root of reflection, inside the JDK,
 * rather than at rewritten call sites.
 *
 * <p>{@link ReflectionInterceptTransformer} rewrites
 * {@code Class.getDeclaredMethods()} call sites to {@code ReflectionBridge},
 * which strips {@code __reclazz$} members. A call-site rewrite can never be
 * complete: meta-reflection,
 * {@code Class.class.getMethod("getDeclaredFields").invoke(target)}, invokes
 * the real method through a {@code Method} object and no call site exists to
 * rewrite. One such leak was observed in the field, {@code __reclazz$} fields
 * surfacing in a framework scan, and never reproduced; this closes the whole
 * class of them instead of chasing the instance.
 *
 * <p>The JDK keeps its own filter for exactly this purpose,
 * {@code jdk.internal.reflect.Reflection.registerFieldsToFilter} and
 * {@code registerMethodsToFilter}, which is how {@code Class.getDeclaredFields()}
 * on {@code MethodHandles.Lookup} hides {@code IMPL_LOOKUP}. A registered name
 * disappears from every route: direct scans, meta-reflection, and lookups by
 * name ({@code getDeclaredField} answers {@code NoSuchFieldException}).
 * Measured on SapMachine 21 and JBR 25:
 * <ul>
 *   <li>{@code MethodHandles.Lookup} resolution ignores the filter, so the
 *       invokedynamic dispatch that runs every trampolined method is
 *       untouched.</li>
 *   <li>{@code Method}/{@code Field} objects obtained before registration keep
 *       working, so a framework that already scanned loses nothing.</li>
 *   <li>A second registration for the same class throws
 *       {@code IllegalArgumentException("Filter already registered")}, so each
 *       class is registered exactly once, with every name known for it. That
 *       is safe because the JVM never changes a loaded class's member set: the
 *       injected members a class will ever have are all present at load
 *       time.</li>
 *   <li>The filter only applies when {@code Class}'s reflection cache is built
 *       after registration; a pre-registration scan stays cached in
 *       {@code Class#reflectionData}. That field is a SoftReference the JDK is
 *       prepared to lose at any GC, so clearing it after registration is
 *       equivalent to a collected cache and makes the filter effective
 *       immediately. A class redefinition invalidates the cache as well, so
 *       every reloaded class is clean from its first reload even without the
 *       flush.</li>
 * </ul>
 *
 * <p>Registration happens after class load (there is no Class object before),
 * so a scan that ran before the agent registered a class may hold unfiltered
 * {@code Method}/{@code Field} objects; {@code ReflectionBridge} stays in
 * place as cover for those.
 *
 * <p>Before a class's fields are hidden, everything Reclazz itself reads
 * through reflection is captured: the {@code __reclazz$lookup} value into
 * {@link LookupCapture} and the {@code __reclazz$ext} {@code Field} into
 * {@link FieldStore}'s cache. Registering without those captures would cut the
 * engine off from its own infrastructure.
 */
public final class ReflectionRootFilter {

    private static final String INTERNAL_PREFIX = "__reclazz$";
    private static final String RENAMED_PREFIX = "__reclazz$v0$";
    private static final String EXT_FIELD = "__reclazz$ext";
    private static final String LOOKUP_FIELD = "__reclazz$lookup";

    /** The instance the agent installed, or null before {@link #install}. */
    private static volatile ReflectionRootFilter installed;

    /**
     * What has been registered per class, shared across instances because the
     * JDK's filter map is a single JVM-wide state: two instances that each
     * registered the same class would hit the JDK's "already registered"
     * refusal. A ClassValue so the record dies with the class.
     */
    private static final ClassValue<Registration> registrations = new ClassValue<>() {
        @Override
        protected Registration computeValue(Class<?> type) {
            return new Registration();
        }
    };

    private static final class Registration {
        boolean registered;
        final Set<String> fieldNames = new LinkedHashSet<>();
        final Set<String> methodNames = new LinkedHashSet<>();
    }

    private final boolean available;
    private final Method registerFields;   // Reflection.registerFieldsToFilter
    private final Method registerMethods;  // Reflection.registerMethodsToFilter
    private final Field reflectionData;    // java.lang.Class#reflectionData

    /**
     * Probes the capability once. Any Throwable anywhere in the probe means
     * "unsupported on this JVM": the agent then says so in one line and keeps
     * today's behaviour, the call-site bridge.
     */
    public ReflectionRootFilter(Instrumentation instrumentation) {
        boolean ok = false;
        Method rf = null, rm = null;
        Field rd = null;
        try {
            if (instrumentation == null) {
                throw new IllegalStateException("no Instrumentation to open modules with");
            }
            Module base = Object.class.getModule();
            Module our = ReflectionRootFilter.class.getModule();
            // Two opens in one call: jdk.internal.reflect for the filter
            // registry, java.lang for the reflection cache flush.
            instrumentation.redefineModule(base, Set.of(), Map.of(),
                    Map.of("jdk.internal.reflect", Set.of(our),
                           "java.lang", Set.of(our)),
                    Set.of(), Map.of());

            Class<?> reflection = Class.forName("jdk.internal.reflect.Reflection");
            rf = reflection.getDeclaredMethod("registerFieldsToFilter", Class.class, Set.class);
            rm = reflection.getDeclaredMethod("registerMethodsToFilter", Class.class, Set.class);
            rf.setAccessible(true);
            rm.setAccessible(true);
            rd = Class.class.getDeclaredField("reflectionData");
            rd.setAccessible(true);
            ok = true;
        } catch (Throwable unsupported) {
            StatusReporter.info("Root-level reflection filtering unavailable on this JVM ("
                    + unsupported + "); injected members stay hidden through the call-site bridge only.");
        }
        this.available = ok;
        this.registerFields = rf;
        this.registerMethods = rm;
        this.reflectionData = rd;
    }

    /** Probe once and keep the result for the static entry points. */
    public static void install(Instrumentation instrumentation) {
        installed = new ReflectionRootFilter(instrumentation);
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Static convenience for the reload path: a no-op until {@link #install}
     * ran, and a no-op on a JVM where the probe failed.
     */
    public static void registerInjectedMembersOn(Class<?> clazz) {
        ReflectionRootFilter filter = installed;
        if (filter != null) {
            filter.registerInjectedMembers(clazz);
        }
    }

    /**
     * Hide every {@code __reclazz$} member of a Reclazz-transformed class.
     *
     * <p>A class without injected members is left alone: registering a filter
     * on a class Reclazz did not transform would be claiming territory the
     * agent has no business in.
     *
     * <p>The declared members are enumerated BEFORE registration, both because
     * the {@code __reclazz$v0$} names have to be read off the class while
     * reflection still shows them, and because the lookup and ext-field
     * captures need the same window.
     */
    public void registerInjectedMembers(Class<?> clazz) {
        if (!available) return;
        Registration record = registrations.get(clazz);
        synchronized (record) {
            if (record.registered) return;

            Set<String> methodNames = new LinkedHashSet<>();
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().startsWith(RENAMED_PREFIX)) {
                    methodNames.add(m.getName());
                }
            }
            Set<String> fieldNames = new LinkedHashSet<>();
            for (Field f : clazz.getDeclaredFields()) {
                if (f.getName().startsWith(INTERNAL_PREFIX)) {
                    fieldNames.add(f.getName());
                }
            }
            if (!fieldNames.contains(LOOKUP_FIELD)) {
                // No lookup field means Reclazz never transformed this class,
                // whatever else it declares. Nothing here is ours to hide.
                return;
            }

            // Capture everything the engine itself reads reflectively, while
            // it still can. Reading the static field initialises the class if
            // needed, so a null lookup means the initialiser is mid-run on
            // another thread or the injected initialiser failed; either way
            // the capture would be incomplete and registration is once-only,
            // so leave it for the next opportunity.
            if (!captureLookup(clazz)) return;
            if (fieldNames.contains(EXT_FIELD)) {
                FieldStore.captureExtField(clazz);
            }

            registerFor(clazz, record, fieldNames, methodNames);
        }
    }

    /**
     * Register a filter for exactly these names, once per class.
     *
     * <p>Idempotent: a repeat call for a class already registered keeps the
     * earlier names and does nothing, because the JDK refuses a second
     * registration outright (measured, {@code IllegalArgumentException}).
     * Callers therefore pass the full set they know in one call; the loaded
     * class's member set cannot change afterwards, so nothing is ever missing
     * from it.
     */
    public void registerFor(Class<?> clazz, Set<String> fieldNames, Set<String> methodNames) {
        if (!available) return;
        Registration record = registrations.get(clazz);
        synchronized (record) {
            registerFor(clazz, record, fieldNames, methodNames);
        }
    }

    private void registerFor(Class<?> clazz, Registration record,
                             Set<String> fieldNames, Set<String> methodNames) {
        if (record.registered) {
            if (!record.fieldNames.containsAll(fieldNames)
                    || !record.methodNames.containsAll(methodNames)) {
                // Cannot happen for injected members (the set is fixed at
                // load time), but if a caller ever asks, the truth is better
                // than silence: the JDK accepts one registration per class.
                StatusReporter.warn("Reflection filter for " + clazz.getName()
                        + " is already registered and cannot be extended; new names ignored.");
            }
            return;
        }
        try {
            if (!fieldNames.isEmpty()) {
                registerFields.invoke(null, clazz, Set.copyOf(fieldNames));
            }
            if (!methodNames.isEmpty()) {
                registerMethods.invoke(null, clazz, Set.copyOf(methodNames));
            }
            record.fieldNames.addAll(fieldNames);
            record.methodNames.addAll(methodNames);
            record.registered = true;

            // The enumeration above (and anything else that reflected on this
            // class earlier) left an unfiltered scan in Class#reflectionData.
            // The field is a SoftReference the JDK is prepared to lose at any
            // GC, so clearing it is an ordinary event for the JDK and makes
            // the filter effective from the next scan on.
            reflectionData.set(clazz, null);
        } catch (Throwable t) {
            // A failed registration leaves exactly today's behaviour: the
            // bridge keeps hiding at rewritten call sites.
            StatusReporter.warn("Could not register reflection filter for "
                    + clazz.getName() + ": " + t);
        }
    }

    /**
     * Capture the class's own lookup into the bootstrap-classloader cache.
     *
     * @return false when there is nothing to capture yet
     */
    private boolean captureLookup(Class<?> clazz) {
        try {
            Field lookupField = clazz.getDeclaredField(LOOKUP_FIELD);
            lookupField.setAccessible(true);
            MethodHandles.Lookup lookup = (MethodHandles.Lookup) lookupField.get(null);
            if (lookup == null) return false;
            LookupCapture.store(clazz, lookup);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
