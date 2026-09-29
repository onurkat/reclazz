# Installation

Choose the route that starts your **application JVM**. IntelliJ and SAP Commerce
are optional; Reclazz also runs with Spring Boot and plain Java.

| How you run the application | Start here |
|---|---|
| IntelliJ Java run configuration | [Install and enable the IDE plugin](#intellij-idea) |
| Spring Boot with Gradle `bootRun` | [Gradle setup](#gradle) |
| Spring Boot with Maven | [Maven development profile](#maven) |
| Plain Java, another IDE or a custom launcher | [Standalone agent](#standalone-agent) |
| SAP Commerce scripts or IntelliJ | [SAP Commerce setup](#sap-commerce) |
| An AI client should inspect the running app | [Optional MCP server](#optional-mcp-server), after one of the above |

Use one attachment route for a given JVM. Installing an IDE plugin, adding a
Spring starter or connecting an MCP client is not by itself evidence of a reload.
Finish with [your first reload](#your-first-reload).

## Versions and requirements

- The agent and MCP server target **Java 17+**. JDK 17/21 are the baseline test
  lanes; [the client matrix](client-matrix.md) records narrower OS/framework
  coverage. This is not a guarantee for every later JDK or framework version.
- The IDE plugin declares IntelliJ builds **233 through 262.\*** (2023.3–2026.2).
  IDE compatibility and application-JVM compatibility are separate.
- SAP Commerce is needed only for the SAP route; see its [guide](sap-commerce.md).
- Stock JDKs support Reclazz's documented structural reload paths; a special JBR
  installation is not required to begin. Supported changes and native type limits
  are in [usage](usage.md) and [superclass feasibility](superclass-feasibility.md).
- Examples use source version **1.3.0**. A source version is not proof that every
  registry has published it. Choose a version present in your chosen channel or
  use the local build steps below. Match the agent and its integration artifacts;
  newer BUILD/VERIFY/MCP features may require local builds.

## IntelliJ IDEA

1. Open **Settings → Plugins → Marketplace**, search for **Reclazz**, install it
   and restart the IDE if prompted. Alternatively, use **Install Plugin from
   Disk…** with a plugin ZIP from [GitHub Releases](https://github.com/onurkat/reclazz/releases).
   Choose a release that actually contains the ZIP; the agent JAR is not the IDE plugin.
2. Open the project and enable **Settings → Tools → Reclazz → Enable Reclazz**.
   It is off by default. This also works for non-SAP projects.
3. Start a Java run configuration handled by the IDE. Reclazz supplies its bundled
   agent and watches the IDE's module compiler outputs. For a run delegated to
   Gradle or Maven, use that tool's setup below rather than assuming IDE injection.
4. Compile a small edit with the build that writes those watched outputs, then
   check the running application's response. Detailed options: [usage](usage.md).

The ZIP contains `agent/reclazz-agent.jar` under the plugin's installed root;
that bundled name is deliberately unversioned. No separate agent download is
needed for IDE-managed runs. SAP launches outside the IDE use a staged copy;
follow the SAP guide rather than persisting a path inside an IDE installation.

### Optional command-line plugin installation

JetBrains documents `installPlugins` for its launchers. These examples use the
**2026.2 launcher documentation**, not a fresh OS/IDE installation test. Adjust
the IDE path/name to your installation; older IDE launchers need their own check.

```sh
# macOS, standalone IntelliJ IDEA installation
open -na "IntelliJ IDEA.app" --args installPlugins com.onurkat.reclazz
# Linux, example installation directory
/opt/idea/bin/idea.sh installPlugins com.onurkat.reclazz
```

```bat
:: Windows cmd.exe, with the IDE bin directory on PATH
idea64.exe installPlugins com.onurkat.reclazz
```

For Toolbox-managed IDEs, use the launcher name configured in Toolbox. These
commands install the plugin only; still enable it for the project and start the
application with the agent. See JetBrains' [plugin installation](https://www.jetbrains.com/help/idea/install-plugins-from-the-command-line.html)
and [OS-specific launchers](https://www.jetbrains.com/help/idea/working-with-the-ide-features-from-command-line.html).

## Gradle

For an existing Spring Boot application, retain its Spring Boot version and add
the Reclazz plugin to its `build.gradle.kts`:

```kotlin
plugins {
    id("com.onurkat.reclazz") version "1.3.0"
}
repositories { mavenCentral() }
```

Start with `./gradlew bootRun` (Windows: `gradlew.bat bootRun`). In another terminal,
compile edits with `./gradlew classes`. The plugin attaches to `bootRun` and test
JVMs. **Plain `application.run` is not automatically covered**; use the standalone
route for now. Automatic watching currently selects the first main class output;
projects with multiple outputs should explicitly set `watchDirs` in the
[Gradle options](gradle-plugin.md#options).

If that plugin version is not available in the Plugin Portal, use a released
matching version or the standalone route. A local agent can be selected via
`reclazz { agentJar.set(file("/absolute/path/to/agent-1.3.0.jar")) }`; this replaces
agent resolution, not Gradle plugin resolution. Full setup: [Gradle plugin](gradle-plugin.md).

## Maven

Use the opt-in `reclazz-dev` profile in [the Maven guide](maven.md#minimal-development-profile).
It attaches the agent to `spring-boot:run` while retaining your other JVM arguments:

```sh
mvn -Preclazz-dev reclazz:prepare-agent spring-boot:run
# In another terminal, in the same application directory:
mvn compile
```

A complete runnable [Maven example](../examples/maven-spring-boot/README.md) includes
the POM, endpoint, version selection and first-reload steps. Keep your application's
existing Boot version; the example's Boot 3.3.5 pin is a reproducible fixture, not
an upgrade recommendation. The longer dependency-plugin alternative remains in
[the Maven guide](maven.md#without-the-plugin-works-today).

The optional [Spring Boot starter](spring-boot-starter.md) reports attachment and
can expose Actuator status. It does **not** attach the agent and is unnecessary
for the first reload. Keep it in development configuration if you choose it.

## Standalone agent

Download `reclazz-agent-X.Y.Z.jar` from the assets of your chosen
[GitHub release](https://github.com/onurkat/reclazz/releases), or build from source
below. Replace `X.Y.Z` and the application paths. Keep the entire agent argument
quoted when paths contain spaces.

```sh
# macOS/Linux: compiled classes must be on this application's classpath
java "-javaagent:/absolute/tools/reclazz-agent-X.Y.Z.jar=watchDirs=/absolute/app/target/classes" -cp "/absolute/app/target/classes" com.example.Main
```

```powershell
# Windows PowerShell: adapt the main class and output directory
java "-javaagent:C:/Tools/Reclazz/reclazz-agent-X.Y.Z.jar=watchDirs=C:/Projects/app/target/classes" -cp "C:/Projects/app/target/classes" com.example.Main
```

Add your normal dependency classpath/JVM options as needed; Windows classpath
entries use `;` instead of `:`. For an executable application JAR retain your
normal `-jar app.jar` launch, while pointing `watchDirs` to the actual recompiled
class output. Watching source files or merely rebuilding a JAR is not this recipe.
The flag must be present at startup so classes are instrumented when loaded.
The watcher defaults to a **30-second startup delay**; wait before your first edit.
For a small standalone demo only, `startupDelaySec=0` removes that wait.
Agent arguments and framework-specific limits: [usage](usage.md).

## SAP Commerce

Follow [SAP Commerce setup](sap-commerce.md) for the correct Hybris home, launcher
properties and compile command, or [manual setup](usage.md#option-1-manual-setup).
Enabling the IDE plugin does not modify an already-running external server JVM;
start it once with the configured agent before attempting reloads. Keep the
server-facing agent at a stable path, outside versioned IDE installation folders.

## Optional MCP server

First run the application with the agent using one of the routes above. Then
follow [MCP installation](mcp-server.md#running-it): build or obtain the matching
standalone `reclazz-mcp-X.Y.Z.jar`, verify its checksum, and point the client's
stdio configuration at its absolute path and a Java 17+ executable. Use an
explicit application `portFile` in tool calls when the client's working directory
is different. The MCP server connects to the application; it does not launch or
instrument it. MCP assets/features described on main are not retroactively
available in older releases. [Client coverage](client-matrix.md).

## Building from source

From the repository root, with its Gradle wrapper and JDK 17+:

```sh
./gradlew :agent:shadowJar
./gradlew buildPlugin
# Optional, only if you need the MCP server:
./gradlew :mcp-server:mcpRelease
```

On Windows use `gradlew.bat` in place of `./gradlew`. For the current 1.3.0 source:

| Output | Use |
|---|---|
| `agent/build/libs/agent-1.3.0.jar` | Standalone shaded agent; use this exact path in `-javaagent` for a local build |
| `build/distributions/reclazz-1.3.0.zip` | Local IDE plugin; install from disk |
| `mcp-server/build/distributions/mcp/reclazz-mcp-1.3.0.jar` and `.jar.sha256` | Optional standalone MCP package |

Read `pluginVersion` in `gradle.properties` if building another version.
`agent-1.3.0-thin.jar` is not the standalone package. The source build's
`agent-1.3.0.jar`, GitHub's `reclazz-agent-1.3.0.jar` and the IDE's bundled
`reclazz-agent.jar` are different names for their respective packaging contexts.
For local Maven plugin/agent installation, see [Maven local builds](maven.md#local-builds).
These commands build local files; they do not publish a release.

## Your first reload

1. Start the application with the agent and confirm the target JVM is the one
   you intended. Connection alone only confirms connectivity.
2. After the watcher starts, call an endpoint or method and record its response.
3. Change a simple method's returned text, compile to a watched class output,
   then call it again **without restarting**. Expect the new text and the same JVM.
4. The [Maven example](../examples/maven-spring-boot/README.md#first-reload) gives a
   concrete response and JVM-identity check. For matching builds supporting it,
   [DOCTOR and exact-byte verification](for-ai-agents.md#confirm-it-attached-and-verify-a-reload)
   provide further target/receipt evidence; still check application behavior.

If only connection succeeds, do not call installation accepted: check the agent
was present at startup, the compiled output is watched, and the current change
is supported. [Troubleshooting](../README.md#troubleshooting).

Validation scope for this guide: the Maven example and standalone launch were
exercised on macOS arm64 with SapMachine 21; details are in the
[example](../examples/maven-spring-boot/README.md#validation-boundary). Windows/Linux
commands, Marketplace installation, IDE launcher installation and a live SAP
Commerce setup were not rerun for this change. Their references and existing
coverage are separate from this local verification.
