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
