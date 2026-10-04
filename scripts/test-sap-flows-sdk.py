#!/usr/bin/env python3
"""Real-agent acceptance with installed SAP contracts; no tenant or scheduler startup."""
import argparse
from collections import deque
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import urllib.request
import uuid


def expected_body(flow, version, nonce, pid, spring):
    result = {'nonce': nonce, 'pid': str(pid), 'spring': spring, 'stable': 'true',
              'held': f'{flow}-v{version}:{nonce}', 'current': f'{flow}-v{version}:{nonce}'}
    if flow == 'dynamic':
        result.update(stored=f'set-v{version}:{nonce}', currentStored=f'set-v{version}:{nonce}', gets='2', sets='2')
    else:
        code = 'FAILURE' if version == 2 else 'SUCCESS'
        result.update(result=code, currentResult=code, status='FINISHED', currentStatus='FINISHED',
                      jobs='2', performable='true', abortable='false')
    return result


def check_proof(receipt, request, class_name, digest, session, http_status, body, expected):
    """Neither a successful receipt nor changed behavior alone is accepted."""
    fields = {'requestId': request, 'className': class_name, 'expectedSha256': digest,
              'observedSha256': digest, 'status': 'applied', 'sessionId': session}
    for key, value in fields.items():
        if receipt.get(key) != value:
            raise AssertionError(f'Receipt {key}: expected {value}, got {receipt.get(key)}')
    if not session or not receipt.get('completedAt'):
        raise AssertionError('Missing session/completion evidence')
    if http_status != 200 or body != expected:
        raise AssertionError(f'Behavior mismatch: HTTP {http_status}, expected {expected}, got {body}')


def run(args):
    repo = Path(__file__).resolve().parents[1]
    sdk = args.hybris_home.resolve()
    agent = args.agent.resolve()
    if not agent.is_file():
        raise AssertionError('Built agent jar is missing; run :agent:shadowJar and pass --agent')
    platform = sdk / 'bin/platform'
    jars = sorted(platform.rglob('*.jar'))
    spring_jars = list(platform.rglob('spring-core-*.jar'))
    if len(spring_jars) != 1:
        raise AssertionError('Expected exactly one SDK spring-core jar')
    spring = spring_jars[0].name[len('spring-core-'):-len('.jar')]
    for jar in ['ext/core/bin/coreserver.jar', 'ext/processing/bin/processingserver.jar', 'bootstrap/bin/models.jar']:
        if not (platform / jar).is_file():
            raise AssertionError('Required SDK core/processing/models jar is missing')
    classpath = os.pathsep.join(map(str, jars))
    fixtures = repo / 'integration-test/src/sapSdkTest/flows/com/example'

    def command(command_args):
        result = subprocess.run(command_args, capture_output=True, text=True, timeout=90)
        if result.returncode:
            raise AssertionError((result.stdout + result.stderr).replace(str(sdk), '<SDK>'))

    with tempfile.TemporaryDirectory(prefix='reclazz-sap-flows-') as temp:
        root = Path(temp)
        src = root / 'src/com/example'
        src.mkdir(parents=True)
        for file in fixtures.glob('*.java'):
            (src / file.name).write_text(file.read_text())
        classes = root / 'classes'
        classes.mkdir()
        command(['javac', '-proc:none', '-cp', classpath, '-d', str(classes), *map(str, src.glob('*.java'))])
        logging = root / 'log4j2.xml'
        logging.write_text('<Configuration><Appenders/><Loggers><Root level="off"/></Loggers></Configuration>')
        status_port = root / 'status.port'
        lines = deque(maxlen=100)
        http_port = []
        ready = threading.Event()
        process = subprocess.Popen([
            'java', '-Dlog4j.configurationFactory=org.apache.logging.log4j.core.config.xml.XmlConfigurationFactory',
            '-Dlog4j.configurationFile=' + str(logging),
            f'-javaagent:{agent}=platform=generic,watchDirs={classes},portFile={status_port},startupDelaySec=1,debounceMs=100',
            '-cp', os.pathsep.join([str(classes), classpath]), 'com.example.FlowApp'],
            cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

        def output():
            for line in process.stdout:
                lines.append(line.rstrip().replace(str(sdk), '<SDK>'))
                if line.startswith('FLOW_PORT='):
                    http_port.append(int(line.split('=', 1)[1]))
                if '] Watching 1 director' in line:
                    ready.set()

        reader = threading.Thread(target=output, daemon=True)
        reader.start()
        try:
            deadline = time.monotonic() + 60
            while not (ready.is_set() and http_port and status_port.is_file()):
                if process.poll() is not None or time.monotonic() > deadline:
                    raise AssertionError('Synthetic SDK application/watcher did not start')
                time.sleep(.1)

            def behavior(flow, nonce):
                # This endpoint belongs to the temporary process, never the installed application.
                with urllib.request.urlopen(f'http://127.0.0.1:{http_port[0]}/{flow}?{nonce}', timeout=10) as response:
                    return response.status, dict(part.split('=', 1) for part in response.read().decode().split(';'))

            with socket.create_connection(('127.0.0.1', int(status_port.read_text().strip())), timeout=5) as connection:
                incoming = connection.makefile('r', encoding='utf-8')
                request_number = 0

                def query(class_name, digest):
                    nonlocal request_number
                    request_number += 1
                    token = f'flow{request_number}'
                    connection.sendall(f'VERIFY {token} {class_name} {digest}\n'.encode())
                    prefix = f'VERIFY_RESULT {token} '
                    while True:
                        line = incoming.readline()
                        if not line:
                            raise AssertionError('Agent closed its status connection')
                        message = json.loads(line)['message']
                        if message.startswith(prefix):
                            return token, json.loads(message[len(prefix):])

                session = None
                checks = 0
                versions = {'dynamic': 1, 'job': 1}
                for flow in versions:
                    nonce = str(uuid.uuid4())
                    status, body = behavior(flow, nonce)
                    assert status == 200 and body == expected_body(flow, 1, nonce, process.pid, spring), body
                    checks += 1
                for flow, name in [('dynamic', 'DynamicHandler'), ('job', 'ProbeJob')]:
                    previous = None
                    for version in [2, 3, 2]:
                        text = (fixtures / (name + '.java')).read_text().replace('-v1:', f'-v{version}:')
                        if flow == 'job' and version == 2:
                            text = text.replace('CronJobResult.SUCCESS', 'CronJobResult.FAILURE')
                        if version == 3:
                            # Exercise a genuine structural reload without changing SAP superclasses.
                            literal = f'"{flow}-v3:"'
                            assert text.count(literal) == 1
                            text = text.replace(literal, 'prefix()')
                            position = text.rfind('}')
                            text = text[:position] + f'    private String prefix() {{ return {literal}; }}\n' + text[position:]
                        source = src / (name + '.java')
                        source.write_text(text)
                        candidate = root / 'candidate'
                        command(['javac', '-proc:none', '-cp', os.pathsep.join([str(classes), classpath]),
                                 '-d', str(candidate), str(source)])
                        relative = Path('com/example') / (name + '.class')
                        data = (candidate / relative).read_bytes()
                        digest = hashlib.sha256(data).hexdigest()
                        if not args.withhold_edit:
                            staged = (classes / relative).with_suffix('.pending')
                            staged.write_bytes(data)
                            staged.replace(classes / relative)
                        class_name = 'com.example.' + name
                        deadline = time.monotonic() + 45
                        while True:
                            token, receipt = query(class_name, digest)
                            if args.withhold_edit or receipt.get('status') in ['applied', 'failed', 'unverified']:
                                break
                            if time.monotonic() > deadline:
                                raise AssertionError('No matching reload receipt: ' + str(receipt))
                            time.sleep(.1)
                        session = session or receipt.get('sessionId')
                        nonce = str(uuid.uuid4())
                        status, body = behavior(flow, nonce)
                        check_proof(receipt, token, class_name, digest, session, status, body,
                                    expected_body(flow, version, nonce, process.pid, spring))
                        checks += 1
                        if previous and previous != digest:
                            _, old = query(class_name, previous)
                            assert old.get('status') == 'mismatch' and old.get('observedSha256') == digest, old
                            checks += 1
                        previous = digest
                        versions[flow] = version
                        other = 'job' if flow == 'dynamic' else 'dynamic'
                        nonce = str(uuid.uuid4())
                        status, body = behavior(other, nonce)
                        assert status == 200 and body == expected_body(other, versions[other], nonce, process.pid, spring), body
                        checks += 1
                        print(f'PASS: {flow} v{version}, exact receipt and fresh behavior, unrelated {other} retained')
                incoming.close()
                print(f'PASS: {checks} SDK flow checks; Spring {spring}; one JVM/session; no tenant or scheduler started')
        except Exception:
            print('\n'.join(lines))
            raise
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
            reader.join(timeout=5)
            process.stdout.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('hybris_home', type=Path)
    parser.add_argument('--agent', required=True, type=Path)
    parser.add_argument('--withhold-edit', action='store_true', help='negative control: must reject unchanged behavior/receipt')
    options = parser.parse_args()
    try:
        run(options)
    except Exception as failure:
        raise SystemExit(str(failure).replace(str(options.hybris_home.resolve()), '<SDK>')) from None
