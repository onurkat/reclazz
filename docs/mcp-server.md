# Reclazz MCP server

An [MCP](https://modelcontextprotocol.io) server that turns the running Reclazz
agent into tools a coding agent can call: check whether Reclazz is attached,
nudge a scan after a build, ask what still needs a restart, or diagnose why a
class did not reload. It is a thin, loopback-only client of the agent's status
socket and asks the agent nothing beyond its own state.

It lives in `:mcp-server`, not the agent jar, because the agent's own tests
forbid the shipped agent from opening a client socket.

## Running it

The server speaks JSON-RPC over stdio, so an MCP client launches it as a
process. Build the jar with `./gradlew :mcp-server:shadowJar` (or take it from
a release), then point your client at it. A typical `mcpServers` entry:

```json
{
  "mcpServers": {
    "reclazz": {
      "command": "java",
      "args": ["-jar", "/path/to/reclazz-mcp.jar"]
    }
  }
}
```

The tools only return useful data while an application is running with the
Reclazz agent attached (that is what opens the status socket). Point them at a
specific agent with the `portFile`, `port` or `hybrisHome` arguments; otherwise
they look in the usual places (`.reclazz/agent.port`, `.idea/reclazz/agent.port`).

## Tools

| Tool | What it does |
|---|---|
| `reclazz_status` | Whether the agent is attached and how it is doing (reloads, failures, latency, watched directories), as JSON |
| `reclazz_build` | Signal `started`, `ok` or `failed`; waits for the agent to acknowledge the signal, and returns a tool error if unconfirmed |
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
