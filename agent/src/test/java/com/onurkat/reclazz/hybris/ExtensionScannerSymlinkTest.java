/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import com.onurkat.reclazz.platform.ExtensionInfo;
import com.onurkat.reclazz.platform.ExtensionScanner;

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
 * dev setup, the extension lives in its own working copy) must be
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

    /**
     * The 2026-11-23 setup-wizard live run: two working copies whose extension directories link into each other formed a
     * cycle, and the scan stopped at it, so an extension visited after the cycle was reported "not found" and never watched.
     */
    @Test
    void aSymlinkCycleIsSkippedAndEveryOtherExtensionIsStillFound() throws IOException {
        java.util.List<String> names = java.util.List.of("aaaext", "mmmext", "zzzext");
        for (String name : names) {
            Path ext = tmp.resolve("workingcopy/" + name);
            writeExtension(ext, name);
            Files.createSymbolicLink(ext.resolve(name), ext); // a link back to its own directory, as the stray one was
            Files.createSymbolicLink(customDir.resolve(name), ext);
        }
        writeLocalExtensions(names.toArray(String[]::new));

        Map<String, ExtensionInfo> extensions = new ExtensionScanner(platformHome, configDir).scanExtensions();

        for (String name : names) assertNotNull(extensions.get(name), name + " must be found despite the cycles");
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
    @Test void constantsSourceRequiresExplicitPrefixAndCorePackage() throws Exception {
        Path ext=customDir.resolve("example");Files.createDirectories(ext);
        Files.writeString(ext.resolve("extensioninfo.xml"),"<extensioninfo><extension name='example' classprefix='Example'><coremodule packageroot='com.example'/></extension></extensioninfo>");
        writeLocalExtensions("example");
        var info=new ExtensionScanner(platformHome,configDir).scanExtensions().get("example");
        assertEquals(ext.resolve("src/com/example/constants/ExampleConstants.java").toAbsolutePath(),info.constantsSource().orElseThrow());
        Files.writeString(ext.resolve("extensioninfo.xml"),"<extensioninfo><extension name='example'><coremodule packageroot='com.example'/></extension></extensioninfo>");
        assertTrue(new ExtensionScanner(platformHome,configDir).scanExtensions().get("example").constantsSource().isEmpty());
        Files.writeString(ext.resolve("extensioninfo.xml"),"<extensioninfo><extension name='example' classprefix='Example'/></extensioninfo>");
        assertTrue(new ExtensionScanner(platformHome,configDir).scanExtensions().get("example").constantsSource().isEmpty());
    }
}
