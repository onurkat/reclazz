# The Status Socket

What the agent says while it runs, and the five things it can be asked, so
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
| `BUILD <state>` | hold class files when a build starts; accept the complete captured output only after success; failure keeps the hold. Arguments are case-insensitive. An unknown argument is ignored |
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
These commands describe one ordered build stream per agent. Multiple
independent builders writing the same output directories are not supported.
Agents receiving no BUILD signals keep their usual behavior.

For a whole Gradle or Maven build, send the start before invoking the build
tool. A `classes.doFirst` hook runs after its compilation dependencies and
is too late to protect them. This local wrapper sends the result too:

```python
import pathlib, socket, subprocess
port = int(pathlib.Path(".reclazz/agent.port").read_text().strip())
with socket.create_connection(("127.0.0.1", port)) as client:
    def signal(state):
        client.sendall(("BUILD " + state + "\n").encode("utf-8"))
    signal("started")
    result = subprocess.run(["./gradlew", "classes"])  # or ["mvn", "compile"]
    signal("ok" if result.returncode == 0 else "failed")
raise SystemExit(result.returncode)
```

Sending only `BUILD ok` on a connection releases an abandoned hold after a verified build.
Run only one wrapper at a time for a given agent.

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
