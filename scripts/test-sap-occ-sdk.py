#!/usr/bin/env python3
"""Run synthetic OCC mapping acceptance using an installed SDK; never starts or writes SAP."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('hybris_home', type=Path)
parser.add_argument('--disable-refresh', action='store_true', help='negative control: mapping assertions must fail')
args = parser.parse_args()
repo = Path(__file__).resolve().parents[1]
sdk = args.hybris_home.resolve()
web = sdk / 'bin/modules/web-services-commons/webservicescommons'
if not (web / 'bin/webservicescommonsserver.jar').is_file():
    parser.error('SDK webservicescommonsserver.jar is missing')
jars = sorted((sdk / 'bin/platform').rglob('*.jar')) + sorted(web.rglob('*.jar'))
main = repo / 'agent/build/classes/java/main'
if not (main / 'com/onurkat/reclazz/spring/xml/SpringXmlReloader.class').is_file():
    parser.error('Run ./gradlew :agent:compileJava first')
classpath = os.pathsep.join(map(str, [main, *jars]))
source = repo / 'integration-test/src/sapSdkTest/java/com/onurkat/reclazz/spring/xml/OccFieldMappingSdkProbe.java'

def run(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=90)
    except subprocess.TimeoutExpired:
        raise SystemExit("SDK check exceeded the 90-second command limit") from None
    print((result.stdout + result.stderr).replace(str(sdk), '<SDK>'), end='')
    if result.returncode:
        raise SystemExit(result.returncode)

with tempfile.TemporaryDirectory(prefix='reclazz-occ-sdk-') as temporary:
    out = Path(temporary)
    sources = [str(source)]
    if args.disable_refresh:
        production = repo / 'agent/src/main/java/com/onurkat/reclazz/spring/xml/SpringXmlReloader.java'
        text = production.read_text()
        hook = 'SapFieldSetRefresher.refresh(appContext, changedMappings)'
        if text.count(hook) != 1:
            parser.error('Expected exactly one refresh hook for negative control')
        mutation = out / 'SpringXmlReloader.java'
        mutation.write_text(text.replace(hook, 'java.util.List.<String>of()'))
        sources.append(str(mutation))
    run(['javac', '-proc:none', '-cp', classpath, '-d', temporary, *sources])
    logging = out / 'log4j2.xml'
    logging.write_text('<Configuration><Appenders/><Loggers><Root level="off"/></Loggers></Configuration>')
    run(['java', '--add-opens=java.base/java.lang=ALL-UNNAMED',
         '-Dlog4j.configurationFactory=org.apache.logging.log4j.core.config.xml.XmlConfigurationFactory',
         '-Dlog4j.configurationFile=' + str(logging), '-cp', os.pathsep.join([temporary, classpath]),
         'com.onurkat.reclazz.spring.xml.OccFieldMappingSdkProbe', str(out / 'fixture')])
