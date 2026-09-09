/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Updates singleton proxy chains through Spring's API, without replacing their targets. */
final class SpringAopProxyRefresh {
    static final String CREATOR = "org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator";

    private SpringAopProxyRefresh() {}

    static int refresh(Object factory, Object creator, Class<?> aspect) throws Exception {
        if (!creator.getClass().getName().equals(CREATOR)) {
            skipped(aspect.getName(), "custom auto-proxy creator " + creator.getClass().getName());
            return 0;
        }
        ClassLoader loader = creator.getClass().getClassLoader();
        Class<?> advised = Class.forName("org.springframework.aop.framework.Advised", false, loader);
        Class<?> precedence = Class.forName("org.springframework.aop.aspectj.AspectJPrecedenceInformation", false, loader);
        Class<?> advisorType = Class.forName("org.springframework.aop.Advisor", false, loader);
        Method aspectName = precedence.getMethod("getAspectName");
        Method getAdvisors = advised.getMethod("getAdvisors");
        Method getTargetSource = advised.getMethod("getTargetSource");
        Method getTargetClass = Class.forName("org.springframework.aop.TargetClassAware", false, loader).getMethod("getTargetClass");
        Method isAopProxy = Class.forName("org.springframework.aop.support.AopUtils", false, loader).getMethod("isAopProxy", Object.class);
        Method find = method(creator, "findAdvisorsThatCanApply", List.class, Class.class, String.class);
        Method extend = method(creator, "extendAdvisors", List.class);
        Method sort = method(creator, "sortAdvisors", List.class);
        Set<String> aspectNames = Set.copyOf(Arrays.asList((String[]) Class.forName(
                        "org.springframework.beans.factory.BeanFactoryUtils", false, loader)
                .getMethod("beanNamesForTypeIncludingAncestors", Class.forName(
                        "org.springframework.beans.factory.ListableBeanFactory", false, loader),
                        Class.class, boolean.class, boolean.class)
                .invoke(null, factory, aspect, true, false)));
        if (aspectNames.isEmpty()) return 0;
        var candidates = new ArrayList<Object>();
        // Parse before touching any live chain. Invalid expressions leave all existing proxies alone.
        for (Object candidate : (List<?>) method(creator, "findCandidateAdvisors").invoke(creator)) {
            if (belongs(candidate, precedence, aspectName, aspectNames)) {
                if ((boolean) advisorType.getMethod("isPerInstance").invoke(candidate)) {
                    skipped(aspect.getName(), "per-instance aspect advisors");
                    return 0;
                }
                // matches(Class) catches parser failures and returns false in Spring 5.3.
                // Force parsing first so invalid syntax cannot look like intentional removal.
                Object pointcut = Class.forName("org.springframework.aop.PointcutAdvisor", false, loader)
                        .getMethod("getPointcut").invoke(candidate);
                Class.forName("org.springframework.aop.aspectj.AspectJExpressionPointcut", false, loader)
                        .getMethod("getPointcutExpression").invoke(pointcut);
                candidates.add(candidate);
            }
        }
        var changes = new ArrayList<Change>();
        String[] names = (String[]) factory.getClass().getMethod("getSingletonNames").invoke(factory);
        Method singleton = factory.getClass().getMethod("getSingleton", String.class);
        for (String name : names) {
            Object bean = singleton.invoke(factory, name);
            if (bean == null) continue;
            boolean proxy = advised.isInstance(bean);
            if (!proxy && (boolean) isAopProxy.invoke(null, bean)) {
                skipped(name, "opaque proxy (advisor chain unavailable)");
                continue;
            }
            Class<?> targetClass = proxy ? (Class<?>) getTargetClass.invoke(bean) : bean.getClass();
            if (targetClass == null) continue;
            if ((boolean) method(creator, "isInfrastructureClass", Class.class).invoke(creator, targetClass)
                    || (boolean) method(creator, "shouldSkip", Class.class, String.class).invoke(creator, targetClass, name)) continue;
            Object[] old = proxy ? (Object[]) getAdvisors.invoke(bean) : new Object[0];
            List<?> fresh = (List<?>) find.invoke(creator, candidates, targetClass, name);
            boolean hadAspect = Arrays.stream(old).anyMatch(a -> belongs(a, precedence, aspectName, aspectNames));
            if (!hadAspect && fresh.isEmpty()) continue;
            if (!proxy) {
                skipped(name, "previously unproxied singleton");
                continue;
            }
            if ((boolean) advised.getMethod("isFrozen").invoke(bean)) {
                skipped(name, "frozen proxy");
                continue;
            }
            Object source = getTargetSource.invoke(bean);
            if (!source.getClass().getName().equals("org.springframework.aop.target.SingletonTargetSource")) {
                skipped(name, "dynamic or custom target source");
                continue;
            }
            Object target = source.getClass().getMethod("getTarget").invoke(source);
            if ((boolean) isAopProxy.invoke(null, target)) {
                skipped(name, "nested proxy");
                continue;
            }
            var kept = new ArrayList<Object>();
            for (Object a : old) if (!belongs(a, precedence, aspectName, aspectNames)) kept.add(a);
            var next = new ArrayList<>(kept);
            next.addAll(fresh);
            // Supplies ExposeInvocationInterceptor when this proxy gains its first AspectJ advice.
            extend.invoke(creator, next);
            List<?> sorted = (List<?>) sort.invoke(creator, next);
            var keptAfterSort = sorted.stream().filter(a -> kept.stream().anyMatch(k -> k == a)).toList();
            if (!sameIdentities(kept, keptAfterSort)) {
                skipped(name, "custom advisor order would change");
                continue;
            }
            changes.add(new Change(name, bean, old, sorted.toArray()));
        }
        // Preparation above cannot expose half a parsed pointcut. Spring's public mutation API
        // invalidates warmed method chains; this is not an atomic transaction with in-flight calls.
        Method remove = advised.getMethod("removeAdvisor", int.class);
        Method add = advised.getMethod("addAdvisor", int.class, advisorType);
        int updated = 0;
        for (Change change : changes) {
            try {
                replace(change.bean(), change.next(), getAdvisors, remove, add);
                updated++;
            } catch (Exception failure) {
                try { replace(change.bean(), change.old(), getAdvisors, remove, add); }
                catch (Exception rollback) { failure.addSuppressed(rollback); }
                skipped(change.name(), "advisor update failed: " + com.onurkat.reclazz.ui.Failures.describe(failure));
            }
        }
        return updated;
    }

    private static void replace(Object proxy, Object[] next, Method get, Method remove, Method add) throws Exception {
        // Keep unchanged advisors in place (transaction/cache/user advice included).
        var current = new ArrayList<>(Arrays.asList((Object[]) get.invoke(proxy)));
        for (int i = 0; i < next.length; i++) {
            if (i < current.size() && current.get(i) == next[i]) continue;
            int found = -1;
            for (int j = i; j < current.size(); j++) if (current.get(j) == next[i]) { found = j; break; }
            if (found >= 0) { remove.invoke(proxy, found); current.remove(found); }
            add.invoke(proxy, i, next[i]);
            current.add(i, next[i]);
        }
        for (int i = current.size() - 1; i >= next.length; i--) remove.invoke(proxy, i);
    }

    private static boolean sameIdentities(List<?> a, List<?> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    private static boolean belongs(Object advisor, Class<?> precedence, Method name, Set<String> names) {
        if (!precedence.isInstance(advisor)) return false;
        try { return names.contains(name.invoke(advisor)); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private static Method method(Object target, String name, Class<?>... arguments) throws NoSuchMethodException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, arguments);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException next) { /* inherited protected API */ }
        }
        throw new NoSuchMethodException(name);
    }

    static void skipped(String subject, String reason) {
        String message = "AOP pointcut refresh needs a restart for " + subject + ": " + reason;
        StatusReporter.warn(message);
        RestartLedger.note(subject, "AOP pointcut refresh: " + reason);
    }

    private record Change(String name, Object bean, Object[] old, Object[] next) {}
}
