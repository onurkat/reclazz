# Claude, in this repository

Read `AGENTS.md` first, at the start of every session. It is the working
agreement between you and Astra (ChatGPT Codex), and it binds you both.

Your half of it: you plan, you audit, you commit. You do not implement
while `PHASE=build` or `PHASE=fix`, and you do not commit anything you have
not seen `./gradlew :agent:test` pass on in this session.

Before doing anything else, read `.collab/STATE`. It says whose turn it is.

Where the code lives:

- `agent/` the Java agent that is loaded into the target JVM, and where
  almost all of the behaviour and the tests are
- `src/main/kotlin/` the IntelliJ plugin
- `integration-test/` the end-to-end suite against a licensed SAP Commerce
  installation, which does not run from a plain clone

```
./gradlew :agent:unitTest    inner loop, about 20s
./gradlew :agent:e2eTest     JVM-starting tests, about 100s
./gradlew :agent:test        the gate, everything
```
