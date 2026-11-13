#!/usr/bin/env python3
"""Opt-in actual SDK XML new-listener regression; no live tenant or install mutation."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def run(options):
    repo = Path(__file__).resolve().parents[1]
    sdk = options.hybris_home.resolve()
    agent = options.agent.resolve()
    if not agent.is_file():
        raise AssertionError('Built agent jar missing; pass --agent')
    jars = sorted((sdk / 'bin/platform').rglob('*.jar'))
    if len(list((sdk / 'bin/platform').rglob('spring-core-*.jar'))) != 1:
        raise AssertionError('Expected one installed SDK Spring graph')
    classpath = os.pathsep.join([str(agent), *map(str, jars)])
    fixtures = repo / 'integration-test/src/sapSdkTest/events/com/example'
    with tempfile.TemporaryDirectory(prefix='reclazz-sap-xml-events-') as temp:
        root = Path(temp)
        logging = root / 'log4j2.xml'
        logging.write_text('<Configuration><Appenders/><Loggers><Root level="off"/></Loggers></Configuration>')
        commands = [
            ['javac', '-proc:none', '-cp', classpath, '-d', str(root),
             str(fixtures / 'XmlListenerApp.java'), str(fixtures / 'ProbeEvent.java')],
            ['java', '-Dlog4j.configurationFactory=org.apache.logging.log4j.core.config.xml.XmlConfigurationFactory',
             '-Dlog4j.configurationFile=' + str(logging), '-cp', str(root) + os.pathsep + classpath,
             'com.example.XmlListenerApp', str(root / 'probe-spring.xml')]]
        for command in commands:
            result = subprocess.run(command, capture_output=True, text=True, timeout=90)
            print((result.stdout + result.stderr).replace(str(sdk), '<SDK>'), end='')
            if result.returncode:
                raise SystemExit(result.returncode)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('hybris_home', type=Path)
    parser.add_argument('--agent', type=Path, required=True)
    run(parser.parse_args())
