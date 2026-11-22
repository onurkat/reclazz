/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.util;

import com.onurkat.reclazz.e2e.harness.JavaLauncher;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JavaLauncherTest {
    @TempDir Path dir;
    @Test void missingExtensionlessLauncherSelectsExistingWindowsExecutable()throws Exception {
        Path bin=Files.createDirectories(dir.resolve("jdk home/bin"));
        Path exe=Files.writeString(bin.resolve("java.exe"),"synthetic launcher");
        assertEquals(exe,JavaLauncher.resolve(bin.resolve("java")));
    }
    @Test void existingExtensionlessLauncherKeepsItsConfiguredIdentity()throws Exception {
        Path java=Files.writeString(dir.resolve("java"),"unix launcher");
        Files.writeString(dir.resolve("java.exe"),"windows launcher");
        assertEquals(java,JavaLauncher.resolve(java));
    }
    @Test void explicitExeIsPreserved()throws Exception {
        Path exe=Files.writeString(dir.resolve("java.exe"),"configured launcher");
        assertEquals(exe,JavaLauncher.resolve(exe));
    }
    @Test void missingLauncherStaysMissingForTheExistingExecutableCheck() {
        Path missing=dir.resolve("java");assertEquals(missing,JavaLauncher.resolve(missing));assertFalse(Files.exists(JavaLauncher.resolve(missing)));
    }
    @Test void unrelatedLauncherNamesAreNotRewritten()throws Exception {
        Files.writeString(dir.resolve("custom.exe"),"not the requested path");
        Path custom=dir.resolve("custom");assertEquals(custom,JavaLauncher.resolve(custom));
    }
}
