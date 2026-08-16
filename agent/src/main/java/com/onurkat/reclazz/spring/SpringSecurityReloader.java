/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;


/**
 * Triggers Spring Security filter chain rebuild after SecurityConfigurer changes.
 *
 * When a class with @EnableWebSecurity or implementing SecurityConfigurer is reloaded,
 * the security filter chain may need to be rebuilt.
 *
 * All Spring interaction is via reflection — graceful no-op if Spring Security is not present.
 */
public class SpringSecurityReloader {

    private final PlatformContext platformContext;

    public SpringSecurityReloader(PlatformContext platformContext) {
        this.platformContext = platformContext;
    }

    /**
     * Trigger security filter chain rebuild if the reloaded class is security-related.
     */
    public boolean reloadSecurityConfig(Class<?> reloadedClass) {
        if (!isSecurityConfigClass(reloadedClass)) return false;

        boolean handled = false;
        // Security beans may live in any context (typically a web context).
        for (Object appContext : platformContext.getAllApplicationContexts()) {
            handled |= reloadSecurityConfigIn(appContext, reloadedClass);
        }
        return handled;
    }

    private boolean reloadSecurityConfigIn(Object appContext, Class<?> reloadedClass) {
        try {
            // Find SecurityFilterChain beans and try to refresh them
            String[] filterChainNames = SpringBeans.beanNamesForType(appContext,
                    "org.springframework.security.web.SecurityFilterChain");

            if (filterChainNames != null && filterChainNames.length > 0) {
                StatusReporter.warn("Spring Security configuration changed in " + reloadedClass.getName());
                StatusReporter.warn("Security filter chain rebuild is limited. " +
                        "A full restart may be needed for security changes to take effect.");
                com.onurkat.reclazz.agent.RestartLedger.note(reloadedClass.getName(),
                        "a security configuration change the filter chain cannot rebuild");
                return true;
            }

            // Also try with WebSecurityConfigurerAdapter (legacy)
            String[] configurerNames = SpringBeans.beanNamesForType(appContext,
                    "org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter");
            if (configurerNames != null && configurerNames.length > 0) {
                StatusReporter.warn("Legacy WebSecurityConfigurerAdapter changed. " +
                        "Restart required for security changes.");
                return true;
            }

        } catch (Exception e) {
            StatusReporter.warn("Spring Security reload check failed: " + e.getMessage());
        }
        return false;
    }

    private boolean isSecurityConfigClass(Class<?> clazz) {
        try {
            // Check annotations
            for (var annotation : clazz.getAnnotations()) {
                String name = annotation.annotationType().getName();
                if (name.contains("EnableWebSecurity") ||
                        name.contains("EnableGlobalMethodSecurity") ||
                        name.contains("EnableMethodSecurity")) {
                    return true;
                }
            }
            // Check interfaces/superclass for SecurityConfigurer
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                for (Class<?> iface : current.getInterfaces()) {
                    if (iface.getName().contains("SecurityConfigurer") ||
                            iface.getName().contains("SecurityFilterChain")) {
                        return true;
                    }
                }
                if (current.getName().contains("WebSecurityConfigurerAdapter")) {
                    return true;
                }
                current = current.getSuperclass();
            }
        } catch (Exception ignored) {}
        return false;
    }
}
