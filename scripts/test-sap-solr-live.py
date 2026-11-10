#!/usr/bin/env python3
"""Owned Solr boot layer; acceptance includes stopped full-tree recovery and default44."""
from pathlib import Path
import argparse
import hashlib
import http.cookiejar
import importlib.util
import json
import os
import re
import shlex
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from sap_items_snapshot import capture, restore, inventory, verify

spec = importlib.util.spec_from_file_location('items_support', Path(__file__).with_name('test-sap-items-live.py'))
support = importlib.util.module_from_spec(spec); spec.loader.exec_module(support)
REPO, SAP_HOME, PLATFORM = support.REPO, support.SAP_HOME, support.PLATFORM
EXT = REPO / 'reclazztestsolr'
LINK = SAP_HOME / 'bin/custom/reclazztestsolr'
ENDPOINT = support.BASE + '/reclazztestsolr/v2/test/batch/solr'
SOLR_CONF = SAP_HOME / 'config/solr/instances/default'
SOLR_DATA = SAP_HOME / 'data/solr/instances/reclazz-profile'
SOLR_LOG = SAP_HOME / 'data/solr-profile-log'
SOLR_DIST = SAP_HOME / 'bin/modules/search-and-navigation/solrserver/resources/solr'
PORT = 8983


def port_open():
    try:
        with socket.create_connection(('127.0.0.1', PORT), 1):
            return True
    except OSError:
        return False


def custom_listing(root=None):
    root = Path(root or SAP_HOME / 'bin/custom')
    return {p.name: ('symlink:' + os.readlink(p) if p.is_symlink() else 'directory' if p.is_dir() else 'file')
            for p in sorted(root.iterdir())}


def fixture_hashes():
    result = support.fixture_hashes()
    for template in sorted((REPO / 'integration-test/src/main/resources/templates/solr').rglob('*')):
        if template.is_file():
            relative = template.relative_to(REPO / 'integration-test/src/main/resources/templates/solr')
            path = EXT / relative
            if path.read_bytes() != template.read_bytes():
                raise RuntimeError('Dirty profile fixture: ' + str(relative))
            result[str(path.relative_to(REPO))] = support.digest(path)
    if len(result) != 80:
        raise RuntimeError('Expected70 normal +10 profile fixture files')
    return result


def require_options(gates, enabled):
    for key, expected in {'agentArgsFound': True, 'agentOptionsMatchConfig': True, 'autoCompile': True,
                          'autoSolrReindex': enabled, 'resetStatics': False,
                          'autoUpdateRunningSystem': False, 'impexAllowRemove': False}.items():
        if type(gates.get(key)) is not bool or gates[key] != expected:
            raise RuntimeError('Boot gate mismatch: ' + key + '=' + str(gates.get(key)))


def validate_tenants(tenants):
    expected = 'jdbc:hsqldb:file:' + str(SAP_HOME / 'data/hsqldb/mydb')
    if not isinstance(tenants, list) or not {'master', 'junit'}.issubset({t.get('id') for t in tenants}):
        raise RuntimeError('Missing native tenant facts')
    if any(t.get('db', '').split(';', 1)[0] != expected for t in tenants):
        raise RuntimeError('Foreign tenant DB; no provisioning permitted')


def normal_facts():
    """Rollback-only native query on the authorized localhost server; never logs auth."""
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
    script = '''import groovy.json.JsonOutput
import de.hybris.platform.core.Registry
def a=Class.forName('com.onurkat.reclazz.agent.ReclazzAgent',false,ClassLoader.systemClassLoader)
def f=a.getDeclaredField('platformContext');f.accessible=true
def extensions=f.get(null).getHybrisContext().getExtensions().keySet()
def fs=Registry.applicationContext.getBean('flexibleSearchService')
def counts=['SolrFacetSearchConfig','SolrIndexedType','SolrIndex','ReclazzSchemaProbe'].collectEntries{[(it):fs.search('SELECT {pk} FROM {'+it+'}').result.size()]}
def tenants=[Registry.masterTenant]+Registry.slaveTenants.values()
return JsonOutput.toJson([solrserverLoaded:extensions.contains('solrserver'),solrFixtureLoaded:extensions.contains('reclazztestsolr'),reclazztest:extensions.contains('reclazztest'),reclazztestbackoffice:extensions.contains('reclazztestbackoffice'),counts:counts,tenants:tenants.collect{[id:it.tenantID,db:it.config.getParameter('db.url')]}])'''
    request = urllib.request.Request(support.BASE + '/console/scripting/execute', data=urllib.parse.urlencode(
        {'script': script, 'scriptType': 'groovy', 'commit': 'false'}).encode(), headers={'X-CSRF-TOKEN': token, 'Accept': 'application/json'})
    result = json.load(opener.open(request, timeout=40))
    if result.get('stacktraceText'):
        raise RuntimeError('Rollback-only native baseline query failed')
    return json.loads(result['executionResult'])


def validate_normal(facts):
    validate_tenants(facts.get('tenants'))
    if any(facts.get(key) is not False for key in ['solrserverLoaded', 'solrFixtureLoaded']):
        raise RuntimeError('Profile extensions remain loaded')
    if any(facts.get(key) is not True for key in ['reclazztest', 'reclazztestbackoffice']):
        raise RuntimeError('Normal fixture missing')
    if facts.get('counts') != {t: 0 for t in ['SolrFacetSearchConfig', 'SolrIndexedType', 'SolrIndex', 'ReclazzSchemaProbe']}:
        raise RuntimeError('Native normal DB is not the empty Solr baseline')


def preflight():
    no_inner_writer()
    require_options(support.get(support.ENDPOINT + '/gates'), False)
    validate_normal(normal_facts()); support.tenant_health()
    if SAP_HOME.resolve() != SAP_HOME or not (SAP_HOME / 'bin/custom/reclazztest').samefile(REPO / 'reclazztest'):
        raise RuntimeError('Foreign clean installation')
    if LINK.exists() or LINK.is_symlink():
        raise RuntimeError('Solr fixture link already exists; ownership unknown')
    if port_open():
        raise RuntimeError('Solr port already occupied')
    fixture_hashes()
    state = support.doctor()
    if state.get('watcherState') != 'watching' or state.get('buildHold') != 'none':
        raise RuntimeError('Agent busy/not watching')
    if support.java_writers() != [str(state['pid'])]:
        raise RuntimeError('Conflicting clean-install native writer')
    for root in [SAP_HOME / 'config', SAP_HOME / 'data', SOLR_DIST, PLATFORM / 'tomcat/conf']:
        if root.resolve() != root:
            raise RuntimeError('Foreign physical snapshot root')
        inventory(root)  # refuse nonregular/symlink roots before the first test write


def wrapper_bytes(original):
    text = original.decode(); prefix = '-javaagent:' + str(support.JAR)
    matches = [m for m in re.finditer(r'(?m)^wrapper\.java\.additional\.\d+=(-javaagent:\S+)[ \t\r]*$', text)
               if m.group(1) == prefix or m.group(1).startswith(prefix + '=')]
    if len(matches) != 1 or 'autoSolrReindex=' in matches[0].group(1):
        raise RuntimeError('Expected one normal owned agent without an explicit Solr option')
    m = matches[0]
    return (text[:m.end(1)] + (',' if '=' in m.group(1) else '=') + 'autoSolrReindex=true' + text[m.end(1):]).encode()


def profile_server_bytes(original):
    # Use the verified native Context of our existing fixture, preserving all
    # unrelated runtime XML bytes. No vendor template is embedded in source.
    text = original.decode()
    expected = str(REPO / 'reclazztest/web/webroot')
    pattern = r'<Context\s+path="/reclazztest"\s+docBase="' + re.escape(expected) + r'"\s*>[\s\S]*?</Context>'
    matches = list(re.finditer(pattern, text))
    if len(matches) != 1 or text.count('</Host>') != 1 or 'path="/reclazztestsolr"' in text:
        raise RuntimeError('Native fixture Context is absent/ambiguous or profile already deployed')
    block = matches[0].group(0)
    if 'className="de.hybris.tomcat.HybrisWebappLoader"' not in block or 'platformHome="' + str(PLATFORM) + '"' not in block:
        raise RuntimeError('Foreign native webapp loader')
    deployed = block.replace('path="/reclazztest"', 'path="/reclazztestsolr"').replace(expected, str(EXT / 'web/webroot'))
    return text.replace('</Host>', '\n<!-- Owned temporary Solr profile Context -->\n' + deployed + '\n</Host>').encode()


def native(folder, label, *args):
    support.command(['/bin/bash', '-c', 'source ./setantenv.sh && ant ' + shlex.join(args)], folder / (label + '.log'), 600)


def solr_args(command):
    return [command, '-Dinstance.name=default', '-Dinstance.port=' + str(PORT),
            '-Dinstance.conf.dir=' + str(SOLR_CONF), '-Dinstance.data.dir=' + str(SOLR_DATA),
            '-Dinstance.log.dir=' + str(SOLR_LOG), '-Dinstance.hostname=127.0.0.1',
            '-Dinstance.javaoptions=-Dsolr.jetty.host=127.0.0.1']


def parse_listener(text):
    pid = None; names = []; pids = set()
    for line in text.splitlines():
        if line.startswith('p'):
            pid = line[1:]; pids.add(pid)
        elif line.startswith('n'):
            names.append(line[1:])
    if len(pids) != 1 or not names or any(name not in {'127.0.0.1:' + str(PORT), '[::1]:' + str(PORT)} for name in names):
        raise RuntimeError('Solr listener is not one loopback-only owned process')
    return next(iter(pids))


def owned_listener():
    result = subprocess.run(['/usr/sbin/lsof', '-nP', '-iTCP:' + str(PORT), '-sTCP:LISTEN', '-Fpcn'], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError('Solr listener absent')
    pid = parse_listener(result.stdout)
    command = subprocess.check_output(['ps', '-p', pid, '-o', 'command='], text=True)
    # Inspect only; command lines may contain native auth, so never serialize them.
    paths = {}
    for key, root in [('solr.solr.home', SOLR_CONF), ('solr.data.home', SOLR_DATA), ('solr.log.dir', SOLR_LOG)]:
        match = re.search(r'(?:^|\s)-D' + re.escape(key) + r'=([^\s]+)', command)
        if not match:
            raise RuntimeError('Missing native Solr directory argument')
        actual = Path(match.group(1).strip('"')).resolve()
        if not actual.is_relative_to(root.resolve()) or not actual.is_relative_to(SAP_HOME):
            raise RuntimeError('Foreign Solr JVM/directories')
        paths[key] = str(actual)
    return {'pid': pid, 'loopbackOnly': True, 'paths': paths}



def no_inner_writer():
    lines = subprocess.check_output(['ps', '-axo', 'pid=,command='], text=True).splitlines()
    if any('com.onurkat.reclazz.inttest.ReclazzTestRunnerKt' in line for line in lines):
        raise RuntimeError('Inner runner survives; no recovery permitted')


def stopped():
    support.stopped()
    if port_open():
        raise RuntimeError('Solr listener survives; no recovery permitted')


def stop_all(folder, label, solr_requested):
    no_inner_writer()
    support.command(['./hybrisserver.sh', 'stop'], folder / (label + '-stop.log'), 90)
    if port_open():
        if not solr_requested:
            raise RuntimeError('Unexpected Solr listener; do not stop it')
        owned_listener()  # no blind stop by port
        native(folder, label + '-stop-solr', *solr_args('stopSolrServer'))
    deadline = time.monotonic() + 330
    while time.monotonic() < deadline:
        try:
            stopped(); return
        except RuntimeError:
            time.sleep(1)
    stopped()


def start(folder, label, profile):
    support.PORT_FILE.unlink(missing_ok=True)
    support.command(['./hybrisserver.sh', 'start'], folder / (label + '-start.log'), 90)
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        try:
            gates = support.get(support.ENDPOINT + '/gates')
        except Exception:
            time.sleep(2); continue
        require_options(gates, profile)
        try:
            state = support.doctor()
            if state.get('watcherState') != 'watching' or state.get('buildHold') != 'none':
                raise RuntimeError('Agent not ready')
            result = {'doctor': {k: state.get(k) for k in ['pid', 'sessionId', 'watcherState', 'buildHold']}, 'gates': gates}
            if profile:
                result['listener'] = owned_listener()
                result['nativeBaseline'] = support.get(ENDPOINT + '/baseline')
                validate_tenants(result['nativeBaseline']['tenants'])
                for field in ['solrserverLoaded', 'solrFixtureLoaded', 'solrReachable']:
                    if result['nativeBaseline']['gates'].get(field) is not True:
                        raise RuntimeError('Profile prerequisite missing: ' + field)
            else:
                result['nativeBaseline'] = normal_facts(); validate_normal(result['nativeBaseline'])
                if port_open():
                    raise RuntimeError('Solr listener remains after normal restoration')
            result['health'] = support.tenant_health()
            return result
        except Exception:
            time.sleep(2)
    raise RuntimeError('Solr profile readiness deadline')


def post_setup(nonce):
    request = urllib.request.Request(ENDPOINT + '/setup', data=urllib.parse.urlencode({'nonce': nonce}).encode())
    with urllib.request.urlopen(request, context=support.TLS, timeout=300) as response:
        return json.load(response)


def runner(folder, label, nonce=None, inject=False):
    env = {k: v for k, v in os.environ.items() if not k.startswith(('RECLAZZ_TEST_', 'RECLAZZ_SOLR_', 'RECLAZZ_ITEMS_', 'RECLAZZ_STATICS_'))}
    env.update(JAVA_HOME=str(support.JAVA_HOME), RECLAZZ_TEST_BASE_URL=support.BASE,
               RECLAZZ_TEST_PORT_FILE=str(support.PORT_FILE), RECLAZZ_TEST_EXT_PATH=str(REPO / 'reclazztest'),
               RECLAZZ_TEST_HYBRIS_HOME=str(SAP_HOME))
    if nonce:
        env.update(RECLAZZ_TEST_PROFILE='solr-attempt', RECLAZZ_SOLR_NONCE=nonce, RECLAZZ_SOLR_RECOVERY=str(folder))
    if inject:
        env['RECLAZZ_SOLR_INJECT_FAILURE'] = 'true'
    log = folder / (label + '.log')
    with log.open('w') as output:
        process = subprocess.Popen(['./gradlew', ':integration-test:run', '--console=plain'], cwd=REPO, env=env,
                                   stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait(900)
        except subprocess.TimeoutExpired:
            raise RuntimeError('Inner writer deadline; process preserved PID=' + str(process.pid))
    text = re.sub(r'\x1b\[[0-9;]*m', '', log.read_text())
    return {'exitCode': code, 'counts': {s: len(re.findall(r'^.*\|\s*' + s + r'\s*\|', text, re.M)) for s in ['PASS', 'FAIL', 'ERROR', 'SKIP']},
            'injectedFailure': 'Injected Solr failure after real FINISHED and stored v2' in text,
            'default44': 'Default committed membership: 44 cases' in text}


def remove_owned_link(link=LINK, target=EXT):
    if not link.is_symlink() or link.resolve() != target.resolve():
        raise RuntimeError('Refuse removing an unowned custom entry')
    link.unlink()


def profile(folder, number, inject):
    root = folder / ('cycle-' + str(number)); root.mkdir()
    wrapper = PLATFORM / 'tomcat/conf/wrapper.conf'; wrapper_original = wrapper.read_bytes()
    profile_wrapper = wrapper_bytes(wrapper_original)
    server = PLATFORM / 'tomcat/conf/server.xml'; profile_server = profile_server_bytes(server.read_bytes())
    fixtures = fixture_hashes(); custom = custom_listing(); agent_hash = support.digest(support.JAR)
    originals = {str(p.relative_to(EXT)): p.read_bytes().hex() for p in EXT.rglob('*') if p.is_file() and str(p.relative_to(REPO)) in fixtures}
    generated = [PLATFORM / 'bootstrap/gensrc', PLATFORM / 'bootstrap/modelclasses', PLATFORM / 'bootstrap/bin',
                 REPO / 'reclazztest/gensrc', REPO / 'reclazztest/classes']
    targets = [SAP_HOME / 'config', SAP_HOME / 'data', SOLR_DIST, PLATFORM / 'tomcat/conf'] + generated
    snapshots = ['config', 'data', 'solr-distribution', 'tomcat-conf'] + ['generated-' + str(i) for i in range(len(generated))]
    logs = SAP_HOME / 'log/tomcat'; offsets = {p: p.stat().st_size for p in logs.glob('console-*.log')}
    result = {'number': number, 'injected': inject, 'beforeDoctor': support.doctor()}
    captured = False; linked = False; solr_requested = False
    try:
        stop_all(root, 'capture', False)
        for target, name in zip(targets, snapshots):
            capture(target, root / name, stopped)
        state = {'home': str(SAP_HOME), 'snapshotsVerified': True, 'targets': {name: str(target) for target, name in zip(targets, snapshots)},
                 'wrapperBytes': wrapper_original.hex(), 'agentSha256': agent_hash, 'fixtureHashes': fixtures,
                 'sourceBytes': originals, 'customListing': custom}
        (root / 'state.json').write_text(json.dumps(state, indent=2) + '\n')
        if json.loads((root / 'state.json').read_text()) != state:
            raise RuntimeError('Durable recovery state read-back failed')
        captured = True
        if LINK.exists() or LINK.is_symlink():
            raise RuntimeError('Custom entry appeared before provisioning')
        LINK.symlink_to(EXT); linked = True
        localext = SAP_HOME / 'config/localextensions.xml'
        text = localext.read_text()
        if text.count('</extensions>') != 1 or 'name="solrserver"' in text or 'name="reclazztestsolr"' in text:
            raise RuntimeError('Unexpected extension baseline')
        localext.write_text(text.replace('</extensions>', '    <extension name="solrserver"/>\n    <extension name="reclazztestsolr"/>\n</extensions>'))
        props = SAP_HOME / 'config/local.properties'
        props.write_bytes(props.read_bytes() + b'\nsolrserver.instances.default.hostname=127.0.0.1\n')
        native(root, 'profile-build', 'build')
        wrapper.write_bytes(profile_wrapper)
        server.write_bytes(profile_server)
        solr_requested = True
        native(root, 'profile-start-solr', *solr_args('startSolrServer'))
        result['listener'] = owned_listener()
        result['profileBoot'] = start(root, 'profile', True)
        nonce = 'reclazz-solr-' + str(uuid.uuid4()); result['nonce'] = nonce
        result['seed'] = post_setup(nonce)
        (root / 'seed.json').write_text(json.dumps(result['seed'], indent=2) + '\n')
        result['attempt'] = runner(root, 'attempt', nonce, inject)
        attempt = result['attempt']
        expected = {'PASS': 0, 'FAIL': 1, 'ERROR': 0, 'SKIP': 0} if inject else {'PASS': 1, 'FAIL': 0, 'ERROR': 0, 'SKIP': 0}
        if attempt['counts'] != expected or (inject and (attempt['exitCode'] == 0 or not attempt['injectedFailure'])) or (not inject and attempt['exitCode'] != 0):
            raise RuntimeError('Native Solr attempt failed/skipped or expected failure missing')
    except Exception as failure:
        # Never serialize native HTTP error bodies or process command lines/auth.
        result['failure'] = str(failure)
    finally:
        if captured:
            try:
                stop_all(root, 'restore', solr_requested)
                if linked:
                    remove_owned_link()
                for relative, value in originals.items():
                    (EXT / relative).write_bytes(bytes.fromhex(value))
                wrapper.write_bytes(wrapper_original)
                for target, name in zip(targets, snapshots):
                    restore(root / name, target, stopped)
                native(root, 'normal-rebuild', 'build')
                # Native build may touch transient data/generated outputs. Restore again
                # after it exits so byte equality is proven immediately before boot.
                for target, name in zip(targets, snapshots):
                    restore(root / name, target, stopped)
                result['snapshotsRestoredBeforeBoot'] = all(inventory(t) == verify(root / n) for t, n in zip(targets, snapshots))
                if not result['snapshotsRestoredBeforeBoot']:
                    raise RuntimeError('Snapshot verification mismatch before normal boot')
                if custom_listing() != custom or fixture_hashes() != fixtures or support.digest(support.JAR) != agent_hash or wrapper.read_bytes() != wrapper_original:
                    raise RuntimeError('Source/custom/agent/launcher recovery mismatch')
                result['normalBoot'] = start(root, 'normal', False)
                previous = result.get('profileBoot', {'doctor': result['beforeDoctor']})['doctor']['sessionId']
                if result['normalBoot']['doctor']['sessionId'] == previous:
                    raise RuntimeError('Recovery was not a fresh JVM')
                result['fixtureFiles'] = len(fixtures); result['restoration'] = 'PASS'
            except Exception as failure:
                result['restoration'] = 'FAIL'; result['restoreFailure'] = str(failure)
        else:
            try:
                if not support.java_writers():
                    result['normalBoot'] = start(root, 'uncaptured-normal', False)
            except Exception as failure:
                result['restoreFailure'] = str(failure)
    native_log = b''
    for path in logs.glob('console-*.log'):
        if path.stat().st_size < offsets.get(path, 0):
            result['failure'] = 'Native log truncation'
        with path.open('rb') as stream:
            stream.seek(offsets.get(path, 0)); native_log += stream.read()
    (root / 'native.log').write_bytes(native_log)
    result['nativeRefreshFailures'] = native_log.count(b'Failed to refresh Spring bean')
    if result['nativeRefreshFailures']:
        result['failure'] = 'Native bean refresh failures'
    result['verdict'] = 'PASS' if 'failure' not in result and result.get('restoration') == 'PASS' else 'FAIL'
    (root / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({k: result.get(k) for k in ['number', 'injected', 'verdict', 'restoration', 'failure', 'restoreFailure']}), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs', type=int, default=3); parser.add_argument('--failure-control', action='store_true')
    args = parser.parse_args()
    if args.runs < 1:
        parser.error('--runs must be positive')
    try:
        preflight()
    except Exception as failure:
        print('SOLR_PROFILE SKIP ' + str(failure) + '; zero writes'); return 0
    folder = Path(tempfile.mkdtemp(prefix='reclazz-solr-', dir='/private/tmp'))
    print('Recovery/evidence directory: ' + str(folder), flush=True)
    results = []
    for number in range(args.runs + int(args.failure_control)):
        result = profile(folder, number + 1, args.failure_control and number == 0); results.append(result)
        if result['verdict'] != 'PASS':
            break
    baseline = runner(folder, 'normal-default') if all(r['verdict'] == 'PASS' for r in results) else {'unrun': 'profile/recovery failed'}
    passed = len(results) == args.runs + int(args.failure_control) and all(r['verdict'] == 'PASS' for r in results) and baseline.get('exitCode') == 0 and baseline.get('default44') is True and baseline.get('counts') == {'PASS': 43, 'FAIL': 0, 'ERROR': 0, 'SKIP': 1}
    (folder / 'summary.json').write_text(json.dumps({'verdict': 'PASS' if passed else 'FAIL', 'cycles': results, 'default': baseline}, indent=2) + '\n')
    print('SOLR_PROFILE ' + ('PASS' if passed else 'FAIL') + '; recovery evidence: ' + str(folder), flush=True)
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
