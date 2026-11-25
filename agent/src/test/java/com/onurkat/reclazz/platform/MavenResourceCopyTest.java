/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Properties;
import java.util.Set;

import static com.onurkat.reclazz.platform.MavenResourceCopy.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

class MavenResourceCopyTest {
    @TempDir Path dir;
    Path source, output;
    final Properties settings = new Properties();
    int roots;

    @BeforeEach void prepare() throws Exception {
        source = Files.createDirectory(dir.resolve("resources")).toRealPath();
        output = Files.createDirectory(dir.resolve("classes")).toRealPath();
        settings.setProperty("version", "3.3.1");
        settings.setProperty("output", output.toString());
        settings.setProperty("overwrite", "true");
        root(source, "", false, ".*", "");
    }

    void root(Path path, String target, boolean filtered, String include, String exclude) {
        String prefix = "root." + roots++ + ".";
        settings.setProperty(prefix + "path", path.toString());
        settings.setProperty(prefix + "target", target);
        settings.setProperty(prefix + "filtered", Boolean.toString(filtered));
        settings.setProperty(prefix + "include.count", "1");
        settings.setProperty(prefix + "include.0", include);
        settings.setProperty(prefix + "exclude.count", "1");
        settings.setProperty(prefix + "exclude.0", exclude);
    }

    MavenResourceCopy launch() throws Exception {
        settings.setProperty("roots", Integer.toString(roots));
        Path file = dir.resolve("manifest.properties");
        try (var writer = Files.newBufferedWriter(file)) { settings.store(writer, null); }
        return MavenResourceCopy.load(file, Set.of(output));
    }

    @Test void copiesSelectedBytesAndRetainsOutputOnDeletion() throws Exception {
        settings.setProperty("root.0.target", "assets");
        var copy = launch();
        Path file = source.resolve("new.bin"), target = output.resolve("assets/new.bin");
        byte[] bytes = {0, 10, -1, 9};
        Files.write(file, bytes);
        assertEquals(COPIED, copy.saved(file, false));
        assertArrayEquals(bytes, Files.readAllBytes(target));
        Files.delete(file);
        assertEquals(DELETED, copy.saved(file, true));
        assertArrayEquals(bytes, Files.readAllBytes(target));
    }

    @Test void preservesExternallyChangedOutputAfterSuccessfulCopy() throws Exception {
        Path file = Files.writeString(source.resolve("page.html"), "at launch");
        Path target = Files.copy(file, output.resolve("page.html"));
        var copy = launch();
        Files.writeString(file, "updated");
        assertEquals(COPIED, copy.saved(file, false));
        assertEquals("updated", Files.readString(target));
        Files.writeString(target, "external");
        Files.writeString(file, "second update");
        assertEquals(MANUAL, copy.saved(file, false));
        assertEquals("external", Files.readString(target));
    }

    @Test void declinesDestinationSharedWithAnEmptyRoot() throws Exception {
        Path other = Files.createDirectory(dir.resolve("other"));
        root(other, "shared", false, ".*", "");
        Path file = Files.createDirectories(source.resolve("shared")).resolve("page.html");
        Files.writeString(file, "new");
        assertEquals(MANUAL, launch().saved(file, false));
        assertFalse(Files.exists(output.resolve("shared/page.html")));
    }

    @Test void honorsFilteringAndExclusions() throws Exception {
        settings.setProperty("root.0.filtered", "true");
        settings.setProperty("root.0.exclude.0", "secret[.]txt");
        var copy = launch();
        Path template = Files.writeString(source.resolve("filtered.txt"), "${version}");
        Path excluded = Files.writeString(source.resolve("secret.txt"), "secret");
        assertEquals(MANUAL, copy.saved(template, false));
        assertEquals(EXCLUDED, copy.saved(excluded, false));
        assertFalse(Files.exists(output.resolve("filtered.txt")));
        assertFalse(Files.exists(output.resolve("secret.txt")));
    }

    @Test void parentTraversalCannotPublishOutsideTheOutput() throws Exception {
        Path file = Files.writeString(dir.resolve("outside.txt"), "outside");
        var copy = launch();
        assertEquals(MANUAL, copy.saved(source.resolve("../outside.txt"), false));
        assertEquals("outside", Files.readString(file));
    }

    @Test void outputSymlinkCannotRedirectCopy() throws Exception {
        Path outside = Files.createDirectory(dir.resolve("outside"));
        Files.createSymbolicLink(output.resolve("assets"), outside);
        settings.setProperty("root.0.target", "assets");
        Path file = Files.writeString(source.resolve("new.txt"), "new");
        assertEquals(MANUAL, launch().saved(file, false));
        assertFalse(Files.exists(outside.resolve("new.txt")));
    }

    @Test void preservesOutputWhenMavenWouldSkipNewerFile() throws Exception {
        settings.setProperty("overwrite", "false");
        Path file = Files.writeString(source.resolve("new.txt"), "original");
        Path target = Files.copy(file, output.resolve("new.txt"));
        var copy = launch();
        Files.writeString(file, "edited");
        Files.setLastModifiedTime(target, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000));
        assertEquals(UNCHANGED, copy.saved(file, false));
        assertEquals("original", Files.readString(target));
    }
}
