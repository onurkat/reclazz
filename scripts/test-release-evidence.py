#!/usr/bin/env python3
# Copyright 2026 Onur Kat
# SPDX-License-Identifier: Apache-2.0
"""Release gate tests without remote publication. The opt-in Gradle probe may resolve build dependencies."""
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import sys
sys.dont_write_bytecode = True
import unittest
from unittest.mock import patch
import urllib.error
import zipfile

SCRIPTS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('evidence', SCRIPTS / 'release-evidence.py')
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)


class GateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='reclazz gate ')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write('.gitignore', 'build/\ntarget/\n.gradle/\n__pycache__/\n')
        self.write('gradle.properties', 'pluginVersion=1.2.3\n')
        self.git('init', '-q', '-b', 'main')
        self.git('config', 'user.email', 'test@example.com')
        self.git('config', 'user.name', 'Test')
        self.git('add', '.')
        self.git('commit', '-qm', 'fixture')
        self.commit = self.git('rev-parse', 'HEAD')
        for name in ['agent/build/libs/agent-1.2.3.jar',
                     'mcp-server/build/distributions/mcp/reclazz-mcp-1.2.3.jar',
                     'build/distributions/reclazz-1.2.3-signed.zip']:
            self.write(name, 'checked:' + name)

    def git(self, *args):
        return evidence.git(self.root, *args)

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def capture(self, profile='local'):
        return evidence.create(self.root, '1.2.3', self.commit, profile)

    def distribution(self):
        for module, artifact, name in [
            ('agent', 'reclazz-agent', 'agent/build/reclazz-agent-1.2.3-central-bundle.zip'),
            ('spring-boot-starter', 'reclazz-spring-boot-starter', 'spring-boot-starter/build/reclazz-spring-boot-starter-1.2.3-central-bundle.zip'),
            ('maven-plugin', 'reclazz-maven-plugin', evidence.MAVEN_BUNDLE),
        ]:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(path, 'w') as archive:
                for suffix in ['.jar', '-sources.jar', '-javadoc.jar', '.pom']:
                    for ending in [suffix, suffix + '.asc']:
                        archive.writestr(f'com/onurkat/reclazz/{artifact}/1.2.3/{artifact}-1.2.3{ending}', ending)
        for name in ['libs/gradle-plugin-1.2.3.jar', 'libs/gradle-plugin-1.2.3-sources.jar',
                     'libs/gradle-plugin-1.2.3-javadoc.jar', 'publications/pluginMaven/pom-default.xml',
                     'publications/pluginMaven/module.json']:
            self.write('gradle-plugin/build/' + name, name)
        return self.capture('distribution')

    def test_receipt_and_copy_identify_exact_bytes(self):
        recorded = self.capture()
        self.assertEqual(recorded, evidence.verify(self.root))
        self.assertEqual(self.commit, recorded['sourceCommit'])
        self.assertEqual('1.2.3', recorded['version'])
        signed = self.root / 'build/distributions/reclazz-1.2.3-signed.zip'
        self.assertEqual(signed.read_bytes(), (self.root / evidence.ASSETS / 'reclazz-1.2.3.zip').read_bytes())

    def test_changed_source_or_head_is_rejected(self):
        self.capture()
        self.write('gradle.properties', 'pluginVersion=1.2.3\n# changed\n')
        with self.assertRaisesRegex(ValueError, 'source changed'):
            evidence.verify(self.root)
        self.git('add', '.')
        self.git('commit', '-qm', 'different source')
        with self.assertRaisesRegex(ValueError, 'source changed'):
            evidence.verify(self.root)

    def test_modified_missing_and_extra_artifacts_are_rejected(self):
        self.capture()
        self.write('build/release-gate/assets/unexpected.jar', 'extra')
        with self.assertRaisesRegex(ValueError, 'artifact changed'):
            evidence.verify(self.root)
        (self.root / 'build/release-gate/assets/unexpected.jar').unlink()
        self.write('build/distributions/reclazz-1.2.3-signed.zip', 'different')
        with self.assertRaisesRegex(ValueError, 'artifact changed'):
            evidence.verify(self.root)
        (self.root / 'build/distributions/reclazz-1.2.3-signed.zip').unlink()
        with self.assertRaisesRegex(ValueError, 'missing release artifact'):
            evidence.verify(self.root)

    def test_missing_signed_bundle_member_is_rejected(self):
        self.distribution()
        with zipfile.ZipFile(self.root / evidence.MAVEN_BUNDLE, 'w') as archive:
            archive.writestr('unrelated', 'no signed artifacts')
        with self.assertRaises(KeyError):
            self.capture('distribution')

    def test_ci_gate_cannot_authorize_local_publishers(self):
        self.capture('ci')
        for publisher in ['marketplace', 'portal']:
            with self.assertRaisesRegex(ValueError, 'not included'):
                evidence.verify_publisher(self.root, publisher, ['build/release-gate/assets/reclazz-agent-1.2.3.jar'])

    def test_publisher_cannot_select_a_file_outside_receipt(self):
        self.capture()
        with self.assertRaisesRegex(ValueError, 'not checked'):
            evidence.verify_publisher(self.root, 'marketplace', ['build/distributions/other.zip'])
        with self.assertRaisesRegex(ValueError, 'not included'):
            evidence.verify_publisher(self.root, 'portal', ['build/distributions/reclazz-1.2.3-signed.zip'])

    def test_paths_and_symlinks_are_rejected(self):
        for path in ['../elsewhere', '/tmp/elsewhere']:
            with self.assertRaises(ValueError):
                evidence.inventory(self.root, [path])
        target = self.write('build/a.jar', 'jar')
        (self.root / 'build/link.jar').symlink_to(target)
        with self.assertRaisesRegex(ValueError, 'symlink'):
            evidence.inventory(self.root, ['build/link.jar'])

    @patch.dict(os.environ, {'RECLAZZ_CENTRAL_TOKEN': 'test-token'})
    def test_upload_uses_checked_bundle_and_manual_publication(self):
        self.distribution()
        data = (self.root / evidence.MAVEN_BUNDLE).read_bytes()
        calls = []
        def upload(request, timeout):
            calls.append(request)
            self.assertEqual(120, timeout)
            self.assertEqual('POST', request.method)
            self.assertEqual('https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED', request.full_url)
            self.assertEqual('Bearer test-token', request.get_header('Authorization'))
            self.assertEqual(data, request.data.split(b'\r\n\r\n', 1)[1].rsplit(b'\r\n--', 1)[0])
            response = io.BytesIO(b'12345678-1234-1234-1234-123456789012')
            response.status = 201
            return response
        evidence.stage_maven(self.root, upload)
        self.assertEqual(1, len(calls))
        self.write(evidence.MAVEN_BUNDLE, 'changed')
        with self.assertRaisesRegex(ValueError, 'artifact changed'):
            evidence.stage_maven(self.root, upload)
        self.assertEqual(1, len(calls), 'changed artifact must never reach HTTP')

    @patch.dict(os.environ, {'RECLAZZ_CENTRAL_TOKEN': 'test-token'})
    def test_http_error_does_not_echo_secrets_or_follow_redirects(self):
        self.distribution()
        def upload(request, timeout):
            raise urllib.error.URLError('secret test-token')
        with self.assertRaisesRegex(ValueError, '^Central staging failed; check the Portal before retrying$'):
            evidence.stage_maven(self.root, upload)
        self.assertIsNone(evidence.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://elsewhere'))

    @unittest.skipUnless(os.environ.get('RECLAZZ_TEST_GRADLE_WRAPPER'), 'real Gradle probe is opt-in')
    def test_real_gradle_upload_graph_does_not_rebuild_and_rechecks_bytes(self):
        for name in ['release-evidence.py', 'release-publish.init.gradle']:
            self.write('scripts/' + name, (SCRIPTS / name).read_text())
        self.write('settings.gradle', "rootProject.name = 'gate-probe'\ninclude 'gradle-plugin'\n")
        self.write('build.gradle', '''
            tasks.register('producer') {
                doLast { throw new GradleException('producer ran during upload') }
            }
            tasks.register('publishPlugin') {
                dependsOn 'producer'
                ext.archiveFile = layout.file(providers.provider { file('build/distributions/reclazz-1.2.3-signed.zip') })
                doLast { file('build/uploaded').text = 'uploaded' }
            }
        ''')
        self.write('gradle-plugin/build.gradle', """
            plugins { id 'java-gradle-plugin'; id 'com.gradle.plugin-publish' version '1.3.1' }
            group = 'com.onurkat.reclazz'
            version = '1.2.3'
            gradlePlugin {
                website = 'https://example.com'
                vcsUrl = 'https://example.com/source'
                plugins { gate { id = 'example.gate'; implementationClass = 'example.Gate' } }
            }
            tasks.named('publishPlugins') {
                actions.clear() // Real plugin/configuration; replace only the remote operation.
                doLast { file('build/uploaded').text = 'uploaded' }
            }
        """)
        self.git('add', '.')
        self.git('commit', '-qm', 'graph fixture')
        self.commit = self.git('rev-parse', 'HEAD')
        self.distribution()
        command = [os.environ['RECLAZZ_TEST_GRADLE_WRAPPER'], '-p', str(self.root),
                   '-I', str(self.root / 'scripts/release-publish.init.gradle'), ':publishPlugin', ':gradle-plugin:publishPlugins',
                   '--no-daemon', '--no-configuration-cache']
        result = subprocess.run(command, capture_output=True, text=True, timeout=120)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue((self.root / 'build/uploaded').exists())
        self.assertTrue((self.root / 'gradle-plugin/build/uploaded').exists())
        (self.root / 'build/uploaded').unlink()
        (self.root / 'gradle-plugin/build/uploaded').unlink()
        self.write('build/distributions/reclazz-1.2.3-signed.zip', 'tampered')
        result = subprocess.run(command, capture_output=True, text=True, timeout=120)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('release artifact changed', result.stdout + result.stderr)
        self.assertFalse((self.root / 'build/uploaded').exists())
        self.assertFalse((self.root / 'gradle-plugin/build/uploaded').exists())


if __name__ == '__main__':
    unittest.main()
