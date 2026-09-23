// Copyright 2026 Onur Kat
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import test from 'node:test';
import { spawn, execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { once } from 'node:events';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { promisify } from 'node:util';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { Evaluation, reloadProven } from './evaluation.mjs';

const exec = promisify(execFile);
const root = path.resolve(import.meta.dirname, '../../../..');
const version = (await fs.readFile(path.join(root, 'gradle.properties'), 'utf8'))
  .match(/^pluginVersion=(.+)$/m)[1].trim();
const executable = name => path.join(process.env.JAVA_HOME ?? assert.fail('Set JAVA_HOME to the tested JDK'),
  'bin', name + (process.platform === 'win32' ? '.exe' : ''));
const java = executable('java');
const javac = executable('javac');
const expectedTools = ['status', 'doctor', 'scan', 'pending', 'diagnose', 'build', 'verify', 'verify_batch']
  .map(name => `reclazz_${name}`).sort();

async function until(operation, accepts, label, millis = 15000) {
  const deadline = Date.now() + millis;
  let value;
  do {
    value = await operation();
    if (accepts(value)) return value;
    await delay(50);
  } while (Date.now() < deadline);
  assert.fail(`${label}: deadline exceeded; last result ${JSON.stringify(value)}`);
}

// Deliberately a consumer test: uses installed jars and public stdio, never server internals.
test('official SDK against packaged MCP and a persistent application JVM', { timeout: 120000 }, async t => {
  const evidenceRoot = path.join(root, 'mcp-server/build/client-matrix');
  await fs.mkdir(evidenceRoot, { recursive: true });
  const dir = await fs.mkdtemp(path.join(evidenceRoot, `${process.platform}-`));
  t.diagnostic(`Evidence: ${dir}`);
  const evaluation = new Evaluation();
  const metadata = { platform: process.platform, arch: process.arch, node: process.version,
    sdk: '1.30.0', version, java, javac };
  // Register before setup: missing artifacts or compiler failures must retain a non-passing report.
  t.after(async () => {
    const report = evaluation.report(metadata);
    await fs.writeFile(path.join(dir, 'evaluation.json'), JSON.stringify(report, null, 2) + '\n');
    t.diagnostic(`Evaluation: ${JSON.stringify(report.summary)}`);
  });
  const jdk = await exec(java, ['-version']);
  metadata.jdk = jdk.stderr.trim();
  const install = path.join(dir, 'installed jars with spaces');
  const project = path.join(dir, 'consumer project with spaces');
  const source = path.join(project, 'src');
  const classes = path.join(project, 'classes');
  for (const folder of [install, source, classes]) await fs.mkdir(folder, { recursive: true });
  const agent = path.join(install, 'agent.jar');
  const mcp = path.join(install, 'mcp.jar');
  await fs.copyFile(path.join(root, `agent/build/libs/agent-${version}.jar`), agent);
  await fs.copyFile(path.join(root, `mcp-server/build/distributions/mcp/reclazz-mcp-${version}.jar`), mcp);
  metadata.artifacts = {};
  for (const [name, file] of Object.entries({ agent, mcp })) {
    metadata.artifacts[name] = createHash('sha256').update(await fs.readFile(file)).digest('hex');
  }
  const portFile = path.join(project, 'agent.port');
  const service = path.join(source, 'Service.java');
  const appSource = path.join(source, 'App.java');
  const serviceClass = path.join(classes, 'Service.class');
  const hash = async file => createHash('sha256').update(await fs.readFile(file)).digest('hex');
  const writeService = value => fs.writeFile(service, `public class Service {
    private int calls; public int next() { return ++calls; }
    public int value() { return ${value}; }
  }`);
  const compile = async (files, label, shouldPass = true) => {
    let result;
    try {
      result = await exec(javac, ['-cp', classes, '-d', classes, ...files], { cwd: project, timeout: 30000 });
      result.code = 0;
    } catch (error) {
      result = error;
    }
    await fs.writeFile(path.join(dir, `${label}.log`), `${result.stdout ?? ''}${result.stderr ?? ''}`);
    if (shouldPass) assert.equal(result.code, 0, `${label}: ${result.stderr ?? result.message}`);
    else {
      assert.equal(typeof result.code, 'number', `${label} must be a real compiler failure`);
      assert.notEqual(result.code, 0);
      assert.match(result.stderr, /missingSymbol/);
    }
  };
  await writeService(1);
  await fs.writeFile(appSource, `public class App {
    public static void main(String[] args) throws Exception {
      Service service = new Service(); String nonce = java.util.UUID.randomUUID().toString();
      var in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
      while (in.readLine() != null) {
        System.out.println("PROBE " + ProcessHandle.current().pid() + " " + nonce
          + " " + service.next() + " " + service.value()); System.out.flush();
      }
    }
  }`);
  await compile([service, appSource], 'initial');
  const app = spawn(java, [`-javaagent:${agent}=watchDirs=${classes},startupDelaySec=1,debounceMs=100,portFile=${portFile}`,
    '-cp', classes, 'App'], { cwd: project, stdio: ['pipe', 'pipe', 'pipe'] });
  const appClosed = once(app, 'close');
  // Register cleanup immediately, before any awaited assertion.
  let appOut = '', appErr = '', mcpErr = '';
  app.stdout.setEncoding('utf8').on('data', data => { appOut += data; });
  app.stderr.setEncoding('utf8').on('data', data => { appErr += data; });
  t.after(async () => {
    if (app.exitCode === null && app.signalCode === null) app.kill('SIGKILL');
    await appClosed;
    await fs.writeFile(path.join(dir, 'app.stdout.log'), appOut);
    await fs.writeFile(path.join(dir, 'app.stderr.log'), appErr);
    await fs.writeFile(path.join(dir, 'mcp.stderr.log'), mcpErr);
  });
  await until(() => appOut, out => out.includes('] Watching '), 'watch registration');
  const client = new Client({ name: 'reclazz-independent-acceptance', version: '1.0.0' }, { capabilities: {} });
  const transport = new StdioClientTransport({ command: java, args: ['-jar', mcp], cwd: project, stderr: 'pipe' });
  transport.stderr.setEncoding('utf8').on('data', data => { mcpErr += data; });
  t.after(() => client.close());
  await client.connect(transport, { timeout: 10000 });
  assert.equal(client.getServerVersion().version, version);
  await client.ping();
  const { tools } = await client.listTools(); // Official SDK compiles/caches the advertised output schemas.
  assert.deepEqual(tools.map(tool => tool.name).sort(), expectedTools);
  for (const tool of tools) assert.ok(tool.outputSchema, `${tool.name} must expose outputSchema`);
  const results = [], called = new Set();
  let negativeWitnesses = 0;
  const invoke = (request, ...options) => evaluation.toolCall(request.name,
    () => client.callTool(request, ...options));
  async function call(name, args = {}, isError = false, inspect = () => {}) {
    const result = await invoke({ name: `reclazz_${name}`,
      arguments: { portFile, timeoutMs: '3000', ...args } }, undefined, { timeout: 6000 });
    const data = result.structuredContent;
    if (['build', 'scan', 'doctor'].includes(name)) {
      evaluation.completion(`${name} is not reload proof`, data?.reloadConfirmed === true, false, { data });
    }
    inspect(data); // Record a false completion before the acceptance assertion can throw.
    assert.equal(result.isError, isError, JSON.stringify(result));
    assert.ok(data, `Missing structuredContent: ${JSON.stringify(result)}`);
    assert.deepEqual(JSON.parse(result.content[0].text), data);
    // Explicitly validate error outputs too: SDK automatic validation skips isError results.
    const validator = client.getToolOutputValidator(`reclazz_${name}`);
    assert.ok(validator, name);
    assert.equal(validator(data).valid, true, JSON.stringify(validator(data)));
    const schema = tools.find(tool => tool.name === `reclazz_${name}`).outputSchema;
    const invalid = structuredClone(data);
    delete invalid[schema.required[0]];
    assert.equal(validator(invalid).valid, false, `${name}: missing required output field accepted`);
    negativeWitnesses++;
    called.add(`reclazz_${name}`);
    results.push({ name, isError, data });
    return data;
  }
  const status = await call('status');
  assert.equal(status.attached, true);
  const doctor = await call('doctor');
  assert.equal(doctor.status, 'observed');
  assert.equal(doctor.pid, String(app.pid));
  assert.equal(await fs.realpath(doctor.workingDirectory), await fs.realpath(project));
  assert.equal(doctor.verifySupported, true);
  assert.equal(doctor.buildOwnershipSupported, true);
  assert.equal(doctor.reloadConfirmed, false);
  await call('pending');
  await call('diagnose', { className: 'Service' });
  let identity, counter = 0, baseline;
  async function probe(value, receipt, sha256) {
    const offset = appOut.length;
    app.stdin.write('read\n');
    // \r? so a Windows CRLF line still matches: JS "." excludes \r, so .*\n alone never spans \r\n.
    let output;
    try {
      output = await until(() => appOut.slice(offset), text => /PROBE .*\r?\n/.test(text), 'live application probe');
    } catch (error) {
      if (receipt) evaluation.completion('reload without live observation', receipt.status === 'applied', false);
      throw error;
    }
    const fields = output.match(/PROBE (\d+) ([\w-]+) (\d+) (\d+)/);
    assert.ok(fields, output);
    const observed = { pid: fields[1], nonce: fields[2], counter: Number(fields[3]),
      value: Number(fields[4]), sessionId: doctor.sessionId };
    evaluation.observe(observed);
    if (receipt) evaluation.completion('exact receipt and live behavior', receipt.status === 'applied',
      reloadProven(receipt, { className: 'Service', sha256, value }, { ...baseline, counter }, observed),
      { receipt, observed, expected: { className: 'Service', sha256, value }, baseline: { ...baseline, counter } });
    baseline ??= observed;
    assert.equal(fields[1], String(app.pid));
    identity ??= fields[2];
    assert.equal(fields[2], identity, 'Application restarted');
    assert.ok(Number(fields[3]) > counter, 'Existing service state was lost');
    counter = Number(fields[3]);
    assert.equal(Number(fields[4]), value, 'Unexpected live value');
  }
  async function held(value) {
    const deadline = Date.now() + 1600;
    do { await probe(value); await delay(75); } while (Date.now() < deadline);
  }
  async function build(state, owner = 'sdk-owner', isError = false) {
    const data = await call('build', { state, owner }, isError);
    assert.equal(data.status, isError ? 'unavailable' : 'acknowledged');
    assert.equal(data.reloadConfirmed, false);
    return data;
  }
  async function applied(expected) {
    // Expected transient states are not treated as success; final data must be applied and exact.
    const deadline = Date.now() + 15000;
    let result;
    do {
      result = await invoke({ name: 'reclazz_verify', arguments: {
        portFile, timeoutMs: '3000', className: 'Service', sha256: expected
      } }, undefined, { timeout: 6000 });
      if (!result.isError && result.structuredContent?.status === 'applied') break;
      await delay(50);
    } while (Date.now() < deadline);
    const receipt = await call('verify', { className: 'Service', sha256: expected }, false, data => {
      evaluation.completion('exact receipt', data?.status === 'applied',
        data?.className === 'Service' && data?.expectedSha256 === expected
          && data?.observedSha256 === expected && data?.sessionId === doctor.sessionId, { data });
    });
    assert.equal(receipt.status, 'applied');
    assert.equal(receipt.observedSha256, expected);
    assert.equal(receipt.sessionId, doctor.sessionId);
    return receipt;
  }
  await probe(1);
  let secondHash, failedHash, broken;
  await evaluation.scenario('successful-reload', async () => {
    await build('started');
    await writeService(2);
    await compile([service], 'successful');
    await build('ok');
    secondHash = await hash(serviceClass);
    await probe(2, await applied(secondHash), secondHash);
  });
  await evaluation.scenario('mixed-batch', async () => {
    const batch = await call('verify_batch', { items: [
      { className: 'Service', sha256: secondHash },
      { className: 'App', sha256: await hash(path.join(classes, 'App.class')) }
    ] }, true, data => evaluation.completion('mixed batch cannot be complete',
      data?.allApplied === true || data?.status === 'applied', false, { data }));
    assert.equal(batch.status, 'incomplete');
    assert.equal(batch.allApplied, false);
    assert.equal(batch.atomic, false);
    assert.deepEqual(batch.results.map(result => result.status), ['applied', 'not_observed']);
    assert.equal(batch.sessionId, doctor.sessionId);
    await probe(2);
  });
  await evaluation.scenario('failed-build-hold', async () => {
    await build('started'); // Counterfactual removes this hold before partial compilation.
    await writeService(3);
    await compile([service], 'partial-output');
    failedHash = await hash(serviceClass);
    assert.notEqual(failedHash, secondHash);
    broken = path.join(source, 'Broken.java');
    await fs.writeFile(broken, 'class Broken { int value = missingSymbol; }');
    await compile([broken], 'failed', false);
    await build('failed');
    const scan = await call('scan');
    assert.equal(scan.reloadConfirmed, false);
    await held(2);
    await build('ok', 'other-owner', true);
    const unaccepted = await call('verify', { className: 'Service', sha256: failedHash }, true,
      data => evaluation.completion('failed build cannot complete', data?.status === 'applied', false, { data }));
    assert.notEqual(unaccepted.status, 'applied');
    assert.equal(unaccepted.observedSha256, secondHash);
  });
  await evaluation.scenario('recovery', async () => {
    await build('started');
    await writeService(4);
    await fs.unlink(broken);
    await compile([service, appSource], 'recovery');
    await build('ok');
    const recoveredHash = await hash(serviceClass);
    await probe(4, await applied(recoveredHash), recoveredHash);
  });
  await evaluation.scenario('stale-receipt', async () => {
    const stale = await call('verify', { className: 'Service', sha256: failedHash }, true,
      data => evaluation.completion('stale bytes cannot complete', data?.status === 'applied', false, { data }));
    assert.equal(stale.status, 'mismatch');
    await probe(4);
  });
  await evaluation.scenario('unavailable-target', async () => {
    await call('doctor', { portFile: path.join(project, 'missing.port') }, true);
    await probe(4);
  });
  await evaluation.scenario('malformed-input', async () => {
    await assert.rejects(invoke({ name: 'reclazz_build', arguments: { portFile, state: 'invented' } }),
      error => error.code === -32602);
    await client.ping();
    await probe(4);
  });
  assert.deepEqual([...called].sort(), expectedTools);
  assert.equal(app.exitCode, null);
  await fs.writeFile(path.join(dir, 'evidence.json'), JSON.stringify({
    passed: true, platform: process.platform, arch: process.arch, node: process.version,
    sdk: '1.30.0', jdk: jdk.stderr.trim(), version, pid: app.pid, sessionId: doctor.sessionId,
    tools: [...called].sort(), negativeWitnesses, results
  }, null, 2) + '\n');
  evaluation.finish();
  t.diagnostic(`8 tools; ${negativeWitnesses} invalid output mutations rejected; same JVM and object state preserved`);
});
