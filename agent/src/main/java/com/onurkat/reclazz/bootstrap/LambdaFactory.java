/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.io.Serializable;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.LambdaMetafactory;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

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
 * <p>Extra marker interfaces, including {@code Serializable}, are preserved
 * by a reflective proxy. Serialization itself still fails: its invocation
 * handler is not serializable. A reloaded lambda that must cross a
 * serialization boundary needs a restart.
 *
 * <p>BOOTSTRAP CLASS: must have ZERO dependencies outside java.*.
 */
public final class LambdaFactory {

    private LambdaFactory() {
    }

    private static final MethodHandle MAKE;
    private static final MethodHandle MAKE_WITH_INTERFACES;

    static {
        try {
            MAKE = MethodHandles.lookup().findStatic(LambdaFactory.class, "make",
                    MethodType.methodType(Object.class, Class.class,
                            MethodHandle.class, Object[].class));
            MAKE_WITH_INTERFACES = MethodHandles.lookup().findStatic(LambdaFactory.class,
                    "makeWithInterfaces", MethodType.methodType(Object.class, ClassLoader.class,
                            Class[].class, String.class, MethodType[].class,
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
     * The variant with marker interfaces, bridges and serializable lambdas.
     *
     * <p>No Java caller, and not dead: the companion generator rewrites the
     * bootstrap handle keeping {@code bsm.getName()}, so a call site javac
     * linked through {@code LambdaMetafactory.altMetafactory} arrives here
     * under the same name. Removing it turns any lambda of that shape in a
     * reloaded class into a NoSuchMethodError at its first call.
     */
    public static CallSite altMetafactory(MethodHandles.Lookup caller, String invokedName,
                                          MethodType invokedType, Object... args) {
        Class<?> samType = invokedType.returnType();
        var interfaces = new LinkedHashSet<Class<?>>();
        interfaces.add(samType);
        var methods = new ArrayList<MethodType>();
        methods.add((MethodType) args[0]);
        int flags = (Integer) args[3];
        int next = 4;
        if ((flags & LambdaMetafactory.FLAG_MARKERS) != 0) {
            int count = (Integer) args[next++];
            for (int i = 0; i < count; i++) interfaces.add((Class<?>) args[next++]);
        }
        if ((flags & LambdaMetafactory.FLAG_BRIDGES) != 0) {
            int count = (Integer) args[next++];
            for (int i = 0; i < count; i++) methods.add((MethodType) args[next++]);
        }
        if ((flags & LambdaMetafactory.FLAG_SERIALIZABLE) != 0) {
            interfaces.add(Serializable.class);
        }
        if (interfaces.size() == 1) {
            return metafactory(caller, invokedName, invokedType,
                    (MethodType) args[0], (MethodHandle) args[1], (MethodType) args[2]);
        }
        // The SAM may be a bootstrap interface while a marker belongs to the
        // application. The capturing class's loader can see both.
        MethodHandle make = MethodHandles.insertArguments(MAKE_WITH_INTERFACES, 0,
                caller.lookupClass().getClassLoader(), interfaces.toArray(Class<?>[]::new),
                invokedName, methods.toArray(MethodType[]::new), (MethodHandle) args[1]);
        return new ConstantCallSite(make.asCollector(Object[].class, invokedType.parameterCount())
                .asType(invokedType));
    }

    /** Bind the captures, wrap the rest as the interface. */
    @SuppressWarnings("unused")
    private static Object make(Class<?> samType, MethodHandle impl, Object[] captured)
            throws Throwable {
        MethodHandle bound = MethodHandles.insertArguments(impl, 0, captured);
        return MethodHandleProxies.asInterfaceInstance(samType, bound);
    }

    /** Extra interfaces need an explicit proxy on JDKs whose MH wrappers omit them. */
    @SuppressWarnings("unused")
    private static Object makeWithInterfaces(ClassLoader loader, Class<?>[] interfaces,
                                              String methodName, MethodType[] methods,
                                              MethodHandle impl, Object[] captured) {
        MethodHandle bound = MethodHandles.insertArguments(impl, 0, captured);
        List<MethodType> signatures = List.of(methods);
        // This handler intentionally does not implement Serializable.
        return Proxy.newProxyInstance(loader, interfaces, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> interfaces[0].getName() + "@"
                            + Integer.toHexString(System.identityHashCode(proxy));
                    default -> throw new UnsupportedOperationException(method.toString());
                };
            }
            MethodType signature = MethodType.methodType(method.getReturnType(), method.getParameterTypes());
            if (method.getName().equals(methodName) && signatures.contains(signature)) {
                return bound.asType(signature).invokeWithArguments(args == null ? new Object[0] : args);
            }
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            throw new AbstractMethodError(method.toString());
        });
    }

}
