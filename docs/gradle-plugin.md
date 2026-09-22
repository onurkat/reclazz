# Reclazz Gradle plugin

The plugin attaches the Reclazz agent to a project's run and test JVMs, so you
do not hand-write a `-javaagent` flag. Apply it and `bootRun` and the test
tasks start with the agent already attached, watching the main output directory.

The plugin id is `com.onurkat.reclazz`.

## What it does

- Adds `-javaagent:<reclazz-agent.jar>=platform=spring,watchDirs=<main output>`
  to the `bootRun` task when the Spring Boot plugin is applied.
- Adds the same flag to `Test` tasks, so tests run against reloaded classes.
- Resolves the agent jar from the coordinate
  `com.onurkat.reclazz:reclazz-agent:<version>`, or from an explicit path you set.

## Apply it

The agent is on Maven Central, so applying the plugin is enough for a standard
Spring Boot project:

```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.3.0"
    id("com.onurkat.reclazz") version "1.3.0"
}
```

Then run your app the way you already do:

```
./gradlew bootRun
```

The plugin resolves `com.onurkat.reclazz:reclazz-agent` at the version you
applied and attaches it. No `reclazz` block is needed for the common case.

### Offline, or pinned to a local jar

To use a jar you already have rather than resolving one, set `agentJar`. It
wins over the coordinate, so nothing is downloaded:

```kotlin
reclazz {
    // e.g. the jar from https://github.com/onurkat/reclazz/releases
    agentJar.set(file("libs/reclazz-agent-1.3.0.jar"))
}
```

## Options

```kotlin
reclazz {
    enabled.set(true)                       // turn the agent on or off
    agentJar.set(file("libs/agent.jar"))    // explicit jar; wins over agentVersion
    agentVersion.set("1.3.0")               // coordinate version when no agentJar
    platform.set("spring")                  // defaults to spring under the Boot plugin
    watchDirs.set(listOf("build/classes/java/main"))  // defaults to the main output
    arguments.put("debounceMs", "300")      // extra agent args as key=value
    arguments.put("verbose", "true")
    applyToBootRun.set(true)
    applyToTest.set(true)
}
```

Anything in `arguments` is appended as `key=value` and overrides the computed
`platform` and `watchDirs`, so you can express any agent argument the flag
accepts. For SAP Commerce, set `platform` and the Hybris arguments explicitly,
for example `arguments.put("hybrisHome", "/opt/hybris")`.

## Checking status

The plugin adds a `reclazzStatus` task that prints, as one JSON line, whether
the agent is attached to a running app and how it is doing. It is meant for a
person or a coding agent to check the inner loop:

```
./gradlew reclazzStatus
```

```json
{"attached":true,"agent":"1.3.0","protocol":1,"port":54123,"health":["Reloads: 3, failures: 0, median 12ms"]}
```

When nothing is attached it prints `{"attached":false,"reason":"..."}`. It reads
the agent's loopback status socket and asks nothing of the agent beyond its own
state. Options: `--port-file`, `--port`, `--hybris-home`, `--timeout-ms`.

## Notes

- The agent only reloads classes it transformed at load time, so the flag must
  be present at JVM startup. The plugin adds it to task startup for you.
- The agent is published at `com.onurkat.reclazz:reclazz-agent` on Maven
  Central, which is what the version-only form resolves.
- `reclazzStatus` lives in the plugin, not the agent jar, because the agent's
  tests forbid the shipped agent from opening a client socket; the status
  client that must open one to loopback belongs beside the build.

## Opt-in whole-build safety

A plugin build containing `reclazzSafeBuild` can protect a **separate, finite
child Gradle invocation** against partial compilation. Ordinary `build`,
`compileJava`, `bootRun` and `test` are unchanged. Apply the plugin at the root,
use a locally built standalone MCP jar containing the owned `BuildMain`, and
configure the root task:

```kotlin
tasks.named<com.onurkat.reclazz.gradle.ReclazzSafeBuildTask>("reclazzSafeBuild") {
    mcpJar.set(file("tools/reclazz-mcp-1.3.0.jar"))
    port.set(54123) // explicit loopback agent endpoint; check its doctor identity first
    owner.set("builder-alice") // retain for recovery; do not share with concurrent builders
    timeoutMs.set(5000) // acknowledgement timeout, not a build duration limit
    buildArguments.set(listOf("--offline", ":service:classes", ":web:classes"))
    // gradleExecutable defaults to the current Gradle installation.
    // buildDirectory defaults to this root project.
}
```

Invoke **only** `./gradlew reclazzSafeBuild --no-configuration-cache`. Do not add
other tasks, dependencies or finalizers: the outer task graph is checked before
any task runs. Continuous mode and outer configuration cache are rejected. The
child uses the configured argument list, a fresh temporary project cache and
`--no-daemon` (so it cannot deadlock on the parent's project-cache lock). Specify
child properties/settings explicitly; outer CLI options are not automatically
forwarded. The safety task has no up-to-date/cache shortcut, even when every child
compile task is up-to-date. Nested safe builds are refused.

The standalone wrapper acquires a named BUILD hold and waits for its correlated
acknowledgement **before launching the child**. It sends `ok` only after the
whole child exits zero; compilation failure sends `failed` and preserves the
hold. A missing agent, rejected ownership or missing acknowledgement fails the
task. The MCP jar is an explicit local file, not a new plugin dependency or a
network download. A plugin version without this task must be rebuilt locally;
these instructions do not imply a published release.

After a failed build, fix the sources and repeat the complete build with the
**same owner**. A different owner cannot release or compile through that hold.
Keep the owner private to one builder; do not run concurrent builds with the same
owner. A failed/disconnected run is not permission to send `BUILD ok` manually.
After recovery, use exact-byte `VERIFY`/`reclazz_verify_batch` receipts and check
application behavior: successful compilation alone confirms no reload.

Protection covers only outputs written synchronously by the selected child build
to the selected agent's watched directories. Configuration-time writes by the
outer build, external writers, asynchronously spawned work, unselected modules
and other agents are outside that boundary. It is not an atomic reload or rollback.
Gradle 8.10.2 is the exercised version; other versions need separate acceptance.
