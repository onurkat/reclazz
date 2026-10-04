#!/usr/bin/env python3
"""Real-agent custom-cache characterization using installed SDK Spring and Guava jars."""
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
    platform = sdk / 'bin/platform'
    jars = sorted(platform.rglob('*.jar'))
    guava = [jar for jar in jars if jar.name.startswith('guava-')]
    if not agent.is_file() or len(guava) != 1:
        raise AssertionError('Built agent jar and exactly one installed platform Guava jar required')
    classpath = os.pathsep.join(map(str, jars))
    fixtures = repo / 'integration-test/src/sapSdkTest/caches/com/example'
    print('SDK Guava: ' + guava[0].name)

    def command(argv):
        result = subprocess.run(argv, capture_output=True, text=True, timeout=90)
        if result.returncode:
            raise AssertionError((result.stdout + result.stderr).replace(str(sdk), '<SDK>'))

    with tempfile.TemporaryDirectory(prefix='reclazz-cache-sdk-') as temporary:
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
        ready, identity = threading.Event(), []
        process = subprocess.Popen([
            'java', '-Dlog4j.configurationFactory=org.apache.logging.log4j.core.config.xml.XmlConfigurationFactory',
            '-Dlog4j.configurationFile=' + str(logging),
            f'-javaagent:{agent}=platform=generic,watchDirs={classes},portFile={port},startupDelaySec=1,debounceMs=100',
            '-cp', os.pathsep.join([str(classes), classpath]), 'com.example.CacheApp'],
            cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

        def read():
            for line in process.stdout:
                line = line.rstrip().replace(str(sdk), '<SDK>')
                lines.append(line)
                if line.startswith('CACHE_RESULT '): results.put(line)
                if line.startswith('CACHE_READY='): identity.append(line.split('=', 1)[1])
                if '] Watching 1 director' in line: ready.set()

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

            def operation(op='LOOK'):
                nonlocal serial, checks
                serial += 1
                token = f'op{serial}'
                process.stdin.write(f'{token} {op}\n'); process.stdin.flush()
                line = results.get(timeout=15)
                prefix = f'CACHE_RESULT {token} '
                assert line.startswith(prefix), line
                result = dict(part.split('=') for part in line[len(prefix):].split(';'))
                assert result.pop('wired') == 'true', 'Managed consumer references are stale'
                checks += 1
                return {key: int(value) for key, value in result.items()}

            previous = operation()
            assert previous['guava'] == previous['ttl'] == 11, previous
            for key in ['guavaLoads', 'ttlLoads', 'unrelatedGuavaLoads', 'unrelatedTtlLoads',
                        'guavaBuilds', 'ttlBuilds', 'unrelatedBuilds']:
                assert previous[key] == 1, previous
            assert operation() == previous, 'Warm reads recomputed cached values'
            checks += 2
            print('PASS: initial warm cache hits and managed references')
            session = None
            with socket.create_connection(('127.0.0.1', int(port.read_text().strip())), timeout=10) as connection:
                incoming = connection.makefile('r', encoding='utf-8')

                def reload(name, old, new):
                    nonlocal serial, session, checks
                    path = src / (name + '.java')
                    source = path.read_text()
                    assert source.count(old) == 1, 'Edit target must be unique'
                    path.write_text(source.replace(old, new))
                    command(['javac', '-proc:none', '-cp', os.pathsep.join([str(classes), classpath]),
                             '-d', str(candidate), str(path)])
                    relative = Path('com/example') / (name + '.class')
                    data = (candidate / relative).read_bytes()
                    digest, class_name = hashlib.sha256(data).hexdigest(), 'com.example.' + name
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
                    print(f'RECEIPT: {class_name} applied sha256={digest} session={session}')

                def check(label, guava, ttl, raw_guava, raw_ttl, refreshed=(), expire=False):
                    nonlocal previous, checks
                    current = operation('EXPIRE' if expire else 'LOOK')
                    expected = dict(guava=guava, ttl=ttl, rawGuava=raw_guava, rawTtl=raw_ttl,
                                    unrelatedGuava=99, unrelatedTtl=99)
                    for key, value in expected.items():
                        assert current[key] == value, (label, key, current)
                    for key in ['guava', 'ttl', 'unrelatedGuava', 'unrelatedTtl']:
                        delta = int(expire or key in refreshed)
                        assert current[key + 'Loads'] == previous[key + 'Loads'] + delta, (label, key, current)
                    for key in ['guava', 'ttl', 'unrelated']:
                        if key in refreshed:
                            assert current[key + 'Builds'] > previous[key + 'Builds'], (label, key, current)
                        else:
                            assert current[key + 'Builds'] == previous[key + 'Builds'], (label, key, current)
                    assert current['unrelatedId'] == previous['unrelatedId'], (label, current)
                    assert current['clock'] == previous['clock'] + (61000000000 if expire else 0), current
                    assert operation() == current, (label, 'warm repeat changed')
                    checks += 1
                    print(f'PASS: {label}: {json.dumps(current, sort_keys=True)}')
                    previous = current

                reload('RulesService', 'return 10;', 'return 20;')
                check('bean dependency only', 21, 21, 21, 21, ('guava', 'ttl'))
                reload('GuavaOwner', 'return 0;', 'return 100;')
                check('Guava owner', 121, 21, 121, 21, ('guava',))
                reload('TtlOwner', 'return 0;', 'return 200;')
                check('TTL owner', 121, 221, 121, 221, ('ttl',))
                reload('PlainHelper', 'return 1;', 'return 2;')
                if args.require_fresh_helper:
                    current = operation()
                    assert current['plain'] == 2 and current['rules'] == 20, current
                    assert current['rawGuava'] == 122 and current['rawTtl'] == 222, current
                    assert current['clock'] == previous['clock'], current
                    for key in ['unrelatedId', 'unrelatedBuilds', 'unrelatedGuava', 'unrelatedTtl',
                                'unrelatedGuavaLoads', 'unrelatedTtlLoads']:
                        assert current[key] == previous[key], (key, current)
                    assert current['guava'] == current['rawGuava'] and current['ttl'] == current['rawTtl'], (
                        'Desired helper freshness FAILED: applied helper=2, raw=122/222, '
                        f"cached={current['guava']}/{current['ttl']} before TTL")
                    assert operation() == current, 'Fresh helper results did not stay cached'
                    print('PASS: desired helper freshness before TTL, with unrelated caches retained')
                    incoming.close()
                    return
                check('known limitation: helper-only retains custom cached results', 121, 221, 122, 222)
                assert previous['plain'] == 2 and previous['rules'] == 20, previous
                checks += 1
                reload('GuavaOwner', 'return 100;', 'return 101;')
                check('Guava owner recovery', 123, 221, 123, 222, ('guava',))
                reload('TtlOwner', 'return 200;', 'return 201;')
                check('TTL owner recovery', 123, 223, 123, 223, ('ttl',))
                check('controlled TTL expiry', 123, 223, 123, 223, expire=True)
                incoming.close()
            print(f'PASS: {checks} SDK custom-cache characterization checks; PID/Spring {identity[0]}; '
                  'helper-only custom cache freshness is NOT supported by this result')
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
    parser.add_argument('--require-fresh-helper', action='store_true', help='desired-contract probe; fails while helper-only custom caches remain stale')
    args = parser.parse_args()
    try: run(args)
    except Exception as failure:
        raise SystemExit(str(failure).replace(str(args.hybris_home.resolve()), '<SDK>')) from None
