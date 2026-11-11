#!/usr/bin/env python3
"""Portable boundaries of the actual cold dynamic-attribute setup driver."""
from pathlib import Path
from unittest.mock import patch
import importlib.util
import json
import shutil
import tempfile
import unittest
from types import SimpleNamespace
from sap_items_snapshot import capture, inventory

spec = importlib.util.spec_from_file_location('dynamic_setup', Path(__file__).with_name('setup-sap-dynamic-attribute.py'))
d = importlib.util.module_from_spec(spec); spec.loader.exec_module(d)


class DynamicSetupTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name).resolve()
        self.roots = {k: self.base / k for k in ('config', 'data', 'bootstrap', 'tomcat', 'native-temp', 'fixture', 'backoffice-fixture')}
        for root in self.roots.values():
            root.mkdir(); (root / 'original').write_bytes(b'original')
        self.folder = self.base / 'evidence'; self.folder.mkdir()
        self.before = {'fixtureHashes': {'source': 'v1'}, 'agentSha256': 'agent-v1'}

    def snapshot(self):
        for name, root in self.roots.items(): capture(root, self.folder / name, lambda: None)

    def test_exact_seven_nonoverlapping_complete_roots(self):
        roots = d.targets()
        self.assertEqual(7, len(roots)); self.assertEqual(d.PLATFORM / 'tomcat', roots['tomcat'])
        self.assertEqual(d.REPO / 'reclazztest', roots['fixture'])
        self.assertEqual(d.REPO / 'reclazztestbackoffice', roots['backoffice-fixture'])
        d.check_roots(self.roots)
        with self.assertRaisesRegex(RuntimeError, 'Overlapping'):
            d.check_roots({**self.roots, 'nested': self.roots['config']})

    def test_corrupt_last_snapshot_refuses_before_first_deletion(self):
        self.snapshot()
        (self.folder / 'backoffice-fixture/files/original').write_bytes(b'corrupt')
        (self.roots['config'] / 'original').write_bytes(b'current')
        before = {k: inventory(v) for k,v in self.roots.items()}
        with patch.object(d, 'stopped'), self.assertRaisesRegex(RuntimeError, 'verification failed'):
            d.restore_trees(self.folder, self.roots)
        self.assertEqual(before, {k: inventory(v) for k,v in self.roots.items()})

    def test_surviving_writer_blocks_restore_without_deletion(self):
        self.snapshot(); before = inventory(self.roots['data'])
        with patch.object(d, 'stopped', side_effect=RuntimeError('writer survives')):
            with self.assertRaisesRegex(RuntimeError, 'writer survives'): d.restore_trees(self.folder, self.roots)
        self.assertEqual(before, inventory(self.roots['data']))

    def test_interrupted_copy_can_restore_a_missing_owned_target(self):
        self.snapshot(); expected = {k: inventory(v) for k,v in self.roots.items()}
        shutil.rmtree(self.roots['fixture'])
        with patch.object(d, 'stopped'): d.restore_trees(self.folder, self.roots)
        self.assertEqual(expected, {k: inventory(v) for k,v in self.roots.items()})

    def run_attempt(self, provision, native=lambda *a: None):
        with patch.object(d, 'targets', return_value=self.roots), patch.object(d, 'stopped'), \
             patch.object(d.solr, 'stop_all'), patch.object(d, 'native', side_effect=native), \
             patch.object(d, 'provision', side_effect=provision), patch.object(d.support, 'start', return_value={'session': 'new'}), \
             patch.object(d, 'facts', return_value={'present': False}), \
             patch.object(d.support, 'fixture_hashes', return_value=self.before['fixtureHashes']), \
             patch.object(d.support, 'digest', return_value='agent-v1'):
            return d.attempt(self.folder, self.before, True)

    def test_injected_setup_failure_restores_baseline(self):
        old = {k: inventory(v) for k,v in self.roots.items()}
        def provision(*args):
            self.assertTrue(json.loads((self.folder/'state.json').read_text())['captured'])
            for root in self.roots.values(): (root / 'original').write_bytes(b'schema-v2')
            raise RuntimeError('Injected dynamic setup failure after schema update')
        state = self.run_attempt(provision)
        self.assertEqual('EXPECTED FAILURE RECOVERED', state['verdict'])
        self.assertEqual(old, {k: inventory(v) for k,v in self.roots.items()})

    def test_setup_failure_restores_web_and_commonweb_outputs(self):
        existing = self.roots['tomcat'] / 'commonweb/lib/shared.jar'
        existing.parent.mkdir(parents=True); existing.write_bytes(b'old jar')
        old = {k: inventory(v) for k,v in self.roots.items()}
        def write_outputs():
            existing.write_bytes(b'new jar')
            extra = self.roots['fixture'] / 'web/webroot/WEB-INF/classes/example/Extra.class'
            extra.parent.mkdir(parents=True, exist_ok=True); extra.write_bytes(b'new web class')
        def provision(*args):
            write_outputs(); raise RuntimeError('Injected dynamic setup failure after schema update')
        state = self.run_attempt(provision, native=lambda *a: write_outputs())
        self.assertEqual('PASS', state['restoration'])
        self.assertEqual(old, {k: inventory(v) for k,v in self.roots.items()})

    def test_wrong_recovery_descriptor_is_failure_not_pass(self):
        with patch.object(d, 'validate_facts', side_effect=RuntimeError('descriptor remains')):
            state = self.run_attempt(lambda *a: (_ for _ in ()).throw(RuntimeError('Injected dynamic setup failure')))
        self.assertEqual('recovery-required', state['phase']); self.assertEqual('FAIL', state['restoration'])

    def test_descriptor_checks_are_typed_and_exact(self):
        good = {'present': True, 'handler': 'reclazzDynamicLabelHandler', 'property': False, 'read': True, 'write': True}
        d.validate_facts(good, True); d.validate_facts({'present': False}, False)
        for field, value in [('present', 1), ('handler', 'other'), ('property', True), ('write', False), ('read', 'true')]:
            with self.subTest(field=field), self.assertRaises(RuntimeError): d.validate_facts({**good, field: value}, True)

    def test_generated_property_model_cannot_satisfy_dynamic_api(self):
        source = self.base / 'bootstrap/gensrc/de/hybris/platform/core/model/ReclazzSchemaProbeModel.java'
        source.parent.mkdir(parents=True); source.write_text('getPropertyValue(DYNAMICLABEL); setPropertyValue(DYNAMICLABEL,value);')
        with patch.object(d, 'PLATFORM', self.base), self.assertRaisesRegex(RuntimeError, 'dynamic accessor bodies'):
            d.generated_api(self.folder)

    def test_symlink_snapshot_root_is_refused(self):
        link = self.base / 'link'; link.symlink_to(self.roots['data'])
        with self.assertRaisesRegex(RuntimeError, 'physical'): d.check_roots({'link': link})

    def test_preflight_failure_precedes_lease_and_evidence_creation(self):
        with patch.object(d, 'preflight', side_effect=RuntimeError('busy')), patch.object(d, 'lease') as lease, \
             patch.object(d.tempfile, 'mkdtemp') as create, patch('sys.argv', ['setup']):
            with self.assertRaisesRegex(RuntimeError, 'busy'): d.main()
        lease.assert_not_called(); create.assert_not_called()

    def test_durable_lease_refuses_unfinished_owner(self):
        lease = self.base / 'lease'; lease.write_text('/private/tmp/unfinished')
        with patch.object(d, 'LEASE', lease), self.assertRaisesRegex(RuntimeError, 'Unfinished'):
            with d.lease(): pass
        self.assertEqual('/private/tmp/unfinished', lease.read_text())

    def source_tree(self, staged):
        repo = self.base / 'repo'; templates = self.base / 'templates'
        repo.mkdir(); templates.mkdir()
        names = ['reclazztest/' + rel for rel in d.OWNED[:2]]
        if staged: names += ['reclazztest/' + rel for rel in d.OWNED[2:]]
        names += ['reclazztest/filler-' + str(i) for i in range(70)]
        for name in names:
            target = repo / name; target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(b'baseline')
        for rel in d.OWNED:
            target = templates / rel; target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(b'baseline')
        return repo, templates, {name: 'hash' for name in names}

    def test_old72_source_is_verified_against_head(self):
        repo, templates, hashes = self.source_tree(False)
        with patch.object(d, 'REPO', repo), patch.object(d, 'TEMPLATES', templates), \
             patch.object(d.subprocess, 'run', return_value=SimpleNamespace(stdout=b'baseline')):
            d.check_sources(hashes)
            (repo / 'reclazztest/filler-0').write_bytes(b'unowned edit')
            with self.assertRaisesRegex(RuntimeError, 'Unowned fixture change'): d.check_sources(hashes)

    def test_committed74_source_can_provision_an_older_db_without_overwriting_edits(self):
        repo, templates, hashes = self.source_tree(True)
        with patch.object(d, 'REPO', repo), patch.object(d, 'TEMPLATES', templates), \
             patch.object(d.subprocess, 'run', return_value=SimpleNamespace(stdout=b'baseline')):
            d.check_sources(hashes)
            (repo / 'reclazztest' / d.OWNED[3]).write_bytes(b'unowned controller edit')
            with self.assertRaisesRegex(RuntimeError, 'Unowned fixture change'): d.check_sources(hashes)

    def test_current77_source_keeps_non_dynamic_fixtures_head_verified(self):
        repo, templates, hashes = self.source_tree(True)
        for number in range(3):
            name = 'reclazztest/prepare-' + str(number)
            (repo / name).write_bytes(b'baseline'); hashes[name] = 'hash'
        with patch.object(d, 'REPO', repo), patch.object(d, 'TEMPLATES', templates), \
             patch.object(d.subprocess, 'run', return_value=SimpleNamespace(stdout=b'baseline')):
            d.check_sources(hashes)
            (repo / 'reclazztest/prepare-0').write_bytes(b'foreign edit')
            with self.assertRaisesRegex(RuntimeError, 'Unowned fixture change'): d.check_sources(hashes)

    def test_current80_source_keeps_non_dynamic_fixtures_head_verified(self):
        repo, templates, hashes = self.source_tree(True)
        for number in range(6):
            name = 'reclazztest/prepare-' + str(number)
            (repo / name).write_bytes(b'baseline'); hashes[name] = 'hash'
        with patch.object(d, 'REPO', repo), patch.object(d, 'TEMPLATES', templates), \
             patch.object(d.subprocess, 'run', return_value=SimpleNamespace(stdout=b'baseline')):
            d.check_sources(hashes)
            (repo / 'reclazztest/prepare-0').write_bytes(b'foreign edit')
            with self.assertRaisesRegex(RuntimeError, 'Unowned fixture change'): d.check_sources(hashes)

    def test_current83_source_keeps_non_dynamic_fixtures_head_verified(self):
        repo, templates, hashes = self.source_tree(True)
        for number in range(9):
            name = 'reclazztest/prepare-' + str(number)
            (repo / name).write_bytes(b'baseline'); hashes[name] = 'hash'
        with patch.object(d, 'REPO', repo), patch.object(d, 'TEMPLATES', templates), \
             patch.object(d.subprocess, 'run', return_value=SimpleNamespace(stdout=b'baseline')):
            d.check_sources(hashes)
            (repo / 'reclazztest/prepare-0').write_bytes(b'foreign edit')
            with self.assertRaisesRegex(RuntimeError, 'Unowned fixture change'): d.check_sources(hashes)

    def test_current86_source_keeps_non_dynamic_fixtures_head_verified(self):
        repo, templates, hashes = self.source_tree(True)
        for number in range(12):
            name = 'reclazztest/prepare-' + str(number)
            (repo / name).write_bytes(b'baseline'); hashes[name] = 'hash'
        with patch.object(d, 'REPO', repo), patch.object(d, 'TEMPLATES', templates), \
             patch.object(d.subprocess, 'run', return_value=SimpleNamespace(stdout=b'baseline')):
            d.check_sources(hashes)
            (repo / 'reclazztest/prepare-0').write_bytes(b'foreign edit')
            with self.assertRaisesRegex(RuntimeError, 'Unowned fixture change'): d.check_sources(hashes)

    def test_partial_source_provisioning_is_refused(self):
        repo, templates, hashes = self.source_tree(True)
        (repo / 'reclazztest' / d.OWNED[3]).unlink()
        with patch.object(d, 'REPO', repo), patch.object(d, 'TEMPLATES', templates), self.assertRaisesRegex(RuntimeError, 'Partial'):
            d.check_sources(hashes)


if __name__ == '__main__': unittest.main()
