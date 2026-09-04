# Third-Party Components

Everything a Reclazz release puts on your disk, and where it came from.

The list is short on purpose. The agent is loaded into the JVM that runs your
application, so every library inside it becomes a library inside that
application, and each one is a decision to justify rather than a convenience to
accept.

## Shipped in `agent/reclazz-agent.jar`

| Component | Version | Licence | Why it is here |
|---|---|---|---|
| [ASM](https://asm.ow2.io/) | 9.10.1 | BSD 3-Clause | Reads and rewrites bytecode: call sites, field access, added members, companion classes |

One library. Byte Buddy used to be the second, and it was removed once it came
to this table and could not answer the question at the top of it: it was
generating two probe classes, and in one file it was only being used for the
copy of ASM bundled inside it. That is 25 MB of classes in your application's
JVM to save sixty lines in ours. The shipped agent went from 10.2 MB to 0.7 MB.

It is relocated so it cannot collide with the copy your application already
has:

    org.objectweb.asm      ->  com.onurkat.reclazz.shaded.asm

SLF4J bindings are excluded, because an agent has no business installing a
logging implementation into somebody else's process.

## Shipped in `lib/reclazz-<version>.jar`

Reclazz code only. No third-party classes are bundled: the Kotlin standard
library is provided by the IDE (`kotlin.stdlib.default.dependency=false`), as is
the IntelliJ Platform itself.

## Built against, not distributed

| Component | Licence | Note |
|---|---|---|
| IntelliJ Platform | Apache License 2.0 | Provided by the IDE at runtime |
| Kotlin standard library | Apache License 2.0 | Provided by the IDE at runtime |
| SAP Commerce, Spring, Hibernate, Log4j2, Logback | their own | Reached reflectively, at runtime, in your process. Nothing is bundled and no API is redistributed |

## Used to build and test, never shipped

The build file names more than the table above, and the difference is worth
stating rather than leaving for someone to find and wonder about. JUnit, Spring
(for the XML reloader's tests), `jakarta.persistence-api` (so a test can use the
real annotations the agent matches by descriptor) and Byte Buddy's agent-attach
helper are test dependencies. None of them are in a release; the commands below
are how you check that rather than take it on trust.

Byte Buddy in particular used to ship and no longer does, so seeing its name in
`agent/build.gradle.kts` is expected: what is left is `testImplementation`.

## Verifying this yourself

The claim is checkable without trusting the table:

```bash
unzip -l reclazz-<version>.zip                     # what the release contains
unzip -l agent/reclazz-agent.jar | grep -v onurkat # anything outside our packages
```

The second command returns directory entries, `META-INF`, a small
`reclazz.properties`, and one file worth explaining rather than leaving as an
unexplained blob in an audit:

    META-INF/reclazz-bootstrap.bin

It is a jar inside the jar, holding the classes that have to live on the
bootstrap classloader so that instrumented code can reach them. The `.bin`
extension only stops the shadow plugin from unpacking it during the build. It
is a plain jar and opens like one:

```bash
unzip -p agent/reclazz-agent.jar META-INF/reclazz-bootstrap.bin > bootstrap.jar
unzip -l bootstrap.jar | grep -v onurkat   # directory entries and MANIFEST, nothing else
```

So: no unrelocated third-party code at the top level, none nested inside, and
nothing else along for the ride. The relocation is checkable the same way:

```bash
unzip -l agent/reclazz-agent.jar | grep -c org/objectweb/asm            # 0
unzip -l agent/reclazz-agent.jar | grep -c com/onurkat/reclazz/shaded/asm  # 149
```
