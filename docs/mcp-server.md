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

The wrapper waits for an acknowledged `started` before launching the command.
It sends `ok` only after exit zero, and `failed` after a nonzero exit, launch
failure or interruption. A lost connection is never replaced mid-build. A
missing agent or missing start receipt prevents compilation; an unconfirmed
finish returns nonzero. A confirmed finish preserves the command's exit code.
The app keeps running. Class output from a failed build stays held until a
subsequent successful build. If the wrapper is killed, the installed hold stays
in place. Run another complete wrapped build to recover; do not send ok merely
to clear the warning. A lost ok receipt leaves an uncertain result, not proof
that no reload occurred.

Only one builder may target a given agent at a time, including IDE builds.
Use compile-only commands, not long-running `bootRun`/`spring-boot:run`. Normal
unwrapped builds retain legacy behavior. The Gradle/Maven attachment plugins
do not install this wrapper automatically. Receipt confirms the build signal,
not completion of live reload; exercise the endpoint/test to verify behavior.

## Protecting a build through MCP

For a client that launches its own compiler, call `reclazz_build` with
`{"state":"started","portFile":"/absolute/path/.reclazz/agent.port"}` and
require `isError:false` **before** launching compilation. Afterwards call the
same tool against the same running agent with `state:"ok"` only for exit zero,
or `state:"failed"` for failure/cancellation. Do not restart the app or run
another builder between those calls. If the target changes, stop the sequence;
the terminal wrapper is preferable when one persistent connection is required.
`reclazz_scan` alone provides no failed-build protection and cannot release a
hold. The MCP server signals states; it does not launch arbitrary build commands
or place compiler output into its JSON-RPC stream.

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
