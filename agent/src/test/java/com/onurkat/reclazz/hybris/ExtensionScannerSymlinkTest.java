/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test: custom extensions symlinked into bin/custom (a common
 * dev setup — the extension lives in its own working copy) must be
 * discovered. Files.walk without FOLLOW_LINKS listed the symlink itself
 * but never descended into it, so the extension was silently invisible
 * ("Watching 0 directories" in autoCompile mode).
 */
class ExtensionScannerSymlinkTest {

    @TempDir
    Path tmp;

    private Path platformHome;
    private Path configDir;
    private Path customDir;

    @BeforeEach
    void setUp() throws IOException {
        platformHome = Files.createDirectories(tmp.resolve("hybris/bin/platform"));
        customDir = Files.createDirectories(tmp.resolve("hybris/bin/custom"));
        configDir = Files.createDirectories(tmp.resolve("hybris/config"));
    }

    private void writeExtension(Path extDir, String name) throws IOException {
        Files.createDirectories(extDir.resolve("src"));
        Files.writeString(extDir.resolve("extensioninfo.xml"), """
                <extensioninfo>
                    <extension classprefix="%s" name="%s"/>
                </extensioninfo>
                """.formatted(name, name));
    }

    private void writeLocalExtensions(String... names) throws IOException {
        StringBuilder sb = new StringBuilder("<hybrisconfig><extensions>");
        for (String n : names) {
            sb.append("<extension name='").append(n).append("'/>");
        }
        sb.append("</extensions></hybrisconfig>");
        Files.writeString(configDir.resolve("localextensions.xml"), sb.toString());
    }

    @Test
    void symlinkedCustomExtensionIsDiscovered() throws IOException {
        // Real extension lives OUTSIDE the hybris tree, only symlinked in.
        Path realExt = tmp.resolve("workingcopy/myext");
        writeExtension(realExt, "myext");
        Files.createSymbolicLink(customDir.resolve("myext"), realExt);
        writeLocalExtensions("myext");

        Map<String, ExtensionInfo> extensions =
                new ExtensionScanner(platformHome, configDir).scanExtensions();

        ExtensionInfo info = extensions.get("myext");
        assertNotNull(info, "symlinked extension must be discovered");
        assertTrue(Files.isDirectory(info.getPath().resolve("src")),
                "resolved path must reach the real src dir");
    }

    @Test
    void plainDirectoryExtensionStillDiscovered() throws IOException {
        Path ext = customDir.resolve("plainext");
        writeExtension(ext, "plainext");
        writeLocalExtensions("plainext");

        Map<String, ExtensionInfo> extensions =
                new ExtensionScanner(platformHome, configDir).scanExtensions();

        assertNotNull(extensions.get("plainext"));
    }

    @Test
    void symlinkedExtensionNotInLocalExtensionsIsStillScannedAsCustom() throws IOException {
        // scanCustomExtensions picks up bin/custom extensions even when
        // absent from localextensions.xml.
        Path realExt = tmp.resolve("workingcopy/otherext");
        writeExtension(realExt, "otherext");
        Files.createSymbolicLink(customDir.resolve("otherext"), realExt);
        writeLocalExtensions(); // empty

        Map<String, ExtensionInfo> extensions =
                new ExtensionScanner(platformHome, configDir).scanExtensions();

        assertNotNull(extensions.get("otherext"),
                "custom-dir scan must follow symlinks too");
    }
}
