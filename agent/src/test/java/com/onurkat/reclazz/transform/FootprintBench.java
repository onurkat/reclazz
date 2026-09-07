/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.agent.AgentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Not a test: what the agent keeps per watched class after the load-time
 * transform, as heap. Run with -Preclazz.bench.jar=... -Preclazz.bench.out=...
 */
class FootprintBench {

    @Test
    @EnabledIfSystemProperty(named = "reclazz.bench.jar", matches = ".+")
    void measure() throws Exception {
        Path jar = Path.of(System.getProperty("reclazz.bench.jar"));
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            var en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (!e.getName().endsWith(".class") || e.getName().contains("module-info")) continue;
                try (InputStream is = jf.getInputStream(e)) {
                    classes.put(e.getName().substring(0, e.getName().length() - 6), is.readAllBytes());
                }
            }
        }
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);
        StringBuilder out = new StringBuilder();

        long before = usedAfterGc();
        TransformContext context = new TransformContext();
        classes.keySet().forEach(context::addWatched);
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));
        int transformed = 0;
        for (var e : classes.entrySet()) {
            try {
                if (transformer.transform(loader, e.getKey(), null, null, e.getValue()) != null) transformed++;
            } catch (Throwable ignored) {
            }
        }
        long afterAll = usedAfterGc();
        long cacheBytes = TransformedClassCache.deflatedBytes();
        int cacheCount = TransformedClassCache.classCount();
        out.append(String.format("classes=%d transformed=%d heapDelta=%dKB (%d bytes/class) | cache: %d classes, %dKB deflated (%d bytes/class)%n",
                classes.size(), transformed, (afterAll - before) / 1024, (afterAll - before) / Math.max(1, transformed),
                cacheCount, cacheBytes / 1024, cacheBytes / Math.max(1, cacheCount)));

        // Metadata alone: keep the context, count what a fresh context of the same names costs.
        int methods = 0, fields = 0, annotations = 0;
        for (String name : classes.keySet()) {
            TransformContext.ClassMetadata m = context.getMetadata(name);
            if (m == null) continue;
            methods += m.getMethods().size();
            fields += m.getFields().size();
            annotations += m.getAnnotations() == null ? 0 : m.getAnnotations().size();
        }
        long annotationChars = 0;
        for (String name : classes.keySet()) {
            TransformContext.ClassMetadata m = context.getMetadata(name);
            if (m == null || m.getAnnotations() == null) continue;
            for (String a : m.getAnnotations()) annotationChars += a.length();
        }
        int shapes = -1;
        long shapeMembers = 0;
        try {
            java.lang.reflect.Field f = SuperCallTarget.class.getDeclaredField("SHAPES");
            f.setAccessible(true);
            Map<?, ?> map = (Map<?, ?>) f.get(null);
            shapes = map.size();
            for (Object shape : map.values()) {
                for (java.lang.reflect.Field sf : shape.getClass().getDeclaredFields()) {
                    sf.setAccessible(true);
                    Object v = sf.get(shape);
                    if (v instanceof java.util.Collection<?> c) shapeMembers += c.size();
                    if (v instanceof Map<?, ?> mm) shapeMembers += mm.size();
                }
            }
        } catch (Exception ignored) {
        }
        out.append(String.format("metadata: %d methods, %d fields, %d annotation signatures (%d chars) across %d classes | parent shapes cached: %d holding %d members%n",
                methods, fields, annotations, annotationChars, transformed, shapes, shapeMembers));

        // What the cache costs beyond its payload, and what the metadata costs, by dropping each in turn.
        long withEverything = usedAfterGc();
        java.lang.reflect.Field entries = TransformedClassCache.class.getDeclaredField("ENTRIES");
        entries.setAccessible(true);
        synchronized (TransformedClassCache.class) {
            ((Map<?, ?>) entries.get(null)).clear();
        }
        long withoutCache = usedAfterGc();
        out.append(String.format("cache retained (payload+structure): %dKB%n", (withEverything - withoutCache) / 1024));
        Files.writeString(Path.of(System.getProperty("reclazz.bench.out")), out.toString());
        System.out.println(out);
        // Keep the context alive to the end so the measurement includes it.
        if (context.getWatchedClassCount() < 0) throw new IllegalStateException();
    }

    private static long usedAfterGc() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(100);
        }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
