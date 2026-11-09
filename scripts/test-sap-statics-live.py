#!/usr/bin/env python3
"""Full resetStatics acceptance: fresh off/on lanes, stopped recovery, normal default44."""
from pathlib import Path
import argparse
import importlib.util
import json
import os
import re
import subprocess
import tempfile
import time

# Reuse audited clean-install process/HTTP helpers, never its schema mutation path.
spec = importlib.util.spec_from_file_location('items_support', Path(__file__).with_name('test-sap-items-live.py'))
support = importlib.util.module_from_spec(spec); spec.loader.exec_module(support)
REPO, SAP_HOME, PLATFORM, JAR = support.REPO, support.SAP_HOME, support.PLATFORM, support.JAR
RELATIVES = ['src/com/onurkat/reclazztest/batch/StaticProbe.java',
             'web/src/com/onurkat/reclazztest/controllers/StaticProbeController.java']


def require_options(gates, enabled):
    required = {'agentArgsFound': True, 'agentOptionsMatchConfig': True, 'autoCompile': True,
                'resetStatics': enabled, 'autoUpdateRunningSystem': False,
                'autoSolrReindex': False, 'impexAllowRemove': False}
    for key, value in required.items():
        if type(gates.get(key)) is not bool or gates[key] != value:
            raise RuntimeError('Boot option mismatch: ' + key + '=' + str(gates.get(key)))


def wrapper_bytes(original, jar):
    text = original.decode(); prefix = '-javaagent:' + str(jar)
    matches = [m for m in re.finditer(r'(?m)^wrapper\.java\.additional\.\d+=(-javaagent:\S+)[ \t\r]*$', text)
               if m.group(1) == prefix or m.group(1).startswith(prefix + '=')]
    if len(matches) != 1:
        raise RuntimeError('Expected exactly one owned agent wrapper entry')
    match = matches[0]
    if 'resetStatics=' in match.group(1):
        raise RuntimeError('Explicit static option in normal wrapper')
    return (text[:match.end(1)] + (',' if '=' in match.group(1) else '=') +
            'resetStatics=true' + text[match.end(1):]).encode()


def require_baseline(state):
    expected = {'direct': 'v1', 'retainedGetter': 'v1', 'version': 'body-v1', 'refused': 'call-v1',
                'count': 7, 'initializerCalls': 1, 'runtimeValue': 'startup'}
    if any(type(state.get(k)) is not type(v) or state[k] != v for k, v in expected.items()):
        raise RuntimeError('Static first-load baseline mismatch: ' + str(state))
    if type(state.get('identity')) is not int:
        raise RuntimeError('Static identity missing')


def preflight():
    require_options(support.get(support.ENDPOINT + '/gates'), False)
    support.validate_facts(support.get(support.ENDPOINT + '/items/facts'))
    support.tenant_health()
    if SAP_HOME.resolve() != SAP_HOME or not (SAP_HOME / 'bin/custom/reclazztest').samefile(REPO / 'reclazztest'):
        raise RuntimeError('Clean-install fixture mismatch')
    state = support.doctor()
    if state.get('watcherState') != 'watching' or state.get('buildHold') != 'none':
        raise RuntimeError('Agent busy/not watching')
    for relative in RELATIVES:
        if (REPO / 'reclazztest' / relative).read_bytes() != (REPO / 'integration-test/src/main/resources/templates/batch' / relative).read_bytes():
            raise RuntimeError('Dirty static fixture: ' + relative)


def start(folder, label, enabled):
    support.PORT_FILE.unlink(missing_ok=True)
    support.command(['./hybrisserver.sh', 'start'], folder / (label + '-start.log'), 90)
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        try:
            gates = support.get(support.ENDPOINT + '/gates')
        except Exception:
            time.sleep(2); continue
        require_options(gates, enabled)  # a responding wrong boot cannot converge
        try:
            state = support.doctor()
            if state.get('watcherState') != 'watching' or state.get('buildHold') != 'none':
                raise RuntimeError('Agent not ready')
            baseline = support.get(support.ENDPOINT + '/statics'); require_baseline(baseline)
            return {'identity': {k: state.get(k) for k in ['pid', 'sessionId', 'watcherState', 'buildHold']},
                    'gates': gates, 'statics': baseline, 'health': support.tenant_health()}
        except Exception:
            time.sleep(2)
    raise RuntimeError('Static profile readiness deadline')


def runner(folder, label, profile=None, inject=False):
    env = {k: v for k, v in os.environ.items() if not k.startswith(('RECLAZZ_TEST_', 'RECLAZZ_ITEMS_', 'RECLAZZ_STATICS_'))}
    env.update(JAVA_HOME=str(support.JAVA_HOME), RECLAZZ_TEST_BASE_URL=support.BASE,
               RECLAZZ_TEST_PORT_FILE=str(support.PORT_FILE), RECLAZZ_TEST_EXT_PATH=str(REPO / 'reclazztest'),
               RECLAZZ_TEST_HYBRIS_HOME=str(SAP_HOME))
    if profile:
        env['RECLAZZ_TEST_PROFILE'] = profile
    if inject:
        env['RECLAZZ_STATICS_INJECT_FAILURE'] = 'true'
    log = folder / (label + '.log')
    with log.open('w') as output:
        process = subprocess.Popen(['./gradlew', ':integration-test:run', '--console=plain'], cwd=REPO,
                                   env=env, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait(900)
        except subprocess.TimeoutExpired:
            # Restoration must not race the surviving inner source writer.
            raise RuntimeError('Inner runner deadline; writer preserved PID=' + str(process.pid))
    text = re.sub(r'\x1b\[[0-9;]*m', '', log.read_text())
    return {'exitCode': code, 'counts': {s: len(re.findall(r'^.*\|\s*' + s + r'\s*\|', text, re.M))
                                      for s in ['PASS', 'FAIL', 'ERROR', 'SKIP']},
            'default44': 'Default committed membership: 44 cases' in text,
            'injectedFailure': 'Injected static failure after reload and runtime mutation' in text}


def accept_attempt(result, injected):
    if injected:
        if result['exitCode'] == 0 or not result['injectedFailure'] or result['counts'] != {'PASS': 0, 'FAIL': 1, 'ERROR': 0, 'SKIP': 0}:
            raise RuntimeError('Injected static failure was not observed')
    elif result['exitCode'] != 0 or result['counts'] != {'PASS': 1, 'FAIL': 0, 'ERROR': 0, 'SKIP': 0}:
        raise RuntimeError('Static attempt failed/skipped after outer writes')


def ensure_no_inner_writer():
    lines = subprocess.check_output(['ps', '-axo', 'pid=,command='], text=True).splitlines()
    if any('com.onurkat.reclazz.inttest.ReclazzTestRunnerKt' in line for line in lines):
        raise RuntimeError('Inner runner still writing; unsafe to restore')


def build(folder, label):
    support.command(['/bin/bash', '-c', 'source ./setantenv.sh && ant build'], folder / (label + '-build.log'))


def recover(folder, originals, configs, jar_hash, fixtures, previous_sessions):
    ensure_no_inner_writer()
    support.stop(folder, 'restore')
    for path, value in {**originals, **configs}.items():
        path.write_bytes(value)
    build(folder, 'restore')
    if any(path.read_bytes() != value for path, value in {**originals, **configs}.items()):
        raise RuntimeError('Native build/source/config recovery mismatch')
    if support.digest(JAR) != jar_hash or support.fixture_hashes() != fixtures:
        raise RuntimeError('Agent/fixture recovery mismatch')
    normal = start(folder, 'normal', False)
    if normal['identity']['sessionId'] in previous_sessions:
        raise RuntimeError('Normal recovery was not a fresh JVM')
    return normal


def profile(folder, number, inject=False):
    root = folder / ('cycle-' + str(number)); root.mkdir()
    originals = {REPO / 'reclazztest' / relative: (REPO / 'reclazztest' / relative).read_bytes() for relative in RELATIVES}
    configs = {p: p.read_bytes() for p in (SAP_HOME / 'config').rglob('*') if p.is_file() and p.suffix in {'.properties', '.xml', '.conf'}}
    wrapper = PLATFORM / 'tomcat/conf/wrapper.conf'; configs[wrapper] = wrapper.read_bytes()
    enabled_wrapper = wrapper_bytes(configs[wrapper], JAR)  # refuse before mutation
    fixtures = support.fixture_hashes(); jar_hash = support.digest(JAR)
    backup = {'home': str(SAP_HOME), 'files': {str(p): value.hex() for p, value in {**originals, **configs}.items()},
              'agentSha256': jar_hash, 'fixtureHashes': fixtures}
    (root / 'recovery.json').write_text(json.dumps(backup, indent=2) + '\n')
    offsets = {p: p.stat().st_size for p in (SAP_HOME / 'log/tomcat').glob('console-*.log')}
    result = {'number': number, 'injected': inject}; sessions = {support.doctor()['sessionId']}
    try:
        support.stop(root, 'off'); build(root, 'off')
        result['offBoot'] = start(root, 'off', False)
        if result['offBoot']['identity']['sessionId'] in sessions:
            raise RuntimeError('Off lane was not a fresh JVM')
        sessions.add(result['offBoot']['identity']['sessionId'])
        result['offAttempt'] = runner(root, 'off-attempt', 'statics-off'); accept_attempt(result['offAttempt'], False)
        support.stop(root, 'on'); build(root, 'on')
        wrapper.write_bytes(enabled_wrapper)
        if any(p != wrapper and p.read_bytes() != v for p, v in configs.items()):
            raise RuntimeError('Unowned config changed before static boot')
        result['onBoot'] = start(root, 'on', True)
        if result['onBoot']['identity']['sessionId'] in sessions:
            raise RuntimeError('On lane was not a fresh JVM')
        sessions.add(result['onBoot']['identity']['sessionId'])
        result['onAttempt'] = runner(root, 'on-attempt', 'statics-on', inject); accept_attempt(result['onAttempt'], inject)
    except Exception as failure:
        result['failure'] = str(failure)
    finally:
        try:
            result['normalBoot'] = recover(root, originals, configs, jar_hash, fixtures, sessions)
            result['fixtureFiles'] = len(fixtures); result['restoration'] = 'PASS'
        except Exception as failure:
            result['restoration'] = 'FAIL'; result['restoreFailure'] = str(failure)
    native = b''
    for p in (SAP_HOME / 'log/tomcat').glob('console-*.log'):
        offset = offsets.get(p, 0)
        if p.stat().st_size < offset:
            result['failure'] = 'Native log truncated during profile'
        with p.open('rb') as stream:
            stream.seek(offset); native += stream.read()
    (root / 'native.log').write_bytes(native)
    result['nativeRefreshFailures'] = native.count(b'Failed to refresh Spring bean')
    if result['nativeRefreshFailures']:
        result['failure'] = 'Native bean refresh failure'
    result['verdict'] = 'PASS' if 'failure' not in result and result['restoration'] == 'PASS' else 'FAIL'
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
        print('STATICS_PROFILE SKIP ' + str(failure) + '; zero writes'); return 0
    folder = Path(tempfile.mkdtemp(prefix='reclazz-statics-', dir='/private/tmp'))
    print('Recovery/evidence directory: ' + str(folder), flush=True)
    results = []
    for n in range(args.runs + int(args.failure_control)):
        result = profile(folder, n + 1, args.failure_control and n == 0); results.append(result)
        if result['verdict'] != 'PASS':
            break
    baseline = {'unrun': 'profile/recovery failed'}
    if all(r['verdict'] == 'PASS' for r in results):
        baseline = runner(folder, 'normal-default')
    passed = len(results) == args.runs + int(args.failure_control) and all(r['verdict'] == 'PASS' for r in results) and baseline.get('exitCode') == 0 and baseline.get('counts') == {'PASS': 43, 'FAIL': 0, 'ERROR': 0, 'SKIP': 1} and baseline.get('default44') is True
    (folder / 'summary.json').write_text(json.dumps({'verdict': 'PASS' if passed else 'FAIL', 'cycles': results, 'default': baseline}, indent=2) + '\n')
    print('STATICS_PROFILE ' + ('PASS' if passed else 'FAIL') + '; recovery evidence: ' + str(folder), flush=True)
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
