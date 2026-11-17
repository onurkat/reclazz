/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import com.onurkat.reclazz.platform.PlatformTenant;

import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Drops the caches that hold static text, so an edited label is read again.
 *
 * Two separate stores, one per kind of file:
 *
 * {@code <ext>-locales_<iso>.properties} carries type and enum names. The
 * platform reads those files from disk into a single map on
 * {@code JaloBasedTypeLocalization} and answers every
 * {@code Localization.getLocalizedString} from it, so the file is live data
 * behind a cache rather than something baked in at startup. Clearing the map
 * makes the next lookup re-read the files. Measured on a running server: an
 * edited value stayed at the old text across requests, and appeared on the
 * first request after the cache was cleared, with no database write and no
 * system update.
 *
 * Backoffice labels are ZK's, and ZK keeps them behind
 * {@code org.zkoss.util.resource.Labels}, whose locators read from the
 * extension's resources folder. {@code Labels.reset()} is the flush.
 *
 * The two are reached differently on purpose. The platform class comes from
 * the application context's loader, the same route the rest of the Hybris
 * integration takes. ZK is not on that path at all: it lives inside the
 * backoffice web application and is loaded by its own loader, so it is found
 * among the loaded classes instead, which is the one place that sees every
 * loader in the process.
 */
public class HybrisLocalizationReloader {

    private static final String TYPE_LOCALIZATION =
            "de.hybris.platform.util.localization.TypeLocalization";
    private static final String ZK_LABELS = "org.zkoss.util.resource.Labels";

    private final ClassLoader platformClassLoader;
    private final Instrumentation instrumentation;

    private final Class<?> typeLocalizationForTests;
    private final java.util.List<Class<?>> zkLabelsForTests;

    public HybrisLocalizationReloader(ClassLoader platformClassLoader,
                                      Instrumentation instrumentation) {
        this.platformClassLoader = platformClassLoader;
        this.instrumentation = instrumentation;
        this.typeLocalizationForTests = null;
        this.zkLabelsForTests = null;
    }

    /**
     * Test seam. Neither class can be loaded outside a running server, and
     * handing them in is less machinery than pretending to be a classloader
     * that answers to their names.
     */
    HybrisLocalizationReloader(Class<?> typeLocalization, Class<?>... zkLabels) {
        this.platformClassLoader = null;
        this.instrumentation = null;
        this.typeLocalizationForTests = typeLocalization;
        this.zkLabelsForTests = java.util.List.of(zkLabels);
    }

    /**
     * Re-reads type and enum names from the locales files.
     *
     * @return true when the cache was cleared; false when the platform is not
     *         there, which is the answer for any application that is not
     *         SAP Commerce
     */
    public boolean reloadTypeLocalizations() {
        Class<?> typeLocalization = findTypeLocalization();
        if (typeLocalization == null) return false;

        // getInstance() goes through the platform's singleton machinery, which
        // reads the tenant from a ThreadLocal the watcher thread does not have.
        if (typeLocalizationForTests == null
                && !PlatformTenant.ensureActive(platformClassLoader)) {
            return false;
        }

        try {
            Method getInstance = typeLocalization.getMethod("getInstance");
            Object instance = getInstance.invoke(null);
            if (instance == null) return false;
            instance.getClass().getMethod("clearLocalizationCache").invoke(instance);
            return true;
        } catch (Throwable t) {
            StatusReporter.warn("Could not refresh type localizations: " + describe(t));
            return false;
        }
    }

    /**
     * Writes the names in the locales files onto the types, which is the
     * "Localize types" step of Update Running System on its own. Clearing the
     * cache reaches {@code Localization.getLocalizedString}; a type or
     * attribute name read from its descriptor, which is what Backoffice shows,
     * is a stored value and stayed at the old text (measured: the file lookup
     * answered the new label, {@code getName(Locale)} answered null).
     *
     * @return true when the platform ran it
     */
    public boolean localizeStoredTypeNames() {
        Class<?> typeLocalization = findTypeLocalization();
        if (typeLocalization == null) return false;
        if (typeLocalizationForTests == null
                && !PlatformTenant.ensureActive(platformClassLoader)) {
            return false;
        }
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        if (platformClassLoader != null) thread.setContextClassLoader(platformClassLoader);
        try {
            Object instance = typeLocalization.getMethod("getInstance").invoke(null);
            if (instance == null) return false;
            instance.getClass().getMethod("localizeTypes").invoke(instance);
            return true;
        } catch (Throwable t) {
            StatusReporter.warn("Could not write the names onto the types: " + describe(t)
                    + "; HAC Update Running System with \"Localize types\" does the same.");
            return false;
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * Writes onto the types only the names and descriptions keyed in one
     * locales file, the way "Localize types" writes each of them: per
     * language, the platform's merged value when it is not blank, added to the
     * names already stored. {@code localizeTypes()} walks every type, every
     * attribute of every type and every enumeration value (75s on a 2211
     * install with the B2B accelerator); the keys in the file a developer
     * saved are a few dozen.
     *
     * <p>Keys follow the platform's own, lower-cased: {@code type.<code>.name}
     * and {@code .description} for a type, {@code type.<code>.<qualifier>.name}
     * and {@code .description} for an attribute of that type, and
     * {@code type.<enum>.<value>.name} for an enumeration value. A key the
     * type system does not know is left alone, as the full step leaves it.
     *
     * @return how many names or descriptions changed, or -1 when this could
     *         not run and the full step is the way to write them
     */
    public int localizeStoredTypeNames(java.nio.file.Path file) {
        if (platformClassLoader == null || !PlatformTenant.ensureActive(platformClassLoader)) return -1;
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(platformClassLoader);
        Object transaction = null;
        try {
            java.util.Locale persistence = (java.util.Locale) Class.forName("de.hybris.bootstrap.util.LocaleHelper",
                    false, platformClassLoader).getMethod("getPersistenceLocale").invoke(null);
            java.util.Properties saved = new java.util.Properties();
            try (var reader = java.nio.file.Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
                saved.load(reader);
            }
            Object localization = findTypeLocalization().getMethod("getInstance").invoke(null);
            // Cleared by reloadTypeLocalizations, so this is the files as they are now, merged across extensions.
            Map<?, ?> merged = (Map<?, ?>) localization.getClass().getMethod("getLocalizations").invoke(localization);
            Object types = Class.forName("de.hybris.platform.jalo.type.TypeManager", false, platformClassLoader)
                    .getMethod("getInstance").invoke(null);
            Class<?> enumeration = Class.forName("de.hybris.platform.jalo.enumeration.EnumerationType", false, platformClassLoader);
            Class<?> composed = Class.forName("de.hybris.platform.jalo.type.ComposedType", false, platformClassLoader);
            Class<?> tx = Class.forName("de.hybris.platform.tx.Transaction", false, platformClassLoader);
            transaction = tx.getMethod("current").invoke(null);
            tx.getMethod("begin").invoke(transaction);
            int written = 0;
            for (String raw : saved.stringPropertyNames()) {
                String key = raw.toLowerCase(persistence);
                String[] parts = key.split("\\.", -1);
                if (parts.length < 3 || parts.length > 4 || !parts[0].equals("type")) continue;
                String kind = parts[parts.length - 1];
                if (!kind.equals("name") && !kind.equals("description")) continue;
                Map<Object, String> values = new java.util.HashMap<>();
                for (var entry : merged.entrySet()) {
                    String value = ((java.util.Properties) entry.getValue()).getProperty(key);
                    if (value != null && !value.isBlank()) values.put(entry.getKey(), value);
                }
                if (values.isEmpty()) continue;
                Object type = lookup(types, "getType", parts[1]);
                if (type == null) continue;
                Object target = type;
                boolean enumValue = false;
                if (parts.length == 4) {
                    if (enumeration.isInstance(type) && kind.equals("name")) {
                        target = enumerationValue(type, parts[2], persistence);
                        enumValue = true;
                    } else if (composed.isInstance(type)) {
                        target = lookup(type, "getAttributeDescriptorIncludingPrivate", parts[2]);
                    } else {
                        target = null;
                    }
                    if (target == null) continue;
                }
                if (write(target, kind.equals("name") ? "AllNames" : "AllDescriptions", values, enumValue)) written++;
            }
            tx.getMethod("commit").invoke(transaction);
            transaction = null;
            return written;
        } catch (Throwable t) {
            StatusReporter.warn("Could not write the names in " + file.getFileName() + " onto the types: " + describe(t)
                    + "; running the full \"Localize types\" step instead.");
            return -1;
        } finally {
            if (transaction != null) {
                try { transaction.getClass().getMethod("rollback").invoke(transaction); } catch (Throwable ignored) { }
            }
            thread.setContextClassLoader(previous);
        }
    }

    /** Added to the stored map, never replacing it: a language the files do not name keeps its value. */
    @SuppressWarnings("unchecked")
    private boolean write(Object target, String property, Map<Object, String> values, boolean session)
            throws ReflectiveOperationException {
        Object context = null;
        if (session) {
            Object jalo = Class.forName("de.hybris.platform.jalo.JaloSession", false, platformClassLoader)
                    .getMethod("getCurrentSession").invoke(null);
            context = jalo.getClass().getMethod("getSessionContext").invoke(jalo);
        }
        Class<?> contextType = session ? Class.forName("de.hybris.platform.jalo.SessionContext", false, platformClassLoader) : null;
        Map<Object, Object> stored = (Map<Object, Object>) (session
                ? target.getClass().getMethod("get" + property, contextType).invoke(target, context)
                : target.getClass().getMethod("get" + property).invoke(target));
        Map<Object, Object> next = new java.util.HashMap<>(stored == null ? Map.of() : stored);
        next.putAll(values);
        if (next.equals(stored)) return false;
        if (session) target.getClass().getMethod("set" + property, contextType, Map.class).invoke(target, context, next);
        else target.getClass().getMethod("set" + property, Map.class).invoke(target, next);
        return true;
    }

    private Object enumerationValue(Object type, String code, java.util.Locale persistence) throws ReflectiveOperationException {
        for (Object value : (java.util.List<?>) type.getClass().getMethod("getValues").invoke(type)) {
            String valueCode = (String) value.getClass().getMethod("getCode").invoke(value);
            if (valueCode != null && valueCode.toLowerCase(persistence).equals(code)) return value;
        }
        return null;
    }

    /** The platform answers an unknown code with JaloItemNotFoundException; that is "not here". */
    private Object lookup(Object owner, String method, String code) throws ReflectiveOperationException {
        try {
            return owner.getClass().getMethod(method, String.class).invoke(owner, code);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() != null
                    && failure.getCause().getClass().getName().equals("de.hybris.platform.jalo.JaloItemNotFoundException"))
                return null;
            throw failure;
        }
    }

    /**
     * Drops ZK's label cache, which is what backoffice reads its labels from.
     *
     * @return true when the cache was cleared; false when backoffice is not
     *         part of this server, or has not been opened yet and so has not
     *         loaded ZK
     */
    /** What a Backoffice label save achieved, as the running Backoffice now answers. */
    public enum LabelReload {
        /** Backoffice has not loaded ZK here, or no copy of its label cache could be cleared. */
        NOT_LOADED,
        /** A key from the saved file now resolves to the file's value. */
        VERIFIED,
        /** Cleared and reloaded, but the file offered no key that could be looked up to check. */
        UNCHECKED,
        /** Cleared, yet the key still resolves to something other than the saved value. */
        STALE
    }

    /** The outcome of a label save, with the key and values the check compared. */
    public record LabelOutcome(LabelReload result, String key, String expected, String actual) { }

    /**
     * Clears ZK's label cache and reloads it at once, through Backoffice's own
     * class loader, for every locale it had loaded, then checks one key of the
     * saved file.
     *
     * <p>Clearing alone was not enough. Measured live on SAP Commerce 2211: after
     * the reset the next Backoffice request reloaded the labels on a thread whose
     * context class loader still served the file's old bytes, so the navigation
     * tree kept the old text across re-login and a configuration reset while the
     * agent reported the labels re-read. Reloading straight after the reset with
     * the web application's loader made the new text appear on every try.
     */
    public LabelOutcome reloadBackofficeLabels(java.nio.file.Path saved) {
        java.util.List<Class<?>> labels = findZkLabels();
        java.util.Map.Entry<String, String> probe = firstLabel(saved);
        java.util.Locale fileLocale = localeOf(saved);
        String key = probe == null ? "reclazz.labels.reload" : probe.getKey();
        int reset = 0;
        String actual = null;
        boolean checked = false, matched = false;
        for (Class<?> labelsClass : labels) {
            java.util.Set<java.util.Locale> locales = loadedLocales(labelsClass);
            try {
                labelsClass.getMethod("reset").invoke(null);
                reset++;
            } catch (Throwable t) {
                StatusReporter.warn("Could not refresh backoffice labels: " + describe(t));
                continue;
            }
            locales.add(fileLocale);
            for (java.util.Locale locale : locales) {
                String value = lookup(labelsClass, locale, key);
                if (probe != null && locale.equals(fileLocale) && value != null) {
                    checked = true;
                    actual = value;
                    if (probe.getValue().equals(value)) matched = true;
                }
            }
        }
        if (reset == 0) return new LabelOutcome(LabelReload.NOT_LOADED, key, null, null);
        if (matched) return new LabelOutcome(LabelReload.VERIFIED, key, probe.getValue(), probe.getValue());
        if (checked) return new LabelOutcome(LabelReload.STALE, key, probe.getValue(), actual);
        return new LabelOutcome(LabelReload.UNCHECKED, key, probe == null ? null : probe.getValue(), null);
    }

    /** The first key and value of a saved labels file, or null when there is none to check. */
    private static java.util.Map.Entry<String, String> firstLabel(java.nio.file.Path saved) {
        if (saved == null) return null;
        try (var in = java.nio.file.Files.newBufferedReader(saved, java.nio.charset.StandardCharsets.UTF_8)) {
            java.util.Properties props = new java.util.Properties();
            props.load(in);
            return props.stringPropertyNames().stream().sorted().findFirst()
                    .map(k -> java.util.Map.entry(k, props.getProperty(k))).orElse(null);
        } catch (java.io.IOException | IllegalArgumentException unreadable) {
            return null;
        }
    }

    /** labels_en.properties is English, labels_pt_BR.properties Brazilian Portuguese, labels.properties English. */
    static java.util.Locale localeOf(java.nio.file.Path saved) {
        if (saved == null) return java.util.Locale.ENGLISH;
        String name = saved.getFileName().toString().replaceFirst("\\.properties$", "");
        int underscore = name.indexOf('_');
        if (underscore < 0) return java.util.Locale.ENGLISH;
        String[] parts = name.substring(underscore + 1).split("_");
        return parts.length > 1 ? new java.util.Locale(parts[0], parts[1]) : new java.util.Locale(parts[0]);
    }

    /** The locales ZK had loaded before the reset, so each is warm again afterwards. Best effort. */
    private static java.util.Set<java.util.Locale> loadedLocales(Class<?> labelsClass) {
        java.util.Set<java.util.Locale> locales = new java.util.LinkedHashSet<>();
        try {
            java.lang.reflect.Field loaderField = labelsClass.getDeclaredField("_loader");
            loaderField.setAccessible(true);
            Object loader = loaderField.get(null);
            java.lang.reflect.Field labelsField = loader.getClass().getDeclaredField("_labels");
            labelsField.setAccessible(true);
            Object map = labelsField.get(loader);
            if (map instanceof java.util.Map<?, ?> m) {
                for (Object k : m.keySet()) if (k instanceof java.util.Locale l) locales.add(l);
            }
        } catch (Throwable unknownShape) {
            // Not ZK's LabelLoaderImpl: the file's own locale is still reloaded.
        }
        return locales;
    }

    /**
     * Resolves one key for one locale with Backoffice's class loader as the
     * context loader, which also loads that locale's labels again. Null when this
     * is not ZK (ZK's Locales is absent) or the lookup fails.
     */
    private static String lookup(Class<?> labelsClass, java.util.Locale locale, String key) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        ClassLoader zk = labelsClass.getClassLoader();
        thread.setContextClassLoader(zk);
        try {
            Class<?> locales = Class.forName("org.zkoss.util.Locales", true, zk);
            java.lang.reflect.Method set = locales.getMethod("setThreadLocal", java.util.Locale.class);
            Object before = set.invoke(null, locale);
            try {
                Object value = labelsClass.getMethod("getLabel", String.class).invoke(null, key);
                return value == null ? null : value.toString();
            } finally {
                set.invoke(null, before);
            }
        } catch (Throwable notZk) {
            return null;
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** Clears every copy of ZK's label cache; true when at least one was cleared. */
    public boolean reloadBackofficeLabels() {
        return reloadBackofficeLabels(null).result() != LabelReload.NOT_LOADED;
    }

    private Class<?> findTypeLocalization() {
        if (typeLocalizationForTests != null) return typeLocalizationForTests;
        if (platformClassLoader == null) return null;
        try {
            return Class.forName(TYPE_LOCALIZATION, false, platformClassLoader);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * ZK is loaded by the web application's own classloader, which nothing
     * here has a reference to, and a server can hold more than one copy for
     * that reason. The loaded-class list is the way in, and it is also the
     * honest test of whether backoffice is running at all: a server without
     * it never loads the class, and gets a quiet false.
     */
    private java.util.List<Class<?>> findZkLabels() {
        if (zkLabelsForTests != null) return zkLabelsForTests;
        if (instrumentation == null) return java.util.List.of();

        java.util.List<Class<?>> found = new java.util.ArrayList<>(2);
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (ZK_LABELS.equals(loaded.getName())) found.add(loaded);
        }
        return found;
    }

    /** Reflection wraps the real failure and carries no message of its own. */
    private static String describe(Throwable t) {
        Throwable cause = (t.getCause() != null) ? t.getCause() : t;
        return (cause.getMessage() != null)
                ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                : cause.getClass().getName();
    }
}
