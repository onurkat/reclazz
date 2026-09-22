# Reclazz MCP server

An [MCP](https://modelcontextprotocol.io) server that turns the running Reclazz
agent into tools a coding agent can call: check whether Reclazz is attached,
nudge a scan after a build, ask what still needs a restart, or diagnose why a
class did not reload. It is a thin, loopback-only client of the agent's status
socket and asks the agent nothing beyond its own state.

It lives in `:mcp-server`, not the agent jar, because the agent's own tests
forbid the shipped agent from opening a client socket.

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
existing configuration. Replace both example paths and `X.Y.Z`; use the absolute
path to your Java 17+ executable if the client cannot find `java` on its PATH:

```json
{
  "mcpServers": {
    "reclazz": {
      "command": "/absolute/path/to/java",
      "args": ["-jar", "/absolute/path/to/reclazz-mcp-X.Y.Z.jar"]
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

The application must separately run with the Reclazz agent attached. Client
working directories vary: use an **absolute** `portFile` in tool arguments, for
example `{"portFile":"/absolute/project/.reclazz/agent.port"}`. `port` and
`hybrisHome` are alternatives. Without an explicit address, discovery looks for
`.reclazz/agent.port` and `.idea/reclazz/agent.port` relative to the server process
working directory. They are tool arguments, not MCP server startup flags.

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
jar's version; the tools list contains the six tools below. The notification
has no response. EOF closes the server. If Java cannot open the jar, check the
absolute path; if it cannot load its classes, use the packaged jar from
`build/distributions/mcp`, not the plain development jar. A missing attached
application is a separate condition: `reclazz_status` reports `attached:false`.

## Tools

| Tool | What it does |
|---|---|
| `reclazz_status` | Whether the agent is attached and how it is doing (reloads, failures, latency, watched directories), as JSON |
| `reclazz_build` | Signal `started`, `ok` or `failed`; waits for the agent to acknowledge the signal, and returns a tool error if unconfirmed |
| `reclazz_verify` | Structured exact-byte reload receipt; requires `className` and lower-case `sha256`; only `status:applied` is success |
| `reclazz_scan` | Ask the agent to look at the watched directories now and reload changed classes, instead of waiting for its next poll |
| `reclazz_pending` | What still needs a restart in this session |
| `reclazz_diagnose` | Why a given class did or did not reload last time (requires `className`) |

Each tool accepts optional `portFile`, `port` and `hybrisHome` arguments to
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
| `reclazz_status` | Existing `attached`, optional `agent`, `protocol`, `port`, `health`, `reason` fields |
| `reclazz_scan` | `status:sent` or `unavailable`, `detail`, `reloadConfirmed:false` |
| `reclazz_build` | `status:acknowledged` or `unavailable`, requested `state` and `owner`, `detail`, `reloadConfirmed:false` |
| `reclazz_pending` | `status:observed` or `unavailable`, `lines`, `detail`, `complete:false` |
| `reclazz_diagnose` | Same diagnostic fields plus requested `className` |
| `reclazz_verify` | Existing correlated receipt fields; `status:applied` alone is success, or the existing `unavailable` failure object |

`sent` confirms a socket write, `acknowledged` confirms the BUILD signal, and
`observed` describes collected diagnostic lines. None proves reload completion.
The diagnostic `complete:false` reflects the lack of a response end marker in
the broadcast stream. VERIFY preserves its exact-byte checks, and application
behavior still needs its own test.

Status, pending, diagnose and verify advertise `readOnlyHint:true` and
`openWorldHint:false`. Scan and build advertise `readOnlyHint:false`,
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
`timeoutMs` is an integer string in 1–60,000, with defaults of 5,000 for
build/verify and 2,000 for other tools. Paths with spaces are supported.
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
