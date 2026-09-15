/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.util.Reflect;
import java.lang.reflect.Proxy;

/** Read-only eligibility for property recreation; Spring creates the replacement proxy. */
final class PropertyTransactionProxy {
    private PropertyTransactionProxy() { }

    static Object target(Object bean, ClassLoader spring) throws Exception {
        if (!isProxy(bean, spring)) return bean;
        Class<?> advised = Class.forName("org.springframework.aop.framework.Advised", false, spring);
        if (!advised.isInstance(bean)) throw unsupported("proxy advice cannot be inspected");
        if ((boolean) advised.getMethod("isFrozen").invoke(bean)) throw unsupported("frozen proxy");
        Object source = advised.getMethod("getTargetSource").invoke(bean);
        if (source == null || !source.getClass().getName().equals("org.springframework.aop.target.SingletonTargetSource"))
            throw unsupported("dynamic or custom target source");
        Object[] advisors = (Object[]) advised.getMethod("getAdvisors").invoke(bean);
        if (advisors.length != 1 || !advisors[0].getClass().getName().equals(
                "org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor"))
            throw unsupported("requires exactly one standard transaction advisor without additional advice");
        // getAdvice() may instantiate an advice bean. Checking a candidate must
        // only inspect the already configured interceptor, not create one.
        Object interceptor = Reflect.readField(advisors[0], "advice");
        if (interceptor == null || !interceptor.getClass().getName().equals(
                "org.springframework.transaction.interceptor.TransactionInterceptor"))
            throw unsupported("custom or unavailable transaction interceptor");
        Object attributes = PropertyChangeCheck.call(interceptor, "getTransactionAttributeSource");
        if (attributes == null || !attributes.getClass().getName().equals(
                "org.springframework.transaction.annotation.AnnotationTransactionAttributeSource"))
            throw unsupported("custom or unavailable transaction metadata");
        Object target = source.getClass().getMethod("getTarget").invoke(source);
        if (target == null || target == bean || isProxy(target, spring)) throw unsupported("nested or unavailable target");
        return target;
    }

    private static boolean isProxy(Object bean, ClassLoader spring) throws Exception {
        if (Proxy.isProxyClass(bean.getClass())) return true;
        try {
            return (boolean) Class.forName("org.springframework.aop.support.AopUtils", false, spring)
                    .getMethod("isAopProxy", Object.class).invoke(null, bean);
        } catch (ClassNotFoundException noAop) { return false; }
    }

    private static IllegalStateException unsupported(String reason) {
        return new IllegalStateException("requires an unproxied bean or a supported transaction proxy: " + reason);
    }
}
