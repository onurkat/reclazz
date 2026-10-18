#!/usr/bin/env python3
"""G1 native SDK Spring constructor controls and desired watcher acceptance; no tenant."""
import argparse
from collections import deque
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.request
import uuid


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sdk_graphs(root):
    found = {}
    wanted = {'5.3.43': '7b75c95797c6270cf61088c331aef46e6011c5244231e5996f81c04e73dad271',
              '6.2.12': '26ab8ce28a42fe951b0a4ebaf8dbe97478effca833a7b2f51b45d6540fe7179f'}
    for folder, dirs, files in os.walk(root):
        rel = Path(folder).relative_to(root)
        dirs[:] = [d for d in dirs if d not in {'.git', '.collab', 'node_modules', 'target', 'build', '.gradle', '.idea'}
                   and (len(rel.parts) < 5 or d in {'hybris', 'bin', 'platform', 'lib', 'ext', 'core', 'bootstrap', 'resources'})]
        if len(rel.parts) > 12:
            dirs[:] = []
        for version, digest in wanted.items():
            name = f'spring-beans-{version}.jar'
            if name not in files or version in found:
                continue
            jar = Path(folder) / name
            if sha(jar) != digest:
                continue
            platform = next((p for p in jar.parents if p.name == 'platform'), None)
            if platform is None:
                continue
            jars = []
            for artifact in ['spring-beans', 'spring-core', 'spring-context', 'spring-expression', 'spring-aop']:
                p = jar.parent / f'{artifact}-{version}.jar'
                if not p.is_file():
                    raise AssertionError('Incomplete exact SDK Spring graph: ' + artifact)
                jars.append(p)
            support = sorted(p for p in platform.rglob('*.jar')
                             if p.name.startswith(('commons-logging-', 'spring-jcl-', 'micrometer-observation-', 'micrometer-commons-')))
            jars.extend(support)
            found[version] = {'root': platform, 'jars': jars}
    if set(found) != set(wanted):
        raise AssertionError('Required exact SDK graphs unavailable; this is setup failure, not RED')
    return found


def fields(text):
    return dict(part.split('=', 1) for part in text.strip().split(';'))


def run(args):
    repo = Path(__file__).resolve().parents[1]
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    lock = (out / 'RUN.lock').open('w')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    graphs = sdk_graphs(args.sdk_search_root.resolve())
    fixtures = repo / 'integration-test/src/sapSdkTest/constructor/com/example'
    sources = {p.name: p.read_text() for p in sorted(fixtures.glob('*.java'))}
    agent = args.agent.resolve()
    assert agent.is_file()
    summary = {'status': 'RUNNING', 'kind': 'G1_BASELINE_TESTS_NO_REPAIR', 'agent_sha256': sha(agent),
               'source_sha256': {n: sha(fixtures / n) for n in sources}, 'cases': [], 'setup_failures': []}

    def save():
        (out / 'results.json').write_text(json.dumps(summary, indent=2) + '\n')

    save()
    for version, graph in sorted(graphs.items()):
        cp = os.pathsep.join(map(str, graph['jars']))
        for jdk in [17, 21]:
            lane = f'spring-{version}-jdk-{jdk}'
            java = Path(f'/Library/Java/JavaVirtualMachines/sapmachine-{jdk}.jdk/Contents/Home/bin/java')
            javac = java.with_name('javac')
            assert java.is_file() and javac.is_file()
            lane_out = out / lane
            lane_out.mkdir(exist_ok=True)

            def redact(text):
                return text.replace(str(graph['root']), '<SDK>')

            def command(argv):
                result = subprocess.run(list(map(str, argv)), capture_output=True, text=True, timeout=90)
                if result.returncode:
                    raise AssertionError('Setup command failed: ' + redact(result.stdout + result.stderr))
                return result.stdout

            def record(case):
                case['lane'] = lane
                summary['cases'].append(case)
                save()
                print(lane, case['name'], case['verdict'], flush=True)

            try:
                with tempfile.TemporaryDirectory(prefix='reclazz-constructor-') as temp:
                    temp = Path(temp)
                    src = temp / 'src/com/example'
                    src.mkdir(parents=True)
                    for name, text in sources.items():
                        (src / name).write_text(text)
                    base = temp / 'base'
                    base.mkdir()
                    command([javac, '-proc:none', '-cp', cp, '-d', base, *sorted(src.glob('*.java'))])
                    helper_variants = {10: base / 'com/example/Helper.class'}
                    owner_variants = {}
                    for seed in [20, 30, 40]:
                        (src / 'Helper.java').write_text(sources['Helper.java'].replace('return 10;', f'return {seed};'))
                        dest = temp / f'helper-{seed}'
                        command([javac, '-proc:none', '-cp', os.pathsep.join([str(base), cp]), '-d', dest, src / 'Helper.java'])
                        helper_variants[seed] = dest / 'com/example/Helper.class'
                    for marker in [2, 3, 4]:
                        (src / 'Owner.java').write_text(sources['Owner.java'].replace('owner-v1', f'owner-v{marker}'))
                        dest = temp / f'owner-{marker}'
                        command([javac, '-proc:none', '-cp', os.pathsep.join([str(base), cp]), '-d', dest, src / 'Owner.java'])
                        owner_variants[marker] = dest / 'com/example/Owner.class'

                    for shape in ['root', 'parent']:
                        for seed in [10, 20, 30, 40]:
                            native = temp / f'native-{shape}-{seed}'
                            shutil.copytree(base, native)
                            shutil.copyfile(helper_variants[seed], native / 'com/example/Helper.class')
                            output = command([java, '-cp', os.pathsep.join([str(native), cp]),
                                              'com.example.ConstructorOwnershipApp', 'native', shape])
                            snap = fields(next(line[5:] for line in output.splitlines() if line.startswith('SNAP=')))
                            ok = (snap['spring'] == version and snap['factoryJar'] == f'spring-beans-{version}.jar'
                                  and snap['heldCaptured'] == str(seed) and snap['helperSeed'] == str(seed)
                                  and all(snap[x] == 'true' for x in ['forward', 'reverse', 'heldIsCurrent', 'unrelated']))
                            record({'name': f'native-fresh-{shape}-{seed}', 'kind': 'NATIVE_CONTROL',
                                    'verdict': 'PASS' if ok else 'FAIL', 'snapshot': snap})
                            if not ok:
                                raise AssertionError('Native control failed; watcher results would not establish RED')
                        output = command([java, '-cp', os.pathsep.join([str(base), cp]),
                                          'com.example.ConstructorOwnershipApp', 'replay', shape])
                        snap = fields(next(line[5:] for line in output.splitlines() if line.startswith('SNAP=')))
                        ok = snap['heldCaptured'] == '10' and snap['forward'] == 'false' and snap['reverse'] == 'false'
                        record({'name': f'native-cached-replay-{shape}', 'kind': 'CACHE_CHARACTERIZATION',
                                'verdict': 'PASS' if ok else 'FAIL', 'snapshot': snap})

                    for shape, owner_first, withheld in [('root', True, False), ('parent', True, False),
                                                          ('parent', False, False), ('root', False, False),
                                                          ('parent', False, True)]:
                        case_name = f'watch-{shape}-ownerfirst-{owner_first}-withheld-{withheld}'
                        classes = temp / case_name
                        shutil.copytree(base, classes)
                        port_file = temp / (case_name + '.port')
                        lines = deque(maxlen=400)
                        app_ports = []
                        ready = threading.Event()
                        process = subprocess.Popen([str(java), f'-javaagent:{agent}=platform=generic,watchDirs={classes},portFile={port_file},startupDelaySec=1,debounceMs=100',
                                                    '-cp', os.pathsep.join([str(classes), cp]),
                                                    'com.example.ConstructorOwnershipApp', 'watch', shape],
                                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

                        def output_reader():
                            for line in process.stdout:
                                lines.append(redact(line.rstrip()))
                                if line.startswith('CONSTRUCTOR_PORT='):
                                    app_ports.append(int(line.split('=', 1)[1]))
                                if '] Watching 1 director' in line:
                                    ready.set()

                        reader = threading.Thread(target=output_reader, daemon=True)
                        reader.start()
                        observations = []
                        infrastructure_error = None
                        connection = None
                        stream = None
                        try:
                            deadline = time.monotonic() + 40
                            while not (app_ports and port_file.is_file() and ready.is_set()):
                                if process.poll() is not None or time.monotonic() > deadline:
                                    raise AssertionError('Watcher startup failed; not behavioral RED')
                                time.sleep(.1)
                            connection = socket.create_connection(('127.0.0.1', int(port_file.read_text())), timeout=15)
                            stream = connection.makefile('r', encoding='utf-8')
                            session = None

                            def snapshot():
                                nonce = str(uuid.uuid4())
                                with urllib.request.urlopen(f'http://127.0.0.1:{app_ports[0]}/?{nonce}', timeout=10) as response:
                                    snap = fields(response.read().decode())
                                    assert response.status == 200 and snap['nonce'] == nonce
                                assert snap['pid'] == str(process.pid) and snap['spring'] == version
                                assert snap['factoryJar'] == f'spring-beans-{version}.jar'
                                return snap

                            initial = snapshot()
                            assert initial['heldCaptured'] == '10' and initial['heldIsCurrent'] == 'true'
                            owner_hash = sha(classes / 'com/example/Owner.class')

                            def edit(name, candidate, expected, marker=None):
                                nonlocal session
                                before = snapshot()
                                data = candidate.read_bytes()
                                digest = hashlib.sha256(data).hexdigest()
                                target = classes / 'com/example' / (name + '.class')
                                if not withheld:
                                    pending = target.with_suffix('.pending')
                                    pending.write_bytes(data)
                                    pending.replace(target)
                                deadline = time.monotonic() + 35
                                while True:
                                    token = 'ctor-' + uuid.uuid4().hex[:12]
                                    connection.sendall(f'VERIFY {token} com.example.{name} {digest}\n'.encode())
                                    prefix = 'VERIFY_RESULT ' + token + ' '
                                    while True:
                                        line = stream.readline()
                                        if not line:
                                            raise AssertionError('Status connection closed')
                                        message = json.loads(line)['message']
                                        if message.startswith(prefix):
                                            receipt = json.loads(message[len(prefix):])
                                            break
                                    if withheld or receipt.get('status') in {'applied', 'failed', 'unverified'}:
                                        break
                                    if time.monotonic() > deadline:
                                        raise AssertionError('No final matching receipt')
                                    time.sleep(.1)
                                snap = snapshot()
                                if withheld:
                                    checks = {'not_applied': receipt.get('status') != 'applied',
                                              'unchanged_behavior': snap['heldCaptured'] == '10' and snap['helperSeed'] == '10'}
                                else:
                                    session = session or receipt.get('sessionId')
                                    checks = {'applied_matching_receipt': receipt.get('status') == 'applied'
                                              and receipt.get('requestId') == token and receipt.get('className') == 'com.example.' + name
                                              and receipt.get('expectedSha256') == digest and receipt.get('observedSha256') == digest
                                              and bool(session) and receipt.get('sessionId') == session and bool(receipt.get('completedAt')),
                                              'held_captured_current': snap['heldCaptured'] == str(expected),
                                              'current_captured_current': snap['currentCaptured'] == str(expected),
                                              'helper_current': snap['helperSeed'] == str(expected),
                                              'held_owner_current': snap['heldIsCurrent'] == 'true',
                                              'held_helper_current': snap['heldHelperIsCurrent'] == 'true',
                                              'forward_edge': snap['forward'] == 'true', 'reverse_edge': snap['reverse'] == 'true',
                                              'unrelated_preserved': snap['unrelated'] == 'true'}
                                    checks['framework_receiver_recreated'] = (snap['ownerIdentity'] != before['ownerIdentity']
                                                                            if name == 'Owner' else snap['helperIdentity'] != before['helperIdentity'])
                                    if marker is not None:
                                        checks['owner_body_applied'] = snap['ownerMarker'] == f'owner-v{marker}'
                                observations.append({'saved': name, 'expected': expected, 'candidate_sha256': digest,
                                                     'checks': checks, 'snapshot': snap, 'receipt': receipt})

                            if owner_first:
                                for marker in [2, 3, 4]:
                                    edit('Owner', owner_variants[marker], 10, marker)
                                owner_hash = sha(classes / 'com/example/Owner.class')
                            for seed in ([20] if withheld else [20, 30, 40]):
                                edit('Helper', helper_variants[seed], seed)
                                assert sha(classes / 'com/example/Owner.class') == owner_hash, 'Owner edited during helper-only save'
                        except Exception as failed:
                            infrastructure_error = redact(str(failed))
                        finally:
                            if stream is not None:
                                stream.close()
                            if connection is not None:
                                connection.close()
                            if process.poll() is None:
                                process.terminate()
                                try:
                                    process.wait(timeout=10)
                                except subprocess.TimeoutExpired:
                                    process.kill()
                                    process.wait(timeout=10)
                            reader.join(timeout=5)
                            process.stdout.close()
                            (lane_out / (case_name + '.log')).write_text('\n'.join(lines) + '\n')
                        failed_checks = [k for obs in observations for k, value in obs['checks'].items() if not value]
                        verdict = 'SETUP_ERROR' if infrastructure_error else ('FAIL' if failed_checks else 'PASS')
                        record({'name': case_name, 'kind': 'WITHHELD_CONTROL' if withheld else 'DESIRED_WATCHER',
                                'verdict': verdict, 'observations': observations, 'failed_checks': failed_checks,
                                'infrastructure_error': infrastructure_error, 'owned_process_exited': process.poll() is not None})
            except Exception as failed:
                summary['setup_failures'].append({'lane': lane, 'error': redact(str(failed))})
                save()
    summary['status'] = 'COMPLETE'
    summary['counts'] = {v: sum(c['verdict'] == v for c in summary['cases']) for v in ['PASS', 'FAIL', 'SETUP_ERROR']}
    save()
    print(json.dumps(summary['counts']), flush=True)
    return 1 if summary['setup_failures'] or summary['counts']['FAIL'] or summary['counts']['SETUP_ERROR'] else 0


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk-search-root', type=Path, required=True)
    parser.add_argument('--agent', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    options = parser.parse_args()
    raise SystemExit(run(options))
