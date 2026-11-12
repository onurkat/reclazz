#!/usr/bin/env python3
"""Serialized items-schema profile. Native attempts count only after stopped-DB recovery."""
from pathlib import Path
from sap_suite_contract import DEFAULT_CASES, DEFAULT_OUTCOME, NORMAL_FIXTURE_FILES, default_result
import argparse
import hashlib
import json
import os
import re
import socket
import ssl
import subprocess
import tempfile
import time
import urllib.request
from sap_items_snapshot import capture, restore, verify, inventory

REPO = Path(__file__).resolve().parent.parent
# A second disposable install (for a parallel agent) is selected with
# RECLAZZ_SAP_HOME and RECLAZZ_SAP_BASE; the default is the one beside the repo.
SAP_HOME = Path(os.environ.get('RECLAZZ_SAP_HOME') or REPO.parent / 'reclazz-sap') / 'hybris'
PLATFORM = SAP_HOME / 'bin/platform'
BASE = os.environ.get('RECLAZZ_SAP_BASE') or 'https://localhost:9102'
ENDPOINT = BASE + '/reclazztest/v2/test/batch'
PORT_FILE = SAP_HOME.parent / 'agent.port'
JAR = SAP_HOME.parent / 'agent/reclazz-agent.jar'
JAVA_HOME = Path('/Library/Java/JavaVirtualMachines/sapmachine-21.jdk/Contents/Home')
TLS = ssl._create_unverified_context()


def get(url):
    with urllib.request.urlopen(url, context=TLS, timeout=15) as response:
        if response.status != 200:
            raise RuntimeError('Expected HTTP200')
        return json.load(response)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def doctor():
    token = 'items-' + str(time.monotonic_ns())
    with socket.create_connection(('127.0.0.1', int(PORT_FILE.read_text())), 5) as connection:
        connection.settimeout(10)
        connection.sendall(('DOCTOR ' + token + '\n').encode())
        for line in connection.makefile():
            message = json.loads(line).get('message', '')
            prefix = 'DOCTOR_RESULT ' + token + ' '
            if message.startswith(prefix):
                return json.loads(message[len(prefix):])
    raise RuntimeError('Missing running-agent identity')


def java_writers():
    lines = subprocess.check_output(['ps', '-axo', 'pid=,command='], text=True).splitlines()
    return [line.split(None, 1)[0] for line in lines if str(SAP_HOME) in line and '/bin/java ' in line]


def stopped():
    if java_writers() or (SAP_HOME / 'data/hsqldb/mydb.lck').exists():
        raise RuntimeError('Clean-install DB writer or HSQLDB lock remains')


def command(args, log, timeout=600):
    env = os.environ.copy()
    env['JAVA_HOME'] = str(JAVA_HOME); env['PATH'] = str(JAVA_HOME / 'bin') + ':' + env['PATH']
    with Path(log).open('w') as stream:
        process = subprocess.Popen(args, cwd=PLATFORM, env=env, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait(timeout)
        except subprocess.TimeoutExpired:
            # Do not kill a native writer mid-operation or restore an open database.
            raise RuntimeError('Native command exceeded deadline; process preserved, PID=' + str(process.pid))
    if code:
        raise RuntimeError('Native command failed; see ' + Path(log).name)


def stop(folder, label):
    command(['./hybrisserver.sh', 'stop'], folder / (label + '-stop.log'), 90)
    deadline = time.monotonic() + 330
    while time.monotonic() < deadline:
        try:
            stopped(); return
        except RuntimeError:
            time.sleep(1)
    stopped()  # refuses restoration while the JVM or a native ant child survives


def schema_wrapper_bytes(original, jar):
    text = original.decode()
    prefix = '-javaagent:' + str(jar)
    matches = [m for m in re.finditer(r'(?m)^wrapper\.java\.additional\.\d+=(-javaagent:\S+)[ \t\r]*$', text)
               if m.group(1) == prefix or m.group(1).startswith(prefix + '=')]
    if len(matches) != 1:
        raise RuntimeError('Expected exactly one owned agent wrapper entry')
    match = matches[0]; argument = match.group(1)
    if 'autoUpdateRunningSystem=' in argument:
        raise RuntimeError('Explicit schema option in normal wrapper')
    suffix = ',' if '=' in argument else '='
    return (text[:match.end(1)] + suffix + 'autoUpdateRunningSystem=true' + text[match.end(1):]).encode()


def require_boot_options(gates, expected_update):
    required = {'agentArgsFound': True, 'agentOptionsMatchConfig': True, 'autoCompile': True,
                'autoUpdateRunningSystem': expected_update, 'resetStatics': False,
                'autoSolrReindex': False, 'impexAllowRemove': False}
    for key, value in required.items():
        if type(gates.get(key)) is not bool or gates[key] != value:
            raise RuntimeError('Boot option mismatch: ' + key + '=' + str(gates.get(key)))


def start(folder, label, expected_update):
    PORT_FILE.unlink(missing_ok=True)
    command(['./hybrisserver.sh', 'start'], folder / (label + '-start.log'), 90)
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        try:
            gates = get(ENDPOINT + '/gates')
        except Exception:
            time.sleep(2); continue
        # A responding server with the wrong options cannot converge without another boot.
        require_boot_options(gates, expected_update)
        try:
            if get(BASE + '/occ/v2/reclazz-fixture/reclazzprobe?fields=FULL') != {'code': 'v0', 'label': 'label0'}:
                raise RuntimeError('Normal OCC baseline unavailable')
            state = doctor()
            if state.get('watcherState') != 'watching' or state.get('buildHold') != 'none':
                raise RuntimeError('Agent not ready')
            return {'doctor': {k: state.get(k) for k in ['pid', 'sessionId', 'watcherState', 'buildHold']},
                    'gates': gates, 'facts': get(ENDPOINT + '/items/facts'), 'health': tenant_health()}
        except Exception:
            time.sleep(2)
    raise RuntimeError('Clean-install readiness deadline')


def tenant_health():
    result = get(ENDPOINT + '/items/health')
    if set(result) != {'master', 'junit'} or any(type(v) is not int or v < 0 for v in result.values()):
        raise RuntimeError('Native master/junit health unavailable')
    return result


def validate_facts(facts):
    expected_db = 'jdbc:hsqldb:file:' + str(SAP_HOME / 'data/hsqldb/mydb')
    tenants = facts.get('tenants')
    if not isinstance(tenants, list) or not tenants or not {'master', 'junit'}.issubset({t['id'] for t in tenants}):
        raise RuntimeError('Missing master/junit DB facts')
    if any(t['db'].split(';', 1)[0] != expected_db for t in tenants):
        raise RuntimeError('Foreign/non-disposable tenant DB; no writes allowed')
    if facts.get('collisions') != 0 or facts.get('typeCode') != 31991 or facts.get('table', '').lower() != 'reclazzschemaprobes':
        raise RuntimeError('Synthetic deployment metadata mismatch')
    if facts.get('batchValuePresent') is not False or facts.get('rows') != 0:
        raise RuntimeError('Schema baseline attribute/row mismatch')


def runner(folder, label, profile=None, recovery=None, inject=False):
    env = os.environ.copy()
    for key in ['RECLAZZ_TEST_ONLY', 'RECLAZZ_TEST_PROFILE', 'RECLAZZ_ITEMS_RECOVERY', 'RECLAZZ_ITEMS_INJECT_FAILURE']:
        env.pop(key, None)
    env.update(JAVA_HOME=str(JAVA_HOME), RECLAZZ_TEST_BASE_URL=BASE, RECLAZZ_TEST_PORT_FILE=str(PORT_FILE),
               RECLAZZ_TEST_EXT_PATH=str(REPO / 'reclazztest'), RECLAZZ_TEST_HYBRIS_HOME=str(SAP_HOME))
    if profile:
        env['RECLAZZ_TEST_PROFILE'] = profile; env['RECLAZZ_ITEMS_RECOVERY'] = str(recovery)
    if inject:
        env['RECLAZZ_ITEMS_INJECT_FAILURE'] = 'true'
    with (folder / (label + '.log')).open('w') as stream:
        result = subprocess.run(['./gradlew', ':integration-test:run', '--console=plain'], cwd=REPO,
                                env=env, stdout=stream, stderr=subprocess.STDOUT, timeout=900)
    text = (folder / (label + '.log')).read_text()
    return {'exitCode': result.returncode, **default_result(text),
            'injectedFailure': 'Injected items failure after persisted write' in text}


def save_recovery_state(folder, config_bytes, fixture_bytes, generated, agent_hash):
    # Durable originals support manual recovery even if the test process is lost.
    state = {'home': str(SAP_HOME), 'snapshotVerified': True, 'agentSha256': agent_hash,
             'configBytes': {str(path): value.hex() for path, value in config_bytes.items()},
             'configSha256': {str(path): hashlib.sha256(value).hexdigest() for path, value in config_bytes.items()},
             'fixtureSourceRelative': 'resources/reclazztest-items.xml',
             'fixtureSourceBytes': fixture_bytes.hex(),
             'fixtureSourceSha256': hashlib.sha256(fixture_bytes).hexdigest(),
             'generatedTargets': [str(path) for path in generated]}
    target = folder / 'state.json'
    target.write_text(json.dumps(state, indent=2) + '\n')
    if json.loads(target.read_text()) != state:
        raise RuntimeError('Durable recovery state read-back failed')


def fixture_hashes():
    tracked = subprocess.check_output(['git', '--no-optional-locks', 'ls-files', '--', 'reclazztest', 'reclazztestbackoffice'], cwd=REPO, text=True).splitlines()
    added = subprocess.check_output(['git', '--no-optional-locks', 'ls-files', '--others', '--exclude-standard', '--', 'reclazztest', 'reclazztestbackoffice'], cwd=REPO, text=True).splitlines()
    return {name: digest(REPO / name) for name in tracked + added}


def profile(folder, number, inject):
    """One entire native attempt/recovery is a profile; inner PASS alone is insufficient."""
    root = folder / ('cycle-' + str(number)); root.mkdir()
    baseline_facts = get(ENDPOINT + '/items/facts'); validate_facts(baseline_facts)
    jar_hash = digest(JAR)
    fixtures = fixture_hashes()
    log_root = SAP_HOME / 'log/tomcat'
    offsets = {p: p.stat().st_size for p in log_root.glob('console-*.log')}
    configs = {p: p.read_bytes() for p in (SAP_HOME / 'config').rglob('*') if p.is_file() and p.suffix in {'.properties', '.xml', '.conf'}}
    wrapper = PLATFORM / 'tomcat/conf/wrapper.conf'; configs[wrapper] = wrapper.read_bytes()
    items = REPO / 'reclazztest/resources/reclazztest-items.xml'; baseline_items = items.read_bytes()
    generated = [PLATFORM / 'bootstrap/gensrc', PLATFORM / 'bootstrap/modelclasses', PLATFORM / 'bootstrap/bin',
                 REPO / 'reclazztest/gensrc', REPO / 'reclazztest/classes']
    result = {'number': number, 'injected': inject, 'baselineFacts': baseline_facts, 'beforeDoctor': doctor()}
    captured = False
    try:
        stop(root, 'capture')
        capture(SAP_HOME / 'data/hsqldb', root / 'db', stopped)
        for i, path in enumerate(generated):
            capture(path, root / ('generated-' + str(i)), stopped)
        save_recovery_state(root, configs, baseline_items, generated, jar_hash)
        captured = True
        command(['/bin/bash', '-c', 'source ./setantenv.sh && ant build'], root / 'build.log')
        # This is the actual launcher input. Keep local.properties intent unchanged.
        wrapper.write_bytes(schema_wrapper_bytes(configs[wrapper], JAR))
        if any(path != wrapper and path.read_bytes() != value for path, value in configs.items()):
            raise RuntimeError('Unowned configuration change before schema boot')
        result['profileBoot'] = start(root, 'profile', True)
        validate_facts(result['profileBoot']['facts'])
        result['attempt'] = runner(root, 'attempt', 'items-attempt', root, inject)
        if inject:
            if result['attempt']['exitCode'] == 0 or not result['attempt']['injectedFailure']:
                raise RuntimeError('Injected failure was not observed')
        elif result['attempt']['exitCode'] != 0 or result['attempt']['counts'] != {'PASS': 1, 'FAIL': 0, 'ERROR': 0, 'SKIP': 0}:
            raise RuntimeError('Schema attempt failed/skipped')
    except Exception as failure:
        result['failure'] = str(failure)
    finally:
        if captured:
            try:
                stop(root, 'restore')
                items.write_bytes(baseline_items)
                for path, value in configs.items():
                    path.write_bytes(value)
                for i, path in enumerate(generated):
                    restore(root / ('generated-' + str(i)), path, stopped)
                restore(root / 'db', SAP_HOME / 'data/hsqldb', stopped)
                result['dbHashesRestoredBeforeBoot'] = inventory(SAP_HOME / 'data/hsqldb') == verify(root / 'db')
                if not result['dbHashesRestoredBeforeBoot'] or digest(JAR) != jar_hash:
                    raise RuntimeError('DB/agent recovery mismatch')
                result['normalBoot'] = start(root, 'normal', False)
                validate_facts(result['normalBoot']['facts'])
                if result['normalBoot']['doctor']['sessionId'] == result.get('profileBoot', {'doctor': result['beforeDoctor']})['doctor']['sessionId']:
                    raise RuntimeError('Recovery was not a fresh JVM boot')
                result['sourcesRestored'] = items.read_bytes() == baseline_items
                result['configHashesRestored'] = all(p.read_bytes() == value for p, value in configs.items())
                if not result['sourcesRestored'] or not result['configHashesRestored']:
                    raise RuntimeError('Source/config recovery mismatch')
                result['fixtureFiles'] = len(fixtures)
                result['fixtureHashesRestored'] = fixture_hashes() == fixtures
                if not result['fixtureHashesRestored']:
                    raise RuntimeError('Fixture byte restoration mismatch')
                result['restoration'] = 'PASS'
            except Exception as failure:
                result['restoration'] = 'FAIL'; result['restoreFailure'] = str(failure)
        else:
            # No schema mutation occurred. A stopped server must still be returned to normal.
            try:
                if not java_writers():
                    result['normalBoot'] = start(root, 'uncaptured-normal', False)
            except Exception as failure:
                result['restoreFailure'] = str(failure)
    native = b''
    for path in log_root.glob('console-*.log'):
        with path.open('rb') as stream:
            stream.seek(offsets.get(path, 0)); native += stream.read()
    (root / 'native.log').write_bytes(native)
    result['nativeRefreshFailures'] = native.count(b'Failed to refresh Spring bean')
    if result['nativeRefreshFailures']:
        result['failure'] = 'Native bean refresh failures occurred'
    result['verdict'] = 'PASS' if 'failure' not in result and result.get('restoration') == 'PASS' else 'FAIL'
    (root / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({k: result.get(k) for k in ['number', 'injected', 'verdict', 'restoration', 'failure', 'restoreFailure']}), flush=True)
    return result


def preflight():
    gates = get(ENDPOINT + '/gates')
    required = {'agentArgsFound': True, 'agentOptionsMatchConfig': True, 'autoCompile': True,
                'autoUpdateRunningSystem': False, 'resetStatics': False, 'autoSolrReindex': False,
                'impexAllowRemove': False, 'codegenExtensionLoaded': True, 'codegenGeneratedRoots': True}
    for field, value in required.items():
        if type(gates.get(field)) is not bool or gates[field] != value:
            return 'gate ' + field + ' missing/mismatch'
    facts = get(ENDPOINT + '/items/facts')
    if facts.get('typePresent') is not True:
        return 'synthetic baseline type not deployed'
    validate_facts(facts)
    tenant_health()
    if SAP_HOME.resolve() != SAP_HOME or not (SAP_HOME / 'bin/custom/reclazztest').samefile(REPO / 'reclazztest'):
        raise RuntimeError('Clean install ownership mismatch')
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs', type=int, default=3)
    parser.add_argument('--failure-control', action='store_true')
    args = parser.parse_args()
    if args.runs < 1:
        parser.error('--runs must be positive')
    # Read-only preflight precedes even the recovery/log directory.
    try:
        reason = preflight()
    except Exception as failure:
        reason = 'Preflight unavailable: ' + str(failure)
    if reason:
        print('ITEMS_PROFILE SKIP ' + reason + '; zero writes'); return 0
    folder = Path(tempfile.mkdtemp(prefix='reclazz-items-', dir='/private/tmp'))
    print('Recovery/evidence directory: ' + str(folder), flush=True)
    results = []
    for number in range(args.runs + int(args.failure_control)):
        result = profile(folder, number + 1, args.failure_control and number == 0)
        results.append(result)
        if result['verdict'] != 'PASS':
            break
    if all(r['verdict'] == 'PASS' for r in results):
        baseline = runner(folder, 'normal-default')
        if baseline['exitCode'] != 0 or baseline['counts'] != DEFAULT_OUTCOME or not baseline['defaultSuite']:
            raise RuntimeError('Restored default suite failed; evidence preserved in ' + str(folder))
    else:
        baseline = {'unrun': 'profile/recovery failed'}
    passed = len(results) == args.runs + int(args.failure_control) and all(r['verdict'] == 'PASS' for r in results) and 'unrun' not in baseline
    (folder / 'summary.json').write_text(json.dumps({'verdict': 'PASS' if passed else 'FAIL', 'cycles': results, 'default': baseline}, indent=2) + '\n')
    print('ITEMS_PROFILE ' + ('PASS' if passed else 'FAIL') + '; recovery evidence: ' + str(folder), flush=True)
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
