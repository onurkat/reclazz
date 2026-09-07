/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One command prepares the three edits a release commit is made of, the
 * same way every time: the version, the dated changelog section with a
 * fresh Unreleased above it, and the change-notes block the IDE shows.
 */
@DisabledOnOs(OS.WINDOWS)
class BumpVersionScriptTest {

    @TempDir
    Path repo;

    @BeforeEach
    void aRepositoryShapedLikeThisOne() throws Exception {
        Path root = AgentSources.root().getParent().getParent().getParent().getParent();
        Files.createDirectories(repo.resolve("scripts"));
        for (String script : List.of("bump-version.sh", "changelog-section.sh")) {
            Path copy = repo.resolve("scripts").resolve(script);
            Files.copy(root.resolve("scripts").resolve(script), copy, StandardCopyOption.REPLACE_EXISTING);
            Files.setPosixFilePermissions(copy, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        }
        Files.writeString(repo.resolve("gradle.properties"), "pluginVersion=1.1.1\nother=x\n");
        Files.writeString(repo.resolve("CHANGELOG.md"), """
                # Changelog

                ## [Unreleased]

                ### Fixed

                - **Breakpoints bind in reloaded code.** Details here.

                ### Changed

                - **The agent's every-class scan reads the constant pool.** More.
                - A small thing without a headline.

                ## [1.1.1] - 2026-09-07

                - Older.
                """);
        Files.createDirectories(repo.resolve("src/main/resources/META-INF"));
        Files.writeString(repo.resolve("src/main/resources/META-INF/plugin.xml"), """
                <idea-plugin>
                    <change-notes><![CDATA[
                        <h3>1.1.1</h3>
                        <ul><li>Old notes.</li></ul>
                    ]]></change-notes>
                </idea-plugin>
                """);
    }

    @Test
    void theThreeEditsAreMadeTogether() throws Exception {
        Run run = bump("1.2.0");
        assertEquals(0, run.exit(), run.err());

        assertEquals("pluginVersion=1.2.0\nother=x\n", Files.readString(repo.resolve("gradle.properties")));

        String changelog = Files.readString(repo.resolve("CHANGELOG.md"));
        assertTrue(changelog.contains("## [Unreleased]\n\n## [1.2.0] - 2026-09-08\n\n### Fixed"),
                "Unreleased becomes the dated section with a fresh Unreleased above it:\n" + changelog);
        assertTrue(changelog.contains("## [1.1.1] - 2026-09-07"), "older sections untouched");

        String notes = Files.readString(repo.resolve("src/main/resources/META-INF/plugin.xml"));
        int at120 = notes.indexOf("<h3>1.2.0</h3>");
        int at111 = notes.indexOf("<h3>1.1.1</h3>");
        assertTrue(at120 > 0 && at120 < at111, "the new block goes on top:\n" + notes);
        assertTrue(notes.contains("<li>Breakpoints bind in reloaded code.</li>"), notes);
        assertTrue(notes.contains("<li>The agent's every-class scan reads the constant pool.</li>"), notes);
        assertFalse(notes.contains("A small thing without a headline"), "only headlines seed the notes");
    }

    @Test
    void runningItTwiceForTheSameVersionIsRefused() throws Exception {
        assertEquals(0, bump("1.2.0").exit());
        Run again = bump("1.2.0");
        assertNotEquals(0, again.exit());
        assertTrue(again.err().contains("already has a section for 1.2.0"), again.err());
    }

    @Test
    void aMalformedVersionIsRefusedBeforeAnythingIsTouched() throws Exception {
        Run run = bump("2");
        assertNotEquals(0, run.exit());
        assertEquals("pluginVersion=1.1.1\nother=x\n", Files.readString(repo.resolve("gradle.properties")));
    }

    private record Run(int exit, String out, String err) {}

    private Run bump(String version) throws Exception {
        List<String> command = new ArrayList<>(List.of("bash", repo.resolve("scripts/bump-version.sh").toString(), version));
        ProcessBuilder pb = new ProcessBuilder(command).directory(repo.toFile());
        pb.environment().put("RECLAZZ_RELEASE_DATE", "2026-09-08");
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        byte[] err = p.getErrorStream().readAllBytes();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        return new Run(p.exitValue(), new String(out, StandardCharsets.UTF_8), new String(err, StandardCharsets.UTF_8));
    }
}
