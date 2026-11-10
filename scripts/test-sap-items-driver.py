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


if __name__ == '__main__':
    unittest.main()
