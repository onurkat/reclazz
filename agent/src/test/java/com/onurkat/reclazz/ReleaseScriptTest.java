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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The release script refuses what the release workflow would refuse later,
 * before anything is tagged or uploaded, and under --dry-run prints the
 * steps in the order docs/publishing.md prescribes. Run against a throwaway
 * repository shaped like this one.
 */
@DisabledOnOs(OS.WINDOWS)
class ReleaseScriptTest {

    @TempDir
    Path repo;

    private record Run(int exit, String out, String err) {}

    @BeforeEach
    void aRepositoryShapedLikeThisOne() throws Exception {
        Path root = AgentSources.root().getParent().getParent().getParent().getParent();   // java, main, src, agent
        Files.createDirectories(repo.resolve("scripts"));
        for (String script : List.of("release.sh", "changelog-section.sh")) {
            Path copy = repo.resolve("scripts").resolve(script);
            Files.copy(root.resolve("scripts").resolve(script), copy, StandardCopyOption.REPLACE_EXISTING);
            Files.setPosixFilePermissions(copy, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        }
        Files.writeString(repo.resolve("gradle.properties"), "pluginVersion=1.2.3\n");
        Files.writeString(repo.resolve("CHANGELOG.md"), "# Changelog\n\n## [1.2.3] - 2026-09-08\n\n- A thing.\n\n## [1.2.2] - 2026-09-01\n\n- Older.\n");
        Files.createDirectories(repo.resolve("src/main/resources/META-INF"));
        Files.writeString(repo.resolve("src/main/resources/META-INF/plugin.xml"),
                "<idea-plugin><change-notes><![CDATA[<h3>1.2.3</h3><ul><li>A thing.</li></ul>]]></change-notes></idea-plugin>\n");
        Files.writeString(repo.resolve("gradlew"), "#!/bin/sh\nexit 0\n");
        git("init", "-q", "-b", "main");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        git("add", "-A");
        git("commit", "-q", "-m", "Release-shaped fixture");
    }

    @Test
    void aDryRunListsTheStepsInOrderAndTouchesNothing() throws Exception {
        Run run = release("1.2.3", "--dry-run");
        assertEquals(0, run.exit(), run.err());
        List<String> steps = run.out().lines().filter(l -> l.startsWith("would ")).toList();
        assertEquals(List.of(
                "would run: ./gradlew verifyPlugin",
                "would run: ./gradlew signPlugin --no-daemon",
                "would run: ./gradlew publishPlugin --no-daemon",
                "would run: git tag -a v1.2.3 -m Reclazz 1.2.3",
                "would run: git push origin main --follow-tags",
                "would wait for: gh release view v1.2.3",
                "would run: cp build/distributions/reclazz-1.2.3-signed.zip /tmp/reclazz-1.2.3.zip",
                "would run: gh release upload v1.2.3 /tmp/reclazz-1.2.3.zip"), steps);
        assertEquals("", git("tag", "-l").out().trim(), "a dry run tags nothing");
    }

    @Test
    void skippingTheMarketplaceLeavesOutOnlyTheUpload() throws Exception {
        Run run = release("1.2.3", "--dry-run", "--skip-publish");
        assertEquals(0, run.exit(), run.err());
        assertFalse(run.out().contains("publishPlugin"));
        assertTrue(run.out().contains("signPlugin"), "the signed zip is still built for the GitHub release");
    }

    @Test
    void everyMissingPreconditionIsNamedAtOnce() throws Exception {
        Files.writeString(repo.resolve("gradle.properties"), "pluginVersion=1.2.2\n");   // and the tree is now dirty
        git("tag", "v1.2.3");
        Run run = release("1.2.3", "--dry-run");
        assertNotEquals(0, run.exit());
        String err = run.err();
        assertTrue(err.contains("not clean"), err);
        assertTrue(err.contains("pluginVersion=1.2.2, not 1.2.3"), err);
        assertTrue(err.contains("tag v1.2.3 already exists"), err);
        assertFalse(run.out().contains("would run"), "nothing runs when a precondition fails: " + run.out());
    }

    @Test
    void theTwoReleaseNoteFilesAreBothRequired() throws Exception {
        Files.writeString(repo.resolve("CHANGELOG.md"), "# Changelog\n\n## [1.2.2] - 2026-09-01\n\n- Older.\n");
        Files.writeString(repo.resolve("src/main/resources/META-INF/plugin.xml"),
                "<idea-plugin><change-notes><![CDATA[<h3>1.2.2</h3>]]></change-notes></idea-plugin>\n");
        git("commit", "-q", "-am", "notes for the previous version only");
        Run run = release("1.2.3", "--dry-run");
        assertNotEquals(0, run.exit());
        assertTrue(run.err().contains("CHANGELOG.md has no '## [1.2.3]' section"), run.err());
        assertTrue(run.err().contains("<h3>1.2.3</h3>"), run.err());
    }

    @Test
    void offMainIsRefused() throws Exception {
        git("checkout", "-q", "-b", "feature");
        Run run = release("1.2.3", "--dry-run");
        assertNotEquals(0, run.exit());
        assertTrue(run.err().contains("on branch 'feature'"), run.err());
    }

    private Run release(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("bash", repo.resolve("scripts/release.sh").toString()));
        command.addAll(List.of(args));
        return run(command);
    }

    private Run git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Run run = run(command);
        assertEquals(0, run.exit(), "git " + String.join(" ", args) + ": " + run.err());
        return run;
    }

    private Run run(List<String> command) throws Exception {
        Process p = new ProcessBuilder(command).directory(repo.toFile()).start();
        byte[] out = p.getInputStream().readAllBytes();
        byte[] err = p.getErrorStream().readAllBytes();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "timed out: " + command);
        return new Run(p.exitValue(), new String(out, StandardCharsets.UTF_8), new String(err, StandardCharsets.UTF_8));
    }
}
