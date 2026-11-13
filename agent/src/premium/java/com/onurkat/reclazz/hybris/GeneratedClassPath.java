/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Lets the platform load a model class that {@code ant build} generated while
 * the server was running.
 *
 * <p>The platform classloader reads model classes from
 * {@code bootstrap/bin/models.jar}, which it opened at startup. The regen
 * writes a new jar and the class files beside it in
 * {@code bootstrap/modelclasses}, and the open jar shows neither: a type added
 * to items.xml got its table from Update Running System, but the platform
 * could not load its model class and handed out the generic {@code ItemModel}
 * for it, without any of its attributes.
 *
 * <p>The generated class directories are appended to that loader's search
 * path. They come after the jar, so every class the jar already has keeps
 * coming from it, and only a class the jar does not have is found in the
 * directory, by the loader that defines its siblings.
 */
public final class GeneratedClassPath {

    /** The class every generated model extends, so its loader is the one that loads models. */
    private static final String ITEM_MODEL = "de.hybris.platform.core.model.ItemModel";

    private GeneratedClassPath() {
    }

    /** @return how many directories were added; 0 when they already were, or nothing could be done */
    public static int expose(ClassLoader platformLoader, Collection<Path> generatedDirs, Instrumentation instrumentation) {
        if (platformLoader == null || generatedDirs == null || generatedDirs.isEmpty()) return 0;
        ClassLoader models;
        try {
            models = Class.forName(ITEM_MODEL, false, platformLoader).getClassLoader();
        } catch (ClassNotFoundException | LinkageError notSap) {
            return 0;
        }
        if (!(models instanceof URLClassLoader loader)) return 0;
        try {
            Set<URL> present = Set.copyOf(Arrays.asList(loader.getURLs()));
            Method addURL = null;
            int added = 0;
            for (Path dir : generatedDirs) {
                if (!Files.isDirectory(dir)) continue;
                // A directory URL needs its trailing slash, which toUri gives an existing directory.
                URL url = dir.toUri().toURL();
                if (present.contains(url)) continue;
                if (addURL == null) addURL = addUrlMethod(instrumentation);
                addURL.invoke(loader, url);
                added++;
            }
            return added;
        } catch (Throwable refused) {
            StatusReporter.warn("A model class generated while the server runs cannot be loaded by the platform ("
                    + Failures.describe(refused) + "); a new type keeps the generic ItemModel until a restart.");
            RestartLedger.note("generated model classes",
                    "the platform cannot load a model class generated while running");
            return 0;
        }
    }

    /** Checked readiness: zero newly added roots can still mean already ready. */
    static boolean ready(ClassLoader platformLoader, Collection<Path> roots, Instrumentation instrumentation) {
        if (platformLoader == null || roots == null || roots.isEmpty()
                || roots.stream().anyMatch(p -> !Files.isDirectory(p))) return false;
        expose(platformLoader,roots,instrumentation);
        try {
            ClassLoader models=Class.forName(ITEM_MODEL,false,platformLoader).getClassLoader();
            if (!(models instanceof URLClassLoader loader)) return false;
            Set<URL> urls=Set.copyOf(Arrays.asList(loader.getURLs()));
            for (Path root:roots) if (!urls.contains(root.toUri().toURL())) return false;
            return true;
        } catch (Throwable refused) { return false; }
    }

    /**
     * {@code URLClassLoader.addURL} is protected in a package java.base does not
     * open. The agent opens it to itself here, the first time a regen needs it,
     * and not before.
     */
    private static Method addUrlMethod(Instrumentation instrumentation) throws ReflectiveOperationException {
        Module base = URLClassLoader.class.getModule();
        Module self = GeneratedClassPath.class.getModule();
        if (instrumentation != null && !base.isOpen("java.net", self)) {
            instrumentation.redefineModule(base, Set.of(), Map.of(),
                    Map.of("java.net", Set.of(self)), Set.of(), Map.of());
        }
        Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
        addURL.setAccessible(true);
        return addURL;
    }
}
