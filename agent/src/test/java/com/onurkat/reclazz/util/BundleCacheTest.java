/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cache that decides whether a translator's save reaches anybody.
 *
 * <p>{@code ResourceBundle.getBundle} memoises per classloader, base name and
 * locale, and never looks at the file again. That is the point of it and it is
 * also why a hot-reload agent has to know about it: every framework cache above
 * can be emptied and the next lookup is still answered from this one, with the
 * text as it was when the application first asked.
 *
 * <p>The first test is that behaviour, shown rather than described, because it
 * is what the fix is for. The rest is that clearing it works and is safe to do
 * when there is nothing to clear.
 */
class BundleCacheTest {

    @TempDir
    Path tmp;

    /** Turkish, because the text this is about is usually not ASCII. */
    private ClassLoader bundleWith(String text) throws IOException {
        Files.writeString(tmp.resolve("Greetings.properties"),
                "hello=" + text + "\n", StandardCharsets.UTF_8);
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, null);
    }

    @Test
    void withoutClearingItTheOldTextIsServedForever() throws IOException {
        ClassLoader loader = bundleWith("Merhaba");
        assertEquals("Merhaba",
                ResourceBundle.getBundle("Greetings", Locale.ROOT, loader).getString("hello"));

        Files.writeString(tmp.resolve("Greetings.properties"),
                "hello=İyi günler\n", StandardCharsets.UTF_8);

        assertEquals("Merhaba",
                ResourceBundle.getBundle("Greetings", Locale.ROOT, loader).getString("hello"),
                "this is the whole problem: the file changed and the lookup did not");
    }

    @Test
    void clearingItMakesTheNextLookupReadTheFile() throws IOException {
        ClassLoader loader = bundleWith("Merhaba");
        ResourceBundle.getBundle("Greetings", Locale.ROOT, loader);

        Files.writeString(tmp.resolve("Greetings.properties"),
                "hello=İyi günler\n", StandardCharsets.UTF_8);
        BundleCache.clear(loader);

        assertEquals("İyi günler",
                ResourceBundle.getBundle("Greetings", Locale.ROOT, loader).getString("hello"),
                "and the Turkish letters came through, which is the other half of it");
    }

    /**
     * The bundle was loaded by whichever loader found it, and inside a SAP
     * Commerce server that is not the one this agent was loaded by. Clearing
     * has to reach up the chain rather than only the loader it was handed.
     */
    @Test
    void aLoaderFurtherUpTheChainIsClearedToo() throws IOException {
        ClassLoader parent = bundleWith("Merhaba");
        ClassLoader child = new URLClassLoader(new URL[0], parent);
        assertEquals("Merhaba",
                ResourceBundle.getBundle("Greetings", Locale.ROOT, parent).getString("hello"));

        Files.writeString(tmp.resolve("Greetings.properties"),
                "hello=İyi günler\n", StandardCharsets.UTF_8);
        BundleCache.clear(child);

        assertEquals("İyi günler",
                ResourceBundle.getBundle("Greetings", Locale.ROOT, parent).getString("hello"),
                "the agent is handed the loader it can see, not the one that read the file");
    }

    @Test
    void clearingWhatHoldsNothingIsFine() {
        assertTrue(BundleCache.clear() > 0,
                "it clears the loaders it can reach without being given one");
        assertTrue(BundleCache.clear((ClassLoader) null) > 0,
                "and a null among them is not a reason to do nothing");
    }
}
