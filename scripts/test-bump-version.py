#!/usr/bin/env python3
# Copyright 2026 Onur Kat
# SPDX-License-Identifier: Apache-2.0
"""Version preparation regressions; only disposable scratch repositories are edited."""
import importlib.util
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
SCRIPTS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('bump', SCRIPTS / 'bump-version.py')
bump = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bump)


class VersionBumpTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='reclazz version fixture ')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.write(bump.FILES[0], 'pluginVersion=1.2.3\notherVersion=5.0.0\n')
        self.write(bump.FILES[1], '''<project xmlns="http://maven.apache.org/POM/4.0.0">
<artifactId>reclazz-maven-plugin</artifactId><version>1.2.3</version>
<dependencies><dependency><groupId>com.onurkat.reclazz</groupId>
<artifactId>reclazz-agent</artifactId><version>1.2.3</version></dependency>
<dependency><artifactId>unrelated</artifactId><version>1.2.3</version></dependency>
</dependencies></project>\n''')
        self.write(bump.FILES[2], '# Changelog\n\n## [Unreleased]\n\n### Fixed\n- Plain note\n  continued here.\n\n## [1.2.3] - 2026-01-01\n- Old note\n')
        self.write(bump.FILES[3], '<idea-plugin><change-notes><![CDATA[\n<h3>1.2.3</h3>\n<ul><li>Old note</li></ul>\n]]></change-notes></idea-plugin>\n')
        (self.root / 'scripts').mkdir()
        for name in ('bump-version.sh', 'bump-version.py'):
            shutil.copy2(SCRIPTS / name, self.root / 'scripts' / name)

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(text.encode('utf-8'))

    def snapshot(self):
        return {name: ((self.root / name).read_bytes(), stat.S_IMODE((self.root / name).stat().st_mode))
                for name in bump.FILES if (self.root / name).exists()}

    def run_cli(self, *args, release_date='2026-10-02'):
        return subprocess.run(['bash', str(self.root / 'scripts/bump-version.sh'), *args],
                              cwd=self.root.parent, text=True, capture_output=True,
                              env={**os.environ, 'RECLAZZ_RELEASE_DATE': release_date}, timeout=10)

    def apply(self):
        bump.bump(self.root, '1.2.4', '2026-10-02')

    def assert_clean(self):
        self.assertFalse((self.root / bump.TRANSACTION).exists())

    def test_entrypoint_plain_bullets_and_optional_v(self):
        result = self.run_cli('v1.2.4')
        self.assertEqual(0, result.returncode, result.stderr)
        texts = [(self.root / name).read_text() for name in bump.FILES]
        self.assertIn('pluginVersion=1.2.4\notherVersion=5.0.0', texts[0])
        self.assertEqual(2, texts[1].count('<version>1.2.4</version>'))
        self.assertIn('<artifactId>unrelated</artifactId><version>1.2.3</version>', texts[1])
        self.assertIn('## [Unreleased]\n\n## [1.2.4] - 2026-10-02', texts[2])
        self.assertIn('<li>Plain note continued here.</li>', texts[3])
        self.assertIn('<h3>1.2.3</h3>', texts[3])
        self.assert_clean()

    def test_real_repository_metadata_in_scratch(self):
        for name in bump.FILES:
            shutil.copy2(SCRIPTS.parent / name, self.root / name)
        result = self.run_cli('999.0.0')
        self.assertEqual(0, result.returncode, result.stderr)
        for name in bump.FILES:
            self.assertIn('999.0.0', (self.root / name).read_text())

    def test_bold_headlines_escaped_and_old_notes_unchanged(self):
        self.write(bump.FILES[2], '## [Unreleased]\n- **A < B & C ]]> D**: details\n- Plain <script> & text\n')
        self.apply()
        notes = (self.root / bump.FILES[3]).read_text()
        self.assertIn('<li>A &lt; B &amp; C ]]&gt; D</li>', notes)
        self.assertNotIn(': details', notes)
        self.assertNotIn('Plain &lt;script&gt;', notes)
        self.assertIn('<h3>1.2.3</h3>\n<ul><li>Old note</li></ul>', notes)

    def test_plain_notes_are_html_escaped(self):
        self.write(bump.FILES[2], '## [Unreleased]\n- Plain <script> & text\n')
        self.apply()
        self.assertIn('<li>Plain &lt;script&gt; &amp; text</li>',
                      (self.root / bump.FILES[3]).read_text())

    def test_crlf_and_modes(self):
        for name in bump.FILES:
            path = self.root / name
            path.write_bytes(path.read_bytes().replace(b'\n', b'\r\n'))
            path.chmod(0o640)
        self.apply()
        for name in bump.FILES:
            data, mode = self.snapshot()[name]
            self.assertNotIn(b'\n', data.replace(b'\r\n', b''))
            self.assertEqual(0o640, mode)

    def test_invalid_cli_inputs_leave_all_bytes(self):
        before = self.snapshot()
        for args in [(), ('1.2.4', 'extra'), ('1.2',), ('1.2.3.4',), ('a.b.c',),
                     ('1.2.4-rc1',), ('1.2.4+build',), ('01.2.4',), ('1.2.04',),
                     ('vv1.2.4',), ('1.2.4\n',), ('1.2/4',), ('١.٢.٤',), ('1.2.3',)]:
            with self.subTest(args=args):
                self.assertNotEqual(0, self.run_cli(*args).returncode)
                self.assertEqual(before, self.snapshot())
                self.assert_clean()

    def test_bad_date(self):
        before = self.snapshot()
        for value in ('2026-02-30', '2026-1-2', '2026-10-02\n', ''):
            with self.subTest(value=value):
                self.assertNotEqual(0, self.run_cli('1.2.4', release_date=value).returncode)
                self.assertEqual(before, self.snapshot())
                self.assert_clean()

    def test_missing_files(self):
        for name in bump.FILES:
            with self.subTest(name=name):
                path = self.root / name; original = path.read_bytes(); path.unlink()
                before = self.snapshot()
                with self.assertRaises(ValueError): self.apply()
                self.assertEqual(before, self.snapshot()); self.assert_clean()
                path.write_bytes(original)

    def test_invalid_sections_or_versions(self):
        changes = [(0, 'pluginVersion=1.2.3', '# no version'),
                   (0, 'pluginVersion=1.2.3', 'pluginVersion=1.2.3\npluginVersion=1.2.3'),
                   (0, 'pluginVersion=1.2.3', 'pluginVersion=1.2.3\n pluginVersion : 1.0.0'),
                   (1, '<version>1.2.3</version>', '<version>0.0.1</version>'),
                   (1, '<artifactId>reclazz-agent</artifactId>', '<artifactId>other-agent</artifactId>'),
                   (1, '</project>', ''),
                   (2, '## [Unreleased]', '## [Other]'),
                   (2, '## [Unreleased]', '## [Unreleased]\n\n## [Unreleased]'),
                   (2, '- Plain note\n  continued here.', ''),
                   (2, '## [1.2.3]', '## [1.2.4]'),
                   (3, '<h3>1.2.3</h3>', '<h3>1.2.4</h3>'),
                   (3, 'change-notes', 'description'),
                   (3, '</idea-plugin>', '')]
        for index, old, new in changes:
            with self.subTest(index=index, old=old):
                path = self.root / bump.FILES[index]; original = path.read_bytes()
                path.write_bytes(original.replace(old.encode(), new.encode()))
                before = self.snapshot()
                with self.assertRaises((ValueError, bump.ET.ParseError)): self.apply()
                self.assertEqual(before, self.snapshot()); self.assert_clean()
                path.write_bytes(original)

    def test_symlink_refused(self):
        path = self.root / bump.FILES[0]; saved = self.root / 'saved.properties'
        path.rename(saved); path.symlink_to(saved)
        before = self.snapshot()
        with self.assertRaises(ValueError): self.apply()
        self.assertTrue(path.is_symlink()); self.assertEqual(before, self.snapshot())
        self.assert_clean()

    def test_staged_write_failure_never_replaces_originals(self):
        before = self.snapshot(); write = Path.write_bytes
        def fail(path, data):
            if path.name == '3.new': raise OSError('injected staging failure')
            return write(path, data)
        with patch.object(Path, 'write_bytes', fail), patch.object(bump.os, 'replace') as replace:
            with self.assertRaisesRegex(OSError, 'staging'): self.apply()
            replace.assert_not_called()
        self.assertEqual(before, self.snapshot()); self.assert_clean()

    def test_each_replacement_failure_restores_bytes_and_modes(self):
        for position in range(4):
            with self.subTest(position=position):
                before = self.snapshot(); replace = os.replace
                def fail(src, dst):
                    if src.name == f'{position}.new': raise OSError('injected replacement failure')
                    return replace(src, dst)
                with patch.object(bump.os, 'replace', fail):
                    with self.assertRaisesRegex(OSError, 'replacement'): self.apply()
                self.assertEqual(before, self.snapshot()); self.assert_clean()

    def test_interrupt_after_replacement_is_restored(self):
        before = self.snapshot(); replace = os.replace
        def interrupt(src, dst):
            replace(src, dst)
            if src.name == '2.new': raise KeyboardInterrupt()
        with patch.object(bump.os, 'replace', interrupt):
            with self.assertRaises(KeyboardInterrupt): self.apply()
        self.assertEqual(before, self.snapshot()); self.assert_clean()

    def test_failed_rollback_preserves_all_backups_and_blocks_retry(self):
        before = self.snapshot(); replace = os.replace
        def fail(src, dst):
            if src.name in ('2.new', '0.restore'): raise OSError('injected persistent failure')
            return replace(src, dst)
        with patch.object(bump.os, 'replace', fail):
            with self.assertRaisesRegex(RuntimeError, 'Originals retained'): self.apply()
        for index, name in enumerate(bump.FILES):
            self.assertEqual(before[name][0], (self.root / bump.TRANSACTION / f'{index}.original').read_bytes())
        damaged = self.snapshot()
        with self.assertRaises(FileExistsError): self.apply()
        self.assertEqual(damaged, self.snapshot())
        # The reported originals can actually recover the four files.
        for index, name in enumerate(bump.FILES):
            shutil.copy2(self.root / bump.TRANSACTION / f'{index}.original', self.root / name)
        self.assertEqual(before, self.snapshot())

    def test_existing_transaction_blocks_other_invocation(self):
        transaction = self.root / bump.TRANSACTION; transaction.mkdir()
        (transaction / 'owner').write_text('another process')
        before = self.snapshot()
        self.assertNotEqual(0, self.run_cli('1.2.4').returncode)
        self.assertEqual(before, self.snapshot())
        self.assertEqual('another process', (transaction / 'owner').read_text())

    def test_changed_input_during_staging_is_not_overwritten(self):
        write = Path.write_bytes
        def edit(path, data):
            result = write(path, data)
            if path.name == '3.new': write(self.root / bump.FILES[0], b'owner edit\n')
            return result
        with patch.object(Path, 'write_bytes', edit), patch.object(bump.os, 'replace') as replace:
            with self.assertRaisesRegex(ValueError, 'input changed'): self.apply()
            replace.assert_not_called()
        self.assertEqual(b'owner edit\n', (self.root / bump.FILES[0]).read_bytes())
        self.assert_clean()


if __name__ == '__main__':
    unittest.main()
