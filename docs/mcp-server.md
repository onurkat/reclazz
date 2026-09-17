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
| `reclazz_scan` | Ask the agent to look at the watched directories now and reload changed classes, instead of waiting for its next poll |
| `reclazz_pending` | What still needs a restart in this session |
| `reclazz_diagnose` | Why a given class did or did not reload last time (requires `className`) |

Each tool accepts optional `portFile`, `port` and `hybrisHome` arguments to
locate the agent; `reclazz_diagnose` also requires `className`.

## What it does not do

It opens one connection, to `127.0.0.1`, to the agent already running on this
machine. It sends no telemetry and reaches nothing off the machine, the same
promise the agent itself keeps.
