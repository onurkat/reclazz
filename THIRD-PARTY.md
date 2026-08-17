# Third-Party Components

Everything a Reclazz release puts on your disk, and where it came from.

The list is short on purpose. The agent is loaded into the JVM that runs your
application, so every library inside it becomes a library inside that
application, and each one is a decision to justify rather than a convenience to
accept.

## Shipped in `agent/reclazz-agent.jar`

| Component | Version | Licence | Why it is here |
|---|---|---|---|
| [Byte Buddy](https://bytebuddy.net/) | 1.18.5 | Apache License 2.0 | Generates the companion classes that carry members a reload adds |
| [ASM](https://asm.ow2.io/) | 9.8 | BSD 3-Clause | Reads and rewrites bytecode: call sites, field access, added members |

Both are relocated so they cannot collide with the copies your application
already has:

    org.objectweb.asm      ->  com.onurkat.reclazz.shaded.asm
    net.bytebuddy          ->  com.onurkat.reclazz.shaded.bytebuddy

Their SLF4J bindings are excluded, because an agent has no business installing a
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

## Verifying this yourself

The claim is checkable without trusting the table:

```bash
unzip -l reclazz-<version>.zip                     # what the release contains
unzip -l agent/reclazz-agent.jar | grep -v onurkat # anything outside our packages
```

The second command returning nothing but directory entries and `META-INF` is the
whole point: there is no unrelocated third-party code, and nothing else is
along for the ride.
