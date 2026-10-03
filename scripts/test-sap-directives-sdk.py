#!/usr/bin/env python3
"""Run synthetic SAP list directive acceptance using an installed SDK; never starts or writes SAP."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('hybris_home', type=Path)
parser.add_argument('--disable-guard', action='store_true', help='negative control: restart assertions must fail')
args = parser.parse_args()
repo = Path(__file__).resolve().parents[1]
sdk = args.hybris_home.resolve()
classes = sdk / 'bin/platform/ext/platformservices/classes'
if not (classes / 'de/hybris/platform/converters/impl/ModifyPopulatorList.class').is_file():
    parser.error('SDK compiled platformservices classes are missing')
jars = sorted((sdk / 'bin/platform').rglob('*.jar'))
main = repo / 'agent/build/classes/java/main'
if not (main / 'com/onurkat/reclazz/spring/xml/SpringXmlReloader.class').is_file():
    parser.error('Run ./gradlew :agent:compileJava first')
classpath = os.pathsep.join(map(str, [main, classes, *jars]))
source = repo / 'integration-test/src/sapSdkTest/java/com/onurkat/reclazz/spring/xml/ListDirectiveSdkProbe.java'

def run(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=90)
    except subprocess.TimeoutExpired:
        raise SystemExit("SDK check exceeded the 90-second command limit") from None
    print((result.stdout + result.stderr).replace(str(sdk), '<SDK>'), end='')
    if result.returncode:
        raise SystemExit(result.returncode)

with tempfile.TemporaryDirectory(prefix='reclazz-directives-sdk-') as temporary:
    out = Path(temporary)
    sources = [str(source)]
    if args.disable_guard:
        production = repo / 'agent/src/main/java/com/onurkat/reclazz/spring/xml/XmlSafetyClassifier.java'
        text = production.read_text()
        hook = 'SapListDirectiveGuard.classify(liveFactory, beanName, existing, newBd, out)'
        if text.count(hook) != 1:
            parser.error('Expected exactly one directive guard for negative control')
        mutation = out / 'XmlSafetyClassifier.java'
        mutation.write_text(text.replace(hook, 'false'))
        sources.append(str(mutation))
    run(['javac', '-proc:none', '-cp', classpath, '-d', temporary, *sources])
    logging = out / 'log4j2.xml'
    logging.write_text('<Configuration><Appenders/><Loggers><Root level="off"/></Loggers></Configuration>')
    run(['java',
         '-Dlog4j.configurationFactory=org.apache.logging.log4j.core.config.xml.XmlConfigurationFactory',
         '-Dlog4j.configurationFile=' + str(logging), '-cp', os.pathsep.join([temporary, classpath]),
         'com.onurkat.reclazz.spring.xml.ListDirectiveSdkProbe', str(out / 'fixture')])
