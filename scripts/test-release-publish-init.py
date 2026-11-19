#!/usr/bin/env python3
"""Run the real publisher init guard against local fake publishers; no upload APIs."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
INIT = Path(os.environ.get("RECLAZZ_RELEASE_INIT_SCRIPT", ROOT / "scripts/release-publish.init.gradle"))
GRADLE = Path(os.environ.get("RECLAZZ_TEST_GRADLE", ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")))
EVIDENCE = os.environ.get("RECLAZZ_RELEASE_INIT_TEST_OUTPUT")


class PublisherPreflightTest(unittest.TestCase):
    def fixture(self, directory, reject=False):
        (directory / "scripts").mkdir()
        (directory / "gradle-plugin").mkdir()
        (directory / "settings.gradle").write_text("rootProject.name='publisher-guard-probe'\ninclude 'gradle-plugin'\n")
        (directory / "signed.zip").write_bytes(b"signed fixture")
        (directory / "unsigned.zip").write_bytes(b"unsigned fixture")
        (directory / "scripts/release-evidence.py").write_text(
            "import json,sys\nfrom pathlib import Path\n"
            "Path('verification.json').write_text(json.dumps(sys.argv[1:]))\n"
            + ("sys.exit(9)\n" if reject else "sys.exit(0)\n")
        )
        (directory / "build.gradle").write_text("""
            tasks.register('compileJava') { doLast { file('compiled').text='unexpected' } }
            tasks.register('signPlugin') {
                ext.signedArchiveFile = objects.fileProperty().fileValue(file('signed.zip'))
                doLast { file('signed-again').text='unexpected' }
            }
            tasks.register('publishPlugin') {
                ext.archiveFile = objects.fileProperty().fileValue(file('unsigned.zip'))
                dependsOn 'compileJava', 'signPlugin'
                doLast { file('publisher-action').text='local fake action' }
            }
        """)
        child = directory / "gradle-plugin"
        for name in ["artifact.jar", "pom.xml", "metadata.module"]:
            (child / name).write_text(name + " unchanged")
        (child / "build.gradle").write_text("""
            class ProbeArtifact { File file }
            class ProbePublication { List artifacts }
            class ProbePublications {
                ProbePublication value
                Object getByName(String name) { assert name == 'pluginMaven'; value }
            }
            class ProbePublishing { ProbePublications publications }
            extensions.add('publishing', new ProbePublishing(publications:
                new ProbePublications(value: new ProbePublication(artifacts:
                    [new ProbeArtifact(file: file('artifact.jar'))]))))
            tasks.register('generatePomFileForPluginMavenPublication') {
                ext.destination = file('pom.xml')
            }
            tasks.register('generateMetadataFileForPluginMavenPublication') {
                ext.outputFile = objects.fileProperty().fileValue(file('metadata.module'))
            }
            tasks.register('publishPlugins') {
                dependsOn rootProject.tasks.named('compileJava'), rootProject.tasks.named('signPlugin')
                doLast { rootProject.file('publisher-action').text='local fake action' }
            }
        """)

    def run_guard(self, task, reject=False):
        with tempfile.TemporaryDirectory(prefix="reclazz-publisher-guard-") as temp:
            directory = Path(temp)
            self.fixture(directory, reject)
            artifacts = [directory / name for name in ["signed.zip", "unsigned.zip", "gradle-plugin/artifact.jar", "gradle-plugin/pom.xml", "gradle-plugin/metadata.module"]]
            before = {p: p.read_bytes() for p in artifacts}
            command = (["cmd", "/c", str(GRADLE)] if os.name == "nt" else ["sh", str(GRADLE)])
            command += ["-p", str(directory), "-I", str(INIT), task, "--console=plain"]
            run = subprocess.run(command, capture_output=True, text=True, timeout=180)
            if EVIDENCE:
                output = Path(EVIDENCE)
                output.mkdir(parents=True, exist_ok=True)
                name = self._testMethodName
                (output / (name + ".log")).write_text(run.stdout + run.stderr)
                (output / (name + ".json")).write_text(json.dumps({"argv": command, "exit": run.returncode}, indent=2) + "\n")
            self.assertTrue((directory / "verification.json").exists(), "Publisher preflight never ran; see retained Gradle log")
            observed = json.loads((directory / "verification.json").read_text())
            self.assertEqual(observed[:2], ["verify-publisher", "marketplace" if task == "publishPlugin" else "portal"])
            expected = [directory / "signed.zip"] if task == "publishPlugin" else artifacts[2:]
            self.assertEqual([Path(name).resolve() for name in observed[2:]], [p.resolve() for p in expected])
            self.assertFalse((directory / "compiled").exists())
            self.assertFalse((directory / "signed-again").exists())
            self.assertTrue(all(p.read_bytes() == content for p, content in before.items()))
            self.assertEqual((directory / "publisher-action").exists(), not reject)
            self.assertEqual(run.returncode == 0, not reject, "Unexpected Gradle status; see retained log")

    def test_marketplace_verifies_signed_archive_before_local_action(self):
        self.run_guard("publishPlugin")

    def test_rejected_verification_prevents_local_publisher_action(self):
        self.run_guard("publishPlugin", reject=True)

    def test_portal_verifies_publication_artifacts_before_local_action(self):
        self.run_guard(":gradle-plugin:publishPlugins")


if __name__ == "__main__":
    unittest.main()
