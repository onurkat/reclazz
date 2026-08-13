<img src="branding/reclazz-mark.svg" alt="" width="88" height="88">

# Reclazz

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/com.onurkat.reclazz?label=Marketplace)](https://plugins.jetbrains.com/plugin/33498-reclazz)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/com.onurkat.reclazz)](https://plugins.jetbrains.com/plugin/33498-reclazz)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-17%2B-orange.svg)](https://adoptium.net/)
[![IntelliJ IDEA](https://img.shields.io/badge/IntelliJ%20IDEA-2023.3%2B-purple.svg)](https://www.jetbrains.com/idea/)
[![Sponsor](https://img.shields.io/badge/Sponsor-GitHub-ea4aaa.svg)](https://github.com/sponsors/onurkat)

**Free, open-source hot-reload for Spring applications. Edit code, see changes live, no restart needed.**

Website: [www.onurkat.com/reclazz](https://www.onurkat.com/reclazz)

Reclazz is a Java agent that watches your compiled class files and hot-swaps them into the running JVM instantly. Works with any Spring Boot application out of the box, with extended support for SAP Commerce (Hybris).

## Quick Start (Spring Boot)

Add the agent to your JVM arguments:

```bash
java -javaagent:/path/to/reclazz-agent.jar -jar myapp.jar
```

Or with explicit watch directories:

```bash
java -javaagent:/path/to/reclazz-agent.jar=watchDirs=/path/to/target/classes -jar myapp.jar
```

Build your project (`mvn compile` or `gradle classes`), and Reclazz hot-swaps the changes automatically. No restart needed.

## Why Reclazz?

Spring Boot DevTools restarts the entire application context on every change. JRebel costs $550+/year. Reclazz is **free, open-source**, and performs true in-place class redefinition, so your application state is preserved.

### Comparison

| Feature | Reclazz | Spring Boot DevTools | JRebel |
|---|---|---|---|
| Price | **Free & open source** | Free | $550+/year |
| Reload type | **In-place hot-swap** | Full context restart | In-place hot-swap |
| Application state | **Preserved** | Lost on restart | Preserved |
| Method body changes | Yes | Yes (restart) | Yes |
| Add methods/fields | **Yes (any JDK 17+)** | Yes (restart) | Yes |
| Spring bean refresh | **Yes (singleton destroy + recreate)** | Yes (restart) | Yes |
| MVC mapping re-scan | **Yes** | Yes (restart) | Yes |
| Cache eviction | **Yes** | Yes (restart) | Partial |
| @Scheduled re-register | **Yes** | Yes (restart) | No |
| @EventListener refresh | **Yes** | Yes (restart) | No |
| AOP proxy refresh | **Yes** | Yes (restart) | Partial |
| Spring Data repo refresh | **Yes** | Yes (restart) | Partial |
| Spring Security notification | **Yes** | Yes (restart) | Partial |
| @Async re-processing | **Yes** | Yes (restart) | No |
| Reload speed | **Instant (~50ms)** | Seconds (context restart) | Instant |
| SAP Commerce support | **Yes (purpose-built)** | No | Generic |

## Features

### Spring Integration

- **Spring Bean Refresh**: Automatically destroys and recreates singleton beans after class reload
- **MVC Re-scan**: Re-registers `@RequestMapping` methods when controllers change structurally
- **Cache Eviction**: Evicts Spring caches for classes with `@Cacheable`/`@CacheEvict`/`@CachePut`
- **Scheduler Reload**: Cancels and re-registers `@Scheduled` tasks
- **Event Listener Refresh**: Re-registers `@EventListener` methods
- **AOP Proxy Refresh**: Clears `AbstractAutoProxyCreator` caches for `@Aspect` classes
- **Async Re-processing**: Re-processes `@Async` beans
- **Spring Data Refresh**: Destroys and recreates `Repository` beans
- **Spring Security Notification**: Detects `@EnableWebSecurity` / `SecurityConfigurer` changes

### Core

- **Hot class reload**: Redefines classes in the running JVM via Instrumentation API
- **Structural changes**: Add/remove methods and fields on any JDK 17+ (companion-class engine); JBR/DCEVM additionally gives full reflective visibility of new members
- **Auto-detection**: Automatically detects Spring Boot, Maven, and Gradle project layouts
- **Zero config**: Just add `-javaagent`, no configuration files needed
- **IntelliJ IDEA plugin**: Auto-injects agent into run configurations, auto-detects JDK and JBR

### Platform Support

- **Spring Boot**: Auto-detects `target/classes` (Maven) or `build/classes/java/main` (Gradle)
- **SAP Commerce (Hybris)**: Full extension-aware support with interceptor reload, ImpEx auto-import
- **Generic Java**: Works with any Java application via `watchDirs=` configuration

## Installation

### From JetBrains Marketplace (Recommended)

[plugins.jetbrains.com/plugin/33498-reclazz](https://plugins.jetbrains.com/plugin/33498-reclazz)

Or from inside the IDE:

1. Go to **Settings** > **Plugins** > **Marketplace**
2. Search for **"Reclazz"**
3. Click **Install** and restart the IDE

The plugin bundles the agent JAR, so there is nothing else to download.

### From GitHub

Download the latest release from [github.com/onurkat/reclazz/releases](https://github.com/onurkat/reclazz/releases), or build from source:

```bash
git clone https://github.com/onurkat/reclazz.git
cd reclazz
./gradlew clean build
# Install: Settings > Plugins > gear icon > Install Plugin from Disk > build/distributions/reclazz-*.zip
```

## Usage

### Spring Boot

Add the agent to your JVM arguments:

```bash
# Maven
java -javaagent:/path/to/reclazz-agent.jar -jar target/myapp.jar

# Gradle
java -javaagent:/path/to/reclazz-agent.jar -jar build/libs/myapp.jar

# With explicit watch directories
java -javaagent:/path/to/reclazz-agent.jar=watchDirs=/path/to/target/classes -jar myapp.jar
```

Then build your project in another terminal:

```bash
mvn compile   # Maven
gradle classes  # Gradle
```

Reclazz watches the compiled `.class` output and hot-swaps changes automatically.

Structural changes (adding/removing methods and fields) work on any JDK 17+. On JBR/DCEVM, add `-XX:+AllowEnhancedClassRedefinition` for full reflective visibility of new members; on standard JVMs, reclazz uses a companion-class fallback (see [Supported JDK Providers](#supported-jdk-providers) for the reflective-visibility caveat).

### IntelliJ IDEA (Recommended)

When you run the application from IntelliJ, the plugin handles everything:

1. Install the plugin. Reclazz introduces itself once and asks whether to
   turn on: click **Enable Reclazz**. It starts switched off, because
   enabling it means putting an agent into the JVM that runs your code.
   (Missed the dialog, or opening a second project? **Settings** >
   **Tools** > **Reclazz** > **Enable Reclazz**.)
2. Run your application from IntelliJ (Run/Debug)
3. The agent is injected automatically with optimal JVM flags
4. Build your project. Changes are hot-swapped, status shown in the IDE

### SAP Commerce (Hybris)

Edit `hybris/config/local.properties`:

```properties
tomcat.generaloptions=-javaagent:/path/to/reclazz-agent.jar ${tomcat.generaloptions}
```

Reclazz auto-detects the Hybris home directory from `platform.home`, `HYBRIS_BIN_DIR`, or the classpath. Start the server and run `ant build`. Reclazz hot-swaps the compiled classes automatically.

Hybris-specific features:
- **items.xml and beans.xml regeneration**: Saving a `*-items.xml` or `*-beans.xml`
  runs the platform's own code generation in the background and hot-reloads the
  regenerated model and DTO classes, instead of `ant clean all` and a restart.
  New attributes still need a database column, so Reclazz prints a reminder to
  run **HAC > Platform > Update Running System**; it will not run DDL against a
  live database.
- **Spring XML reload**: `*-spring.xml` changes are diffed and applied, which is
  also how a brand new `@Component` becomes available without a restart
- **Interceptor reload**: Re-registers Hybris interceptors (Validate, Prepare, Load, Remove)
- **ImpEx auto-import**: Optionally auto-imports changed `.impex` files (opt-in)
- **Extension-aware**: Understands Hybris extension structure, classpath, and classloader hierarchy

### Attach to Running Server

If the server is already running without the `-javaagent` flag:

1. Go to **Tools** > **Attach Reclazz to Running Server**
2. Select the JVM from the list
3. The agent loads into the running JVM, no restart needed

## Configuration

### Agent Arguments

| Argument | Default | Description |
|---|---|---|
| `platform` | `auto` | Platform: `auto`, `spring`, `hybris`, `generic` |
| `watchDirs` | auto-detect | Semicolon-separated paths to watch for .class changes |
| `hybrisHome` | auto-detect | Path to `hybris/` directory (Hybris mode) |
| `watchExtensions` | all | Semicolon-separated extension names (Hybris mode) |
| `excludePatterns` | (none) | Semicolon-separated glob patterns to ignore |
| `autoCompile` | `false` | Internal compilation mode (compile on save) |
| `autoImpex` | `false` | Auto-import ImpEx files (Hybris mode) |
| `debounceMs` | `500` | Change debounce delay (ms) |
| `startupDelaySec` | `30` | Delay before watching starts (seconds) |
| `structuralReload` | `true` | Enable structural reload engine |
| `verbose` | `false` | Verbose console output |

Example:

```bash
java -javaagent:/path/to/agent.jar=watchDirs=/app/target/classes,debounceMs=300,verbose=true -jar myapp.jar
```

### IntelliJ Plugin Settings

Go to **Settings** > **Tools** > **Reclazz**:

| Setting | Default | Description |
|---|---|---|
| Enable Reclazz | Off | Master switch |
| Auto-detect JDK | On | Detects JDK version and JBR, adds recommended flags |
| Verbose logging | Off | Extra detail in the Reclazz tool window |
| AutoCompile | Off | Compile `.java` files internally instead of using build tool |
| Debounce (ms) | 500 | Delay before processing file changes |
| Startup delay (s) | 30 | Wait before watching files after server start |

## Supported JDK Providers

Reclazz works with any JDK 17+ that supports the standard `java.lang.instrument` API. Structural reload (add/remove methods and fields) works on every vendor below. The difference is reflective visibility, not whether the change takes effect.

| JDK Provider | Method Body | Structural Reload | Reflective Visibility of New Members |
|---|---|---|---|
| **JetBrains Runtime (JBR)** | Yes | Yes (enhanced redefinition) | Full: reflection sees new members |
| **DCEVM** | Yes | Yes (enhanced redefinition) | Full: reflection sees new members |
| **Oracle JDK** | Yes | Yes (companion-class mode) | Hot-compiled callers only |
| **SapMachine** | Yes | Yes (companion-class mode) | Hot-compiled callers only, common in SAP Commerce |
| **OpenJDK** | Yes | Yes (companion-class mode) | Hot-compiled callers only |
| **Amazon Corretto** | Yes | Yes (companion-class mode) | Hot-compiled callers only |
| **Eclipse Temurin** | Yes | Yes (companion-class mode) | Hot-compiled callers only |
| **Azul Zulu** | Yes | Yes (companion-class mode) | Hot-compiled callers only |
| **BellSoft Liberica** | Yes | Yes (companion-class mode) | Hot-compiled callers only |
| **GraalVM** | Limited | Limited | Not recommended |

**Enhanced redefinition** (JBR/DCEVM) applies structural changes directly to the original `Class` object, so reflection and reflective caches (like Hybris `ModelService`) see new members immediately.

**Companion-class mode** (standard JVMs) puts new members on a hidden nestmate and routes calls through them via `invokedynamic` rewriting. Hot-compiled code that calls new members works transparently; reflection on the original class (`Class.getMethod(...)`) and caches built at boot time do not pick up the new members until server restart.

## What Can Be Hot-Reloaded?

| Change Type | Any JDK 17+ | Caveat on Standard JVMs |
|---|---|---|
| Method body changes | Yes | None |
| Add new methods | **Yes** | Reachable from hot-compiled callers; not from reflection on the original class |
| Add new fields | **Yes** | Reachable from hot-compiled callers; not from reflection on the original class |
| Remove methods/fields | **Yes** | Existing callers keep the previous implementation until they are hot-recompiled |
| Change annotations | **Yes** | None |
| Spring bean logic | Yes | None |
| New Spring beans (@Component) | Via `*-spring.xml` hot-reload | None |
| Spring XML/YAML changes | Yes (`*-spring.xml` reloader) | None |
| Superclass changes | No | None |

## Architecture

```
             Build Tool (mvn/gradle/ant)
                       |
                       v
               classes/*.class files
                       |
+----------------------|---------------------------+
|  Application JVM     |                            |
|                      |                            |
|  +-------------------v--+                         |
|  |     FileWatcher      |  watches .class files   |
|  |   (NIO WatchService) |                         |
|  +----------+-----------+                         |
|             | bytecode                            |
|      +------v-------+                             |
|      | ClassReloader |                             |
|      | (Instrument-  |                             |
|      |  ation API)   |                             |
|      +------+--------+                             |
|             |                                     |
|   +---------+-----------+                          |
|   |         |           |                          |
|   v         v           v                          |
| Spring   Spring       Spring                      |
| Bean     MVC Re-      Cache                        |
| Refresh  scan         Eviction  + Scheduler,       |
|                                   AOP, Async,      |
|                                   Events, Data,    |
|                                   Security         |
|                                                   |
|   Application | Spring Context | Service Layer    |
+---------------------------------------------------+
```

## Project Structure

```
reclazz/
├── agent/                                    # Java agent (runs inside JVM)
│   └── src/main/java/com/onurkat/reclazz/
│       ├── agent/                            # Agent entry point, config, reloader
│       ├── platform/                         # Platform abstraction layer
│       │   ├── PlatformContext.java           # Interface for platform-specific ops
│       │   ├── PlatformDetector.java          # Auto-detects runtime platform
│       │   ├── SpringBootContext.java         # Spring Boot / generic Java context
│       │   ├── HybrisPlatformContext.java     # SAP Commerce context
│       │   ├── ApplicationContextHolder.java  # Spring context capture
│       │   └── SpringContextInterceptTransformer.java
│       ├── spring/                           # Generic Spring reloaders
│       │   ├── SpringReloadOrchestrator.java  # Coordinates all reloaders
│       │   ├── SpringBeanReloader.java        # Singleton destroy + recreate
│       │   ├── SpringMvcReloader.java         # @RequestMapping re-scan
│       │   ├── SpringCacheReloader.java       # Cache eviction
│       │   ├── SpringSchedulerReloader.java   # @Scheduled re-register
│       │   ├── SpringEventReloader.java       # @EventListener refresh
│       │   ├── SpringAopReloader.java         # AOP proxy cache clear
│       │   ├── SpringAsyncReloader.java       # @Async re-processing
│       │   ├── SpringDataReloader.java        # Repository refresh
│       │   └── SpringSecurityReloader.java    # Security config notification
│       ├── compiler/                         # Incremental compilation
│       ├── hybris/                           # SAP Commerce specific
│       ├── watcher/                          # File watching (NIO WatchService)
│       ├── transform/                        # Bytecode transformation engine
│       ├── reload/                           # Structural reload engine
│       ├── bootstrap/                        # Bootstrap classloader classes
│       └── ui/                               # Console output
│
├── src/main/                                 # IntelliJ IDEA plugin (Kotlin)
├── docs/                                     # Documentation
├── branding/                                 # The mark, and where it is used
│
├── integration-test/                         # End-to-end suite (needs a live
│                                             #   SAP Commerce install; see its README)
└── reclazztest/                              # The extension that suite edits
```

`integration-test/` and `reclazztest/` are test scaffolding. They ship
in no release, nothing in `agent/` or `src/` refers to them, and both
need a licensed SAP Commerce installation to run. `./gradlew clean
build` is the whole gate for everyone else. See
[CONTRIBUTING.md](CONTRIBUTING.md#testing) for what each layer of the
test suite actually checks.

## Troubleshooting

### "No class output directories detected"
Reclazz couldn't find `target/classes` (Maven) or `build/classes/java/main` (Gradle) on the classpath. Use `watchDirs=` to specify the directory explicitly.

### "Class redefinition not supported"
Your JVM doesn't support class redefinition. This shouldn't happen with modern JDKs.

### Structural change fails on a class
Structural reload works on any JDK 17+ for classes the agent transformed at load time. If the message says the class "was loaded before Reclazz attached", start the server with `-javaagent` (the IntelliJ plugin's Install action sets this up) instead of attaching later. Attach mode cannot retrofit the reload infrastructure into already-loaded classes on standard JVMs.

### Spring bean not refreshed
Reclazz destroys and recreates the singleton, cascades the refresh to dependent beans, and re-points fields that still hold the old instance to the new one (stale-reference healing). If a component still sees stale behavior, check the Reclazz tool window for a reload error on that class.

### Spring Boot DevTools compatibility
Reclazz can run alongside DevTools, but it's recommended to disable DevTools when using Reclazz. DevTools performs full context restarts which are slower and lose application state.

### Docker / containerized environments
Mount the agent JAR into the container and add the `-javaagent` flag to `JAVA_OPTS`:

```dockerfile
COPY reclazz-agent.jar /opt/reclazz/agent.jar
ENV JAVA_OPTS="-javaagent:/opt/reclazz/agent.jar=watchDirs=/app/target/classes"
```

## Contributing

Contributions are welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup and guidelines.

Areas where help is needed:

- Testing with different Spring Boot versions and configurations
- Integration with additional Spring modules
- Performance optimization for large codebases
- IDE plugin improvements

## Supporting Reclazz

Reclazz is free, and stays free. If it saves you restarts, you can fund
the maintenance time through [GitHub Sponsors](https://github.com/sponsors/onurkat).
Nothing is gated behind it, and nothing in the plugin nags you about it.

## License

Apache License 2.0. See [LICENSE](LICENSE) for details.

The code is yours to use, fork, and redistribute. The **name "Reclazz"**
and its logo are trademarks and are not covered by the license: publish
your fork under a different name. See [TRADEMARK.md](TRADEMARK.md):
it is short, and permissive about everything except identity.
