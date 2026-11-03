/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What an items.xml type added while the server runs needs from the platform
 * before ModelService can hand out its model: a loader that can find the
 * generated class, and converters that know the new type system.
 */
class GeneratedModelClassesTest {

    @Test
    void aModelClassGeneratedAfterStartupBecomesLoadableBesideItsSiblings(@TempDir Path dir) throws Exception {
        Path startup = dir.resolve("startup");
        Path generated = dir.resolve("modelclasses");
        compile(startup, "de.hybris.platform.core.model.ItemModel",
                "package de.hybris.platform.core.model; public class ItemModel { }");
        compile(generated, "de.hybris.platform.core.model.ItemModel",
                "package de.hybris.platform.core.model; public class ItemModel { public static final String REGENERATED = \"yes\"; }");
        compile(generated, "de.hybris.platform.core.model.ProbeModel",
                "package de.hybris.platform.core.model; public class ProbeModel extends ItemModel { }",
                generated);

        try (var platform = new URLClassLoader(new URL[]{startup.toUri().toURL()}, null)) {
            assertThrows(ClassNotFoundException.class,
                    () -> platform.loadClass("de.hybris.platform.core.model.ProbeModel"));

            assertEquals(1, GeneratedClassPath.expose(platform, List.of(generated), ByteBuddyAgent.install()));
            Class<?> probe = Class.forName("de.hybris.platform.core.model.ProbeModel", false, platform);
            assertSame(platform, probe.getClassLoader(), "defined by the loader that loads its siblings");
            Class<?> item = platform.loadClass("de.hybris.platform.core.model.ItemModel");
            assertSame(item, probe.getSuperclass());
            assertThrows(NoSuchFieldException.class, () -> item.getField("REGENERATED"),
                    "a class the startup path has keeps coming from it");

            assertEquals(0, GeneratedClassPath.expose(platform, List.of(generated), ByteBuddyAgent.install()),
                    "a directory already on the path is not added again");
        }
    }

    @Test
    void anUpdateRefreshesEveryAwareConverterAndReloadsTheRegistry(@TempDir Path dir) throws Exception {
        String pkg = "de.hybris.platform.servicelayer.internal.converter";
        compile(dir, pkg + ".ConverterRegistry", "package " + pkg + "; public interface ConverterRegistry {"
                + " java.util.Collection<Object> getModelConverters(); }");
        compile(dir, pkg + ".impl.TypeSystemAwareModelConverter", "package " + pkg + ".impl;"
                + " public interface TypeSystemAwareModelConverter { void typeSystemChanged(); }", dir);
        compile(dir, pkg + ".impl.Converters", """
                package de.hybris.platform.servicelayer.internal.converter.impl;
                public class Converters {
                    public static class Aware implements TypeSystemAwareModelConverter {
                        public int changed;
                        public void typeSystemChanged() { changed++; }
                    }
                    public static class Registry implements de.hybris.platform.servicelayer.internal.converter.ConverterRegistry {
                        public final Aware aware = new Aware();
                        public int forcedLoads;
                        private final java.util.Map<String, Object> typeToConverterCache = new java.util.HashMap<>(java.util.Map.of("probe", "item"));
                        private final java.util.Map<Class<?>, Object> modelToConverterCache = new java.util.HashMap<>(java.util.Map.of(Object.class, "item"));
                        public java.util.Collection<Object> getModelConverters() { return java.util.List.of(aware, "not aware"); }
                        protected synchronized void loadDefaults(boolean force) { if (force) forcedLoads++; }
                        public boolean cachesEmpty() { return typeToConverterCache.isEmpty() && modelToConverterCache.isEmpty(); }
                    }
                }
                """, dir);
        compile(dir, "de.hybris.platform.core.Registry", """
                package de.hybris.platform.core;
                public class Registry {
                    public static final Context CONTEXT = new Context();
                    public static Context getApplicationContext() { return CONTEXT; }
                    public static class Context {
                        public final Object registry = new de.hybris.platform.servicelayer.internal.converter.impl.Converters.Registry();
                        public java.util.Map<String, Object> getBeansOfType(Class<?> type) {
                            return type.isInstance(registry) ? java.util.Map.of("converterRegistry", registry) : java.util.Map.of();
                        }
                    }
                }
                """, dir);

        try (var platform = new URLClassLoader(new URL[]{dir.toUri().toURL()}, null)) {
            assertTrue(ModelConverterRefresh.refresh(platform));
            Object context = platform.loadClass("de.hybris.platform.core.Registry").getField("CONTEXT").get(null);
            Object registry = context.getClass().getField("registry").get(context);
            Object aware = registry.getClass().getField("aware").get(registry);
            assertEquals(1, aware.getClass().getField("changed").get(aware), "the converter re-reads its attributes");
            assertEquals(1, registry.getClass().getField("forcedLoads").get(registry), "new types get converters");
            assertEquals(true, registry.getClass().getMethod("cachesEmpty").invoke(registry),
                    "a type that resolved to its supertype's converter does not keep that answer");
        }
    }

    private static void compile(Path out, String className, String source, Path... classpath) throws Exception {
        Path src = out.resolveSibling(out.getFileName() + "-src").resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        Files.createDirectories(out);
        List<String> args = new java.util.ArrayList<>(List.of("-proc:none", "-d", out.toString()));
        if (classpath.length > 0) {
            args.add("-cp");
            args.add(classpath[0].toString());
        }
        args.add(src.toString());
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                args.toArray(String[]::new)), "stub " + className + " compiles");
    }
}
