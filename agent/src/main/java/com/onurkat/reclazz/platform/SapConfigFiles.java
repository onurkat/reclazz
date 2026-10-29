/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import java.nio.file.Path;

/** Free file classification, independent of platform property application. */
public final class SapConfigFiles {
    private SapConfigFiles() { }

    public static boolean isPlatformConfiguration(Path file) {
        if (file == null || file.getFileName() == null) return false;

        String name = file.getFileName().toString();
        if (name.equals("local.properties") || name.equals("project.properties")) {
            return true;
        }
        if (!name.endsWith(".properties")) return false;

        Path parent = file.getParent();
        return parent != null && parent.getFileName() != null
                && parent.getFileName().toString().equals("props");
    }
}
