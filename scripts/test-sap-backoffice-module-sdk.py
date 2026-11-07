#!/usr/bin/env python3
"""Prepare/reload a synthetic module through the actual SDK WidgetClassLoader; no tenant or SDK writes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def run(args):
    repo = Path(__file__).resolve().parents[1]
    sdk, agent = args.hybris_home.resolve(), args.agent.resolve()
    binaries = args.java_home / 'bin' if args.java_home else None
    java, javac = (str(binaries / name) if binaries else name for name in ('java', 'javac'))
    libraries = sdk / 'bin/modules/backoffice-framework/backoffice/web/webroot/WEB-INF/lib'
    if not agent.is_file() or not list(libraries.glob('cockpitframework-*.jar')):
        raise AssertionError('Built agent and SDK Backoffice libraries required')
    jars = sorted(libraries.glob('*.jar')) + sorted((sdk / 'bin/platform/tomcat/lib').glob('*.jar'))
    for pattern in ('commons-lang3-*.jar', 'commons-io-*.jar', 'slf4j-api-*.jar'):
        jars += sorted((sdk / 'bin/platform').rglob(pattern))
    cp = os.pathsep.join(map(str, [agent] + jars))
    with tempfile.TemporaryDirectory(prefix='reclazz-module-sdk-') as temporary:
        root = Path(temporary)
        for label in ('deployed', 'empty-watch'): (root / label).mkdir()
        source = root / 'ModuleController.java'
        shapes = {
            'baseline': 'public static String marker(){return "one";}',
            'body': 'public static String marker(){return "two";}',
            'added': 'public static String marker(){return added();} public static String added(){return "new";}',
        }
        def compile(files, output, classpath):
            result = subprocess.run([javac, '-proc:none', '-g', '-parameters', '-cp', classpath,
                                     '-d', str(output), *map(str, files)], capture_output=True, text=True, timeout=60)
            if result.returncode: raise AssertionError('Synthetic module fixture compilation failed')
        for label, body in shapes.items():
            source.write_text('package com.example; public class ModuleController {' + body + '}')
            compile([source], root / label, cp)
        with zipfile.ZipFile(root / 'deployed/example.jar', 'w') as jar:
            jar.write(root / 'baseline/com/example/ModuleController.class', 'com/example/ModuleController.class')
        app = repo / 'integration-test/src/sapSdkTest/backoffice-module/com/example/NativeModuleApp.java'
        compile([app], root / 'driver', cp)
        result = subprocess.run([java, '-javaagent:' + str(agent) + '=platform=generic,watchDirs='
                                 + str(root / 'empty-watch') + ',portFile=' + str(root / 'agent.port')
                                 + ',startupDelaySec=0', '-cp', cp + os.pathsep + str(root / 'driver'),
                                 'com.example.NativeModuleApp', str(root), str(sdk)],
                                cwd=root, capture_output=True, text=True, timeout=60)
        observations = [line for line in result.stdout.splitlines()
                        if line.startswith(('ROOTS ', 'AUTOCOMPILE ', 'NATIVE_LOADER ', 'BODY ', 'ADDED ', 'ADDED_CALL '))]
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.with_suffix('.log').write_text((result.stdout + result.stderr)
                .replace(str(sdk), '<SDK_ROOT>').replace(temporary, '<SCRATCH>'))
        if result.returncode or len(observations) != 6:
            raise AssertionError('Native module probe failed; inspect sanitized output log')
        receipt = {'status': 'PASS', 'observations': observations,
                   'agentSha256': hashlib.sha256(agent.read_bytes()).hexdigest(),
                   'liveServerChanged': False, 'sdkWrites': False,
                   'scope': 'Actual SDK loader and real agent transformation/redefinition, synthetic Java module; no native tenant or rendered UI'}
        if args.output: args.output.write_text(json.dumps(receipt, indent=2) + '\n')
        print(json.dumps(receipt))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('hybris_home', type=Path)
    parser.add_argument('--agent', type=Path, required=True)
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--output', type=Path)
    arguments = parser.parse_args()
    try: run(arguments)
    except Exception as failure:
        print('FAIL: ' + type(failure).__name__ + ': ' + str(failure).replace(str(arguments.hybris_home), '<SDK_ROOT>').replace(str(arguments.hybris_home.resolve()), '<SDK_ROOT>'))
        raise SystemExit(1)
