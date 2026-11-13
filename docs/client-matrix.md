# Agentic client acceptance matrix

This matrix separates an independent MCP client from Reclazz's own protocol
fixtures, and separates an executable CI lane from a lane actually observed
passing. SDK acceptance does not certify any desktop AI application or GUI.

## Independent client

`mcp-server/src/test/client` is a private, test-only npm project. It pins the
[official MCP TypeScript SDK](https://github.com/modelcontextprotocol/typescript-sdk)
to **1.30.0** with a lockfile. Node 22 runs the consumer; the installed product
still requires only Java. No npm dependency is added to the agent, IDE plugin,
build plugins or MCP distribution.

The SDK performs initialization/negotiation, ping, tool discovery and calls
against the **packaged MCP jar over stdio**. The test installs both jars under
paths containing spaces and launches a real application JVM with the agent.
It covers:

- MCP startup from an unrelated client directory with explicit project and relative
  port-file defaults; all tools check same-connection target-directory evidence.

- All eleven tools and their advertised output schemas, including error results;
  JSON text must match structured output. Missing required result fields are
  rejected by the SDK's own schema validator.
- DOCTOR target PID, working directory, session and capabilities. Observation
  does not claim that any reload has completed.
- An acknowledged owned BUILD, actual javac compilation, exact-byte VERIFY and
  changed behavior in the same application PID and existing service instance.
- A partial compiler output followed by a real compiler error. Failed output
  stays unapplied even after SCAN, a competing owner is refused, and the same
  owner can recover with a complete successful build.
- Manual pause holds compiled output through SCAN; correlated status shows pending
  files and resume leads to exact-byte VERIFY and changed behavior in the same JVM.
- Mixed applied/unobserved batch results, stale hashes, missing target,
  malformed tool arguments, and continued ping after errors. Neither a BUILD
  acknowledgement nor a scan is treated as reload completion.

Reclazz's Java `AgenticEndToEndTest` separately covers explicit MCP protocol
versions `2024-11-05` and `2025-06-18`. The independent SDK uses its normal
negotiation, without overriding or recreating the client's handshake.

From the repository root, with `JAVA_HOME` set to the JDK being tested:

```sh
./gradlew :agent:shadowJar :mcp-server:mcpRelease
npm --prefix mcp-server/src/test/client ci --ignore-scripts --no-audit --no-fund
npm --prefix mcp-server/src/test/client test
```

On Windows use `gradlew.bat` for the first command and set `JAVA_HOME` to a
native JDK installation. The test uses absolute `java.exe` and `javac.exe`
paths with argument arrays, not a shell command assembled from project paths.
The Gradle toolchain can differ from `JAVA_HOME`; the latter determines the
application and MCP processes in this independent test.

Each run writes a fresh directory under `mcp-server/build/client-matrix/`.
`evidence.json` is written only after all assertions pass, recording SDK/Node/JDK
versions, OS/architecture, JVM identity, session and actual tool results. Compiler
and application logs remain on failure as well. Do not infer success from a
directory's presence or from evidence left by a previous run.

The same run also writes `evaluation.json` with seven named scenario outcomes,
false-completion checks, observed restarts, tool-call counts and elapsed time,
including failed and unrun scenarios. See [workflow evaluation](agentic-evaluation.md)
for exact metric definitions and failure-reporting limits. This is a deterministic
workflow evaluation, not a model benchmark.

## Real build tools

`scripts/test-build-plugin-safety.py` is the existing separate acceptance harness
for actual Gradle and Maven **two-module builds**. It exercises seven scenarios
per tool: ordinary opt-out, partial compilation failure, competing owner,
same-owner recovery, exact-byte receipts, up-to-date barrier and offline target
refusal before output changes. It talks directly to the agent's socket; it is
not another independent MCP SDK client.

Build `:gradle-plugin:jar`, `:agent:shadowJar`, `:mcp-server:shadowJar` and package
`maven-plugin/pom.xml` first. The Maven plugin needs the matching agent artifact
available in its build repository. The fixture itself overlays artifacts into
an isolated repository and does not publish to the user's repository. Existing
[Gradle](gradle-plugin.md) and [Maven](maven.md) guides describe the opt-in wrappers.

```sh
python3 scripts/test-build-plugin-safety.py --help
python3 scripts/test-build-plugin-safety.py \
  --work-dir /tmp/reclazz-client-build-acceptance \
  --gradle /absolute/path/to/gradle-8.10.2/bin/gradle
```

Use a fresh work directory every time. The default artifacts are for 1.6.0;
for another version pass `--version` and the matching artifact paths shown by
`--help`. Maven 3.9.x and its fixture compiler plugin must be cached for this
offline harness. The cache-overlay script uses symlinks; Windows execution of
this separate harness is not claimed by the SDK CI lane.

## Execution status

The current change adds the SDK test to existing **Ubuntu/JDK17** and
**Windows/JDK17** CI lanes using Node22. Both upload consumer evidence. These
are configured lanes; a passing hosted run must be checked for the relevant
commit. The existing Ubuntu/JDK21 lane keeps the Java protocol tests.

Observed locally on **2026-09-23**, macOS aarch64, Node22.20.0:

| Consumer / runtime | Observed result | Scope |
| --- | --- | --- |
| Official SDK 1.30.0 / SAP JDK 17.0.16 | PASS | Eight tools, 18 invalid output mutations rejected, live reload/failure/recovery |
| Official SDK 1.30.0 / SAP JDK 21.0.10.0.1 | PASS | Same acceptance against a separately launched JDK21 application and MCP process |
| Gradle 8.10.2 / SAP JDK21 | PASS | Seven real two-module build scenarios using the existing fixture |
| Maven 3.9.16 / SAP JDK21 | PASS | Seven real two-module build scenarios using the existing fixture |
| SDK / Windows JDK17 | Not run locally | CI configured; no hosted result claimed here |
| SDK / Linux JDK17 | Not run locally | CI configured; no hosted result claimed here |
| Native build fixture / Windows | Not run | SDK acceptance does not imply native Gradle/Maven fixture acceptance |
| Inspector GUI / desktop AI clients | Not run | No GUI or account acceptance claim |

The negative witness bypassed the failed build's hold in a temporary test copy.
The same application probe failed with live value `3` where held value `2` was
required. The committed test always uses the hold; no production source was
modified for this experiment.

Inspector GUI, Claude Desktop, Cursor and other interactive clients are not
certified by these tests. No external account or hosted service is needed for
the local acceptance flow.

On **2026-10-03**, the pause/resume extension passed locally on macOS aarch64,
Node22.20.0, official SDK1.30.0 and SAP JDK17.0.16: nine tools, 23 invalid
output mutations rejected, paused output retained through SCAN, then exact-byte
verification and changed behavior after resume in the same JVM/instance.
This additional run does not establish hosted CI or JDK21 acceptance for the
new control tool.

On **2026-10-03**, explicit-project startup also passed the local macOS aarch64
SDK1.30.0 / Node22.20.0 / SAP JDK17.0.16 acceptance: MCP ran from the jar install
directory while the app ran from the selected consumer project. All nine tools
worked with startup defaults; seven evaluation scenarios passed with zero false
completions or restarts. Hosted and Windows runs for this extension remain unrun.

## Installed coding clients

Observed locally on **2026-11-10**, macOS arm64, Java 21, matching locally built
Reclazz agent/MCP 1.5.0, against an isolated copy of the Spring Boot 3.3.5 Maven
sample. These checks establish discovery and read-only attachment status, not
reload correctness or every tool's execution. See
[client setup](mcp-server.md#claude-code) for configuration examples.

| Client / version | Observed result | Exercised route and limits |
| --- | --- | --- |
| Claude Code 2.1.321 | PASS: eleven tools discovered; one `reclazz_status({})` returned `attached:true` with health | Project registration in the temporary sample; print mode with explicit JSON config and strict MCP selection |
| Codex CLI 0.162.0-alpha.17.2 | PASS: eleven tools discovered; one `reclazz_status({})` returned `attached:true` with health | Ephemeral CLI run, user config ignored, per-run `mcp_servers` overrides; persistent TOML entry parsed separately |
| Cursor desktop 3.26.13 | Documented, not verified | Desktop installed; separate Agent CLI absent; no installed non-GUI tool-call route was established; no GUI acceptance or installation performed |
| Cursor Agent CLI | Documented, not verified | Not installed; desktop presence does not imply CLI presence |
| Claude Desktop, Inspector GUI, other operating systems/client versions | Not verified by this check | No result is inferred from the two CLI checks |

Each successful client performed its own initialization, actual `tools/list`
and one `tools/call` against the packaged jar. A temporary transparent stdio
recorder forwarded bytes unchanged and captured the exchanged MCP messages;
the server executable in those probes was instrumented, rather than a second
uninstrumented model run. The MCP jar path contained spaces. Both clients
selected the same Spring endpoint; its HTTP value, PID and startup identity
were unchanged after the calls. Neither client called a mutating tool.

Claude's project registration added an empty `env` object along with the
documented executable and arguments. Acceptance compared that generated entry
with the intended configuration. The persistent Codex TOML recipe was parsed
and compared with the JSON/shell recipes; the actual Codex call used CLI
overrides, not an edited `~/.codex/config.toml`.

Existing client config files were protected from writes during invocation and
their before/after hashes and absence checks matched. Only temporary project
configuration was created and removed. The owned Spring process and temporary
MCP children exited. These model-assisted client checks used existing client
authentication; unlike the independent SDK lane, they are not an offline or
account-free test. No login or credentials were added.
