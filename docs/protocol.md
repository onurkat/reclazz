# The Status Socket

What the agent says while it runs, and the commands it accepts, so
that something other than the IntelliJ plugin can listen: a VS Code or
Eclipse extension, a build script that nudges the agent when it has finished
writing class files, a log shipper.

The agent's own tests hold this document to the code (`ProtocolContractTest`),
so a field or command listed here is one the agent has.

## Finding the port

The agent listens on **127.0.0.1 only**, on an ephemeral port unless
`statusPort=N` is passed, and publishes its port and a fresh per-process credential in a private file.

| Where | When |
|---|---|
| the path given as `portFile=...` | always, when given |
| `<hybris>/.reclazz/agent.port` | SAP Commerce, no `portFile` given |
| `<working directory>/.reclazz/agent.port` | everything else, no `portFile` given |

When IntelliJ launches the agent it may select `.idea/reclazz/agent.port`.
The agent atomically publishes two ASCII lines. Line one is the decimal port.
Line two starts with `token=` followed by 64 lowercase hexadecimal characters.
The file ends in a newline. The agent creates the file privately before writing
the credential (POSIX mode 0600, a private Windows ACL for the current user with system and administrator access permitted). It removes its own
publication at shutdown. A fixed port still needs this file. If private
publication fails, reload continues with the status listener disabled. Choose
a writable directory on a filesystem that supports private files and restart the agent. A shared project directory is supported. Use a directory only trusted users can modify when the client also needs to trust the identity of the advertised endpoint.

## Authenticating a connection

Read the whole file as one snapshot on every connection. For protocol 2 the
first client line is `AUTH <token>\n`. Wait for `CONNECTED` with version 2
before sending commands. Events also require authentication. Missing, invalid
or late authentication closes the connection silently. Keep the credential
out of command arguments, logs and reports.

| Client and file | Behaviour |
|---|---|
| Updated client, two-line file | Authenticates before receiving events. A malformed file fails discovery. |
| Updated client, legacy decimal file or explicit port only | Waits for a protocol 1 greeting. To use a new agent, supply its private file. |
| Legacy client, updated agent | Cannot subscribe or send commands. Update the client to a version supporting protocol 2. |

## The stream

One JSON object per line, UTF-8, newline-terminated. The first line on
successful authentication is a `CONNECTED` event; after that every status line the agent
prints, and a `HEARTBEAT` every 10 seconds when nothing else is said.

```json
{"level":"RELOAD","message":"Reloaded com.acme.OrderService (12ms)","timestamp":"2026-09-08T09:12:44.120Z"}
```

| Field | Present | Meaning |
|---|---|---|
| `level` | always | one of the levels below |
| `message` | always | the text of the line, JSON-escaped, at most 4096 characters |
| `timestamp` | always | ISO-8601, UTC, when the line was emitted |
| `type` | structural reloads | `structural`, so a listener can count them apart |
| `version` | `CONNECTED` only | the protocol version, `2` |
| `agent` | `CONNECTED` only | the agent's release, such as `1.1.1` |

Levels: `CONNECTED`, `HEARTBEAT`, `INFO`, `OK`, `WARN`, `ERROR`, `RELOAD`,
`STRUCTURAL_RELOAD`, `COMPILE`.

Fields are only ever added, never renamed or removed, and a listener that
ignores fields it does not know keeps working; `version` also identifies the handshake. The agent keeps at most 5 clients and drops events for
a client that stops reading rather than blocking a reload on it.

## Commands

A client may send one line at a time, at most 512 characters (4096 for CONFIG); anything not
listed here is ignored without an answer, and a line that never ends closes
the connection. Every answer arrives as ordinary `INFO` lines on the stream,
to every client, except requested BUILD receipts, RELOAD state, DOCTOR observations, SCAN results and VERIFY results, which go only to the requesting connection.

| Command | Answer |
|---|---|
| `DIAGNOSE <class>` | why the class did or did not reload last time: the class file the bytes came from, the outcome, what a restart would change |
| `PENDING` | restart-required changes plus manual-pause state and pending counts |
| `HEALTH` | how the session is going: reloads, failures, latency, watched directories, a reload that is still running |
| `BUILD <state> [request=<token>] [owner=<token>]` | hold class files when a build starts; accept the complete captured output only after success; failure keeps the hold. States are case-insensitive; `request=` is literal and its token is echoed unchanged. An unknown argument makes the entire command ignored |
| `VERIFY <token> <class> <sha256>` | read the latest exact-byte reload receipt for one class; requester-only structured result (below) |
| `DOCTOR <token>` | read bounded JVM, session, watcher and capability evidence; requester-only result (below) |
| `RELOAD <action> request=<token>` | pause/resume/status automatic reload admission; requester-only correlated state (below) |
| `CONFIG <action> <id> ...` | GET reads effective runtime options; SET with base and key/value pairs applies one atomic revision; requester-only correlated results (below) |
| `SCAN [request=<token>]` | look at the watched directories now, instead of on the next poll. Files changed since their recorded baseline, or first seen with an mtime after the original watched-root registration, are handled as usual. Older unseen files establish a silent baseline and are not auto-applied just because they were found. Send it when a build has just finished |

CONFIG replies are also requester-only. CONFIG changes only the listed runtime
options; it accepts no script, class payload or arbitrary path. Actual compilation,
reload and platform actions still use the existing watched-file/admission pipeline.

## SCAN completion

Bare `SCAN` keeps its fire-and-forget behavior. `SCAN request=<token>` uses a
1-64 character ASCII letter/digit/underscore/hyphen token and replies only to
that connection, once: `SCAN_RESULT <token> <json>` as an `INFO` message.
The JSON contains `requestId`, `sessionId` (the same session as DOCTOR), `status`,
`detail`, `reloadConfirmed:false`, and `enqueued` only for status `queued`.
Status `unavailable` means the scan/delivery failed, timed out, stopped or its
completion hook/session was unavailable; a missing count never means zero.

`enqueued` counts valid file events delivered to the agent callback in that
scan pass, including already pending events flushed by the barrier. Coalesced
requests share that pass count; counts are not additive or uniquely caused by
one request. An unchanged scan can complete with zero. The receipt follows
callback delivery, not completed compilation/reload. Pause and BUILD holds
remain effective; use VERIFY and application checks for reload outcome.

Concurrent connections can await separate barriers; commands on one connection
run serially. A request arriving after a pass's barrier cutoff waits for a
later pass. Tokens are correlation labels, not a server deduplication key;
use a fresh token for each request. Malformed arguments are ignored. Disconnect
makes a reply undeliverable without undoing the scan or affecting other callers.
Watcher stop fails queued and in-flight barriers; server stop closes sockets.
The internal watcher wait is bounded at ten seconds. A client timeout means
unknown outcome; do not infer success, replay on another agent, or accept a late
reply for a new request. No terminal is broadcast to another client.

DOCTOR advertises `scanCompletionSupported` only when the counted hook and
verification session are wired. This is an additive protocol-1 capability,
separate from legacy `scanSupported`. Older reports that omit it do not
establish completion support. Legacy clients can continue sending bare SCAN.

## Pausing automatic reload

Send `RELOAD pause request=my-request` and wait for the requester-only INFO
message `RELOAD_STATE <token> <action> paused=<true|false> pendingClasses=<count> pendingActions=<count> buildHold=<none|named|legacy>`.
Actions are exactly `pause`, `resume`, `status` (lower-case). The mandatory
request token uses 1–64 ASCII letters, digits, underscores or hyphens.
Invalid commands or an unavailable listener produce no acknowledgement.

Pause is an agent-wide manual switch: any local client can resume it; it is
not a per-client lock. Disconnect does not resume. Repeated pause/resume is
safe. `status` only reads state; `PENDING` also reports the pause state and
pending counts alongside its existing restart-required entries. `HEALTH`
reports a manual pause. Counts cover observed, not-yet-admitted distinct class
paths and automatic action keys, not every unobserved disk change. A capture
already reading bytes may return its entries to these pending maps afterward.

Class files, source compilation and resource actions observed while paused
are retained/coalesced. `SCAN` still observes files but cannot bypass pause.
Resume schedules a scan and eligible pending work using current output. Its
acknowledgement is an admission-state snapshot, **not reload completion**;
use VERIFY and application checks for completion. A missing acknowledgement
leaves the outcome uncertain: query status before retrying. Old agents ignore
this command; clients must not treat timeout as success.

Manual pause is independent of BUILD ownership. Resume cannot unlock active
or failed builds. A successful owner's BUILD ok still validates/captures output
and releases that build hold, while a manual pause keeps changes pending;
unreadable output still retains ownership. A later build start/failure continues
to block admission. A BUILD hold blocks admission of class files and of automatic
actions (source compilation and resource or platform callbacks). Held automatic
actions are coalesced by key, normally the file path, and admitted in order after
the owner's BUILD ok, unless a manual pause is still active. Work admitted before
the hold may finish; nothing is atomic or rolled back.

Work admitted before pause may finish, including captured class batches waiting
for a request boundary and compilation/framework callbacks already running.
Pause does not interrupt a batch, roll back changes, prevent an application
classloader from first loading new disk output, or freeze application requests.
A pause during class capture postpones the unadmitted batch. No selective
application, rollback or transactional reload is implied.

## Holding a build until it succeeds

The IntelliJ plugin sends `BUILD started` at build start and `BUILD ok`
only when compilation finishes without errors or cancellation. It sends
`BUILD failed` otherwise. Files from a failed build remain held and join
those from the next successful build. `SCAN` alone does not release a hold.

On success the agent waits for its watcher to finish scanning, captures
all pending class bytes, then accepts that batch. A newer start or failure
invalidates an unfinished capture. Already accepted bytes may finish
reloading while the next build is held. This controls admission to reload;
it does not make JVM redefinitions or framework refreshes transactional.
A read or scan failure leaves the entire build held for another `BUILD ok`.

A missing result or disconnected client never releases output. After five
minutes there is one warning; `HEALTH` shows the held class-file count, automatic
action count and start time.
After verifying that the output directory contains a successful build,
`BUILD ok` can also recover a hold left by an IDE that closed mid-build.
Unowned (legacy) commands describe one ordered build stream per agent. They
cannot alter a named hold, and a named builder cannot take a legacy hold.
Agents receiving no BUILD signals keep their usual behavior.

For a whole Gradle or Maven build, install the hold **before** invoking the
build tool. A `classes.doFirst` hook runs after its compilation dependencies
and is too late to protect them. Sending bytes is not proof that the hold has
been installed: wait for a receipt before starting the compiler.

An optional `request=<token>` suffix asks for an acknowledgement. Tokens are
1–64 ASCII letters, digits, underscores or hyphens; an invalid suffix causes
the entire command to be ignored. After the build listener returns, only the
requesting connection receives an `INFO` event whose `message` is exactly
`BUILD_ACK <token> <state>` (state lower-case). For started/failed the hold is
installed before this receipt. For ok it confirms scheduling the capture,
**not** completion of capture or reload. There is no receipt when the listener
is unavailable or throws. Legacy BUILD commands still work without receipts when no named hold exists.

Use the terminal wrapper in the MCP jar for either build tool; see
[mcp-server.md](mcp-server.md#protecting-terminal-builds). It requires this
receipt support, so an older agent refuses the wrapped build before any
compiler runs. A missing receipt is a failure, never permission to proceed.
If an ok receipt is lost, its result is uncertain: the agent may already have
accepted output; verify live behavior rather than reporting success.

### Build ownership

Add `owner=<token>` together with `request=<token>` to protect a named build.
Both tokens use 1–64 ASCII letters, digits, underscores or hyphens. Options may
appear in either order; duplicate or invalid options are ignored as a whole.
Only `started` acquires a free named hold. A different owner, or an unowned
command, cannot change it with started, ok or failed. Successful requests receive
`BUILD_ACK <token> <state> owner=<owner>`; ownership conflicts or completion
without an active named hold receive `BUILD_REJECTED <token> <state> owner=<owner>`.
An unowned request rejected by a named hold receives `BUILD_REJECTED <token> <state>`.
Replies go only to the requesting connection. An unavailable listener produces
no receipt, including when an older listener cannot enforce named ownership.

Ownership survives failure and disconnect. Reuse the same owner for recovery:
start another complete build, then signal ok only after successful compilation.
Scan or read failure retains ownership too. The ok receipt precedes asynchronous
capture; another owner's start is rejected until that capture is accepted.
Already accepted batches can finish reloading while the next build is held.
There is no timeout unlock or force takeover. If the owner token is lost,
restart the application/agent to reset the hold.

Choose a unique owner per independent build. This is cooperative correlation,
not authentication: sharing a token deliberately shares ownership. It cannot
isolate output from a process that writes without acquiring the hold. Legacy
unowned builders still require a single ordered builder. An unowned ok can
recover only an unowned hold after a verified successful build. Attachment
plugins do not automatically wrap terminal compilation. New wrappers require
an exact owner-correlated receipt and refuse to compile against older agents.

## Nudging the agent from a build

On macOS the JDK's file watcher polls on a two-second cycle, and `SCAN`
saves that wait. The IntelliJ plugin sends it after every build; a Gradle
or Maven build can do the same.

In a Gradle build with the updated Reclazz plugin on its buildscript classpath:

```kotlin
import com.onurkat.reclazz.protocol.StatusEndpoint
import groovy.json.JsonSlurper
import java.net.Socket

tasks.named("classes") {
    doLast {
        val file = rootProject.file(".reclazz/agent.port")
        if (file.isFile) {
            val endpoint = StatusEndpoint.read(file.toPath())
            Socket("127.0.0.1", endpoint.port()).use { socket ->
                socket.soTimeout = 3000
                val output = socket.getOutputStream()
                endpoint.authenticate(output)
                val hello = JsonSlurper().parseText(socket.getInputStream().bufferedReader().readLine()) as Map<*, *>
                check(hello["level"] == "CONNECTED" && hello["version"] == endpoint.protocol())
                output.write("SCAN\n".toByteArray(Charsets.UTF_8))
                output.flush()
            }
        }
    }
}
```

For whole-build coordination configure the Gradle `reclazzSafeBuild` task's
`portFile` or Maven `-Dreclazz.portFile=<path>`. Both pass the file path to
the MCP build wrapper, which reads the current credential at connection time.

## Verifying exact compiled bytes

With matching current agent/MCP jars, send `VERIFY <token> <class> <sha256>`.
VERIFY is uppercase; token is 1–64 ASCII letters/digits/underscores/hyphens.
Class is a dot-separated Java binary name, up to 256 characters, including `$`
for nested classes; identifier-ignorable control characters are rejected.
SHA-256 is exactly 64 lower-case hex digits, calculated from the compiled class
file the caller expects to have been applied. The agent never reads a path
supplied by this query. Invalid commands or an unavailable verifier get no reply.

The requester receives an INFO event with `message` equal to
`VERIFY_RESULT <token> <json>`. Parse the event first, then parse the JSON suffix.
Existing readers can continue treating message as text. The nested object has
these string fields:

| Receipt member | Meaning |
|---|---|
| requestId | The unchanged request token; check it before accepting a result |
| sessionId | Random ID of this agent verifier; changes on initialization |
| className | The requested binary name |
| expectedSha256 | The requested hash |
| observedSha256 | Hash of the latest captured reload input, or empty if absent |
| status | One of the states below |
| source | Origin path, bounded to 200 characters |
| detail | Outcome or reason, bounded to 200 characters |
| completedAt | ISO UTC batch completion time; empty before completion or if absent |

Only **applied** is positive evidence. It means the expected input bytes matched,
a successful reload outcome was recorded, the class was uniquely loaded before
the attempt, and the entire batch returned, including deferred redefinition and
framework refresh, with no verification warning/error on the applying thread.
A warning in any member conservatively prevents success for the other members
too. The explicit notice that removed-method callers now get NoSuchMethodError
as after a restart is informational for verification; it still appears as WARN.
A removal that keeps a previous implementation until restart remains
`unverified`, including in a batch that also has a restart-equivalent notice.

Optional SAP Commerce `autoSolrReindex` work runs after a provider/resolver reload on a
separate worker. Its `FINISHED`, `FINISHED_UNVERIFIED` or `FAILED` INFO line
describes the indexing operation only. An `applied` class receipt does not
certify stored search-field freshness, and an indexing failure does not undo
the class reload. No new socket command or receipt field is introduced.

A superclass-changing save illustrates this distinction: eligible method bodies
may run while the original superclass remains. Its warning makes the matching
receipt `unverified`, including when a dependent method stays pinned to its old
body. A class-level blocker instead produces `failed`. Neither means that the
requested hierarchy was applied. `PENDING` retains the session's restart concerns;
a later ordinary save can have an `applied` receipt without clearing earlier
superclass notes. Check current live behavior as well as the receipt; retained
notes are not an exact diff of the latest source. See
[production reload reporting](superclass-feasibility.md#production-reload-reporting).

- `running`: matching bytes are being applied; no completion yet.
- `failed`: the matching attempt reported failure or the batch threw; detail says why.
- `unverified`: matching attempt finished without sufficient evidence: absent
  outcome, unloaded/ambiguous class, duplicate input name, or a batch warning/error.
- `mismatch`: the latest attempt used different bytes; an older success cannot
  certify a newer edit. Inspect observedSha256 and source.
- `not_observed`: no retained receipt in this session. This includes eviction,
  restart, classes never reloaded, and untracked mutations invalidating old proof.

The ledger retains at most 512 latest class receipts, not an operation history.
It observes external class-file and autoCompile batches; hashes come from the
captured input, never from a file rewritten while a reload is running. Starting
a new attempt supersedes old proof even if that attempt later fails. A failed
compiler that leaves files held creates no receipt for those new bytes.

This is a point-in-time receipt for one class, not a transaction across classes,
a full snapshot of all classloaders, or a business-behaviour test. It cannot prove
that an identical-byte recompile caused a new reload, or that another agent or
instrumentation tool has not subsequently changed the JVM. Keep one builder per
agent; verify each changed class with a bounded polling deadline, keep sessionId
consistent, then exercise the endpoint or application test. No reply, disconnect,
old-agent timeout or a non-applied state must ever be treated as success.

## Doctor observation

`DOCTOR <token>` accepts a token of 1–64 ASCII letters, digits, underscores or
hyphens. The requesting connection receives an INFO event whose message is
`DOCTOR_RESULT <token> <json>`. Parse the event, match the token, then parse the
JSON suffix. No build, scan, reload or hold change is performed.

`status:observed` carries `requestId`, `detail`, `sessionId` (the same process
identity used by VERIFY), `agentVersion`, string `pid`, `javaVersion`, `vmName`,
and target JVM `workingDirectory`. A working directory is not a repository root.
`watcherState` is starting, watching, stalled, stopped or unavailable. Stalled means the live watcher has not completed a poll within its liveness bound; stopped also covers a dead watcher thread. `buildHold` is none,
named, legacy or unavailable; owner tokens are never disclosed. Boolean
`buildOwnershipSupported`, `verifySupported` and `scanSupported` describe wired
handlers, not capabilities inferred from a version string. Before wiring, support
can be false and the verification session empty.

`watchedDirectoryCount` counts currently valid registrations whose directories
still exist. `watchedDirectories` samples at most eight normalized absolute paths,
each at most 256 characters. The corresponding entry at the same index in
`watchedDirectoryKinds` gives the active registration kind, such as `classes`,
`src`, `resources` or `webroot`. An unclassified registration uses `unknown`.
Both arrays omit the same entries when a path is too long or the response
budget is reached. `watchSampleTruncated` marks an incomplete sample. `unwatchableDirectoryCount` reports refused
registrations. Registrations can include class, source and resource directories;
this sample does not prove coverage of a particular compiled output. The snapshot
is observational, not atomic with watcher or build changes.

`reloadConfirmed` is always false. Observation is neither readiness nor reload
proof. Metadata strings are bounded to 512 characters and the JSON receipt to
3900 characters so the enclosing 4096-character message limit cannot truncate it.
If evidence cannot be produced within these bounds, `status:unavailable` includes
only correlation and a detail message. Older agents can give no response; clients
must report unavailable rather than assume support. The private port file controls access to these reports.

## Client-side batch verification

MCP `reclazz_verify_batch` composes 1–32 existing VERIFY queries on one connection.
There is no batch agent command and no atomic snapshot. Every item has its own
correlation token; the client requires the same sessionId across validated
receipts and one total time budget. It preserves earlier receipts and marks
remaining items unavailable on connection/evidence failure. Cancelling the MCP
request closes only that observation connection; the running agent's reload and
build ownership are unaffected. See [MCP batch and cancellation](mcp-server.md#bounded-batch-verification-and-cancellation).


## Live runtime settings

`CONFIG GET <id>` returns an INFO message beginning `CONFIG <id> OK rev=<n>`,
followed by all six effective booleans, `live=<keys>`, `watchExtensions.count=<n>`
and `watchExtensions.sha256=<hash>`, `loadedUninstrumented=<n>` and `unwatchable=<n>`. The id is 1–64 ASCII letters, digits,
underscores or hyphens. Keys in `live` are the only options the peer may SET:

| Key | Runtime scope |
|---|---|
| `autoCompile` | internal Java compiler and source/class watcher transition |
| `watchExtensions` | discovered named SAP Commerce extensions, separated by semicolons; no watch-all transition |
| `autoImpex` | ImpEx import on a save whose action starts after the change |
| `impexAllowRemove` | REMOVE permission captured with the import decision |
| `backofficeConfigReset` | Backoffice reset on the next configuration dispatch |
| `autoUpdateRunningSystem` | update policy captured at codegen/localization operation start |
| `autoSolrReindex` | new indexing enqueues; queued/running work drains when disabled |

A SAP Commerce agent advertises all six booleans and, in named mode, `watchExtensions`; a non-SAP Commerce agent advertises only `autoCompile`.
The watch filter hash is SHA-256 of sorted, distinct nonblank configured names
joined by semicolons (UTF-8); `*` represents watch-all. Count/hash are display
and comparison evidence, never SET inputs. They describe the effective filter,
not the actual registered-directory count. DOCTOR's `options` object, when the
runtime controller is wired, includes the revision, six booleans, `loadedUninstrumented`, `unwatchable` and a watch
filter count/hash plus at most five names, each bounded to 40 characters, with
an explicit `truncated` flag. Its existing 3900-character budget still applies.

`CONFIG SET <id> base=<rev> <key>=<value>...` accepts one to seven changes:
six booleans (exactly `true` or `false`) and `watchExtensions=a;b` on SAP Commerce.
Keys are unique. The named list is sorted/deduplicated, bounded to 3500 characters,
and every name must already be discovered; otherwise `ERROR unknown_extension`
leaves the entire batch unchanged. Empty lists select no named extensions. A
transition from or to `*` returns `ERROR restart_required`; the plugin preserves
its existing blank-list meaning of watch-all. Unknown keys, duplicates, malformed
revisions and unsupported platform keys make no option change.

Scope, booleans, admission inventory and receipt statistics publish as one Runtime
revision. Removing an extension stops its file subscriptions and drops queued
unadmitted events; it does not unload classes or undo previously applied effects.
Previously admitted class names retain transform ownership. Newly selected classes
that were already loaded without instrumentation support body edits only until
restart. A first raw reload may perform one capture-only retransform of that exact
Class to compare its VM schema; it never runs in save preparation and never seeds
an admitted-byte baseline. Known structural changes are refused before native
redefine or companion publication. Classes first loaded from newly selected output
roots can receive normal instrumentation. `loadedUninstrumented` counts raw loaded
definitions in newly added extensions, not failed reload attempts. `unwatchable`
reports skipped directory evidence, so OK does not promise every directory is watched.

One serial settings executor compares `base` with the current revision when the
transaction executes. A matching revision publishes the entire options snapshot
and increments the revision once; a stale one returns
`CONFIG <id> CONFLICT rev=<current> ...` with the effective fields. Other replies
are `CONFIG <id> ERROR <code> <reason>`, with `bad_request`, `unknown_key`,
`unknown_extension`, `restart_required`, `unavailable` or `failed`. Reasons are bounded. Before runtime
wiring, GET returns `ERROR unavailable`; this is distinct from an older agent
that ignores CONFIG entirely.

AutoCompile preparation runs on the reload executor. It waits for BUILD ownership,
manual pause and native generated-output admission without releasing their holds.
Generation checks and protected cleanup/commit steps share the queue lock. A new
hold invalidates the staged transition and requeues it. Fatal staging failure leaves
old roots/options, compiler, admission and baselines intact. The codegen fence
admits no transition while a native run owns a permit, including the interval
before its generated-output hold; its final release queues one retry. Compiler,
watch inventory and new subscription baselines stage before any destructive
cleanup. After the guarded departing-key scan/drain/cancel, enabling compilation
cleans generated copies, then installs and publishes the prepared revision.
Cleanup itself is not rollbackable; individual I/O failures are reported/skipped.
The compiler is retained idle when disabled and reused only for its bound scope;
a scope change while disabled rebuilds it on the next enable. Exact properties
and loaded external XML are baselined on subscription; re-adding a removed module
does not replay edits made while it was removed.

After ten seconds a slow/held SET reports `CONFIG <id> PENDING rev=<current>`.
It keeps the same request id until a terminal OK, CONFLICT or ERROR reply follows.
The client may read GET while waiting but must not resend the pending SET. GET
alone cannot attribute a revision advanced by an earlier queued request to this
transaction. The plugin retains pending correlation alongside its GET requests,
drops replies from old connection generations, refetches once on a conflict and
never echoes a successful acknowledgement as another SET. The latest Apply owns
the status line. On reconnect it GETs effective values and SETs only supported
differences from persisted project settings. A watch-filter mismatch is shown as
restart-required without sending that setting.

The startup argument line is unchanged. With no IDE connection after a restart,
its original options apply; the plugin resends saved differences on reconnect.
