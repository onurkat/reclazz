// Copyright 2026 Onur Kat
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';

export const scenarios = ['successful-reload', 'mixed-batch', 'failed-build-hold',
  'recovery', 'stale-receipt', 'unavailable-target', 'malformed-input'];

// The oracle requires both the exact receipt and independent application evidence.
export function reloadProven(receipt, expected, baseline, observed) {
  return Boolean(expected?.className && /^[a-f0-9]{64}$/.test(expected.sha256)
    && baseline?.pid && baseline?.nonce && baseline?.sessionId
    && Number.isInteger(baseline.counter) && Number.isInteger(expected.value)
    && receipt?.status === 'applied' && receipt.className === expected.className
    && receipt.expectedSha256 === expected.sha256 && receipt.observedSha256 === expected.sha256
    && receipt.sessionId === baseline.sessionId
    && observed?.pid === baseline.pid && observed.nonce === baseline.nonce
    && observed.sessionId === baseline.sessionId && observed.counter > baseline.counter
    && observed.value === expected.value);
}

const bucket = name => ({ name, status: 'not_run', elapsedMs: 0, toolCalls: 0,
  callsByTool: {}, observations: [], restarts: 0, completionChecks: [] });

export class Evaluation {
  constructor(names = scenarios, now = () => performance.now()) {
    assert.equal(new Set(names).size, names.length, 'Duplicate scenarios');
    assert.ok(names.length, 'An evaluation needs scenarios');
    this.now = now;
    this.started = now();
    this.setup = bucket('setup');
    delete this.setup.status;
    delete this.setup.elapsedMs;
    this.rows = names.map(bucket);
    this.active = null;
    this.previous = null;
    this.completed = false;
  }

  async toolCall(name, operation) {
    const row = this.active ?? this.setup;
    row.toolCalls++;
    row.callsByTool[name] = (row.callsByTool[name] ?? 0) + 1;
    return operation(); // Count attempted calls even if SDK validation or the transport throws.
  }

  observe(observation) {
    const row = this.active ?? this.setup;
    assert.ok(observation.pid && observation.nonce && observation.sessionId, 'Missing target identity');
    if (this.previous && ['pid', 'nonce', 'sessionId'].some(key => observation[key] !== this.previous[key])) {
      row.restarts++;
    }
    row.observations.push({ ...observation });
    this.previous = { ...observation };
  }

  completion(label, claimed, proven, evidence = {}) {
    assert.equal(typeof claimed, 'boolean');
    assert.equal(typeof proven, 'boolean');
    (this.active ?? this.setup).completionChecks.push({ label, claimed, proven,
      falseCompletion: claimed && !proven, evidence });
  }

  async scenario(name, operation) {
    assert.equal(this.active, null, 'Scenarios must run sequentially');
    const row = this.rows.find(item => item.name === name);
    assert.ok(row && row.status === 'not_run', `Unknown or repeated scenario: ${name}`);
    this.active = row;
    row.status = 'running';
    const start = this.now();
    try {
      await operation();
      assert.equal(row.completionChecks.some(check => check.falseCompletion), false, 'False completion');
      assert.equal(row.restarts, 0, 'Application identity changed');
      row.status = 'passed';
    } catch (error) {
      row.status = 'failed';
      row.error = String(error.stack ?? error);
      throw error;
    } finally {
      row.elapsedMs = this.now() - start;
      this.active = null;
    }
  }

  finish() {
    assert.ok(this.rows.every(row => row.status === 'passed'), 'Scenarios incomplete');
    assert.equal(this.setup.completionChecks.some(check => check.falseCompletion), false, 'False setup completion');
    assert.equal(this.setup.restarts, 0, 'Target changed during setup');
    this.completed = true;
    this.elapsedMs = this.now() - this.started;
  }

  report(metadata = {}) {
    const buckets = [this.setup, ...this.rows];
    const checks = buckets.flatMap(row => row.completionChecks);
    const completedClaims = checks.filter(check => check.claimed).length;
    const falseCompletions = checks.filter(check => check.falseCompletion).length;
    const succeeded = this.rows.filter(row => row.status === 'passed').length;
    return { schemaVersion: 1, kind: 'deterministic-workflow', metadata,
      passed: this.completed, complete: this.completed,
      summary: { planned: this.rows.length, succeeded,
        failed: this.rows.filter(row => row.status === 'failed').length,
        notRun: this.rows.filter(row => row.status === 'not_run').length,
        successRate: succeeded / this.rows.length, completedClaims, falseCompletions,
        falseCompletionRate: completedClaims ? falseCompletions / completedClaims : null,
        restarts: buckets.reduce((sum, row) => sum + row.restarts, 0),
        identityObservations: buckets.reduce((sum, row) => sum + row.observations.length, 0),
        toolCalls: buckets.reduce((sum, row) => sum + row.toolCalls, 0),
        elapsedMs: this.elapsedMs ?? this.now() - this.started },
      setup: structuredClone(this.setup), scenarios: this.rows.map(row => ({ ...structuredClone(row),
        completedClaims: row.completionChecks.filter(check => check.claimed).length,
        falseCompletions: row.completionChecks.filter(check => check.falseCompletion).length })) };
  }
}
