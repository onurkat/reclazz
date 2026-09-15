/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.util.Reflect;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/** Uses the application's configured async interceptor; never creates an executor. */
final class SpringAsyncAdvice {
    private static final String PROCESSOR = "org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor";
    private static final String ADVISOR = "org.springframework.scheduling.annotation.AsyncAnnotationAdvisor";
    private static final String INTERCEPTOR = "org.springframework.scheduling.annotation.AnnotationAsyncExecutionInterceptor";

    private SpringAsyncAdvice() { }

    static boolean isAdvisor(Object advisor) { return advisor.getClass().getName().equals(ADVISOR); }

    static Object advisor(Object factory, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = Class.forName(PROCESSOR, false, loader);
        String[] names = (String[]) factory.getClass().getMethod("getBeanNamesForType", Class.class, boolean.class, boolean.class)
                .invoke(factory, type, false, false);
        if (names.length != 1) throw new IllegalStateException("expected one standard async postprocessor");
        Object processor = factory.getClass().getMethod("getSingleton", String.class).invoke(factory, names[0]);
        if (processor == null || processor.getClass() != type)
            throw new IllegalStateException("custom or uninitialized async postprocessor");
        if (!Boolean.TRUE.equals(Reflect.readField(processor, "beforeExistingAdvisors")))
            throw new IllegalStateException("async advice must precede existing advisors");
        var annotationField = Reflect.findField(type, "asyncAnnotationType");
        if (annotationField == null) throw new IllegalStateException("async annotation configuration unavailable");
        Object annotation = annotationField.get(processor);
        if (annotation != null && annotation != Class.forName("org.springframework.scheduling.annotation.Async", false, loader))
            throw new IllegalStateException("custom async annotation configuration");
        Object advisor = Reflect.readField(processor, "advisor");
        if (advisor == null || !isAdvisor(advisor)) throw new IllegalStateException("standard async advisor unavailable");
        return advisor;
    }

    static void validateProxy(Object bean, Object factory, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> advised = Class.forName("org.springframework.aop.framework.Advised", false, loader);
        Object[] advisors = (Object[]) advised.getMethod("getAdvisors").invoke(bean);
        for (int i = 0; i < advisors.length; i++) {
            if (isAdvisor(advisors[i]) && (i != 0 || advisors[i] != advisor(factory, loader)))
                throw new IllegalStateException("async proxy advisor must be the configured advisor, once and first");
        }
    }

    static Object interceptor(Object advisor, Method metadata, Class<?> owner, ClassLoader loader) throws ReflectiveOperationException {
        Object pointcut = advisor.getClass().getMethod("getPointcut").invoke(advisor);
        Class<?> pointcutType = Class.forName("org.springframework.aop.Pointcut", false, loader);
        Object filter = pointcutType.getMethod("getClassFilter").invoke(pointcut);
        Object matcher = pointcutType.getMethod("getMethodMatcher").invoke(pointcut);
        Class<?> matcherType = Class.forName("org.springframework.aop.MethodMatcher", false, loader);
        if (!(boolean) Class.forName("org.springframework.aop.ClassFilter", false, loader).getMethod("matches", Class.class).invoke(filter, owner)
                || (boolean) matcherType.getMethod("isRuntime").invoke(matcher)
                || !(boolean) matcherType.getMethod("matches", Method.class, Class.class).invoke(matcher, metadata, owner))
            throw new IllegalStateException("no matching standard async advisor");
        Object advice = advisor.getClass().getMethod("getAdvice").invoke(advisor);
        if (advice == null || !advice.getClass().getName().equals(INTERCEPTOR))
            throw new IllegalStateException("custom async interceptor");
        return advice;
    }

    // Spring keys executor resolution by Method. Discard only the superseded hidden
    // methods; unrelated application methods and submitted tasks remain untouched.
    static void forget(Object processor, List<Method> methods) throws ReflectiveOperationException {
        if (!processor.getClass().getName().equals(PROCESSOR) || methods.isEmpty()) return;
        Object advisor = Reflect.readField(processor, "advisor");
        if (advisor == null || !isAdvisor(advisor)) return;
        Object advice = advisor.getClass().getMethod("getAdvice").invoke(advisor);
        if (advice == null || !advice.getClass().getName().equals(INTERCEPTOR)) return;
        Object cached = Reflect.readField(advice, "executors");
        if (cached instanceof Map<?, ?> map) methods.forEach(map::remove);
    }
}
