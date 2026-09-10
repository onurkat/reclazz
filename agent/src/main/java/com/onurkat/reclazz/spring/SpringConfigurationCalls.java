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
    private SpringConfigurationCalls() { }

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
                try {
                    // Native supplier creation tracks this getBean as a dependency;
                    // native singleton creation also detects factory call cycles.
                    return beanFactory.getClass().getMethod("getBean", String.class).invoke(beanFactory, factory.name());
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
        // Only class/method metadata lives here; never a context, bean or factory.
        AddedBeanBridge.publish(owner, methods);
    }
}
