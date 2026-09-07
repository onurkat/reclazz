/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.watcher;

import com.onurkat.reclazz.agent.AgentConfig;
import com.onurkat.reclazz.platform.PlatformContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Not a test: what the watcher keeps per class file after its baseline
 * walk, as heap. Run with -Preclazz.bench.out=... (and optionally
 * -Preclazz.bench.files=N).
 */
class WatcherFootprintBench {

    @TempDir
    Path dir;

    @Test
    @EnabledIfSystemProperty(named = "reclazz.bench.out", matches = ".+")
    void measure() throws Exception {
        int files = Integer.getInteger("reclazz.bench.files", 20_000);
        byte[] payload = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 61};
        for (int i = 0; i < files; i++) {
            Path pkg = dir.resolve("p" + (i % 200));
            Files.createDirectories(pkg);
            Files.write(pkg.resolve("C" + i + ".class"), payload);
        }
        FileWatcher watcher = new FileWatcher(new NoopPlatformContext(), AgentConfig.parse(null));
        long before = usedAfterGc();
        int counted = watcher.prepopulateContentHashes(dir, null);
        long after = usedAfterGc();
        String line = String.format("files=%d hashed=%d heapDelta=%dKB (%d bytes/file)%n",
                files, counted, (after - before) / 1024, (after - before) / Math.max(1, counted));
        Files.writeString(Path.of(System.getProperty("reclazz.bench.out")), line);
        System.out.println(line);
        if (watcher.watchedFileCount() < -1) throw new IllegalStateException();
    }

    private static long usedAfterGc() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(100);
        }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static final class NoopPlatformContext implements PlatformContext {
        @Override public Platform getPlatformId() { return Platform.GENERIC; }
        @Override public void initialize() { }
        @Override public Map<String, List<Path>> getClassOutputDirs() { return Map.of(); }
        @Override public Map<String, List<Path>> getSourceDirs() { return Map.of(); }
        @Override public Map<String, List<Path>> getResourceDirs() { return Map.of(); }
        @Override public String resolveClasspath() { return ""; }
        @Override public String resolveClassName(Path classFile) { return null; }
        @Override public Path resolveOutputDir(Path classFile) { return null; }
        @Override public Object getApplicationContext() { return null; }
    }
}
