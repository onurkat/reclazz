/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring caches a message bundle after the first lookup, and outside SAP
 * Commerce Reclazz used to answer a saved bundle with "may require a restart
 * to take effect", which was a guess printed as a fact.
 *
 * <p>The two sources are not the same problem, which is the thing a live run
 * corrected. {@code ReloadableResourceBundleMessageSource} ships the reset,
 * and the ancestor-including one is the one to prefer, because a Boot
 * application stacks at least two sources and clearing only the front one
 * leaves what was resolved through it. {@code ResourceBundleMessageSource},
 * which is what Boot autoconfigures and therefore what most applications
 * actually have, ships no reset at all: this was written expecting one, and
 * the server answered that no such method exists anywhere up its hierarchy.
 * That one is reset by emptying its own {@code cached...} maps and the JDK
 * {@code ResourceBundle} cache underneath them, and the maps alone are the
 * half-measure that looks like it worked.
 */
class SpringMessageSourceReloaderTest {

    /** ReloadableResourceBundleMessageSource: both, and ancestors is the one. */
    static class Reloadable {
        String called = "none";

        public void clearCache() {
            called = "clearCache";
        }

        public void clearCacheIncludingAncestors() {
            called = "clearCacheIncludingAncestors";
        }
    }

    /** An older reloadable source: only the plain reset. */
    static class OnlyPlainReset {
        String called = "none";

        public void clearCache() {
            called = "clearCache";
        }
    }

    /**
     * ResourceBundleMessageSource's real shape: no reset, two private caches,
     * and the JDK's bundle cache behind them.
     */
    static class BundleSource {
        private final java.util.Map<String, Object> cachedResourceBundles =
                new java.util.HashMap<>();
        private final java.util.Map<Object, Object> cachedBundleMessageFormats =
                new java.util.HashMap<>();

        java.util.Map<String, Object> bundles() {
            return cachedResourceBundles;
        }

        java.util.Map<Object, Object> formats() {
            return cachedBundleMessageFormats;
        }

        public ClassLoader getBundleClassLoader() {
            return BundleSource.class.getClassLoader();
        }
    }

    /** A message source of somebody's own making: no reset, no caches. */
    static class NoReset {
        @SuppressWarnings("unused")
        private final java.util.Map<String, Object> lookups = new java.util.HashMap<>();
    }

    /** A reset that throws is not a reset that happened. */
    static class Broken {
        public void clearCache() {
            throw new IllegalStateException("no");
        }
    }

    @Test
    void theAncestorResetIsPreferredWhenBothExist() {
        Reloadable source = new Reloadable();

        assertTrue(SpringMessageSourceReloader.clear(source));
        assertEquals("clearCacheIncludingAncestors", source.called,
                "clearing only the front source leaves what was resolved through it");
    }

    @Test
    void theSimpleResetIsUsedWhenItIsTheOnlyOne() {
        OnlyPlainReset source = new OnlyPlainReset();

        assertTrue(SpringMessageSourceReloader.clear(source));
        assertEquals("clearCache", source.called);
    }

    /**
     * Boot's default source, the one this was first written unable to reset.
     * Both of its caches have to go, and the count has to say it happened.
     */
    @Test
    void theSourceWithNoResetHasItsOwnCachesEmptied() {
        BundleSource source = new BundleSource();
        source.bundles().put("messages", new Object());
        source.formats().put(new Object(), new Object());

        assertTrue(SpringMessageSourceReloader.clear(source));
        assertTrue(source.bundles().isEmpty());
        assertTrue(source.formats().isEmpty(),
                "the message formats are cached separately from the bundles");
    }

    @Test
    void aSourceWithNoResetIsNotCountedAsDone() {
        NoReset theirs = new NoReset();

        assertFalse(SpringMessageSourceReloader.clear(theirs),
                "counting it would turn the honest warning into a false success");
    }

    @Test
    void aResetThatThrowsIsNotCountedEither() {
        assertFalse(SpringMessageSourceReloader.clear(new Broken()));
    }
}
