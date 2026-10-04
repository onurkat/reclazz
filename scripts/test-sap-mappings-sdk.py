#!/usr/bin/env python3
"""Check OCC request selection using an installed SDK and synthetic controllers."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('hybris_home', type=Path)
parser.add_argument('--disable-refresh', action='store_true', help='negative control: selection assertions must fail')
args = parser.parse_args()
repo = Path(__file__).resolve().parents[1]
sdk = args.hybris_home.resolve()
web = sdk / 'bin/modules/commerce-services/commercewebservices/web/webroot/WEB-INF/classes'
services = sdk / 'bin/modules/commerce-services/commerceservices/classes'
if not (web / 'de/hybris/platform/commercewebservices/core/request/mapping/handler/CommerceHandlerMapping.class').is_file():
    parser.error('SDK compiled CommerceHandlerMapping is missing')
main = repo / 'agent/build/classes/java/main'
if not (main / 'com/onurkat/reclazz/spring/SapCommerceMappings.class').is_file():
    parser.error('Run ./gradlew :agent:compileJava first')
# Match agent/build.gradle.kts. SDK ASM versions may not support the probe JDK.
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
asm = sorted((cache / 'caches/modules-2/files-2.1/org.ow2.asm').glob('*/9.10.1/*/*.jar'))
if not asm:
    parser.error('Project ASM 9.10.1 cache is missing; compile the agent first')
classpath = os.pathsep.join(map(str, [main, web, services, *asm, *sorted((sdk / 'bin/platform').rglob('*.jar'))]))

def run(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=90)
    except subprocess.TimeoutExpired:
        raise SystemExit('SDK check exceeded the 90-second command limit') from None
    print((result.stdout + result.stderr).replace(str(sdk), '<SDK>'), end='')
    if result.returncode:
        raise SystemExit(result.returncode)

with tempfile.TemporaryDirectory(prefix='reclazz-occ-mappings-sdk-') as temporary:
    out = Path(temporary)
    src = out / 'src/com/example'
    src.mkdir(parents=True)
    imports = ('package com.example; import org.springframework.web.bind.annotation.*;'
               'import de.hybris.platform.commerceservices.request.mapping.annotation.RequestMappingOverride;'
               'import de.hybris.platform.commerceservices.request.mapping.annotation.ApiVersion;')

    def controller(name, annotation, api='v2', extra=''):
        (src / (name + '.java')).write_text(imports + '@RestController @ApiVersion("' + api + '") public class '
            + name + '{' + annotation + ' public String item(){return "' + name + '";}' + extra + '}')

    controller('Base', '@GetMapping("/item")')
    controller('Low', '@GetMapping("/item") @RequestMappingOverride(priorityProperty="10")')
    controller('Other', '@GetMapping("/other")')
    for stage, annotation in [
            ('initial', '@GetMapping("/item") @RequestMappingOverride(priorityProperty="20")'),
            ('other', '@GetMapping("/candidate")'), ('removed', ''),
            ('moved', '@GetMapping("/moved") @RequestMappingOverride(priorityProperty="20")'),
            ('priority', '@GetMapping("/item") @RequestMappingOverride(priorityProperty="5")'),
            ('noOverride', '@GetMapping("/item")'),
            ('apiVersion', '@GetMapping("/item") @RequestMappingOverride(priorityProperty="20")'),
            ('tie', '@GetMapping("/item") @RequestMappingOverride(priorityProperty="10")')]:
        controller('High', annotation, 'v1' if stage == 'apiVersion' else 'v2')
        run(['javac', '-proc:none', '-cp', classpath, '-d', str(out / stage), *map(str, src.glob('*.java'))])
    controller('Low', '')
    run(['javac', '-proc:none', '-cp', classpath, '-d', str(out / 'removed'), str(src / 'Low.java')])
    controller('High', '@GetMapping("/moved") @RequestMappingOverride(priorityProperty="20")',
               extra='@GetMapping("/added") public String added(){return "added";}')
    run(['javac', '-proc:none', '-cp', classpath, '-d', str(out / 'addedMethod'), str(src / 'High.java')])

    sources = [str(repo / 'integration-test/src/sapSdkTest/java/com/onurkat/reclazz/spring/OccRequestMappingSdkProbe.java')]
    if args.disable_refresh:
        production = repo / 'agent/src/main/java/com/onurkat/reclazz/spring/SpringMvcReloader.java'
        hook = ('        if (SapCommerceMappings.supports(handlerMapping)) {\n'
                '            return SapCommerceMappings.rescan(handlerMapping, controllerClass);\n        }')
        source = production.read_text()
        if source.count(hook) != 1:
            parser.error('Expected exactly one SDK rescan hook for negative control')
        mutation = out / 'SpringMvcReloader.java'
        mutation.write_text(source.replace(hook, ''))
        sources.append(str(mutation))
    run(['javac', '-proc:none', '-cp', classpath, '-d', temporary, *sources])
    manifest = out / 'MANIFEST.MF'
    probe = 'com.onurkat.reclazz.spring.OccRequestMappingSdkProbe'
    manifest.write_text('Manifest-Version: 1.0\nPremain-Class: ' + probe + '\nCan-Redefine-Classes: true\n\n')
    run(['jar', 'cfm', str(out / 'probe.jar'), str(manifest), '-C', temporary, 'com'])
    logging = out / 'log4j2.xml'
    logging.write_text('<Configuration><Appenders/><Loggers><Root level="off"/></Loggers></Configuration>')
    run(['java', '-javaagent:' + str(out / 'probe.jar'),
         '-Dlog4j.configurationFactory=org.apache.logging.log4j.core.config.xml.XmlConfigurationFactory',
         '-Dlog4j.configurationFile=' + str(logging), '-cp', os.pathsep.join([temporary, str(out / 'initial'), classpath]),
         probe, temporary])
