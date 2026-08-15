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

    /**
     * Whether this file is the platform's configuration, rather than one of the
     * many other things that happen to be spelled {@code .properties}.
     *
     * The distinction is not pedantic. Everything in a watched extension is a
     * candidate, and almost none of it is configuration: on a mid-sized project
     * measured while writing this, 353 property files held 8,728 keys, and 350
     * of those files were message bundles for e-mails and OCC responses.
     * Pushing an e-mail subject line into the running server's configuration
     * changes what every component that reads that configuration sees, and it
     * arrives with no edit at all when a branch is checked out.
     *
     * So the platform's own naming decides: {@code local.properties} and
     * {@code project.properties}, and the numbered files the platform keeps in
     * a {@code props} directory.
     */
    public static boolean isPlatformConfiguration(Path file) {
        if (file == null || file.getFileName() == null) return false;

        String name = file.getFileName().toString();
        if (name.equals("local.properties") || name.equals("project.properties")) {
            return true;
        }
        if (!name.endsWith(".properties")) return false;

        Path parent = file.getParent();
        return parent != null && parent.getFileName() != null
                && parent.getFileName().toString().equals("props");
    }

    private final ClassLoader platformClassLoader;
    private final Class<?> configClassForTests;
    private final PropertyFileSnapshots snapshots;

    public HybrisConfigReloader(ClassLoader platformClassLoader, PropertyFileSnapshots snapshots) {
        this.platformClassLoader = platformClassLoader;
        this.configClassForTests = null;
        this.snapshots = snapshots;
    }

    /**
     * Test seam. The platform class cannot be loaded outside a running server,
     * and handing one in is less machinery than pretending to be a classloader
     * that answers to its name.
     */
    HybrisConfigReloader(Class<?> configClass) {
        this(configClass, new PropertyFileSnapshots());
    }

    HybrisConfigReloader(Class<?> configClass, PropertyFileSnapshots snapshots) {
        this.platformClassLoader = null;
        this.configClassForTests = configClass;
        this.snapshots = snapshots;
    }

    /**
     * @return the keys whose value changed and were applied, in file order;
     *         empty when nothing changed or the platform is not reachable
     */
    public List<String> apply(Path propertiesFile) {
        List<String> applied = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        Class<?> config = findConfig();
        if (config == null) return applied;

        // Against this file's own previous content, never against the running
        // configuration: the configuration is not a copy of any one file. See
        // PropertyFileSnapshots.
        java.util.Map<String, String> edited = snapshots.changedSince(propertiesFile);
        if (edited.isEmpty()) return applied;

        // Config reads the tenant from a ThreadLocal that the watcher thread
        // does not have. Without this the call fails with a bare
        // NullPointerException from inside the platform.
        if (configClassForTests == null && !PlatformTenant.ensureActive(platformClassLoader)) {
            return applied;
        }

        try {
            Method get = config.getMethod("getParameter", String.class);
            Method set = config.getMethod("setParameter", String.class, String.class);

            for (java.util.Map.Entry<String, String> entry : edited.entrySet()) {
                String key = entry.getKey();
                String desired = entry.getValue();
                if (desired == null || isUnresolved(desired)) continue;

                String current = (String) get.invoke(null, key);
                if (desired.equals(current)) continue;

                set.invoke(null, key, desired);

                // Read it back. The platform is free to ignore, coerce or
                // override a value, and a key reported as applied that the
                // server did not take is exactly the kind of claim this
                // feature must not make.
                String after = (String) get.invoke(null, key);
                if (desired.equals(after)) {
                    applied.add(key);
                } else {
                    rejected.add(key);
                }
            }
        } catch (Throwable t) {
            // Reflection wraps whatever the platform threw, and the wrapper
            // carries no message of its own. Report the cause, and its type:
            // a NullPointerException with nothing to say is the usual shape
            // here and the type is the only clue.
            Throwable cause = (t.getCause() != null) ? t.getCause() : t;
            String detail = (cause.getMessage() != null)
                    ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                    : cause.getClass().getName();
            StatusReporter.warn("Could not apply property changes: " + detail);
            return List.of();
        }

        if (!rejected.isEmpty()) {
            StatusReporter.warn("The platform did not take " + rejected.size()
                    + " property change(s): " + rejected
                    + ". They need a restart.");
        }

        return applied;
    }

    /**
     * Whether a value still contains a placeholder the platform expands when it
     * loads the file.
     *
     * The file says {@code file:${HYBRIS_CONFIG_DIR}/security/keystore.jks} and
     * the running server holds the expanded path, so the two never compare
     * equal and the raw text looks like a change on every save. Writing it back
     * replaces a working absolute path with the literal characters
     * {@code ${HYBRIS_CONFIG_DIR}}, which is how a save of an untouched line
     * takes out SSO. Reclazz does not expand these, because doing it the way
     * the platform does means reproducing the platform, so a value carrying one
     * is left exactly as the server has it.
     */
    private static boolean isUnresolved(String value) {
        return value.contains("${");
    }

    /**
     * Whether the platform configuration can be reached at all. It separates
     * "nothing in the file differed from what the server already holds" from
     * "this is not a platform and the edit reached nothing", which read the
     * same from an empty result and do not deserve the same message.
     */
    public boolean isPlatformReachable() {
        return findConfig() != null;
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
}
