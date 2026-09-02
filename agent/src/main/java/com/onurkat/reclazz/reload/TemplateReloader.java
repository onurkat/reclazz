/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.TemplateEngineRegistry;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;

/**
 * Drops the parsed-template caches so an edited template is read again.
 *
 * There is no class to redefine here. A template is data: the engine parsed it
 * once, kept the result, and will keep serving that result until something
 * tells it not to. Every engine has a method for exactly this, and the work is
 * finding the engine rather than clearing it, which
 * {@link com.onurkat.reclazz.transform.TemplateInterceptTransformer} handles.
 *
 * Reflection rather than a compile-time dependency, because the agent must run
 * in applications that have neither engine on the classpath.
 */
public class TemplateReloader {

    /**
     * The clear method for each engine, tried in order. Thymeleaf routes
     * through a cache manager that may legitimately be null when caching is
     * off; Freemarker exposes the call directly.
     */
    private static final String[][] CLEAR_CALLS = {
            {"org.thymeleaf.TemplateEngine", "clearTemplateCache"},
            {"freemarker.template.Configuration", "clearTemplateCache"},
    };

    /**
     * @return how many engines were cleared, so the caller can say nothing
     *         rather than claim a reload that did not happen
     */
    public int reload(String fileName) {
        Object[] engines = TemplateEngineRegistry.snapshot();
        if (engines.length == 0) return 0;

        int cleared = 0;
        for (Object engine : engines) {
            if (clear(engine)) cleared++;
        }

        if (cleared > 0) {
            StatusReporter.info("Template cache cleared for " + fileName
                    + " (" + cleared + " engine" + (cleared == 1 ? "" : "s") + ")");
        }
        return cleared;
    }

    private boolean clear(Object engine) {
        for (String[] target : CLEAR_CALLS) {
            if (!isInstanceOf(engine, target[0])) continue;
            try {
                Method m = engine.getClass().getMethod(target[1]);
                m.invoke(engine);
                return true;
            } catch (NoSuchMethodException e) {
                // A version that names it differently; try the next candidate
                // rather than reporting a failure the user cannot act on.
            } catch (Throwable t) {
                StatusReporter.warn("Could not clear template cache on "
                        + engine.getClass().getName() + ": " + com.onurkat.reclazz.ui.Failures.describe(t));
                return false;
            }
        }
        return false;
    }

    /** Name-based, because the agent has neither engine on its classpath. */
    private boolean isInstanceOf(Object o, String className) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            if (className.equals(c.getName())) return true;
        }
        return false;
    }
}
