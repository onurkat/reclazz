/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.util.Reflect;
import java.lang.reflect.Proxy;
import java.util.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;

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
        boolean transaction = false, classic = false;
        int modern = 0;
        if (advisors.length == 0) throw unsupported("proxy has no supported advisor");
        for (Object advisor : advisors) {
            if (advisor.getClass().getName().equals("org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor")) {
                if (transaction) throw unsupported("duplicate transaction advisor");
                transaction(advisor); transaction = true;
            } else if (SpringSecurityAdvice.isAdvisor(advisor)) {
                if (classic || modern != 0) throw unsupported("duplicate or mixed security advisors");
                if (Reflect.readField(advisor, "interceptor") == null)
                    throw unsupported("security interceptor is not initialized");
                SpringSecurityAdvice.interceptor(advisor); classic = true;
            } else if (SpringModernSecurityAdvice.isAdvisor(advisor)) {
                var policy = SpringModernSecurityAdvice.inspectInitialized(advisor);
                if (classic || policy.inactiveOnly() || (modern & policy.policies()) != 0)
                    throw unsupported("duplicate, mixed or unsupported security advisors");
                modern |= policy.policies();
            } else throw unsupported("additional cache, async or custom advice");
        }
        Object target = source.getClass().getMethod("getTarget").invoke(source);
        if (target == null || target == bean || isProxy(target, spring)) throw unsupported("nested or unavailable target");
        int required = securityMetadata(target.getClass(), new HashSet<>());
        if (!classic && (modern & required) != required)
            throw unsupported("missing required pre/post security advisor");
        return target;
    }

    private static void transaction(Object advisor) throws Exception {
        // getAdvice() may instantiate an advice bean. Checking a candidate must
        // only inspect the already configured interceptor, not create one.
        Object interceptor = Reflect.readField(advisor, "advice");
        if (interceptor == null || !interceptor.getClass().getName().equals(
                "org.springframework.transaction.interceptor.TransactionInterceptor"))
            throw unsupported("custom or unavailable transaction interceptor");
        Object attributes = PropertyChangeCheck.call(interceptor, "getTransactionAttributeSource");
        if (attributes == null || !attributes.getClass().getName().equals(
                "org.springframework.transaction.annotation.AnnotationTransactionAttributeSource"))
            throw unsupported("custom or unavailable transaction metadata");
    }

    private static int securityMetadata(Class<?> type, Set<Class<?>> seen) {
        if (type == null || type == Object.class || !seen.add(type)) return 0;
        int policies = securityMetadata(type.getDeclaredAnnotations(), type.getClassLoader());
        for (var method : type.getDeclaredMethods()) policies |= securityMetadata(method.getDeclaredAnnotations(), type.getClassLoader());
        for (Class<?> parent : type.getInterfaces()) policies |= securityMetadata(parent, seen);
        return policies | securityMetadata(type.getSuperclass(), seen);
    }
    private static int securityMetadata(java.lang.annotation.Annotation[] annotations, ClassLoader loader) {
        var supported = new ComposedSecurityAnnotations(loader);
        int policies = 0;
        for (var annotation : annotations) {
            String descriptor = Type.getDescriptor(annotation.annotationType());
            if (SpringSecurityAdvice.hasSecurity(List.of(new AnnotationNode(descriptor)), loader)
                    && !supported.supported(descriptor))
                throw unsupported("only direct or fixed composed pre/post authorization is supported");
            policies |= supported.policies(annotation.annotationType());
        }
        return policies;
    }

    private static boolean isProxy(Object bean, ClassLoader spring) throws Exception {
        if (Proxy.isProxyClass(bean.getClass())) return true;
        try {
            return (boolean) Class.forName("org.springframework.aop.support.AopUtils", false, spring)
                    .getMethod("isAopProxy", Object.class).invoke(null, bean);
        } catch (ClassNotFoundException noAop) { return false; }
    }

    private static IllegalStateException unsupported(String reason) {
        return new IllegalStateException("requires an unproxied bean or a supported transaction/security proxy: " + reason);
    }
}
