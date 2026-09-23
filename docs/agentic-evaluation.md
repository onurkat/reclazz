# Reproducible agentic workflow evaluation

This is a fixed, deterministic evaluation of the Reclazz tool workflow. It uses
actual compiled class files, the packaged MCP server, the official MCP SDK and a
persistent application JVM. It does **not** measure an LLM, certify an AI desktop
client or estimate success rates on arbitrary user tasks. No model API, account
or extra dependency is required.

## Run

From the repository root, set `JAVA_HOME` to the JDK you want to measure, then:

```sh
./gradlew :agent:shadowJar :mcp-server:mcpRelease
npm --prefix mcp-server/src/test/client ci --ignore-scripts --no-audit --no-fund
npm --prefix mcp-server/src/test/client test
```

Use `gradlew.bat` on Windows. Node 22 and a complete JDK with `javac` are required.
The private test project pins SDK 1.30.0. `npm test` runs the evaluation recorder's
negative tests first, then the live acceptance fixture, sequentially. To run only
the recorder tests, use `npm --prefix mcp-server/src/test/client run test:evaluation`.
The existing Linux/Windows JDK17 client CI steps also run and upload this report;
a configured lane is not evidence that a particular revision passed there.

Each run creates a fresh `mcp-server/build/client-matrix/<platform>-<random>/`
directory. It retains compiler/application logs and writes **`evaluation.json`**
with scenario measurements. Ordinary setup/assertion failures also produce a
non-passing report after the directory and report hook have been created. Failure
before that point, process termination, a machine crash or an unwritable disk
cannot guarantee a report. A missing report is never success.

The existing `evidence.json` remains the acceptance-only success artifact.
Read the current run's exit status and `evaluation.json.passed`; do not reuse an
older report. Reports include OS/architecture, Node/JDK/SDK/product versions,
Java executable paths and SHA-256 digests of the two installed jars (when setup
reached that point). Compare runs using the same artifacts and environment.

## Fixed scenarios

The seven scenarios run in this order in one JVM; recovery deliberately depends
on the failed build. A failed prerequisite aborts the flow and leaves subsequent
scenarios `not_run`, rather than shrinking the denominator.

| Scenario | Required outcome |
| --- | --- |
| `successful-reload` | Owned build, actual compile, exact v2 receipt and live v2 behavior in the original JVM with retained service state |
| `mixed-batch` | Service applied, App unobserved; batch remains incomplete and non-atomic |
| `failed-build-hold` | Partial v3 output plus real compiler failure remains unapplied through SCAN; competing owner cannot release it |
| `recovery` | Original owner completes a fresh v4 build; exact receipt and live v4 behavior agree |
| `stale-receipt` | The old v3 hash cannot be claimed as applied after v4 |
| `unavailable-target` | Missing port file is an error, never proof of reload; original application stays alive |
| `malformed-input` | Invalid build state is rejected; MCP ping and original application remain responsive |

## What the numbers mean

- **Success:** all assertions in a named scenario passed, no false completion
  check fired and no observed target identity change occurred. The summary's
  `successRate` is passed scenarios divided by all seven planned scenarios.
  It is coverage of these fixed cases, not a statistical model success estimate.
  Overall `passed`/`complete` requires the final acceptance checks as well.
- **False completion:** a checked positive completion claim lacks its required
  evidence. BUILD, SCAN and DOCTOR cannot prove reload; a failed/stale request
  or mixed batch cannot count as complete. Positive VERIFY needs the requested
  class/hash and original session; an additional independent check requires
  matching live value, PID, startup nonce and advancing service counter.
  `completionChecks` retains claims, proof verdicts and supporting observations.
  `falseCompletions` counts failed claim checks, not unique tasks. A receipt and
  its live-behavior check are separate checks. `falseCompletionRate` divides by
  checked positive claims, and is `null` when there were none. Expected negative
  responses are successful safety outcomes, not false completions. Polls count
  as calls; the terminal receipt is scored for completion, not every poll.
- **Restarts:** observed changes of PID, startup nonce or target session between
  identity observations. The first launch is excluded; changing several identity
  fields together counts once. The fixture never intentionally restarts the JVM.
  Live probes use the initial DOCTOR session; subsequent receipts are checked
  against it separately. Missing probes fail the scenario: zero observed restarts
  on an incomplete run does not establish continuity. The recorded observation
  count and observations delimit what was actually seen.
- **Calls:** SDK `tools/call` attempts, including verification polling, expected
  errors and calls that throw. Per-scenario counts and `callsByTool` are separate
  from discovery/setup calls; the summary includes both. Initialize, ping,
  listTools, compilation and live application probes are not tool calls.
- **Elapsed time:** monotonic milliseconds per scenario, including its compilation,
  polling and deliberate hold-observation window. Total time starts after the
  evidence directory is created and includes setup and final acceptance work,
  excluding teardown and report writing. These are observations, not latency
  guarantees; there is no speed ranking or performance threshold. Existing
  bounded timeouts still fail a stalled test.

Recorder tests prove that wrong hashes, sessions, class names, live values,
identity and lost state cannot establish completion; falsely claiming success
fails a scenario. They also check rejected-call accounting, elapsed intervals,
restart counting and preservation of failed/unrun scenarios. They do not replace
the actual JVM run. Existing acceptance assertions are retained.

This evaluation reuses the [client acceptance fixture](client-matrix.md) rather
than adding a second build harness. Native Gradle/Maven two-module coverage,
framework coverage and interactive client coverage retain the separate limits
documented there. This task adds no production protocol or reload behavior.
