#!/usr/bin/env python3
"""Retained native Orika add/remove acceptance in a separate JVM; never starts or writes SAP."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request


def dto(stage):
    return ('package com.example; public class OrikaRollbackDto { private String code; '
            'public String getCode(){return code;} public void setCode(String v){code=v;} '
            + ('private String extra; public String getExtra(){return extra;} public void setExtra(String v){extra=v;} ' if stage else '')
            + ('private String other; public String getOther(){return other;} public void setOther(String v){other=v;} ' if stage == 2 else '')
            + 'public String describe(){return code' + ('+":"+extra' if stage else '')
            + ('+":"+other' if stage == 2 else '') + ';} }')


def run(args):
    repo = Path(__file__).resolve().parents[1]
    sdk = args.hybris_home.resolve()
    agent = args.agent.resolve()
    if not agent.is_file():
        raise AssertionError('Built agent jar missing')
    patterns = ['bin/platform/ext/core/lib/orika-core-*.jar', 'bin/platform/ext/core/lib/slf4j-api-*.jar',
                'bin/platform/ext/core/lib/paranamer-*.jar', 'bin/platform/ext/core/lib/guava-*.jar',
                'bin/platform/ext/validation/lib/javassist-*.jar', 'bin/platform/ext/core/lib/jackson-core-*.jar',
                'bin/platform/ext/core/lib/jackson-annotations-*.jar', 'bin/platform/ext/core/lib/jackson-databind-*.jar',
                'bin/modules/search-and-navigation/solrserver/resources/solr/*/server/modules/sql/lib/janino-*.jar',
                'bin/modules/search-and-navigation/solrserver/resources/solr/*/server/modules/sql/lib/commons-compiler-*.jar']
    jars = []
    for pattern in patterns:
        found = list(sdk.glob(pattern))
        if len(found) != 1:
            raise AssertionError('Expected exactly one installed SDK jar for ' + pattern)
        jars.extend(found)
    # Default Orika uses Javassist. No native SAP logging binding or platform bootstrap is loaded.
    classpath = os.pathsep.join(map(str, jars))
    fixture = repo / 'integration-test/src/sapSdkTest/orika/com/example/OrikaRollbackApp.java'
    with tempfile.TemporaryDirectory(prefix='reclazz-orika-sdk-') as directory:
        root = Path(directory); classes = root / 'classes'; classes.mkdir()
        source = root / 'OrikaRollbackDto.java'; source.write_text(dto(0))
        subprocess.run(['javac','-proc:none','-cp',classpath,'-d',str(classes),str(source),str(fixture)],check=True)
        initial = (classes/'com/example/OrikaRollbackDto.class').read_bytes()
        lines = []; ready = threading.Event(); ports = []
        process = subprocess.Popen(['java','--add-opens=java.base/java.lang=ALL-UNNAMED',
            f'-javaagent:{agent}=platform=generic,watchDirs={classes},portFile={root / "agent.port"},startupDelaySec=1,debounceMs=100',
            '-cp',str(classes)+os.pathsep+classpath,'com.example.OrikaRollbackApp'],
            cwd=root,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
        def read():
            for line in process.stdout:
                lines.append(line.rstrip())
                if line.startswith('ORIKA_PORT='): ports.append(int(line.split('=',1)[1]))
                if '] Watching 1 director' in line: ready.set()
        threading.Thread(target=read,daemon=True).start()
        def response():
            try:
                with urllib.request.urlopen('http://127.0.0.1:'+str(ports[0])+'/',timeout=8) as result:
                    return result.status,result.read().decode()
            except urllib.error.HTTPError as error:
                return error.code,error.read().decode()
        def expected(value):
            actual = response()
            if actual != (200,value): raise AssertionError('Native mapping mismatch: '+repr(actual)+' expected '+value)
        try:
            if not ready.wait(60) or not ports: raise AssertionError('Synthetic watcher/HTTP did not start')
            expected('code'); checks = 1
            for stage in [1,2,1,0,1,0]:
                source.write_text(dto(stage)); compiled=root/'compiled'
                subprocess.run(['javac','-proc:none','-cp',classpath,'-d',str(compiled),str(source)],check=True)
                mark=len(lines); shutil.copy2(compiled/'com/example/OrikaRollbackDto.class',classes/'com/example/OrikaRollbackDto.class')
                deadline=time.monotonic()+40
                while not any(('Reloaded com.example.OrikaRollbackDto (' in line or 'Structural reload: com.example.OrikaRollbackDto (' in line) for line in lines[mark:]):
                    if time.monotonic()>deadline or process.poll() is not None: raise AssertionError('Missing exact owner reload')
                    time.sleep(.02)
                value = 'code'+(':v2' if stage else '')+(':other' if stage==2 else '')
                expected(value); expected(value); checks += 2
            if (classes/'com/example/OrikaRollbackDto.class').read_bytes() != initial:
                raise AssertionError('Original synthetic class bytes not restored')
            print(json.dumps({'checks':checks,'failures':0,'sdkJars':[p.name for p in jars],
                              'sequence':'baseline, add, partial add, partial remove, final remove, re-add, remove',
                              'originalClassBytesRestored':True}))
        except BaseException:
            print('\n'.join(lines[-100:])); raise
        finally:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill();process.wait(timeout=5)


if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('hybris_home',type=Path)
    parser.add_argument('--agent',type=Path,default=Path(__file__).resolve().parents[1]/'agent/build/libs/agent-1.5.0.jar')
    run(parser.parse_args())
