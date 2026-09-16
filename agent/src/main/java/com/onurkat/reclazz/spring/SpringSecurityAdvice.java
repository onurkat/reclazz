/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.util.Reflect;
import org.objectweb.asm.tree.AnnotationNode;
import java.lang.reflect.Method;
import java.util.*;

/** Classic native Spring method authorization; no security classes enter the agent linkage. */
final class SpringSecurityAdvice {
    private static final String ROOT = "org.springframework.security.access.";
    private static final String ADVISOR = ROOT + "intercept.aopalliance.MethodSecurityMetadataSourceAdvisor";
    private static final String SOURCE = ROOT + "method.DelegatingMethodSecurityMetadataSource";
    private static final Set<String> ANNOTATIONS = Set.of(
            "Lorg/springframework/security/access/prepost/PreAuthorize;",
            "Lorg/springframework/security/access/prepost/PostAuthorize;");

    private SpringSecurityAdvice() { }
    static boolean annotation(String descriptor) { return ANNOTATIONS.contains(descriptor); }
    static boolean isAdvisor(Object advisor) { return advisor.getClass().getName().equals(ADVISOR); }

    // Unsupported direct/composed security annotations still activate a refusal route.
    static boolean hasSecurity(List<AnnotationNode> annotations, ClassLoader loader) {
        if (annotations == null) return false;
        for (var annotation : annotations) {
            String name = org.objectweb.asm.Type.getType(annotation.desc).getClassName();
            if (securityName(name)) return true;
            if (name.startsWith("java.")) continue;
            try {
                if (securityType(Class.forName(name, false, loader), new HashSet<>())) return true;
            } catch (ClassNotFoundException | LinkageError unreadable) {
                // The general annotation allowlist independently refuses unknown metadata.
            }
        }
        return false;
    }
    private static boolean securityType(Class<?> type, Set<Class<?>> seen) {
        if (securityName(type.getName())) return true;
        if (!seen.add(type) || type.getName().startsWith("java.")) return false;
        for (var annotation : type.getDeclaredAnnotations()) if (securityType(annotation.annotationType(), seen)) return true;
        return false;
    }
    private static boolean securityName(String name) {
        return name.startsWith(ROOT) || name.startsWith("javax.annotation.security.") || name.startsWith("jakarta.annotation.security.");
    }

    static Object interceptor(Object advisor) throws ReflectiveOperationException {
        Object interceptor = call(advisor, "getAdvice");
        exact(interceptor, ROOT + "intercept.aopalliance.MethodSecurityInterceptor");
        Object source = call(interceptor, "getSecurityMetadataSource");
        exact(source, SOURCE);
        if (field(advisor, "attributeSource") != source) throw unsupported("advisor and interceptor metadata differ");
        List<?> sources = (List<?>) call(source, "getMethodSecurityMetadataSources");
        if (sources.size() != 1) throw unsupported("expected only pre/post annotation metadata");
        exact(sources.get(0), ROOT + "prepost.PrePostAnnotationSecurityMetadataSource");
        exact(field(sources.get(0), "attributeFactory"), ROOT + "expression.method.ExpressionBasedAnnotationAttributeFactory");
        Object decision = call(interceptor, "getAccessDecisionManager");
        exact(decision, ROOT + "vote.AffirmativeBased");
        List<?> voters = (List<?>) call(decision, "getDecisionVoters");
        if (voters.size() != 3) throw unsupported("custom security voters");
        exact(voters.get(0), ROOT + "prepost.PreInvocationAuthorizationAdviceVoter");
        exact(voters.get(1), ROOT + "vote.RoleVoter");
        exact(voters.get(2), ROOT + "vote.AuthenticatedVoter");
        Object pre = field(voters.get(0), "preAdvice");
        exact(pre, ROOT + "expression.method.ExpressionBasedPreInvocationAdvice");
        exact(field(pre, "expressionHandler"), ROOT + "expression.method.DefaultMethodSecurityExpressionHandler");
        Object after = call(interceptor, "getAfterInvocationManager");
        exact(after, ROOT + "intercept.AfterInvocationProviderManager");
        List<?> providers = (List<?>) call(after, "getProviders");
        if (providers.size() != 1) throw unsupported("custom post authorization providers");
        exact(providers.get(0), ROOT + "prepost.PostInvocationAdviceProvider");
        Object post = field(providers.get(0), "postAdvice");
        exact(post, ROOT + "expression.method.ExpressionBasedPostInvocationAdvice");
        exact(field(post, "expressionHandler"), ROOT + "expression.method.DefaultMethodSecurityExpressionHandler");
        exact(call(interceptor, "getRunAsManager"), ROOT + "intercept.NullRunAsManager");
        return interceptor;
    }

    static void forget(Object bean, List<Method> methods) throws ReflectiveOperationException {
        if (!bean.getClass().getName().equals(SOURCE) || methods.isEmpty()) return;
        Object cache = field(bean, "attributeCache");
        if (!(cache instanceof Map<?, ?> map)) throw unsupported("security attribute cache unavailable");
        // Spring synchronizes this HashMap itself. Retire only our previous hidden methods.
        synchronized (map) {
            var keys = map.keySet().iterator();
            while (keys.hasNext()) if (methods.contains(field(keys.next(), "method"))) keys.remove();
        }
    }
    private static void exact(Object value, String name) {
        if (value == null || !value.getClass().getName().equals(name)) throw unsupported("expected " + name);
    }
    private static Object field(Object value, String name) throws ReflectiveOperationException {
        var field = Reflect.findField(value.getClass(), name);
        if (field == null) throw unsupported("unavailable security metadata " + name);
        return field.get(value);
    }
    private static Object call(Object value, String name) throws ReflectiveOperationException {
        return value.getClass().getMethod(name).invoke(value);
    }
    private static IllegalStateException unsupported(String reason) {
        return new IllegalStateException("Added Spring operation refused: " + reason);
    }
}
