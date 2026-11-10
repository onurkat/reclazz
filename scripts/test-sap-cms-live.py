#!/usr/bin/env python3
"""Serialized native CMS request-component/JSP profile, never general CMS coverage."""
from pathlib import Path
import argparse
import http.cookiejar
import importlib.util
import json
import os
import re
import shlex
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from sap_items_snapshot import capture, restore, inventory, verify

spec = importlib.util.spec_from_file_location('solr_support', Path(__file__).with_name('test-sap-solr-live.py'))
solr = importlib.util.module_from_spec(spec); spec.loader.exec_module(solr)
support = solr.support
REPO, SAP_HOME, PLATFORM = support.REPO, support.SAP_HOME, support.PLATFORM
EXT = REPO / 'reclazztestcms'
LINK = SAP_HOME / 'bin/custom/reclazztestcms'
ENDPOINT = support.BASE + '/reclazztestcms/v2/test/batch/cms'
MODULES = {
    'acceleratorcms': 'core-accelerator/acceleratorcms',
    'acceleratorfacades': 'core-accelerator/acceleratorfacades',
    'acceleratorservices': 'core-accelerator/acceleratorservices',
    'acceleratorstorefrontcommons': 'core-accelerator/deprecated/acceleratorstorefrontcommons',
    'cms2': 'web-content-management-system/cms2',
    'cms2lib': 'web-content-management-system/cms2lib',
    'cmsfacades': 'web-content-management-system/cmsfacades',
    'springintegrationlibs': 'platform/springintegrationlibs',
    'ticketsystem': 'customer-service/ticketsystem',
}


def native_read(script):
    """Owned localhost HAC, rollback-only; do not persist credentials or raw errors."""
    props = (SAP_HOME / 'config/local.properties').read_text()
    password = next(line.split('=', 1)[1].strip() for line in props.splitlines() if line.startswith('initialpassword.admin='))
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
                                        urllib.request.HTTPSHandler(context=support.TLS))
    page = opener.open(support.BASE + '/hac/login', timeout=15).read().decode()
    token = re.search(r'name="_csrf" value="([^"]+)"', page).group(1)
    try:
        opener.open(support.BASE + '/j_spring_security_check', urllib.parse.urlencode(
            {'j_username': 'admin', 'j_password': password, '_csrf': token}).encode(), timeout=15).read()
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise RuntimeError('Local native read login failed') from None
    password = None
    page = opener.open(support.BASE + '/console/scripting', timeout=15).read().decode()
    token = re.search(r'name="_csrf" content="([^"]+)"', page).group(1)
    request = urllib.request.Request(support.BASE + '/console/scripting/execute', data=urllib.parse.urlencode(
        {'script': script, 'scriptType': 'groovy', 'commit': 'false'}).encode(), headers={'X-CSRF-TOKEN': token, 'Accept': 'application/json'})
    result = json.load(opener.open(request, timeout=40))
    if result.get('stacktraceText'):
        raise RuntimeError('Rollback-only native CMS query failed')
    return json.loads(result['executionResult'])


def normal_cms_facts():
    return native_read('''import groovy.json.JsonOutput
import de.hybris.platform.core.Registry
def a=Class.forName('com.onurkat.reclazz.agent.ReclazzAgent',false,ClassLoader.systemClassLoader)
def f=a.getDeclaredField('platformContext');f.accessible=true
def extensions=f.get(null).getHybrisContext().getExtensions().keySet().sort()
def ctx=Registry.applicationContext
def classes=[:]
['de.hybris.platform.acceleratorstorefrontcommons.controllers.cms.AbstractCMSComponentController','de.hybris.platform.cms2.model.contents.components.CMSParagraphComponentModel'].each { n ->
 try {Class.forName(n,false,ctx.classLoader);classes[n]=true} catch(ClassNotFoundException absent) {classes[n]=false}
}
def types=[:]
['AbstractCMSComponent','CMSParagraphComponent','ContentCatalog'].each { n ->
 try {ctx.getBean('typeService').getComposedTypeForCode(n);types[n]=true} catch(de.hybris.platform.servicelayer.exceptions.UnknownIdentifierException absent) {types[n]=false}
}
return JsonOutput.toJson([extensions:extensions,classes:classes,types:types,beans:['cmsComponentService','cmsPageContextService'].collectEntries{[(it):ctx.containsBean(it)]}])''')


def validate_normal_cms(facts):
    if not isinstance(facts.get('extensions'), list) or any(name in facts['extensions'] for name in [*MODULES, 'reclazztestcms']):
        raise RuntimeError('CMS extension remains in normal runtime')
    for key, size in [('classes', 2), ('types', 3), ('beans', 2)]:
        values = facts.get(key)
        if not isinstance(values, dict) or len(values) != size or any(value is not False for value in values.values()):
            raise RuntimeError('Normal CMS absence mismatch: ' + key)


def fixture_hashes():
    result = solr.fixture_hashes()
    template = REPO / 'integration-test/src/main/resources/templates/cms'
    for source in sorted(template.rglob('*')):
        if source.is_file():
            relative = source.relative_to(template); actual = EXT / relative
            if source.read_bytes() != actual.read_bytes():
                raise RuntimeError('Dirty CMS fixture: ' + str(relative))
            result[str(actual.relative_to(REPO))] = support.digest(actual)
    if len(result) != 89:
        raise RuntimeError('Expected80 normal/Solr +9 owned CMS fixture files')
    return result


def snapshot_targets():
    # Complete trees also cover native commonweb sync/lib/classes, Ant server
    # deployment and SDK dependency build outputs, not just modelclasses.
    return {'config': SAP_HOME / 'config', 'data': SAP_HOME / 'data',
            'tomcat': PLATFORM / 'tomcat', 'bootstrap': PLATFORM / 'bootstrap',
            'native-temp': SAP_HOME / 'temp/hybris',
            'normal-fixture': REPO / 'reclazztest', 'backoffice-fixture': REPO / 'reclazztestbackoffice',
            'cms-fixture': EXT,
            **{'sdk-' + name: SAP_HOME / 'bin/modules' / path for name, path in MODULES.items()}}


def require_clean_home():
    if SAP_HOME.resolve() != SAP_HOME or not (SAP_HOME / 'bin/custom/reclazztest').samefile(REPO / 'reclazztest'):
        raise RuntimeError('Foreign clean installation')


def preflight():
    solr.no_inner_writer()
    require_clean_home()
    solr.require_options(support.get(support.ENDPOINT + '/gates'), False)
    solr.validate_normal(solr.normal_facts()); validate_normal_cms(normal_cms_facts())
    support.tenant_health()
    if LINK.exists() or LINK.is_symlink() or solr.port_open():
        raise RuntimeError('Existing CMS link or foreign service')
    fixture_hashes()
    state = support.doctor()
    if state.get('watcherState') != 'watching' or state.get('buildHold') != 'none' or support.java_writers() != [str(state['pid'])]:
        raise RuntimeError('Agent/native writer busy')
    for target in snapshot_targets().values():
        if target.resolve() != target:
            raise RuntimeError('Foreign physical snapshot root')
        inventory(target)


def profile_server_bytes(original):
    # Reuse the audited native Context derivation with only this profile's
    # owned name/physical root substituted, never a copied vendor template.
    if b'path="/reclazztestcms"' in original:
        raise RuntimeError('CMS Context already present')
    return solr.profile_server_bytes(original).replace(b'path="/reclazztestsolr"', b'path="/reclazztestcms"').replace(
        str(solr.EXT / 'web/webroot').encode(), str(EXT / 'web/webroot').encode())


def schema_configuration():
    # Native Initialization.isDrop checks presence, so droptables MUST be absent.
    return {'essential': 'false', 'localizetypes': 'false', 'clearhmc': 'false',
            'createProjectData': 'false', 'lucenesearch_rebuild.indexes': 'false', 'dryrun': 'false'}


def schema_arguments(config):
    return ['updatesystem', '-Dtenant=master', '-DtypeSystemOnly=true', '-DdryRun=false', '-DconfigFile=' + str(config), '-Dbuild.parallel=false']


def native(folder, label, *args):
    support.command(['/bin/bash', '-c', 'source ./setantenv.sh && ant ' + shlex.join(args)], folder / (label + '.log'), 900)


def stop(folder, label):
    solr.stop_all(folder, label, False)


def restore_trees(folder, targets):
    solr.no_inner_writer()
    for name in targets:
        verify(folder / name)
    for name, target in targets.items():
        restore(folder / name, target, solr.stopped)


def invalidate_profile_markers(folder):
    # Native ExtensionInfo uses <extension>_itemstouch; extension_build uses
    # <extension>_srctouch. Restoring classes without these leaves stale skips.
    verify(folder / 'native-temp')
    solr.no_inner_writer(); solr.stopped()
    touch = SAP_HOME / 'temp/hybris/touch'
    removed = []
    for name in [*MODULES, 'reclazztestcms']:
        for marker in sorted(touch.glob(name + '_*touch')):
            if not marker.is_file() or marker.is_symlink():
                raise RuntimeError('Unexpected native build marker')
            marker.unlink(); removed.append(marker.name)
    return removed


def start(folder, label, profile, normal_extensions):
    support.PORT_FILE.unlink(missing_ok=True)
    support.command(['./hybrisserver.sh', 'start'], folder / (label + '-start.log'), 90)
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        try:
            gates = support.get(support.ENDPOINT + '/gates')
        except Exception:
            time.sleep(2); continue
        solr.require_options(gates, False)
        try:
            state = support.doctor()
            if state.get('watcherState') != 'watching' or state.get('buildHold') != 'none':
                raise RuntimeError('Agent not ready')
            result = {'doctor': {key: state.get(key) for key in ['pid', 'sessionId', 'watcherState', 'buildHold']}, 'gates': gates}
            if profile:
                result['cms'] = support.get(ENDPOINT + '/facts')
            else:
                result['cms'] = normal_cms_facts(); validate_normal_cms(result['cms'])
                if result['cms']['extensions'] != normal_extensions:
                    raise RuntimeError('Normal extension set changed')
                solr.validate_normal(solr.normal_facts())
                result['health'] = support.tenant_health()
            return result
        except Exception:
            time.sleep(2)
    raise RuntimeError('Native CMS/normal readiness deadline')


def runner(folder, label, inject=False):
    env = {key: value for key, value in os.environ.items() if not key.startswith(
        ('RECLAZZ_TEST_', 'RECLAZZ_CMS_', 'RECLAZZ_SOLR_', 'RECLAZZ_ITEMS_', 'RECLAZZ_STATICS_'))}
    env.update(JAVA_HOME=str(support.JAVA_HOME), RECLAZZ_TEST_BASE_URL=support.BASE,
               RECLAZZ_TEST_PORT_FILE=str(support.PORT_FILE), RECLAZZ_TEST_EXT_PATH=str(REPO / 'reclazztest'),
               RECLAZZ_TEST_HYBRIS_HOME=str(SAP_HOME))
    if label == 'attempt':
        env.update(RECLAZZ_TEST_PROFILE='cms-attempt', RECLAZZ_CMS_RECOVERY=str(folder),
                   RECLAZZ_CMS_NONCE='reclazz-cms-' + str(uuid.uuid4()))
    if inject:
        env['RECLAZZ_CMS_INJECT_FAILURE'] = 'true'
    log = folder / (label + '.log')
    with log.open('w') as output:
        process = subprocess.Popen(['./gradlew', ':integration-test:run', '--console=plain'], cwd=REPO, env=env,
                                   stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait(900)
        except subprocess.TimeoutExpired:
            raise RuntimeError('Inner CMS writer deadline; process preserved PID=' + str(process.pid))
    text = re.sub(r'\x1b\[[0-9;]*m', '', log.read_text())
    return {'exitCode': code, 'counts': {status: len(re.findall(r'^.*\|\s*' + status + r'\s*\|', text, re.M))
                                       for status in ['PASS', 'FAIL', 'ERROR', 'SKIP']},
            'injectedFailure': 'Injected CMS failure after real rendered v2' in text,
            'default44': 'Default committed membership: 44 cases' in text}


def setup_api_probe(folder):
    cp = os.pathsep.join(str(path) for path in [PLATFORM / 'bootstrap/modelclasses',
                                               EXT / 'web/webroot/WEB-INF/classes'])
    log = folder / 'native-model-controller-api.txt'
    with log.open('w') as stream:
        for name in ['de.hybris.platform.cms2.model.contents.components.CMSParagraphComponentModel',
                     'de.hybris.platform.acceleratorstorefrontcommons.controllers.cms.AbstractCMSComponentController']:
            result = subprocess.run([str(support.JAVA_HOME / 'bin/javap'), '-p', '-classpath', cp, name],
                                    stdout=stream, stderr=subprocess.STDOUT)
            if result.returncode:
                raise RuntimeError('Native model/commonweb build gate unavailable')
    relative = 'de/hybris/platform/acceleratorstorefrontcommons/controllers/cms/AbstractCMSComponentController'
    source = SAP_HOME / 'bin/modules/core-accelerator/deprecated/acceleratorstorefrontcommons/commonweb/src' / (relative + '.java')
    copied = EXT / 'web/commonwebsrc/acceleratorstorefrontcommons' / (relative + '.java')
    compiled = EXT / 'web/webroot/WEB-INF/classes' / (relative + '.class')
    if source.read_bytes() != copied.read_bytes():
        raise RuntimeError('Native commonweb source changed during copy')
    (folder / 'native-provenance.json').write_text(json.dumps({'sourceSha256': support.digest(source),
        'classSha256': support.digest(compiled)}, indent=2) + '\n')


def profile(folder, number, inject, setup_only=False):
    root = folder / ('cycle-' + str(number)); root.mkdir()
    targets = snapshot_targets()
    wrapper = PLATFORM / 'tomcat/conf/wrapper.conf'; wrapper_original = wrapper.read_bytes()
    server = PLATFORM / 'tomcat/conf/server.xml'; profile_server = profile_server_bytes(server.read_bytes())
    fixtures = fixture_hashes(); custom = solr.custom_listing(); agent_hash = support.digest(support.JAR)
    before = support.doctor(); normal_extensions = normal_cms_facts()['extensions']
    logs = SAP_HOME / 'log/tomcat'; offsets = {path: path.stat().st_size for path in logs.glob('console-*.log')}
    result = {'number': number, 'injected': inject, 'setupOnly': setup_only, 'beforeDoctor': before}
    captured = False; linked = False
    cycle_started = time.monotonic(); timings = {}; result['seconds'] = timings
    try:
        stop(root, 'capture')
        for name, target in targets.items():
            capture(target, root / name, solr.stopped)
        state = {'home': str(SAP_HOME), 'snapshotsVerified': True, 'targets': {name: str(path) for name, path in targets.items()},
                 'wrapperBytes': wrapper_original.hex(), 'fixtureHashes': fixtures, 'agentSha256': agent_hash,
                 'customListing': custom, 'normalExtensions': normal_extensions}
        (root / 'state.json').write_text(json.dumps(state, indent=2) + '\n')
        if json.loads((root / 'state.json').read_text()) != state:
            raise RuntimeError('Durable CMS recovery state mismatch')
        captured = True
        result['invalidatedMarkers'] = invalidate_profile_markers(root)
        if LINK.exists() or LINK.is_symlink():
            raise RuntimeError('CMS custom entry appeared before provisioning')
        LINK.symlink_to(EXT); linked = True
        localext = SAP_HOME / 'config/localextensions.xml'; text = localext.read_text()
        if text.count('</extensions>') != 1 or any('name="' + name + '"' in text for name in [*MODULES, 'reclazztestcms']):
            raise RuntimeError('Unexpected extension baseline')
        additions = ''.join('    <extension name="' + name + '"/>\n' for name in [*MODULES, 'reclazztestcms'])
        localext.write_text(text.replace('</extensions>', additions + '</extensions>'))
        native(root, 'profile-build', 'build', '-Dbuild.parallel=false')
        setup_api_probe(root)
        result['nativeBuildAndApi'] = 'PASS'
        if not setup_only:
            config = root / 'schema-only.json'; config.write_text(json.dumps(schema_configuration(), indent=2) + '\n')
            # Verified native default-map keys: avoid project data being selected
            # by an inherited config property before configFile is merged.
            props = SAP_HOME / 'config/local.properties'
            props.write_bytes(props.read_bytes() + b'\nupdate.importEssentialData.enabled=false\nupdate.clearHmcConfiguration.enabled=false\nupdate.localizeTypes.enabled=false\nupdate.rebuildLucenesearchIndexes.enabled=false\nupdate.executeProjectData.extensionName.list=\n')
            result['schemaCommand'] = schema_arguments(config)
            native(root, 'profile-schema', *result['schemaCommand'])
            solr.stopped()
            # updatesystem depends on server, which may regenerate native conf.
            # Preserve that generated classpath, but require the same owned agent.
            arguments = lambda data: re.findall(rb'(?m)^wrapper\.java\.additional\.\d+=(-javaagent:\S+)', data)
            if arguments(wrapper.read_bytes()) != arguments(wrapper_original):
                raise RuntimeError('Native schema deployment changed the agent launcher')
            server.write_bytes(profile_server)
            result['profileBoot'] = start(root, 'profile', True, normal_extensions)
            timings['setup'] = round(time.monotonic() - cycle_started, 3)
            attempt_started = time.monotonic()
            result['attempt'] = runner(root, 'attempt', inject)
            timings['attempt'] = round(time.monotonic() - attempt_started, 3)
            attempt = result['attempt']
            expected = {'PASS': 0, 'FAIL': 1, 'ERROR': 0, 'SKIP': 0} if inject else {'PASS': 1, 'FAIL': 0, 'ERROR': 0, 'SKIP': 0}
            if attempt['counts'] != expected or (inject and (attempt['exitCode'] == 0 or not attempt['injectedFailure'])) or (not inject and attempt['exitCode'] != 0):
                raise RuntimeError('Native CMS attempt failed/skipped or injected failure missing')
    except Exception as failure:
        result['failure'] = str(failure)
    finally:
        if captured:
            recovery_started = time.monotonic()
            try:
                stop(root, 'restore')
                if linked:
                    solr.remove_owned_link(LINK, EXT)
                restore_trees(root, targets)
                native(root, 'normal-rebuild', 'build', '-Dbuild.parallel=false')
                restore_trees(root, targets)
                result['snapshotsRestoredBeforeBoot'] = all(inventory(target) == verify(root / name) for name, target in targets.items())
                if not result['snapshotsRestoredBeforeBoot'] or solr.custom_listing() != custom or fixture_hashes() != fixtures or support.digest(support.JAR) != agent_hash or wrapper.read_bytes() != wrapper_original:
                    raise RuntimeError('CMS source/config/agent/custom snapshot restoration mismatch')
                result['normalBoot'] = start(root, 'normal', False, normal_extensions)
                previous = result.get('profileBoot', {'doctor': before})['doctor']['sessionId']
                if result['normalBoot']['doctor']['sessionId'] == previous:
                    raise RuntimeError('Recovery was not a fresh normal JVM')
                result['fixtureFiles'] = len(fixtures); result['snapshotRoots'] = len(targets); result['restoration'] = 'PASS'
            except Exception as failure:
                result['restoration'] = 'FAIL'; result['restoreFailure'] = str(failure)
            timings['recovery'] = round(time.monotonic() - recovery_started, 3)
        else:
            try:
                if not support.java_writers():
                    result['normalBoot'] = start(root, 'uncaptured-normal', False, normal_extensions)
            except Exception as failure:
                result['restoreFailure'] = str(failure)
    native_log = b''
    for path in logs.glob('console-*.log'):
        if path.stat().st_size < offsets.get(path, 0):
            result['failure'] = 'Native log truncation'
        with path.open('rb') as stream:
            stream.seek(offsets.get(path, 0)); native_log += stream.read()
    (root / 'native.log').write_bytes(native_log)
    timings['wholeCycle'] = round(time.monotonic() - cycle_started, 3)
    result['nativeRefreshFailures'] = native_log.count(b'Failed to refresh Spring bean')
    if result['nativeRefreshFailures']:
        result['failure'] = 'Native bean refresh failures'
    result['verdict'] = 'PASS' if 'failure' not in result and result.get('restoration') == 'PASS' else 'FAIL'
    (root / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({key: result.get(key) for key in ['number', 'setupOnly', 'injected', 'verdict', 'restoration', 'failure', 'restoreFailure']}), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs', type=int, default=3); parser.add_argument('--failure-control', action='store_true')
    parser.add_argument('--setup-probe', action='store_true', help='Build/API/recovery diagnostic only; never CMS render acceptance')
    args = parser.parse_args()
    if args.runs < 1 or (args.setup_probe and args.failure_control):
        parser.error('Positive runs required; setup probe cannot be a failure control')
    try:
        preflight()
    except Exception as failure:
        print('CMS_PROFILE SKIP ' + str(failure) + '; zero writes'); return 0
    folder = Path(tempfile.mkdtemp(prefix='reclazz-cms-', dir='/private/tmp'))
    print('Recovery/evidence directory: ' + str(folder), flush=True)
    results = []
    count = 1 if args.setup_probe else args.runs + int(args.failure_control)
    for number in range(count):
        result = profile(folder, number + 1, args.failure_control and number == 0, args.setup_probe); results.append(result)
        if result['verdict'] != 'PASS':
            break
    console_offsets = {path: path.stat().st_size for path in (SAP_HOME / 'log/tomcat').glob('console-*.log')}
    baseline = runner(folder, 'normal-default') if not args.setup_probe and all(row['verdict'] == 'PASS' for row in results) else {'unrun': 'setup-only or profile failed'}
    default_tail = b''; console_intact = True
    for path in (SAP_HOME / 'log/tomcat').glob('console-*.log'):
        console_intact &= path.stat().st_size >= console_offsets.get(path, 0)
        with path.open('rb') as stream:
            stream.seek(console_offsets.get(path, 0)); default_tail += stream.read()
    (folder / 'normal-default-native.log').write_bytes(default_tail)
    baseline['nativeRefreshFailures'] = default_tail.count(b'Failed to refresh Spring bean')
    baseline['consoleIntact'] = console_intact
    passed = len(results) == count and all(row['verdict'] == 'PASS' for row in results)
    passed &= console_intact and baseline['nativeRefreshFailures'] == 0
    if not args.setup_probe:
        passed &= baseline.get('exitCode') == 0 and baseline.get('default44') is True and baseline.get('counts') == {'PASS': 43, 'FAIL': 0, 'ERROR': 0, 'SKIP': 1}
    (folder / 'summary.json').write_text(json.dumps({'verdict': 'PASS' if passed else 'FAIL', 'setupOnly': args.setup_probe, 'cycles': results, 'default': baseline}, indent=2) + '\n')
    print(('CMS_SETUP_PROBE ' if args.setup_probe else 'CMS_PROFILE ') + ('PASS' if passed else 'FAIL') + '; recovery evidence: ' + str(folder), flush=True)
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
