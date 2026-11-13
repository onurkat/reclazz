#!/usr/bin/env python3
"""Serialized items-schema profile. Native attempts count only after stopped-DB recovery."""
from pathlib import Path
from sap_suite_contract import DEFAULT_CASES, DEFAULT_OUTCOME, NORMAL_FIXTURE_FILES, default_result
import argparse
import datetime
import shutil
import http.cookiejar
import urllib.parse
import urllib.error
import hashlib
import json
import os
import re
import socket
import ssl
import subprocess
import tempfile
import time
import urllib.request
from sap_items_snapshot import capture, restore, verify, inventory
import re as _re
from pathlib import Path as _Path

# Built artifact names follow pluginVersion, so a version bump needs no edit here.
_VERSION = _re.search(r'^pluginVersion=(\S+)$', (_Path(__file__).resolve().parents[1] / 'gradle.properties').read_text(), _re.M).group(1)

REPO = Path(__file__).resolve().parent.parent
# A second disposable install (for a parallel agent) is selected with
# RECLAZZ_SAP_HOME and RECLAZZ_SAP_BASE; the default is the one beside the repo.
SAP_HOME = Path(os.environ.get('RECLAZZ_SAP_HOME') or REPO.parent / 'reclazz-sap') / 'hybris'
PLATFORM = SAP_HOME / 'bin/platform'
BASE = os.environ.get('RECLAZZ_SAP_BASE') or 'https://localhost:9102'
ENDPOINT = BASE + '/reclazztest/v2/test/batch'
PORT_FILE = SAP_HOME.parent / 'agent.port'
JAR = SAP_HOME.parent / 'agent/reclazz-agent.jar'
JAVA_HOME = Path('/Library/Java/JavaVirtualMachines/sapmachine-21.jdk/Contents/Home')
TLS = ssl._create_unverified_context()


def get(url):
    with urllib.request.urlopen(url, context=TLS, timeout=15) as response:
        if response.status != 200:
            raise RuntimeError('Expected HTTP200')
        return json.load(response)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def doctor():
    token = 'items-' + str(time.monotonic_ns())
    with socket.create_connection(('127.0.0.1', int(PORT_FILE.read_text())), 5) as connection:
        connection.settimeout(10)
        connection.sendall(('DOCTOR ' + token + '\n').encode())
        for line in connection.makefile():
            message = json.loads(line).get('message', '')
            prefix = 'DOCTOR_RESULT ' + token + ' '
            if message.startswith(prefix):
                return json.loads(message[len(prefix):])
    raise RuntimeError('Missing running-agent identity')


def java_writers():
    lines = subprocess.check_output(['ps', '-axo', 'pid=,command='], text=True).splitlines()
    return [line.split(None, 1)[0] for line in lines if str(SAP_HOME) in line and '/bin/java ' in line]


def stopped():
    if java_writers() or (SAP_HOME / 'data/hsqldb/mydb.lck').exists():
        raise RuntimeError('Clean-install DB writer or HSQLDB lock remains')


def command(args, log, timeout=600):
    env = os.environ.copy()
    env['JAVA_HOME'] = str(JAVA_HOME); env['PATH'] = str(JAVA_HOME / 'bin') + ':' + env['PATH']
    with Path(log).open('w') as stream:
        process = subprocess.Popen(args, cwd=PLATFORM, env=env, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait(timeout)
        except subprocess.TimeoutExpired:
            # Do not kill a native writer mid-operation or restore an open database.
            raise RuntimeError('Native command exceeded deadline; process preserved, PID=' + str(process.pid))
    if code:
        raise RuntimeError('Native command failed; see ' + Path(log).name)


def stop(folder, label):
    command(['./hybrisserver.sh', 'stop'], folder / (label + '-stop.log'), 90)
    deadline = time.monotonic() + 330
    while time.monotonic() < deadline:
        try:
            stopped(); return
        except RuntimeError:
            time.sleep(1)
    stopped()  # refuses restoration while the JVM or a native ant child survives


def schema_wrapper_bytes(original, jar):
    text = original.decode()
    prefix = '-javaagent:' + str(jar)
    matches = [m for m in re.finditer(r'(?m)^wrapper\.java\.additional\.\d+=(-javaagent:\S+)[ \t\r]*$', text)
               if m.group(1) == prefix or m.group(1).startswith(prefix + '=')]
    if len(matches) != 1:
        raise RuntimeError('Expected exactly one owned agent wrapper entry')
    match = matches[0]; argument = match.group(1)
    if 'autoUpdateRunningSystem=' in argument:
        raise RuntimeError('Explicit schema option in normal wrapper')
    suffix = ',' if '=' in argument else '='
    return (text[:match.end(1)] + suffix + 'autoUpdateRunningSystem=true' + text[match.end(1):]).encode()


def require_boot_options(gates, expected_update):
    required = {'agentArgsFound': True, 'agentOptionsMatchConfig': True, 'autoCompile': True,
                'autoUpdateRunningSystem': expected_update, 'resetStatics': False,
                'autoSolrReindex': False, 'impexAllowRemove': False}
    for key, value in required.items():
        if type(gates.get(key)) is not bool or gates[key] != value:
            raise RuntimeError('Boot option mismatch: ' + key + '=' + str(gates.get(key)))


def start(folder, label, expected_update):
    PORT_FILE.unlink(missing_ok=True)
    command(['./hybrisserver.sh', 'start'], folder / (label + '-start.log'), 90)
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        try:
            gates = get(ENDPOINT + '/gates')
        except Exception:
            time.sleep(2); continue
        # A responding server with the wrong options cannot converge without another boot.
        require_boot_options(gates, expected_update)
        try:
            if get(BASE + '/occ/v2/reclazz-fixture/reclazzprobe?fields=FULL') != {'code': 'v0', 'label': 'label0'}:
                raise RuntimeError('Normal OCC baseline unavailable')
            state = doctor()
            if state.get('watcherState') != 'watching' or state.get('buildHold') != 'none':
                raise RuntimeError('Agent not ready')
            return {'doctor': {k: state.get(k) for k in ['pid', 'sessionId', 'watcherState', 'buildHold']},
                    'gates': gates, 'facts': get(ENDPOINT + '/items/facts'), 'health': tenant_health()}
        except Exception:
            time.sleep(2)
    raise RuntimeError('Clean-install readiness deadline')


def tenant_health():
    result = get(ENDPOINT + '/items/health')
    if set(result) != {'master', 'junit'} or any(type(v) is not int or v < 0 for v in result.values()):
        raise RuntimeError('Native master/junit health unavailable')
    return result


def validate_facts(facts):
    expected_db = 'jdbc:hsqldb:file:' + str(SAP_HOME / 'data/hsqldb/mydb')
    tenants = facts.get('tenants')
    if not isinstance(tenants, list) or not tenants or not {'master', 'junit'}.issubset({t['id'] for t in tenants}):
        raise RuntimeError('Missing master/junit DB facts')
    if any(t['db'].split(';', 1)[0] != expected_db for t in tenants):
        raise RuntimeError('Foreign/non-disposable tenant DB; no writes allowed')
    if facts.get('collisions') != 0 or facts.get('typeCode') != 31991 or facts.get('table', '').lower() != 'reclazzschemaprobes':
        raise RuntimeError('Synthetic deployment metadata mismatch')
    if facts.get('batchValuePresent') is not False or facts.get('rows') != 0:
        raise RuntimeError('Schema baseline attribute/row mismatch')


def runner(folder, label, profile=None, recovery=None, inject=False):
    env = os.environ.copy()
    for key in ['RECLAZZ_TEST_ONLY', 'RECLAZZ_TEST_PROFILE', 'RECLAZZ_ITEMS_RECOVERY', 'RECLAZZ_ITEMS_INJECT_FAILURE']:
        env.pop(key, None)
    env.update(JAVA_HOME=str(JAVA_HOME), RECLAZZ_TEST_BASE_URL=BASE, RECLAZZ_TEST_PORT_FILE=str(PORT_FILE),
               RECLAZZ_TEST_EXT_PATH=str(REPO / 'reclazztest'), RECLAZZ_TEST_HYBRIS_HOME=str(SAP_HOME))
    if profile:
        env['RECLAZZ_TEST_PROFILE'] = profile; env['RECLAZZ_ITEMS_RECOVERY'] = str(recovery)
    if inject:
        env['RECLAZZ_ITEMS_INJECT_FAILURE'] = 'true'
    with (folder / (label + '.log')).open('w') as stream:
        result = subprocess.run(['./gradlew', ':integration-test:run', '--console=plain'], cwd=REPO,
                                env=env, stdout=stream, stderr=subprocess.STDOUT, timeout=900)
    text = (folder / (label + '.log')).read_text()
    return {'exitCode': result.returncode, **default_result(text),
            'injectedFailure': 'Injected items failure after persisted write' in text,
            'newTypeSuite': new_type_report(text), 'newRelationSuite': new_relation_report(text)}


def save_recovery_state(folder, config_bytes, fixture_bytes, generated, agent_hash):
    # Durable originals support manual recovery even if the test process is lost.
    state = {'home': str(SAP_HOME), 'snapshotVerified': True, 'agentSha256': agent_hash,
             'configBytes': {str(path): value.hex() for path, value in config_bytes.items()},
             'configSha256': {str(path): hashlib.sha256(value).hexdigest() for path, value in config_bytes.items()},
             'fixtureSourceRelative': 'resources/reclazztest-items.xml',
             'fixtureSourceBytes': fixture_bytes.hex(),
             'fixtureSourceSha256': hashlib.sha256(fixture_bytes).hexdigest(),
             'generatedTargets': [str(path) for path in generated]}
    target = folder / 'state.json'
    target.write_text(json.dumps(state, indent=2) + '\n')
    if json.loads(target.read_text()) != state:
        raise RuntimeError('Durable recovery state read-back failed')


def fixture_hashes():
    manifest = os.environ.get('RECLAZZ_FIXTURE_MANIFEST')
    if manifest:
        expected = json.loads(Path(manifest).read_text())
        if not isinstance(expected, dict) or len(expected) != NORMAL_FIXTURE_FILES:
            raise RuntimeError('Reviewed fixture manifest membership mismatch')
        for name, sha in expected.items():
            path = Path(name)
            if path.is_absolute() or '..' in path.parts or path.parts[0] not in {'reclazztest', 'reclazztestbackoffice'} or len(sha) != 64:
                raise RuntimeError('Unsafe fixture manifest member')
        actual = {name: digest(REPO / name) for name in expected}
        if actual != expected:
            raise RuntimeError('Reviewed fixture manifest byte mismatch')
        return actual
    tracked = subprocess.check_output(['git', '--no-optional-locks', 'ls-files', '--', 'reclazztest', 'reclazztestbackoffice'], cwd=REPO, text=True).splitlines()
    added = subprocess.check_output(['git', '--no-optional-locks', 'ls-files', '--others', '--exclude-standard', '--', 'reclazztest', 'reclazztestbackoffice'], cwd=REPO, text=True).splitlines()
    return {name: digest(REPO / name) for name in tracked + added}


def profile(folder, number, inject):
    """One entire native attempt/recovery is a profile; inner PASS alone is insufficient."""
    root = folder / ('cycle-' + str(number)); root.mkdir()
    baseline_facts = get(ENDPOINT + '/items/facts'); validate_facts(baseline_facts)
    jar_hash = digest(JAR)
    fixtures = fixture_hashes()
    log_root = SAP_HOME / 'log/tomcat'
    offsets = {p: p.stat().st_size for p in log_root.glob('console-*.log')}
    configs = {p: p.read_bytes() for p in (SAP_HOME / 'config').rglob('*') if p.is_file() and p.suffix in {'.properties', '.xml', '.conf'}}
    wrapper = PLATFORM / 'tomcat/conf/wrapper.conf'; configs[wrapper] = wrapper.read_bytes()
    items = REPO / 'reclazztest/resources/reclazztest-items.xml'; baseline_items = items.read_bytes()
    generated = [PLATFORM / 'bootstrap/gensrc', PLATFORM / 'bootstrap/modelclasses', PLATFORM / 'bootstrap/bin',
                 REPO / 'reclazztest/gensrc', REPO / 'reclazztest/classes']
    result = {'number': number, 'injected': inject, 'baselineFacts': baseline_facts, 'beforeDoctor': doctor()}
    captured = False
    try:
        stop(root, 'capture')
        capture(SAP_HOME / 'data/hsqldb', root / 'db', stopped)
        for i, path in enumerate(generated):
            capture(path, root / ('generated-' + str(i)), stopped)
        save_recovery_state(root, configs, baseline_items, generated, jar_hash)
        captured = True
        command(['/bin/bash', '-c', 'source ./setantenv.sh && ant build'], root / 'build.log')
        # This is the actual launcher input. Keep local.properties intent unchanged.
        wrapper.write_bytes(schema_wrapper_bytes(configs[wrapper], JAR))
        if any(path != wrapper and path.read_bytes() != value for path, value in configs.items()):
            raise RuntimeError('Unowned configuration change before schema boot')
        result['profileBoot'] = start(root, 'profile', True)
        validate_facts(result['profileBoot']['facts'])
        result['attempt'] = runner(root, 'attempt', 'items-attempt', root, inject)
        if inject:
            if result['attempt']['exitCode'] == 0 or not result['attempt']['injectedFailure']:
                raise RuntimeError('Injected failure was not observed')
        elif result['attempt']['exitCode'] != 0 or result['attempt']['counts'] != {'PASS': 1, 'FAIL': 0, 'ERROR': 0, 'SKIP': 0}:
            raise RuntimeError('Schema attempt failed/skipped')
    except Exception as failure:
        result['failure'] = str(failure)
    finally:
        if captured:
            try:
                stop(root, 'restore')
                items.write_bytes(baseline_items)
                for path, value in configs.items():
                    path.write_bytes(value)
                for i, path in enumerate(generated):
                    restore(root / ('generated-' + str(i)), path, stopped)
                restore(root / 'db', SAP_HOME / 'data/hsqldb', stopped)
                result['dbHashesRestoredBeforeBoot'] = inventory(SAP_HOME / 'data/hsqldb') == verify(root / 'db')
                if not result['dbHashesRestoredBeforeBoot'] or digest(JAR) != jar_hash:
                    raise RuntimeError('DB/agent recovery mismatch')
                result['normalBoot'] = start(root, 'normal', False)
                validate_facts(result['normalBoot']['facts'])
                if result['normalBoot']['doctor']['sessionId'] == result.get('profileBoot', {'doctor': result['beforeDoctor']})['doctor']['sessionId']:
                    raise RuntimeError('Recovery was not a fresh JVM boot')
                result['sourcesRestored'] = items.read_bytes() == baseline_items
                result['configHashesRestored'] = all(p.read_bytes() == value for p, value in configs.items())
                if not result['sourcesRestored'] or not result['configHashesRestored']:
                    raise RuntimeError('Source/config recovery mismatch')
                result['fixtureFiles'] = len(fixtures)
                result['fixtureHashesRestored'] = fixture_hashes() == fixtures
                if not result['fixtureHashesRestored']:
                    raise RuntimeError('Fixture byte restoration mismatch')
                result['restoration'] = 'PASS'
            except Exception as failure:
                result['restoration'] = 'FAIL'; result['restoreFailure'] = str(failure)
        else:
            # No schema mutation occurred. A stopped server must still be returned to normal.
            try:
                if not java_writers():
                    result['normalBoot'] = start(root, 'uncaptured-normal', False)
            except Exception as failure:
                result['restoreFailure'] = str(failure)
    native = b''
    for path in log_root.glob('console-*.log'):
        with path.open('rb') as stream:
            stream.seek(offsets.get(path, 0)); native += stream.read()
    (root / 'native.log').write_bytes(native)
    result['nativeRefreshFailures'] = native.count(b'Failed to refresh Spring bean')
    if result['nativeRefreshFailures']:
        result['failure'] = 'Native bean refresh failures occurred'
    result['verdict'] = 'PASS' if 'failure' not in result and result.get('restoration') == 'PASS' else 'FAIL'
    (root / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({k: result.get(k) for k in ['number', 'injected', 'verdict', 'restoration', 'failure', 'restoreFailure']}), flush=True)
    return result


def preflight():
    gates = get(ENDPOINT + '/gates')
    required = {'agentArgsFound': True, 'agentOptionsMatchConfig': True, 'autoCompile': True,
                'autoUpdateRunningSystem': False, 'resetStatics': False, 'autoSolrReindex': False,
                'impexAllowRemove': False, 'codegenExtensionLoaded': True, 'codegenGeneratedRoots': True}
    for field, value in required.items():
        if type(gates.get(field)) is not bool or gates[field] != value:
            return 'gate ' + field + ' missing/mismatch'
    facts = get(ENDPOINT + '/items/facts')
    if facts.get('typePresent') is not True:
        return 'synthetic baseline type not deployed'
    validate_facts(facts)
    tenant_health()
    if SAP_HOME.resolve() != SAP_HOME or not (SAP_HOME / 'bin/custom/reclazztest').samefile(REPO / 'reclazztest'):
        raise RuntimeError('Clean install ownership mismatch')
    return None



NEW_TYPE_LABEL = 'New item type attempt (outer recovery required)'


NEW_RELATION_LABEL = 'New relation attempt (outer recovery required)'

def new_relation_report(text):
    return new_type_report(text, NEW_RELATION_LABEL)

def new_type_report(text, label=NEW_TYPE_LABEL):
    plain = re.sub(r'\x1b\[[0-9;]*m', '', text)
    rows = re.findall(r'^([^|\n]+)\|\s*(PASS|FAIL|ERROR|SKIP)\s*\|[^\n]*$', plain, re.M)
    return [(name.strip(), status) for name, status in rows] == [(label, 'PASS')]


def hac(script,commit=False):
 props=(SAP_HOME/'config/local.properties').read_text();password=next(line.split('=',1)[1].strip() for line in props.splitlines() if line.startswith('initialpassword.admin='))
 opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),urllib.request.HTTPSHandler(context=TLS))
 page=opener.open(BASE+'/hac/login',timeout=20).read().decode();token=re.search(r'name="_csrf" value="([^"]+)"',page).group(1)
 try:opener.open(BASE+'/j_spring_security_check',urllib.parse.urlencode({'j_username':'admin','j_password':password,'_csrf':token}).encode(),timeout=20).read()
 except urllib.error.HTTPError as e:
  if e.code!=404:raise RuntimeError('Native HAC login failed') from None
 password=None
 page=opener.open(BASE+'/console/scripting',timeout=20).read().decode();token=re.search(r'name="_csrf" content="([^"]+)"',page).group(1)
 request=urllib.request.Request(BASE+'/console/scripting/execute',data=urllib.parse.urlencode({'script':script,'scriptType':'groovy','commit':str(commit).lower()}).encode(),headers={'X-CSRF-TOKEN':token,'Accept':'application/json'})
 result=json.load(opener.open(request,timeout=60))
 if result.get('stacktraceText'):raise RuntimeError(str(result['stacktraceText']))
 return json.loads(result['executionResult'])
NEW_TYPE_COLLISIONS='''import groovy.json.JsonOutput
import de.hybris.platform.core.Registry
import de.hybris.platform.jalo.type.TypeManager
def code='ReclazzAddedTypeProbe';def table='ReclazzAddedTypeProbes'
def types=TypeManager.getInstance().getAllComposedTypes()
def collisions=types.count{t->t.getCode()==code||t.getItemTypeCode()==31992||table.equalsIgnoreCase(t.getTable())}
def con=Registry.getCurrentTenant().getDataSource().getConnection();def tables=[]
try{def rs=con.metaData.getTables(null,null,null,['TABLE'] as String[]);try{while(rs.next()){def n=rs.getString('TABLE_NAME');if(n.toUpperCase().contains(table.toUpperCase()))tables.add(n)}}finally{rs.close()}}finally{con.close()}
return JsonOutput.toJson([collisions:collisions,matchingTables:tables,baselinePresent:types.any{it.getCode()=='ReclazzSchemaProbe'},tenant:Registry.currentTenant.tenantID])'''

def new_type_absent(load_classes=True):
    facts = hac(NEW_TYPE_COLLISIONS)
    if facts.get('collisions') != 0 or facts.get('matchingTables') != [] or facts.get('baselinePresent') is not True or facts.get('tenant') != 'master':
        raise RuntimeError('New type/table collision or recovery mismatch: ' + str(facts))
    script = """import groovy.json.JsonOutput
import de.hybris.platform.core.Registry
def names=['com.onurkat.reclazztest.model.ReclazzAddedTypeProbeModel','com.onurkat.reclazztest.jalo.ReclazzAddedTypeProbe']
def found=names.collectEntries {name -> try {Class.forName(name,false,Registry.applicationContext.classLoader);[(name):true]}catch(ClassNotFoundException e){[(name):false]}}
return JsonOutput.toJson(found)"""
    if not load_classes:
        # Class.forName before generation seeds SAP CacheClassNotFoundRule. A resource
        # lookup proves absence without changing that cache in the experiment JVM.
        script = script.replace("try {Class.forName(name,false,Registry.applicationContext.classLoader);[(name):true]}catch(ClassNotFoundException e){[(name):false]}",
                                "[(name):(Registry.applicationContext.classLoader.getResource(name.replace('.', '/')+'.class')!=null)]")
    classes = hac(script)
    if len(classes) != 2 or any(value is not False for value in classes.values()):
        raise RuntimeError('New generated Model/Jalo survived: ' + str(classes))
    return {'metadata': facts, 'classes': classes}


def wait_new_type_idle():
    deadline = time.monotonic() + 1200
    while time.monotonic() < deadline:
        writers = java_writers()
        if not writers:
            stopped(); return
        state = doctor()
        if writers == [str(state['pid'])]:
            status = hac("import groovy.json.JsonOutput;return JsonOutput.toJson([locked:new de.hybris.platform.hac.facade.HacInitUpdateFacade().isLocked()])")
            if status.get('locked') is False:
                return
        time.sleep(5)
    raise RuntimeError('Native writer/update remains; installation and lock preserved')


def new_type_roots():
    return {'db': SAP_HOME / 'data/hsqldb', 'config': SAP_HOME / 'config',
            'bootstrap': PLATFORM / 'bootstrap', 'native-temp': SAP_HOME / 'temp/hybris',
            'tomcat': PLATFORM / 'tomcat', 'fixture': REPO / 'reclazztest',
            'backoffice-fixture': REPO / 'reclazztestbackoffice',
            'native-backoffice': SAP_HOME / 'bin/modules/backoffice-framework/backoffice'}


def rebuild_new_type(folder, checkout, label):
    if checkout.resolve() != REPO.resolve():
        raise RuntimeError('Build cleanup is confined to the assigned checkout')
    stopped()
    for name in ['reclazztest', 'reclazztestbackoffice']:
        (SAP_HOME / 'temp/hybris/touch' / (name + '_srctouch')).unlink(missing_ok=True)
        for relative in ['classes', 'backoffice/classes', 'web/webroot/WEB-INF/classes']:
            target = checkout / name / relative
            if target.exists():
                shutil.rmtree(target)
    command(['/bin/bash', '-c', 'source ./setantenv.sh && ant all'], folder / (label + '-build.log'), 900)


def wt_targets(checkout):
    found = []
    for install in [SAP_HOME.parent, REPO.parent / 'reclazz-sap', REPO.parent / 'reclazz-sap-astra']:
        for root, dirs, files in os.walk(install, followlinks=False):
            for name in dirs + files:
                path = Path(root) / name
                if path.is_symlink() and path.resolve().is_relative_to(checkout):
                    found.append(str(path))
    return sorted(set(found))



NEW_RELATION_COLLISIONS = "import groovy.json.JsonOutput\nimport de.hybris.platform.core.Registry\nimport de.hybris.platform.jalo.type.TypeManager\ndef ctx=Registry.applicationContext;def ms=ctx.getBean('modelService');def fs=ctx.getBean('flexibleSearchService');def tm=TypeManager.getInstance()\ndef names=['ReclazzRelationLeftProbe','ReclazzRelationRightProbe','ReclazzOneToManyProbe','ReclazzManyToManyProbe'];def tables=['ReclazzRelLeftProbes','ReclazzRelRightProbes','ReclazzRelLinks'];def types=tm.allComposedTypes\n def collisions=types.findAll{names.contains(it.code)||[31993,31994,31995].contains(it.itemTypeCode)||tables.any{n->n.equalsIgnoreCase(it.table)}}.collect{it.code}\n def con=Registry.currentTenant.dataSource.connection;def found=[]\n try{def rs=con.metaData.getTables(null,null,null,['TABLE'] as String[]);try{while(rs.next()){def n=rs.getString('TABLE_NAME');if(tables.any{t->t.equalsIgnoreCase(n)})found.add(n)}}finally{rs.close()}}finally{con.close()}\n return JsonOutput.toJson([collisions:collisions,tables:found,tenant:Registry.currentTenant.tenantID])"

def new_relation_absent(load_classes=False):
    facts = hac(NEW_RELATION_COLLISIONS)
    if facts.get('collisions') != [] or facts.get('tables') != [] or facts.get('tenant') != 'master':
        raise RuntimeError('Relation type/table collision or recovery mismatch: ' + str(facts))
    names = [f'com.onurkat.reclazztest.{kind}.{name}' + ('Model' if kind == 'model' else '') for kind in ['model', 'jalo'] for name in ['ReclazzRelationLeftProbe', 'ReclazzRelationRightProbe']]
    script = "import groovy.json.JsonOutput;import de.hybris.platform.core.Registry;def names=" + json.dumps(names) + ";return JsonOutput.toJson(names.collectEntries{n->[(n):(Registry.applicationContext.classLoader.getResource(n.replace('.','/')+'.class')!=null)]})"
    resources = hac(script)
    if len(resources) != 4 or any(value is not False for value in resources.values()):
        raise RuntimeError('Relation generated resources survived: ' + str(resources))
    return {'metadata': facts, 'resources': resources}

def new_type_profile(folder, restore_repo, lock, inject=False, new_relation=False):
    """Stage1's full stopped recovery, with the durable Kotlin oracle as inner attempt."""
    mode = 'new-relation' if new_relation else 'new-item-type'
    absent = new_relation_absent if new_relation else new_type_absent
    record = {'phase': 'preflight', 'mode': mode, 'attempt': 'UNRUN'}
    def save():
        (folder / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
    owner = 'OWNER=astra LANE=' + ('c33c-new-relation' if new_relation else 'c33a-new-item-type') + ' WT=' + str(REPO) + ' SINCE=' + datetime.datetime.now(datetime.timezone.utc).isoformat() + ' PID=' + str(os.getpid()) + ' PURPOSE=new-item-type-profile\n'
    lock.mkdir()  # no stale-lock guesses or concurrent installation work
    (lock / 'owner').write_text(owner)
    captured = False
    clean_return = False
    try:
        record['beforeDoctor'] = doctor()
        if java_writers() != [str(record['beforeDoctor']['pid'])]:
            raise RuntimeError('Own native writer active; zero installation changes')
        require_boot_options(get(ENDPOINT + '/gates'), False)
        record['baselineFacts'] = get(ENDPOINT + '/items/facts'); validate_facts(record['baselineFacts'])
        record['health'] = tenant_health(); record['beforeAbsent'] = absent()
        fixtures = fixture_hashes()
        record['originalLinks'] = {name: os.readlink(SAP_HOME / 'bin/custom' / name) for name in ['reclazztest', 'reclazztestbackoffice']}
        if any((SAP_HOME / 'bin/custom' / name).resolve() != restore_repo / name for name in record['originalLinks']):
            raise RuntimeError('Normal restore checkout/link mismatch')
        record['jarDigest'] = digest(JAR); shutil.copy2(JAR, folder / 'agent-before.jar'); save()
        stop(folder, 'snapshot')
        for name, path in new_type_roots().items():
            capture(path, folder / name, stopped)
        captured = True
        wrapper = PLATFORM / 'tomcat/conf/wrapper.conf'
        save_recovery_state(folder, {wrapper: wrapper.read_bytes()}, (REPO / 'reclazztest/resources/reclazztest-items.xml').read_bytes(), list(new_type_roots().values()), record['jarDigest'])
        state = json.loads((folder / 'state.json').read_text())
        state.update(mode=mode, fullSnapshotVerified=True,
                     snapshots={name: str(path) for name, path in new_type_roots().items()})
        (folder / 'state.json').write_text(json.dumps(state, indent=2) + '\n')
        record['phase'] = 'captured'; save()
        for name in record['originalLinks']:
            link = SAP_HOME / 'bin/custom' / name; link.unlink(); link.symlink_to(REPO / name)
        if new_relation:
            candidate = REPO / 'agent/build/libs' / f'agent-{_VERSION}.jar'
            record['candidateSha256'] = digest(candidate); shutil.copy2(candidate, JAR)
        rebuild_new_type(folder, REPO, 'candidate')
        wrapper.write_bytes(schema_wrapper_bytes(wrapper.read_bytes(), JAR))
        record['profileBoot'] = start(folder, 'schema-profile', True)
        validate_facts(record['profileBoot']['facts']); record['negativeControl'] = absent(load_classes=False)
        record['phase'] = 'attempt'; save()
        result = runner(folder, 'attempt', 'items-new-relation-attempt' if new_relation else 'items-new-type-attempt', folder, inject)
        record['inner'] = result; save()
        if inject:
            if result['exitCode'] == 0 or not result['injectedFailure']:
                raise RuntimeError('Injected new-type failure not observed')
        elif result['exitCode'] != 0 or not result['newRelationSuite' if new_relation else 'newTypeSuite']:
            raise RuntimeError('Exact new-type attempt PASS missing')
        if doctor()['sessionId'] != record['profileBoot']['doctor']['sessionId']:
            raise RuntimeError('Experiment JVM changed')
        validate_facts(get(ENDPOINT + '/items/facts'))
        if tenant_health() != record['health']:
            raise RuntimeError('Control health changed')
        record['attempt'] = 'PASS'; save()
    except Exception as failure:
        record['failure'] = str(failure); record['attempt'] = 'FAIL'; save()
    finally:
        if captured:
            try:
                wait_new_type_idle(); stop(folder, 'restore')
                for name in new_type_roots():
                    verify(folder / name)
                for name, path in new_type_roots().items():
                    restore(folder / name, path, stopped)
                record['allSnapshotHashesRestored'] = all(inventory(path) == verify(folder / name) for name, path in new_type_roots().items())
                if not record['allSnapshotHashesRestored'] or fixture_hashes() != fixtures:
                    raise RuntimeError('Full snapshot/fixture restoration mismatch')
                record['phase'] = 'snapshots-restored'; save()
                for name, target in record['originalLinks'].items():
                    link = SAP_HOME / 'bin/custom' / name
                    if link.resolve() != REPO / name:
                        raise RuntimeError('Extension link changed during attempt')
                    link.unlink(); link.symlink_to(target)
                shutil.copy2(folder / 'agent-before.jar', JAR)
                if digest(JAR) != record['jarDigest']:
                    raise RuntimeError('Agent byte recovery mismatch')
                # All native build outputs were restored from the stopped snapshot.
                # The normal checkout was never edited/built: reuse its existing outputs.
                record['normalBoot'] = start(folder, 'restored-main', False)
                validate_facts(record['normalBoot']['facts']); record['restoredAbsent'] = absent()
                if record['normalBoot']['doctor']['sessionId'] == record.get('profileBoot', {'doctor': record['beforeDoctor']})['doctor']['sessionId'] or tenant_health() != record['health']:
                    raise RuntimeError('Fresh normal JVM/control recovery mismatch')
                record['remainingWTTargets'] = wt_targets(REPO)
                if record['remainingWTTargets']:
                    raise RuntimeError('Worktree links remain')
                record['restoration'] = 'PASS'; clean_return = True
            except Exception as failure:
                record['restoration'] = 'FAIL'; record['restoreFailure'] = str(failure)
        else:
            # No schema was saved; finish any native baseline startup after a partial capture.
            try:
                if not java_writers():
                    record['normalBoot'] = start(folder, 'uncaptured-normal', False)
                clean_return = True
            except Exception as failure:
                record['restoreFailure'] = str(failure)
        record['verdict'] = 'PASS' if record.get('attempt') == 'PASS' and record.get('restoration') == 'PASS' else 'FAIL'
        record['phase'] = 'restored' if clean_return else 'recovery-required'; save()
        if clean_return and (lock / 'owner').read_text() == owner:
            (lock / 'owner').unlink(); lock.rmdir(); record['lockReleased'] = True; save()
    print(('NEW_RELATION_PROFILE ' if new_relation else 'NEW_ITEM_TYPE_PROFILE ') + record['verdict'] + '; evidence: ' + str(folder), flush=True)
    return record


def run_new_type(args, new_relation=False):
    if not args.fixture_manifest or not args.restore_repo or not args.lock or args.runs != 1:
        raise RuntimeError('Opt-in items mode requires --runs 1, --fixture-manifest, --restore-repo and --lock')
    os.environ['RECLAZZ_FIXTURE_MANIFEST'] = str(args.fixture_manifest.resolve())
    if SAP_HOME.resolve() != SAP_HOME or not (SAP_HOME.parent / 'hybris/bin/platform').is_dir():
        raise RuntimeError('Disposable installation identity mismatch')
    if not args.lock.parent.is_dir() or args.lock.name not in {'SAP-astra.lock', 'SAP.lock'}:
        raise RuntimeError('Explicit collab installation lock required')
    fixture_hashes()  # read-only manifest check before output/lock creation
    restore_repo = args.restore_repo.resolve()
    if restore_repo == REPO or not (restore_repo / 'reclazztest/resources/reclazztest-items.xml').is_file():
        raise RuntimeError('A distinct normal restore checkout is required')
    folder = Path(tempfile.mkdtemp(prefix='reclazz-new-relation-' if new_relation else 'reclazz-new-item-type-', dir='/private/tmp'))
    print('Recovery/evidence directory: ' + str(folder), flush=True)
    result = new_type_profile(folder, restore_repo, args.lock, args.failure_control, new_relation)
    return 0 if result['verdict'] == 'PASS' else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs', type=int, default=3)
    parser.add_argument('--failure-control', action='store_true')
    parser.add_argument('--new-item-type', action='store_true')
    parser.add_argument('--new-relation', action='store_true')
    parser.add_argument('--fixture-manifest', type=Path)
    parser.add_argument('--restore-repo', type=Path)
    parser.add_argument('--lock', type=Path)
    args = parser.parse_args()
    if args.new_relation and args.new_item_type:
        parser.error('Select one opt-in items mode')
    if args.new_relation:
        return run_new_type(args, new_relation=True)
    if args.new_item_type:
        return run_new_type(args)
    if args.runs < 1:
        parser.error('--runs must be positive')
    # Read-only preflight precedes even the recovery/log directory.
    try:
        reason = preflight()
    except Exception as failure:
        reason = 'Preflight unavailable: ' + str(failure)
    if reason:
        print('ITEMS_PROFILE SKIP ' + reason + '; zero writes'); return 0
    folder = Path(tempfile.mkdtemp(prefix='reclazz-items-', dir='/private/tmp'))
    print('Recovery/evidence directory: ' + str(folder), flush=True)
    results = []
    for number in range(args.runs + int(args.failure_control)):
        result = profile(folder, number + 1, args.failure_control and number == 0)
        results.append(result)
        if result['verdict'] != 'PASS':
            break
    if all(r['verdict'] == 'PASS' for r in results):
        baseline = runner(folder, 'normal-default')
        if baseline['exitCode'] != 0 or baseline['counts'] != DEFAULT_OUTCOME or not baseline['defaultSuite']:
            raise RuntimeError('Restored default suite failed; evidence preserved in ' + str(folder))
    else:
        baseline = {'unrun': 'profile/recovery failed'}
    passed = len(results) == args.runs + int(args.failure_control) and all(r['verdict'] == 'PASS' for r in results) and 'unrun' not in baseline
    (folder / 'summary.json').write_text(json.dumps({'verdict': 'PASS' if passed else 'FAIL', 'cycles': results, 'default': baseline}, indent=2) + '\n')
    print('ITEMS_PROFILE ' + ('PASS' if passed else 'FAIL') + '; recovery evidence: ' + str(folder), flush=True)
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
