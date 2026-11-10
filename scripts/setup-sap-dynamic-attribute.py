#!/usr/bin/env python3
"""One-time, stopped-snapshot setup for the normal synthetic dynamic attribute.

No reload claim is made here. Successful setup keeps the new metadata; every
failed attempt restores the complete old DB/build/fixture baseline before boot.
"""
from pathlib import Path
from contextlib import contextmanager
import argparse
import fcntl
import importlib.util
import json
import os
import re
import shutil
import subprocess
import tempfile
from sap_items_snapshot import capture, inventory, verify, restore

spec = importlib.util.spec_from_file_location('dynamic_cms_support', Path(__file__).with_name('test-sap-cms-live.py'))
cms = importlib.util.module_from_spec(spec); spec.loader.exec_module(cms)
support, solr = cms.support, cms.solr
REPO, HOME, PLATFORM = cms.REPO, cms.SAP_HOME, cms.PLATFORM
LEASE = Path('/private/tmp/reclazz-dynamic-setup.lease')
TEMPLATES = REPO / 'integration-test/src/main/resources/templates/batch'
OWNED = ('resources/reclazztest-items.xml', 'resources/reclazztest-spring.xml',
         'src/com/onurkat/reclazztest/batch/DynamicLabelHandler.java',
         'web/src/com/onurkat/reclazztest/controllers/DynamicProbeController.java')
UPDATE_PROPERTIES = (b'\nupdate.importEssentialData.enabled=false\nupdate.clearHmcConfiguration.enabled=false\n'
                     b'update.localizeTypes.enabled=false\nupdate.rebuildLucenesearchIndexes.enabled=false\n'
                     b'update.executeProjectData.extensionName.list=\n')


def targets():
    return {'config': HOME / 'config', 'data': HOME / 'data',
            'bootstrap': PLATFORM / 'bootstrap', 'tomcat': PLATFORM / 'tomcat',
            'native-temp': HOME / 'temp/hybris', 'fixture': REPO / 'reclazztest',
            'backoffice-fixture': REPO / 'reclazztestbackoffice'}


def check_roots(roots, allow_missing=False):
    paths = list(roots.values())
    for path in paths:
        if path.resolve() != path or (not path.is_dir() and not (allow_missing and not path.exists())):
            raise RuntimeError('Foreign/missing physical snapshot root')
    if any(a == b or a in b.parents or b in a.parents
           for i, a in enumerate(paths) for b in paths[i+1:]):
        raise RuntimeError('Overlapping snapshot roots')


def facts():
    return cms.native_read('''import groovy.json.JsonOutput
import de.hybris.platform.core.Registry
import de.hybris.platform.jalo.type.TypeManager
def ctx=Registry.applicationContext
def ts=ctx.getBean('typeService');def t=ts.getComposedTypeForCode('ReclazzSchemaProbe')
def present=ts.hasAttribute(t,'dynamicLabel');def result=[present:present]
if(present) {
 def d=TypeManager.instance.getComposedType('ReclazzSchemaProbe').getAttributeDescriptor('dynamicLabel')
 result.handler=d.attributeHandler;result.property=d.isProperty()
 result.read=d.isReadable();result.write=d.isWritable()
}
return JsonOutput.toJson(result)''')


def validate_facts(value, present):
    if value.get('present') is not present:
        raise RuntimeError('Dynamic descriptor presence mismatch')
    if present and (value.get('handler') != 'reclazzDynamicLabelHandler' or
                    value.get('property') is not False or value.get('read') is not True or value.get('write') is not True):
        raise RuntimeError('Native dynamic handler/descriptor mismatch')


def check_sources(hashes):
    """Permit the old migration source or the reviewed, already checked-out source.

    Never overwrite a same-count local edit. The latter mode makes setup usable
    after this change is committed, before an older DB has the new descriptor.
    """
    if len(hashes) not in (72, 74):
        raise RuntimeError('Expected old72 or reviewed74 fixture membership')
    staged = len(hashes) == 74
    owned = {'reclazztest/' + relative for relative in OWNED}
    for relative in OWNED[2:]:
        if (REPO / 'reclazztest' / relative).exists() != staged:
            raise RuntimeError('Partial dynamic source provisioning')
    for name in hashes:
        path = REPO / name
        if staged and name in owned:
            expected = (TEMPLATES / path.relative_to(REPO / 'reclazztest')).read_bytes()
        else:
            result = subprocess.run(['git', '--no-optional-locks', 'show', 'HEAD:' + name],
                                    cwd=REPO, capture_output=True, timeout=15, check=True)
            expected = result.stdout
        if path.read_bytes() != expected:
            raise RuntimeError('Unowned fixture change: ' + name)
    if not staged:
        for relative in OWNED[:2]:
            data = (REPO / 'reclazztest' / relative).read_bytes()
            if b'dynamicLabel' in data or b'DynamicLabelHandler' in data:
                raise RuntimeError('Partial dynamic metadata provisioning')


def preflight():
    cms.require_clean_home(); solr.no_inner_writer()
    if support.preflight() is not None:
        raise RuntimeError('Normal items preflight unavailable')
    solr.require_options(support.get(support.ENDPOINT + '/gates'), False)
    before = support.doctor()
    if before.get('watcherState') != 'watching' or before.get('buildHold') != 'none' or support.java_writers() != [str(before['pid'])]:
        raise RuntimeError('Native writer/agent busy')
    validate_facts(facts(), False)
    hashes = support.fixture_hashes()
    check_sources(hashes)
    for relative in OWNED:
        if not (TEMPLATES / relative).is_file():
            raise RuntimeError('Missing reviewed setup template')
    check_roots(targets())
    return {'doctor': {k: before[k] for k in ('pid', 'sessionId')}, 'fixtureHashes': hashes,
            'agentSha256': support.digest(support.JAR)}


@contextmanager
def lease():
    fd = os.open(LEASE, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'r+') as handle:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if handle.read().strip():
            raise RuntimeError('Unfinished dynamic setup owns the durable lease')
        yield handle


def set_lease(handle, folder):
    handle.seek(0); handle.truncate(); handle.write(str(folder) if folder else '')
    handle.flush(); os.fsync(handle.fileno())


def save(folder, state):
    pending = folder / 'state.pending'; pending.write_text(json.dumps(state, indent=2) + '\n')
    pending.replace(folder / 'state.json')
    if json.loads((folder / 'state.json').read_text()) != state:
        raise RuntimeError('Durable setup state read-back failed')


def stopped():
    solr.no_inner_writer(); solr.stopped()


def restore_trees(folder, roots):
    check_roots(roots, allow_missing=True); stopped()
    # Corruption of the LAST snapshot must refuse before the FIRST deletion.
    manifests = {name: verify(folder / name) for name in roots}
    for name, target in roots.items():
        restore(folder / name, target, stopped)
    if any(inventory(target) != manifests[name] for name, target in roots.items()):
        raise RuntimeError('Complete setup snapshot restoration mismatch')


def native(folder, label, *args):
    cms.native(folder, label, *args)


def generated_api(folder):
    relative = 'de/hybris/platform/core/model/ReclazzSchemaProbeModel.java'
    source = PLATFORM / 'bootstrap/gensrc' / relative
    data = source.read_text()
    if not re.search(r'getDynamicValue\(this,\s*DYNAMICLABEL\)', data) or not re.search(r'setDynamicValue\(this,\s*DYNAMICLABEL,\s*value\)', data):
        raise RuntimeError('Generated native dynamic accessor bodies missing')
    jar = PLATFORM / 'bootstrap/bin/models.jar'
    command = [str(support.JAVA_HOME / 'bin/javap'), '-classpath', str(jar),
               'de.hybris.platform.core.model.ReclazzSchemaProbeModel']
    result = subprocess.run(command, capture_output=True, text=True, timeout=30, check=True)
    for signature in ('public java.lang.String getDynamicLabel();', 'public void setDynamicLabel(java.lang.String);'):
        if signature not in result.stdout:
            raise RuntimeError('Generated class signature mismatch')
    (folder / 'generated-api.txt').write_text(result.stdout)
    return {'sourceSha256': support.digest(source), 'modelsJarSha256': support.digest(jar),
            'command': command, 'signatures': ['String getDynamicLabel()', 'void setDynamicLabel(String)']}


def provision(folder, inject):
    for relative in OWNED[:3]:
        path = REPO / 'reclazztest' / relative
        path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes((TEMPLATES / relative).read_bytes())
    native(folder, 'setup-build', 'build', '-Dbuild.parallel=false')
    generated_api(folder)  # Verify native signatures BEFORE deploying their typed consumer.
    controller = REPO / 'reclazztest' / OWNED[3]
    controller.write_bytes((TEMPLATES / OWNED[3]).read_bytes())
    native(folder, 'controller-build', 'build', '-Dbuild.parallel=false')
    api = generated_api(folder)
    config = folder / 'schema-only.json'; config.write_text(json.dumps(cms.schema_configuration()) + '\n')
    props = HOME / 'config/local.properties'; original = props.read_bytes()
    wrapper = PLATFORM / 'tomcat/conf/wrapper.conf'
    args = lambda data: re.findall(rb'(?m)^wrapper\.java\.additional\.\d+=(-javaagent:\S+)', data)
    before_args = args(wrapper.read_bytes())
    props.write_bytes(original + UPDATE_PROPERTIES)
    native(folder, 'schema-only', *cms.schema_arguments(config))
    stopped()
    if props.read_bytes() != original + UPDATE_PROPERTIES:
        raise RuntimeError('Unowned local properties change')
    props.write_bytes(original)
    if args(wrapper.read_bytes()) != before_args:
        raise RuntimeError('Schema deployment changed agent launcher')
    if inject:
        raise RuntimeError('Injected dynamic setup failure after schema update')
    return api


def attempt(folder, before, inject=False):
    roots = targets(); check_roots(roots)
    state = {**before, 'schema': 1, 'home': str(HOME), 'targets': {k: str(v) for k, v in roots.items()},
             'phase': 'stopping', 'captured': False, 'injected': inject}
    save(folder, state)
    failure = None
    try:
        solr.stop_all(folder, 'capture', False); stopped()
        sizes = {k: sum(x['size'] for x in inventory(v).values()) for k, v in roots.items()}
        if shutil.disk_usage(folder).free < sum(sizes.values()) * 2 + 512 * 1024 * 1024:
            raise RuntimeError('Insufficient snapshot/recovery space')
        state['rootBytes'] = sizes
        for name, target in roots.items():
            capture(target, folder / name, stopped)
        for name in roots: verify(folder / name)
        state.update(captured=True, phase='captured'); save(folder, state)
        state['generatedApi'] = provision(folder, inject)
        state['normalBoot'] = support.start(folder, 'dynamic-normal', False)
        validate_facts(facts(), True)
        if support.preflight() is not None:
            raise RuntimeError('Items preflight regressed')
        if support.digest(support.JAR) != before['agentSha256']:
            raise RuntimeError('Installed agent changed')
        state.update(phase='complete', verdict='SETUP PASS (not live C15 acceptance)')
    except Exception as error:
        failure = str(error); state['failure'] = failure
        try:
            if state['captured']:
                solr.stop_all(folder, 'recovery', False)
                restore_trees(folder, roots)
                native(folder, 'normal-rebuild', 'build', '-Dbuild.parallel=false')
                restore_trees(folder, roots)
            elif support.java_writers():
                # A surviving unowned/timed-out Ant or JVM is never killed blindly.
                stopped()
            state['recoveryBoot'] = support.start(folder, 'recovered-normal', False)
            validate_facts(facts(), False)
            if support.fixture_hashes() != before['fixtureHashes'] or support.digest(support.JAR) != before['agentSha256']:
                raise RuntimeError('Recovered source/agent baseline mismatch')
            state.update(phase='recovered', restoration='PASS', verdict='EXPECTED FAILURE RECOVERED' if inject and 'Injected dynamic setup failure' in failure else 'FAIL RECOVERED')
        except Exception as recovery_error:
            state.update(phase='recovery-required', restoration='FAIL', recoveryFailure=str(recovery_error))
    save(folder, state)
    return state


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inject-failure', action='store_true', help='after schema step, restore the old baseline')
    options = parser.parse_args()
    before = preflight()  # read-only before even a log/recovery directory or lease
    with lease() as handle:
        before = preflight()  # ownership may change while obtaining the lease
        folder = Path(tempfile.mkdtemp(prefix='reclazz-dynamic-setup-', dir='/private/tmp'))
        set_lease(handle, folder)
        state = attempt(folder, before, options.inject_failure)
        if state['phase'] in ('complete', 'recovered'): set_lease(handle, None)
        print(json.dumps({'evidence': str(folder), 'phase': state['phase'], 'verdict': state.get('verdict'),
                          'failure': state.get('failure'), 'recoveryFailure': state.get('recoveryFailure')}, indent=2))
        if state['phase'] != 'complete' and not (options.inject_failure and state.get('verdict') == 'EXPECTED FAILURE RECOVERED'):
            raise SystemExit(1)


if __name__ == '__main__': main()
