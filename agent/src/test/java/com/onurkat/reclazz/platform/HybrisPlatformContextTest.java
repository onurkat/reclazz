/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import com.onurkat.reclazz.agent.AgentConfig;
import com.onurkat.reclazz.hybris.ExtensionInfo;
import com.onurkat.reclazz.hybris.HybrisContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the platform-level class output / class-name resolution
 * logic added for the beans.xml hot-reload feature (1.0.29). These methods
 * are pure functions over the on-disk Hybris layout, so we stand up a
 * minimal fake Hybris directory structure in a TempDir and verify the
 * resolver walks it correctly.
 *
 * The {@link HybrisContext} state is injected directly via reflection so
 * the tests don't have to run a full {@code ExtensionScanner} or parse a
 * {@code localextensions.xml} — we only care about the path-matching
 * logic, not the startup discovery pipeline.
 */
class HybrisPlatformContextTest {

    @TempDir
    Path tempDir;

    private Path hybrisHome;
    private Path platformHome;
    private Path bootstrap;
    private Path modelClasses;
    private Path bootstrapClasses;
    private HybrisPlatformContext ctx;
    private HybrisContext hybrisContext;

    @BeforeEach
    void setUp() throws Exception {
        hybrisHome = tempDir;
        platformHome = hybrisHome.resolve("bin").resolve("platform");
        bootstrap = platformHome.resolve("bootstrap");
        modelClasses = bootstrap.resolve("modelclasses");
        bootstrapClasses = bootstrap.resolve("classes");
        Files.createDirectories(platformHome);
        Files.createDirectories(modelClasses);
        Files.createDirectories(bootstrapClasses);

        ctx = new HybrisPlatformContext(hybrisHome, AgentConfig.parse(null));
        // Inject a pre-built HybrisContext so we don't need a real
        // localextensions.xml + ExtensionScanner run.
        hybrisContext = new HybrisContext(hybrisHome);
        setField(hybrisContext, "platformHome", platformHome);
        setField(hybrisContext, "configDir", hybrisHome.resolve("config"));
        setField(hybrisContext, "extensions", new LinkedHashMap<String, ExtensionInfo>());
        setField(ctx, "hybrisContext", hybrisContext);
    }

    // ─── getClassOutputDirs ───────────────────────────────────────────────────

    @Test
    void getClassOutputDirs_includesBootstrapDirsUnderSyntheticLabel() {
        Map<String, List<Path>> dirs = ctx.getClassOutputDirs();

        assertTrue(dirs.containsKey("__platform_bootstrap__"),
                "bootstrap dirs must surface under the synthetic platform label");
        List<Path> bootstrapEntries = dirs.get("__platform_bootstrap__");
        assertTrue(bootstrapEntries.contains(modelClasses),
                "modelclasses/ must be watched — it holds DTOs generated from *-beans.xml");
        assertTrue(bootstrapEntries.contains(bootstrapClasses),
                "bootstrap/classes/ must be watched");
    }

    @Test
    void getClassOutputDirs_includesExtensionClassesAlongsideBootstrap() throws Exception {
        // Add a custom extension with a classes/ dir
        Path extDir = hybrisHome.resolve("bin/custom/myExt");
        Path extClasses = extDir.resolve("classes");
        Files.createDirectories(extClasses);

        ExtensionInfo myExt = new ExtensionInfo(
                "myExt", extDir, List.of(), true, false, true);
        Map<String, ExtensionInfo> exts = new LinkedHashMap<>();
        exts.put("myExt", myExt);
        setField(hybrisContext, "extensions", exts);

        Map<String, List<Path>> dirs = ctx.getClassOutputDirs();
        assertTrue(dirs.containsKey("myExt"),
                "custom extension's classes/ still registered");
        assertTrue(dirs.get("myExt").contains(extClasses));
        assertTrue(dirs.containsKey("__platform_bootstrap__"),
                "bootstrap dirs still present alongside ext dirs");
    }

    @Test
    void getClassOutputDirs_skipsBootstrapDirsWhenMissing() throws Exception {
        // Rebuild a fake layout without the bootstrap sub-tree
        Path cleanHome = Files.createTempDirectory("reclazz-clean-");
        try {
            Files.createDirectories(cleanHome.resolve("bin").resolve("platform"));
            HybrisPlatformContext cleanCtx = new HybrisPlatformContext(cleanHome, AgentConfig.parse(null));
            HybrisContext cleanHybris = new HybrisContext(cleanHome);
            setField(cleanHybris, "platformHome", cleanHome.resolve("bin/platform"));
            setField(cleanHybris, "configDir", cleanHome.resolve("config"));
            setField(cleanHybris, "extensions", new LinkedHashMap<String, ExtensionInfo>());
            setField(cleanCtx, "hybrisContext", cleanHybris);

            Map<String, List<Path>> dirs = cleanCtx.getClassOutputDirs();
            assertFalse(dirs.containsKey("__platform_bootstrap__"),
                    "synthetic label should not appear when bootstrap dirs don't exist");
        } finally {
            deleteRecursive(cleanHome);
        }
    }

    // ─── resolveClassName ─────────────────────────────────────────────────────

    @Test
    void resolveClassName_bootstrapModelclassesPath_returnsQualifiedName() {
        Path clazz = modelClasses.resolve("com").resolve("example")
                .resolve("shop").resolve("core").resolve("dto")
                .resolve("ReclazzBeansTestData.class");
        String fqn = ctx.resolveClassName(clazz);
        assertEquals("com.example.shop.core.dto.ReclazzBeansTestData", fqn);
    }

    @Test
    void resolveClassName_bootstrapClassesPath_returnsQualifiedName() {
        Path clazz = bootstrapClasses.resolve("de").resolve("hybris")
                .resolve("bootstrap").resolve("Foo.class");
        String fqn = ctx.resolveClassName(clazz);
        assertEquals("de.hybris.bootstrap.Foo", fqn);
    }

    @Test
    void resolveClassName_extensionClassesPath_returnsQualifiedName() throws Exception {
        Path extDir = hybrisHome.resolve("bin/custom/myExt");
        Path extClasses = extDir.resolve("classes");
        Files.createDirectories(extClasses);
        setField(hybrisContext, "extensions",
                Map.of("myExt", new ExtensionInfo("myExt", extDir, List.of(), true, false, true)));

        Path clazz = extClasses.resolve("com").resolve("example").resolve("Service.class");
        assertEquals("com.example.Service", ctx.resolveClassName(clazz));
    }

    @Test
    void resolveClassName_unrelatedPath_returnsNull() {
        Path clazz = tempDir.resolve("unrelated").resolve("Foo.class");
        assertNull(ctx.resolveClassName(clazz));
    }

    // ─── resolveOutputDir ─────────────────────────────────────────────────────

    @Test
    void resolveOutputDir_bootstrapModelclassesPath_returnsModelclassesRoot() {
        Path clazz = modelClasses.resolve("com").resolve("foo").resolve("Bar.class");
        assertEquals(modelClasses, ctx.resolveOutputDir(clazz));
    }

    @Test
    void resolveOutputDir_bootstrapClassesPath_returnsBootstrapClassesRoot() {
        Path clazz = bootstrapClasses.resolve("de").resolve("hybris").resolve("A.class");
        assertEquals(bootstrapClasses, ctx.resolveOutputDir(clazz));
    }

    @Test
    void resolveOutputDir_extensionClassesPath_stillReturnsExtensionClasses() throws Exception {
        Path extDir = hybrisHome.resolve("bin/custom/myExt");
        Path extClasses = extDir.resolve("classes");
        Files.createDirectories(extClasses);
        setField(hybrisContext, "extensions",
                Map.of("myExt", new ExtensionInfo("myExt", extDir, List.of(), true, false, true)));

        Path clazz = extClasses.resolve("com").resolve("example").resolve("Service.class");
        assertEquals(extClasses, ctx.resolveOutputDir(clazz));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = findField(target.getClass(), fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> cls, String fieldName) throws NoSuchFieldException {
        Class<?> current = cls;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static void deleteRecursive(Path root) throws Exception {
        if (!Files.exists(root)) return;
        Files.walk(root)
                .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
    }
}
