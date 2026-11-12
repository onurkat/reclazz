#!/usr/bin/env python3
"""Serialized clean-install setup/edit/restore steps; Claude supplies Chrome evidence."""
from pathlib import Path
from sap_suite_contract import DEFAULT_CASES, DEFAULT_OUTCOME, NORMAL_FIXTURE_FILES, default_result
import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
from contextlib import contextmanager
import re
import socket
import subprocess
import tempfile
import time
import uuid
from sap_items_snapshot import capture, inventory, verify

spec = importlib.util.spec_from_file_location('cms_support', Path(__file__).with_name('test-sap-cms-live.py'))
cms = importlib.util.module_from_spec(spec); spec.loader.exec_module(cms)
solr, support = cms.solr, cms.support
REPO, HOME, PLATFORM = cms.REPO, cms.SAP_HOME, cms.PLATFORM
NORMAL_EXT = REPO / 'reclazztestbackoffice'
EXT = REPO / 'reclazztestbackofficecfg'
LINK = HOME / 'bin/custom/reclazztestbackofficecfg'
LOCAL_EXT = HOME / 'config/localextensions.xml'
TEMPLATES = REPO / 'integration-test/src/main/resources/templates/backofficecfg'
LEASE = Path('/private/tmp/reclazz-backofficecfg-profile.lease')
CONFIG = EXT / 'resources/reclazztestbackofficecfg-backoffice-config.xml'
CONTROLLER = NORMAL_EXT / 'backoffice/src/com/onurkat/reclazztestbackoffice/widgets/ReclazzProbeController.java'
OWNER = 'com.onurkat.reclazztestbackoffice.widgets.ReclazzProbeController'
SETUP_LINES = b'\nbackoffice.cockpitng.reset.triggers=start\nbackoffice.cockpitng.reset.scope=cockpitConfig\n'


def remove_setup_lines(actual, original):
    if actual != original + SETUP_LINES:
        raise RuntimeError('Owned setup properties changed; refusing unrelated diff')
    return original


def targets():
    return {'config': HOME / 'config', 'data': HOME / 'data',
            'tomcat': PLATFORM / 'tomcat', 'bootstrap': PLATFORM / 'bootstrap',
            'native-temp': HOME / 'temp/hybris', 'normal-fixture': REPO / 'reclazztest',
            'backoffice-fixture': NORMAL_EXT, 'profile-fixture': EXT,
            'native-backoffice': HOME / 'bin/modules/backoffice-framework/backoffice'}


def media():
    # getStreamFromMedia signature is used by the installed native persistence strategy.
    return cms.native_read('''import groovy.json.JsonOutput
import de.hybris.platform.core.Registry
import java.security.MessageDigest
 def ctx=Registry.applicationContext
 def rows=ctx.getBean('flexibleSearchService').search('SELECT {pk} FROM {Media} WHERE {code}=?code',[code:'cockpitng-config']).result
 if(rows.size()!=1) throw new IllegalStateException('Expected exactly one config media')
 def stream=ctx.getBean('mediaService').getStreamFromMedia(rows[0]);byte[] bytes
 try {bytes=stream.readAllBytes()} finally {stream.close()}
 def text=new String(bytes,'UTF-8')
 return JsonOutput.toJson([sha256:HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(bytes)),size:bytes.length,
 v1:text.contains('reclazz_regression_v1'),v2:text.contains('reclazz_regression_v2')])''')


def preflight():
    cms.require_clean_home(); solr.no_inner_writer()
    solr.require_options(support.get(support.ENDPOINT + '/gates'), False)
    normal = solr.normal_facts(); solr.validate_normal(normal)
    cms.validate_normal_cms(cms.normal_cms_facts()); support.tenant_health()
    state = support.doctor()
    if state.get('watcherState') != 'watching' or state.get('buildHold') != 'none' or support.java_writers() != [str(state['pid'])]:
        raise RuntimeError('Native writer/agent not ready')
    if not normal.get('reclazztestbackoffice'):
        raise RuntimeError('Backoffice fixture not loaded')
    validate_custom(False); require_profile_absent(); profile_hashes()
    if len(cms.fixture_hashes()) != NORMAL_FIXTURE_FILES + 10 + 11 or len(solr.fixture_hashes()) != NORMAL_FIXTURE_FILES + 10:
        raise RuntimeError('Normal fixture membership mismatch')
    props = (HOME / 'config/local.properties').read_bytes()
    if re.search(rb'(?m)^\s*backoffice\.cockpitng\.reset\.(?:triggers|scope)\s*=', props):
        raise RuntimeError('Existing native reset overrides; no setup permitted')
    for path in targets().values():
        if path.resolve() != path:
            raise RuntimeError('Foreign snapshot root')
        inventory(path)
    return state


def save(folder, state):
    path = folder / 'state.json'; temporary = folder / 'state.pending'
    temporary.write_text(json.dumps(state, indent=2) + '\n'); temporary.replace(path)
    if json.loads(path.read_text()) != state:
        raise RuntimeError('Durable state read-back failed')


def load(folder):
    if folder.absolute() != folder.resolve() or folder.parent != Path('/private/tmp') or not folder.name.startswith('reclazz-backofficecfg-'):
        raise RuntimeError('Foreign recovery folder')
    state = json.loads((folder / 'state.json').read_text())
    if state.get('schema') != 2 or state.get('home') != str(HOME) or state.get('targets') != {k: str(v) for k,v in targets().items()}:
        raise RuntimeError('Recovery target identity mismatch')
    return state


@contextmanager
def lease(folder=None):
    # A lock serializes live operations; its durable owner survives between commands
    # and crashes. Never infer abandonment from a process exit.
    fd = os.open(LEASE, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'r+') as handle:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        owner = handle.read().strip()
        if (folder is None and owner) or (folder is not None and owner != str(folder)):
            raise RuntimeError('Backoffice profile lease belongs to another operation')
        yield handle


def set_lease(handle, folder):
    handle.seek(0); handle.truncate(); handle.write(str(folder) if folder else '')
    handle.flush(); os.fsync(handle.fileno())


def profile_hashes(allow_config=False):
    expected = {str(p.relative_to(TEMPLATES)): p.read_bytes() for p in TEMPLATES.rglob('*') if p.is_file()}
    if len(expected) != 3:
        raise RuntimeError('Expected exactly three owned profile templates')
    sources = subprocess.check_output(['git','--no-optional-locks','ls-files','--cached','--others','--exclude-standard','--',str(EXT)],cwd=REPO,text=True).splitlines()
    if set(sources) != {str((EXT/name).relative_to(REPO)) for name in expected}:
        raise RuntimeError('Unowned profile source membership')
    result = {}
    for name,data in expected.items():
        path = EXT / name; actual = path.read_bytes()
        allowed = [data, data.replace(b'reclazz_regression_v1',b'reclazz_regression_v2')] if allow_config and path == CONFIG else [data]
        if actual not in allowed:
            raise RuntimeError('Unowned profile source change: ' + name)
        result[name] = hashlib.sha256(actual).hexdigest()
    return result


def validate_custom(profile):
    for name,target in [('reclazztest', REPO/'reclazztest'), ('reclazztestbackoffice',NORMAL_EXT)]:
        link = HOME / 'bin/custom' / name
        if not link.is_symlink() or link.resolve() != target:
            raise RuntimeError('Foreign normal fixture symlink')
    exists = LINK.exists() or LINK.is_symlink()
    if exists and (not LINK.is_symlink() or LINK.resolve() != EXT):
        raise RuntimeError('Foreign profile custom entry')
    if exists != profile:
        raise RuntimeError('Profile link membership mismatch')


def profile_localextensions(original):
    text = original.decode()
    if text.count('</extensions>') != 1 or 'reclazztestbackofficecfg' in text:
        raise RuntimeError('Unexpected normal localextensions baseline')
    return text.replace('</extensions>', '    <extension name="reclazztestbackofficecfg"/>\n</extensions>').encode()


def require_profile_absent():
    validate_custom(False)
    if b'reclazztestbackofficecfg' in LOCAL_EXT.read_bytes():
        raise RuntimeError('Profile remains in localextensions before original-data boot')


def remove_profile(folder, state):
    original = bytes.fromhex(state['localextensionsBytes'])
    actual = LOCAL_EXT.read_bytes()
    if actual not in [original, profile_localextensions(original)]:
        raise RuntimeError('Unowned localextensions edit; refusing overwrite')
    if LINK.exists() or LINK.is_symlink():
        solr.remove_owned_link(LINK, EXT)
    LOCAL_EXT.write_bytes(original)
    require_profile_absent()


def log_offsets():
    return {str(p):p.stat().st_size for p in (HOME/'log/tomcat').glob('console-*.log')}


def refresh_failures(offsets):
    total = 0
    for path in (HOME/'log/tomcat').glob('console-*.log'):
        offset = offsets.get(str(path),0)
        if path.stat().st_size < offset:
            raise RuntimeError('Native log truncated; refresh count unavailable')
        with path.open('rb') as handle:
            handle.seek(offset); total += handle.read().count(b'Failed to refresh Spring bean')
    return total


def read_event(stream, connection, timeout):
    connection.settimeout(timeout)
    line = stream.readline()
    if not line:
        raise RuntimeError('Agent event stream closed')
    return json.loads(line)


def receipt(events, kind):
    if any(e.get('level') == 'ERROR' for e in events):
        raise RuntimeError('Backoffice change reported ERROR')
    if kind == 'config':
        change = next((i for i,e in enumerate(events) if e.get('level') == 'INFO' and
                       e.get('message') == 'Backoffice config changed: ' + CONFIG.name + ' [reclazztestbackofficecfg]'), None)
        if change is None:
            return None
        pattern = r'^Backoffice configuration reset as with F4 \(([1-9][0-9]*) services?\) for ' + re.escape(CONFIG.name) + r': open a new view to see it\. Configuration changed in the UI was reset too\.$'
        return next((e for e in events[change + 1:] if e.get('level') == 'OK' and re.fullmatch(pattern, e.get('message', ''))), None)
    compiled = next((i for i,e in enumerate(events) if e.get('level') == 'COMPILE' and
                     (e.get('message') == 'Compiled ' + CONTROLLER.name or e.get('message','').startswith('Compiled ' + CONTROLLER.name + ' ('))), None)
    if compiled is None:
        return None
    for event in events[compiled + 1:]:
        prefix = {'RELOAD': 'Reloaded ', 'STRUCTURAL_RELOAD': 'Structural reload: '}.get(event.get('level'))
        message = event.get('message', '')
        if prefix and message.startswith(prefix):
            tail = message[len(prefix):]
            if re.split(r'[ :]', tail, 1)[0] == OWNER and not tail.partition(' ')[2].startswith('(inner class)'):
                if kind == 'socket-add' and event['level'] != 'STRUCTURAL_RELOAD':
                    continue
                return event
    return None


def socket_shape(original, marker=None):
    if marker is None:
        return original
    text = original.decode()
    anchor = 'import com.hybris.cockpitng.annotations.ViewEvent;'
    if text.count(anchor) != 1 or not text.endswith('}\n'):
        raise RuntimeError('Unexpected owned controller baseline')
    text = text.replace(anchor, anchor + '\nimport com.hybris.cockpitng.annotations.SocketEvent;')
    method = '\n\t@SocketEvent(socketId = "probeInput")\n\tpublic void onProbe(final String value)\n\t{\n\t\tstatus.setValue("handled ' + marker + ': " + value);\n\t}\n'
    return (text[:-2] + method + '}\n').encode()


def verify_edit_media(before, after, data, kind):
    if kind == 'config':
        expected_v2 = b'reclazz_regression_v2' in data
        if after['v2'] != expected_v2 or after['v1'] == expected_v2 or after['sha256'] == before['sha256']:
            raise RuntimeError('Native F4 reset did not apply the owned config ID to media')
    elif after != before:
        raise RuntimeError('Socket edit unexpectedly changed persisted config media')


def write_and_wait(folder, state, path, data, kind):
    if data == path.read_bytes():
        raise RuntimeError('Expected distinct owned edit')
    before = media()
    if before != state.get('expectedMedia',state['setupMedia']):
        raise RuntimeError('Media changed outside experiment')
    doctor = support.doctor()
    if doctor['sessionId'] != state['experimentDoctor']['sessionId']:
        raise RuntimeError('Experiment JVM changed')
    events = []; cursor = str(uuid.uuid4())
    with socket.create_connection(('127.0.0.1', int(support.PORT_FILE.read_text())), 5) as connection:
        stream = connection.makefile('r')
        if read_event(stream, connection, 5).get('level') != 'CONNECTED':
            raise RuntimeError('No fresh event connection')
        # A new stream has no history replay. The cursor identifies this connection
        # and the ordered events collected strictly after this atomic save.
        staged = path.with_suffix(path.suffix + '.pending'); staged.write_bytes(data); staged.replace(path)
        deadline = time.monotonic() + 120
        outcome = None
        while outcome is None:
            left = deadline - time.monotonic()
            if left <= 0:
                raise RuntimeError('Backoffice exact receipt deadline')
            event = read_event(stream, connection, left)
            if event.get('level') != 'HEARTBEAT':
                events.append(event)
            outcome = receipt(events, kind)
    after = media()
    verify_edit_media(before, after, data, kind)
    if kind == 'config':
        state['expectedMedia'] = after
    result = {'kind': kind, 'cursor': cursor, 'sessionId': doctor['sessionId'],
              'sourceSha256': hashlib.sha256(data).hexdigest(), 'events': events,
              'receipt': outcome, 'media': after, 'rendered': 'UNRUN; Claude Chrome evidence required'}
    state['steps'].append(result); save(folder, state)
    print(json.dumps(result), flush=True)


def check_sources(state):
    current = cms.fixture_hashes()
    permitted = str(CONTROLLER.relative_to(REPO))
    if set(current) != set(state['fixtureHashes']) or any(current[name] != value for name,value in state['fixtureHashes'].items() if name != permitted):
        raise RuntimeError('Unowned normal fixture diff; refusing overwrite')
    source = bytes.fromhex(state['controllerBytes'])
    if CONTROLLER.read_bytes() not in [source,socket_shape(source,'v1'),socket_shape(source,'v2')]:
        raise RuntimeError('Unowned controller diff; refusing overwrite')
    current_profile = profile_hashes(allow_config=True)
    if set(current_profile) != set(state['profileHashes']) or any(value != state['profileHashes'][name] for name,value in current_profile.items() if EXT/name != CONFIG):
        raise RuntimeError('Profile source membership changed')


def edit(folder, state, operation):
    if state.get('phase') != 'prepared':
        raise RuntimeError('Profile is not prepared')
    solr.no_inner_writer(); solr.require_options(support.get(support.ENDPOINT + '/gates'), False)
    validate_custom(True)
    if LOCAL_EXT.read_bytes() != profile_localextensions(bytes.fromhex(state['localextensionsBytes'])):
        raise RuntimeError('Unowned profile localextensions change')
    check_sources(state)
    original = bytes.fromhex(state['controllerBytes']); config = bytes.fromhex(state['configBytes'])
    if CONTROLLER.read_bytes() not in [original, socket_shape(original, 'v1'), socket_shape(original, 'v2')]:
        raise RuntimeError('Unowned controller diff')
    changed_config = config.replace(b'reclazz_regression_v1', b'reclazz_regression_v2')
    if CONFIG.read_bytes() not in [config, changed_config]:
        raise RuntimeError('Unowned config diff')
    if operation.startswith('config-'):
        write_and_wait(folder, state, CONFIG, config if operation == 'config-v1' else changed_config, 'config')
    else:
        expected = {'socket-add': original, 'socket-edit': socket_shape(original,'v1'), 'socket-remove': socket_shape(original,'v2')}[operation]
        if CONTROLLER.read_bytes() != expected:
            raise RuntimeError('Socket step out of order')
        data = {'socket-add': socket_shape(original,'v1'), 'socket-edit': socket_shape(original,'v2'), 'socket-remove': original}[operation]
        write_and_wait(folder, state, CONTROLLER, data, operation)


def restore_profile(folder, state):
    if state['phase'] == 'restored':
        raise RuntimeError('Profile already restored')
    check_sources(state)
    # Verify ALL immutable backups before deleting any active tree or link.
    paths = targets()
    for name in paths:
        verify(folder/name)
    started = time.monotonic(); solr.stop_all(folder, 'restore', False)
    remove_profile(folder,state)
    cms.restore_trees(folder, paths)
    require_profile_absent()
    cms.native(folder, 'normal-rebuild', 'build', '-Dbuild.parallel=false')
    cms.restore_trees(folder, paths)
    if not all(inventory(path) == verify(folder/name) for name,path in paths.items()):
        raise RuntimeError('Snapshot restoration mismatch')
    if solr.custom_listing() != state['custom'] or support.digest(support.JAR) != state['agentSha256']:
        raise RuntimeError('Custom/agent identity changed')
    require_profile_absent()  # Must precede the first boot of original persisted data.
    normal = cms.start(folder, 'normal', False, state['normalExtensions'])
    final_media = media()
    if final_media != state['beforeMedia'] or final_media['v1'] or final_media['v2'] or cms.fixture_hashes() != state['fixtureHashes'] or profile_hashes() != state['profileHashes']:
        raise RuntimeError('Media/source baseline not restored')
    if normal['doctor']['sessionId'] == state['beforeDoctor']['sessionId']:
        raise RuntimeError('Recovery was not a fresh JVM')
    state.update(phase='restored', normalDoctor=normal['doctor'], restoredMedia=final_media,
                 recoverySeconds=round(time.monotonic()-started,3),refreshFailures=refresh_failures(state['logOffsets']))
    save(folder, state); print('BACKOFFICE_RESTORED ' + str(folder), flush=True)


def prepare():
    try:
        doctor = preflight(); before_media = media()
        if before_media['v1'] or before_media['v2']:
            raise RuntimeError('Owned config IDs already in original media')
    except Exception as error:
        print('BACKOFFICE SKIP ' + str(error) + '; zero writes'); return
    with lease() as handle:
        folder = Path(tempfile.mkdtemp(prefix='reclazz-backofficecfg-', dir='/private/tmp'))
        set_lease(handle,folder)
        print('Recovery directory: ' + str(folder), flush=True)
        state = {'schema':2,'home':str(HOME),'phase':'capturing','targets':{k:str(v) for k,v in targets().items()},
                 'beforeMedia':before_media,'beforeDoctor':doctor,'agentSha256':support.digest(support.JAR),
                 'custom':solr.custom_listing(),'normalExtensions':cms.normal_cms_facts()['extensions'],
                 'fixtureHashes':cms.fixture_hashes(),'profileHashes':profile_hashes(),
                 'controllerBytes':CONTROLLER.read_bytes().hex(),'configBytes':CONFIG.read_bytes().hex(),
                 'localextensionsBytes':LOCAL_EXT.read_bytes().hex(),'steps':[],'logOffsets':log_offsets()}
        save(folder,state)
        captured = False; started = time.monotonic()
        try:
            solr.stop_all(folder, 'capture', False)
            for name,path in targets().items(): capture(path,folder/name,solr.stopped)
            captured = True; state['phase']='captured';save(folder,state)
            validate_custom(False)
            LINK.symlink_to(EXT)
            LOCAL_EXT.write_bytes(profile_localextensions(bytes.fromhex(state['localextensionsBytes'])))
            cms.native(folder,'profile-build','build','-Dbuild.parallel=false')
            expected = sorted([*state['normalExtensions'],'reclazztestbackofficecfg'])
            experiment = cms.start(folder,'profile-normal',False,expected)
            setup_media = media();state['setupPath']='normal-module-merge'
            if not setup_media['v1']:
                solr.stop_all(folder,'setup-fallback',False)
                props = HOME/'config/local.properties';original=(folder/'config/files/local.properties').read_bytes()
                if props.read_bytes()!=original: raise RuntimeError('Unowned setup properties diff')
                props.write_bytes(original+SETUP_LINES)
                print('SETUP ONLY: native startup cockpitConfig reset; stopped DB snapshot retained.',flush=True)
                cms.start(folder,'setup-reset',False,expected)
                setup_media=media();solr.stop_all(folder,'setup-reset',False)
                props.write_bytes(remove_setup_lines(props.read_bytes(),original))
                experiment=cms.start(folder,'experiment',False,expected)
                if media()!=setup_media: raise RuntimeError('Media changed after reset triggers removed')
                state['setupPath']='startup-reset-fallback'
            if setup_media['sha256']==before_media['sha256'] or not setup_media['v1'] or setup_media['v2']:
                raise RuntimeError('Native setup did not seed exact v1')
            state.update(phase='prepared',setupMedia=setup_media,experimentDoctor=experiment['doctor'],setupSeconds=round(time.monotonic()-started,3))
            save(folder,state)
            print('BACKOFFICE_PREPARED (render UNRUN) '+str(folder),flush=True)
        except Exception as error:
            state['failure']=str(error);save(folder,state)
            if captured:
                restore_profile(folder,state);set_lease(handle,None)
            elif not support.java_writers():
                cms.start(folder,'uncaptured-normal',False,state['normalExtensions']);set_lease(handle,None)
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation',choices=['prepare','status','config-v1','config-v2','socket-add','socket-edit','socket-remove','restore'])
    parser.add_argument('--state',type=Path)
    args=parser.parse_args()
    if args.operation=='prepare':
        if args.state: parser.error('prepare creates its own private state')
        prepare(); return
    if not args.state: parser.error('--state is required')
    state=load(args.state)
    with lease(args.state) as handle:
        state=load(args.state)
        if args.operation=='restore':
            restore_profile(args.state,state);set_lease(handle,None)
        elif args.operation=='status':
            print(json.dumps({'phase':state['phase'],'media':media(),'steps':len(state['steps']),'refreshFailures':refresh_failures(state['logOffsets']),'rendered':'UNRUN; no scripted browser PASS'}))
        else: edit(args.state,state,args.operation)


if __name__=='__main__':
    main()
