/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.watcher;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.PlatformContext.Platform;
import com.onurkat.reclazz.platform.PlatformContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * With autoCompile the watcher follows sources, and Reclazz compiles them.
 * Model classes generated from items.xml are compiled by the platform's own
 * ant build into bootstrap/modelclasses, a directory nobody watched in that
 * mode: on a live SAP Commerce server a new attribute's getter never reached
 * the running model class, while the codegen step reported "Structural
 * reloader is applying new bytecode now".
 */
class GeneratedClassWatchTest {

    @TempDir Path tmp;

    @Test
    void autoCompileStillWatchesClassesThePlatformGenerates() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("ext/src"));
        Path generated = Files.createDirectories(tmp.resolve("bootstrap/modelclasses/acme/model"));
        Path generatedRoot = tmp.resolve("bootstrap/modelclasses");
        PlatformContext platform = new PlatformContext() {
            @Override public Platform getPlatformId() { return Platform.GENERIC; }
            @Override public void initialize() { }
            @Override public Map<String, List<Path>> getClassOutputDirs() { return Map.of(); }
            @Override public Map<String, List<Path>> getSourceDirs() { return Map.of("ext", List.of(src)); }
            @Override public Map<String, List<Path>> getResourceDirs() { return Map.of(); }
            @Override public Map<String, List<Path>> getGeneratedClassDirs() {
                return Map.of("__platform_bootstrap__", List.of(generatedRoot));
            }
            @Override public String resolveClasspath() { return ""; }
            @Override public String resolveClassName(Path classFile) { return null; }
            @Override public Path resolveOutputDir(Path classFile) { return null; }
            @Override public Object getApplicationContext() { return null; }
            @Override public List<Object> getAllApplicationContexts() { return new ArrayList<>(); }
        };
        FileWatcher watcher = new FileWatcher(platform,
                AgentConfig.parse("autoCompile=true,startupDelaySec=0,debounceMs=50"));
        List<Path> seen = new CopyOnWriteArrayList<>();
        watcher.onFileChanges(events -> events.forEach(e -> seen.add(e.getPath())));
        Thread thread = new Thread(watcher::startWatching);
        thread.start();
        try {
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (System.nanoTime() < deadline
                    && watcher.doctorEvidence().directories().stream().noneMatch(d -> d.contains("modelclasses"))) {
                Thread.sleep(50);
            }
            assertTrue(watcher.doctorEvidence().directories().stream().anyMatch(d -> d.contains("modelclasses")),
                    "generated class directory is watched: " + watcher.doctorEvidence().directories());

            Path model = Files.write(generated.resolve("ProbeModel.class"), new byte[]{1, 2, 3});
            watcher.scanNow();
            assertTrue(seen.contains(model), seen.toString());
        } finally {
            watcher.stopWatching();
            thread.join(2000);
        }
    }
}
