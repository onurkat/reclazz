/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Links a lambda whose implementation lives in a hidden companion class.
 *
 * <p>javac links every lambda through {@code LambdaMetafactory}, which spins a
 * proxy class that invokes the implementation method <em>by name</em>. A
 * companion is a hidden class, and a hidden class has no name another class
 * can resolve, so the proxy failed its first call with
 * {@code ClassNotFoundException: demo.Api$$Reclazz$v1/0x...} out of a source
 * line that looked perfectly ordinary (measured on JDK 21; the metafactory
 * has no hidden-owner path for this shape). Resolving by name against the
 * original class instead is not merely limited but wrong: javac numbers the
 * {@code lambda$...} synthetics per class in declaration order, so one added
 * lambda renumbers every one after it, and a name that still resolves can
 * quietly be a different lambda's body.
 *
 * <p>So the companion's lambda call sites link here instead. By the time this
 * bootstrap runs, the JVM has already resolved the implementation
 * MethodHandle (a hidden class may reference itself in its own constant
 * pool), and a resolved handle needs no name: the captured arguments are
 * bound onto it and {@link MethodHandleProxies#asInterfaceInstance} wraps the
 * result in the functional interface. Version-exact by construction, because
 * the handle is the companion's current body, not a name that might resolve
 * to an older one.
 *
 * <p>Two honest differences from the metafactory's proxies, both invisible to
 * code that treats a lambda as a lambda: the object is a reflective proxy
 * rather than a spun class (a per-call cost that belongs to a development
 * reload, not production), and a serializable lambda loses its
 * serializability, which {@link #altMetafactory} accepts silently because a
 * reloaded lambda that must cross a serialization boundary needs a restart
 * anyway.
 *
 * <p>BOOTSTRAP CLASS: must have ZERO dependencies outside java.*.
 */
public final class LambdaFactory {

    private LambdaFactory() {
    }

    private static final MethodHandle MAKE;

    static {
        try {
            MAKE = MethodHandles.lookup().findStatic(LambdaFactory.class, "make",
                    MethodType.methodType(Object.class, Class.class,
                            MethodHandle.class, Object[].class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Drop-in signature match for {@code LambdaMetafactory.metafactory}.
     *
     * @param invokedType (captured args) &rarr; functional interface
     * @param implMethod  already resolved against the companion, which is the
     *                    whole point
     */
    public static CallSite metafactory(MethodHandles.Lookup caller, String invokedName,
                                       MethodType invokedType, MethodType samMethodType,
                                       MethodHandle implMethod, MethodType instantiatedMethodType) {
        Class<?> samType = invokedType.returnType();
        MethodHandle make = MethodHandles.insertArguments(MAKE, 0, samType, implMethod);
        make = make.asCollector(Object[].class, invokedType.parameterCount())
                .asType(invokedType);
        return new ConstantCallSite(make);
    }

    /**
     * Drop-in signature match for {@code LambdaMetafactory.altMetafactory},
     * which javac uses for serializable lambdas and marker interfaces. The
     * extra flags are accepted and ignored: the object built here implements
     * the functional interface and nothing else.
     */
    public static CallSite altMetafactory(MethodHandles.Lookup caller, String invokedName,
                                          MethodType invokedType, Object... args) {
        return metafactory(caller, invokedName, invokedType,
                (MethodType) args[0], (MethodHandle) args[1], (MethodType) args[2]);
    }

    /** Bind the captures, wrap the rest as the interface. */
    @SuppressWarnings("unused")
    private static Object make(Class<?> samType, MethodHandle impl, Object[] captured)
            throws Throwable {
        MethodHandle bound = MethodHandles.insertArguments(impl, 0, captured);
        return MethodHandleProxies.asInterfaceInstance(samType, bound);
    }
}
