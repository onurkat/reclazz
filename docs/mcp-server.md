# Reclazz MCP server

An [MCP](https://modelcontextprotocol.io) server that turns the running Reclazz
agent into tools a coding agent can call: check whether Reclazz is attached,
nudge a scan after a build, ask what still needs a restart, or diagnose why a
class did not reload. It is a thin, loopback-only client of the agent's status
socket and asks the agent nothing beyond its own state.

It lives in `:mcp-server`, not the agent jar, because the agent's own tests
forbid the shipped agent from opening a client socket.

The [client acceptance matrix](client-matrix.md) records independent SDK,
real build-tool and OS coverage, with runnable tests and explicit untested lanes.

For the developer workflow, read
[MCP for coding agents: close the loop](mcp-for-coding-agents.md): each benefit
has a recorded example and a limit, from target discovery to byte receipts.

## Running it

The server requires Java 17 or newer and speaks JSON-RPC over stdio. It is a
standalone jar containing its runtime dependencies; it does not need IntelliJ,
Gradle or a separately installed Gson at runtime.

### Build and install locally

From a source checkout, build matching agent/MCP jars and test the MCP package:

```sh
./gradlew :agent:shadowJar :mcp-server:test :mcp-server:mcpRelease
version=$(sed -n 's/^pluginVersion=//p' gradle.properties)
install_dir="$HOME/.local/share/reclazz/$version"
mkdir -p "$install_dir"
cp "mcp-server/build/distributions/mcp/reclazz-mcp-$version.jar" \
   "mcp-server/build/distributions/mcp/reclazz-mcp-$version.jar.sha256" "$install_dir/"
(cd "$install_dir" && shasum -a 256 -c "reclazz-mcp-$version.jar.sha256")
```

These are macOS/Linux shell commands; Linux can use `sha256sum -c` in place of
`shasum -a 256 -c`. On Windows copy the same two files to a permanent directory
and compare the SHA-256 from [Get-FileHash](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.utility/get-filehash?view=powershell-5.1)
with the first field of the sidecar (ignoring hex letter case). The sidecar names only the jar, so relocation is supported.
Stop on a checksum mismatch. This stages/installs local files; it does not publish.

### Release assets

The updated tag workflow will attach `reclazz-mcp-X.Y.Z.jar` and
`reclazz-mcp-X.Y.Z.jar.sha256` to future GitHub releases, next to the matching
agent jar. **This change is unreleased; it does not add MCP assets to existing
releases.** When a release contains these assets, download both from that same
tag, put them together in a permanent directory and verify the checksum as above.
Use matching agent and MCP builds for BUILD/VERIFY support. A checksum detects
file corruption; it is not a signature or proof of publisher identity.

### Configure a stdio client

For clients using an `mcpServers` JSON configuration, merge this entry into their
existing configuration. Replace the example paths and `X.Y.Z`; use the absolute
path to your Java 17+ executable if the client cannot find `java` on its PATH:

```json
{
  "mcpServers": {
    "reclazz": {
      "command": "/absolute/path/to/java",
      "args": ["-jar", "/absolute/path/to/reclazz-mcp-X.Y.Z.jar",
               "--project-dir", "/absolute/path/to/project"]
    }
  }
}
```

Keep each argument separate, even for paths with spaces. Do not include shell
quotes inside the JSON strings. On Windows use escaped backslashes or forward
slashes (for example `C:/Tools/Reclazz/reclazz-mcp-X.Y.Z.jar`). Client-specific
configuration file locations vary; this entry does not modify them automatically.
Clients with another configuration format need the same executable and arguments.
Restart/reconnect the MCP client after changing the entry.

The application must separately run with the Reclazz agent attached. The
`--project-dir` option makes startup independent of the client's working
directory. Discovery checks only that directory's `.reclazz/agent.port` and
`.idea/reclazz/agent.port`, in that order. For a custom port file, also add
`"--port-file", "relative/or/absolute/agent.port"` to the argument array.
The port file may be absent at startup; the application can create it later.
An absent, unreadable, malformed or unreachable explicit target never causes
fallback to a different file or project.

### Claude Code

From the project whose application will run with Reclazz, register the packaged
server with [Claude Code's project scope](https://code.claude.com/docs/en/mcp):

```sh
claude mcp add --scope project --transport stdio reclazz -- \
  "/absolute/path/to/java" -jar "/absolute/path/to/reclazz-mcp-X.Y.Z.jar" \
  --project-dir "/absolute/path/to/project"
```

Replace all three paths first. This creates or updates the project's
`.mcp.json`. Inspect the existing file before merging an entry named `reclazz`;
do not overwrite other servers. The default `local` scope and the `user` scope
instead store entries in `~/.claude.json`. Project scope is not an installation
of the Java agent; start the application separately.

Reconnect Claude Code and approve the project MCP server if the client asks.
Use `/mcp` to inspect the connection, then ask it to call `reclazz_status` with
`{}` using only this server. For an isolated print-mode check, the installed
CLI also supports `--mcp-config /path/to/mcp.json --strict-mcp-config`; see the
[CLI reference](https://code.claude.com/docs/en/cli-reference). A configured
server or a model's description of it is not proof that the call ran.

### Codex

Merge this table into `~/.codex/config.toml`, preserving existing tables. Codex
uses [TOML MCP configuration](https://developers.openai.com/codex/mcp), so the
JSON `mcpServers` wrapper above is not a Codex configuration entry:

```toml
[mcp_servers.reclazz]
command = "/absolute/path/to/java"
args = ["-jar", "/absolute/path/to/reclazz-mcp-X.Y.Z.jar", "--project-dir", "/absolute/path/to/project"]
```

Replace the executable, jar and project paths. Keep each argument as one TOML
string, including paths with spaces. Do not add a second table with the same
name. This local stdio server needs no API key, HTTP URL or MCP OAuth login.
The Codex client can separately require its own provider authentication.

Restart/reconnect Codex, inspect `/mcp`, and request one `reclazz_status` call
with `{}`. `codex mcp list` lists configuration; it does not prove tool
discovery or execution. A temporary CLI verification can use explicit `-c`
overrides and `exec --ignore-user-config` where the installed version supports
them, without editing the user's persistent config. The matrix identifies
which route was actually exercised.

### Cursor

Create or merge the `reclazz` entry in the project's `.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "reclazz": {
      "type": "stdio",
      "command": "/absolute/path/to/java",
      "args": ["-jar", "/absolute/path/to/reclazz-mcp-X.Y.Z.jar",
               "--project-dir", "/absolute/path/to/project"]
    }
  }
}
```

[Cursor's MCP guide](https://cursor.com/docs/mcp) also documents
`~/.cursor/mcp.json` for global configuration. Use the project file when this
server should target only that project. Reconnect the server in Cursor's MCP
settings, inspect its available tools and ask Agent to call `reclazz_status`
with `{}`. Do not count a registered entry as a completed call.

The separate Cursor Agent CLI has its own installation. If it is already
installed, its documented `agent mcp list-tools reclazz` command lists tools;
see [Cursor CLI MCP](https://cursor.com/docs/cli/mcp). A desktop installation
alone does not establish that CLI's availability. Some Cursor launchers
automatically install or update it when `cursor agent` is invoked. Inspect
availability before invoking that route during a no-install check.

### Check the connection and target

The current server exposes eleven tools, including `reclazz_await_reload` and
`reclazz_events`. Compare the client's discovered tools with the table below.
Then inspect the actual `reclazz_status` tool result, rather than the model's
paraphrase. With a running, healthy target expect `attached:true` and health
data. `attached:false` is an explicit diagnostic result, not a successful
attachment. Even `attached:true` does not prove a reload or changed application
behavior; use DOCTOR, exact-byte verification and an application check for that.

If the client cannot start the server, check the absolute Java executable,
Java version, readable packaged jar and its checksum. If it starts but cannot
reach the application, check the selected project, the application's actual
port file, and whether its launch directory satisfies the project check below.
Client permission denial, missing provider authentication and an unreachable
provider are separate from a server or target failure. Preserve the error and
do not report an unexecuted tool call as verified. The
[client matrix](client-matrix.md) records observed versions and unverified lanes.

### Target selection and overrides

Startup accepts only `--project-dir PATH`, `--port-file PATH`, or `--help`.
Unknown/duplicate options and missing/blank/invalid values fail before stdio
starts (exit2, stderr only). The selected project must be an existing directory.
Help writes usage to stderr and exits0. There is no shared configuration file,
process search, parent-directory search or machine-global default.

Relative startup project paths resolve from the MCP process's startup directory.
A relative startup port file resolves from the selected project, or that startup
directory when no project is selected. Each server keeps its own defaults.
Per-tool selection works as follows:

- `projectDir` overrides the project for that call; relative values resolve from
  the startup project/directory. It resets the startup port-file default, so
  project-only overrides discover the new project's own files.
- Per-call `port`, then `portFile`, then `hybrisHome` take precedence over the
  startup port file. Relative `portFile`/`hybrisHome` paths use the effective
  project directory. An explicit `hybrisHome` uses only its `.reclazz/agent.port`;
  absence does not fall back to the project/client directory.
- Overrides affect only that call. Invalid input does not retry against defaults.
  A call selecting another project's port must also select its `projectDir`
  when a startup project is configured.

An **explicit project** opts into a launch-directory check: on the same agent
connection, before the requested command, MCP requires a valid correlated
DOCTOR result whose canonical absolute `workingDirectory` equals or is below
that project. Missing/unsupported/malformed evidence, a relative working
directory or another project's directory fails closed. This also applies to
status and batch verification. It prevents a stale port pointing to an agent
launched outside the selected tree from receiving BUILD/SCAN/reload commands.

Working directory is not proof of repository root, watched-output coverage or
which of two JVMs launched in the same project is intended. Inspect DOCTOR PID,
session and watch evidence for those distinctions. If the application launches
outside its project tree, use explicit `--port-file` alone (or per-call `portFile`)
without project selection; this selects an endpoint but does **not** validate its
project. No-argument startup retains discovery within the startup working
directory and has no project check. Old agents without DOCTOR cannot satisfy an
explicit project check; use matching agent/MCP builds.

### Smoke check without an attached application

Set `MCP_JAR` to the absolute installed jar path and run in a macOS/Linux shell:

```sh
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  | java -jar "$MCP_JAR"
```

Expect two JSON response lines: initialization reports `reclazz-mcp` and the
jar's version; the tools list contains the eleven tools below. The notification
has no response. EOF closes the server. If Java cannot open the jar, check the
absolute path; if it cannot load its classes, use the packaged jar from
`build/distributions/mcp`, not the plain development jar. A missing attached
application is a separate condition: `reclazz_status` reports `attached:false`.

## Tools

| Tool | What it does |
|---|---|
| `reclazz_doctor` | Read correlated target JVM, session, live watch registration and capability evidence, with next checks |
| `reclazz_status` | Whether the agent is attached and how it is doing (reloads, failures, latency, watched directories), as JSON |
| `reclazz_build` | Signal `started`, `ok` or `failed`; waits for the agent to acknowledge the signal, and returns a tool error if unconfirmed |
| `reclazz_verify_batch` | Read 1–32 unique class/hash pairs on one connection within a total timeout; cancellable through stdio |
| `reclazz_verify` | Structured exact-byte reload receipt; requires `className` and lower-case `sha256`; only `status:applied` is success |
| `reclazz_scan` | Ask the agent to look at the watched directories now and reload changed classes, instead of waiting for its next poll |
| `reclazz_pending` | Restart-required changes plus manual-pause and pending counts |
| `reclazz_reload_control` | Pause/resume automatic reload admission or inspect its state; never releases BUILD holds |
| `reclazz_diagnose` | Why a given class did or did not reload last time (requires `className`) |
| `reclazz_await_reload` | Checkpoint received events before saving, then wait for an exact-owner event and same-session byte receipt; reports errors, holds and bounded timeouts |
| `reclazz_events` | List bounded received-event pages from that checkpoint with exact filters; observations never confirm a reload |

Each tool accepts optional `projectDir`, `portFile`, `port` and `hybrisHome` arguments to
locate the agent; `reclazz_diagnose` also requires `className`.

## Tool execution errors

`reclazz_scan`, `reclazz_pending` and `reclazz_diagnose` return `isError:true`
with a text explanation when the agent cannot be reached or its handshake is
invalid. Before sending a command, the client requires a JSON `CONNECTED` event
with numeric status-socket `version:1` and a nonblank string `agent` release.
Malformed or unsupported handshakes do not terminate the MCP stdio session.

Pending/diagnose also return a tool error if the connection fails, a diagnostic
response is malformed, or no `INFO` answer arrives before disconnect/timeout.
Silence does not mean nothing needs a restart or no diagnosis exists. An explicit
empty-ledger answer from the agent remains a successful result. Invalid tool
arguments still return a JSON-RPC error rather than a tool execution result.

`reclazz_status` remains an attachment diagnostic: `attached:false` is a
successful observation. If the handshake succeeds but health cannot be read,
it reports `attached:true` with a `reason` describing the missing health data.

SCAN has no acknowledgement in the status-socket protocol. Its `isError:false`
means the command was written to a validated connection; acceptance and reload
completion are unconfirmed. Use `reclazz_verify` for exact-byte reload evidence.
The legacy pending/diagnose `INFO` stream is broadcast and has no request ID or
end marker. Collected lines are diagnostic observations, not proof that the
entire response was received or that every line belongs to this request.

## Protocol version negotiation

The server supports `2025-06-18` and `2024-11-05`. Send a nonblank string
`params.protocolVersion` with `initialize`; a missing, blank or non-string version
returns JSON-RPC `-32602`. Either supported requested version is returned unchanged.
Any other nonblank version receives `2025-06-18` as the supported alternative;
the server does not echo arbitrary versions. The selected version belongs to that
stdio session. An invalid initialization request does not change it.

Following the [MCP version negotiation rule](https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle#version-negotiation),
a client that supports the returned version sends `notifications/initialized`
and continues. A client that cannot support it should disconnect. This server
advertises tools only. It does not implement HTTP transport, resources, prompts,
sampling or elicitation. Full initialization-order enforcement is not added here;
for compatibility, direct calls before initialization retain the legacy format.

## Structured tool contracts

Sessions negotiating `2025-06-18` receive `outputSchema` and `annotations` from
`tools/list`. Every tool execution result, including `isError:true`, has a JSON
object in `structuredContent` and the identical serialized JSON in its text
content block. Invalid requests/arguments remain JSON-RPC errors without a tool
result. Explicit `2024-11-05` sessions retain the existing text results and omit
these newer fields.

| Tool | Structured result |
|---|---|
| `reclazz_doctor` | `status:observed` with JVM/watch/capability evidence, or `unavailable`; separate `clientWorkingDirectory`, `nextActions`, `reloadConfirmed:false` |
| `reclazz_reload_control` | `status:acknowledged` with `action`, `paused`, pending counts and `buildHold`, or `unavailable`; `reloadConfirmed:false` |
| `reclazz_status` | Existing `attached`, optional `agent`, `protocol`, `port`, `health`, `reason` fields |
| `reclazz_scan` | `status:sent` or `unavailable`, `detail`, `reloadConfirmed:false` |
| `reclazz_build` | `status:acknowledged` or `unavailable`, requested `state` and `owner`, `detail`, `reloadConfirmed:false` |
| `reclazz_pending` | `status:observed` or `unavailable`, `lines`, `detail`, `complete:false` |
| `reclazz_diagnose` | Same diagnostic fields plus requested `className` |
| `reclazz_verify_batch` | `status:applied`, `incomplete` or `unavailable`; ordered `results`, `requestedCount`, `sessionId`, `allApplied`, `atomic:false`, `reloadConfirmed:false` |
| `reclazz_verify` | Existing correlated receipt fields; `status:applied` alone is success, or the existing `unavailable` failure object |
| `reclazz_await_reload` | `ready`, `applied`, `compile_error`, `refused`, `restart_required`, `held`, `timeout` or `unavailable`; explicit received-event correlation limits and a byte receipt for `applied` |
| `reclazz_events` | `observed` with ordered event pages, `nextCursor`, `snapshotHead` and `hasMore`, or `unavailable`; always `reloadConfirmed:false` |

`sent` confirms a socket write, `acknowledged` confirms the BUILD signal, and
`observed` describes collected diagnostic lines. None proves reload completion.
The diagnostic `complete:false` reflects the lack of a response end marker in
the broadcast stream. VERIFY preserves its exact-byte checks, and application
behavior still needs its own test.

Status, doctor, pending, diagnose, verify, verify-batch, await-reload and events advertise `readOnlyHint:true` and
`openWorldHint:false`. Scan, build and reload-control advertise `readOnlyHint:false`,
`destructiveHint:true`, `idempotentHint:false` and `openWorldHint:true`: live
reload can replace behavior and invoke application callbacks with external
effects. These conservative hints are metadata, not permission or safety proof.

The schemas use JSON Schema 2020-12. The contract tests export real responses to
`mcp-server/build/tool-contract-fixtures`. An independent check can be run with
an existing Python `jsonschema` 4.x installation after the tests:

```sh
./gradlew :mcp-server:test --rerun
python3 mcp-server/src/test/python/check_tool_contracts.py
```

The Python checker validates the schemas, successful and failed results, and
rejects deliberate missing fields, wrong types, invalid statuses and invented
completion flags. It is an optional development check, not a runtime dependency.

## Input validation

Each stdio line carries one JSON-RPC 2.0 object. Requests have a string method,
a string or integer ID (not null), and optional object params. Notifications
have no ID and receive no response. Batch arrays are not supported by this server.
Malformed JSON returns `-32700`, invalid request envelopes return `-32600`, and
invalid method/tool parameters return `-32602`. When an ID cannot be determined,
the error has `id:null`. The process continues reading subsequent lines.

Frames are limited to 65,536 Java characters, excluding the newline; an oversized
line is drained and rejected before the next line is read. Known tool arguments
must be strings, at most 4,096 Java characters, without control characters.
`className` for diagnose/verify must be a binary Java name of at most 256
characters (Unicode identifiers and `$` inner classes are supported).
`timeoutMs` is an integer string in 1–60,000, with defaults of 5,000 for build,
verify, doctor, reload_control, verify_batch, await_reload and events, and 2,000 for status, scan,
pending and diagnose. Paths with spaces are supported.
Invalid numeric ports produce tool errors for scan/pending/diagnose; status
reports them as `attached:false`.

Diagnose rejects invalid names before connecting. The socket writer additionally
rejects commands containing control characters or exceeding 512 Java characters,
so a supplied name cannot insert a second line command.

## What it does not do

It opens one connection, to `127.0.0.1`, to the agent already running on this
machine. It sends no telemetry and reaches nothing off the machine, the same
promise the agent itself keeps.

## Protecting terminal builds

Build the current agent and MCP jars together from this source tree:
`./gradlew :agent:shadowJar :mcp-server:shadowJar`. This receipt protocol is new;
it is not a promise that an older published agent supports it. Attach the built
agent to your development app and use the resulting MCP jar as `MCP_JAR` below.
From the consuming project's directory, wrap the whole compile command:

```sh
java -cp "$MCP_JAR" com.onurkat.reclazz.mcp.BuildMain \
  --port-file .reclazz/agent.port -- ./gradlew classes

java -cp "$MCP_JAR" com.onurkat.reclazz.mcp.BuildMain \
  --port-file .reclazz/agent.port -- mvn compile
```

`--port N` selects an explicit loopback port instead. With neither option, the
usual port files are located in the current directory. `--timeout-ms N` changes
the receipt timeout (default 5000; range 1–60000). Command arguments after `--`
are passed directly, without a shell. Quote paths containing spaces. On Windows,
use your command interpreter explicitly for `.bat`/`.cmd` build launchers.

The wrapper generates a unique owner token and prints it to stderr before
connecting. Retain that token: `--owner TOKEN` reuses it for recovery after a
failed or disconnected build. Owner tokens are 1–64 ASCII letters, digits,
underscores or hyphens. A fresh token cannot take another outstanding hold.
The wrapper waits for an owner-correlated `started` receipt before launching the command.
It sends `ok` only after exit zero, and `failed` after a nonzero exit, launch
failure or interruption. A lost connection is never replaced mid-build. A
missing agent or missing start receipt prevents compilation; an unconfirmed
finish returns nonzero. A confirmed finish preserves the command's exit code.
The app keeps running. Class output from a failed build stays held until a
subsequent successful build by the same owner. If the wrapper is killed, the
installed hold stays in place. Run another complete wrapped build with the
printed `--owner TOKEN` to recover; do not send ok merely
to clear the warning. A lost ok receipt leaves an uncertain result, not proof
that no reload occurred.

Different named owners and unowned IDE commands cannot change an owned hold.
A named start cannot take an active legacy hold. Ownership releases after
successful output capture, asynchronously after the ok receipt. Lost owner
tokens require an application/agent restart; no timeout or force unlock exists.
Tokens identify cooperating builds; they are not authentication and cannot
protect output from a compiler that bypasses the hold. Use a unique token for
each independent build and retain it until recovery is complete.
Use compile-only commands, not long-running `bootRun`/`spring-boot:run`. Normal
unwrapped builds retain legacy behavior. The Gradle/Maven attachment plugins
do not install this wrapper automatically. Receipt confirms the build signal,
not completion of live reload; exercise the endpoint/test to verify behavior.

## Protecting a build through MCP

For a client that launches its own compiler, call `reclazz_build` with
`{"state":"started","owner":"build-unique-id","portFile":"/absolute/path/.reclazz/agent.port"}` and
require `isError:false` **before** launching compilation. Afterwards call the
same tool against the same running agent with the same `owner` and `state:"ok"` only for exit zero,
or `state:"failed"` for failure/cancellation. Do not restart the app or run
another builder between those calls. If the target changes, stop the sequence;
the terminal wrapper is preferable when one persistent connection is required.
`reclazz_scan` alone provides no failed-build protection and cannot release a
hold. The MCP server signals states; it does not launch arbitrary build commands
or place compiler output into its JSON-RPC stream. The caller must choose and
retain a unique owner; it is required in both supported MCP protocol versions.
Missing or malformed owner is an invalid argument. Ownership rejection or an
old/wrong-owner receipt is a tool error. Use the retained owner for started,
failed and successful recovery; a disconnected MCP call does not release it.

## Verifying the compiled change

For a concrete first installation, use the [Maven verification recipe](../examples/maven-spring-boot/README.md#verify-the-installation):
it combines DOCTOR identity, class-file hashing, this receipt and an observable
response, with next steps for wrong targets, stale sessions and unwatched output.

Build and attach matching current agent/MCP jars; older published agents may not
support this tool's socket command. After a successful protected compilation,
compute SHA-256 of each changed `.class` file and call `reclazz_verify` with its
binary name (`com.acme.OrderService`, or `com.acme.OrderService$Nested`), `sha256`,
and the same `portFile`/`port` used for the build. It returns JSON text with
requestId, sessionId, className, expectedSha256, observedSha256, status, source,
detail and completedAt. Request IDs are generated and checked by the MCP client.

Require `isError:false` and `status:"applied"`, matching both hashes. All other
states have `isError:true`: `running`, `not_observed`, `mismatch`, `failed` or
`unverified`. Read the detail and poll with a bounded deadline for asynchronous
completion; do not infer success from an increasing global counter. Connection,
timeout and malformed-receipt failures return `status:"unavailable"` and
`isError:true`. Bad input returns JSON-RPC invalid params. Optional string
`timeoutMs` bounds each socket request (default 5000, range 1–60000).

A fresh session ID invalidates assumptions from the earlier app. The receipt is
bounded, latest-only evidence that the exact captured bytes finished the reload
batch, including deferred refresh; it is not a build job ID or an atomic result
for several classes. Warnings, ambiguous classloaders and never-loaded classes
cannot produce a positive receipt. See [the receipt contract](protocol.md#verifying-exact-compiled-bytes).
After verifying the relevant classes, exercise the live endpoint or test to
prove the intended application behavior.

## Check the target before an agentic build

Call `reclazz_doctor` using the same endpoint arguments as the other tools.
Its default timeout is 5000 ms. The tool reads one correlated DOCTOR response and
validates its types and bounds. Missing, old, malformed, oversized or uncorrelated
responses produce `isError:true`, `status:unavailable`; capability support remains
unknown. Both supported MCP versions receive JSON text; 2025-06-18 also receives
the identical object as structured content.

Compare the target `pid`, `workingDirectory` and `sessionId` against the application
intended for the build. `clientWorkingDirectory` belongs to the MCP process;
neither directory is an inferred repository root. `sessionId` matches VERIFY for
this agent instance. Capability booleans reflect installed handlers; observation
can legitimately show incomplete startup. `watcherState`, live directory count,
refused registration count and a bounded path sample expose watcher evidence.
`watchSampleTruncated:true` means absence from the sample proves nothing about
coverage. The sample can include source and resource watches too.

Follow `nextActions` to inspect startup, output paths or a held build. `buildHold`
reports only the kind of hold, never its owner token, and doctor cannot release it.
Even a successful `status:observed` reports `reloadConfirmed:false`; follow the
owned-build, exact-byte VERIFY and application behavior checks for actual proof.

## Bounded batch verification and cancellation

`reclazz_verify_batch` takes `items`, an array of 1–32 objects containing only
`className` and lower-case `sha256`. Class names must be unique and follow the
single-VERIFY rules. The complete array is validated before opening a connection.
Endpoint arguments remain the same. `timeoutMs` is a string from 1 to 60000,
default `"5000"`, covering connect, handshake and every receipt together.

The tool composes existing VERIFY commands sequentially on one socket; it neither
builds nor polls automatically. `results` preserves input order. Validated earlier
receipts are retained when a timeout, disconnect, malformed receipt or changed
session makes the remaining entries unavailable. It does not reconnect or combine
proof from different agent sessions. Each socket reply is limited to 16384
characters, with at most 32 replies. `sessionId` is empty if none was validated.

`status:applied` and `allApplied:true` require every individual receipt to be
applied; `incomplete` means all receipts arrived but at least one was not applied.
`unavailable` means one or more receipts could not be validated. Both latter cases
return `isError:true`. Even all-applied is a sequence of point-in-time observations,
not a transactional reload or guarantee that earlier receipts remain current.
`atomic` and `reloadConfirmed` remain false. Test application behavior separately.
Both supported MCP versions return JSON text; 2025-06-18 also returns the identical
object as structured content with its output schema.

The stdio server accepts one batch at a time with no queued work. While it is
active, `ping` and `tools/list` remain responsive; other requests receive JSON-RPC
error -32000 and can be retried after completion/cancellation. Duplicate active IDs
are invalid. Initialization therefore cannot change a batch's negotiated output
version while it runs. Existing non-batch tools retain synchronous behavior.

For a pending batch, send a notification with the original request ID and no `id`:

```json
{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":42,"reason":"No longer needed"}}
```

Cancellation closes the batch socket, including during handshake or reply waits,
and suppresses its response if cancellation wins the completion race. Numeric and
string IDs are distinct. Unknown, completed and malformed cancellations are
ignored. Await-reload uses this same worker and cancellation mechanism;
initialize and other tools are not cancellable. End of stdin also
cancels the active batch and frees its socket; keep stdin open while awaiting a
batch result. Cancelling observation does not cancel application reload, undo
changes, release a build hold or reclaim its owner. A result already sent may
race with cancellation; clients should ignore such a late response, as described
in the [2025-06-18 cancellation specification](https://modelcontextprotocol.io/specification/2025-06-18/basic/utilities/cancellation)
and its [2024-11-05 counterpart](https://modelcontextprotocol.io/specification/2024-11-05/basic/utilities/cancellation).

### Build-plugin entrypoints

The Gradle plugin's explicit `reclazzSafeBuild` task and Maven plugin's standalone
`safe-build` goal reuse the local MCP jar's `BuildMain` for one owned connection
around a complete child build. See [Gradle safety](gradle-plugin.md#opt-in-whole-build-safety)
and [Maven safety](maven.md#opt-in-whole-reactor-safety). They do not change plain
compile/build invocations, add automatic `ok` finalizers, or imply a published
plugin release. Retain the owner for recovery and collect exact-byte VERIFY
receipts separately after a successful build.

A reproducible acceptance harness lives at `scripts/test-build-plugin-safety.py`.
Build local agent/MCP/Gradle-plugin jars and package the Maven plugin, then run:

```text
python3 scripts/test-build-plugin-safety.py --work-dir /tmp/reclazz-safety-proof --gradle /absolute/path/to/gradle
```

It requires a fresh work directory, Java/Javac 17+, Gradle 8.10.2, Maven 3.9.x and
an already populated offline Maven cache. `--help` lists explicit artifact and
tool paths. It does not publish or install to the user's Maven repository; it
uses a temporary cache overlay. Each tool exercises two real modules and a live
agent: opt-out, partial failure, competing owner rejection, same-owner recovery,
exact-byte receipts, up-to-date barriers and offline refusal before output.
Logs and `evidence.json` stay in the work directory. A missing tool/dependency is
a failed prerequisite, never a silently skipped success. Mac/Linux local results
do not prove Windows compatibility.

## Pause and resume automatic reload

`reclazz_reload_control` accepts `action: "pause"`, `"resume"` or `"status"`
and the same `port`/`portFile` endpoint options as other tools. For example:

```json
{"name":"reclazz_reload_control","arguments":{"action":"pause","portFile":"/project/.reclazz/agent.port"}}
```

Wait for `status: "acknowledged"`. The result reports `paused`,
`pendingClasses`, `pendingActions`, and `buildHold` (`none`, `named`, `legacy`)
as an admission-state snapshot. `reloadConfirmed` is always false. On
`unavailable`, the action may have reached the agent; inspect `status` before
retrying. Unsupported older agents time out instead of appearing successful.

Pause retains observed class/source/resource changes; SCAN does not apply them.
Resume schedules current output, but never clears an active or failed BUILD
hold. A successful BUILD does not clear manual pause. Already admitted work
may finish. The switch is shared by all clients of this agent, survives client
disconnect, and is not a per-client ownership lock. Repeated pause/resume does
not discard pending changes. See [the socket contract](protocol.md#pausing-automatic-reload)
for admission and count boundaries. No IDE control or automatic rollback is added.

## Waiting for a reload

`reclazz_await_reload` keeps a bounded event subscription in this MCP process.
Take a checkpoint **before** editing or compiling, retain the returned
`sessionId`, `subscriptionId` and `cursor`, then wait on that same target:

```json
{"name":"reclazz_await_reload","arguments":{"action":"checkpoint","timeoutMs":"5000"}}
```

After saving, copy the returned identities into the wait call. The following
identities are placeholders, not a receipt from a running application:

```json
{"name":"reclazz_await_reload","arguments":{"action":"wait","sessionId":"returned-session","subscriptionId":"returned-subscription","afterCursor":"0","filePath":"/project/src/main/java/com/example/Service.java","ownerClassName":"com.example.Service","timeoutMs":"10000"}}
```

Source mode accepts an absolute normalized `.java` path and requires an explicit
`ownerClassName` to produce a useful result. Without the owner it returns
`unavailable` with `reasonCode:owner_required`. It observes `Compiled <basename>`
followed by an exact-owner reload, excluding inner-class-only events. The agent
does not include the full source path in that compile event. Visible ambiguous
same-basename sequences fail closed; this does not detect every path collision.

Alternatively, select `className` instead of `filePath`/`ownerClassName` for
externally compiled output. Class mode requires a new exact binary-owner reload
event after the checkpoint, without inventing a COMPILE event. Both modes accept
optional lower-case compiled-owner `sha256`. Pass the expected compiled hash
when available. Without it, the tool discovers the latest observed hash with
one VERIFY query and checks that hash again; discovery alone never confirms a
reload. `hashSource:caller` distinguishes supplied bytes from `hashSource:receipt`.

| Field or result | Meaning |
|---|---|
| `correlationScope:received_events` | The cursor orders events received on this unbroken MCP subscription, not agent attempt starts |
| `sourcePathConfirmed:false` | The compile event cannot prove a full source path |
| `editRevisionConfirmed:false` | A previous in-flight attempt can finish after checkpoint; the tool does not prove an arbitrary source revision was compiled |
| `ready` | Checkpoint established; no reload is confirmed |
| `applied` | Exact-owner reload observed and a completed exact-byte VERIFY receipt validated in the same JVM session; `reloadConfirmed:true` |
| `compile_error` | A new compiler-error block names that source basename and line; bounded diagnostic lines retained |
| `refused` | Exact-owner hot-swap failure with a compatible failed byte receipt |
| `restart_required` | The observed owner reports a superclass change that was not applied; independently salvageable bodies may have changed |
| `held` | Agent-wide pause or BUILD hold observed; it does not prove this target was pending, and admitted work may finish |
| `timeout` | No qualifying evidence within the total deadline; never success |
| `unavailable` | Missing owner, lost/foreign cursor, changed session/target, malformed or contradictory receipt, or unavailable connection; inspect `reasonCode` |

Only `ready` and `applied` return `isError:false`. All outcomes carry the three
correlation-limit fields above. `applied` proves a point-in-time completed byte
receipt, not application behavior, source revision, atomic reload or retained
bean state. Exercise the live endpoint/test afterward. Typed restart diagnosis
currently recognizes the agent's superclass-change warning; unknown failures
cannot become success through an unverified receipt. This is not an exhaustive
classifier for SAP resource operations.

BUILD ok acknowledges scheduling. A subsequent await can still report `held`
before the agent accepts the queued build output. Reuse the same checkpoint
and recheck after the caller's successful BUILD sequence; await never sends
BUILD, SCAN, pause, resume, compilation or restart commands.

Timeout covers the entire call, including connection, handshake, target checks
and receipt queries, with the usual 5000ms default and 60000ms maximum. This MCP
process retains at most two target subscriptions, each with at most 2,048 events
and 1,048,576 retained Java characters. Returned diagnostics contain at most 16 lines of
512 characters, with `diagnosticsTruncated` when clipped. Old or evicted cursors
return unavailable. A new connection has a new subscription identity even if
the application session is unchanged; obtain a new checkpoint instead of
silently reusing the old cursor.

Await and events use the same bounded worker slot as verify-batch. Ping and tools/list
remain available during a wait. Cancellation closes the observation socket,
invalidates its cursor and suppresses a late response; it does not undo an
application reload. A later checkpoint can connect again. EOF closes all MCP
subscriptions, and idle target eviction never discards an active wait.


## Inspecting a batch of events

Call `reclazz_await_reload` with `action:checkpoint` before editing. Keep that
response's `sessionId`, `subscriptionId` and `cursor`. The read-only
`reclazz_events` tool lists received observations from this same persistent
subscription; it has no separate checkpoint action.

```json
{"name":"reclazz_events","arguments":{"sessionId":"COPY_SESSION","subscriptionId":"COPY_SUBSCRIPTION","sinceCursor":"0","limit":"50"}}
```

Replace `sinceCursor` with the checkpoint's cursor on the first call, then use
each page's `nextCursor`. Reuse the same target arguments. Pages contain at most
`limit` matching events, an integer string from `1` to `100`, default `50`.
An empty healthy page returns immediately. `timeoutMs` is the total call budget,
including target and session checks, default `5000`, maximum `60000`.

| Page field | Meaning |
|---|---|
| `events` | Received events in ascending local sequence order |
| `nextCursor` | Last inspected event, including excluded events; feed it into the next `sinceCursor` |
| `snapshotHead` | Head captured together with this page's input history; later arrivals belong to subsequent queries |
| `hasMore` | Uninspected events remain through that head; they may not match your filters |
| `correlationScope:received_events` | Local receive order, not a durable agent history or attempt identifier |
| `sourcePathConfirmed:false`, `editRevisionConfirmed:false` | No full source path or source revision attribution |
| `reloadConfirmed:false` | Listing an event never validates an applied byte receipt or application behavior |

For example, if events `2`, `4` and `6` match and `limit` is `2`, page one
returns `2` and `4`, with `nextCursor:"4"`. The next page can return `6`.
A page with no matches still advances through its inspected snapshot. Reading
the same input cursor again deliberately replays retained history; only
traversal through `nextCursor` avoids duplicates. If you change filters after
advancing, previously excluded events are not replayed. Keep an earlier valid
cursor for another view.

Optional filters are exact `level`, binary `ownerClassName`, and literal Java
`fileBasename`, for example `Service.java`. Levels are INFO, OK, WARN, ERROR,
COMPILE, RELOAD and STRUCTURAL_RELOAD. Filenames have no directory separators and are matched literally,
without glob or regular-expression matching. Filters combine with AND on **one event**. A COMPILE
usually has a filename but no owner; a RELOAD has an owner but no filename.
Combining these filters does not link adjacent events. Query them separately
from the same checkpoint when inspecting both kinds of evidence:

```json
{"name":"reclazz_events","arguments":{"sessionId":"COPY_SESSION","subscriptionId":"COPY_SUBSCRIPTION","sinceCursor":"0","fileBasename":"Service.java"}}
{"name":"reclazz_events","arguments":{"sessionId":"COPY_SESSION","subscriptionId":"COPY_SUBSCRIPTION","sinceCursor":"0","ownerClassName":"com.example.Service"}}
```

Each row carries `sequence`, `level`, `timestamp`, `message`, `messageTruncated`
and a parsed `type`. The timestamp comes from the agent's broadcast; missing
or invalid timestamps are explicit `null`. Sequence orders rows even when
clock times repeat or move backwards. Messages retain at most 512 Java
characters, with `messageTruncated:true` when clipped. Parsing uses the full
bounded received message, and optional filename/owner fields appear only when
that event explicitly admits them. An inner-class display folded to
`Outer (inner class)` cannot identify an exact binary owner.

Types are `compile`, `reload`, `structural_reload`, `compile_error`,
`compiler_diagnostic`, `refused`, `framework_failure`, `restart_required` and
`unknown`. These describe emitted text; even `reload` confirms no completed
receipt. A compiler diagnostic identifies a basename, not a full source path
or a reconstructed compile batch. Unknown events remain visible. SAP resource
messages are not yet exhaustively classified. Use `await_reload`/`verify` and
an endpoint or application test to confirm a change.

The two-target registry and each 2048-event/1,048,576-character ring are shared
with await. Timestamp characters count toward retention. Evicted input cursors
return `unavailable/cursor_evicted`; future cursors return `invalid_cursor`.
Disconnect, changed target/session, idle eviction or cancellation invalidates
the subscription. The tool returns no partial page or advanced cursor on
failure and never reconnects silently. Obtain an explicit new checkpoint;
it cannot recover history lost before connection or after eviction. An
unavailable result is a tool error, not a healthy empty page. Cancellation
closes the shared stream, invalidating its await cursors too; ping and tools/list
remain available during a blocked read. No BUILD, SCAN, pause, resume,
compilation or restart command is sent by event listing.
