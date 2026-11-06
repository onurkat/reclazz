/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.InjectedNames;
import com.onurkat.reclazz.transform.CallSiteAdapter;
import com.onurkat.reclazz.transform.TransformContext;
import com.onurkat.reclazz.transform.TransformExclusions;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What a call to a removed method reaches once the save that removed it is
 * live: what the same call would reach after a restart.
 *
 * <p>The JVM will not let a method leave a loaded class, so every caller that
 * still names it, a framework holding its {@code Method}, a class nobody
 * recompiled, kept running the previous body until restart. Measured against
 * a live SAP Commerce server: the endpoint went on answering with the removed
 * method's last result, which looks like the save did nothing. After a
 * restart that caller does not get an answer at all; it gets
 * {@code NoSuchMethodError}, or the inherited implementation when the removed
 * method was an override. Every call to the method goes through its
 * trampoline's call site, the public method included, so retargeting that
 * site is enough to give the restart answer straight away.
 *
 * <p>Methods the trampoline never covered keep their body: lambda bodies and
 * other compiler-generated methods (an old lambda object still points at its
 * {@code lambda$} body, and a bridge removed by a signature change still
 * routes calls that a live object relies on).
 */
final class RemovedMethodTargets {

    private RemovedMethodTargets() {
    }

    /** One removed method and what its callers reach now. */
    record Removal(String name, String inheritedFrom) {
    }

    /**
     * The call site targets to install, and what each removed method became.
     * {@code kept} names the ones whose previous body still answers because
     * the restart answer could not be built.
     */
    record Plan(Map<String, MethodHandle> targets, List<Removal> throwing,
                List<Removal> inherited, List<String> kept) {
        boolean isEmpty() {
            return throwing.isEmpty() && inherited.isEmpty() && kept.isEmpty();
        }
    }

    private static final MethodHandle NEW_ERROR;

    static {
        try {
            NEW_ERROR = MethodHandles.publicLookup().findConstructor(NoSuchMethodError.class,
                    MethodType.methodType(void.class, String.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static Plan plan(Class<?> owner, MethodHandles.Lookup ownerLookup,
                     List<TransformContext.MethodSig> removed) {
        Map<String, MethodHandle> targets = new LinkedHashMap<>();
        List<Removal> throwing = new ArrayList<>();
        List<Removal> inherited = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        String internalName = owner.getName().replace('.', '/');
        for (var m : removed) {
            if ("<init>".equals(m.name()) || "<clinit>".equals(m.name())) continue;
            if ((m.access() & (Modifier.ABSTRACT | Modifier.NATIVE)) != 0) continue;
            // ACC_SYNTHETIC 0x1000 and ACC_BRIDGE 0x0040: compiler-generated,
            // renumbered on ordinary edits and never named by the developer.
            if ((m.access() & 0x1040) != 0
                    || TransformExclusions.shouldSkipMethod(internalName, m.name(), m.descriptor(), m.access())) {
                continue;
            }
            boolean isStatic = (m.access() & Modifier.STATIC) != 0;
            String siteKey = InjectedNames.siteKey(m.name(), CallSiteAdapter.descHash(m.descriptor()), isStatic);
            try {
                MethodType type = MethodType.fromMethodDescriptorString(m.descriptor(), owner.getClassLoader());
                MethodType siteType = isStatic ? type : type.insertParameterTypes(0, owner);
                MethodHandle target = null;
                Method parent = (m.access() & Modifier.PRIVATE) != 0 ? null
                        : inheritedImplementation(owner, m.name(), type, isStatic);
                if (parent != null) {
                    try {
                        target = isStatic
                                ? ownerLookup.findStatic(parent.getDeclaringClass(), m.name(), type)
                                : ownerLookup.findSpecial(parent.getDeclaringClass(), m.name(), type, owner);
                        target = target.asType(siteType);
                    } catch (ReflectiveOperationException | IllegalArgumentException unreachable) {
                        // The restart answer is the inherited body, and a throw
                        // would be wrong; the previous body is the closer of the
                        // two that can still be given.
                        kept.add(m.name());
                        continue;
                    }
                    inherited.add(new Removal(m.name(), parent.getDeclaringClass().getName()));
                } else {
                    target = thrower(siteType, describe(owner, m.name(), type));
                    throwing.add(new Removal(m.name(), null));
                }
                targets.put(siteKey, target);
            } catch (RuntimeException | LinkageError unresolvable) {
                kept.add(m.name());
            }
        }
        return new Plan(targets, throwing, inherited, kept);
    }

    /**
     * The implementation a restarted JVM would resolve the call to: the
     * nearest superclass method with the same signature, then a default
     * method of an interface the class implements directly.
     */
    private static Method inheritedImplementation(Class<?> owner, String name, MethodType type, boolean isStatic) {
        Class<?>[] parameters = type.parameterArray();
        for (Class<?> c = owner.getSuperclass(); c != null; c = c.getSuperclass()) {
            Method found = declared(c, name, parameters, type.returnType());
            if (found == null) continue;
            int access = found.getModifiers();
            if (Modifier.isPrivate(access) || Modifier.isStatic(access) != isStatic) continue;
            return Modifier.isAbstract(access) ? null : found;
        }
        if (isStatic) return null;
        for (Class<?> i : owner.getInterfaces()) {
            Method found = declared(i, name, parameters, type.returnType());
            if (found != null && found.isDefault()) return found;
        }
        return null;
    }

    private static Method declared(Class<?> c, String name, Class<?>[] parameters, Class<?> returnType) {
        try {
            Method found = c.getDeclaredMethod(name, parameters);
            return found.getReturnType() == returnType ? found : null;
        } catch (NoSuchMethodException | SecurityException | LinkageError absent) {
            return null;
        }
    }

    /** A target that fails each call the way a restarted JVM would. */
    private static MethodHandle thrower(MethodType siteType, String message) {
        MethodHandle error = NEW_ERROR.bindTo(message).asType(MethodType.methodType(NoSuchMethodError.class));
        MethodHandle raise = MethodHandles.throwException(siteType.returnType(), NoSuchMethodError.class);
        MethodHandle call = MethodHandles.foldArguments(raise, error);
        return MethodHandles.dropArguments(call, 0, siteType.parameterList());
    }

    /** "'java.lang.String com.example.Foo.gone(int)'", as the JVM names a missing method. */
    private static String describe(Class<?> owner, String name, MethodType type) {
        return "'" + type.returnType().getTypeName() + " " + owner.getName() + "." + name
                + Stream.of(type.parameterArray()).map(Class::getTypeName)
                        .collect(Collectors.joining(", ", "(", ")"))
                + "' was removed by a reload. Restore the method or update its callers.";
    }
}
