/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;

/**
 * Drops the cached messages so a saved bundle is read again.
 *
 * <p>Spring reads a message bundle once and answers from a cache afterwards,
 * which is the right thing for a server and the wrong thing for someone
 * editing the text. Outside SAP Commerce this used to end in one line saying
 * a change "may require a restart to take effect", which was a guess printed
 * as a fact.
 *
 * <p>There are two message sources and they are not the same problem.
 * {@code ReloadableResourceBundleMessageSource} ships the reset:
 * {@code clearCacheIncludingAncestors}, preferred over plain
 * {@code clearCache} because a Boot application stacks at least two sources
 * and clearing only the front one leaves what was resolved through it.
 *
 * <p>{@code ResourceBundleMessageSource}, which is what Boot autoconfigures
 * and therefore the one most applications actually have, ships no reset at
 * all: this was written expecting one and measured against a live Boot 3.3
 * server, which answered that no such method exists on it or anywhere up its
 * hierarchy. Its caches are two private maps, and underneath them sits the
 * JDK's own {@code ResourceBundle} cache, which is what actually re-reads the
 * file. So both are cleared: the maps by name, and the JDK cache through
 * {@link java.util.ResourceBundle#clearCache(ClassLoader)}, which is a public
 * API meant for exactly this. Clearing only the maps leaves the JDK handing
 * the same bundle back, which is the half-measure that looks like it worked.
 *
 * <p>All reflective, no Spring dependency. A message source that is neither
 * shape gets the sentence this used to give everybody, which is now about
 * that one bean rather than about the whole idea.
 */
public class SpringMessageSourceReloader {

    private static final String MESSAGE_SOURCE = "org.springframework.context.MessageSource";

    /** Most thorough first: the second is what the first falls back to. */
    private static final String[] RESETS = {"clearCacheIncludingAncestors", "clearCache"};

    private final PlatformContext platformContext;

    public SpringMessageSourceReloader(PlatformContext platformContext) {
        this.platformContext = platformContext;
    }

    /**
     * @return how many message sources were reset; zero means this application
     *         has none this can reach, not that nothing was cached
     */
    public int reload() {
        int reset = 0;
        for (Object appContext : platformContext.getAllApplicationContexts()) {
            String[] names = SpringBeans.beanNamesForType(appContext, MESSAGE_SOURCE);
            for (String name : names) {
                Object source = SpringBeans.getBean(appContext, name);
                if (source == null) continue;
                if (clear(source)) reset++;
            }
        }
        return reset;
    }

    /** True when this source will not answer from its cache any more. */
    static boolean clear(Object messageSource) {
        for (String reset : RESETS) {
            Method method = com.onurkat.reclazz.util.Reflect.findMethod(messageSource.getClass(), reset);
            if (method == null) continue;
            try {
                method.invoke(messageSource);
                return true;
            } catch (Throwable oneSource) {
                // A source that will not reset keeps what it cached, and the
                // count reports only what really happened.
                return false;
            }
        }
        return clearBundleCaches(messageSource);
    }

    /**
     * The source with no reset of its own: empty its caches, then the JDK's.
     *
     * <p>The maps are memoisation and nothing else, so emptying them costs one
     * re-read. The {@code ResourceBundle} cache underneath is the one that
     * decides whether that re-read reaches the file at all.
     */
    private static boolean clearBundleCaches(Object messageSource) {
        boolean touched = false;
        for (Class<?> c = messageSource.getClass(); c != null && c != Object.class;
                c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (!field.getName().startsWith("cached")) continue;
                if (!java.util.Map.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    java.util.Map<?, ?> cache = (java.util.Map<?, ?>) field.get(messageSource);
                    if (cache != null) {
                        cache.clear();
                        touched = true;
                    }
                } catch (Throwable oneField) {
                    // A map that cannot be emptied keeps what it holds.
                }
            }
        }
        if (!touched) return false;

        try {
            java.util.ResourceBundle.clearCache(bundleLoader(messageSource));
        } catch (Throwable jdkCache) {
            // Without this the maps refill from the same bundle object, so
            // say nothing was reset rather than claim a re-read that is not
            // going to happen.
            return false;
        }
        return true;
    }

    /** The loader the source reads its bundles with, or its own. */
    private static ClassLoader bundleLoader(Object messageSource) {
        Method getter = com.onurkat.reclazz.util.Reflect.findMethod(messageSource.getClass(), "getBundleClassLoader");
        if (getter != null) {
            try {
                Object loader = getter.invoke(messageSource);
                if (loader instanceof ClassLoader found) return found;
            } catch (Throwable notThere) {
                // fall through to the source's own loader
            }
        }
        return messageSource.getClass().getClassLoader();
    }

    /** The line to print, given what {@link #reload()} reached. */
    public static void report(String fileName, int reset) {
        if (reset > 0) {
            StatusReporter.success(fileName + " re-read: " + com.onurkat.reclazz.ui.Plural.of(reset, "message source")
                    + com.onurkat.reclazz.ui.Plural.word(reset, " dropped its cache", " dropped their cache")
                    + ", so the next lookup reads the file.");
            return;
        }
        StatusReporter.warn(fileName + " changed, but no message source here exposes a "
                + "cache reset Reclazz knows, so the text a lookup already resolved stays "
                + "what it was. A restart applies it.");
        com.onurkat.reclazz.agent.RestartLedger.note(fileName,
                "message text a message source did not re-read");
    }
}
