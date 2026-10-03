#!/usr/bin/env python3
# Copyright 2026 Onur Kat
# SPDX-License-Identifier: Apache-2.0
"""Local-artifact Maven first-reload acceptance; no publication. Linux/macOS only."""
import argparse
import contextlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('safety', ROOT / 'scripts/test-build-plugin-safety.py')
safety = importlib.util.module_from_spec(spec)
spec.loader.exec_module(safety)
CLASS = 'com.example.demo.HelloController'
CLASS_FILE = Path('target/classes/com/example/demo/HelloController.class')
BEFORE, AFTER = 'Hello before reload', 'Hello after reload'


class Rejected(AssertionError):
    pass


def require(condition, reason):
    if not condition:
        raise Rejected(reason)


def check_doctor(doctor, baseline, project, version, watch=True):
    require(doctor.get('status') == 'observed', 'doctor unavailable')
    require(doctor.get('pid') == str(baseline.get('pid')) and baseline.get('pid'), 'wrong PID')
    require(Path(doctor.get('workingDirectory', '')).resolve() == project.resolve(), 'wrong project')
    require(doctor.get('agentVersion') == version, 'wrong agent version')
    require(bool(doctor.get('sessionId')) and doctor.get('verifySupported') is True, 'missing receipt capability')
    require(doctor.get('watcherState') == 'watching' and doctor.get('buildHold') == 'none', 'watcher not ready')
    if watch:
        require(any(Path(p).resolve() == (project / 'target/classes').resolve()
                    for p in doctor.get('watchedDirectories', [])), 'wrong watched output')
    require(baseline.get('message') == BEFORE and baseline.get('marker') == 'kept', 'invalid baseline or lost JVM argument')


def check_receipt(receipt, session, digest):
    require(receipt.get('status') == 'applied', 'reload not applied')
    require(receipt.get('sessionId') == session, 'wrong session')
    require(receipt.get('className') == CLASS, 'wrong class')
    require(receipt.get('expectedSha256') == digest == receipt.get('observedSha256'), 'wrong bytes')


def check_behavior(before, after):
    require(after.get('message') == AFTER, 'unchanged behavior')
    require(after.get('marker') == before.get('marker') == 'kept', 'lost JVM argument')
    require(after.get('pid') == before.get('pid') and after.get('startedAt') == before.get('startedAt')
            and before.get('pid') and before.get('startedAt'), 'JVM restarted')


def rejects(operation, reason):
    try:
        operation()
    except Rejected as error:
        require(str(error) == reason, f'wrong rejection: {error}; expected {reason}')
        return reason
    raise AssertionError(f'false acceptance: expected {reason}')


def until(operation, accept, seconds, label):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        last = operation()
        if accept(last):
            return last
        time.sleep(.2)
    raise AssertionError(f'{label} deadline; last observation: {last}')


def stop(process):
    # Each owned command starts its own session. Also kill children if Maven exits first.
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        pass
    finally:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=10)


def run(command, cwd, log):
    with log.open('w') as out:
        out.write('COMMAND: ' + json.dumps(list(map(str, command))) + '\n'); out.flush()
        process = subprocess.Popen(list(map(str, command)), cwd=cwd, stdout=out,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait(timeout=240)
            require(code == 0, f'command failed ({code}); see {log}')
        finally:
            stop(process)


def doctor(project):
    port_file = project / '.reclazz/agent.port'
    require(port_file.is_file(), 'missing agent port file')
    return safety.query(int(port_file.read_text().strip()), 'DOCTOR')


def verify(project, digest):
    port = int((project / '.reclazz/agent.port').read_text().strip())
    return safety.query(port, 'VERIFY', f' {CLASS} {digest}')


@contextlib.contextmanager
def running(project, mode, base, logs):
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    command = list(base)
    if mode == 'no-agent':
        command += ['-Dspring-boot.run.jvmArguments=-Ddemo.marker=kept']
    else:
        command += ['-Preclazz-dev', 'reclazz:prepare-agent']
    if mode == 'unwatched':
        unused = project / 'unused output'; unused.mkdir()
        command += [f'-Dreclazz.watchDirs={unused}']
    command += ['spring-boot:run', f'-Dspring-boot.run.arguments=--server.port={port}']
    with (logs / f'{mode}-launch.log').open('w') as log:
        log.write('COMMAND: ' + json.dumps(command) + '\n'); log.flush()
        process = subprocess.Popen(command, cwd=project, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        def response():
            require(process.poll() is None, f'application exited; see {mode}-launch.log')
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{port}/hello', timeout=2) as result:
                    return json.load(result)
            except (OSError, ValueError):
                return None
        try:
            first = until(response, lambda value: value is not None, 180, 'HTTP startup')
            yield first, response
        finally:
            stop(process)


def ready(project):
    def observe():
        if not (project / '.reclazz/agent.port').is_file():
            return None
        # A malformed/rejected protocol response is a failure, not startup readiness.
        try:
            return doctor(project)
        except (ConnectionError, TimeoutError):
            return None
    return until(observe, lambda d: d and d.get('watcherState') == 'watching', 90, 'watcher startup')


def edited(project, base, logs, mode):
    source = project / 'src/main/java/com/example/demo/HelloController.java'
    before = safety.sha(project / CLASS_FILE)
    text = source.read_text()
    require(text.count(BEFORE) == 1, 'fixture baseline changed')
    source.write_text(text.replace(BEFORE, AFTER))
    run(base + ['compile'], project, logs / f'{mode}-compile.log')
    digest = safety.sha(project / CLASS_FILE)
    require(digest != before, 'compiler did not produce changed bytes')
    return digest


def prepare(args, work, version):
    repository = work / 'repository'
    if args.maven_cache.is_dir():
        shutil.copytree(args.maven_cache, repository, symlinks=False)
    else:
        repository.mkdir()
    namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
    pom = ROOT / 'maven-plugin/pom.xml'
    tree = ET.parse(pom)
    require(tree.findtext('m:version', namespaces=namespace) == version, 'Maven plugin version mismatch')
    dependencies = tree.findall('m:dependencies/m:dependency', namespace)
    require(any(d.findtext('m:artifactId', namespaces=namespace) == 'reclazz-agent'
                and d.findtext('m:version', namespaces=namespace) == version for d in dependencies),
            'Maven agent dependency mismatch')
    sources = {'reclazz-agent': ROOT / f'agent/build/libs/agent-{version}.jar',
               'reclazz-maven-plugin': ROOT / f'maven-plugin/target/reclazz-maven-plugin-{version}.jar'}
    evidence = {}
    for artifact, source in sources.items():
        require(source.is_file(), f'missing built artifact: {source}')
        directory = repository / 'com/onurkat/reclazz' / artifact / version
        directory.mkdir(parents=True, exist_ok=True)
        target = directory / f'{artifact}-{version}.jar'
        safety.overlay(target, source=source)
        if artifact == 'reclazz-maven-plugin':
            safety.overlay(directory / f'{artifact}-{version}.pom', source=pom)
        else:
            safety.overlay(directory / f'{artifact}-{version}.pom', text=f'<project><modelVersion>4.0.0</modelVersion>'
                           f'<groupId>com.onurkat.reclazz</groupId><artifactId>{artifact}</artifactId>'
                           f'<version>{version}</version></project>')
        require(safety.sha(target) == safety.sha(source), 'wrong installed artifact bytes')
        evidence[artifact] = {'source': str(source), 'sha256': safety.sha(source)}
    return repository, evidence


def exercise(args, work, results):
    require(os.name == 'posix', 'this process-group fixture requires Linux/macOS')
    version = re.search(r'^pluginVersion=(.+)$', (ROOT / 'gradle.properties').read_text(), re.M).group(1)
    repository, artifacts = prepare(args, work, version)
    results.update(version=version, artifacts=artifacts)
    logs = work / 'evidence'
    base = [args.maven, '-B', '-ntp', f'-Dmaven.repo.local={repository}', f'-Dreclazz.version={version}']
    if args.offline:
        base.append('--offline')
    run([args.maven, '-version'], work, logs / 'toolchain.log')
    for mode in ('normal', 'no-agent', 'unwatched'):
        project = work / (mode + ' project with spaces')
        shutil.copytree(ROOT / 'examples/maven-spring-boot', project,
                        ignore=shutil.ignore_patterns('target', '.reclazz'))
        with running(project, mode, base, logs) as (before, response):
            require(before.get('message') == BEFORE and before.get('marker') == 'kept', 'invalid HTTP baseline')
            if mode == 'no-agent':
                reason = rejects(lambda: doctor(project), 'missing agent port file')
                results['cases'].append({'case': mode, 'rejected': reason, 'before': before})
                continue
            observation = ready(project)
            check_doctor(observation, before, project, version, watch=mode == 'normal')
            initial = verify(project, safety.sha(project / CLASS_FILE))
            require(initial.get('status') == 'not_observed', 'fresh consumer already has a receipt')
            rejects(lambda: check_receipt(initial, observation['sessionId'], safety.sha(project / CLASS_FILE)), 'reload not applied')
            if mode == 'normal':
                rejects(lambda: check_doctor(observation, before, project, version + '-wrong'), 'wrong agent version')
            else:
                rejects(lambda: check_doctor(observation, before, project, version), 'wrong watched output')
            digest = edited(project, base, logs, mode)
            def receipt():
                value = verify(project, digest)
                require(value.get('status') in ('running', 'not_observed', 'applied'), f'reload rejected: {value}')
                return value
            if mode == 'normal':
                applied = until(receipt, lambda value: value.get('status') == 'applied', 30, 'reload receipt')
                check_receipt(applied, observation['sessionId'], digest)
                after = until(response, lambda value: value and value.get('message') == AFTER, 30, 'changed behavior')
                check_behavior(before, after)
                results['cases'].append({'case': mode, 'before': before, 'after': after,
                                         'doctor': observation, 'receipt': applied,
                                         'controls': ['connection-only rejected', 'wrong expected version rejected']})
            else:
                # Observe a bounded window, not just one early asynchronous response.
                deadline = time.monotonic() + 5
                while True:
                    not_applied = receipt(); after = response()
                    require(not_applied.get('status') == 'not_observed' and after and after.get('message') == BEFORE,
                            'unwatched-output negative control unexpectedly reloaded')
                    if time.monotonic() >= deadline: break
                    time.sleep(.2)
                rejects(lambda: check_receipt(not_applied, observation['sessionId'], digest), 'reload not applied')
                results['cases'].append({'case': mode, 'doctor': observation, 'receipt': not_applied, 'after': after,
                                         'rejected': 'reload not applied'})
        print(f'PASS {mode}', flush=True)
    results['passed'] = True


class OracleTest(unittest.TestCase):
    def test_receipt_requires_all_evidence(self):
        good = dict(status='applied', sessionId='session', className=CLASS, expectedSha256='a'*64, observedSha256='a'*64)
        check_receipt(good, 'session', 'a'*64)
        for key, wrong in [('status','not_observed'), ('status','failed'), ('sessionId','new'),
                           ('className','Other'), ('expectedSha256','b'*64), ('observedSha256','b'*64)]:
            with self.subTest(key=key, wrong=wrong):
                with self.assertRaises(Rejected): check_receipt({**good, key: wrong}, 'session', 'a'*64)

    def test_behavior_requires_same_jvm_and_preserved_argument(self):
        before = dict(message=BEFORE, pid=42, startedAt=123, marker='kept')
        after = {**before, 'message': AFTER}
        check_behavior(before, after)
        for key, value in [('message',BEFORE), ('pid',43), ('startedAt',124), ('marker','missing')]:
            with self.subTest(key=key):
                with self.assertRaises(Rejected): check_behavior(before, {**after, key:value})

    def test_doctor_requires_intended_agent_and_output(self):
        project = Path('/fixture')
        first = dict(pid=42, message=BEFORE, marker='kept')
        good = dict(status='observed', pid='42', workingDirectory=str(project), agentVersion='1.2.3',
                    sessionId='session', verifySupported=True, watcherState='watching', buildHold='none',
                    watchedDirectories=[str(project/'target/classes')])
        check_doctor(good, first, project, '1.2.3')
        for key, value in [('status','unavailable'), ('pid','43'), ('workingDirectory','/other'),
                           ('agentVersion','0.0.0'), ('sessionId',''), ('verifySupported',False),
                           ('watcherState','starting'), ('buildHold','named'), ('watchedDirectories',[])]:
            with self.subTest(key=key):
                with self.assertRaises(Rejected): check_doctor({**good,key:value}, first, project, '1.2.3')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--unit', action='store_true', help='only check acceptance oracles, no processes')
    parser.add_argument('--work-dir', type=Path, help='fresh directory for fixtures and evidence')
    parser.add_argument('--maven', default='mvn')
    parser.add_argument('--maven-cache', type=Path, default=Path.home()/'.m2/repository')
    parser.add_argument('--offline', action='store_true', help='require all Maven dependencies already cached')
    args = parser.parse_args()
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(OracleTest)
    if not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful(): return 1
    if args.unit: return 0
    if args.work_dir:
        work = args.work_dir.resolve(); work.mkdir(parents=True, exist_ok=False)
    else:
        parent = ROOT/'build/maven-consumer'; parent.mkdir(parents=True,exist_ok=True)
        work = Path(tempfile.mkdtemp(prefix='run-',dir=parent)).resolve()
    logs = work/'evidence'; logs.mkdir()
    results = {'passed': False, 'cases': [], 'workDirectory': str(work)}
    print(f'Evidence: {logs}', flush=True)
    def interrupted(signum, frame): raise KeyboardInterrupt(f'signal {signum}')
    old_handler = signal.signal(signal.SIGTERM, interrupted)
    try:
        exercise(args, work, results)
        return 0
    except BaseException as error:
        results['error'] = f'{type(error).__name__}: {error}'
        print(results['error'], file=sys.stderr)
        return 1
    finally:
        signal.signal(signal.SIGTERM, old_handler)
        (logs/'result.json').write_text(json.dumps(results, indent=2)+'\n')


if __name__ == '__main__':
    sys.exit(main())
