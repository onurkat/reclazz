#!/usr/bin/env python3
"""Actual cockpitng binding/listener regression, detached ZK components, no running SAP tenant."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import subprocess
import tempfile
import threading
import time


def run(args):
    repo = Path(__file__).resolve().parents[1]
    sdk, jar = args.hybris_home.resolve(), args.agent.resolve()
    lib = sdk / 'bin/modules/backoffice-framework/backoffice/web/webroot/WEB-INF/lib'
    if not jar.is_file() or not list(lib.glob('cockpitframework*.jar')):
        raise AssertionError('Built agent and SDK Backoffice cockpitframework jars required')
    binaries = Path(args.java_home) / 'bin' if args.java_home else None
    java, javac = (str(binaries / name) if binaries else name for name in ['java', 'javac'])
    jars = sorted(lib.glob('*.jar')) + sorted((sdk / 'bin/platform/tomcat/lib').glob('*.jar'))
    for pattern in ['slf4j-api*.jar', 'commons-lang3*.jar', 'spring-*.jar']:
        jars += sorted((sdk / 'bin/platform').rglob(pattern))
    cp = os.pathsep.join(map(str, [jar] + jars))
    fixtures = repo / 'integration-test/src/sapSdkTest/backoffice/com/example'
    with tempfile.TemporaryDirectory(prefix='reclazz-backoffice-sdk-') as temporary:
        root = Path(temporary)
        classes, candidate, src = (root / name for name in ['classes', 'candidate', 'src'])
        for directory in [classes, candidate, src]: directory.mkdir()
        original = (fixtures / 'Widget.java').read_text()
        widget = src / 'Widget.java'; widget.write_text(original)
        app = src / 'SocketBindingApp.java'; app.write_text((fixtures / app.name).read_text())

        def compile(files, output):
            result = subprocess.run([javac, '-proc:none', '-g', '-parameters', '-cp', cp + os.pathsep + str(classes),
                                     '-d', str(output), *map(str, files)], capture_output=True, text=True, timeout=60)
            if result.returncode:
                # Never echo a native classpath or compiler message with local paths.
                raise AssertionError('Synthetic SDK fixture compilation failed')

        compile([widget, app], classes)
        lines, pending = [], queue.Queue()
        process = subprocess.Popen([java, '-javaagent:' + str(jar) + '=watchDirs=' + str(classes)
                                    + ',portFile=' + str(root / 'agent.port')
                                    + ',platform=generic,startupDelaySec=0,debounceMs=100',
                                    '-cp', cp + os.pathsep + str(classes), 'com.example.SocketBindingApp'],
                                   cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, bufsize=1)

        def read():
            for line in process.stdout:
                value = line.rstrip(); lines.append(value); pending.put(value)
            pending.put(None)

        reader = threading.Thread(target=read, daemon=True); reader.start()

        def until(predicate, timeout=45):
            end = time.monotonic() + timeout
            while time.monotonic() < end:
                try: line = pending.get(timeout=max(.1, end - time.monotonic()))
                except queue.Empty: break
                if line is None: raise AssertionError('Native probe JVM exited early')
                if predicate(line): return line
            raise AssertionError('Expected native probe event timed out')

        def command(value, prefix):
            process.stdin.write(value + '\n'); process.stdin.flush()
            return until(lambda line: line.startswith(prefix))

        try:
            ready = watching = False
            while not (ready and watching):
                line = until(lambda value: True)
                ready |= line.startswith('READY ')
                watching |= '] Watching ' in line
            assert command('duplicate', 'DUPLICATE ') == 'DUPLICATE before=1 after=2'
            receipts = []
            expected = [
                'RESULT visible=false physical=false old=none fresh=none held=not-created existing=existing:sample',
                'RESULT visible=true physical=false old=none fresh=added-1:sample held=not-created existing=existing:sample',
                'RESULT visible=true physical=false old=none fresh=added-2:sample held=added-2:sample existing=existing:sample',
                'RESULT visible=false physical=false old=none fresh=none held=ERROR:NoSuchMethodError existing=existing:sample',
                'RESULT visible=true physical=false old=none fresh=added-4:sample held=added-4:sample existing=existing:sample',
            ]
            receipts.append(command('probe', 'RESULT ')); assert receipts[-1] == expected[0], receipts[-1]
            for stage in [1, 2, 3, 4]:
                extra = '' if stage == 3 else '\n    @SocketEvent(socketId="example") public void input(String value){seen="added-' + str(stage) + ':"+value;}\n'
                widget.write_text(original.replace('\n}', extra + '\n}'))
                compile([widget], candidate)
                target = classes / 'com/example/Widget.class'
                data = (candidate / 'com/example/Widget.class').read_bytes()
                if not args.withhold_edit:
                    staged = target.with_suffix('.pending'); staged.write_bytes(data); staged.replace(target)
                    until(lambda line: 'Reloaded com.example.Widget' in line or 'Structural reload: com.example.Widget' in line)
                receipts.append(command('probe', 'RESULT '))
                assert receipts[-1] == expected[stage], receipts[-1]
            print(json.dumps({'status': 'PASS', 'observations': receipts, 'watchedSaves': 4,
                              'duplicateControl': '1 -> 2; whole-helper replay remains unsafe',
                              'agentSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
                              'scope': 'native default MVC binding/listener and detached real ZK components; not rendered UI/definition/connection acceptance',
                              'liveServerChanged': False}))
        finally:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=10)
            reader.join(timeout=5)
            process.stdin.close(); process.stdout.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('hybris_home', type=Path)
    parser.add_argument('--agent', type=Path, required=True)
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--withhold-edit', action='store_true', help='Negative control: acceptance must fail without the watched save')
    arguments = parser.parse_args()
    try: run(arguments)
    except Exception as failure:
        # Tracebacks can expose a private SDK path. Keep the public failure bounded.
        print('FAIL: ' + type(failure).__name__ + ': ' + str(failure).replace(str(arguments.hybris_home), '<SDK_ROOT>'))
        raise SystemExit(1)
