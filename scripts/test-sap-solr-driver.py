#!/usr/bin/env python3
"""Portable real boundary checks for Solr boot ownership and stopped recovery."""
from pathlib import Path
from sap_suite_contract import DEFAULT_CASES, DEFAULT_OUTCOME, DEFAULT_TEST_NAMES, NORMAL_FIXTURE_FILES
import importlib.util
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('solr_driver', Path(__file__).with_name('test-sap-solr-live.py'))
driver = importlib.util.module_from_spec(spec); spec.loader.exec_module(driver)
GATES = {'agentArgsFound': True, 'agentOptionsMatchConfig': True, 'autoCompile': True,
         'autoSolrReindex': False, 'resetStatics': False, 'autoUpdateRunningSystem': False, 'impexAllowRemove': False}


def normal():
    return {'solrserverLoaded': False, 'solrFixtureLoaded': False, 'reclazztest': True, 'reclazztestbackoffice': True,
            'counts': {t: 0 for t in ['SolrFacetSearchConfig', 'SolrIndexedType', 'SolrIndex', 'ReclazzSchemaProbe']},
            'tenants': [{'id': t, 'db': 'jdbc:hsqldb:file:' + str(driver.SAP_HOME / 'data/hsqldb/mydb') + ';shutdown=true'} for t in ['master', 'junit']]}


class DriverTest(unittest.TestCase):
    def skip(self, gates):
        with patch.object(driver, 'no_inner_writer'), patch.object(driver.support, 'get', return_value=gates), \
             patch.object(driver.tempfile, 'mkdtemp') as mkdir, patch.object(driver, 'profile') as attempt, patch('sys.argv', ['driver']):
            self.assertEqual(0, driver.main()); mkdir.assert_not_called(); attempt.assert_not_called()

    def test_missing_disabled_wrong_type_and_mismatched_gates_write_nothing(self):
        for key in GATES:
            gates = dict(GATES); del gates[key]; self.skip(gates)
            self.skip(dict(GATES, **{key: not GATES[key]}))
        self.skip(dict(GATES, autoSolrReindex='false'))

    def test_unreachable_server_zero_writes(self):
        with patch.object(driver, 'no_inner_writer'), patch.object(driver.support, 'get', side_effect=OSError('offline')), \
             patch.object(driver.tempfile, 'mkdtemp') as mkdir, patch('sys.argv', ['driver']):
            self.assertEqual(0, driver.main()); mkdir.assert_not_called()

    def test_profile_and_normal_runtime_options_match(self):
        driver.require_options(GATES, False); driver.require_options(dict(GATES, autoSolrReindex=True), True)
        with self.assertRaises(RuntimeError):
            driver.require_options(GATES, True)

    def test_foreign_tenant_or_missing_master_junit_refused(self):
        facts = normal(); driver.validate_normal(facts)
        for tenants in [[{'id': 'master', 'db': 'jdbc:postgresql:foreign'}, facts['tenants'][1]], facts['tenants'][:1]]:
            with self.assertRaises(RuntimeError):
                driver.validate_tenants(tenants)

    def test_foreign_physical_home_refused_before_fixture_or_directory_write(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); real = root / 'real'; real.mkdir(); alias = root / 'alias'; alias.symlink_to(real)
            with patch.object(driver, 'SAP_HOME', alias), patch.object(driver, 'no_inner_writer'), \
                 patch.object(driver.support, 'get', return_value=GATES), patch.object(driver.support, 'tenant_health'), \
                 patch.object(driver, 'normal_facts', side_effect=lambda: normal()), patch.object(driver.tempfile, 'mkdtemp') as mkdir, \
                 patch.object(driver, 'fixture_hashes') as fixtures, patch('sys.argv', ['driver']):
                self.assertEqual(0, driver.main()); mkdir.assert_not_called(); fixtures.assert_not_called()

    def test_profile_extension_or_owned_row_cannot_be_normal(self):
        for change in [{'solrserverLoaded': True}, {'solrFixtureLoaded': True}, {'reclazztestbackoffice': False}, {'counts': {}}]:
            with self.assertRaises(RuntimeError):
                driver.validate_normal(dict(normal(), **change))

    def test_wrapper_keeps_lf_and_crlf_and_unrelated_options(self):
        for newline in [b'\n', b'\r\n']:
            original = ('wrapper.java.additional.25=-javaagent:' + str(driver.support.JAR) + '=autoCompile=true').encode() + newline
            self.assertEqual(original.replace(b'autoCompile=true', b'autoCompile=true,autoSolrReindex=true'), driver.wrapper_bytes(original))

    def test_missing_duplicate_or_explicit_solr_wrapper_refused(self):
        original = ('wrapper.java.additional.25=-javaagent:' + str(driver.support.JAR) + '=autoCompile=true\n').encode()
        for data in [b'not an agent\n', original + original, original.replace(b'autoCompile=true', b'autoSolrReindex=false')]:
            with self.assertRaises(RuntimeError):
                driver.wrapper_bytes(data)

    def test_native_profile_context_preserves_unrelated_server_configuration(self):
        for newline in ['\n', '\r\n']:
            block = '<Context path="/reclazztest" docBase="' + str(driver.REPO / 'reclazztest/web/webroot') + '"><Manager pathname=""/><Loader platformHome="' + str(driver.PLATFORM) + '" className="de.hybris.tomcat.HybrisWebappLoader" deployName="default"/></Context>'
            original = ('<Server><Host autoDeploy="false">' + newline + block + newline + '</Host></Server>').encode()
            actual = driver.profile_server_bytes(original)
            self.assertIn(block.encode(), actual)
            self.assertIn(b'path="/reclazztestsolr"', actual)
            self.assertIn(('docBase="' + str(driver.EXT / 'web/webroot') + '"').encode(), actual)
            self.assertIn(b'<Host autoDeploy="false">', actual)
            self.assertEqual(2, actual.count(b'<Context '))

    def test_foreign_or_duplicate_context_refused(self):
        for xml in [b'<Server><Host/></Server>', b'<Context path="/reclazztestsolr"/><Host></Host>']:
            with self.assertRaises(RuntimeError):
                driver.profile_server_bytes(xml)

    def test_wildcard_or_multiple_listeners_refused(self):
        for text in ['p1\ncjava\nn*:8983\n', 'p1\nn127.0.0.1:8983\np2\nn[::1]:8983\n', 'p1\nn192.0.2.1:8983\n', '']:
            with self.assertRaises(RuntimeError):
                driver.parse_listener(text)
        self.assertEqual('123', driver.parse_listener('p123\ncjava\nf25\nn127.0.0.1:8983\n'))

    def test_foreign_solr_command_directories_refused(self):
        from types import SimpleNamespace
        result = SimpleNamespace(returncode=0, stdout='p123\nn127.0.0.1:8983\n')
        good = 'java -Dsolr.solr.home=' + str(driver.SOLR_CONF) + ' -Dsolr.data.home=' + str(driver.SOLR_DATA) + ' -Dsolr.log.dir=' + str(driver.SOLR_LOG)
        with patch.object(driver.subprocess, 'run', return_value=result), patch.object(driver.subprocess, 'check_output', return_value=good):
            self.assertEqual('123', driver.owned_listener()['pid'])
        for command in [good.replace(str(driver.SOLR_DATA), '/foreign/data'), 'java -Dsolr.solr.home=/foreign']:
            with patch.object(driver.subprocess, 'run', return_value=result), patch.object(driver.subprocess, 'check_output', return_value=command):
                with self.assertRaises(RuntimeError):
                    driver.owned_listener()

    def test_unowned_custom_entry_or_alias_is_not_removed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); owned = root / 'owned'; foreign = root / 'foreign'; owned.mkdir(); foreign.mkdir(); link = root / 'custom'
            link.mkdir()
            with self.assertRaises(RuntimeError):
                driver.remove_owned_link(link, owned)
            link.rmdir(); link.symlink_to(foreign)
            with self.assertRaises(RuntimeError):
                driver.remove_owned_link(link, owned)
            self.assertTrue(link.is_symlink()); link.unlink(); link.symlink_to(owned)
            driver.remove_owned_link(link, owned); self.assertFalse(link.is_symlink()); self.assertTrue(owned.exists())

    def test_live_writer_or_listener_blocks_actual_tree_restore(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); data = root / 'data'; data.mkdir(); (data / 'base').write_bytes(b'baseline')
            driver.capture(data, root / 'snapshot', lambda: None); (data / 'base').write_bytes(b'live')
            for reason in ['Tomcat survives', 'Solr survives', 'ant child survives']:
                with patch.object(driver.support, 'stopped', side_effect=RuntimeError(reason)):
                    with self.assertRaises(RuntimeError):
                        driver.restore(root / 'snapshot', data, driver.stopped)
                self.assertEqual(b'live', (data / 'base').read_bytes())
            with patch.object(driver.support, 'stopped'), patch.object(driver, 'port_open', return_value=True):
                with self.assertRaises(RuntimeError):
                    driver.restore(root / 'snapshot', data, driver.stopped)
            self.assertEqual(b'live', (data / 'base').read_bytes())

    def test_whole_config_data_recovery_removes_extra_core_directories(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ['config', 'data']:
                live = root / name; live.mkdir(); (live / 'baseline').write_bytes(b'original')
                driver.capture(live, root / (name + '-snapshot'), lambda: None)
                core = live / 'solr/owned/core'; core.mkdir(parents=True); (core / 'extra').write_bytes(b'index')
                driver.restore(root / (name + '-snapshot'), live, lambda: None)
                self.assertFalse((live / 'solr').exists()); self.assertEqual(b'original', (live / 'baseline').read_bytes())

    def test_surviving_inner_runner_prevents_stop_and_restore(self):
        with patch.object(driver, 'no_inner_writer', side_effect=RuntimeError('inner survives')), patch.object(driver.support, 'command') as command:
            with self.assertRaises(RuntimeError):
                driver.stop_all(Path('/unused'), 'restore', True)
            command.assert_not_called()

    def test_unknown_listener_is_never_stopped(self):
        with patch.object(driver, 'no_inner_writer'), patch.object(driver.support, 'command'), patch.object(driver, 'port_open', return_value=True), patch.object(driver, 'native') as native:
            with self.assertRaises(RuntimeError):
                driver.stop_all(Path('/unused'), 'restore', False)
            native.assert_not_called()


    def test_default_report_requires_exact_membership_and_outcome(self):
        runner = driver
        rows = [(name, 'PASS') for name in DEFAULT_TEST_NAMES]
        def log(selected, membership=45):
            return f'  Default committed membership: {membership} cases (Orika rollback included)\n' + ''.join(
                f'{name} | \x1b[32m{status}\x1b[0m | 1s |\n' for name, status in selected)
        valid = log(rows)
        invalid = [log(rows[:-1], 44), log(rows[:-1]),
                   log(rows + [('Hibernate L2 cache', 'SKIP')], 46),
                   log(rows + [('Hibernate L2 cache', 'PASS')], 46),
                   log(rows, 46),
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
