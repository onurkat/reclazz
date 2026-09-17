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

## Today: point at the agent jar

The agent is not on a public registry yet, so for now give the plugin the jar
you downloaded from the GitHub releases page.

```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.3.0"
    id("com.onurkat.reclazz")
}

reclazz {
    // The reclazz-agent jar from https://github.com/onurkat/reclazz/releases
    agentJar.set(file("libs/reclazz-agent-1.3.0.jar"))
}
```

Then run your app the way you already do:

```
./gradlew bootRun
```

## Once the agent is on Maven Central

When the agent is published, drop `agentJar` and the plugin resolves it by
version (the version defaults to the plugin's own):

```kotlin
plugins {
    id("com.onurkat.reclazz") version "1.3.0"
}
// no reclazz block needed for a standard Spring Boot project
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

## Notes

- The agent only reloads classes it transformed at load time, so the flag must
  be present at JVM startup. The plugin adds it to task startup for you.
- Publishing the plugin to the Gradle Plugin Portal and the agent to Maven
  Central are the two steps that make the `version`-only form above work for
  everyone. Until then, use the `agentJar` path.
