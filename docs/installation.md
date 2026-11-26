# Installation

## Install from JetBrains Marketplace (recommended)

For an application you run directly from IntelliJ, start here. You do not need to download a separate Java agent.

1. In IntelliJ IDEA, open **Settings → Plugins → Marketplace**.
2. Search for **Reclazz**, choose **Install**, and restart IntelliJ if prompted.
3. Open your project. In **Settings → Tools → Reclazz**, tick **Enable Reclazz in this project** and choose **Apply**.
4. Start your application with an IntelliJ-managed Java run configuration. Wait for Reclazz to connect.
5. Change the text returned by an existing method, use **Build Project**, and call it again in the same running application.

It worked when the application returns your new text without restarting; the Reclazz tool window also names the reloaded class.

[Install from Marketplace](https://plugins.jetbrains.com/plugin/33498-reclazz)

Running Gradle or Maven from a terminal? Use the [Gradle](#gradle) or [Maven](#maven) steps below. For SAP Commerce, use its [short setup guide](sap-commerce.md#install-from-marketplace).

## Other ways to install

Choose the route that starts your **application JVM**. IntelliJ and SAP Commerce
are optional; Reclazz also runs with Spring Boot and plain Java.

| How you run the application | Start here |
|---|---|
| IntelliJ Java run configuration | [Install and enable the IDE plugin](#intellij-idea) |
| Spring Boot with Gradle `bootRun` | [Gradle setup](#gradle) |
| Plain Java with Gradle `application` / `run` | [Opt-in application run](gradle-plugin.md#plain-java-application-run-opt-in) |
| Spring Boot with Maven | [Maven development profile](#maven) |
| Plain Java, another IDE or a custom launcher | [Standalone Java agent](#standalone-agent) |
| SAP Commerce scripts or IntelliJ | [SAP Commerce setup](#sap-commerce) |
| An AI client should inspect the running app | [Optional MCP server](#optional-mcp-server), after one of the above |

Use one attachment route for a given JVM. Installing an IDE plugin, adding a
Spring starter or connecting an MCP client is not by itself evidence of a reload.
Finish with [your first reload](#your-first-reload).

## Versions and requirements

- The Java agent and MCP server target **Java 17+**. JDK 17/21 are the baseline test
  lanes; [the client matrix](client-matrix.md) records narrower OS/framework
  coverage. This is not a guarantee for every later JDK or framework version.
- **JDK 25 runtime:** unit, end-to-end and Spring 6 suites passed locally on
  macOS on **2026-11-14**, with Spring 5/6 process fixtures targeting Java 21.
  Gradle builds on JDK 21 and launches tests on 25. The Ubuntu/JDK25 CI lane is
  configured; **Linux CI is pending**. Spring 7 was exercised with application
  classes targeting 25, with two first-`@Bean` cases still in independent audit;
  this is not a full Spring 7/JDK25 pass. See the [runtime matrix](client-matrix.md#jdk-25-agent-runtime).
- The IDE plugin declares IntelliJ builds **233 through 263.\*** (2023.3–2026.3).
  IDE compatibility and application-JVM compatibility are separate.
- SAP Commerce is needed only for the SAP Commerce route; see its [guide](sap-commerce.md).
- Stock JDKs support Reclazz's documented structural reload paths; a special JBR
  installation is not required to begin. Supported changes and native type limits
  are in [usage](usage.md) and [superclass feasibility](superclass-feasibility.md).
- These examples use source version **1.11.0**. For Gradle, choose a version available in both the Gradle Plugin Portal (plugin) and Maven Central (Java agent), or use matching local builds. For other integrations, match the agent version to an artifact in your chosen channel. See [building from source](#building-from-source) for features that require a local build.

## IntelliJ IDEA

1. Open **Settings → Plugins → Marketplace**, search for **Reclazz**, install it
   and restart the IDE if prompted. Alternatively, use **Install Plugin from
   Disk…** with a plugin ZIP from [GitHub Releases](https://github.com/onurkat/reclazz/releases).
   Choose a release that actually contains the ZIP; the Java agent JAR is not the IDE plugin.

### Setup wizard

After installing, click **Set Up Reclazz** on the notification, or open
**Tools → Set Up Reclazz**. The wizard opens as the **Setup** tab of the Reclazz tool
window. For a SAP Commerce project it has three steps:

1. **Install Reclazz** adds the Reclazz Java agent to `tomcat.javaoptions` and
   `tomcat.debugjavaoptions` in a marked block of `local.properties`. The exact lines
   are shown before you click.
2. **Run ant server** regenerates the launch configuration, with the output shown
   live. Then restart your server the way you always do.
3. **Connect your AI assistant** (optional) adds the Reclazz MCP server to Claude
   Code, Cursor, VS Code or Codex for this project. You can skip it.

For any other project the wizard turns Reclazz on (the IDE adds the Java agent to the
Java run configurations you start) and offers the same optional AI assistant step.

The manual steps below are what the wizard does for an IDE-started application.
2. Open the project and enable **Settings → Tools → Reclazz → Enable Reclazz in this project**.
   It is off by default. This also works for non-SAP Commerce projects.
3. Start a Java run configuration handled by the IDE. Reclazz supplies its bundled
   Java agent and watches the IDE's module compiler outputs. For a run delegated to
   Gradle or Maven, follow the [Gradle](#gradle) or [Maven](#maven) setup rather than assuming IDE injection.
   IDE-managed JUnit, TestNG and Spock test runs are skipped by default; enable
   **Also attach the Java agent to IDE test runs** to opt in. Delegated builds keep
   their separate [Gradle `applyToTest`](gradle-plugin.md) and [Maven `skip`](maven.md) settings.
4. Compile a small edit with the build that writes those watched outputs, then
   check the running application's response. Detailed options: [usage](usage.md).

The ZIP contains `agent/reclazz-agent.jar` under the plugin's installed root;
that bundled name is deliberately unversioned. No separate Java agent download is
needed for IDE-managed runs. SAP Commerce launches outside the IDE use a staged copy;
follow the SAP Commerce guide rather than persisting a path inside an IDE installation.

For IDE-managed launches, one existing Reclazz VM option is reused only when its
jar path and options exactly match what the IDE would inject, after quote parsing.
A different path/version/options or multiple Reclazz flags stop launch with a
conflict message. Remove the manual Reclazz VM option to use IDE settings, or
disable Reclazz under **Settings → Tools → Reclazz** to manage attachment yourself.
Other Java agents are preserved. Recognition covers `reclazz-agent.jar` and
`reclazz-agent-<version>.jar` (version starts with a digit, classifiers allowed).
Renamed jars at other paths, environment-provided flags and `@argfiles` are not
inspected; option order and equivalent filesystem paths are not normalized.

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
application with the Java agent. See JetBrains' [plugin installation](https://www.jetbrains.com/help/idea/install-plugins-from-the-command-line.html)
and [OS-specific launchers](https://www.jetbrains.com/help/idea/working-with-the-ide-features-from-command-line.html).

## Gradle

For an existing Spring Boot application, retain its Spring Boot version and add
the Reclazz plugin to its `build.gradle.kts`:

```kotlin
plugins {
    id("com.onurkat.reclazz") version "1.11.0"
}
repositories { mavenCentral() }
```

Start with `./gradlew bootRun` (Windows: `gradlew.bat bootRun`). In another terminal,
compile edits with `./gradlew classes`. The plugin attaches to `bootRun` and test
JVMs. Plain `application.run` is opt-in with `reclazz { applyToRun.set(true) }`
in a plugin build containing the new option; see the
[application run guide](gradle-plugin.md#plain-java-application-run-opt-in) for its
availability and scope. Automatic watching includes all main class outputs;
an explicit `watchDirs` list replaces them in the [Gradle options](gradle-plugin.md#options).

If that plugin version is not available in the Plugin Portal, use a released
matching version or the standalone route. A local Java agent jar can be selected via
`reclazz { agentJar.set(file("/absolute/path/to/agent-1.11.0.jar")) }`; this replaces
Java agent resolution, not Gradle plugin resolution. Full setup: [Gradle plugin](gradle-plugin.md).

## Maven

Use the opt-in `reclazz-dev` profile in [the Maven guide](maven.md#minimal-development-profile).
It attaches the Java agent to `spring-boot:run` while retaining your other JVM arguments:

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
can expose Actuator status. It does **not** attach the Java agent and is unnecessary
for the first reload. Keep it in development configuration if you choose it.

## Standalone agent

Download `reclazz-agent-X.Y.Z.jar` from the assets of your chosen
[GitHub release](https://github.com/onurkat/reclazz/releases), or [build from source](#building-from-source). Replace `X.Y.Z` and the application paths. Keep the entire Java agent argument
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
Java agent arguments and framework-specific limits: [usage](usage.md).

## SAP Commerce

Follow [SAP Commerce setup](sap-commerce.md) for the correct Hybris home, launcher
properties and compile command, or [manual setup](usage.md#option-1-manual-setup).
Enabling the IDE plugin does not modify an already-running external server JVM;
start it once with the configured Java agent before attempting reloads. Keep the
server-facing Java agent jar at a stable path, outside versioned IDE installation folders.

## Optional MCP server

First run the application with the Java agent using one of the routes above. Then
use **Settings | Tools | Reclazz | Connect AI assistant** in IntelliJ for the bundled
matching MCP server, preview and confirmation. For standalone/manual setup, follow
[MCP installation](mcp-server.md#running-it): build or obtain the matching
`reclazz-mcp-X.Y.Z.jar`, verify its checksum, and configure a Java 17+ executable. Use an
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

On Windows use `gradlew.bat` in place of `./gradlew`. For the current 1.11.0 source:

| Output | Use |
|---|---|
| `agent/build/libs/agent-1.11.0.jar` | Standalone shaded Java agent; use this exact path in `-javaagent` for a local build |
| `build/distributions/reclazz-1.11.0.zip` | Local IDE plugin; install from disk |
| `mcp-server/build/distributions/mcp/reclazz-mcp-1.11.0.jar` and `.jar.sha256` | Optional standalone MCP package |

Read `pluginVersion` in `gradle.properties` if building another version.
`agent-1.11.0-thin.jar` is not the standalone package. The source build's
`agent-1.11.0.jar`, GitHub's `reclazz-agent-1.11.0.jar` and the IDE's bundled
`reclazz-agent.jar` are different names for their respective packaging contexts.
For local Maven plugin and Java agent installation, see [Maven local builds](maven.md#local-builds).
These commands build local files; they do not publish a release.

## Your first reload

1. Start the application with the Java agent and confirm the target JVM is the one
   you intended. Connection alone only confirms connectivity.
2. After the watcher starts, call an endpoint or method and record its response.
3. Change a simple method's returned text, compile to a watched class output,
   then call it again **without restarting**. Expect the new text and the same JVM.
4. For complete acceptance with matching Java agent and MCP builds, follow the
   [Maven installation verification recipe](../examples/maven-spring-boot/README.md#verify-the-installation)
   from its baseline step before editing: confirm the intended JVM/version with
   DOCTOR, hash the compiled class, require a same-session `applied` VERIFY
   receipt, and check the changed response in the same JVM. A successful
   connection or compile alone never completes this check.

If only connection succeeds, do not call installation accepted: check the Java agent
was present at startup, the compiled output is watched, and the current change
is supported. [Troubleshooting](usage.md#when-a-reload-goes-wrong).

Validation scope for this guide: the Maven example and standalone launch were
exercised on macOS arm64 with SapMachine 21; details are in the
[example](../examples/maven-spring-boot/README.md#validation-boundary). Windows/Linux
commands, Marketplace installation, IDE launcher installation and a live SAP
Commerce setup were not rerun for this change. Their references and existing
coverage are separate from this local verification.
