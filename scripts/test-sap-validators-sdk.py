#!/usr/bin/env python3
"""Real-agent acceptance for a genuine Hibernate Validator ConstraintValidator; no SAP runtime."""
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


def expected_body(vv, dv, nonce, pid):
    # inits stays 1: the provider-held validator instance is reused, its body hot-swapped.
    return {'nonce': nonce, 'pid': str(pid), 'violations': '1',
            'message': f'validator-v{vv}:ok-v{dv}:{nonce}', 'inits': '1', 'validations': '1'}


def run(args):
    repo = Path(__file__).resolve().parents[1]
    sdk = args.hybris_home.resolve()
    agent = args.agent.resolve()
    if not agent.is_file():
        raise AssertionError('Built agent jar is missing; run :agent:shadowJar and pass --agent')
    platform = sdk / 'bin/platform'
    jars = sorted(platform.rglob('*.jar'))
    if not list(platform.rglob('hibernate-validator-*.jar')):
        raise AssertionError('Installed Hibernate Validator jar is missing')
    if not list(platform.rglob('jakarta.validation-api-*.jar')):
        raise AssertionError('Installed jakarta.validation-api jar is missing')
    classpath = os.pathsep.join(map(str, jars))
    fixtures = repo / 'integration-test/src/sapSdkTest/validation/com/example'

    def command(command_args):
        result = subprocess.run(command_args, capture_output=True, text=True, timeout=90)
        if result.returncode:
            raise AssertionError((result.stdout + result.stderr).replace(str(sdk), '<SDK>'))

    with tempfile.TemporaryDirectory(prefix='reclazz-sap-validators-') as temp:
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
        lines = deque(maxlen=120)
        http_port = []
        ready = threading.Event()
        process = subprocess.Popen([
            'java', '-Dlog4j.configurationFactory=org.apache.logging.log4j.core.config.xml.XmlConfigurationFactory',
            '-Dlog4j.configurationFile=' + str(logging),
            f'-javaagent:{agent}=platform=generic,watchDirs={classes},portFile={status_port},startupDelaySec=1,debounceMs=100',
            '-cp', os.pathsep.join([str(classes), classpath]), 'com.example.ValidatorApp'],
            cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

        def output():
            for line in process.stdout:
                lines.append(line.rstrip().replace(str(sdk), '<SDK>'))
                if line.startswith('VALIDATOR_PORT='):
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

            def behavior(nonce):
                with urllib.request.urlopen(f'http://127.0.0.1:{http_port[0]}/?{nonce}', timeout=10) as response:
                    return response.status, dict(part.split('=', 1) for part in response.read().decode().split(';'))

            with socket.create_connection(('127.0.0.1', int(status_port.read_text().strip())), timeout=5) as connection:
                incoming = connection.makefile('r', encoding='utf-8')
                request_number = 0

                def query(class_name, digest):
                    nonlocal request_number
                    request_number += 1
                    token = f'cv{request_number}'
                    connection.sendall(f'VERIFY {token} {class_name} {digest}\n'.encode())
                    prefix = f'VERIFY_RESULT {token} '
                    while True:
                        line = incoming.readline()
                        if not line:
                            raise AssertionError('Agent closed its status connection')
                        message = json.loads(line)['message']
                        if message.startswith(prefix):
                            return token, json.loads(message[len(prefix):])

                checks = 0
                vv, dv = 1, 1
                nonce = str(uuid.uuid4())
                status, body = behavior(nonce)
                assert status == 200 and body == expected_body(1, 1, nonce, process.pid), body
                checks += 1

                session = None
                targets = [('validator', 'ProbeValidator', [2, 3, 2]), ('dependency', 'Dependency', [2])]
                for kind, name, versions in targets:
                    previous = None
                    marker = 'validator-v1:' if kind == 'validator' else 'ok-v1'
                    for version in versions:
                        text = (fixtures / (name + '.java')).read_text().replace(marker, marker.replace('1', str(version)))
                        if kind == 'validator' and version == 3:
                            literal = '"validator-v3:"'
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
                        if kind == 'validator':
                            vv = version
                        else:
                            dv = version
                        nonce = str(uuid.uuid4())
                        status, body = behavior(nonce)
                        check_proof(receipt, token, class_name, digest, session, status, body,
                                    expected_body(vv, dv, nonce, process.pid))
                        checks += 1
                        if previous and previous != digest:
                            _, old = query(class_name, previous)
                            assert old.get('status') == 'mismatch' and old.get('observedSha256') == digest, old
                            checks += 1
                        previous = digest
                        print(f'PASS: {kind} v{version}, applied receipt, reused validator (inits={body["inits"]}), '
                              f'message={body["message"]}')
                incoming.close()
                print(f'PASS: {checks} ConstraintValidator checks; Hibernate Validator; one JVM/session; '
                      'structural annotation edits and provider lifecycle beyond instance reuse UNVERIFIED')
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
