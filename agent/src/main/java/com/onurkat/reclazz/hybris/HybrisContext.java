/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import com.onurkat.reclazz.ui.StatusReporter;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Discovers and holds the SAP Commerce (Hybris) platform context.
 *
 * Parses localextensions.xml and extensioninfo.xml files to build
 * the complete extension dependency graph.
 */
public class HybrisContext {

    private final Path hybrisHome;
    private Path platformHome;
    private Path configDir;
    private Map<String, ExtensionInfo> extensions = new LinkedHashMap<>();

    public HybrisContext(Path hybrisHome) {
        this.hybrisHome = hybrisHome;
    }

    public void initialize() throws IOException {
        // Resolve platform and config directories
        platformHome = hybrisHome.resolve("bin").resolve("platform");
        if (!Files.isDirectory(platformHome)) {
            // Try alternative layout (hybris/ prefix)
            platformHome = hybrisHome.resolve("hybris").resolve("bin").resolve("platform");
        }
        if (!Files.isDirectory(platformHome)) {
            throw new IOException("Cannot find platform directory. Expected at: " +
                    hybrisHome.resolve("bin").resolve("platform"));
        }

        configDir = hybrisHome.resolve("config");
        if (!Files.isDirectory(configDir)) {
            configDir = hybrisHome.resolve("hybris").resolve("config");
        }

        StatusReporter.info("Platform: " + platformHome);
        StatusReporter.info("Config: " + configDir);

        // Scan extensions
        ExtensionScanner scanner = new ExtensionScanner(platformHome, configDir);
        extensions = scanner.scanExtensions();
    }

    public Path getHybrisHome() { return hybrisHome; }
    public Path getPlatformHome() { return platformHome; }
    public Map<String, ExtensionInfo> getExtensions() { return extensions; }

}
