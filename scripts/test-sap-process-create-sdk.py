#!/usr/bin/env python3
"""Exercise the new-process registration hook and native startup recorder on an installed SDK, without a tenant."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('hybris_home', type=Path)
parser.add_argument('--java-home', required=True, type=Path)
parser.add_argument('--reloader-classes', type=Path, help='Use a scratch reloader build for a causal control')
args = parser.parse_args()
repo = Path(__file__).resolve().parents[1]
sdk = args.hybris_home.resolve()
platform = sdk / 'bin/platform'
main = repo / 'agent/build/classes/java/main'
premium = repo / 'agent/build/classes/java/premium'
for required in [main / 'com/onurkat/reclazz/transform/SapProcessDefinitionTransformer.class',
                 premium / 'com/onurkat/reclazz/hybris/SapProcessDefinitionReloader.class']:
    if not required.is_file():
        parser.error('Run ./gradlew :agent:compileJava :agent:compilePremiumJava first')
asm_root = Path.home() / '.gradle/caches/modules-2/files-2.1/org.ow2.asm'
asm = sorted(asm_root.glob('*/9.10.1/*/*.jar'))
if not asm:
    parser.error('Existing ASM 9.10.1 build dependencies are missing')
resources = sorted({p.parent.parent for p in platform.rglob('processdefinition.xsd')})
overrides = [args.reloader_classes.resolve()] if args.reloader_classes else []
if overrides and not (overrides[0] / 'com/onurkat/reclazz/hybris/SapProcessDefinitionReloader.class').is_file():
    parser.error('Scratch reloader classes are missing')
classpath = os.pathsep.join(map(str, [main, *overrides, premium, *asm, *resources, *sorted(platform.rglob('*.jar'))]))

def run(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=90)
    except subprocess.TimeoutExpired:
        raise SystemExit('SDK reload check exceeded 90 seconds') from None
    print((result.stdout + result.stderr).replace(str(sdk), '<SDK>'), end='', flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)

with tempfile.TemporaryDirectory(prefix='reclazz-process-reload-sdk-') as temporary:
    out = Path(temporary)
    sources = repo / 'integration-test/src/sapSdkTest/java/com/example'
    run([str(args.java_home / 'bin/javac'), '-proc:none', '-cp', classpath, '-d', temporary,
         str(repo / 'agent/src/test/java/com/onurkat/reclazz/hybris/ProcessTenantFixture.java'),
         str(repo / 'agent/src/processTestFixtures/java/de/hybris/platform/core/Registry.java'),
         str(repo / 'agent/src/processTestFixtures/java/de/hybris/platform/jalo/JaloSession.java'),
         str(sources / 'ProcessObserverAgent.java'), str(sources / 'ProcessCreateSdkAgent.java'), str(sources / 'ProcessCreateSdkProbe.java')])
    observer = out / 'observer.jar'
    with zipfile.ZipFile(observer, 'w') as jar:
        jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nPremain-Class: com.example.ProcessCreateSdkAgent\n\n')
        for file in (out / 'com/example').glob('*Agent*.class'):
            jar.write(file, str(file.relative_to(out)))
    logging = out / 'log4j2.xml'
    logging.write_text('<Configuration><Appenders/><Loggers><Root level="off"/></Loggers></Configuration>')
    for mode in ['observed']:
        run([str(args.java_home / 'bin/java'), '-javaagent:' + str(observer) + '=' + mode,
             '-Dlog4j.configurationFactory=org.apache.logging.log4j.core.config.xml.XmlConfigurationFactory',
             '-Dlog4j.configurationFile=' + str(logging), '-cp', temporary + os.pathsep + classpath,
             'com.example.ProcessCreateSdkProbe', str(out / mode), mode])
