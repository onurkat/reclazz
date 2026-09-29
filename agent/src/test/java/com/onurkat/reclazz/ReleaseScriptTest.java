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
import java.util.Map;
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
        for (String script : List.of("release.sh", "changelog-section.sh", "release-checks.sh", "release-evidence.py", "release-publish.init.gradle", "test-release-evidence.py")) {
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
        Files.createDirectories(repo.resolve("bin"));
        Path mvn = repo.resolve("bin/mvn");
        Files.writeString(mvn, "#!/bin/sh\nexit 97\n"); // discovery only; dry-run must never execute it
        Files.setPosixFilePermissions(mvn, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Files.writeString(repo.resolve(".gitignore"), "build/\ntarget/\n.home/\n__pycache__/\n");
        Files.createDirectories(repo.resolve("maven-plugin"));
        Files.writeString(repo.resolve("maven-plugin/pom.xml"), "<project><version>1.2.3</version></project>\n");
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
        String commit = git("rev-parse", "HEAD").out().trim();
        assertEquals(List.of(
                "would run: bash scripts/release-checks.sh",
                "would run: ./gradlew signPlugin verifyPluginSignature --no-daemon",
                "would run: ./gradlew :agent:centralBundle :spring-boot-starter:centralBundle --no-daemon",
                "would run: mvn -q -f maven-plugin/pom.xml -Prelease clean deploy -DskipPublishing=true",
                "would run: ./gradlew :gradle-plugin:publishPlugins --validate-only --no-daemon",
                "would run: python3 scripts/release-evidence.py create 1.2.3 " + commit + " distribution",
                "would run: python3 scripts/release-evidence.py verify",
                "would run: ./gradlew -I scripts/release-publish.init.gradle :publishPlugin --no-daemon --no-configuration-cache",
                "would run: python3 scripts/release-evidence.py verify",
                "would run: python3 scripts/release-evidence.py stage-maven",
                "would run: python3 scripts/release-evidence.py verify",
                "would run: ./gradlew -I scripts/release-publish.init.gradle :gradle-plugin:publishPlugins --no-daemon --no-configuration-cache",
                "would run: python3 scripts/release-evidence.py verify",
                "would run: git tag -a v1.2.3 " + commit + " -m Reclazz 1.2.3",
                "would run: python3 scripts/release-evidence.py verify",
                "would run: git push origin " + commit + ":refs/heads/main refs/tags/v1.2.3",
                "would wait for: gh release view v1.2.3",
                "would run: python3 scripts/release-evidence.py verify",
                "would run: gh release upload v1.2.3 build/release-gate/assets/reclazz-1.2.3.zip"), steps);
        assertFalse(Files.exists(repo.resolve("build")), "dry run creates no gate/artifacts");
        assertEquals("", git("tag", "-l").out().trim(), "a dry run tags nothing");
    }

    @Test
    void skippingTheMarketplaceLeavesOutOnlyTheUpload() throws Exception {
        Run run = release("1.2.3", "--dry-run", "--skip-publish");
        assertEquals(0, run.exit(), run.err());
        assertFalse(run.out().lines().anyMatch(l -> l.contains(" :publishPlugin --no-daemon")));
        assertTrue(run.out().contains(" :gradle-plugin:publishPlugins --no-daemon"),
                "skipping Marketplace must preserve the Gradle Plugin Portal upload");
        assertTrue(run.out().contains("signPlugin"), "the signed zip is still built for the GitHub release");
    }

    @Test
    void skippingDistributionPreservesMarketplacePublication() throws Exception {
        Run run = release("1.2.3", "--dry-run", "--skip-distribution");
        assertEquals(0, run.exit(), run.err());
        assertTrue(run.out().lines().anyMatch(l -> l.contains(" :publishPlugin --no-daemon")));
        assertFalse(run.out().contains("centralBundle"));
        assertFalse(run.out().contains("mvn -q"));
        assertFalse(run.out().contains(":gradle-plugin:publishPlugins"));
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

    @Test
    void everyPreparationFailurePreventsAllPublications() throws Exception {
        publicationStubs();
        for (String stage : List.of("build", "npm", "verify", "signPlugin", "centralBundle", "deploy", "validate-only")) {
            Files.deleteIfExists(repo.resolve("build/trace"));
            Run result = releaseWith(Map.of("RELEASE_FAIL", stage), "1.2.3");
            assertNotEquals(0, result.exit(), stage + ": " + result.out());
            assertFalse(trace().contains("UPLOAD"), stage + ": " + trace());
        }
    }

    @Test
    void missingDistributionCredentialFailsBeforePreparation() throws Exception {
        publicationStubs();
        Run result = releaseWith(Map.of("RECLAZZ_CENTRAL_TOKEN", ""), "1.2.3");
        assertNotEquals(0, result.exit());
        assertTrue(result.err().contains("RECLAZZ_CENTRAL_TOKEN is required"), result.err());
        assertEquals("", trace());
    }

    @Test
    void changedSourceDuringPreparationPreventsAllPublications() throws Exception {
        publicationStubs();
        Run result = releaseWith(Map.of("RELEASE_DRIFT", "source"), "1.2.3");
        assertNotEquals(0, result.exit(), result.out());
        assertTrue(result.err().contains("source changed"), result.err());
        assertFalse(trace().contains("UPLOAD"), trace());
    }

    @Test
    void movedHeadDuringPreparationPreventsAllPublications() throws Exception {
        publicationStubs();
        Run result = releaseWith(Map.of("RELEASE_DRIFT", "head"), "1.2.3");
        assertNotEquals(0, result.exit(), result.out());
        assertTrue(result.err().contains("source changed"), result.err());
        assertFalse(trace().contains("UPLOAD"), trace());
    }

    @Test
    void missingSignedArtifactPreventsAllPublications() throws Exception {
        publicationStubs();
        Run result = releaseWith(Map.of("RELEASE_DRIFT", "missing"), "1.2.3");
        assertNotEquals(0, result.exit(), result.out());
        assertFalse(trace().contains("UPLOAD"), trace());
    }

    @Test
    void changedArtifactBeforeGithubUploadStopsThatUpload() throws Exception {
        publicationStubs();
        Run result = releaseWith(Map.of("RELEASE_DRIFT", "artifact"), "1.2.3");
        assertNotEquals(0, result.exit(), result.out());
        assertTrue(result.err().contains("artifact changed"), result.err());
        assertFalse(trace().contains("UPLOAD gh"), trace());
    }

    @Test
    void successfulPublicationUsesCheckedBytesAfterEveryPreparation() throws Exception {
        publicationStubs();
        Run result = releaseWith(Map.of(), "1.2.3");
        assertEquals(0, result.exit(), result.out() + result.err());
        List<String> lines = trace().lines().toList();
        int firstUpload = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("UPLOAD")) { firstUpload = i; break; }
        }
        assertTrue(firstUpload > 0, trace());
        assertTrue(lines.subList(0, firstUpload).stream().anyMatch(l -> l.contains("-DskipPublishing=true")), trace());
        assertTrue(lines.subList(0, firstUpload).stream().anyMatch(l -> l.contains("verifyPluginSignature")), trace());
        assertTrue(lines.subList(0, firstUpload).stream().anyMatch(l -> l.equals("PREP npm test")), trace());
        assertFalse(lines.subList(firstUpload, lines.size()).stream().anyMatch(l -> l.startsWith("PREP")), trace());
        assertEquals(6, lines.stream().filter(l -> l.startsWith("UPLOAD")).count(), trace());
        assertEquals(Files.readString(repo.resolve("build/distributions/reclazz-1.2.3-signed.zip")),
                Files.readString(repo.resolve("build/uploaded.zip")), "GitHub receives the checked signed ZIP");
        assertEquals("", git("tag", "-l").out().trim(), "publication commands are stubs");
    }

    @Test
    void receiptAndHttpBoundaryTestsRunWithoutPublication() throws Exception {
        Run result = run(List.of("python3", "scripts/test-release-evidence.py"));
        assertEquals(0, result.exit(), result.out() + result.err());
        assertTrue(result.err().contains("Ran 10 tests"), result.err());
    }

    @Test
    void tagWorkflowCannotCreateAReleaseWhenItsGateFails() throws Exception {
        publicationStubs();
        Path root = AgentSources.root().getParent().getParent().getParent().getParent();
        String workflow = Files.readString(root.resolve(".github/workflows/release.yml"));
        String check = workflowRun(workflow, "Test and package before creating any release");
        String publish = workflowRun(workflow, "Create the release").replace("/tmp/", "build/notes/");
        Files.createDirectories(repo.resolve("build/notes"));
        Files.writeString(repo.resolve("build/notes/notes.md"), "Release notes\n");
        for (String failure : List.of("build", "npm", "verify", "")) {
            Files.deleteIfExists(repo.resolve("build/trace"));
            Run result = run(List.of("bash", "-e", "-c", check + "\n" + publish),
                    Map.of("RELEASE_FAIL", failure, "GITHUB_REF_NAME", "v1.2.3"));
            if (failure.isEmpty()) {
                assertEquals(0, result.exit(), result.out() + result.err());
                assertTrue(trace().contains("UPLOAD gh release create"), trace());
            } else {
                assertNotEquals(0, result.exit(), result.out());
                assertFalse(trace().contains("UPLOAD"), trace());
            }
        }
    }

    private String workflowRun(String workflow, String step) {
        String section = workflow.substring(workflow.indexOf("      - name: " + step));
        section = section.substring(section.indexOf("        run: |\n") + "        run: |\n".length());
        StringBuilder script = new StringBuilder();
        for (String line : section.split("\n")) {
            if (!line.isBlank() && !line.startsWith("          ")) break;
            script.append(line.isBlank() ? "" : line.substring(10)).append('\n');
        }
        return script.toString();
    }

    private String trace() throws IOException {
        Path path = repo.resolve("build/trace");
        return Files.exists(path) ? Files.readString(path) : "";
    }

    private void executable(String path, String text) throws IOException {
        Path file = repo.resolve(path);
        Files.writeString(file, text);
        Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
    }

    private void publicationStubs() throws Exception {
        String realGit = run(List.of("sh", "-c", "command -v git")).out().trim();
        String realPython = run(List.of("sh", "-c", "command -v python3")).out().trim();
        Files.createDirectories(repo.resolve(".home/.gradle"));
        Files.writeString(repo.resolve(".home/.gradle/gradle.properties"),
                "signing.keyId=test\ngradle.publish.key=test\ngradle.publish.secret=test\n");
        Files.writeString(repo.resolve("scripts/fixture-artifacts.py"), """
                from pathlib import Path
                import zipfile
                def write(name):
                    p = Path(name)
                    p.parent.mkdir(parents=True, exist_ok=True)
                    p.write_text('checked:' + name)
                for name in ['agent/build/libs/agent-1.2.3.jar',
                    'mcp-server/build/distributions/mcp/reclazz-mcp-1.2.3.jar',
                    'build/distributions/reclazz-1.2.3-signed.zip',
                    'gradle-plugin/build/libs/gradle-plugin-1.2.3.jar',
                    'gradle-plugin/build/publications/pluginMaven/pom-default.xml',
                    'gradle-plugin/build/publications/pluginMaven/module.json']:
                    write(name)
                for module, artifact in [('agent', 'reclazz-agent'),
                        ('spring-boot-starter', 'reclazz-spring-boot-starter'), ('maven-plugin', 'reclazz-maven-plugin')]:
                    name = f'{module}/build/{artifact}-1.2.3-central-bundle.zip' if module != 'maven-plugin' else 'maven-plugin/target/central-publishing/central-bundle.zip'
                    write(name)
                    with zipfile.ZipFile(name, 'w') as archive:
                        for suffix in ['.jar', '-sources.jar', '-javadoc.jar', '.pom']:
                            for ending in [suffix, suffix + '.asc']:
                                archive.writestr(f'com/onurkat/reclazz/{artifact}/1.2.3/{artifact}-1.2.3{ending}', ending)
                """);
        executable("gradlew", """
                #!/usr/bin/env bash
                set -eu
                mkdir -p build
                if [[ "$*" == *release-publish.init.gradle* ]]; then
                    python3 scripts/release-evidence.py verify
                    echo "UPLOAD gradle $*" >> build/trace
                    exit 0
                fi
                echo "PREP gradle $*" >> build/trace
                [[ -z "${RELEASE_FAIL:-}" || "$*" != *"$RELEASE_FAIL"* ]] || exit 29
                python3 scripts/fixture-artifacts.py
                if [[ "$*" == *validate-only* ]]; then
                    case "${RELEASE_DRIFT:-}" in
                        source) echo '# changed' >> gradle.properties ;;
                        head) git commit --allow-empty -qm 'changed HEAD' ;;
                        missing) rm build/distributions/reclazz-1.2.3-signed.zip ;;
                    esac
                fi
                """);
        executable("bin/mvn", """
                #!/usr/bin/env bash
                set -eu
                echo "PREP mvn $*" >> build/trace
                [[ -z "${RELEASE_FAIL:-}" || "$*" != *"$RELEASE_FAIL"* ]] || exit 29
                """);
        executable("bin/npm", """
                #!/usr/bin/env bash
                set -eu
                echo "PREP npm ${*: -1}" >> build/trace
                [[ "${RELEASE_FAIL:-}" != npm ]] || exit 29
                """);
        executable("bin/gh", """
                #!/usr/bin/env bash
                set -eu
                if [[ "$*" == *'release view'* ]]; then
                    if [[ "${RELEASE_DRIFT:-}" == artifact ]]; then
                        echo 'modified' >> build/distributions/reclazz-1.2.3-signed.zip
                    fi
                    exit 0
                fi
                cp "${*: -1}" build/uploaded.zip
                echo "UPLOAD gh $*" >> build/trace
                """);
        executable("bin/git", """
                #!/usr/bin/env bash
                set -eu
                if [[ "${RELEASE_STUBS:-}" == 1 && ( "$*" == 'tag -a '* || "$*" == 'push '* ) ]]; then
                    echo "UPLOAD git $*" >> build/trace
                    exit 0
                fi
                exec '%s' "$@"
                """.formatted(realGit));
        executable("bin/python3", """
                #!/usr/bin/env bash
                set -eu
                if [[ "$*" == 'scripts/release-evidence.py stage-maven' ]]; then
                    '%s' scripts/release-evidence.py verify
                    echo 'UPLOAD maven checked bundle' >> build/trace
                    exit 0
                fi
                exec '%s' "$@"
                """.formatted(realPython, realPython));
        git("add", "-A");
        git("commit", "-qm", "offline publication stubs");
    }

    private Run releaseWith(Map<String, String> extra, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("bash", repo.resolve("scripts/release.sh").toString()));
        command.addAll(List.of(args));
        Map<String, String> env = new java.util.HashMap<>();
        env.put("HOME", repo.resolve(".home").toString());
        env.put("RELEASE_STUBS", "1");
        env.put("RECLAZZ_SIGNING_PASSWORD", "test");
        env.put("RECLAZZ_PUBLISH_TOKEN", "test");
        env.put("RECLAZZ_CENTRAL_TOKEN", "test");
        env.putAll(extra);
        return run(command, env);
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
        return run(command, Map.of());
    }

    private Run run(List<String> command, Map<String, String> environment) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(repo.toFile());
        builder.environment().putAll(environment);
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        builder.environment().put("PATH", repo.resolve("bin") + java.io.File.pathSeparator
                + builder.environment().getOrDefault("PATH", ""));
        Process p = builder.start();
        byte[] out = p.getInputStream().readAllBytes();
        byte[] err = p.getErrorStream().readAllBytes();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "timed out: " + command);
        return new Run(p.exitValue(), new String(out, StandardCharsets.UTF_8), new String(err, StandardCharsets.UTF_8));
    }
}
