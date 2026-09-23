// Copyright 2026 Onur Kat
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import test from 'node:test';
import { Evaluation, reloadProven } from './evaluation.mjs';

const baseline = { pid: '123', nonce: 'original-object', sessionId: 'session-1', counter: 1, value: 1 };
const observed = { ...baseline, counter: 2, value: 2 };
const expected = { className: 'Service', sha256: 'a'.repeat(64), value: 2 };
const receipt = { status: 'applied', className: 'Service', expectedSha256: expected.sha256,
  observedSha256: expected.sha256, sessionId: 'session-1' };

test('completion needs exact bytes, target identity, retained state and live behavior', () => {
  assert.equal(reloadProven(receipt, expected, baseline, observed), true);
  for (const change of [{ status: 'acknowledged' }, { className: 'Other' },
    { expectedSha256: 'b'.repeat(64) }, { observedSha256: 'b'.repeat(64) }, { sessionId: 'stale' }]) {
    assert.equal(reloadProven({ ...receipt, ...change }, expected, baseline, observed), false, JSON.stringify(change));
  }
  for (const change of [{ pid: '456' }, { nonce: 'replacement' }, { sessionId: 'new-session' },
    { counter: 1 }, { value: 1 }]) {
    assert.equal(reloadProven(receipt, expected, baseline, { ...observed, ...change }), false, JSON.stringify(change));
  }
  assert.equal(reloadProven(undefined, expected, baseline, observed), false);
  assert.equal(reloadProven(receipt, expected, baseline, undefined), false);
  assert.equal(reloadProven(receipt, {}, {}, {}), false);
});

test('false applied claim fails the scenario even if the workflow returns normally', async () => {
  const evaluation = new Evaluation(['reload', 'later']);
  await assert.rejects(evaluation.scenario('reload', async () => {
    evaluation.completion('receipt + behavior', true,
      reloadProven(receipt, expected, baseline, { ...observed, value: 1 }));
  }), /False completion/);
  const report = evaluation.report();
  assert.equal(report.passed, false);
  assert.equal(report.summary.falseCompletions, 1);
  assert.equal(report.summary.falseCompletionRate, 1);
  assert.equal(report.summary.failed, 1);
  assert.equal(report.summary.notRun, 1);
  assert.equal(report.summary.successRate, 0);
  assert.throws(() => evaluation.finish(), /incomplete/);
});

test('counts polls and rejected attempts; excludes setup from scenario time and includes it in total', async () => {
  let clock = 0;
  const evaluation = new Evaluation(['reload'], () => clock);
  await evaluation.toolCall('doctor', async () => {});
  evaluation.observe(baseline);
  clock = 10;
  await evaluation.scenario('reload', async () => {
    await assert.rejects(evaluation.toolCall('verify', async () => { throw Error('offline'); }), /offline/);
    await evaluation.toolCall('verify', async () => {});
    evaluation.observe(observed);
    evaluation.completion('not ready yet', false, false);
    evaluation.completion('receipt + behavior', true, reloadProven(receipt, expected, baseline, observed));
    clock = 35;
  });
  evaluation.finish();
  clock = 50;
  const report = evaluation.report();
  assert.equal(report.passed, true);
  assert.equal(report.summary.toolCalls, 3);
  assert.equal(report.setup.toolCalls, 1);
  assert.deepEqual(report.scenarios[0].callsByTool, { verify: 2 });
  assert.equal(report.scenarios[0].elapsedMs, 25);
  assert.equal(report.summary.elapsedMs, 35);
  assert.equal(report.summary.successRate, 1);
  assert.equal(report.summary.completedClaims, 1);
  assert.equal(report.summary.falseCompletionRate, 0);
  assert.equal(report.summary.restarts, 0);
  assert.equal(report.summary.identityObservations, 2);
});

test('one changed target counts once, never counts the initial launch, and fails the scenario', async () => {
  for (const change of [{ pid: '456' }, { nonce: 'replacement' }, { sessionId: 'replacement' },
    { pid: '456', nonce: 'replacement', sessionId: 'replacement' }]) {
    const evaluation = new Evaluation(['reload']);
    evaluation.observe(baseline);
    await assert.rejects(evaluation.scenario('reload', async () => {
      evaluation.observe({ ...observed, ...change });
      evaluation.observe({ ...observed, ...change, counter: 3 });
    }), /identity changed/);
    assert.equal(evaluation.report().summary.restarts, 1);
  }
});

test('setup failure and failed assertions cannot turn unrun scenarios into success', async () => {
  const evaluation = new Evaluation(['first', 'second']);
  assert.equal(evaluation.report().passed, false);
  assert.equal(evaluation.report().summary.notRun, 2);
  assert.equal(evaluation.report().summary.falseCompletionRate, null);
  assert.equal(evaluation.report().summary.identityObservations, 0);
  await assert.rejects(evaluation.scenario('first', async () => { throw Error('compiler timed out'); }), /timed out/);
  assert.match(evaluation.report().scenarios[0].error, /compiler timed out/);
  assert.equal(evaluation.report().summary.notRun, 1);
  assert.equal(evaluation.report().summary.falseCompletions, 0);
  assert.throws(() => evaluation.finish(), /incomplete/);
});
