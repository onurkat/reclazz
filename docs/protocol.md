# The Status Socket

What the agent says while it runs, and the commands it accepts, so
that something other than the IntelliJ plugin can listen: a VS Code or
Eclipse extension, a build script that nudges the agent when it has finished
writing class files, a log shipper.

The agent's own tests hold this document to the code (`ProtocolContractTest`),
so a field or command listed here is one the agent has.

## Finding the port

The agent listens on **127.0.0.1 only**, on an ephemeral port unless
`statusPort=N` is passed, and writes the port number as plain text to a port
file:

| Where | When |
|---|---|
| the path given as `portFile=...` | always, when given |
| `<hybris>/.reclazz/agent.port` | SAP Commerce, no `portFile` given |
| `<working directory>/.reclazz/agent.port` | everything else, no `portFile` given |

The IntelliJ plugin also writes and reads `.idea/reclazz/agent.port` for a
run configuration it injected the agent into. The file is written whole
(temporary file and rename), so a reader never sees half a number, and it is
removed when the agent stops.

## The stream

One JSON object per line, UTF-8, newline-terminated. The first line on
connecting is a `CONNECTED` event; after that every status line the agent
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
| `version` | `CONNECTED` only | the protocol version, `1` |
| `agent` | `CONNECTED` only | the agent's release, such as `1.1.1` |

Levels: `CONNECTED`, `HEARTBEAT`, `INFO`, `OK`, `WARN`, `ERROR`, `RELOAD`,
`STRUCTURAL_RELOAD`, `COMPILE`.

Fields are only ever added, never renamed or removed, and a listener that
ignores fields it does not know keeps working; `version` changes only if a
field's meaning does. The agent keeps at most 5 clients and drops events for
a client that stops reading rather than blocking a reload on it.

## Commands

A client may send one line at a time, at most 512 characters; anything not
listed here is ignored without an answer, and a line that never ends closes
the connection. Every answer arrives as ordinary `INFO` lines on the stream,
to every client, except requested BUILD receipts, DOCTOR observations and VERIFY results, which go only to the requesting connection.

| Command | Answer |
|---|---|
| `DIAGNOSE <class>` | why the class did or did not reload last time: the class file the bytes came from, the outcome, what a restart would change |
| `PENDING` | what still needs a restart in this session |
| `HEALTH` | how the session is going: reloads, failures, latency, watched directories, a reload that is still running |
| `BUILD <state> [request=<token>] [owner=<token>]` | hold class files when a build starts; accept the complete captured output only after success; failure keeps the hold. States are case-insensitive; `request=` is literal and its token is echoed unchanged. An unknown argument makes the entire command ignored |
| `VERIFY <token> <class> <sha256>` | read the latest exact-byte reload receipt for one class; requester-only structured result (below) |
| `DOCTOR <token>` | read bounded JVM, session, watcher and capability evidence; requester-only result (below) |
| `SCAN` | look at the watched directories now, instead of on the file watcher's next poll; what changed is reloaded as usual. Send it when a build has just finished |

Nothing a client sends makes the agent load, reload or run anything it
would not have on its own.

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
minutes there is one warning; `HEALTH` shows the held count and start time.
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

Gradle, after the classes are written:

```kotlin
tasks.named("classes") {
    doLast {
        val portFile = rootProject.file(".reclazz/agent.port")
        if (portFile.exists()) {
            java.net.Socket("127.0.0.1", portFile.readText().trim().toInt()).use {
                it.getOutputStream().write("SCAN\n".toByteArray())
            }
        }
    }
}
```

From a shell, after `mvn compile`:

```bash
printf 'SCAN\n' | nc 127.0.0.1 "$(cat .reclazz/agent.port)"
```

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
framework refresh, with no WARN/ERROR on the applying thread. A warning in any
member conservatively prevents success for the other members too.

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
`watcherState` is starting, watching, stopped or unavailable. `buildHold` is none,
named, legacy or unavailable; owner tokens are never disclosed. Boolean
`buildOwnershipSupported`, `verifySupported` and `scanSupported` describe wired
handlers, not capabilities inferred from a version string. Before wiring, support
can be false and the verification session empty.

`watchedDirectoryCount` counts currently valid registrations whose directories
still exist. `watchedDirectories` samples at most eight normalized absolute paths,
each at most 256 characters; longer paths are omitted. `watchSampleTruncated`
explicitly marks omitted entries. `unwatchableDirectoryCount` reports refused
registrations. Registrations can include class, source and resource directories;
this sample does not prove coverage of a particular compiled output. The snapshot
is observational, not atomic with watcher or build changes.

`reloadConfirmed` is always false. Observation is neither readiness nor reload
proof. Metadata strings are bounded to 512 characters and the JSON receipt to
3900 characters so the enclosing 4096-character message limit cannot truncate it.
If evidence cannot be produced within these bounds, `status:unavailable` includes
only correlation and a detail message. Older agents can give no response; clients
must report unavailable rather than assume support. The existing loopback socket
trust boundary still applies.

## Client-side batch verification

MCP `reclazz_verify_batch` composes 1–32 existing VERIFY queries on one connection.
There is no batch agent command and no atomic snapshot. Every item has its own
correlation token; the client requires the same sessionId across validated
receipts and one total time budget. It preserves earlier receipts and marks
remaining items unavailable on connection/evidence failure. Cancelling the MCP
request closes only that observation connection; the running agent's reload and
build ownership are unaffected. See [MCP batch and cancellation](mcp-server.md#bounded-batch-verification-and-cancellation).
