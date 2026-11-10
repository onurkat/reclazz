# MCP for coding agents: close the loop

Your coding agent can edit a Java service and finish a build. Reclazz's MCP
tools help it answer the next questions: Which JVM is running? Were the changed
bytes applied? Is something held or waiting for a restart? What should it check
before telling you the change works?

Keep the application running while the agent gathers that evidence. The bridge
exposes eleven tools, read-only resources and a verification prompt through
stdio, backed by the attached Reclazz agent's local status socket. Use the
[MCP reference](mcp-server.md) for installation, full schemas and target selection. This page explains the developer benefits.

Examples below come from recorded local acceptance through **10 November 2026**.
Tool requests are shown as an MCP tool name and argument object, not a shell
command. The resource and prompt examples identify their method and parameters.
Results show selected fields or selected array entries, not complete responses.
Uppercase session, cursor, path, hash and owner values are placeholders to replace
with evidence from your own run. Rendering this page does not rerun acceptance.

## Find the right running application

An agent can compile successfully while talking to the wrong server. Start with
`reclazz_status` for attachment and health, then `reclazz_doctor` for JVM identity,
session, launch directory, capabilities and watcher evidence.

```json
{
  "name": "reclazz_doctor",
  "arguments": {
    "timeoutMs": "1500"
  }
}
```

Recorded result excerpt:

```json
{
  "status": "observed",
  "watcherState": "watching",
  "buildHold": "none",
  "verifySupported": true,
  "reloadConfirmed": false
}
```

Compare the returned PID, session and working directory with the application
you intended. With an explicit project, the server checks that connection's
DOCTOR launch-directory evidence before sending the requested command.

**Limit:** Attachment and a healthy watcher do not prove that an edit reloaded.
A sampled directory list can be incomplete; a working directory is not a
repository-root assertion. See [target checks](mcp-server.md#target-selection-and-overrides).

## Replace a guessed wait with a bounded result

After saving, an agent needs more than a delay or an increasing reload counter.
`reclazz_await_reload` creates a checkpoint before the edit, then waits for
qualifying received events and an exact-owner byte receipt.

First call `reclazz_await_reload` with `action:"checkpoint"` and require
`status:"ready"`. Save only after that reply. Use its session, subscription and
cursor in the wait:

```json
{
  "name": "reclazz_await_reload",
  "arguments": {
    "action": "wait",
    "sessionId": "SESSION_FROM_CHECKPOINT",
    "subscriptionId": "STREAM_FROM_CHECKPOINT",
    "afterCursor": "CURSOR_FROM_CHECKPOINT",
    "filePath": "ABSOLUTE_SOURCE_PATH",
    "ownerClassName": "com.example.demo.HelloController",
    "timeoutMs": "10000"
  }
}
```

Recorded result excerpt:

```json
{
  "status": "applied",
  "reloadConfirmed": true,
  "correlationScope": "received_events",
  "sourcePathConfirmed": false,
  "editRevisionConfirmed": false
}
```

Recorded acceptance also exercised `compile_error`, `refused`,
`restart_required` and `held`, so an agent can branch on a failed compile,
refusal, hierarchy limit or blocked admission. `timeout` and `unavailable`
remain explicit outcomes, never success by silence.

**Limit:** Received events do not identify an exact editor revision or full
source path. A matching basename can be ambiguous. Require the explicit binary
owner in file mode; read the receipt and still exercise the application.
See [checkpoint and wait](mcp-server.md#waiting-for-a-reload).

## Prove the compiled bytes before claiming completion

A successful compiler exit does not show which bytes the live JVM accepted.
Hash the changed `.class` file and ask `reclazz_verify` for that binary class
and SHA-256. Use `reclazz_verify_batch` for several class/hash pairs on one
connection; inspect every result.

```json
{
  "name": "reclazz_verify",
  "arguments": {
    "className": "com.example.demo.HelloController",
    "sha256": "SHA256_OF_YOUR_CLASS_FILE",
    "timeoutMs": "1500"
  }
}
```

Recorded result excerpt:

```json
{
  "status": "applied",
  "className": "com.example.demo.HelloController",
  "detail": "method bodies"
}
```

Require `isError:false`, `status:"applied"`, the expected session and matching
expected/observed hashes. A recorded batch result had `allApplied:true` while
still reporting `atomic:false` and `reloadConfirmed:false`: its individual
receipts are evidence, not an atomic application transaction.

**Limit:** A byte receipt is not proof of the feature's behavior, source revision
or successful database work. Make the HTTP, UI or application test the last step.
See [exact-byte verification](mcp-server.md#verifying-the-compiled-change).

## Inspect several edits without reading a log by eye

When an agent edits several files, `reclazz_events` gives it bounded, ordered
pages from a checkpoint. Filter by level, exact owner or file basename to
inspect the relevant observations.

```json
{
  "name": "reclazz_events",
  "arguments": {
    "sessionId": "SESSION_FROM_CHECKPOINT",
    "subscriptionId": "STREAM_FROM_CHECKPOINT",
    "sinceCursor": "0",
    "limit": "100",
    "timeoutMs": "10000"
  }
}
```

Recorded result excerpt, showing the first two of eleven returned events:

```json
{
  "status": "observed",
  "nextCursor": "11",
  "hasMore": false,
  "reloadConfirmed": false,
  "events": [
    {
      "sequence": "1",
      "type": "compile",
      "fileBasename": "HelloController.java"
    },
    {
      "sequence": "2",
      "type": "reload",
      "ownerClassName": "com.example.demo.HelloController"
    }
  ]
}
```

Feed back `nextCursor` to continue. Filters apply to each event together; an
empty filtered page can advance over inspected nonmatches. Cursor eviction
is reported as unavailable instead of returning a misleading partial history.

**Limit:** This is bounded received-event history, not a durable attempt journal
or a full-path/revision tracker. Event order does not prove an atomic batch.
An observed reload is not confirmed application behavior; use await/VERIFY and
your own application oracle. See [event pages](mcp-server.md#inspecting-a-batch-of-events).

## Keep partial builds out of the reload queue

A compiler can write some classes before failing on another file. Start an
owned BUILD hold before compilation so that a failed build does not authorize
that partial output. The same owner reports `ok` only after the complete build
succeeds, or `failed` when it does not.

```json
{
  "name": "reclazz_build",
  "arguments": {
    "state": "started",
    "owner": "YOUR_UNIQUE_BUILD_OWNER",
    "timeoutMs": "1500"
  }
}
```

Recorded result excerpt:

```json
{
  "status": "acknowledged",
  "state": "started",
  "reloadConfirmed": false
}
```

The build hold also gates automatic source/resource reload actions. The live
SAP acceptance retained the previous HTTP/class behavior under both started
and failed holds, rejected a competing owner, then applied the latest source
after the original owner's successful build. The
[terminal build wrapper](mcp-server.md#protecting-terminal-builds) manages these
signals around the complete compile command.

**Limit:** An acknowledgement is not reload completion. SCAN or resume cannot
release the hold. Retain the owner token for recovery; do not send `ok` just
to clear a warning. Already admitted work can finish, and framework refresh
and external side effects are not transactional.

## Stage changes while keeping the application available

Use `reclazz_reload_control` to pause automatic reload admission while preparing
several edits. The application keeps serving requests. Resume when the changes
are ready, then obtain receipts and check behavior.

```json
{
  "name": "reclazz_reload_control",
  "arguments": {
    "action": "pause",
    "timeoutMs": "1500"
  }
}
```

Recorded result excerpt:

```json
{
  "status": "acknowledged",
  "action": "pause",
  "paused": true,
  "buildHold": "none",
  "reloadConfirmed": false
}
```

`action:"status"` reads the current admission state; `action:"resume"` resumes
it. Independent client acceptance checked held output, resume, exact-byte
verification and changed behavior in the same application JVM.

**Limit:** Pause does not freeze requests, roll back state or stop already
admitted work. Resume does not unlock a failed BUILD hold. See
[pause and resume](mcp-server.md#pause-and-resume-automatic-reload).

## Give the agent a concrete next diagnostic

An unchanged response should lead to an investigation, not another optimistic
completion message. `reclazz_pending` exposes the restart ledger and admission
state. `reclazz_diagnose` describes the last known outcome for a named class.

```json
{
  "name": "reclazz_diagnose",
  "arguments": {
    "className": "com.example.demo.HelloController",
    "timeoutMs": "1500"
  }
}
```

Recorded result excerpt, selecting the actionable diagnostic line:

```json
{
  "status": "observed",
  "className": "com.example.demo.HelloController",
  "complete": false,
  "lines": [
    "Reclazz has not attempted a reload of this class, and the compiled file has not been rebuilt since this JVM started. The build did not reach it."
  ]
}
```

That result directs the agent back to compiler output rather than a server
restart. A pending restart requirement calls for its specific recovery instead.

**Limit:** Pending/diagnose use a legacy broadcast diagnostic stream with no
response-end marker. `complete:false` is deliberate: the lines are observations,
not a guaranteed complete or exclusively request-owned response. See
[diagnostic errors](mcp-server.md#tool-execution-errors).

## Use the same evidence loop for Spring Boot and SAP Commerce

The tools speak to the running Reclazz agent, so the workflow does not depend
on an IDE. Recorded acceptance used both a Spring Boot sample and a clean,
licensed SAP Commerce 2211 installation with synthetic fixtures.

For example, `reclazz_doctor` returned `status:"observed"` and
`verifySupported:true` on both targets. Applied byte receipts were followed by
real HTTP assertions. Await acceptance exercised eighteen outcomes per target;
event-page acceptance passed fifteen checks per target, including restored
application baselines. These are recorded runs, not new runs of this page.

**Limit:** Shared tools do not make every framework edit reloadable. Type-system
updates, schema changes, framework refresh and restart requirements retain their
own conditions. SAP events in this tool are not a dedicated SAP operation
receipt API. See [live SAP scope](sap-commerce-live-tests.md) and
[reload limits](usage.md#known-limitations).

## Connect the coding client you use

Claude Code and Codex CLI each discovered all eleven tools and made one real
`reclazz_status` call against the Spring sample in recorded local acceptance:

```json
{
  "name": "reclazz_status",
  "arguments": {}
}
```

Recorded result excerpt:

```json
{
  "attached": true,
  "agent": "1.5.0",
  "protocol": 1
}
```

Use the [Claude Code setup](mcp-server.md#claude-code),
[Codex TOML entry](mcp-server.md#codex) or
[Cursor configuration](mcp-server.md#cursor). The
[client matrix](client-matrix.md#installed-coding-clients) records exact versions,
instrumentation and which configuration route was exercised.

**Limit:** Cursor setup is documented, not verified. Its desktop installation
did not provide an installed non-GUI Agent CLI for that acceptance. The two
CLI results do not certify desktop GUIs, other operating systems or every tool
in each client. Client permissions and provider authentication remain separate.

## Read context and choose a verification workflow

A coding client may want attachment or restart context as a resource, or a
reusable prompt before the next edit. `reclazz://status` and `reclazz://pending`
provide the same diagnostic JSON as the corresponding tools. The event URI
template shares the tools' existing checkpoint and bounded event history.

For example, packaged SDK acceptance used these `resources/read` parameters:

```json
{
  "uri": "reclazz://status"
}
```

Recorded attachment fields decoded from the resource's `contents[0].text`:

```json
{
  "attached": true,
  "agent": "1.5.0",
  "protocol": 1
}
```

This is a projection of the recorded result, not its port and full health data.
Resource reads stay on the server's startup target; a URI cannot select another
project or port. A successful read can contain unavailable evidence, so inspect
its JSON before treating it as useful context.

Clients can also retrieve `verify_my_last_change` through `prompts/get`:

```json
{
  "name": "verify_my_last_change"
}
```

The prompt guides doctor -> checkpoint before a new edit -> real BUILD signals
for external compilation -> await reload -> verify the bytes -> check the
application. It explains already-saved edits and tells the agent never to send
BUILD `ok` merely to clear a hold. Fetching the prompt executes none of those
steps and grants no permission to perform them.

**Limit:** The client decides how to surface these capabilities. Recorded
Claude Code acceptance listed both resources and the prompt, but did not request
the template, read a resource or retrieve the prompt in its constrained run.
The official SDK separately exercised those operations. Event resources remain
observations, not byte receipts, source-revision proof or application tests.
Tools-only clients keep the same eleven tools. See
[resources and prompts](mcp-server.md#read-only-resources) for selectors, errors
and exact client coverage.

## Keep the Reclazz bridge local

The transport is `coding client ↔ stdio MCP server ↔ 127.0.0.1 status socket`.
The MCP server has no hosted endpoint or telemetry; the agent opens its status
server on loopback and does not dial out. The bridge does not require a cloud
account to contact the application.

For example, the local status request above returned the agent's attachment
and health. It did not need to upload source to a Reclazz service. The
[agent network guard](../agent/src/test/java/com/onurkat/reclazz/EverythingStaysOnThisMachineTest.java)
checks built agent calls, while the bridge's
[socket client](../mcp-server/src/main/java/com/onurkat/reclazz/mcp/AgentSocket.java)
explicitly selects loopback.

**Limit:** Your coding client can contact its model provider and send tool
results there. Local Reclazz transport is not a claim that the whole AI
workflow stays offline. Loopback is not authentication against other local
processes. Mutating tools can trigger application callbacks and external side
effects; inspect their conservative hints and require appropriate permissions.

## Follow the proof, then check your application

The documented behavior is present in matching locally built agent/MCP 1.5.0.
Source presence does not establish that an older published release contains it;
follow the [matching-build instructions](mcp-server.md#build-and-install-locally).

| Evidence area | What was independently accepted | Public contract or test |
|---|---|---|
| Status, doctor, build, verify, batch, pause and diagnostics | Local Spring/SAP tool probes and real application checks | [MCP reference](mcp-server.md), [packaged workflow](../mcp-server/src/test/java/com/onurkat/reclazz/mcp/AgenticEndToEndTest.java) |
| Automatic actions during BUILD holds | Started/failed hold, same-owner recovery and restored SAP behavior | [Automatic action tests](../agent/src/test/java/com/onurkat/reclazz/agent/ReloadBuildAutomaticTest.java) |
| Await reload | Received-event correlation, exact-owner receipts and negative outcomes | [Await tests](../mcp-server/src/test/java/com/onurkat/reclazz/mcp/AwaitReloadTest.java) |
| Event pages | Ordered pagination, exact filters, gaps and honest observation limits | [Event tests](../mcp-server/src/test/java/com/onurkat/reclazz/mcp/RecentEventsTest.java) |
| Resources and verification prompt | Packaged resource/prompt contracts and SDK reads; installed Claude Code listing with explicit limits | [Resource and prompt reference](mcp-server.md#read-only-resources) |
| Real coding clients | Eleven-tool discovery and read-only status in Claude Code and Codex CLI | [Dated client acceptance](client-matrix.md#installed-coding-clients) |

The bridge provides tools, read-only resources and a verification prompt.
SAP-specific operation receipts remain outside this work. No automatic restart,
durable edit journal, source-revision proof or universal rollback is promised.

Start with the [setup reference](mcp-server.md), verify the intended JVM, and
finish the edit loop with the application's own result.
