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

    // ClassValue does not keep an application loader alive. Neither the weak
    // identity key nor its value retains a receiver or its container.
    private static final ClassValue<RawReceivers> RAW = new ClassValue<>() {
        @Override protected RawReceivers computeValue(Class<?> owner) { return new RawReceivers(); }
    };
    private static final class ReceiverKey extends java.lang.ref.WeakReference<Object> {
        private final int hash;
        ReceiverKey(Object receiver, java.lang.ref.ReferenceQueue<Object> queue) {
            super(receiver, queue); hash = System.identityHashCode(receiver);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof ReceiverKey key && get() != null && get() == key.get();
        }
    }
    private static final class RawReceivers {
        final java.lang.ref.ReferenceQueue<Object> queue = new java.lang.ref.ReferenceQueue<>();
        final Map<ReceiverKey, Enrollment> entries = new HashMap<>();
        synchronized Enrollment get(Object receiver) {
            clean(); return entries.get(new ReceiverKey(receiver, null));
        }
        synchronized void abort(Object receiver, Enrollment pending) {
            ReceiverKey key = new ReceiverKey(receiver, null);
            entries.remove(key, pending);
        }
        synchronized void put(Object receiver, Enrollment enrollment) {
            clean(); entries.put(new ReceiverKey(receiver, queue), enrollment);
        }
        private void clean() {
            java.lang.ref.Reference<?> key;
            while ((key = queue.poll()) != null) entries.remove(key);
        }
    }
    record MethodOwnership(java.lang.ref.WeakReference<Object> definition,
            java.util.concurrent.atomic.AtomicReference<java.lang.ref.WeakReference<Object>> instance) { }
    static final class Enrollment {
        final java.lang.ref.WeakReference<Object> context, factory, definition;
        final String name;
        final Map<String, MethodOwnership> methods;
        volatile boolean retired;
        boolean pending;
        long generation;
        Enrollment(Object context, Object factory, Object definition, String name,
                   Map<String, MethodOwnership> methods) {
            this.context = new java.lang.ref.WeakReference<>(context);
            this.factory = new java.lang.ref.WeakReference<>(factory);
            this.definition = new java.lang.ref.WeakReference<>(definition);
            this.name = name; this.methods = Map.copyOf(methods);
        }
    }
    static Enrollment pending(Object receiver, Class<?> owner, Object context, Object factory, Object definition, String name) {
        Enrollment previous = RAW.get(owner).get(receiver);
        if (previous != null) {
            if (previous.retired || previous.pending || previous.factory.get() != factory)
                throw restart("raw receiver cannot start another enrollment");
            return null; // Prior ACTIVE generation remains available throughout staging.
        }
        Enrollment pending = new Enrollment(context, factory, definition, name, Map.of());
        pending.pending = true;
        RAW.get(owner).put(receiver, pending);
        return pending;
    }
    static void abort(Object receiver, Class<?> owner, Enrollment pending) {
        if (pending != null) RAW.get(owner).abort(receiver, pending);
    }
    static void activate(Object receiver, Class<?> owner, Object context, Object factory,
                         Object definition, String name, Map<String, MethodOwnership> methods) {
        Enrollment previous = RAW.get(owner).get(receiver);
        if (previous != null && (previous.retired || previous.factory.get() != factory))
            throw restart("raw configuration receiver is retired or belongs to another factory");
        Enrollment next = new Enrollment(context, factory, definition, name, methods);
        next.generation = previous == null ? 1 : previous.generation + 1;
        RAW.get(owner).put(receiver, next);
    }
    static void retire(Object receiver, Class<?> owner) {
        Enrollment enrollment = RAW.get(owner).get(receiver);
        if (enrollment != null) { enrollment.pending = false; enrollment.retired = true; }
    }
    static boolean enrolled(Object receiver, Class<?> owner) { return RAW.get(owner).get(receiver) != null; }
    private static IllegalStateException restart(String reason) {
        return new IllegalStateException("raw configuration needs a restart: " + reason);
    }
    private static Object rawFactory(Object receiver, Class<?> owner, String methodName) throws Exception {
        Enrollment enrollment = RAW.get(owner).get(receiver);
        if (enrollment == null) return null;
        if (enrollment.pending) throw restart("registration is pending");
        Object context = enrollment.context.get(), factory = enrollment.factory.get(), definition = enrollment.definition.get();
        boolean valid = !enrollment.retired && !enrollment.pending && context != null && factory != null && definition != null
                && Boolean.TRUE.equals(context.getClass().getMethod("isActive").invoke(context))
                && SpringBeans.getBeanFactory(context) == factory && rawShape(owner, definition)
                && Boolean.TRUE.equals(factory.getClass().getMethod("containsBeanDefinition", String.class).invoke(factory, enrollment.name))
                && factory.getClass().getMethod("getBeanDefinition", String.class).invoke(factory, enrollment.name) == definition
                && factory.getClass().getMethod("getSingleton", String.class).invoke(factory, enrollment.name) == receiver
                && Boolean.TRUE.equals(factory.getClass().getMethod("isSingleton", String.class).invoke(factory, enrollment.name));
        if (!valid) { enrollment.retired = true; throw restart("owning context or singleton changed"); }
        if (methodName != null) {
            var product = enrollment.methods.get(methodName);
            Object expected = product == null ? null : product.definition().get();
            if (expected == null || !Boolean.TRUE.equals(factory.getClass().getMethod("containsBeanDefinition", String.class).invoke(factory, methodName))
                    || factory.getClass().getMethod("getBeanDefinition", String.class).invoke(factory, methodName) != expected)
                throw restart("factory method is no longer owned: " + methodName);
            Object singleton = factory.getClass().getMethod("getSingleton", String.class).invoke(factory, methodName);
            if (singleton != null && singleton != product.instance().get().get())
                throw restart("factory product was replaced: " + methodName);
        }
        return factory;
    }


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
        if (full && config.getClass() == owner && !enrolled(config, owner))
            return enhancedFactory(config, owner) == factory; // Preserve native-route refusal for a manual receiver.
        return full ? (config.getClass() == owner
                ? rawFactory(config, owner, null) == factory : enhancedFactory(config, owner) == factory)
                : config.getClass() == owner;
    }

    static boolean matchesMethod(Object config, Class<?> owner, Object factory, boolean full, String name) throws Exception {
        return full && config.getClass() == owner ? rawFactory(config, owner, name) == factory : matches(config, owner, factory, full);
    }

    static boolean rawShape(Class<?> owner, Object definition) throws Exception {
        if (!definition.getClass().getName().equals("org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition")) return false;
        SpringMemberLayout.RAW_CONFIGURATION.require(definition.getClass());
        ClassLoader spring = definition.getClass().getClassLoader();
        Class<?> utils = Class.forName("org.springframework.context.annotation.ConfigurationClassUtils", false, spring);
        var attribute = utils.getDeclaredField("CONFIGURATION_CLASS_ATTRIBUTE"); attribute.setAccessible(true);
        String key = (String) attribute.get(null);
        boolean full = false;
        for (var annotation : owner.getDeclaredAnnotations())
            if (annotation.annotationType().getName().equals("org.springframework.context.annotation.Configuration"))
                full = Boolean.TRUE.equals(annotation.annotationType().getMethod("proxyBeanMethods").invoke(annotation));
        return full
                && "lite".equals(definition.getClass().getMethod("getAttribute", String.class).invoke(definition, key))
                && Integer.valueOf(0).equals(definition.getClass().getMethod("getRole").invoke(definition))
                && definition.getClass().getMethod("getBeanClass").invoke(definition) == owner
                && definition.getClass().getMethod("getFactoryBeanName").invoke(definition) == null
                && definition.getClass().getMethod("getFactoryMethodName").invoke(definition) == null;
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
                    Object managed = rawFactory(receiver, owner, factory.name());
                    if (managed != null) return reference(managed, owner, factory, arguments);
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
