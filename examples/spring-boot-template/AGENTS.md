# AGENTS.md

Guidance for a coding agent working on this project.

## Hot reload with Reclazz

This project runs with the Reclazz agent attached, so a running app picks up
compiled changes without a restart. Prefer this loop over restarting.

Run the app (the agent attaches automatically, wired in `build.gradle.kts`):

    ./gradlew bootRun

Apply a code change while it runs:

1. Edit the code.
2. Recompile: `./gradlew classes`.
3. The agent hot-swaps the changed classes in place. Do not restart.
4. Verify at http://localhost:8080/hello, or run `./gradlew test` (the test JVM
   is attached too, and `ReclazzAgentSmokeTest` fails if the wiring breaks).

Restart only when a change cannot be applied live (for example a changed
superclass or a reordered enum). Everything else, method bodies, new methods and
fields, new beans, mappings and most annotations, reloads in place.

## Notes

- The agent is a `-javaagent` resolved from Maven Central
  (`com.onurkat.reclazz:reclazz-agent`) and attached at JVM startup; it is not a
  normal dependency and is never bundled into the app.
- It is a development tool. It is not on the production classpath.
- More: https://github.com/onurkat/reclazz/blob/main/docs/for-ai-agents.md
