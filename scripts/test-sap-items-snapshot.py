#!/usr/bin/env python3
import tempfile
import unittest
from pathlib import Path
from sap_items_snapshot import capture, restore, verify, inventory


class SnapshotTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'db'; self.source.mkdir()
        (self.source / 'mydb.data').write_bytes(b'db-v1')
        (self.source / 'mydb.script').write_text('schema-v1')
        self.snapshot = self.root / 'snapshot'

    def stopped(self):
        if (self.source / 'mydb.lck').exists():
            raise RuntimeError('Live writer/lock')

    def test_stopped_snapshot_and_exact_restore_remove_extra_files(self):
        expected = capture(self.source, self.snapshot, self.stopped)
        (self.source / 'mydb.data').write_bytes(b'db-v2')
        (self.source / 'mydb.log').write_bytes(b'new post-snapshot data')
        (self.source / 'mydb.tmp').mkdir()
        (self.source / 'mydb.tmp' / 'extra').write_bytes(b'v2')
        restore(self.snapshot, self.source, self.stopped)
        self.assertEqual(expected, inventory(self.source))

    def test_live_writer_blocks_capture(self):
        (self.source / 'mydb.lck').write_bytes(b'lock')
        with self.assertRaisesRegex(RuntimeError, 'Live writer'):
            capture(self.source, self.snapshot, self.stopped)
        self.assertFalse(self.snapshot.exists())

    def test_live_writer_blocks_restore_without_deletion(self):
        capture(self.source, self.snapshot, self.stopped)
        (self.source / 'mydb.lck').write_bytes(b'lock')
        before = inventory(self.source)
        with self.assertRaisesRegex(RuntimeError, 'Live writer'):
            restore(self.snapshot, self.source, self.stopped)
        self.assertEqual(before, inventory(self.source))

    def test_tampered_snapshot_refuses_restore_before_deletion(self):
        capture(self.source, self.snapshot, self.stopped)
        (self.snapshot / 'files' / 'mydb.data').write_bytes(b'tampered')
        before = inventory(self.source)
        with self.assertRaisesRegex(RuntimeError, 'verification failed'):
            restore(self.snapshot, self.source, self.stopped)
        self.assertEqual(before, inventory(self.source))

    def test_extra_snapshot_file_refuses_restore(self):
        capture(self.source, self.snapshot, self.stopped)
        (self.snapshot / 'files' / 'extra').write_bytes(b'foreign')
        with self.assertRaisesRegex(RuntimeError, 'verification failed'):
            verify(self.snapshot)

    def test_failure_after_mutation_always_restores(self):
        expected = capture(self.source, self.snapshot, self.stopped)
        with self.assertRaisesRegex(RuntimeError, 'injected'):
            try:
                (self.source / 'mydb.data').write_bytes(b'changed')
                raise RuntimeError('injected after write')
            finally:
                restore(self.snapshot, self.source, self.stopped)
        self.assertEqual(expected, inventory(self.source))

    def test_symlink_refused_before_copy(self):
        (self.source / 'link').symlink_to(self.root)
        with self.assertRaisesRegex(RuntimeError, 'symlink'):
            capture(self.source, self.snapshot, self.stopped)
        self.assertFalse(self.snapshot.exists())

    def test_snapshot_cannot_be_overwritten(self):
        capture(self.source, self.snapshot, self.stopped)
        with self.assertRaisesRegex(RuntimeError, 'already exists'):
            capture(self.source, self.snapshot, self.stopped)
        verify(self.snapshot)


if __name__ == '__main__':
    unittest.main()
