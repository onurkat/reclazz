/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.AddedOperationBridge;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;

/** Reflective adapter for Spring-owned transaction/cache interceptors on added singleton methods. */
public final class SpringAddedOperations {
    private static final String TX_ADVISOR = "org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor";
    private static final String CACHE_ADVISOR = "org.springframework.cache.interceptor.BeanFactoryCacheOperationSourceAdvisor";
    private static final Set<String> CREATORS = Set.of(
            "org.springframework.aop.framework.autoproxy.InfrastructureAdvisorAutoProxyCreator",
            "org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator");
    private static final ClassValue<State> states = new ClassValue<>() {
        @Override protected State computeValue(Class<?> type) { return new State(type); }
    };
    private SpringAddedOperations() { }

    public static Set<String> publish(Class<?> owner, byte[] bytes, MethodHandles.Lookup lookup,
                                      List<Object> contexts) throws Exception {
        State state = states.get(owner);
        var plan = AddedOperationMetadata.create(owner, bytes, lookup, state.active);
        if (!plan.operations() && !state.active) return Set.of();
        state.active = true;
        state.contexts = contexts.stream().map(WeakReference::new).toList();
        state.discover(); // Capture identities before the ordinary bean refresh can replace them.
        state.clearMetadataCaches();
        Map<String, AddedOperationBridge.Invocation> routes = new HashMap<>();
        Set<String> covered = new HashSet<>();
        for (var entry : plan.entries()) {
            routes.put(entry.key(), (receiver, args, direct) -> state.invoke(entry, receiver, args, direct));
            covered.add(entry.method().getName() + org.objectweb.asm.Type.getMethodDescriptor(entry.method()));
        }
        for (String removed : state.keys) routes.putIfAbsent(removed, (receiver, args, direct) -> {
            throw refused(owner.getName() + "." + removed, "operation method was removed");
        });
        state.keys = Set.copyOf(routes.keySet());
        AddedOperationBridge.publish(owner, routes);
        return Set.copyOf(covered);
    }

    private static final class State {
        final Class<?> owner;
        final List<Binding> bindings = new ArrayList<>();
        volatile List<WeakReference<Object>> contexts = List.of();
        volatile Set<String> keys = Set.of();
        boolean active;
        State(Class<?> owner) { this.owner = owner; }

        synchronized void discover() throws Exception {
            bindings.removeIf(b -> b.bean.get() == null || b.context.get() == null);
            for (var reference : contexts) {
                Object context = reference.get();
                if (context == null || !(boolean) call(context, "isActive")) continue;
                Object factory = SpringBeans.getBeanFactory(context);
                if (factory == null) continue;
                ClassLoader loader = context.getClass().getClassLoader();
                Class<?> advised = Class.forName("org.springframework.aop.framework.Advised", false, loader);
                for (String name : (String[]) call(factory, "getSingletonNames")) {
                    Object bean = call(factory, "getSingleton", new Class<?>[]{String.class}, name);
                    if (bean == null || !owner.isInstance(bean)) continue;
                    bindings.removeIf(b -> b.bean.get() == bean);
                    Object target = bean;
                    String reason = null;
                    if (advised.isInstance(bean)) {
                        Object source = advised.getMethod("getTargetSource").invoke(bean);
                        if ((boolean) advised.getMethod("isFrozen").invoke(bean)) reason = "frozen proxy";
                        else if (!source.getClass().getName().equals("org.springframework.aop.target.SingletonTargetSource"))
                            reason = "dynamic or custom target source";
                        else {
                            target = call(source, "getTarget");
                            if (target == null || target.getClass() != owner) reason = "nested proxy or subclass target";
                        }
                    } else if (bean.getClass() != owner) reason = "opaque proxy or subclass bean";
                    bindings.add(new Binding(name, new WeakReference<>(bean), new WeakReference<>(target),
                            new WeakReference<>(factory), new WeakReference<>(context), reason));
                }
            }
        }

        synchronized Binding binding(Object receiver) throws Exception {
            Binding match = match(receiver);
            if (match != null) return match;
            discover();
            return match(receiver);
        }

        // A caller may hand us the bean (proxy) or the unwrapped target: a
        // scheduled or event task added to a proxied bean invokes the method on
        // the target, and either identity names the same binding.
        private Binding match(Object receiver) {
            for (Binding binding : bindings) if (binding.bean.get() == receiver) return binding;
            for (Binding binding : bindings) if (binding.target.get() == receiver) return binding;
            return null;
        }

        Object invoke(AddedOperationMetadata.Entry entry, Object receiver, Object[] args, MethodHandle direct) throws Throwable {
            Objects.requireNonNull(receiver);
            Binding binding = binding(receiver);
            if (binding == null) {
                // A prototype/unmanaged instance cannot be distinguished from a known service by type alone.
                // Do not infer transaction ownership for it, or silently execute an annotated operation.
                if (entry.transaction() || entry.cache()) throw refused(owner.getName(), "receiver is not a captured singleton bean");
                return body(direct, receiver, args);
            }
            String subject = owner.getName() + "." + entry.method().getName() + " [" + binding.name + "]";
            Object context = binding.context.get(), factory = binding.factory.get(), target = binding.target.get();
            if (context == null || factory == null || target == null || !(boolean) call(context, "isActive"))
                throw refused(subject, "application context or target is no longer live");
            if (binding.reason != null) throw refused(subject, binding.reason);
            if (entry.reason() != null) throw refused(subject, entry.reason());
            ClassLoader loader = context.getClass().getClassLoader();
            Class<?> advised = Class.forName("org.springframework.aop.framework.Advised", false, loader);
            if (advised.isInstance(receiver)) {
                if ((boolean) advised.getMethod("isFrozen").invoke(receiver)) throw refused(subject, "frozen proxy");
                Object source = advised.getMethod("getTargetSource").invoke(receiver);
                if (!source.getClass().getName().equals("org.springframework.aop.target.SingletonTargetSource"))
                    throw refused(subject, "dynamic or custom target source");
                if (call(source, "getTarget") != target) throw refused(subject, "proxy target changed after capture");
                for (Object advisor : (Object[]) advised.getMethod("getAdvisors").invoke(receiver)) {
                    if (!supported(advisor)) throw refused(subject, "additional proxy advisor " + advisor.getClass().getName());
                }
            }
            Class<?> creatorType = Class.forName("org.springframework.aop.framework.autoproxy.AbstractAdvisorAutoProxyCreator", false, loader);
            String[] names = (String[]) call(factory, "getBeanNamesForType",
                    new Class<?>[]{Class.class, boolean.class, boolean.class}, creatorType, false, false);
            if (names.length == 0 && !entry.transaction() && !entry.cache()) return body(direct, target, args);
            if (names.length != 1) throw refused(subject, "expected one operation auto-proxy creator");
            Object creator = call(factory, "getBean", new Class<?>[]{String.class}, names[0]);
            if (!CREATORS.contains(creator.getClass().getName())) throw refused(subject, "custom auto-proxy creator");
            var applicable = new ArrayList<Object>();
            boolean transaction = false, cache = false;
            for (Object advisor : (List<?>) inherited(creator, "findCandidateAdvisors").invoke(creator)) {
                if (!supported(advisor)) throw refused(subject, "additional candidate advisor " + advisor.getClass().getName());
                Object pointcut = Class.forName("org.springframework.aop.PointcutAdvisor", false, loader).getMethod("getPointcut").invoke(advisor);
                Class<?> pointcutType = Class.forName("org.springframework.aop.Pointcut", false, loader);
                Object filter = pointcutType.getMethod("getClassFilter").invoke(pointcut);
                if (!(boolean) Class.forName("org.springframework.aop.ClassFilter", false, loader).getMethod("matches", Class.class).invoke(filter, owner)) continue;
                Object matcher = pointcutType.getMethod("getMethodMatcher").invoke(pointcut);
                Class<?> matcherType = Class.forName("org.springframework.aop.MethodMatcher", false, loader);
                if ((boolean) matcherType.getMethod("isRuntime").invoke(matcher)) throw refused(subject, "runtime operation pointcut");
                if ((boolean) matcherType.getMethod("matches", Method.class, Class.class).invoke(matcher, entry.method(), owner)) {
                    applicable.add(advisor);
                    transaction |= advisor.getClass().getName().equals(TX_ADVISOR);
                    cache |= advisor.getClass().getName().equals(CACHE_ADVISOR);
                }
            }
            if (entry.transaction() && !transaction) throw refused(subject, "no matching transaction advisor");
            if (entry.cache() && !cache) throw refused(subject, "no matching cache advisor");
            List<?> sorted = (List<?>) inherited(creator, "sortAdvisors", List.class).invoke(creator, applicable);
            var advice = new ArrayList<Object>();
            Class<?> advisorType = Class.forName("org.springframework.aop.Advisor", false, loader);
            for (Object advisor : sorted) {
                Object interceptor = advisorType.getMethod("getAdvice").invoke(advisor);
                boolean tx = advisor.getClass().getName().equals(TX_ADVISOR);
                String expected = tx ? "org.springframework.transaction.interceptor.TransactionInterceptor"
                        : "org.springframework.cache.interceptor.CacheInterceptor";
                if (!interceptor.getClass().getName().equals(expected)) throw refused(subject, "custom operation interceptor");
                Object source = call(interceptor, tx ? "getTransactionAttributeSource" : "getCacheOperationSource");
                String sourceType = tx ? "org.springframework.transaction.annotation.AnnotationTransactionAttributeSource"
                        : "org.springframework.cache.annotation.AnnotationCacheOperationSource";
                if (source == null || !source.getClass().getName().equals(sourceType)) throw refused(subject, "custom operation metadata source");
                advice.add(interceptor);
            }
            return new Invocation(entry.method(), target, args, direct, advice, loader).proceed();
        }

        void clearMetadataCaches() throws Exception {
            for (var reference : contexts) {
                Object context = reference.get();
                if (context == null || !(boolean) call(context, "isActive")) continue;
                Object factory = SpringBeans.getBeanFactory(context);
                if (factory == null) continue;
                for (String name : (String[]) call(factory, "getSingletonNames")) {
                    Object bean = call(factory, "getSingleton", new Class<?>[]{String.class}, name);
                    if (bean == null) continue;
                    String type = bean.getClass().getName();
                    if (type.equals("org.springframework.cache.interceptor.CacheInterceptor"))
                        inherited(bean, "clearMetadataCache").invoke(bean);
                    if (type.equals("org.springframework.cache.annotation.AnnotationCacheOperationSource")
                            || type.equals("org.springframework.transaction.annotation.AnnotationTransactionAttributeSource"))
                        SpringOperationSourceReloader.clearAttributeCache(bean);
                }
            }
        }
    }

    private static final class Invocation {
        final Method metadata;
        final Object target;
        final Object[] args;
        final MethodHandle direct;
        final List<Object> advice;
        final Method invoke;
        final Object spi;
        int index;
        Invocation(Method metadata, Object target, Object[] args, MethodHandle direct, List<Object> advice, ClassLoader loader) throws Exception {
            this.metadata = metadata; this.target = target; this.args = args; this.direct = direct; this.advice = advice;
            Class<?> type = Class.forName("org.aopalliance.intercept.MethodInvocation", false, loader);
            invoke = Class.forName("org.aopalliance.intercept.MethodInterceptor", false, loader).getMethod("invoke", type);
            spi = Proxy.newProxyInstance(loader, new Class<?>[]{type}, (proxy, method, parameters) -> switch (method.getName()) {
                case "getMethod", "getStaticPart" -> metadata;
                case "getThis" -> target;
                case "getArguments" -> args;
                case "proceed" -> proceed();
                case "toString" -> "Reclazz operation " + metadata.getName();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == parameters[0];
                default -> throw new UnsupportedOperationException(method.toString());
            });
        }
        Object proceed() throws Throwable {
            if (index == advice.size()) return body(direct, target, args);
            try { return invoke.invoke(advice.get(index++), spi); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }

    private static boolean supported(Object advisor) {
        String name = advisor.getClass().getName();
        return name.equals(TX_ADVISOR) || name.equals(CACHE_ADVISOR);
    }
    private static Object body(MethodHandle direct, Object target, Object[] args) throws Throwable {
        Object[] all = new Object[args.length + 1]; all[0] = target;
        System.arraycopy(args, 0, all, 1, args.length);
        return direct.invokeWithArguments(all);
    }
    private static IllegalStateException refused(String subject, String reason) {
        String message = "Added Spring operation refused for " + subject + ": " + reason;
        StatusReporter.warn(message); RestartLedger.note(subject, reason);
        return new IllegalStateException(message);
    }
    private static Object call(Object object, String name, Class<?>[] types, Object... args) throws Exception {
        return object.getClass().getMethod(name, types).invoke(object, args);
    }
    private static Object call(Object object, String name) throws Exception { return call(object, name, new Class<?>[0]); }
    private static Method inherited(Object object, String name, Class<?>... types) throws NoSuchMethodException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { Method method = type.getDeclaredMethod(name, types); method.setAccessible(true); return method; }
            catch (NoSuchMethodException next) { /* inherited framework API */ }
        }
        throw new NoSuchMethodException(name);
    }
    private record Binding(String name, WeakReference<Object> bean, WeakReference<Object> target,
                           WeakReference<Object> factory, WeakReference<Object> context, String reason) { }
}
