/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import java.util.Set;

/**
 * Resolves the instance an added scheduled or event method runs on. A plain
 * singleton is returned as is; a standard transaction/cache proxy is unwrapped
 * to its target so the method runs against the real fields, while any advice the
 * method needs is applied by the added-operation bridge on its call site. Any
 * other proxy shape is refused with a named reason.
 */
final class AddedProxyTarget {
    // The standard tx/cache advisors are the supported proxy shape, matching
    // the added-operation infrastructure that applies their advice.
    private static final Set<String> SUPPORTED_ADVISORS = Set.of(
            "org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor",
            "org.springframework.cache.interceptor.BeanFactoryCacheOperationSourceAdvisor");

    private AddedProxyTarget() { }

    static Object resolve(Object bean, Class<?> type) throws ReflectiveOperationException {
        if (bean == null || bean.getClass() == type) return bean;
        ClassLoader spring = bean.getClass().getClassLoader();
        Class<?> advised;
        try { advised = Class.forName("org.springframework.aop.framework.Advised", false, spring); }
        catch (ClassNotFoundException absent) { advised = null; }
        if (advised == null || !advised.isInstance(bean))
            throw new IllegalStateException("added methods need the plain singleton or a standard tx/cache proxy, not " + bean.getClass().getName());
        if ((boolean) advised.getMethod("isFrozen").invoke(bean))
            throw new IllegalStateException("a frozen proxy cannot take an added method");
        Object source = advised.getMethod("getTargetSource").invoke(bean);
        if (!source.getClass().getName().equals("org.springframework.aop.target.SingletonTargetSource"))
            throw new IllegalStateException("a dynamic or custom target source is not supported");
        for (Object advisor : (Object[]) advised.getMethod("getAdvisors").invoke(bean))
            if (!SUPPORTED_ADVISORS.contains(advisor.getClass().getName()))
                throw new IllegalStateException("an added method is not supported beside advisor " + advisor.getClass().getName());
        Object target = source.getClass().getMethod("getTarget").invoke(source);
        if (target == null || target.getClass() != type)
            throw new IllegalStateException("a nested proxy or subclass target is not supported");
        return target;
    }
}
