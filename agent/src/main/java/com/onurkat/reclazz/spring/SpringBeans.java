/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import java.lang.reflect.Method;

/**
 * Reflective Spring access shared by the reloaders.
 *
 * Every reloader talks to Spring purely through reflection (the agent has
 * no compile-time Spring dependency), and each had grown its own private
 * copy of the same four helpers — six copies of {@code findBeanName},
 * six of {@code getBeanNamesForType}, four of {@code findMethod}. They
 * were byte-identical, so a fix in one never reached the others.
 *
 * All methods degrade to null/empty rather than throwing: a missing
 * Spring module simply means that reloader has nothing to do.
 */
final class SpringBeans {

    private SpringBeans() {}

    /** First bean name registered for the given type, or null. */
    static String findBeanName(Object appContext, Class<?> type) {
        String[] names = beanNamesForType(appContext, type);
        return names.length > 0 ? names[0] : null;
    }

    /** Bean names for a type named at runtime; empty when absent. */
    static String[] beanNamesForType(Object appContext, String typeName) {
        try {
            Class<?> type = Class.forName(typeName, false,
                    appContext.getClass().getClassLoader());
            return beanNamesForType(appContext, type);
        } catch (ClassNotFoundException e) {
            // Spring module not on the classpath — nothing to reload.
            return new String[0];
        }
    }

    /** Bean names for a type; empty when none or on any reflective failure. */
    static String[] beanNamesForType(Object appContext, Class<?> type) {
        try {
            Method method = appContext.getClass().getMethod("getBeanNamesForType", Class.class);
            String[] names = (String[]) method.invoke(appContext, type);
            return names != null ? names : new String[0];
        } catch (Exception e) {
            return new String[0];
        }
    }

    /** getBean(name), or null when it cannot be resolved. */
    static Object getBean(Object appContext, String beanName) {
        try {
            Method getBean = appContext.getClass().getMethod("getBean", String.class);
            return getBean.invoke(appContext, beanName);
        } catch (Exception e) {
            return null;
        }
    }

    /** The context's bean factory, or null. */
    static Object getBeanFactory(Object appContext) {
        try {
            Method getBeanFactory = appContext.getClass().getMethod("getBeanFactory");
            return getBeanFactory.invoke(appContext);
        } catch (Exception e) {
            return null;
        }
    }

}
