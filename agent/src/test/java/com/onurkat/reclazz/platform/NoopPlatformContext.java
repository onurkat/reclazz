/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A platform that knows nothing, for tests that drive a component directly
 * and do not want the detection, the directories or a Spring context.
 *
 * <p>One copy. It used to be nested in seven watcher tests, six of them
 * identical and one with an extra override, which is how a change to
 * {@link PlatformContext} became seven edits and a test that quietly kept
 * an older shape.
 */
public final class NoopPlatformContext implements PlatformContext {
    @Override public Platform getPlatformId() { return Platform.GENERIC; }
    @Override public void initialize() { }
    @Override public Map<String, List<Path>> getClassOutputDirs() { return Map.of(); }
    @Override public Map<String, List<Path>> getSourceDirs() { return Map.of(); }
    @Override public Map<String, List<Path>> getResourceDirs() { return Map.of(); }
    @Override public String resolveClasspath() { return ""; }
    @Override public String resolveClassName(Path classFile) { return null; }
    @Override public Path resolveOutputDir(Path classFile) { return null; }
    @Override public Object getApplicationContext() { return null; }
    @Override public List<Object> getAllApplicationContexts() { return new ArrayList<>(); }
}
