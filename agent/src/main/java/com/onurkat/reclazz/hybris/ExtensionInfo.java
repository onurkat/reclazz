/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents an SAP Commerce extension with its metadata.
 */
public class ExtensionInfo {

    private final String name;
    private final Path path;
    private final List<String> requiredExtensions;
    private final boolean hasCoreModule;
    private final boolean hasWebModule;
    private final boolean isCustom;

    public ExtensionInfo(String name, Path path, List<String> requiredExtensions,
                         boolean hasCoreModule, boolean hasWebModule, boolean isCustom) {
        this.name = name;
        this.path = path;
        this.requiredExtensions = requiredExtensions != null ? requiredExtensions : new ArrayList<>();
        this.hasCoreModule = hasCoreModule;
        this.hasWebModule = hasWebModule;
        this.isCustom = isCustom;
    }

    public String getName() { return name; }
    public Path getPath() { return path; }
    public List<String> getRequiredExtensions() { return requiredExtensions; }
    public boolean hasCoreModule() { return hasCoreModule; }
    public boolean hasWebModule() { return hasWebModule; }
    public boolean isCustom() { return isCustom; }

    @Override
    public String toString() {
        return name + " @ " + path +
                (isCustom ? " [custom]" : " [platform]") +
                (hasWebModule ? " [web]" : "");
    }
}
