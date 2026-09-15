/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.AddedBeanBridge;
import com.onurkat.reclazz.bootstrap.InjectedNames;
import org.objectweb.asm.Opcodes;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

/** References to added full-configuration factories resolve through Spring's bean factory. */
final class SpringConfigurationCalls {
    private static final String ENHANCED = "org.springframework.context.annotation.ConfigurationClassEnhancer$EnhancedConfiguration";
    // The native bean factory invokes instance suppliers without explicit args.
    // Carry only the current synchronous bean-reference call to its own supplier.
    private static final ThreadLocal<ExplicitArguments> EXPLICIT_ARGUMENTS = new ThreadLocal<>();
    private static final class ExplicitArguments {
        final Object factory;
        final Class<?> owner;
        final String name, descriptor;
        final Object[] values;
        boolean consumed;
        ExplicitArguments(Object factory, Class<?> owner, AddedBeanAdapter.Factory method, Object[] values) {
            this.factory = factory; this.owner = owner;
            this.name = method.name(); this.descriptor = method.method().desc;
            this.values = values.clone();
        }
    }
    private SpringConfigurationCalls() { }

    static java.util.function.Supplier<Object[]> arguments(Object factory, Class<?> owner,
            AddedBeanAdapter.Factory method, java.util.function.Supplier<Object[]> resolved) {
        return () -> {
            ExplicitArguments call = EXPLICIT_ARGUMENTS.get();
            if (call != null && !call.consumed && call.factory == factory && call.owner == owner
                    && call.name.equals(method.name()) && call.descriptor.equals(method.method().desc)) {
                call.consumed = true;
                return call.values;
            }
            return resolved.get();
        };
    }

    private static Object reference(Object beanFactory, Class<?> owner, AddedBeanAdapter.Factory method,
                                    Object[] arguments) throws Throwable {
        boolean explicit = arguments.length != 0;
        // Native ConfigurationClassEnhancer treats null singleton arguments as
        // reference stubs. It resolves the entire argument list in that case.
        try {
            if (explicit && (boolean) beanFactory.getClass().getMethod("isSingleton", String.class)
                    .invoke(beanFactory, method.name()))
                for (Object argument : arguments) if (argument == null) { explicit = false; break; }
        } catch (InvocationTargetException failure) { throw failure.getCause(); }
        ExplicitArguments previous = EXPLICIT_ARGUMENTS.get();
        if (explicit) EXPLICIT_ARGUMENTS.set(new ExplicitArguments(beanFactory, owner, method, arguments));
        try {
            // Native supplier creation tracks this getBean as a dependency;
            // native singleton creation also detects factory call cycles.
            return explicit
                    ? beanFactory.getClass().getMethod("getBean", String.class, Object[].class)
                        .invoke(beanFactory, method.name(), arguments)
                    : beanFactory.getClass().getMethod("getBean", String.class).invoke(beanFactory, method.name());
        } catch (InvocationTargetException failure) { throw failure.getCause(); }
        finally {
            if (explicit) {
                if (previous == null) EXPLICIT_ARGUMENTS.remove();
                else EXPLICIT_ARGUMENTS.set(previous);
            }
        }
    }

    static boolean matches(Object config, Class<?> owner, Object factory, boolean full) throws Exception {
        return full ? enhancedFactory(config, owner) == factory : config.getClass() == owner;
    }

    private static Object enhancedFactory(Object receiver, Class<?> owner) throws Exception {
        Class<?> actual = receiver.getClass();
        Class<?> enhanced = Class.forName(ENHANCED, false, owner.getClassLoader());
        if (actual.getSuperclass() != owner || !enhanced.isInstance(receiver))
            throw new IllegalStateException("configuration is not Spring's direct enhanced subclass");
        try { return actual.getDeclaredField("$$beanFactory").get(receiver); }
        catch (NoSuchFieldException missing) {
            throw new IllegalStateException("configuration is missing Spring's native bean factory field", missing);
        }
    }

    static void publish(Class<?> owner, AddedBeanAdapter.Plan plan) {
        Map<String, AddedBeanBridge.Invocation> methods = new HashMap<>();
        if (plan.full()) for (var factory : plan.factories()) {
            if ((factory.method().access & Opcodes.ACC_STATIC) != 0) continue;
            String key = InjectedNames.siteKey(factory.method().name, InjectedNames.descHash(factory.method().desc));
            methods.put(key, (receiver, arguments, direct) -> {
                Objects.requireNonNull(receiver);
                // A manually constructed configuration has ordinary Java semantics.
                if (receiver.getClass() == owner) {
                    Object[] all = new Object[arguments.length + 1]; all[0] = receiver;
                    System.arraycopy(arguments, 0, all, 1, arguments.length);
                    return direct.invokeWithArguments(all);
                }
                Object beanFactory = enhancedFactory(receiver, owner);
                if (beanFactory == null) throw new IllegalStateException("configuration has no bean factory");
                return reference(beanFactory, owner, factory, arguments);
            });
        }
        // Only class/method metadata lives here; never a context, bean or factory.
        AddedBeanBridge.publish(owner, methods);
    }
}
