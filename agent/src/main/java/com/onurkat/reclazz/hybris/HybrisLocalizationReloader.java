/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;

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
    private final Class<?> zkLabelsForTests;

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
    HybrisLocalizationReloader(Class<?> typeLocalization, Class<?> zkLabels) {
        this.platformClassLoader = null;
        this.instrumentation = null;
        this.typeLocalizationForTests = typeLocalization;
        this.zkLabelsForTests = zkLabels;
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
     * Drops ZK's label cache, which is what backoffice reads its labels from.
     *
     * @return true when the cache was cleared; false when backoffice is not
     *         part of this server, or has not been opened yet and so has not
     *         loaded ZK
     */
    public boolean reloadBackofficeLabels() {
        Class<?> labels = findZkLabels();
        if (labels == null) return false;

        try {
            labels.getMethod("reset").invoke(null);
            return true;
        } catch (Throwable t) {
            StatusReporter.warn("Could not refresh backoffice labels: " + describe(t));
            return false;
        }
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
     * ZK is loaded by the backoffice web application's own classloader, which
     * nothing here has a reference to. The loaded-class list is the way in,
     * and it is also the honest test of whether backoffice is running at all:
     * a server without it never loads the class, and gets a quiet false.
     */
    private Class<?> findZkLabels() {
        if (zkLabelsForTests != null) return zkLabelsForTests;
        if (instrumentation == null) return null;
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (ZK_LABELS.equals(loaded.getName())) return loaded;
        }
        return null;
    }

    /** Reflection wraps the real failure and carries no message of its own. */
    private static String describe(Throwable t) {
        Throwable cause = (t.getCause() != null) ? t.getCause() : t;
        return (cause.getMessage() != null)
                ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                : cause.getClass().getName();
    }
}
