#!/usr/bin/env python3
"""Portable boundary/recovery checks for the real two-boot driver."""
from pathlib import Path
from sap_suite_contract import DEFAULT_CASES, DEFAULT_OUTCOME, DEFAULT_TEST_NAMES, NORMAL_FIXTURE_FILES
import importlib.util
import tempfile
import unittest
from unittest.mock import patch
import json

spec = importlib.util.spec_from_file_location('statics_driver', Path(__file__).with_name('test-sap-statics-live.py'))
driver = importlib.util.module_from_spec(spec); spec.loader.exec_module(driver)
GATES = {'agentArgsFound': True, 'agentOptionsMatchConfig': True, 'autoCompile': True,
         'resetStatics': False, 'autoUpdateRunningSystem': False, 'autoSolrReindex': False, 'impexAllowRemove': False}
BASELINE = {'direct': 'v1', 'retainedGetter': 'v1', 'version': 'body-v1', 'refused': 'call-v1',
            'count': 7, 'initializerCalls': 1, 'runtimeValue': 'startup', 'identity': 42}


class DriverTest(unittest.TestCase):
    def skipped(self, gates):
        with patch.object(driver.support, 'get', return_value=gates), patch.object(driver.tempfile, 'mkdtemp') as mkdir, \
             patch.object(driver, 'profile') as profile, patch('sys.argv', ['driver']):
            self.assertEqual(0, driver.main()); mkdir.assert_not_called(); profile.assert_not_called()

    def test_missing_disabled_wrong_type_and_mismatched_gates_write_nothing(self):
        for key in GATES:
            gates = dict(GATES); del gates[key]; self.skipped(gates)
            self.skipped(dict(GATES, **{key: not GATES[key]}))
        self.skipped(dict(GATES, resetStatics='false'))

    def test_unavailable_server_writes_nothing(self):
        with patch.object(driver.support, 'get', side_effect=OSError('offline')), patch.object(driver.tempfile, 'mkdtemp') as mkdir, patch('sys.argv', ['driver']):
            self.assertEqual(0, driver.main()); mkdir.assert_not_called()

    def test_enabled_and_disabled_boot_require_runtime_facts(self):
        driver.require_options(GATES, False)
        driver.require_options(dict(GATES, resetStatics=True), True)
        with self.assertRaises(RuntimeError):
            driver.require_options(GATES, True)

    def test_no_reinitialization_or_stale_reader_can_be_baseline(self):
        driver.require_baseline(BASELINE)
        for key, value in [('direct', 'v2'), ('retainedGetter', 'v2'), ('runtimeValue', 'previous-nonce'),
                           ('initializerCalls', 2), ('refused', 'call-v2'), ('count', 9), ('identity', None)]:
            with self.assertRaises(RuntimeError):
                driver.require_baseline(dict(BASELINE, **{key: value}))

    def test_exact_owned_wrapper_and_line_endings_preserved(self):
        jar = Path('/clean/agent.jar')
        for newline in [b'\n', b'\r\n']:
            original = b'wrapper.java.additional.1=-Xmx2G' + newline + b'wrapper.java.additional.25=-javaagent:/clean/agent.jar=autoCompile=true' + newline
            self.assertEqual(original.replace(b'autoCompile=true', b'autoCompile=true,resetStatics=true'), driver.wrapper_bytes(original, jar))

    def test_duplicate_foreign_or_explicit_option_refused(self):
        entry = b'wrapper.java.additional.25=-javaagent:/clean/agent.jar=autoCompile=true\n'
        for original in [entry + entry, entry.replace(b'/clean/agent.jar', b'/foreign.jar'),
                         entry.replace(b'autoCompile=true', b'resetStatics=false'), entry.replace(b'autoCompile=true', b'resetStatics=true')]:
            with self.assertRaises(RuntimeError):
                driver.wrapper_bytes(original, Path('/clean/agent.jar'))

    def test_attempt_skip_after_write_is_failure(self):
        for counts in [{'PASS': 0, 'FAIL': 0, 'ERROR': 0, 'SKIP': 1}, {'PASS': 1, 'FAIL': 1, 'ERROR': 0, 'SKIP': 0}]:
            with self.assertRaises(RuntimeError):
                driver.accept_attempt({'exitCode': 0, 'counts': counts}, False)

    def test_expected_failure_needs_exact_marker_and_failed_case(self):
        result = {'exitCode': 1, 'injectedFailure': True, 'counts': {'PASS': 0, 'FAIL': 1, 'ERROR': 0, 'SKIP': 0}}
        driver.accept_attempt(result, True)
        for change in [{'exitCode': 0}, {'injectedFailure': False}, {'counts': {'PASS': 0, 'FAIL': 0, 'ERROR': 1, 'SKIP': 0}}]:
            with self.assertRaises(RuntimeError):
                driver.accept_attempt(dict(result, **change), True)

    def test_recovery_restores_bytes_before_build_and_requires_fresh_normal_boot(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); source = root / 'StaticProbe.java'; config = root / 'wrapper.conf'
            originals = {source: b'baseline source'}; configs = {config: b'normal\r\n'}
            source.write_bytes(b'edited'); config.write_bytes(b'option on')
            order = []
            def build(*args):
                self.assertEqual(originals[source], source.read_bytes()); self.assertEqual(configs[config], config.read_bytes()); order.append('build')
            with patch.object(driver, 'ensure_no_inner_writer', side_effect=lambda: order.append('guard')), \
                 patch.object(driver.support, 'stop', side_effect=lambda *args: order.append('stop')), \
                 patch.object(driver, 'build', side_effect=build), patch.object(driver.support, 'digest', return_value='jar'), \
                 patch.object(driver.support, 'fixture_hashes', return_value={'fixture': 'hash'}), \
                 patch.object(driver, 'start', return_value={'identity': {'sessionId': 'fresh'}}) as start:
                driver.recover(root, originals, configs, 'jar', {'fixture': 'hash'}, {'old'})
                self.assertEqual(['guard', 'stop', 'build'], order); start.assert_called_once_with(root, 'normal', False)
                with self.assertRaisesRegex(RuntimeError, 'fresh JVM'):
                    driver.recover(root, originals, configs, 'jar', {'fixture': 'hash'}, {'fresh'})

    def test_surviving_writer_refuses_before_restoration(self):
        with patch.object(driver, 'ensure_no_inner_writer', side_effect=RuntimeError('writer remains')), patch.object(driver.support, 'stop') as stop:
            with self.assertRaisesRegex(RuntimeError, 'writer remains'):
                driver.recover(Path('/unused'), {}, {}, 'jar', {}, set())
            stop.assert_not_called()


    def test_default_report_requires_exact_membership_and_outcome(self):
        runner = driver
        rows = [(name, 'SKIP' if name == 'Hibernate L2 cache' else 'PASS') for name in DEFAULT_TEST_NAMES]
        def log(selected, membership=DEFAULT_CASES):
            return f'  Default committed membership: {membership} cases (Orika rollback included)\n' + ''.join(
                f'{name} | \x1b[32m{status}\x1b[0m | 1s |\n' for name, status in selected)
        valid = log(rows)
        invalid = [log(rows[:-1], DEFAULT_CASES-1), log(rows[:-1]),
                   log([(name, 'PASS') for name, _ in rows if name != 'Hibernate L2 cache']),
                   log([('replacement scenario', rows[0][1])] + rows[1:]),
                   log([(name, 'SKIP' if i == 0 else 'PASS') for i, (name, _) in enumerate(rows)])]
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
