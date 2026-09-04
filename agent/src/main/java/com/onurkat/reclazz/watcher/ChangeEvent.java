/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.watcher;

import java.nio.file.Path;

/**
 * Represents a file change event detected by the FileWatcher.
 */
public class ChangeEvent {

    public enum Type {
        CREATED,
        MODIFIED,
        DELETED
    }

    private final Path path;
    private final Type type;
    private final String moduleName;
    private final String sourceRoot; // "src", "web/src", "classes", "web/classes", "resources"

    public ChangeEvent(Path path, Type type, String moduleName, String sourceRoot) {
        this.path = path;
        this.type = type;
        this.moduleName = moduleName;
        this.sourceRoot = sourceRoot;
    }

    public Path getPath() { return path; }
    public Type getType() { return type; }
    public String getModuleName() { return moduleName; }
    @Override
    public String toString() {
        return type + " " + path + " [" + moduleName + "/" + sourceRoot + "]";
    }
}
