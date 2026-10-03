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

For the complete identity and exact-byte check, start with
[Verify the installation](#verify-the-installation) **before editing**. The steps
below are the shorter, behavior-only check.

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

The short check above demonstrates behavior. The complete recipe below adds
identity and exact-byte evidence using existing tools. The automated acceptance
at the end of this page exercises this example in the local/tag release gate
and the Linux JDK 17 CI lane.

## Verify the installation

Use the locally built matching agent and [MCP server](../../docs/mcp-server.md#running-it)
for these steps; an older published package may not expose DOCTOR/VERIFY.
Configure the MCP server in your client and run this example as described above.
These are **MCP tool names and argument objects**, not shell commands.
No new diagnostic service or starter is required.

### 1. Confirm the target before changing it

Call `/hello` once, recording `pid`, `startedAt`, `marker` and the original
message. This also loads the controller before you try to reload it. Then call
`reclazz_doctor` with the application's **absolute** port-file path:

```json
{"portFile":"/absolute/path/to/maven-spring-boot/.reclazz/agent.port","timeoutMs":"5000"}
```

Replace that path with your example directory. On Windows, JSON accepts forward
slashes, for example `C:/Projects/demo/.reclazz/agent.port`. This Maven recipe
uses the application's working directory; an IDE-managed run may instead use
`.idea/reclazz/agent.port`. Read the application's startup port-file message
rather than guessing or selecting another reachable JVM. The MCP server's
working directory may be different.

Require `isError:false` and check these fields in the tool's returned data
(`structuredContent`, or parse its JSON text on older supported MCP clients):

| Evidence | Required check |
|---|---|
| `status` | `observed`; this confirms observation only |
| `pid` | Same digits as `/hello`'s PID; doctor returns it as a string |
| `workingDirectory` | Your intended application directory, resolving filesystem aliases/symlinks; not the MCP client's `clientWorkingDirectory` |
| `agentVersion` | The agent version you intended to launch; use matching local agent/MCP builds, not version equality alone as a compatibility guarantee |
| `sessionId` | Nonempty; record it for this verification attempt |
| `verifySupported` | `true`; unavailable/false is not confirmation of support |
| `watcherState` | `watching`; wait for startup before editing |
| `watchedDirectories` | Check the actual class output, `target/classes` here, against the live registration sample and configuration |
| `buildHold` | `none` for this simple single-builder recipe; investigate an existing hold with its original owner |

The directory list is a bounded sample, may include resource/source directories
and is not complete class-coverage proof. If `watchSampleTruncated:true`, absence
from the list is inconclusive. A nonzero `unwatchableDirectoryCount` needs
inspection; consult startup logs and the actual `watchDirs` configuration.
An existing directory or a positive watch count alone is insufficient.
`reloadConfirmed:false` is **normal for DOCTOR**, even when all these checks pass.
Do not report the installation as verified yet.

### 2. Make and hash one compiled change

Change only `Hello before reload` to `Hello after reload` in `HelloController.java`
and run `mvn compile` in another terminal. Keep this application running and
avoid concurrent builds/edits during the check. After a successful compilation,
from the example directory compute the hash of the **class file**, not the source:

```sh
# macOS
shasum -a 256 target/classes/com/example/demo/HelloController.class
# Linux
sha256sum target/classes/com/example/demo/HelloController.class
```

```powershell
# Windows PowerShell
(Get-FileHash target/classes/com/example/demo/HelloController.class -Algorithm SHA256).Hash.ToLowerInvariant()
```

Use the 64 lower-case hex digits (the first column on macOS/Linux). A failed
compile is a failed check. An identical-byte rebuild is not proof of a new reload;
make the returned text actually different from the baseline.

### 3. Match the receipt and the live response

Call `reclazz_verify` using the **same absolute portFile**. Replace
`SHA256_FROM_THE_CLASS_FILE` below with the hash you just computed:

```json
{"portFile":"/absolute/path/to/maven-spring-boot/.reclazz/agent.port","className":"com.example.demo.HelloController","sha256":"SHA256_FROM_THE_CLASS_FILE","timeoutMs":"5000"}
```

Require all of the following together:

- `isError:false` and `status:"applied"`.
- `className` matches `com.example.demo.HelloController` and both
  `expectedSha256` and `observedSha256` equal the computed hash.
- `sessionId` equals the DOCTOR session recorded before the edit.
- A fresh `/hello` response says `Hello after reload`, still has marker `kept`,
  and has the same `pid` and `startedAt` as before.

Record these observations together. The MCP client validates receipt correlation;
you must compare target/version/session evidence yourself. There are no
`expectedSessionId` or `expectedVersion` tool arguments that perform these checks.

A reload can still be asynchronous after compilation. For `running` or
`not_observed`, retry the same query only within a bounded window (for example
30 seconds total); `timeoutMs` limits each call, not the whole loop. On expiry,
stop and investigate. Other failures need investigation immediately. If another
edit/build occurs, recompute the hash and begin a new attempt; an old receipt
cannot certify new bytes. If the JVM/session changes, start a new baseline and
make a new observable change instead of accepting the old receipt.

A positive receipt covers this class's completed reload batch, not every class
or business behavior. An HTTP change alone may belong to a restarted or different
process. Only the combined checks above complete this installation exercise.
For larger builds, use the existing [owned build protection](../../docs/mcp-server.md#protecting-terminal-builds)
and verify every changed class; this single-file recipe does not add atomicity.

### If the check does not pass

| Observation | Next step |
|---|---|
| Wrong PID, working directory or version | Stop this attempt. Correct the explicit port file or startup agent path, then take a fresh baseline; do not send build/scan commands to an unconfirmed target. |
| New `sessionId`, PID or start time | The baseline no longer identifies the same run. Discard earlier receipts and repeat with a fresh, actually changed method body. |
| HTTP works but DOCTOR is unavailable | HTTP connectivity does not prove agent attachment. Check the application's startup `-javaagent`, agent/MCP build compatibility and selected port file. A stale port file is not an identity check. |
| `watcherState:starting`, missing/refused watches, or wrong output | Wait for startup; check the actual compiler output and configured watches. Creating or compiling into an unwatched directory does not establish reload. Correct launch configuration and repeat. A truncated sample cannot rule out coverage. |
| `buildHold` is named/legacy or unavailable | Follow the original builder's recovery workflow; do not clear someone else's hold to make this check pass. |
| `not_observed` after the deadline | Check output path, target/session, class loading and startup instrumentation. It can also mean evicted evidence; this status alone does not identify a single cause. |
| `mismatch` | Compare the captured hash/source with the intended output. Check for another writer, wrong output or a newer edit; do not reuse an old hash. |
| `failed`, `unverified`, `unavailable` or `isError:true` | Read receipt detail and logs. Warnings or ambiguous/unloaded classes cannot supply a positive receipt. Do not downgrade these to success. |
| Applied bytes but wrong HTTP text | Check the endpoint and expected behavior; the byte receipt alone does not prove the application's result. |

For class-specific information, call `reclazz_diagnose` with the same `portFile`
and `{"className":"com.example.demo.HelloController"}` merged into its arguments.
Check `reclazz_pending` for restart concerns. If the intended class was not
instrumented at startup, start the application once with the correct agent and
repeat the exercise; connecting MCP later does not instrument that JVM.
[Receipt semantics](../../docs/mcp-server.md#verifying-the-compiled-change) and
[doctor evidence limits](../../docs/mcp-server.md#check-the-target-before-an-agentic-build).

## Validation boundary

### Automated local-artifact acceptance

From the repository root, build matching local artifacts and check the Maven
module before running the consumer:

```sh
./gradlew :agent:publishToMavenLocal
mvn -B -ntp -f maven-plugin/pom.xml clean verify
python3 scripts/test-maven-consumer.py
```

This Linux/macOS harness uses Python 3, Java 17+ and Maven. It copies this example
into fresh paths with spaces, copies the local Maven dependency cache into an
isolated repository, and overlays the actual built agent/plugin artifacts. Maven
may download missing dependencies into that isolated repository. Use `--offline`
to require a fully populated cache, or `--maven-cache /path/to/repository` to select
the cache to copy. Existing example sources and the original cache are not edited.
The example's `reclazz.version` property selects the current build version.

The normal launch uses the documented explicit preparation goal and preserves the
default watcher startup delay. Public DOCTOR/VERIFY socket receipts (using the
existing build-safety fixture's correlated query helper) must match the HTTP JVM,
session, class and changed bytes. Both `pid` and `startedAt` remain unchanged,
and the extra JVM argument still produces `marker:kept`. This does not repeat the
separate MCP SDK/client acceptance matrix.

Negative controls reject connection-only evidence, a deliberately wrong expected
agent version, an HTTP-running app without an agent, and a successful compilation
to an unwatched output. Wrong-version validation uses the current real agent with
a mismatched expectation; it does not claim to test an old agent binary.
All owned application processes are stopped. Logs, artifact digests and results
are retained under `build/maven-consumer/run-*/evidence/`, including failed runs.
`--unit` checks the acceptance predicates without launching applications.

Normal CI runs Maven module tests plus this consumer on Linux JDK 17. The shared
`scripts/release-checks.sh` also runs it before publication can begin. This is
wiring, not proof of a completed CI run or remote artifact availability.

### Earlier manual validation

The explicit preparation command and first reload were exercised on macOS arm64,
SapMachine 21.0.10.0.1, Maven 3.9.16 and Boot 3.3.5, using locally built Reclazz
1.3.0 artifacts in an isolated Maven repository and a project path with spaces.
The extra JVM argument and PID/start time survived reload. This is not evidence
of registry availability or a fresh Windows/Linux run.

The complete DOCTOR/VERIFY recipe was also exercised with that local toolchain:
matching bytes and behavior passed together; connection-only, another live JVM,
a restarted session, a wrong hash, an unwatched output and an application without
the agent did not pass. Version mismatch was checked against an intentionally
wrong expected version, not by installing an older agent binary. The Linux and
PowerShell hash commands were not executed in this macOS validation.
