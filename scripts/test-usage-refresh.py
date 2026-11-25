"""Focused checks against the published usage structure and frozen fragments."""
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from website.check import refreshed_usage_anchors, usage_contract_errors


class UsageRefreshTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.repo = Path(temp.name)
        for name in ('website/usage-anchors.json', 'website/usage-structure.json', 'docs/usage.md'):
            dest = self.repo / name
            dest.parent.mkdir(exist_ok=True)
            shutil.copyfile(ROOT / name, dest)
        self.anchors = self.repo / 'website/usage-anchors.json'
        self.doc = self.repo / 'docs/usage.md'
        self.heading = '### Bean methods added after startup\n'

    def test_body_refresh_preserves_pins_and_is_idempotent(self):
        before = json.loads(self.anchors.read_bytes())
        self.doc.write_text(self.doc.read_text().replace(self.heading, self.heading + '\nNew usage detail.\n', 1))
        self.anchors.write_text(refreshed_usage_anchors(self.repo))
        after = json.loads(self.anchors.read_bytes())
        self.assertEqual(len(before['headings']), len(after['headings']))
        changed = []
        for old, new in zip(before['headings'], after['headings']):
            if old != new:
                changed.append(new['key'])
                self.assertEqual(old | {'body_sha256': new['body_sha256']}, new)
        self.assertEqual(['bean-methods-added-after-startup'], changed)
        self.assertEqual([], usage_contract_errors(self.repo))
        self.assertEqual(self.anchors.read_bytes(), refreshed_usage_anchors(self.repo).encode())

    def test_renamed_heading_and_new_heading_fail(self):
        source = self.doc.read_text()
        for new in ('### Renamed methods\n', self.heading + '\n### Extra methods\n'):
            with self.subTest(new=new):
                self.doc.write_text(source.replace(self.heading, new, 1))
                with self.assertRaisesRegex(ValueError, 'Bean methods added after startup|Extra methods'):
                    refreshed_usage_anchors(self.repo)

    def test_reordered_unassigned_siblings_preserve_original_pin_order(self):
        source = self.doc.read_text()
        start = source.index('### How It Works')
        end = source.index('### Setup', start)
        insert = source.index('### Project settings', end)
        self.doc.write_text(source[:start] + source[end:insert] + source[start:end] + source[insert:])
        with self.assertRaisesRegex(ValueError, 'Usage section order changed'):
            refreshed_usage_anchors(self.repo)

    def test_move_or_reorder_requires_explicit_decision(self):
        source = self.doc.read_text()
        start = source.index(self.heading)
        end = source.index('\n### ', start + len(self.heading)) + 1
        section = source[start:end]
        for destination in ('### Bean properties added after startup', '## Data and caches'):
            with self.subTest(destination=destination):
                text = source[:start] + source[end:]
                insert = text.index('\n', text.index(destination)) + 1 if destination.startswith('## ') else text.index(destination)
                self.doc.write_text(text[:insert] + section + text[insert:])
                with self.assertRaisesRegex(ValueError, 'Bean methods added after startup|bean-methods-added-after-startup'):
                    refreshed_usage_anchors(self.repo)


if __name__ == '__main__':
    unittest.main()
