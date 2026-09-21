# Reclazz Spring Boot template

A minimal Spring Boot app that reloads in place with
[Reclazz](https://github.com/onurkat/reclazz): edit a class, recompile, and the
running app picks up the change with no restart and no lost state. Use it as a
starting point, or as a GitHub template repository.

## Run it

```bash
./gradlew bootRun
```

Then open http://localhost:8080/hello.

Now try a reload, with the app still running:

1. Edit the string in `src/main/java/com/example/demo/HelloController.java`.
2. Recompile in another terminal: `./gradlew classes`.
3. Refresh the page. The new text is there, and the app never restarted.

## How the agent is wired

The agent is not an application dependency; it is a `-javaagent` attached to the
JVM at startup. `build.gradle.kts` resolves it from Maven Central into a separate
`reclazzAgent` configuration and adds the flag to `bootRun` and to the test JVM:

```
-javaagent:<reclazz-agent.jar>=platform=spring,watchDirs=build/classes/java/main
```

`./gradlew test` runs against reloaded classes, and `ReclazzAgentSmokeTest`
confirms the agent is attached, so a broken wiring fails the build instead of
silently reloading nothing.

## A cleaner setup

This template wires the agent by hand so the flag and paths are visible. Two
ecosystem pieces make it a single line each:

- The Spring Boot starter (on Maven Central):
  `developmentOnly("com.onurkat.reclazz:reclazz-spring-boot-starter:1.3.0")`
  reports at startup whether the agent is attached and adds a `reclazz` actuator
  endpoint. See
  [spring-boot-starter.md](https://github.com/onurkat/reclazz/blob/main/docs/spring-boot-starter.md).
- The Gradle plugin (on the Gradle Plugin Portal):
  `id("com.onurkat.reclazz") version "1.3.0"` attaches the agent to `bootRun` and
  `test` for you. See
  [gradle-plugin.md](https://github.com/onurkat/reclazz/blob/main/docs/gradle-plugin.md).

## For coding agents

See [AGENTS.md](AGENTS.md) for the exact hot-reload loop to follow in this
project.
