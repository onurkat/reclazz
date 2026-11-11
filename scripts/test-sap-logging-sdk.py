#!/usr/bin/env python3
"""Exercise context ownership with installed Log4j2; no SAP server or tree writes."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('hybris_home', type=Path)
parser.add_argument('--reloader-source', type=Path, help='compile a preserved pre-fix source for the RED control')
args = parser.parse_args()
repo = Path(__file__).resolve().parents[1]
lib = args.hybris_home / 'bin/platform/ext/core/lib'
jars = sorted(lib.glob('log4j-api-*.jar')) + sorted(lib.glob('log4j-core-*.jar'))
if len(jars) != 2:
    parser.error('Expected exactly one installed Log4j API and core jar')
classpath = os.pathsep.join(map(str, jars + sorted(lib.glob('log4j-1.2-api-*.jar')) + sorted(lib.glob('guava-*.jar')) + sorted(lib.glob('slf4j-api-*.jar')) + [args.hybris_home / 'bin/platform/ext/core/bin/coreserver.jar']))
source = args.reloader_source or repo / 'agent/src/main/java/com/onurkat/reclazz/reload/LoggingReloader.java'
with tempfile.TemporaryDirectory(prefix='reclazz-logging-sdk-') as directory:
    out = Path(directory)
    subprocess.run(['javac', '-proc:none', '-cp', classpath, '-d', str(out), str(source),
                    str(repo / 'agent/src/main/java/com/onurkat/reclazz/ui/StatusReporter.java'),
                    str(repo / 'agent/src/main/java/com/onurkat/reclazz/ui/MessageWrap.java'),
                    str(repo / 'agent/src/main/java/com/onurkat/reclazz/ui/RestartLedger.java'),
                    str(repo / 'integration-test/src/sapSdkTest/java/com/onurkat/reclazz/reload/LoggingContextSdkProbe.java')],
                   check=True, timeout=60)
    config = out / 'log4j2.xml'
    config.write_text('<Configuration><Appenders/><Loggers><Root level="off"/></Loggers></Configuration>')
    for mode in [[], ['unused-default'], ['default-owner'], ['isolated-default'], ['source-group']]:
        subprocess.run(['java', '-Dlog4j.configurationFactory=org.apache.logging.log4j.core.config.xml.XmlConfigurationFactory',
                        '-Dlog4j.configurationFile=' + str(config), '-cp', os.pathsep.join([str(out), classpath]),
                        'com.onurkat.reclazz.reload.LoggingContextSdkProbe', *mode], check=True, timeout=60)
