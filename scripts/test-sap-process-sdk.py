#!/usr/bin/env python3
"""Check registered process XML recognition against an installed SDK; no tenant startup."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('hybris_home', type=Path)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[1]
sdk = args.hybris_home.resolve()
main = repo / 'agent/build/classes/java/main'
if not (main / 'com/onurkat/reclazz/platform/SapProcessResources.class').is_file():
    parser.error('Run ./gradlew :agent:compileJava first')
if not (sdk / 'bin/platform/ext/processing/bin/processingserver.jar').is_file():
    parser.error('SDK processingserver.jar is missing')
classpath = os.pathsep.join(map(str, [main, *sorted((sdk / 'bin/platform').rglob('*.jar'))]))

def run(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=90)
    except subprocess.TimeoutExpired:
        raise SystemExit('SDK check exceeded the 90-second command limit') from None
    print((result.stdout + result.stderr).replace(str(sdk), '<SDK>'), end='')
    if result.returncode:
        raise SystemExit(result.returncode)

with tempfile.TemporaryDirectory(prefix='reclazz-process-sdk-') as temporary:
    out = Path(temporary)
    run(['javac', '-proc:none', '-cp', classpath, '-d', temporary,
         str(repo / 'integration-test/src/sapSdkTest/java/com/example/ProcessResourceSdkProbe.java')])
    logging = out / 'log4j2.xml'
    logging.write_text('<Configuration><Appenders/><Loggers><Root level="off"/></Loggers></Configuration>')
    run(['java', '-Dlog4j.configurationFactory=org.apache.logging.log4j.core.config.xml.XmlConfigurationFactory',
         '-Dlog4j.configurationFile=' + str(logging), '-cp', os.pathsep.join([temporary, classpath]),
         'com.example.ProcessResourceSdkProbe', temporary])
