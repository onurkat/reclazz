#!/usr/bin/env python3
"""Portable checks for the real outer profile's preflight and report acceptance."""
import importlib.util
from pathlib import Path
from sap_suite_contract import DEFAULT_CASES, DEFAULT_OUTCOME, DEFAULT_TEST_NAMES, NORMAL_FIXTURE_FILES
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('items_driver', Path(__file__).with_name('test-sap-items-live.py'))
driver = importlib.util.module_from_spec(spec); spec.loader.exec_module(driver)
GATES = {'agentArgsFound': True, 'agentOptionsMatchConfig': True, 'autoCompile': True,
         'autoUpdateRunningSystem': False, 'resetStatics': False, 'autoSolrReindex': False,
         'impexAllowRemove': False, 'codegenExtensionLoaded': True, 'codegenGeneratedRoots': True}


def facts():
    return {'typePresent': True, 'batchValuePresent': False, 'typeCode': 31991,
            'table': 'ReclazzSchemaProbes', 'rows': 0, 'collisions': 0,
            'tenants': [{'id': tenant, 'db': 'jdbc:hsqldb:file:' + str(driver.SAP_HOME / 'data/hsqldb/mydb') + ';shutdown=true'}
                        for tenant in ['master', 'junit']]}


class DriverTest(unittest.TestCase):
    def skipped(self, gates, native_facts=None):
        with patch.object(driver, 'get', side_effect=[gates, native_facts]), \
             patch.object(driver.tempfile, 'mkdtemp') as mkdir, \
             patch.object(driver, 'profile') as attempt, patch('sys.argv', ['driver']):
            self.assertEqual(0, driver.main())
            mkdir.assert_not_called(); attempt.assert_not_called()

    def test_disabled_gate_zero_writes(self):
        self.skipped(dict(GATES, autoCompile=False))

    def test_missing_gate_zero_writes(self):
        gates = dict(GATES); del gates['agentOptionsMatchConfig']; self.skipped(gates)

    def test_mismatch_unsafe_option_zero_writes(self):
        self.skipped(dict(GATES, autoUpdateRunningSystem=True))

    def test_wrong_boolean_type_zero_writes(self):
        self.skipped(dict(GATES, resetStatics='false'))

    def test_missing_baseline_type_zero_writes(self):
        self.skipped(GATES, {'typePresent': False})

    def test_unavailable_endpoint_skips_before_any_write(self):
        with patch.object(driver, 'get', side_effect=RuntimeError('fixture unavailable')), \
             patch.object(driver.tempfile, 'mkdtemp') as mkdir, \
             patch.object(driver, 'profile') as attempt, patch('sys.argv', ['driver']):
            self.assertEqual(0, driver.main()); mkdir.assert_not_called(); attempt.assert_not_called()

    def test_all_tenant_database_urls_must_be_local(self):
        data = facts(); data['tenants'].append({'id': 'foo', 'db': 'jdbc:postgresql:foreign'})
        with self.assertRaisesRegex(RuntimeError, 'Foreign/non-disposable'):
            driver.validate_facts(data)

    def test_master_and_junit_are_both_required(self):
        data = facts(); data['tenants'] = [{'id': 'foo', 'db': data['tenants'][0]['db']}]
        with self.assertRaisesRegex(RuntimeError, 'Missing master/junit'):
            driver.validate_facts(data)

    def test_added_attribute_or_owned_rows_refuse_baseline(self):
        for change in [{'batchValuePresent': True}, {'rows': 1}, {'collisions': 1}]:
            with self.assertRaises(RuntimeError):
                driver.validate_facts(dict(facts(), **change))

    def test_native_facts_accepted(self):
        driver.validate_facts(facts())

    def test_wrapper_preserves_unrelated_lf_settings(self):
        jar = Path('/clean/agent.jar')
        original = b'wrapper.java.additional.1=-Xmx2G\nwrapper.java.additional.25=-javaagent:/clean/agent.jar=autoCompile=true\n'
        self.assertEqual(original.replace(b'autoCompile=true', b'autoCompile=true,autoUpdateRunningSystem=true'), driver.schema_wrapper_bytes(original, jar))

    def test_wrapper_preserves_crlf(self):
        original = b'wrapper.java.additional.25=-javaagent:/clean/agent.jar=autoCompile=true\r\n'
        self.assertEqual(original.replace(b'autoCompile=true', b'autoCompile=true,autoUpdateRunningSystem=true'), driver.schema_wrapper_bytes(original, Path('/clean/agent.jar')))

    def test_wrapper_wrong_jar_refused(self):
        with self.assertRaises(RuntimeError):
            driver.schema_wrapper_bytes(b'wrapper.java.additional.1=-javaagent:/foreign.jar=x\n', Path('/clean/agent.jar'))

    def test_wrapper_duplicate_owned_agent_refused(self):
        original = b'wrapper.java.additional.25=-javaagent:/clean/agent.jar=autoCompile=true\n'
        with self.assertRaises(RuntimeError):
            driver.schema_wrapper_bytes(original + original, Path('/clean/agent.jar'))

    def test_wrapper_explicit_normal_schema_option_refused(self):
        for value in [b'true', b'false']:
            with self.assertRaises(RuntimeError):
                driver.schema_wrapper_bytes(b'wrapper.java.additional.25=-javaagent:/clean/agent.jar=autoUpdateRunningSystem=' + value + b'\n', Path('/clean/agent.jar'))

    def test_boot_options_match_both_profiles(self):
        driver.require_boot_options(GATES, False)
        driver.require_boot_options(dict(GATES, autoUpdateRunningSystem=True), True)

    def test_boot_options_mismatch_fails_immediately(self):
        for gates in [GATES, dict(GATES, autoUpdateRunningSystem='true')]:
            with self.assertRaisesRegex(RuntimeError, 'Boot option mismatch'):
                driver.require_boot_options(gates, True)

    def test_durable_state_recovers_original_bytes_after_live_inputs_change(self):
        import json
        import hashlib
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); config = root / 'wrapper.conf'; source = root / 'items.xml'
            config_before = b'option=original\r\n\xff'; source_before = b'<items>baseline</items>'
            config.write_bytes(config_before); source.write_bytes(source_before)
            driver.save_recovery_state(root, {config: config_before}, source_before, [root / 'generated'], '1' * 64)
            config.write_bytes(b'changed options'); source.write_bytes(b'changed schema')
            state = json.loads((root / 'state.json').read_text())
            config.write_bytes(bytes.fromhex(state['configBytes'][str(config)]))
            source.write_bytes(bytes.fromhex(state['fixtureSourceBytes']))
            self.assertEqual(config_before, config.read_bytes()); self.assertEqual(source_before, source.read_bytes())
            self.assertEqual(hashlib.sha256(config_before).hexdigest(), state['configSha256'][str(config)])
            self.assertEqual(hashlib.sha256(source_before).hexdigest(), state['fixtureSourceSha256'])
            self.assertIs(True, state['snapshotVerified']); self.assertEqual(str(driver.SAP_HOME), state['home'])
            self.assertEqual([str(root / 'generated')], state['generatedTargets'])

    def test_actual_ansi_report_parser_counts_failures_and_skips(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            def report(*args, **kwargs):
                kwargs['stdout'].write(f'  Default committed membership: {DEFAULT_CASES} cases (Orika rollback included)\n'
                                       'some test | \x1b[32mPASS\x1b[0m | 1s |\n'
                                       'some test | \x1b[31mFAIL\x1b[0m | 1s |\n'
                                       'some test | \x1b[33mSKIP\x1b[0m | 1s |\n')
                return type('Result', (), {'returncode': 1})()
            with patch.object(driver.subprocess, 'run', side_effect=report):
                result = driver.runner(root, 'fake')
            self.assertEqual({'PASS': 1, 'FAIL': 1, 'ERROR': 0, 'SKIP': 1}, result['counts'])
            self.assertEqual(1, result['exitCode']); self.assertFalse(result['defaultSuite'])


    def test_default_report_requires_exact_membership_and_outcome(self):
        runner = driver
        rows = [(name, 'PASS') for name in DEFAULT_TEST_NAMES]
        def log(selected, membership=DEFAULT_CASES):
            return f'  Default committed membership: {membership} cases (Orika rollback included)\n' + ''.join(
                f'{name} | \x1b[32m{status}\x1b[0m | 1s |\n' for name, status in selected)
        valid = log(rows)
        invalid = [log(rows[:-1], DEFAULT_CASES - 1), log(rows[:-1]),
                   log(rows + [('Hibernate L2 cache', 'SKIP')], DEFAULT_CASES + 1),
                   log(rows + [('Hibernate L2 cache', 'PASS')], DEFAULT_CASES + 1),
                   log(rows, DEFAULT_CASES + 1),
                   log([('replacement scenario', 'PASS')] + rows[1:]),
                   log([('Hibernate L2 cache', 'SKIP')] + rows[1:]),
                   log([(name, 'SKIP' if i == 0 else 'PASS') for i, (name, _) in enumerate(rows)]),
                   log([(name, 'FAIL' if i == 0 else 'PASS') for i, (name, _) in enumerate(rows)]),
                   log([(name, 'ERROR' if i == 0 else 'PASS') for i, (name, _) in enumerate(rows)])]
        for text, expected in [(valid, True)] + [(text, False) for text in invalid]:
            with tempfile.TemporaryDirectory() as temporary:
                def process(*args, **kwargs):
                    kwargs['stdout'].write(text)
                    return type('Process', (), {'returncode': 0, 'wait': lambda self, timeout: 0})()
                with patch.object(runner.subprocess, 'run', side_effect=process), patch.object(runner.subprocess, 'Popen', side_effect=process):
                    result = runner.runner(Path(temporary), 'default')
                self.assertIs(expected, result['defaultSuite'])
                if expected: self.assertEqual(DEFAULT_OUTCOME, result['counts'])


class NewTypeDriverTest(unittest.TestCase):
    def test_exact_profile_report_membership(self):
        row = driver.NEW_TYPE_LABEL + ' | PASS | 1s |\n'
        self.assertTrue(driver.new_type_report(row))
        for text in ['', row.replace('PASS', 'SKIP'), row.replace('PASS', 'FAIL'),
                     row.replace('New item type', 'replacement'), row + row,
                     row + 'Other | PASS | 1s |\n']:
            self.assertFalse(driver.new_type_report(text))

    def test_opt_in_requires_explicit_recovery_arguments_before_writes(self):
        with patch.object(driver.tempfile, 'mkdtemp') as mkdir, patch('sys.argv', ['driver', '--new-item-type', '--runs', '1']):
            with self.assertRaisesRegex(RuntimeError, 'requires'):
                driver.main()
            mkdir.assert_not_called()

    def test_new_mode_dispatch_does_not_run_default_or_attribute_profile(self):
        with patch.object(driver, 'run_new_type', return_value=0) as new, patch.object(driver, 'preflight') as preflight, patch.object(driver, 'profile') as profile, patch('sys.argv', ['driver', '--new-item-type']):
            self.assertEqual(0, driver.main())
            self.assertTrue(new.call_args.args[0].new_item_type)
            preflight.assert_not_called(); profile.assert_not_called()

    def test_explicit_fixture_manifest_never_invokes_git(self):
        import json
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); manifest = root / 'manifest.json'
            (root / 'reclazztest').mkdir(); (root / 'reclazztest/a').write_bytes(b'original')
            manifest.write_text(json.dumps({'reclazztest/a': driver.digest(root / 'reclazztest/a')}))
            with patch.object(driver, 'REPO', root), patch.object(driver, 'NORMAL_FIXTURE_FILES', 1), patch.dict(driver.os.environ, {'RECLAZZ_FIXTURE_MANIFEST': str(manifest)}), patch.object(driver.subprocess, 'check_output') as command:
                self.assertEqual(1, len(driver.fixture_hashes())); command.assert_not_called()
                (root / 'reclazztest/a').write_bytes(b'changed')
                with self.assertRaisesRegex(RuntimeError, 'byte mismatch'):
                    driver.fixture_hashes()
                manifest.write_text(json.dumps({'../a': 'a' * 64}))
                with self.assertRaisesRegex(RuntimeError, 'Unsafe'):
                    driver.fixture_hashes()

    def test_full_snapshot_includes_new_jalo_source(self):
        roots = driver.new_type_roots()
        self.assertEqual(driver.REPO / 'reclazztest', roots['fixture'])
        self.assertEqual(driver.PLATFORM / 'bootstrap', roots['bootstrap'])
        self.assertEqual(8, len(roots))

    def test_experiment_absence_probe_does_not_seed_native_negative_class_cache(self):
        native = {'collisions': 0, 'matchingTables': [], 'baselinePresent': True, 'tenant': 'master'}
        with patch.object(driver, 'hac', side_effect=[native, {'model': False, 'jalo': False}]) as hac:
            driver.new_type_absent(load_classes=False)
            script = hac.call_args.args[0]
            self.assertNotIn('Class.forName', script)
            self.assertIn('getResource', script)
        with patch.object(driver, 'hac', side_effect=[native, {'model': False, 'jalo': False}]) as hac:
            driver.new_type_absent()
            self.assertIn('Class.forName', hac.call_args.args[0])

    def test_build_cleanup_refuses_the_normal_checkout(self):
        with patch.object(driver, 'stopped') as stopped:
            with self.assertRaisesRegex(RuntimeError, 'confined'):
                driver.rebuild_new_type(Path('/tmp'), driver.REPO.parent / 'reclazz', 'normal')
            stopped.assert_not_called()

    def test_active_writer_refuses_snapshot_deletion(self):
        with patch.object(driver, 'java_writers', return_value=['123']):
            with self.assertRaisesRegex(RuntimeError, 'writer'):
                driver.stopped()

    def test_first_failure_survives_recovery_failure(self):
        # Exercise the actual outer finally before any native mutation.
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); lock = root / 'SAP-astra.lock'
            with patch.object(driver, 'doctor', side_effect=RuntimeError('first failure')), patch.object(driver, 'java_writers', return_value=[]), patch.object(driver, 'start', side_effect=RuntimeError('recovery failure')):
                result = driver.new_type_profile(root, root / 'normal', lock)
            self.assertEqual('first failure', result['failure'])
            self.assertEqual('recovery failure', result['restoreFailure'])
            self.assertEqual('FAIL', result['verdict']); self.assertTrue(lock.exists())


if __name__ == '__main__':
    unittest.main()
