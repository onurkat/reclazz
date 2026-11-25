/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.TemplateEngineRegistry;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;
import com.onurkat.reclazz.transform.TemplateInterceptTransformer;
import com.onurkat.reclazz.ui.Failures;

/**
 * Drops the parsed-template caches so an edited template is read again.
 *
 * There is no class to redefine here. A template is data: the engine parsed it
 * once, kept the result, and will keep serving that result until something
 * tells it not to. Every engine has a method for exactly this, and the work is
 * finding the engine rather than clearing it, which
 * {@link TemplateInterceptTransformer} handles.
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
        return reload(fileName, null);
    }

    public int reload(String fileName, String resource) {
        Object[] engines = TemplateEngineRegistry.snapshot();
        if (engines.length == 0) return 0;

        int cleared = 0;
        for (Object engine : engines) {
            if (clear(engine, resource)) cleared++;
        }

        if (cleared > 0) {
            StatusReporter.info("Template cache cleared for " + fileName
                    + " (" + cleared + " engine" + (cleared == 1 ? "" : "s") + ")");
        }
        return cleared;
    }

    private boolean clear(Object engine, String resource) {
        for (String[] target : CLEAR_CALLS) {
            if (!isInstanceOf(engine, target[0])) continue;
            try {
                if (resource != null && target[0].equals("org.thymeleaf.TemplateEngine") && clearNamed(engine, resource))
                    return true;
                Method m = engine.getClass().getMethod(target[1]);
                m.invoke(engine);
                return true;
            } catch (NoSuchMethodException e) {
                // A version that names it differently; try the next candidate
                // rather than reporting a failure the user cannot act on.
            } catch (Throwable t) {
                StatusReporter.warn("Could not clear template cache on "
                        + engine.getClass().getName() + ": " + Failures.describe(t));
                return false;
            }
        }
        return false;
    }

    private boolean clearNamed(Object engine, String resource) {
        try {
            var resolvers = (java.util.Set<?>) engine.getClass().getMethod("getTemplateResolvers").invoke(engine);
            if (resolvers.size() != 1) return false;
            Object resolver = resolvers.iterator().next();
            Class<?> type = resolver.getClass();
            if (!type.getName().equals("org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver")
                    && !type.getName().equals("org.thymeleaf.spring5.templateresolver.SpringResourceTemplateResolver")) return false;
            if (!((java.util.Map<?, ?>) type.getMethod("getTemplateAliases").invoke(resolver)).isEmpty()) return false;
            String prefix = (String) type.getMethod("getPrefix").invoke(resolver);
            String suffix = (String) type.getMethod("getSuffix").invoke(resolver);
            if (prefix == null || !prefix.startsWith("classpath:/") || suffix == null || suffix.isEmpty()) return false;
            String dir = prefix.substring("classpath:/".length());
            if (!resource.startsWith(dir) || !resource.endsWith(suffix) || dir.length() + suffix.length() >= resource.length()) return false;
            Object manager = engine.getClass().getMethod("getCacheManager").invoke(engine);
            if (manager == null) return false;
            Object cache = manager.getClass().getMethod("getTemplateCache").invoke(manager);
            if (cache == null) return false;
            for (Object key : (java.util.Set<?>) cache.getClass().getMethod("keySet").invoke(cache)) {
                for (String getter : new String[]{"getTemplate", "getOwnerTemplate"}) {
                    String cached = (String) key.getClass().getMethod(getter).invoke(key);
                    if (cached != null && (cached.startsWith("/") || cached.startsWith(".") || cached.contains("/.")
                            || cached.contains("//") || cached.contains(":") || cached.contains("\\"))) return false;
                }
            }
            String file = resource.substring(dir.length());
            String name = file.substring(0, file.length() - suffix.length());
            Method clear = engine.getClass().getMethod("clearTemplateCacheFor", String.class);
            clear.invoke(engine, name);
            clear.invoke(engine, file);
            return true;
        } catch (ReflectiveOperationException | ClassCastException unavailable) {
            return false;
        }
    }

    /** Name-based, because the agent has neither engine on its classpath. */
    private boolean isInstanceOf(Object o, String className) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            if (className.equals(c.getName())) return true;
        }
        return false;
    }
}
