/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import com.onurkat.reclazz.agent.AgentConfig;
import com.onurkat.reclazz.compiler.ClasspathResolver;
import com.onurkat.reclazz.hybris.ExtensionInfo;
import com.onurkat.reclazz.hybris.HybrisContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * PlatformContext implementation for SAP Commerce (Hybris).
 * Wraps HybrisContext and ClasspathResolver, delegating to existing Hybris classes.
 */
public class HybrisPlatformContext implements PlatformContext {

    private final Path hybrisHome;
    private final AgentConfig config;
    private HybrisContext hybrisContext;
    private ClasspathResolver classpathResolver;

    public HybrisPlatformContext(Path hybrisHome, AgentConfig config) {
        this.hybrisHome = hybrisHome;
        this.config = config;
    }

    @Override
    public Platform getPlatformId() {
        return Platform.HYBRIS;
    }

    @Override
    public void initialize() throws Exception {
        hybrisContext = new HybrisContext(hybrisHome);
        hybrisContext.initialize();
        StatusReporter.info("Hybris home: " + hybrisHome);
        StatusReporter.info("Platform version: " + platformVersion());
        StatusReporter.info("Found " + hybrisContext.getExtensions().size() + " extensions");
    }

    /**
     * Which SAP Commerce line this is, from the platform's own build number.
     *
     * <p>Two lines are in the field at once. SAP shipped 2211-jdk21 in
     * September 2025, moving the platform to Java 21 and Spring 6.2, and after
     * 31 August 2026 builds on the Java 17 line are blocked, so every
     * installation is either migrating or has just migrated. The two behave
     * differently enough that it is the first question worth asking about any
     * report, and the answer is sitting in {@code bin/platform/build.number}
     * as {@code version=2211-jdk21.8}.
     *
     * <p>Printed rather than acted on: it costs one line in the log and saves
     * a round trip on every question that starts with "which version".
     */
    private String platformVersion() {
        Path buildNumber = hybrisHome.resolve("bin").resolve("platform").resolve("build.number");
        try {
            for (String line : java.nio.file.Files.readAllLines(buildNumber)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("version=")) {
                    String version = trimmed.substring("version=".length()).trim();
                    if (!version.isEmpty()) return version;
                }
            }
            return "unknown (build.number has no version)";
        } catch (Exception e) {
            return "unknown (" + buildNumber.getFileName() + " not readable)";
        }
    }

    @Override
    public Map<String, List<Path>> getClassOutputDirs() {
        Map<String, List<Path>> result = new LinkedHashMap<>();
        for (ExtensionInfo ext : hybrisContext.getExtensions().values()) {
            if (!config.shouldWatchExtension(ext.getName())) continue;
            if (config.isWatchAllExtensions() && !ext.isCustom()) continue;

            List<Path> dirs = new ArrayList<>();
            Path classesDir = ext.getPath().resolve("classes");
            if (Files.isDirectory(classesDir)) {
                dirs.add(classesDir);
            }

            Path webClassesDir = ext.getPath().resolve("web").resolve("webroot")
                    .resolve("WEB-INF").resolve("classes");
            if (Files.isDirectory(webClassesDir)) {
                dirs.add(webClassesDir);
            }

            if (!dirs.isEmpty()) {
                result.put(ext.getName(), dirs);
            }
        }

        // Platform-level generated code: DTOs from *-beans.xml and models
        // from *-items.xml are generated into bootstrap/modelclasses/ and
        // bootstrap/classes/. Without watching these, changes triggered by
        // the beans.xml reloader (`ant build`) wouldn't flow into the
        // structural reload path because the agent's class watcher
        // wouldn't see them.
        Path bootstrap = hybrisContext.getPlatformHome()
                .resolve("bootstrap");
        List<Path> bootstrapDirs = new ArrayList<>();
        Path modelClasses = bootstrap.resolve("modelclasses");
        if (Files.isDirectory(modelClasses)) {
            bootstrapDirs.add(modelClasses);
        }
        Path bootstrapClasses = bootstrap.resolve("classes");
        if (Files.isDirectory(bootstrapClasses)) {
            bootstrapDirs.add(bootstrapClasses);
        }
        if (!bootstrapDirs.isEmpty()) {
            result.put("__platform_bootstrap__", bootstrapDirs);
        }
        return result;
    }

    @Override
    public Map<String, List<Path>> getSourceDirs() {
        Map<String, List<Path>> result = new LinkedHashMap<>();
        for (ExtensionInfo ext : hybrisContext.getExtensions().values()) {
            if (!config.shouldWatchExtension(ext.getName())) continue;
            if (config.isWatchAllExtensions() && !ext.isCustom()) continue;

            List<Path> dirs = new ArrayList<>();
            Path srcDir = ext.getPath().resolve("src");
            if (Files.isDirectory(srcDir)) {
                dirs.add(srcDir);
            }
            Path webSrcDir = ext.getPath().resolve("web").resolve("src");
            if (Files.isDirectory(webSrcDir)) {
                dirs.add(webSrcDir);
            }

            if (!dirs.isEmpty()) {
                result.put(ext.getName(), dirs);
            }
        }
        return result;
    }

    @Override
    public Map<String, List<Path>> getResourceDirs() {
        Map<String, List<Path>> result = new LinkedHashMap<>();
        for (ExtensionInfo ext : hybrisContext.getExtensions().values()) {
            if (!config.shouldWatchExtension(ext.getName())) continue;
            if (config.isWatchAllExtensions() && !ext.isCustom()) continue;

            Path resourcesDir = ext.getPath().resolve("resources");
            if (Files.isDirectory(resourcesDir)) {
                result.put(ext.getName(), List.of(resourcesDir));
            }
        }

        // The platform's own configuration, which lives outside every
        // extension: config/local.properties and the numbered files under
        // config/<env>/props. Without this the property reloader has nothing
        // to react to, because the file a developer actually edits is the one
        // place nothing else watches.
        Path configDir = hybrisHome.resolve("config");
        if (Files.isDirectory(configDir)) {
            result.put("__platform_config__", List.of(configDir));
        }

        return result;
    }

    @Override
    public String resolveClasspath() {
        if (classpathResolver == null) {
            classpathResolver = new ClasspathResolver(hybrisContext);
        }
        return classpathResolver.resolve();
    }

    @Override
    public String resolveClassName(Path classFile) {
        for (ExtensionInfo ext : hybrisContext.getExtensions().values()) {
            Path extPath = ext.getPath();

            // Check classes/ directory
            Path classesDir = extPath.resolve("classes");
            if (classFile.startsWith(classesDir)) {
                return pathToClassName(classesDir, classFile);
            }

            // Check web/webroot/WEB-INF/classes/
            Path webClassesDir = extPath.resolve("web").resolve("webroot")
                    .resolve("WEB-INF").resolve("classes");
            if (classFile.startsWith(webClassesDir)) {
                return pathToClassName(webClassesDir, classFile);
            }

            // Check bin/ directory
            Path binDir = extPath.resolve("bin");
            if (classFile.startsWith(binDir)) {
                return pathToClassName(binDir, classFile);
            }
        }

        // Platform-level generated model classes (from *-beans.xml and
        // *-items.xml). Generated DTOs land in bootstrap/modelclasses/,
        // bootstrapped classes land in bootstrap/classes/. These aren't
        // owned by any extension — they're platform-wide.
        Path bootstrap = hybrisContext.getPlatformHome().resolve("bootstrap");
        Path modelClasses = bootstrap.resolve("modelclasses");
        if (classFile.startsWith(modelClasses)) {
            return pathToClassName(modelClasses, classFile);
        }
        Path bootstrapClasses = bootstrap.resolve("classes");
        if (classFile.startsWith(bootstrapClasses)) {
            return pathToClassName(bootstrapClasses, classFile);
        }
        return null;
    }

    @Override
    public Path resolveOutputDir(Path classFile) {
        // Platform-level generated code buckets first (short-circuit before
        // the per-extension loop since these paths aren't owned by any ext).
        Path bootstrap = hybrisContext.getPlatformHome().resolve("bootstrap");
        Path modelClasses = bootstrap.resolve("modelclasses");
        if (classFile.startsWith(modelClasses)) return modelClasses;
        Path bootstrapClasses = bootstrap.resolve("classes");
        if (classFile.startsWith(bootstrapClasses)) return bootstrapClasses;

        for (ExtensionInfo ext : hybrisContext.getExtensions().values()) {
            Path extPath = ext.getPath();

            Path classesDir = extPath.resolve("classes");
            if (classFile.startsWith(classesDir)) return classesDir;

            Path webClassesDir = extPath.resolve("web").resolve("webroot")
                    .resolve("WEB-INF").resolve("classes");
            if (classFile.startsWith(webClassesDir)) return webClassesDir;

            Path binDir = extPath.resolve("bin");
            if (classFile.startsWith(binDir)) return binDir;
        }
        return null;
    }

    @Override
    public Object getApplicationContext() {
        // Preferred path: ApplicationContextHolder, populated by
        // SpringContextInterceptTransformer when AbstractApplicationContext.refresh()
        // completes. This works from any thread — no tenant activation, no
        // Hybris-version-specific Registry API quirks. As of Commerce Cloud
        // 2205+ the tenant's context field is populated late in tenant init
        // (after Spring refresh), so Registry.getMasterTenant().getApplicationContext()
        // can return null even when the context is fully live. The intercept
        // transformer captures the context at refresh() directly.
        Object fromHolder = ApplicationContextHolder.getApplicationContext();
        if (fromHolder != null) return fromHolder;

        // Fallback path: ask Hybris Registry directly. Works on older Hybris
        // where tenant.getApplicationContext() is populated synchronously
        // during Spring init, and on tenant-bound threads.
        try {
            Class<?> registryClass = Class.forName("de.hybris.platform.core.Registry");

            // First try master tenant — no ThreadLocal tenant state required.
            try {
                Method getMasterTenant = registryClass.getMethod("getMasterTenant");
                Object masterTenant = getMasterTenant.invoke(null);
                if (masterTenant != null) {
                    Method tenantGetCtx = masterTenant.getClass().getMethod("getApplicationContext");
                    Object ctx = tenantGetCtx.invoke(masterTenant);
                    if (ctx != null) return ctx;
                }
            } catch (NoSuchMethodException ignored) {
                // getMasterTenant() not on this Hybris version — fall through
            }

            // Legacy: Registry.getApplicationContext() needs a ThreadLocal
            // tenant. Works from request handlers / cronjobs, fails on
            // Reclazz's watcher thread.
            Method hasCurrentTenant = registryClass.getMethod("hasCurrentTenant");
            if (!(Boolean) hasCurrentTenant.invoke(null)) return null;
            Method getCtx = registryClass.getMethod("getApplicationContext");
            return getCtx.invoke(null);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<Object> getAllApplicationContexts() {
        // Holder carries every captured context (global + web contexts from
        // refresh interception or attach-time Tomcat scan). The Registry
        // fallback contributes the global context when the holder is empty
        // (e.g. attached before any capture ran).
        List<Object> contexts = new ArrayList<>(ApplicationContextHolder.getAllContexts());
        Object fallback = getApplicationContext();
        if (fallback != null && contexts.stream().noneMatch(c -> c == fallback)) {
            contexts.add(fallback);
        }
        return contexts;
    }

    @Override
    public String resolveModuleName(Path filePath) {
        for (ExtensionInfo ext : hybrisContext.getExtensions().values()) {
            if (filePath.startsWith(ext.getPath())) {
                return ext.getName();
            }
        }
        return "unknown";
    }

    @Override
    public boolean shouldWatch(String moduleName) {
        return config.shouldWatchExtension(moduleName);
    }

    /**
     * Get the underlying HybrisContext for Hybris-specific operations.
     */
    public HybrisContext getHybrisContext() {
        return hybrisContext;
    }

    private static String pathToClassName(Path outputRoot, Path classFile) {
        Path relative = outputRoot.relativize(classFile);
        return relative.toString()
                .replace(".class", "")
                .replace(File.separatorChar, '.')
                .replace('/', '.');
    }
}
