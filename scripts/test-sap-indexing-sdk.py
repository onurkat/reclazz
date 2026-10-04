#!/usr/bin/env python3
"""Real-agent SDK indexing acceptance using a local snapshot store, never a Solr server."""
import argparse
from collections import deque
import hashlib
import json
import os
from pathlib import Path
import queue
import socket
import subprocess
import tempfile
import threading
import time


def run(args):
    repo = Path(__file__).resolve().parents[1]
    sdk, agent = args.hybris_home.resolve(), args.agent.resolve()
    module = sdk / 'bin/modules/search-and-navigation/solrfacetsearch'
    if not agent.is_file() or not (module / 'bin/solrfacetsearchserver.jar').is_file():
        raise AssertionError('Built agent jar and SDK solrfacetsearch module are required')
    jars = sorted((sdk / 'bin/platform').rglob('*.jar')) + sorted(module.rglob('*.jar'))
    classpath = os.pathsep.join(map(str, jars))
    fixtures = repo / 'integration-test/src/sapSdkTest/indexing/com/example'

    def command(argv):
        result = subprocess.run(argv, capture_output=True, text=True, timeout=90)
        if result.returncode:
            raise AssertionError((result.stdout + result.stderr).replace(str(sdk), '<SDK>'))

    with tempfile.TemporaryDirectory(prefix='reclazz-index-sdk-') as temporary:
        root = Path(temporary)
        src, classes, candidate = root / 'src', root / 'classes', root / 'candidate'
        src.mkdir(); classes.mkdir(); candidate.mkdir()
        for file in fixtures.glob('*.java'):
            (src / file.name).write_text(file.read_text())
        command(['javac', '-proc:none', '-cp', classpath, '-d', str(classes), *map(str, src.glob('*.java'))])
        logging = root / 'log4j2.xml'
        logging.write_text('<Configuration><Appenders/><Loggers><Root level="off"/></Loggers></Configuration>')
        port = root / 'status.port'
        lines, results = deque(maxlen=100), queue.Queue()
        guidance = []
        ready, identity = threading.Event(), []
        process = subprocess.Popen([
            'java', '-Dlog4j.configurationFactory=org.apache.logging.log4j.core.config.xml.XmlConfigurationFactory',
            '-Dlog4j.configurationFile=' + str(logging),
            f'-javaagent:{agent}=platform=generic,watchDirs={classes},portFile={port},startupDelaySec=1,debounceMs=100',
            '-cp', os.pathsep.join([str(classes), classpath]), 'com.example.IndexingApp'],
            cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

        def read():
            for line in process.stdout:
                line = line.rstrip().replace(str(sdk), '<SDK>')
                lines.append(line)
                if line.startswith('INDEX_RESULT '): results.put(line)
                if line.startswith('INDEX_READY='): identity.append(line.split('=', 1)[1])
                if '] Watching 1 director' in line: ready.set()
                if '[INFO]' in line and 'SAP indexing code ' in line: guidance.append(line)

        reader = threading.Thread(target=read, daemon=True)
        reader.start()
        try:
            deadline = time.monotonic() + 60
            while not (identity and ready.is_set() and port.is_file()):
                if process.poll() is not None or time.monotonic() > deadline:
                    raise AssertionError('Isolated SDK application/watcher did not start')
                time.sleep(.1)
            assert identity[0].split(';')[0] == str(process.pid)
            serial, checks = 0, 0

            def operation(op, document='old'):
                nonlocal serial
                serial += 1
                token = f'op{serial}'
                process.stdin.write(f'{token} {op} {document}\n'); process.stdin.flush()
                line = results.get(timeout=15)
                prefix = f'INDEX_RESULT {token} '
                assert line.startswith(prefix), line
                return line[len(prefix):]

            def snapshot(versions):
                return ';'.join(f'{key}={key}-v{versions[key]}' for key in ['provider', 'resolver', 'type'])

            versions = dict(provider=1, resolver=1, type=1)
            assert operation('INDEX') == snapshot(versions)
            checks += 1
            session = None
            with socket.create_connection(('127.0.0.1', int(port.read_text().strip())), timeout=10) as connection:
                incoming = connection.makefile('r', encoding='utf-8')
                for key, name in [('provider', 'ProbeProvider'), ('resolver', 'ProbeResolver'),
                                  ('type', 'ProbeTypeResolver'), ('ordinary', 'Ordinary')]:
                    for version in ([2] if key == 'ordinary' else [2, 3, 2]):
                        source = (fixtures / (name + '.java')).read_text().replace(f'{key}-v1', f'{key}-v{version}')
                        if version == 3:
                            literal = f'"{key}-v3"'
                            assert source.count(literal) == 1
                            source = source.replace(literal, 'value()')
                            end = source.rfind('}')
                            source = source[:end] + f'    private String value() {{ return {literal}; }}\n' + source[end:]
                        path = src / (name + '.java'); path.write_text(source)
                        command(['javac', '-proc:none', '-cp', os.pathsep.join([str(classes), classpath]),
                                 '-d', str(candidate), str(path)])
                        relative = Path('com/example') / (name + '.class')
                        data = (candidate / relative).read_bytes()
                        digest, class_name = hashlib.sha256(data).hexdigest(), 'com.example.' + name
                        before = len(guidance)
                        if not args.withhold_edit:
                            staged = (classes / relative).with_suffix('.pending')
                            staged.write_bytes(data); staged.replace(classes / relative)
                        deadline = time.monotonic() + 45
                        while True:
                            serial += 1; token = f'verify{serial}'
                            connection.sendall(f'VERIFY {token} {class_name} {digest}\n'.encode())
                            prefix = f'VERIFY_RESULT {token} '
                            while True:
                                line = incoming.readline()
                                if not line: raise AssertionError('Status connection closed')
                                message = json.loads(line)['message']
                                if message.startswith(prefix):
                                    receipt = json.loads(message[len(prefix):]); break
                            if args.withhold_edit or receipt.get('status') in ['applied', 'failed', 'unverified']: break
                            if time.monotonic() > deadline: raise AssertionError('Matching receipt timed out')
                            time.sleep(.1)
                        session = session or receipt.get('sessionId')
                        expected = dict(requestId=token, className=class_name, status='applied',
                                        expectedSha256=digest, observedSha256=digest, sessionId=session)
                        assert session and receipt.get('completedAt') and all(receipt.get(k) == v for k, v in expected.items()), receipt
                        checks += 1
                        assert operation('LOOK') == snapshot(versions), 'Stored documents changed during code reload'
                        checks += 1
                        if key == 'ordinary':
                            assert operation('ORDINARY') == 'ordinary-v2'
                            assert len(guidance) == before, guidance
                            checks += 2
                        else:
                            versions[key] = version
                            assert operation('INDEX', 'new') == snapshot(versions), 'New document computation stayed stale'
                            assert operation('INDEX') == snapshot(versions), 'Explicit reindex stayed stale'
                            checks += 2
                            deadline = time.monotonic() + 10
                            while len(guidance) == before and time.monotonic() < deadline: time.sleep(.05)
                            assert len(guidance) == before + 1, guidance
                            advice = guidance[-1]
                            assert f'SAP indexing code {class_name} reloaded' in advice and 'Reclazz did not reindex stored documents' in advice, advice
                            checks += 1
                        print(f'PASS: {name} v{version}, matching receipt, isolated stored/new document checks and guidance')
                incoming.close()
            print(f'PASS: {checks} SDK indexing checks; PID/Spring {identity[0]}; no tenant or Solr server')
        except Exception:
            print('\n'.join(lines))
            raise
        finally:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait(timeout=10)
            reader.join(timeout=5)
            process.stdin.close(); process.stdout.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('hybris_home', type=Path)
    parser.add_argument('--agent', required=True, type=Path)
    parser.add_argument('--withhold-edit', action='store_true', help='negative control; unchanged code must fail acceptance')
    args = parser.parse_args()
    try: run(args)
    except Exception as failure:
        raise SystemExit(str(failure).replace(str(args.hybris_home.resolve()), '<SDK>')) from None
