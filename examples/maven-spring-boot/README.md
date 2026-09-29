# Maven first-reload example

A minimal Spring Boot app with an opt-in Reclazz development profile. Use its
POM as a complete example, or merge only the profile/properties from the
[Maven guide](../../docs/maven.md#minimal-development-profile) into your own app.
The Boot 3.3.5 pin matches the existing Gradle template; it is a reproducible
fixture, not a current-version recommendation. Java 17+ and Maven 3.9.x are needed.

## Run

Make matching Reclazz agent and Maven plugin artifacts available first. This
example selects 1.3.0; if they are not published in your chosen repository, use
[local builds](../../docs/maven.md#local-builds). No starter or MCP client is needed.
From this directory:

```sh
mvn -Preclazz-dev reclazz:prepare-agent spring-boot:run
```

Open <http://localhost:8080/hello>. The response contains `message`, `marker`,
`pid` and `startedAt`. `marker` must be `kept`: it came from the additional
`-Ddemo.marker=kept` JVM argument, alongside the agent argument. Object field
order is not significant. Wait 30 seconds for the default watcher startup delay.
If 8080 is occupied, use `-Dspring-boot.run.arguments=--server.port=8081` and
adjust the URL.

## First reload

1. Record the original `message` (`Hello before reload`), `pid` and `startedAt`.
2. In `src/main/java/com/example/demo/HelloController.java`, change only
   `Hello before reload` to `Hello after reload`.
3. With the app still running, compile in another terminal in this directory:

   ```sh
   mvn compile
   ```

4. Request `/hello` again. Expect `Hello after reload`, marker `kept`, and the
   **same** `pid` and `startedAt`. A new JVM, unchanged message or missing marker
   fails this check even if compilation succeeded. Do not restart to make it pass.
5. Stop the development run with Ctrl+C and restore the example text when done.

The explicit preparation goal sets `reclazz.agentArgs` and Boot reads it via
`jvmArguments`, followed by `app.jvmArgs`. It watches `target/classes`. Without
`-Preclazz-dev`, there is no Reclazz attachment and this reload check should fail.
No changes to test-JVM attachment or production dependencies are made by the profile.

This is an observable setup check, not an exact-byte receipt or a release gate.
For the latter evidence see [verification](../../docs/for-ai-agents.md#confirm-it-attached-and-verify-a-reload).
The example is shared with the planned diagnostic and clean-consumer gate work;
it does not imply those gates already run in CI.

## Validation boundary

The explicit preparation command and first reload were exercised on macOS arm64,
SapMachine 21.0.10.0.1, Maven 3.9.16 and Boot 3.3.5, using locally built Reclazz
1.3.0 artifacts in an isolated Maven repository and a project path with spaces.
The extra JVM argument and PID/start time survived reload. This is not evidence
of registry availability or a fresh Windows/Linux run.
