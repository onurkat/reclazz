/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import com.onurkat.reclazz.ui.StatusReporter;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Applies edited platform properties to the running server.
 *
 * SAP Commerce reads its property files once, at startup, into an in-memory
 * configuration. Editing one afterwards changes a file nobody will read again,
 * which is why the answer has always been to restart. The platform does allow
 * runtime changes, through {@code Config.setParameter}, which is what the HAC
 * console does when you edit a property there.
 *
 * So the file is read, compared against what the server currently holds, and
 * only the keys that actually differ are applied. Comparing rather than
 * applying wholesale matters: a config directory holds thousands of keys, most
 * of them untouched, and re-setting them all would be a lot of noise and a lot
 * of chances to overwrite something HAC or another extension set deliberately.
 *
 * Not everything a property controls is re-read when the property changes. A
 * datasource URL is used to build a pool at startup and nothing consults it
 * again; a feature flag read per request takes effect immediately. Reclazz
 * cannot tell the two apart, so it reports what it applied and says that
 * plainly rather than implying the change is live everywhere.
 *
 * Reflection throughout: the agent has no compile-time dependency on the
 * platform, and this must do nothing at all when it is not running on one.
 */
public class HybrisConfigReloader {

    private static final String CONFIG_CLASS = "de.hybris.platform.util.Config";

    private final ClassLoader platformClassLoader;
    private final Class<?> configClassForTests;

    public HybrisConfigReloader(ClassLoader platformClassLoader) {
        this.platformClassLoader = platformClassLoader;
        this.configClassForTests = null;
    }

    /**
     * Test seam. The platform class cannot be loaded outside a running server,
     * and handing one in is less machinery than pretending to be a classloader
     * that answers to its name.
     */
    HybrisConfigReloader(Class<?> configClass) {
        this.platformClassLoader = null;
        this.configClassForTests = configClass;
    }

    /**
     * @return the keys whose value changed and were applied, in file order;
     *         empty when nothing changed or the platform is not reachable
     */
    public List<String> apply(Path propertiesFile) {
        List<String> applied = new ArrayList<>();

        Class<?> config = findConfig();
        if (config == null) return applied;

        Properties fromFile = read(propertiesFile);
        if (fromFile.isEmpty()) return applied;

        try {
            Method get = config.getMethod("getParameter", String.class);
            Method set = config.getMethod("setParameter", String.class, String.class);

            for (String key : fromFile.stringPropertyNames()) {
                String desired = fromFile.getProperty(key);
                String current = (String) get.invoke(null, key);
                if (desired == null || desired.equals(current)) continue;

                set.invoke(null, key, desired);
                applied.add(key);
            }
        } catch (Throwable t) {
            StatusReporter.warn("Could not apply property changes: " + t.getMessage());
            return List.of();
        }

        return applied;
    }

    private Class<?> findConfig() {
        if (configClassForTests != null) return configClassForTests;
        if (platformClassLoader == null) return null;
        try {
            return Class.forName(CONFIG_CLASS, false, platformClassLoader);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A half-written file is the normal case, not the exception: editors save
     * in stages and the watcher is fast. Returning nothing leaves the server
     * on its previous values, and the next save will carry the same keys.
     */
    private Properties read(Path file) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (Throwable t) {
            return new Properties();
        }
        return p;
    }
}
