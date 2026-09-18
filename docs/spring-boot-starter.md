# Reclazz Spring Boot starter

A starter that makes a Spring Boot app aware of Reclazz: it reports at startup
whether the hot-reload agent is attached, gives the exact command to attach it
when it is not, and, where the actuator is present, exposes a `reclazz` endpoint
with live status. The coordinate is
`com.onurkat.reclazz:reclazz-spring-boot-starter`.

## What it does not do

It does not attach the agent. A `-javaagent` has to be on the command line when
the JVM starts, and a starter runs after that, so nothing a dependency does can
turn the agent on by itself. Attaching is the job of the Gradle plugin, the
Maven plugin, or a plain flag; see [gradle-plugin.md](gradle-plugin.md),
[maven.md](maven.md) and [for-ai-agents.md](for-ai-agents.md). The starter is the
Spring-native half: it tells you whether that worked and surfaces the status.

## Add it

```kotlin
dependencies {
    developmentOnly("com.onurkat.reclazz:reclazz-spring-boot-starter:1.3.0")
}
```

`developmentOnly` keeps it out of the production jar, which is the right scope: it
is a development aid. On Maven, use an optional or a dev-profile dependency.

With the agent attached, the log at startup reads:

```
Reclazz hot-reload agent is attached. Edit code, recompile, and changes reload in place, no restart.
```

Without it, the starter says so and prints how to attach it, rather than staying
silent while nothing reloads.

## Configuration

All properties are under the `reclazz` prefix.

| Property | Default | Meaning |
|---|---|---|
| `reclazz.enabled` | `true` | Master switch for the starter's auto-configuration |
| `reclazz.fail-on-missing-agent` | `false` | Fail startup when the agent is not attached, instead of only warning |
| `reclazz.port` | (auto) | Connect to this agent status port directly |
| `reclazz.port-file` | (auto) | Path to the agent port file, if not in a usual place |
| `reclazz.hybris-home` | (auto) | SAP Commerce home, to locate its port file |

Scope it to development the usual Spring way, for example by keeping the
dependency `developmentOnly`, or by setting `reclazz.enabled` per profile.
Turning `fail-on-missing-agent` on in a dev profile makes a hot-reload run
required there: the app refuses to start without the agent.

## The actuator endpoint

When `spring-boot-actuator` is on the classpath, the starter registers a
`reclazz` endpoint. Expose it like any other:

```properties
management.endpoints.web.exposure.include=reclazz
```

```
GET /actuator/reclazz
```

```json
{
  "attached": true,
  "agentFlag": "-javaagent:/.../reclazz-agent-1.3.0.jar=platform=spring",
  "agent": "1.3.0",
  "protocol": 1,
  "port": 52227,
  "health": ["Reloads: 3, failures: 0, median 12ms"]
}
```

When the agent is not attached it returns `{"attached": false}`. The endpoint
reads the agent's loopback status socket and reaches nothing off the machine,
the same promise the agent itself keeps.

## How attachment is detected

The starter reads this JVM's own startup arguments
(`RuntimeMXBean.getInputArguments()`) and looks for a `-javaagent` that names the
Reclazz agent. It asks the agent nothing and opens no socket for the check; the
status socket is used only by the actuator endpoint, and only to loopback.
