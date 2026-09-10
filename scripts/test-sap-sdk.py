#!/usr/bin/env python3
"""Compile/run contract checks with the owner's installed SAP SDK. Never starts SAP or writes its tree."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('hybris_home', type=Path)
parser.add_argument('--disable-refresh', action='store_true', help='negative control: must fail')
args = parser.parse_args()
repo = Path(__file__).resolve().parents[1]
core = args.hybris_home / 'bin/platform/ext/core'
jars = [core / 'bin/coreserver.jar', *sorted((core / 'lib').glob('*.jar'))]
jars += sorted((args.hybris_home / 'bin/platform/lib').glob('*.jar'))
jars += sorted((args.hybris_home / 'bin/platform/bootstrap/bin').glob('*.jar'))
if not jars[0].is_file():
    parser.error('No platform/ext/core/bin/coreserver.jar in this installation')
classpath = os.pathsep.join(map(str, [repo / 'agent/build/classes/java/main', *jars]))
source = repo / 'integration-test/src/sapSdkTest/java/com/onurkat/reclazz/hybris/interceptor/InterceptorRegistrySdkProbe.java'
with tempfile.TemporaryDirectory(prefix='reclazz-sap-sdk-') as out:
    subprocess.run(['javac', '-proc:none', '-cp', classpath, '-d', out, str(source),
                    str(repo / 'reclazztest/src/com/onurkat/reclazztest/interceptors/ValidationProbe.java'),
                    str(repo / 'reclazztest/src/com/onurkat/reclazztest/interceptors/TestValidateInterceptor.java'),
                    str(repo / 'reclazztest/web/src/com/onurkat/reclazztest/controllers/SapVerificationController.java')], check=True, timeout=60)
    tenant = Path(out) / 'tenant'
    tenant.mkdir()
    subprocess.run(['javac', '-proc:none', '-cp', classpath, '-d', str(tenant),
                    str(repo / 'integration-test/src/sapSdkTest/tenantFixture/de/hybris/platform/core/Registry.java')],
                    check=True, timeout=60)
    logging_config = Path(out) / 'log4j2.xml'
    logging_config.write_text('<Configuration><Appenders/><Loggers><Root level="off"/></Loggers></Configuration>')
    subprocess.run(['java', '-Dlog4j.configurationFactory=org.apache.logging.log4j.core.config.xml.XmlConfigurationFactory',
                    '-Dlog4j.configurationFile=' + str(logging_config), '-Dprobe.tenantClasses=' + str(tenant), '-Dprobe.disableRefresh=' + str(args.disable_refresh).lower(),
                    '-cp', os.pathsep.join([out, classpath]),
                    'com.onurkat.reclazz.hybris.interceptor.InterceptorRegistrySdkProbe'], check=True, timeout=60)
