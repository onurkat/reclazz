#!/usr/bin/env python3
"""Portable native CMS provisioning/recovery boundaries; no SAP writes."""
from pathlib import Path
from sap_suite_contract import DEFAULT_CASES, DEFAULT_OUTCOME, DEFAULT_TEST_NAMES, NORMAL_FIXTURE_FILES
import importlib.util
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('cms_driver', Path(__file__).with_name('test-sap-cms-live.py'))
driver = importlib.util.module_from_spec(spec); spec.loader.exec_module(driver)
GATES = {'agentArgsFound': True, 'agentOptionsMatchConfig': True, 'autoCompile': True,
         'autoSolrReindex': False, 'resetStatics': False, 'autoUpdateRunningSystem': False, 'impexAllowRemove': False}


def normal():
    return {'extensions': ['reclazztest', 'reclazztestbackoffice'],
            'classes': {'controller': False, 'model': False},
            'types': {'component': False, 'paragraph': False, 'catalog': False},
            'beans': {'componentService': False, 'pageContextService': False}}


class CmsDriverTest(unittest.TestCase):
    def skip(self, gates):
        with patch.object(driver.solr, 'no_inner_writer'), patch.object(driver, 'require_clean_home'), patch.object(driver.support, 'get', return_value=gates) as read, \
             patch.object(driver.tempfile, 'mkdtemp') as mkdir, patch.object(driver, 'profile') as attempt, patch('sys.argv', ['driver']):
            self.assertEqual(0, driver.main()); read.assert_called_once(); mkdir.assert_not_called(); attempt.assert_not_called()

    def test_missing_disabled_wrong_type_mismatched_gate_zero_writes(self):
        for key in GATES:
            gates = dict(GATES); del gates[key]; self.skip(gates)
            self.skip(dict(GATES, **{key: not GATES[key]}))
            self.skip(dict(GATES, **{key: str(GATES[key])}))

    def test_unreachable_server_zero_writes(self):
        with patch.object(driver.solr, 'no_inner_writer'), patch.object(driver, 'require_clean_home'), patch.object(driver.support, 'get', side_effect=OSError('offline')) as read, \
             patch.object(driver.tempfile, 'mkdtemp') as mkdir, patch('sys.argv', ['driver']):
            self.assertEqual(0, driver.main()); read.assert_called_once(); mkdir.assert_not_called()

    def test_foreign_home_refused_before_native_read_or_directory_write(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); real = root / 'real'; real.mkdir(); alias = root / 'alias'; alias.symlink_to(real)
            with patch.object(driver, 'SAP_HOME', alias), patch.object(driver.solr, 'no_inner_writer'), \
                 patch.object(driver.support, 'get') as read, patch.object(driver.tempfile, 'mkdtemp') as mkdir, patch('sys.argv', ['driver']):
                self.assertEqual(0, driver.main()); read.assert_not_called(); mkdir.assert_not_called()

    def test_existing_cms_extension_or_native_type_cannot_be_normal(self):
        driver.validate_normal_cms(normal())
        for key in ['classes', 'types', 'beans']:
            facts = normal(); facts[key][next(iter(facts[key]))] = True
            with self.assertRaises(RuntimeError):
                driver.validate_normal_cms(facts)
        facts = normal(); facts['extensions'].append('cms2')
        with self.assertRaises(RuntimeError):
            driver.validate_normal_cms(facts)

    def test_missing_or_wrong_type_absence_facts_fail(self):
        for key in ['classes', 'types', 'beans']:
            facts = normal(); del facts[key]
            with self.assertRaises(RuntimeError):
                driver.validate_normal_cms(facts)
            facts = normal(); facts[key][next(iter(facts[key]))] = 'false'
            with self.assertRaises(RuntimeError):
                driver.validate_normal_cms(facts)

    def test_schema_configuration_never_supplies_drop_even_false(self):
        config = driver.schema_configuration()
        self.assertNotIn('droptables', config)
        self.assertEqual('false', config['essential']); self.assertEqual('false', config['createProjectData'])
        self.assertEqual('false', config['clearhmc']); self.assertEqual('false', config['localizetypes'])
        self.assertFalse(any(key.endswith('_sample') for key in config))
        args = driver.schema_arguments(Path('/owned/schema.json'))
        self.assertIn('-Dtenant=master', args); self.assertIn('-DtypeSystemOnly=true', args)
        self.assertIn('-DconfigFile=/owned/schema.json', args); self.assertFalse(any('junit' in arg for arg in args))

    def test_native_context_derivation_preserves_normal_and_adds_only_owned_cms(self):
        block = '<Context path="/reclazztest" docBase="' + str(driver.REPO / 'reclazztest/web/webroot') + '"><Manager pathname=""/><Loader platformHome="' + str(driver.PLATFORM) + '" className="de.hybris.tomcat.HybrisWebappLoader"/></Context>'
        original = ('<Server><Host autoDeploy="false">' + block + '</Host></Server>').encode()
        result = driver.profile_server_bytes(original)
        self.assertIn(block.encode(), result); self.assertIn(b'path="/reclazztestcms"', result)
        self.assertIn(str(driver.EXT / 'web/webroot').encode(), result)
        self.assertNotIn(b'path="/reclazztestsolr"', result)
        with self.assertRaises(RuntimeError):
            driver.profile_server_bytes(result)

    def test_snapshot_roots_include_native_commonweb_outputs_and_full_tomcat(self):
        roots = driver.snapshot_targets()
        self.assertEqual(17, len(roots)); self.assertEqual(driver.PLATFORM / 'tomcat', roots['tomcat'])
        self.assertEqual(driver.EXT, roots['cms-fixture'])
        self.assertEqual(driver.PLATFORM / 'bootstrap', roots['bootstrap'])
        self.assertEqual(9, sum(name.startswith('sdk-') for name in roots))
        for name, path in roots.items():
            self.assertFalse(any(path != other and path in other.parents for other in roots.values()))

    def test_snapshotted_markers_invalidate_only_profile_dependencies_while_stopped(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary) / 'home'; touch = home / 'temp/hybris/touch'; touch.mkdir(parents=True)
            for name in ['cms2_itemstouch', 'cms2_srctouch', 'reclazztestcms_srctouch', 'core_srctouch', 'other_file']:
                (touch / name).write_bytes(b'marker')
            folder = Path(temporary) / 'recovery'; folder.mkdir()
            driver.capture(home / 'temp/hybris', folder / 'native-temp', lambda: None)
            with patch.object(driver, 'SAP_HOME', home), patch.object(driver.solr, 'no_inner_writer'), patch.object(driver.solr, 'stopped', side_effect=RuntimeError('writer')):
                with self.assertRaises(RuntimeError): driver.invalidate_profile_markers(folder)
            self.assertTrue((touch / 'cms2_srctouch').exists())
            with patch.object(driver, 'SAP_HOME', home), patch.object(driver.solr, 'no_inner_writer'), patch.object(driver.solr, 'stopped'):
                removed = driver.invalidate_profile_markers(folder)
            self.assertEqual({'cms2_itemstouch', 'cms2_srctouch', 'reclazztestcms_srctouch'}, set(removed))
            self.assertEqual({'core_srctouch', 'other_file'}, {f.name for f in touch.iterdir()})

    def test_live_writer_blocks_real_restore_without_deletion(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); live = root / 'live'; live.mkdir(); (live / 'base').write_bytes(b'v1')
            driver.capture(live, root / 'snapshot', lambda: None); (live / 'base').write_bytes(b'v2')
            for reason in ['Tomcat survives', 'ant survives', 'HSQLDB lock remains']:
                with patch.object(driver.solr, 'no_inner_writer'), patch.object(driver.solr, 'stopped', side_effect=RuntimeError(reason)):
                    with self.assertRaises(RuntimeError):
                        driver.restore_trees(root, {'snapshot': live})
                self.assertEqual(b'v2', (live / 'base').read_bytes())
            with patch.object(driver.solr, 'no_inner_writer', side_effect=RuntimeError('runner survives')):
                with self.assertRaises(RuntimeError):
                    driver.restore_trees(root, {'snapshot': live})
            self.assertEqual(b'v2', (live / 'base').read_bytes())

    def test_complete_tree_restore_removes_vendor_build_outputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); live = root / 'live'; live.mkdir(); (live / 'own.java').write_bytes(b'own')
            driver.capture(live, root / 'snapshot', lambda: None)
            extra = live / 'web/commonwebsrc/vendor'; extra.mkdir(parents=True); (extra / 'native.java').write_bytes(b'build')
            with patch.object(driver.solr, 'no_inner_writer'), patch.object(driver.solr, 'stopped'):
                driver.restore_trees(root, {'snapshot': live})
            self.assertFalse((live / 'web').exists()); self.assertEqual(b'own', (live / 'own.java').read_bytes())

    def test_corrupt_last_snapshot_refuses_before_first_tree_deletion(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); targets = {}
            for name in ['one', 'two']:
                live = root / ('live-' + name); live.mkdir(); (live / 'base').write_bytes(b'v1')
                driver.capture(live, root / name, lambda: None); (live / 'base').write_bytes(b'live')
                targets[name] = live
            (root / 'two/files/base').write_bytes(b'corrupt')
            with patch.object(driver.solr, 'no_inner_writer'), patch.object(driver.solr, 'stopped'):
                with self.assertRaises(RuntimeError):
                    driver.restore_trees(root, targets)
            self.assertTrue(all((live / 'base').read_bytes() == b'live' for live in targets.values()))

    def test_unowned_custom_entry_or_symlink_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); own = root / 'own'; own.mkdir(); foreign = root / 'foreign'; foreign.mkdir()
            link = root / 'link'; link.symlink_to(foreign)
            with self.assertRaises(RuntimeError):
                driver.solr.remove_owned_link(link, own)
            self.assertTrue(link.is_symlink()); link.unlink(); link.symlink_to(own)
            driver.solr.remove_owned_link(link, own); self.assertFalse(link.is_symlink())

    def test_setup_probe_is_explicitly_not_render_acceptance(self):
        # No normal/default runner or CMS attempt may be fabricated for setup-only.
        import contextlib, io
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(driver, 'preflight'), patch.object(driver.tempfile, 'mkdtemp', return_value=temporary), \
                 patch.object(driver, 'profile', return_value={'verdict': 'PASS'}) as profile, patch.object(driver, 'runner') as runner, \
                 patch('sys.argv', ['driver', '--setup-probe']), contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(0, driver.main()); runner.assert_not_called()
                self.assertIn('CMS_SETUP_PROBE PASS', output.getvalue()); self.assertNotIn('CMS_PROFILE PASS', output.getvalue())
                self.assertTrue(profile.call_args.args[-1])


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
