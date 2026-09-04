/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.util;

import java.util.LinkedHashSet;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * The cache that decides whether a re-read reaches the translation file.
 *
 * <p>{@code ResourceBundle.getBundle} memoises per classloader, per base name
 * and per locale, and never checks the file again. Every framework cache above
 * it can be emptied and the next lookup will still be answered from this one:
 * the bundle object it returns is the same object, holding the text as it was
 * when the application first asked.
 *
 * <p>It was being cleared, but only as the last step of refreshing a Spring
 * {@code MessageSource}, so it happened when there was a message source bean to
 * find and not otherwise. Bean Validation's {@code ValidationMessages}, SAP
 * Commerce's own bundles, and any application that calls
 * {@code ResourceBundle.getBundle} itself all sit under this cache and none of
 * them is a Spring bean.
 *
 * <p>{@code clearCache} is public API and takes a classloader, which is the
 * awkward part: the bundle was loaded by whichever loader found it, and inside
 * a SAP Commerce server that is not the one this agent was loaded by. So it is
 * cleared for the ones a bundle can plausibly have come through. Clearing a
 * cache that holds nothing costs nothing.
 */
public final class BundleCache {

    private BundleCache() {
    }

    /**
     * Empties the JDK's bundle cache for every classloader a translation could
     * have been loaded through.
     *
     * @return how many were cleared, which is what the caller reports on
     */
    public static int clear(ClassLoader... alsoThese) {
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        for (ClassLoader loader : alsoThese) {
            for (ClassLoader up = loader; up != null; up = up.getParent()) loaders.add(up);
        }
        for (ClassLoader up = Thread.currentThread().getContextClassLoader();
                up != null; up = up.getParent()) {
            loaders.add(up);
        }
        for (ClassLoader up = BundleCache.class.getClassLoader(); up != null; up = up.getParent()) {
            loaders.add(up);
        }
        loaders.add(ClassLoader.getSystemClassLoader());

        int cleared = 0;
        for (ClassLoader loader : loaders) {
            if (loader == null) continue;
            try {
                ResourceBundle.clearCache(loader);
                cleared++;
            } catch (Throwable notThisOne) {
                // A loader that will not have its cache cleared keeps it, and
                // the others are still worth clearing.
            }
        }
        return cleared;
    }
}
