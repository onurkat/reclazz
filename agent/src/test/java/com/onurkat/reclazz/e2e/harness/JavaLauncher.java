/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e.harness;

import java.nio.file.Files;
import java.nio.file.Path;

/** Resolve the Java launcher spelling without changing an existing configured path. */
public final class JavaLauncher {
    private JavaLauncher() { }
    public static Path resolve(Path configured) {
        Path name=configured.getFileName();
        if(!Files.isRegularFile(configured)&&name!=null&&name.toString().equalsIgnoreCase("java")) {
            Path windows=configured.resolveSibling("java.exe");
            if(Files.isRegularFile(windows))return windows;
        }
        return configured;
    }
}
