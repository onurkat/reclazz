# The Status Socket

What the agent says while it runs, and the four things it can be asked, so
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

A client may send one line at a time, at most 512 bytes; anything not
listed here is ignored without an answer, and a line that never ends closes
the connection. Every answer arrives as ordinary `INFO` lines on the stream,
to every client.

| Command | Answer |
|---|---|
| `DIAGNOSE <class>` | why the class did or did not reload last time: the class file the bytes came from, the outcome, what a restart would change |
| `PENDING` | what still needs a restart in this session |
| `HEALTH` | how the session is going: reloads, failures, latency, watched directories, a reload that is still running |
| `SCAN` | look at the watched directories now, instead of on the file watcher's next poll; what changed is reloaded as usual. Send it when a build has just finished |

Nothing a client sends makes the agent load, reload or run anything it
would not have on its own.

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
