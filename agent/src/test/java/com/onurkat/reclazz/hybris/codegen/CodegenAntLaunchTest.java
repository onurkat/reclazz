/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris.codegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The platform's ant is started with the files SAP Commerce ships for the
 * operating system at hand: the .sh pair through bash on Unix, the .bat pair
 * through cmd.exe on Windows. It used to look for the Unix pair everywhere.
 */
class CodegenAntLaunchTest {

    @TempDir
    Path platform;

    @Test
    void onUnixTheShellPairIsUsedThroughBash() throws Exception {
        CodegenReloader.AntLaunch launch = CodegenReloader.AntLaunch.forPlatform(platform, false);
        assertEquals(platform.resolve("setantenv.sh"), launch.setEnv());
        assertEquals(platform.resolve("apache-ant/bin/ant"), launch.antBin());
        assertEquals(List.of("/bin/bash", "-c", "source ./setantenv.sh && ant build"), launch.command());
        assertFalse(launch.present());
        Files.createDirectories(platform.resolve("apache-ant/bin"));
        Files.writeString(platform.resolve("setantenv.sh"), "");
        Files.writeString(platform.resolve("apache-ant/bin/ant"), "");
        assertTrue(launch.present());
    }

    @Test
    void onWindowsTheBatchPairIsUsedThroughCmd() throws Exception {
        CodegenReloader.AntLaunch launch = CodegenReloader.AntLaunch.forPlatform(platform, true);
        assertEquals(platform.resolve("setantenv.bat"), launch.setEnv());
        assertEquals(platform.resolve("apache-ant/bin/ant.bat"), launch.antBin());
        assertEquals("cmd.exe", launch.command().get(0));
        assertTrue(launch.command().get(2).contains("call setantenv.bat"), launch.command().toString());
        assertTrue(launch.command().get(2).contains("ant build"));
        Files.createDirectories(platform.resolve("apache-ant/bin"));
        Files.writeString(platform.resolve("setantenv.bat"), "");
        Files.writeString(platform.resolve("apache-ant/bin/ant.bat"), "");
        assertTrue(launch.present(), "a Windows platform install is recognised as having ant");
        assertFalse(CodegenReloader.AntLaunch.forPlatform(platform, false).present(),
                "and the Unix pair is not what it has");
    }

    @Test
    void thePlatformPathIsNeverPartOfTheCommandLine() {
        Path odd = Path.of("/opt/hy bris$(rm -rf x)/platform");
        for (boolean windows : new boolean[]{false, true}) {
            for (String part : CodegenReloader.AntLaunch.forPlatform(odd, windows).command()) {
                assertFalse(part.contains("hy bris"), "the path goes on the process's working directory, not the shell: " + part);
            }
        }
    }
}
