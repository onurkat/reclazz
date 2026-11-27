/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.Plural;
import com.onurkat.reclazz.util.Reflect;

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

    /** Existing native sources whose actual lookup resolves to this file. */
    public Set<Object> ownersOf(Path file) {
        Set<Object> owners = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object context : platformContext.getAllApplicationContexts()) {
            for (Object source : SpringBeans.existingSingletons(context, MESSAGE_SOURCE)) {
                if (ownsBundle(source, file)) owners.add(source);
            }
        }
        return owners;
    }

    public static int reloadOwners(Set<Object> owners) {
        return (int) owners.stream().filter(SpringMessageSourceReloader::clear).count();
    }

    private static final Pattern LOCALE_SUFFIX = Pattern.compile(
            "_(?:[a-z]{2,8}|(?=_))(?:_[A-Z][a-z]{3})?(?:_(?:[A-Z]{2}|[0-9]{3})?)?(?:_[A-Za-z0-9]+)*");

    static boolean ownsBundle(Object source, Path file) {
        String type = source.getClass().getName();
        boolean reloadable = type.equals("org.springframework.context.support.ReloadableResourceBundleMessageSource");
        if (!reloadable && !type.equals("org.springframework.context.support.ResourceBundleMessageSource")) return false;
        String name = file.getFileName().toString();
        if (!name.endsWith(".properties")) return false;
        String stem = name.substring(0, name.length() - ".properties".length());
        try {
            Set<?> bases = (Set<?>) source.getClass().getMethod("getBasenameSet").invoke(source);
            for (Object entry : bases) {
                String base = (String) entry;
                if (base.contains(":") && !(reloadable && base.startsWith("classpath:"))) continue;
                String resource = reloadable ? base : base.replace('.', '/');
                String path = resource.startsWith("classpath:") ? resource.substring("classpath:".length()) : resource;
                String last = path.substring(path.lastIndexOf('/') + 1);
                if (last.isEmpty() || !stem.startsWith(last)) continue;
                String suffix = stem.substring(last.length());
                if (!suffix.isEmpty() && !LOCALE_SUFFIX.matcher(suffix).matches()) continue;
                if (resolvesTo(source, resource + suffix, reloadable, file)) return true;
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            // A source that cannot identify its own resource does not own the save.
        }
        return false;
    }

    private static boolean resolvesTo(Object source, String resource, boolean reloadable, Path file) {
        try {
            Path resolved;
            if (reloadable) {
                Method resolve = Reflect.findMethod(source.getClass(), "resolveResource", String.class);
                if (resolve == null) return false;
                Object selected = resolve.invoke(source, resource);
                if (selected == null) return false;
                resolved = ((File) selected.getClass().getMethod("getFile").invoke(selected)).toPath();
            } else {
                Method getter = Reflect.findMethod(source.getClass(), "getBundleClassLoader");
                if (getter == null || !(getter.invoke(source) instanceof ClassLoader loader)) return false;
                URL url = loader.getResource(resource + ".properties");
                if (url == null || !url.getProtocol().equals("file")) return false;
                resolved = Path.of(url.toURI());
            }
            return Files.isSameFile(resolved, file);
        } catch (Exception unavailable) {
            return false;
        }
    }

    /** True when this source will not answer from its cache any more. */
    static boolean clear(Object messageSource) {
        for (String reset : RESETS) {
            Method method = Reflect.findMethod(messageSource.getClass(), reset);
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
        Method getter = Reflect.findMethod(messageSource.getClass(), "getBundleClassLoader");
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
            StatusReporter.success(fileName + " re-read: " + Plural.of(reset, "message source")
                    + Plural.word(reset, " dropped its cache", " dropped their cache")
                    + ", so the next lookup reads the file.");
            return;
        }
        StatusReporter.warn(fileName + " changed, but no message source here exposes a "
                + "cache reset Reclazz knows, so the text a lookup already resolved stays "
                + "what it was. A restart applies it.");
        RestartLedger.note(fileName,
                "message text a message source did not re-read");
    }
}
