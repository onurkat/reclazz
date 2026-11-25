# Spring Boot 4 reload sample

A small Boot4.1.2 application using Java17, Spring7.0.10 and Boot's Jackson3
stack. Its own wrapper uses Gradle8.14; the repository wrapper is unchanged.
See [Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html).

This is an integration research sample. A successful startup does not establish
that every structural change reloads; measured limitations are recorded separately
before any product fix or compatibility claim.

Build the current repository artifacts into an isolated local Maven repository:

```sh
# From the repository root, in the same terminal used below:
SAMPLE_MAVEN_REPO="$PWD/build/boot4-maven-local"
./gradlew :agent:publishToMavenLocal :gradle-plugin:publishToMavenLocal :spring-boot-starter:publishToMavenLocal -Dmaven.repo.local="$SAMPLE_MAVEN_REPO"
cd examples/spring-boot4-template
./gradlew bootRun -PreclazzMode=manual -Dmaven.repo.local="$SAMPLE_MAVEN_REPO"
```

The sample pins the current source artifact version 1.10.0. Local publication is
required to test the source candidate rather than a previously published jar.
No remote publication is involved. Only run one mode at a time on port8084.

| `reclazzMode` | Agent attachment | Starter |
|---|---|---|
| `manual` (default) | Explicit bootRun JVM flag | Absent |
| `plugin` | Repository's Gradle plugin | Absent |
| `starter` | Explicit bootRun JVM flag | Detection and actuator integration |
| `absent` | None | Agent-absent detection control |

The starter never attaches an agent. It reports whether a startup flag is present.
Its optional endpoint is `/actuator/reclazz`; verify that endpoint and startup
messages rather than treating dependency resolution alone as successful integration.

With the app running, inspect `/hello`, `/message` (a record serialized as JSON),
`/configuration`, `/beans/added` and `/runtime`. The last endpoint reports PID and
Jackson bean/converter class names for this synthetic sample only. Recompile in a
second terminal with the same mode and Maven repository:

```sh
./gradlew classes -PreclazzMode=manual -Dmaven.repo.local="$SAMPLE_MAVEN_REPO"
```

Keep the PID constant while checking a controller body edit, an added endpoint,
an added DTO record component, an `application.properties` value change and a new
`@Service`. Capture HTTP before/after and agent events; do not restart to make a
live check pass. Restore source files and stop the owned sample before switching
mode. Both compiled classes and copied resources are watched by the explicit flag.

No compatibility defect is worked around in the sample. If a change is not applied,
record its actual output and classify it before planning a product change.
