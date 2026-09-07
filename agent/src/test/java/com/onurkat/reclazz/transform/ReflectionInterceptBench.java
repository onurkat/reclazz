/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Not a test: throughput of the every-class reflection intercept over a library corpus.
 * Skipped unless asked for; the corpus file lists one jar per line:
 * <pre>
 * ./gradlew :agent:test --tests '*ReflectionInterceptBench*' \
 *     -Preclazz.bench.corpus=/tmp/jars.txt -Preclazz.bench.out=/tmp/bench.txt
 * </pre>
 */
class ReflectionInterceptBench {

    record Cls(String name, byte[] bytes) {}

    @Test
    @EnabledIfSystemProperty(named = "reclazz.bench.corpus", matches = ".+")
    void measure() throws Exception {
        List<Cls> classes = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(System.getProperty("reclazz.bench.corpus")))) {
            if (line.isBlank()) continue;
            try (JarFile jf = new JarFile(line.trim())) {
                var en = jf.entries();
                while (en.hasMoreElements()) {
                    JarEntry e = en.nextElement();
                    if (!e.getName().endsWith(".class") || e.getName().contains("module-info") || e.getName().startsWith("META-INF")) continue;
                    try (InputStream is = jf.getInputStream(e)) {
                        classes.add(new Cls(e.getName().substring(0, e.getName().length() - 6), is.readAllBytes()));
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        long totalBytes = classes.stream().mapToLong(c -> c.bytes.length).sum();
        sb.append("classes=").append(classes.size()).append(" bytes=").append(totalBytes).append('\n');
        ReflectionInterceptTransformer t = new ReflectionInterceptTransformer();
        for (int round = 0; round < 4; round++) {
            long t0 = System.nanoTime(); int rewritten = 0;
            for (Cls c : classes) {
                if (t.transform(null, c.name, null, null, c.bytes) != null) rewritten++;
            }
            long t1 = System.nanoTime();
            int cpHits = 0;
            for (Cls c : classes) if (ReflectionInterceptTransformer.constantPoolMentionsTarget(new ClassReader(c.bytes))) cpHits++;
            long t2 = System.nanoTime();
            int rawHits = 0;
            for (Cls c : classes) if (contains(c.bytes, CLASS_UTF8)) rawHits++;
            long t3 = System.nanoTime();
            int readers = 0;
            for (Cls c : classes) { new ClassReader(c.bytes); readers++; }
            long t4 = System.nanoTime();
            sb.append(String.format("round=%d current=%dms rewritten=%d | cpWalk=%dms hits=%d | rawScan=%dms hits=%d | classReaderOnly=%dms%n",
                    round, (t1 - t0) / 1_000_000, rewritten, (t2 - t1) / 1_000_000, cpHits, (t3 - t2) / 1_000_000, rawHits, (t4 - t3) / 1_000_000));
        }
        // Verification variants on the watched-class output are measured elsewhere; here, on the raw
        // corpus, compare CheckClassAdapter with and without data flow.
        for (int round = 0; round < 2; round++) {
            long t0 = System.nanoTime();
            for (Cls c : classes) { try { new ClassReader(c.bytes).accept(new CheckClassAdapter(new ClassNode(), true), ClassReader.SKIP_DEBUG); } catch (Throwable ignored) {} }
            long t1 = System.nanoTime();
            for (Cls c : classes) { try { new ClassReader(c.bytes).accept(new CheckClassAdapter(new ClassNode(), false), ClassReader.SKIP_DEBUG); } catch (Throwable ignored) {} }
            long t2 = System.nanoTime();
            sb.append(String.format("verify round=%d dataflow=%dms structural=%dms%n", round, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000));
        }
        Files.writeString(Path.of(System.getProperty("reclazz.bench.out")), sb.toString());
    }

    static final byte[] CLASS_UTF8 = "java/lang/Class".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            if (hay[i] != needle[0]) continue;
            for (int j = 1; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

}
