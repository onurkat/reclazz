# For AI agents

This page is for a coding agent (Claude Code, Cursor, Copilot, Windsurf, Aider
and the like) working on a Spring Boot or SAP Commerce project. It tells you how
to run that project with Reclazz so your code edits take effect in the running
JVM without a restart, and how to confirm that they did.

## What Reclazz gives your edit-run loop

Reclazz is a `-javaagent` that watches compiled class files and hot-swaps them
into the running JVM in place. State is preserved, so the app keeps running
across your edits. It is free, open source, sends no telemetry, and needs no
IDE: everything below is a shell command or a build-file edit, the two things
an agent already does.

## Turn it on

Pick the build you are in. Each path attaches the agent to the dev run and the
test JVM, so a plain `bootRun` / `mvn test` runs against reloaded classes.

### Gradle (plugin)

```kotlin
plugins {
    id("org.springframework.boot") version "3.3.0"
    id("com.onurkat.reclazz") version "1.3.0"
}
```

The plugin resolves `com.onurkat.reclazz:reclazz-agent` from Maven Central and
wires the flag into `bootRun` and `Test`. It is published on the Gradle Plugin
Portal, so the `id(...) version "1.3.0"` form above resolves as-is. Details and
options: [gradle-plugin.md](gradle-plugin.md).

### Maven

No plugin is needed. Let the dependency plugin resolve the agent into a property
and reference it from Surefire and `spring-boot:run`; a `prepare-agent` plugin
is available too. Both are in [maven.md](maven.md).

### Any JVM (works everywhere today)

The agent is on Maven Central as `com.onurkat.reclazz:reclazz-agent`. Resolve or
download the jar, then attach it at startup:

```bash
java -javaagent:/path/to/reclazz-agent.jar=platform=spring,watchDirs=build/classes/java/main -jar app.jar
```

The flag must be present when the JVM starts; the agent only reloads classes it
transformed at load time. Every argument is in [usage.md](usage.md).

## Confirm it attached and verify a reload

Do not assume the swap happened; check it. Two machine-readable ways:

- Gradle task, one JSON line:

  ```
  ./gradlew reclazzStatus
  ```

  ```json
  {"attached":true,"agent":"1.3.0","protocol":1,"port":54123,"health":["Reloads: 3, failures: 0, median 12ms"]}
  ```

  `{"attached":false,"reason":"..."}` when nothing is running. See
  [gradle-plugin.md](gradle-plugin.md#checking-status).

- MCP server, if your client speaks MCP: point it at `reclazz-mcp.jar` and call
  the `reclazz_status`, `reclazz_verify`, `reclazz_pending` and `reclazz_diagnose`
  tools during your loop. Setup and the tool table:
  [mcp-server.md](mcp-server.md).

For failed-build protection, use the [terminal build wrapper or acknowledged
MCP build sequence](mcp-server.md#protecting-terminal-builds) with matching jars
that support build receipts. Plain compile commands do not provide that barrier.

The basic loop: edit code, recompile (`./gradlew classes` or `mvn
compile`), let the agent swap it, then hit the endpoint or run the test. If a
change did not take, `reclazz_diagnose <class>` or the logs say why, and
`reclazz_pending` lists what genuinely needs a restart (a changed superclass, a
reordered enum). Restart only for those.

## Drop-in AGENTS.md recipe

Paste this into the target project's `AGENTS.md` (or `CLAUDE.md`, `.cursorrules`,
whatever your setup reads) so the agent that works on that project keeps using
the hot-reload loop instead of restarting on every change. Adjust the build
commands to the project.

```markdown
## Hot reload with Reclazz

This project runs with the Reclazz agent attached, so a running app picks up
compiled changes without a restart. Prefer this loop over restarting.

Run the app (agent attaches automatically):
    ./gradlew bootRun          # Gradle, Reclazz plugin applied
    # or: mvn spring-boot:run  # Maven, see the project's POM

Apply a code change while it runs:
1. Edit the code.
2. Recompile with the configured Reclazz build wrapper around `./gradlew classes`
   or `mvn compile`. If using MCP, require acknowledged `reclazz_build started`
   before compilation, then send `ok` only on exit zero or `failed` otherwise.
   This requires an agent with build receipt support; stop if unconfirmed.
3. The agent hot-swaps the changed classes in place. Do not restart.
4. Verify each changed compiled class with `reclazz_verify`, passing className,
   its compiled file's lower-case SHA-256 as sha256, and the same agent address.
   Require status applied, matching hashes and the same sessionId. Poll with a
   bounded deadline; any other status is not proof of success. This requires
   matching agent/MCP jars with VERIFY support. Then exercise the endpoint or
   run the test. An attached status or rising reload count alone proves no edit.

Restart only when Reclazz reports the change as pending (for example a changed
superclass or a reordered enum). Check with `./gradlew reclazzStatus`, or the
`reclazz_pending` MCP tool if configured. Everything else reloads live.
```

## Why an agent can rely on this

- Zero config: the flag alone works, no config file required.
- No telemetry, open source: nothing phones home, so a safety layer around the
  agent has nothing to block.
- Loopback only: the status socket and the MCP server open one connection to
  `127.0.0.1` and reach nothing off the machine. The shipped agent jar opens no
  client socket at all; its own tests enforce that.
