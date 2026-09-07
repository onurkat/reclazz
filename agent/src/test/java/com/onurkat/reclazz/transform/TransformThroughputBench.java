/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.agent.AgentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.CheckClassAdapter;

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
 * Not a test: a throughput measurement of the load-time transform over a real jar.
 * Skipped unless asked for:
 * <pre>
 * ./gradlew :agent:test --tests '*TransformThroughputBench*' \
 *     -Preclazz.bench.jar=/path/to/spring-context.jar -Preclazz.bench.out=/tmp/bench.txt
 * </pre>
 */
class TransformThroughputBench {

    @Test
    @EnabledIfSystemProperty(named = "reclazz.bench.jar", matches = ".+")
    void measure() throws Exception {
        Path jar = Path.of(System.getProperty("reclazz.bench.jar"));
        Path out = Path.of(System.getProperty("reclazz.bench.out"));
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
        StringBuilder sb = new StringBuilder();
        sb.append(stages(classes, jar)).append('\n');
        for (int round = 0; round < 3; round++) {
            sb.append(runRound(classes, jar, round)).append('\n');
        }
        Files.writeString(out, sb.toString());
    }

    private String stages(Map<String, byte[]> classes, Path jar) throws Exception {
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);
        TransformContext context = new TransformContext();
        classes.keySet().forEach(context::addWatched);
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));
        java.util.List<byte[]> outputs = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int warm = 0; warm < 3; warm++) {
            outputs.clear();
            long tAnn = 0, tSv = 0, tDo = 0;
            for (var e : classes.entrySet()) {
                byte[] b = e.getValue();
                if ((new ClassReader(b).getAccess() & Opcodes.ACC_INTERFACE) != 0) continue;
                long t0 = System.nanoTime();
                AnnotationSignatures.of(b);
                long t1 = System.nanoTime();
                SerialVersionUid.forInjection(b);
                long t2 = System.nanoTime();
                try { outputs.add(transformer.doTransform(e.getKey(), b, loader)); } catch (Throwable ignored) {}
                long t3 = System.nanoTime();
                tAnn += t1 - t0; tSv += t2 - t1; tDo += t3 - t2;
            }
            long raw = 0;
            for (byte[] o : outputs) raw += o.length;
            sb.append(String.format("warm=%d annSig=%dms svuid=%dms doTransform=%dms rawBytes=%d",
                    warm, tAnn / 1_000_000, tSv / 1_000_000, tDo / 1_000_000, raw));
            for (int level : new int[]{1, 3, 6}) {
                long t0 = System.nanoTime(); long total = 0;
                for (byte[] o : outputs) total += deflate(o, level).length;
                sb.append(String.format(" | L%d=%dms/%d", level, (System.nanoTime() - t0) / 1_000_000, total));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static byte[] deflate(byte[] bytes, int level) {
        java.util.zip.Deflater d = new java.util.zip.Deflater(level);
        try {
            d.setInput(bytes); d.finish();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(bytes.length / 3 + 32);
            byte[] buf = new byte[8192];
            while (!d.finished()) out.write(buf, 0, d.deflate(buf));
            return out.toByteArray();
        } finally { d.end(); }
    }

    private String runRound(Map<String, byte[]> classes, Path jar, int round) throws Exception {
        TransformContext context = new TransformContext();
        classes.keySet().forEach(context::addWatched);
        AgentConfig config = AgentConfig.parse(null);
        ReclazzTransformer transformer = new ReclazzTransformer(context, config);
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);

        long tAlready = 0, tPeek = 0, tDo = 0, tValid = 0, tCache = 0, tFull = 0;
        int n = 0, transformed = 0;
        for (var e : classes.entrySet()) {
            byte[] bytes = e.getValue();
            long t0 = System.nanoTime();
            ReclazzTransformer.isAlreadyTransformed(bytes);
            long t1 = System.nanoTime();
            boolean iface = (new ClassReader(bytes).getAccess() & Opcodes.ACC_INTERFACE) != 0;
            long t2 = System.nanoTime();
            tAlready += t1 - t0; tPeek += t2 - t1;
            if (iface) continue;
            byte[] result;
            try {
                result = transformer.doTransform(e.getKey(), bytes, loader);
            } catch (Throwable t) {
                continue;
            }
            long t3 = System.nanoTime();
            try {
                new ClassReader(result).accept(new CheckClassAdapter(new ClassNode(), true), ClassReader.SKIP_DEBUG);
            } catch (Throwable ignored) {
            }
            long t4 = System.nanoTime();
            TransformedClassCache.put(e.getKey(), result);
            long t5 = System.nanoTime();
            tDo += t3 - t2; tValid += t4 - t3; tCache += t5 - t4;
            n++;
        }
        // Full pipeline as the JVM would drive it, fresh context.
        TransformContext ctx2 = new TransformContext();
        classes.keySet().forEach(ctx2::addWatched);
        ReclazzTransformer full = new ReclazzTransformer(ctx2, config);
        long f0 = System.nanoTime();
        for (var e : classes.entrySet()) {
            try {
                if (full.transform(loader, e.getKey(), null, null, e.getValue()) != null) transformed++;
            } catch (Throwable ignored) {
            }
        }
        tFull = System.nanoTime() - f0;
        return String.format("round=%d classes=%d nonInterface=%d transformed=%d | already=%dms peek=%dms doTransform=%dms checkClass=%dms cachePut=%dms | fullPipeline=%dms",
                round, classes.size(), n, transformed,
                tAlready / 1_000_000, tPeek / 1_000_000, tDo / 1_000_000, tValid / 1_000_000, tCache / 1_000_000, tFull / 1_000_000);
    }
}
