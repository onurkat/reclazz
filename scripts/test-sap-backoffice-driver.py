#!/usr/bin/env python3
"""Portable Backoffice edit/setup/restore contracts; no native or browser writes."""
from pathlib import Path
from sap_suite_contract import DEFAULT_CASES, DEFAULT_OUTCOME, DEFAULT_TEST_NAMES, NORMAL_FIXTURE_FILES
import importlib.util
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('driver',Path(__file__).with_name('test-sap-backoffice-live.py'))
d=importlib.util.module_from_spec(spec);spec.loader.exec_module(d)


def event(level,message):return {'level':level,'message':message}


class BackofficeDriverTest(unittest.TestCase):
    def test_owned_setup_lines_round_trip_and_any_other_diff_refused(self):
        original=b'normal=true\n'
        self.assertEqual(original,d.remove_setup_lines(original+d.SETUP_LINES,original))
        for changed in [original,original+d.SETUP_LINES+b'extra=true\n',b'foreign=true\n'+d.SETUP_LINES]:
            with self.assertRaises(RuntimeError):d.remove_setup_lines(changed,original)

    def test_missing_wrong_type_disabled_or_mismatched_gate_skips_before_writes(self):
        gates={'agentArgsFound':True,'agentOptionsMatchConfig':True,'autoCompile':True,'autoSolrReindex':False,'resetStatics':False,'autoUpdateRunningSystem':False,'impexAllowRemove':False}
        for key in gates:
            for candidate in [dict((k,v) for k,v in gates.items() if k!=key),dict(gates,**{key:not gates[key]}),dict(gates,**{key:str(gates[key])})]:
                with patch.object(d.cms,'require_clean_home'),patch.object(d.solr,'no_inner_writer'),patch.object(d.support,'get',return_value=candidate),patch.object(d.tempfile,'mkdtemp') as mkdir,patch.object(d.solr,'stop_all') as stop:
                    d.prepare();mkdir.assert_not_called();stop.assert_not_called()

    def test_unreachable_gate_skips_before_writes(self):
        with patch.object(d.cms,'require_clean_home'),patch.object(d.solr,'no_inner_writer'),patch.object(d.support,'get',side_effect=OSError('offline')),patch.object(d.tempfile,'mkdtemp') as mkdir:
            d.prepare();mkdir.assert_not_called()

    def test_existing_runner_skips_before_native_read_or_write(self):
        with patch.object(d.cms,'require_clean_home'),patch.object(d.solr,'no_inner_writer',side_effect=RuntimeError('runner')),patch.object(d.support,'get') as read,patch.object(d.tempfile,'mkdtemp') as mkdir:
            d.prepare();read.assert_not_called();mkdir.assert_not_called()

    def test_config_needs_exact_change_then_nonzero_reset_for_exact_file(self):
        change=event('INFO','Backoffice config changed: '+d.CONFIG.name+' [reclazztestbackofficecfg]')
        reset=event('OK','Backoffice configuration reset as with F4 (4 services) for '+d.CONFIG.name+': open a new view to see it. Configuration changed in the UI was reset too.')
        self.assertEqual(reset,d.receipt([change,reset],'config'))
        for events in [[reset],[reset,change],[change,event('OK',reset['message'].replace('(4 services)','(0 services)'))],[change,event('OK',reset['message'].replace(d.CONFIG.name,'other-backoffice-config.xml'))],[event('INFO',change['message'].replace('[reclazztestbackofficecfg]','[other]')),reset]]:
            self.assertIsNone(d.receipt(events,'config'))

    def test_socket_compile_before_exact_owner_reload_no_inner_or_prefix(self):
        compile=event('COMPILE','Compiled '+d.CONTROLLER.name+' (2ms)')
        reload=event('STRUCTURAL_RELOAD','Structural reload: '+d.OWNER+' (+1 method)')
        self.assertEqual(reload,d.receipt([compile,reload],'socket-add'))
        for events in [[reload,compile],[reload],[event('COMPILE','Compiled Other.java'),reload],[compile,event('STRUCTURAL_RELOAD',reload['message'].replace(d.OWNER,d.OWNER+'Other'))],[compile,event('RELOAD','Reloaded '+d.OWNER+' (inner class)')],[compile,event('RELOAD','Reloaded '+d.OWNER+' (2ms)')]]:
            self.assertIsNone(d.receipt(events,'socket-add'))

    def test_cache_only_or_manual_warn_receipt_cannot_prove_config_apply(self):
        change=event('INFO','Backoffice config changed: '+d.CONFIG.name+' [reclazztestbackofficecfg]')
        for message in ['Backoffice configuration cache reset (1 service) for '+d.CONFIG.name+': the next view open re-reads the configuration.',
                        d.CONFIG.name+' saved, not applied; press F4 and reset cockpit configuration in Backoffice (backofficeConfigReset=false).']:
            for level in ['OK','WARN']:
                self.assertIsNone(d.receipt([change,event(level,message)],'config'))

    def test_native_media_must_replace_id_and_change_hash_in_both_directions(self):
        v1={'sha256':'one','size':10,'v1':True,'v2':False}
        v2={'sha256':'two','size':10,'v1':False,'v2':True}
        d.verify_edit_media(v1,v2,b'reclazz_regression_v2','config')
        d.verify_edit_media(v2,v1,b'reclazz_regression_v1','config')
        for before,after,data in [(v1,v1,b'reclazz_regression_v2'),(v2,v2,b'reclazz_regression_v1'),
                                  (v1,dict(v2,v1=True),b'reclazz_regression_v2'),
                                  (v1,dict(v2,sha256='one'),b'reclazz_regression_v2')]:
            with self.assertRaises(RuntimeError):d.verify_edit_media(before,after,data,'config')
        d.verify_edit_media(v1,v1,b'java','socket-add')
        with self.assertRaises(RuntimeError):d.verify_edit_media(v1,v2,b'java','socket-add')

    def test_error_cannot_be_hidden_by_success(self):
        with self.assertRaises(RuntimeError):d.receipt([event('ERROR','failed')],'config')

    def test_socket_shapes_preserve_baseline_and_have_single_public_typed_handler(self):
        original=d.CONTROLLER.read_bytes()
        self.assertNotIn(b'@SocketEvent',original)
        v1=d.socket_shape(original,'v1');v2=d.socket_shape(original,'v2')
        self.assertIn(b'public void onProbe(final String value)',v1)
        self.assertEqual(v1.replace(b'handled v1:',b'handled v2:'),v2)
        self.assertEqual(original,d.socket_shape(original))
        self.assertEqual(1,v1.count(b'@SocketEvent'))

    def test_snapshots_cover_all_native_build_outputs_without_overlap(self):
        roots=d.targets();self.assertEqual(9,len(roots));self.assertIn('native-backoffice',roots);self.assertIn('native-temp',roots)
        for path in roots.values():self.assertFalse(any(path!=other and path in other.parents for other in roots.values()))

    def test_foreign_state_folder_refused(self):
        with self.assertRaises(RuntimeError):d.load(Path('/private/tmp/unowned'))

    def test_profile_is_separate_and_normal_memberships_unchanged(self):
        self.assertEqual(NORMAL_FIXTURE_FILES+10,len(d.solr.fixture_hashes()))
        self.assertEqual(NORMAL_FIXTURE_FILES+19,len(d.cms.fixture_hashes()))
        self.assertEqual(3,len(d.profile_hashes()))
        self.assertNotIn('reclazz_regression_v1',(d.NORMAL_EXT/'resources/reclazztestbackoffice-backoffice-config.xml').read_text())

    def test_socket_removal_accepts_both_exact_owner_receipts(self):
        compile=event('COMPILE','Compiled '+d.CONTROLLER.name)
        for level,prefix in [('RELOAD','Reloaded '),('STRUCTURAL_RELOAD','Structural reload: ')]:
            reload=event(level,prefix+d.OWNER+' (1ms)')
            self.assertEqual(reload,d.receipt([compile,reload],'socket-remove'))
            self.assertIsNone(d.receipt([compile,event(level,prefix+d.OWNER+'Other')],'socket-remove'))

    def test_durable_lease_refuses_second_prepare_between_processes(self):
        with tempfile.TemporaryDirectory() as temporary,patch.object(d,'LEASE',Path(temporary)/'lease'):
            owner=Path(temporary)/'owner'
            with d.lease() as handle:d.set_lease(handle,owner)
            with self.assertRaises(RuntimeError):
                with d.lease():self.fail('second prepare entered')
            with self.assertRaises(RuntimeError):
                with d.lease(Path(temporary)/'foreign'):self.fail('foreign edit entered')
            with d.lease(owner) as handle:d.set_lease(handle,None)
            with d.lease():pass

    def test_owned_link_and_localextensions_removed_before_original_data_boot(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary).resolve();home=root/'home';custom=home/'bin/custom';custom.mkdir(parents=True)
            normal=root/'reclazztestbackoffice';normal.mkdir();fixture=root/'reclazztest';fixture.mkdir();ext=root/'profile';ext.mkdir()
            (custom/'reclazztest').symlink_to(fixture);(custom/'reclazztestbackoffice').symlink_to(normal)
            link=custom/'reclazztestbackofficecfg';link.symlink_to(ext)
            props=root/'localextensions.xml';original=b'<hybrisconfig><extensions>\n</extensions></hybrisconfig>'
            state={'localextensionsBytes':original.hex()};props.write_bytes(d.profile_localextensions(original))
            with patch.object(d,'HOME',home),patch.object(d,'REPO',root),patch.object(d,'NORMAL_EXT',normal),patch.object(d,'EXT',ext),patch.object(d,'LINK',link),patch.object(d,'LOCAL_EXT',props):
                with self.assertRaises(RuntimeError):d.require_profile_absent()
                d.remove_profile(root,state)
                d.require_profile_absent();self.assertEqual(original,props.read_bytes());self.assertFalse(link.is_symlink())
                props.write_bytes(d.profile_localextensions(original));link.symlink_to(ext)
                order=[]
                with patch.object(d,'check_sources'),patch.object(d,'targets',return_value={}),patch.object(d.solr,'stop_all'),patch.object(d.cms,'restore_trees',side_effect=lambda *a:order.append('restore')),patch.object(d.cms,'native'),patch.object(d.solr,'custom_listing',return_value={}),patch.object(d.support,'digest',return_value='agent'),patch.object(d.cms,'start',side_effect=lambda *a:(d.require_profile_absent(),order.append('boot'),(_ for _ in ()).throw(RuntimeError('stop test')))):
                    state.update(phase='prepared',custom={},agentSha256='agent',normalExtensions=[])
                    with self.assertRaisesRegex(RuntimeError,'stop test'):d.restore_profile(root,state)
                self.assertEqual(['restore','restore','boot'],order)
                link.symlink_to(root/'foreign')
                with self.assertRaises(RuntimeError):d.validate_custom(True)

    def test_unrelated_localextensions_change_refuses_removal(self):
        with tempfile.TemporaryDirectory() as temporary:
            p=Path(temporary)/'localextensions.xml';p.write_bytes(b'foreign')
            with patch.object(d,'LOCAL_EXT',p),patch.object(d.solr,'remove_owned_link') as remove:
                with self.assertRaises(RuntimeError):d.remove_profile(Path(temporary),{'localextensionsBytes':b'<extensions></extensions>'.hex()})
                remove.assert_not_called();self.assertEqual(b'foreign',p.read_bytes())

    def test_real_restore_refuses_writer_before_deleting_any_target(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);live=root/'live';live.mkdir();(live/'base').write_bytes(b'baseline')
            d.capture(live,root/'snap',lambda:None);(live/'base').write_bytes(b'current')
            with patch.object(d.solr,'no_inner_writer'),patch.object(d.solr,'stopped',side_effect=RuntimeError('writer')):
                with self.assertRaises(RuntimeError):d.cms.restore_trees(root,{'snap':live})
            self.assertEqual(b'current',(live/'base').read_bytes())


    def test_default_report_requires_exact_membership_and_outcome(self):
        runner = d.cms
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


if __name__=='__main__':unittest.main()
