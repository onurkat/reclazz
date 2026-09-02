<img src="branding/reclazz-mark.svg" alt="" width="88" height="88">

# Reclazz

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/com.onurkat.reclazz?label=Marketplace)](https://plugins.jetbrains.com/plugin/33498-reclazz)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/com.onurkat.reclazz)](https://plugins.jetbrains.com/plugin/33498-reclazz)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-17%2B-orange.svg)](https://adoptium.net/)
[![IntelliJ IDEA](https://img.shields.io/badge/IntelliJ%20IDEA-2023.3%2B-purple.svg)](https://www.jetbrains.com/idea/)
[![Sponsor](https://img.shields.io/badge/Sponsor-GitHub-ea4aaa.svg)](https://github.com/sponsors/onurkat)

**Free, open-source hot-reload for Spring applications. Edit code, see changes live, no restart needed.**

Website: [reclazz.com](https://reclazz.com)

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
| Price | **Free & open source** | Free | Commercial |
| Reload type | **In-place hot-swap** | Full context restart | In-place hot-swap |
| Application state | **Preserved** | Lost on restart | Preserved |
| Method body changes | **Yes** | Yes (restart) | Yes |
| Add / remove methods | **Yes (any JDK 17+)** | Yes (restart) | Yes |
| Add instance fields | **Yes** — set on objects created after the reload | Yes (restart) | Yes |
| Add static fields | **Yes** — the field gets its initial value: constants, and initialisers that stand on their own | Yes (restart) | Yes, but the initialiser is not re-run |
| Added static field whose initialiser is entangled with the rest of `<clinit>` | Reads as null/0, and names the field and the reason | Yes (restart) | Reads as null/0 |
| Add enum values (on the end) | **Yes on JDK 17-25**: `values()`, `valueOf`, switches and the EnumMap/EnumSet built before the reload all see it. What nothing can find: an array your own code sized with `values().length` before the reload still has the old length, and indexing it by the new constant's ordinal throws. JDK 26 refuses the `sun.misc.Unsafe` access this needs, so Reclazz falls back to the JDK's own `jdk.internal.misc.Unsafe`, which JEP 471 does not cover, by opening that package with the agent's own `redefineModule`; `--sun-misc-unsafe-memory-access=allow` keeps the first door open and silences the warning | Yes (restart) | Yes |
| Remove the LAST enum value | **Yes on JDK 17-25** — the tail moves no ordinal, so `values()` and `valueOf` drop it while every survivor keeps its number | Yes (restart) | Yes |
| Insert or reorder enum values | No — that renumbers every ordinal after the change, including `@Enumerated` columns already in your database. Reclazz refuses and says why | Yes (restart) | Yes |
| Add / remove an interface | **Yes on JetBrains Runtime or DCEVM**, existing objects included; on a stock JDK it is refused by the JVM and Reclazz names the interface | Yes (restart) | Yes |
| Change superclass | No — `redefineClasses` rejects it on every JVM, JBR included. The method bodies in the same save are still applied, and the log says the class keeps its old superclass until a restart | Yes (restart) | Yes, by loading classes itself instead of redefining them |
| Spring bean refresh | **Yes** (singleton destroy + recreate) | Yes (restart) | Yes |
| `@Autowired` added to an existing field | **Yes** — Spring resolves a bean's injection points once and keeps them, so this used to reload, re-create the bean and leave the field null. `@Resource`, `@PostConstruct` and `@PreDestroy` come with it | Yes (restart) | Yes |
| A constraint added to a field (`@NotBlank`) | **Yes** — enforced on the next request, instead of the request that should now be rejected being accepted | Yes (restart) | Yes |
| `@ExceptionHandler` / `@InitBinder` / `@ModelAttribute` | **Yes** on a method that was already there, instead of the endpoint going on answering the framework's default | Yes (restart) | Yes |
| Edited `@Aspect` pointcut | **Yes, re-parsed** — and the half that still waits is named: a bean already proxied keeps the advice it was built with until it is itself reloaded | Yes (restart) | Yes |
| Jackson picks up a changed shape | **Yes** — a property renamed with `@JsonProperty`, and a getter you removed, reach the JSON. A getter you *add* is the stock-JDK wall rather than a cache: it lives in the companion, where reflection cannot reach it, so it is **named instead of claimed**, with what will not happen | Yes (restart) | Yes |
| MVC mapping re-scan | **Yes** | Yes (restart) | Yes |
| Cache eviction | **Yes** | Yes (restart) | Yes |
| `@Scheduled` re-register | **Yes** | Yes (restart) | Yes |
| `@EventListener` refresh | **Yes** | Yes (restart) | Yes |
| AOP proxy refresh | **Yes** | Yes (restart) | Yes |
| Spring Data repo refresh | **Yes** | Yes (restart) | Yes |
| `@Async` re-processing | **Yes** | Yes (restart) | Yes |
| JPA entity class reload | **Yes** | Yes (restart) | Yes |
| JPA mapping picks up a new field | **Yes, opt-in** (`jpaRefresh=true`): on JBR/DCEVM with `ddl-auto` at update/create/create-drop, Reclazz rebuilds the persistence unit (65ms measured on the demo app), the schema action creates the column, and repositories injected before the rebuild keep working. Open persistence contexts from before the rebuild are closed. In every other configuration Reclazz names the field, says it is neither saved nor loaded, and reads your `ddl-auto` to tell you whether a restart is enough or the column has to exist first | Yes (restart) | Yes, the mapping is refreshed; the database column is still yours to add |
| New `@Entity` class picks up a mapping | **Yes, opt-in** (`jpaRefresh=true`) on **any JDK 17+**: a fresh class is entirely real, so Reclazz adds it to the persistence unit and rebuilds (measured at 44ms), and `ddl-auto` at update/create creates its table. Several persistence units make it decline and name the ambiguity rather than guess | Yes (restart) | Yes |
| New `@Service` / `@Component` / `@Controller` class | **Yes (any JDK 17+)** — the bean is registered, its constructor autowired, and a new controller's mappings served on the next request | Yes (restart) | Yes |
| Edited `@Transactional` / `@Cacheable` annotation | **Yes** — the cached metadata is re-read, so a flipped `readOnly` or a new `condition` actually applies | Yes (restart) | Yes |
| Spring Security rules | **Yes** — the filter chain is rebuilt and swapped into the live proxy, and method security (`@PreAuthorize`, `@Secured` and friends) is re-read on the service class that carries it, not only on the security configuration: the resolved-once metadata is cleared and refilled from fresh `Method` objects, because the stale one the proxy carries would answer with the expression it read at startup | Yes (restart) | Yes, via a separate plugin |
| `@Value` field picks up a changed property | **Yes** — re-resolved and written in place (a SpEL `@Value` is left alone and said) | Yes (restart) | Yes |
| Constructor-bound `@ConfigurationProperties` (a record) | **Yes** — the bean is rebuilt through the same constructor binding and its holders re-pointed | Yes (restart) | Yes |
| A lambda added to an edited method body | **Yes (any JDK 17+)** — the synthetic body travels with the companion and links through Reclazz's own factory. The object is a reflective proxy rather than a spun class, and a serializable lambda loses serializability until restart | Yes (restart) | Yes |
| A method you add that a framework was going to find (`@Bean`, `@Scheduled`, `@EventListener`, a getter) | **Named.** Added methods live in the companion, which call sites reach and reflection does not, so the scan that would have picked it up cannot see it. Reclazz says which method and what will not happen instead of letting the reload look like it worked. A new `@RequestMapping` is the exception and really does answer: the mapping scan is handed a small class carrying a copy of the method | Yes (restart) | Yes |
| Members you deleted stop being visible | **Yes** — a removed field or method is hidden from reflection, so a deleted getter stops being serialised. Old code already holding it keeps the implementation it had, one outcome rather than three: a live caller is never made to throw for a removal you made on purpose | Yes (restart) | Yes |
| Changed compile-time constant (`static final`) | **Named, and the dependents found**: javac inlines the value and leaves no reference behind, so the changed class cannot reach them, but the sources that read it can be found and are listed by name. With `autoCompile` they are rebuilt and hot-swapped, so the new value is live without a restart. A constants-only holder is covered too, though the JVM never loads it | Yes (restart) | Same limitation, undocumented |
| Message bundle (`messages.properties`) | **Yes** — the message source's cache is dropped, the JDK `ResourceBundle` cache with it, so the next lookup reads the file | Yes (restart) | Yes |
| Template reload (Thymeleaf, Freemarker) | **Yes** | Yes | Yes |
| SAP Commerce: `*-items.xml` regeneration | **Yes** | No | No |
| SAP Commerce: ImpEx auto-import | **Yes** | No | No |
| SAP Commerce: interceptor reload | **Yes** | No | No |
| IDE support | Any IDE or none; the IntelliJ plugin adds auto-attach and a status UI | any | IntelliJ, Eclipse, VS Code, NetBeans |
| Remote / container sync | Syncing changed `.class` files into a watched directory is enough; Compose Watch and rsync recipes under [Remote and containers](#remote-and-containers). CCv2 is unreachable for every tool | No | Yes |
| Modified JVM required | **No** | No | No |

The Reclazz column is measured, on a running JVM, for the release it ships
with; the field and enum rows in particular are the result of running each
case rather than reasoning about it. The other two columns come from each
project's own published documentation, so they say what those tools claim
rather than what was tested here, and a blank where a tool is silent is not
evidence of absence.

For the comparison that matters more: HotswapAgent covers a much wider set
of frameworks than Reclazz and can add enum values, but needs a modified JVM
(DCEVM) to do structural reload at all. Reclazz trades that breadth for
working on the JDK you already have, and for going deeper on SAP Commerce
than either.

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
- **Spring Security rebuild**: an edited `@EnableWebSecurity` configuration is
  applied to the running filter chain, so a rule that was `permitAll` starts
  answering 401 on the next request without a restart. Method security
  (`@PreAuthorize`, `@Secured` and friends) is a separate cache, resolved once
  per method and keyed by something redefinition does not change; it is cleared
  and refilled on every reload, so an edited expression on a service is
  enforced as written on the next request
- **New bean classes**: a brand-new `@Service`, `@Component` or `@RestController`
  file becomes a live bean on any JDK 17+, dependencies injected and mappings
  registered, instead of waiting for the next restart's component scan
- **Annotation metadata**: an edited `@Transactional` or `@Cacheable` takes
  effect, not just the method body it sits on
- **What the frameworks cached about your class**: a reload is only half the
  job, because each framework answers from what it worked out about that class
  once, at startup. An `@Autowired` you add injects, a constraint you add is
  enforced, an `@ExceptionHandler` or `@InitBinder` you add runs, an edited
  pointcut is parsed again, and Jackson stops serialising the old shape

### Core

- **Hot class reload**: Redefines classes in the running JVM via Instrumentation API
- **Save to live**: measured end to end on macOS at the default settings, from writing the class file to the running application serving it, 612ms median. The JDK has no native file watching on macOS and polls its watched directories on a two-second cycle, which was 1.9 seconds of a 2.45-second save; the files you are actually editing are checked directly instead, a handful of stat calls rather than a walk of the tree. The first change to any file still waits for the JDK, every one after it does not, and where the JDK watches natively (Linux, Windows) none of this is needed or used
- **Structural changes**: Add/remove methods and instance fields on any JDK 17+ (companion-class engine). An added instance field is initialised on objects created after the reload; objects that already existed keep the type default. An added *static* field gets its initial value too: a compile-time constant comes straight off the field, and an initialiser that forms a self-contained block is lifted out of `<clinit>` and run on its own, so the rest of the static block is never re-executed. Where the two cannot be separated the field reads as null/0 and the log names it and the reason. An enum value added on the end goes live on JDK 17-25, and so does one removed from the end, which moves no ordinal (see the comparison table for the exact scope); inserting or reordering values is refused with the reason. A member the save *removes* stops being visible to reflection, so scans stop acting on it, while code that already holds it keeps running the implementation it had. A changed compile-time constant is named together with the sources that inlined it, found by looking for it in the watched modules; with `autoCompile` those sources are rebuilt and hot-swapped, so the new value takes effect. JBR/DCEVM additionally gives full reflective visibility of new members
- **Property changes**: a changed key goes into the running Environment and the
  `@ConfigurationProperties` beans whose prefix it touches are rebound, so a
  timeout or a feature flag takes effect without a restart. A bean bound through
  its constructor (a record, or `@ConstructorBinding`) is replaced rather than
  mutated: destroyed, rebuilt through the same constructor binding against the
  updated Environment, and the references holding it re-pointed. A `@Value`
  placeholder field is re-resolved and written in place, and a bean that takes
  a changed `@Value` through its constructor is rebuilt the same way the
  constructor-bound properties bean is. A SpEL `@Value` is the one that still
  waits for a restart, because re-evaluating an arbitrary expression is running
  application code at a moment it did not choose, and Reclazz says so rather
  than reporting it as applied
- **Logging configuration**: `logging.level.<logger>` in a properties file, or a
  saved `logback.xml` / `log4j2.xml`, is applied to the running logging context.
  Raising a logger to debug is one of the most common reasons to restart a
  server and one of the least necessary
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

Structural changes (adding/removing methods and instance fields) work on any JDK 17+. On JBR/DCEVM, add `-XX:+AllowEnhancedClassRedefinition` for full reflective visibility of new members; on standard JVMs, reclazz uses a companion-class fallback (see [Supported JDK Providers](#supported-jdk-providers) for the reflective-visibility caveat).

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

### Without IntelliJ

The agent has no IDE dependency. It needs three things: the `-javaagent` flag
on the JVM, a directory to watch, and any build that writes `.class` files
into that directory.

```bash
java -javaagent:/path/to/reclazz-agent.jar=watchDirs=/path/to/build/classes/java/main -jar myapp.jar
```

Then let any compiler write class files on change:

```bash
gradle -t classes    # Gradle continuous build: recompiles on every save
mvn compile          # Maven: run it after each change; Maven has no built-in watch mode
```

VS Code's Java extension compiles on save, and Eclipse does the same with
**Build Automatically** (its default), so both work out of the box: saving a
file puts the fresh `.class` where Reclazz is watching. One thing to check in
VS Code: Gradle projects imported there may have their class output under
`bin/` rather than `build/classes`, so point `watchDirs` at the directory
your setup actually writes.

The IntelliJ plugin is a convenience on top of this, not a requirement: it
adds auto-attach and a status UI.

### Remote and containers

The agent does not care where the compiler runs. It only needs the changed
`.class` files to appear in a directory it watches, on the filesystem of the
JVM it lives in. Remote and containerized development is therefore a
file-sync problem, not an agent feature.

For Docker, sync the host build output into the watched directory with
Docker Compose Watch:

```yaml
services:
  app:
    build: .
    # your entrypoint or JAVA_TOOL_OPTIONS adds:
    #   -javaagent:/opt/reclazz/reclazz-agent.jar=watchDirs=/app/classes
    develop:
      watch:
        - action: sync
          path: ./target/classes
          target: /app/classes
```

`docker compose watch` copies each changed class file into the container the
moment the compiler writes it, and the agent inside picks it up.

For a VM, one rsync after each build does the same:

```bash
rsync -az target/classes/ user@devhost:/app/classes/
```

SAP Commerce Cloud (CCv2) is out of reach for this, and for every hot-reload
tool: there is no SSH into the running container and the images are
immutable, so a changed class file has no way to arrive. Hot reload on SAP
Commerce is for locally hosted servers.

### The trust boundary

A watched directory is a way to run code in the server's JVM, so it is worth
stating plainly what that means. Anyone who can write a `.class` file into a
watched directory has that class redefined into the running process; anyone who
can write a `.impex` file into a watched ImpEx directory (opt-in) has it run
against the live database. Reclazz does not, and cannot, verify where those
files came from, any more than the JVM verifies its own classpath. So a watched
directory should be trusted exactly as much as the server's classpath itself:
keep it to your own build output on your own machine, which is what local
development is. The agent opens one socket, bound to loopback, that answers two
read-only status questions and triggers nothing; it makes no outbound network
connection of any kind. Nothing about your code or your machine leaves the
machine.

Running under the agent also does not hand the application anything it did not
have. The engine keeps each watched class's own lookup, which carries private
access to that class, and its holder sits on the bootstrap classloader where
every line of code in the process can reach it. Only the engine's own classes,
named by identity before the application starts, are given it: application code
asking is refused, which matters because code that can evaluate a submitted
expression or reach a static method through a deserialized object cannot write
a class file into a watched directory, and should not get there by another
route. Reload-added field values are readable and writable through the same
bootstrap classes, because the code the agent generates calls them, and they
reach only the values Reclazz itself stores.

### SAP Commerce (Hybris)

Edit `hybris/config/local.properties`:

```properties
tomcat.generaloptions=-javaagent:/path/to/reclazz-agent.jar ${tomcat.generaloptions}
```

Reclazz auto-detects the Hybris home directory from `platform.home`, `HYBRIS_BIN_DIR`, or the classpath. Start the server and run `ant build`. Reclazz hot-swaps the compiled classes automatically.

Put it in `local.properties`, not in `tomcat/conf/wrapper.conf` directly. A platform rebuild regenerates that file from the properties and silently drops anything added to it by hand, so the agent stops being attached and the only symptom is that saving a file does nothing. `local.properties` survives the rebuild, which is what puts the line back into the regenerated file.

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
- **ImpEx auto-import**: Optionally imports changed `.impex` files on save
  (opt-in). It runs against your live database with no confirmation and
  nothing that undoes it, so files containing a `REMOVE` header are refused
  and named rather than executed. Set `impexAllowRemove=true` if you mean it.
- **Property changes applied to the running server**: the platform reads its
  property files once, at startup. Saving one now applies the keys whose value
  actually differs, through the same call the HAC console makes. Values that
  are consumed once at startup are named as still needing a restart rather than
  reported as applied.
- **Log levels**: SAP Commerce keeps them in properties rather than a
  `log4j2.xml`, as a `log4j2.logger.<key>.name` and `.level` pair. Changing a
  level applies it to the running Log4j2 context, so raising a logger to debug
  no longer costs a restart. Only the loggers the save touched are set, which
  leaves a level raised from the HAC console alone.
- **Type and enum text**: editing an `<ext>-locales_<iso>.properties` re-reads
  the platform's localization cache, so the new text is served on the next
  lookup. No system update and no database write.
- **Backoffice labels**: `labels_<iso>.properties` under an extension's
  `*-backoffice-labels` folder reach the label cache backoffice reads from,
  every copy of it. Reopen the view and the text is there.
- **Extension-aware**: Understands Hybris extension structure, classpath, and classloader hierarchy


#### On SAP Commerce, compared with JRebel

| What you changed | Reclazz | JRebel |
|---|---|---|
| Price | Free and open source | $550+/year per seat |
| A Java class | Yes | Yes |
| Per-extension setup file | None | `rebel.xml` for backoffice extensions |
| A type in `items.xml` or `beans.xml` | Regenerates and reloads | Not in its Hybris manual |
| A property | Applied to the running server | Not in its Hybris manual |
| A log level | Applied to the running Log4j2 context | Not in its Hybris manual |
| A type or enum name in a locales file | Yes | Not in its Hybris manual |
| A backoffice label | Yes | Not in its Hybris manual |
| A `*-backoffice-config.xml` | Cockpit configuration caches reset on save | Not in its Hybris manual |
| An interceptor | Re-registered | Not in its Hybris manual |
| An ImpEx file | Imported on save (opt-in) | Not in its Hybris manual |
| Licence server and activation | None | Commercial licence, activation required |

Compared against [JRebel's own SAP Commerce manual](https://manuals.jrebel.com/jrebel/advanced/hybris.html),
which covers reloading compiled classes and configuring `rebel.xml`. "Not in its
Hybris manual" means exactly that, not that JRebel is broken: it reloads classes
on Hybris and that is what it documents.

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
| `impexAllowRemove` | `false` | Allow an auto-imported ImpEx to contain `REMOVE` headers |
| `debounceMs` | `500` | Change debounce delay (ms) |
| `startupDelaySec` | `30` | Delay before watching starts (seconds) |
| `structuralReload` | `true` | Enable structural reload engine |
| `jpaRefresh` | `false` | Rebuild the persistence unit when a reloaded entity gains or loses a persistent field. Requires JBR/DCEVM and `ddl-auto` at update/create/create-drop; closes persistence contexts open at the moment of the rebuild |
| `verbose` | `false` | Verbose console output |
| `wrapOutput` | `auto` | Lay long messages out for a fixed-width view, breaking on words and indenting the rest under the first line. `auto` does it when standard output is a terminal, which an application server's is not even when somebody is watching its console, so set `always` there. `never` keeps every message on one line, which is what a log file that gets grepped wants |

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

## Verified On

Everything documented here is measured on a running system rather than inferred
from the code. What that means concretely:

| Environment | Version | Covered |
|---|---|---|
| SAP Commerce | 2211-jdk21.8 (Java 21, Spring 6.2) | 21/23 integration scenarios on a live server. The two that do not pass are named rather than hidden: the ImpEx scenario needs `autoImpex=true` in the agent arguments, and an enum scenario has a 404 flake in the harness that predates the current release |
| Spring Boot | 3.3 | class reload, structural reload, bean refresh, cache, MVC, properties, logging |
| Spring Boot | 2.7 | the same six, including a new endpoint mapped without a restart |
| JDK | SapMachine 17 and 21, JBR 25 | companion-class engine on both, plus native enhanced redefinition |
| Spring Framework | 6.2 (through the SAP Commerce run) and 5.3 / 6.1 (through Spring Boot 2.7 / 3.3) | bean refresh, MVC re-scan, cache, scheduler, events |
| IntelliJ IDEA | 2023.3 to 2026.1 | the range the plugin declares |

Older or newer versions are likely to work, and are not claimed to until they
have been run.

## Supported JDK Providers

Reclazz works with any JDK 17+ that supports the standard `java.lang.instrument` API. Structural reload (add/remove methods and instance fields) works on every vendor below. The difference is reflective visibility, not whether the change takes effect.

There is a second number, and it is about the compiler rather than the runtime.
Instrumentation reads and writes class files, and this build reads them up to
**Java 27**. Running on a newer JDK is fine; compiling *to* one is what is not,
because the bytecode library learns a class file version after the JDK that
introduced it ships. In that window the application runs and method body
changes still reload, since the JVM reads those classes and that path does not
go through the library, while adding or removing members is off. Reclazz says
so once, in Java releases rather than header numbers, with the two ways out:
compile with an older `--release` while you develop, or update Reclazz.

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
| Add new methods | **Yes** | Reachable from hot-compiled callers; not from reflection on the original class. A new Spring MVC endpoint is an exception: it is mapped without a restart |
| Add new fields | **Yes** | Reachable from hot-compiled callers; not from reflection on the original class. Once a class carries members added since startup, the JVM refuses the redefinition that installs the new constructor, so a field added after that reads its default even on new objects; Reclazz names the fields and the reason rather than leaving you to find the value |
| Remove methods/fields | **Yes** | Hidden from reflection so scans stop seeing them; existing callers keep the previous implementation until they are hot-recompiled |
| Change annotations | **Yes** | None |
| Spring bean logic | Yes | None |
| New Spring beans (@Component) | **Yes** — a new stereotype class is registered and wired, and `*-spring.xml` still works for XML-defined beans | None |
| Spring XML/YAML changes | Yes (`*-spring.xml` reloader) | None |
| Property changes | Rebound into `@ConfigurationProperties` beans, constructor-bound beans rebuilt, `@Value` fields re-resolved, beans taking a changed `@Value` through their constructor rebuilt | A SpEL `@Value` keeps its startup value |
| Log levels and logging config | Yes (`logging.level.*`, `logback.xml`, `log4j2.xml`) | Appenders are rebuilt, so a reconfigure resets the context |
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

### What attaching the agent changes about your classes

Reclazz rewrites every watched class at load time, whether or not you ever
reload anything. Most of that is invisible, and it is worth writing down which
parts are not.

**Nothing about reflection.** A method keeps its annotations, its generic
signature, its `throws` clause and its parameter names, so Spring still decides
to proxy it and Jackson still sees `List<Order>` rather than a raw list. Every
member Reclazz injects is marked synthetic, which is what keeps it out of the
framework scans that skip synthetic members.

**Stack traces gain a frame.** A call into a watched method shows two frames:
the dispatch under your own method name, and above it the moved body under an
internal one.

```
at com.acme.OrderService.__reclazz$v0$place$9edc617f(OrderService.java:42)
at com.acme.OrderService.place(OrderService.java:42)
```

Both carry the real file and line, so the trace still says where the failure
was and your IDE can still jump from the frame you recognise. The extra line is
the cost of the dispatch the reload engine is built on. One thing does not
recover: a log pattern that prints the calling method name (`%M` in Log4j2)
reads it off the moved body, so it prints the internal name. The logger name,
the line number and the class name are all unaffected.

**`serialVersionUID` is pinned.** Injecting members would change the number the
JVM computes for a class that does not declare one, which would make anything
serialized before the agent unreadable after it. Reclazz computes the number
your class would have had and writes it in, so serialized data and mixed
clusters keep working.

**Fields added by a reload are runtime-only.** A field that appears in a reload
lives beside the object rather than in it. It is not serialized, and a `clone()`
gets its own copy rather than sharing the original's. After deserialization it
reads its type default, which is what an object built before that reload reads
too.

**Each reload costs metaspace that is never returned.** Around 8.5 KB, measured
over 60 reloads. This is the JVM's cost rather than Reclazz's: redefining a
class makes the JVM keep the previous version, so that threads still running
the old code keep working, and a bare `redefineClasses` loop with no agent at
all measured 10.6 KB per redefinition on the same JDK. Reclazz's own companion
classes are collected, one for one with the reloads. It is only worth thinking
about where metaspace is capped, which on SAP Commerce it is: a day of heavy
editing is a few MB, and Reclazz warns when the pool passes 85% full so that a
long session ends in a restart you chose rather than an `OutOfMemoryError` you
did not.

**`synchronized` survives, with one exception.** Both the dispatch and the moved
body take the monitor, so mutual exclusion holds from startup. A structural
reload that moves a synchronized method's body to a companion class is the case
that does not hold, and Reclazz warns by name when it happens.

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

### "Do I still need to restart?"

**Tools > What Still Needs a Restart?** answers it. Reclazz keeps a note of
everything this session did that a restart would change: a static field whose
initialiser could not be separated from the rest of the static block, an enum
change the JVM would not take, a superclass change, a Spring bean the XML
reload could not apply, a property the platform refused. When the list is
empty it says so, which is the answer that saves the restart.

### "Why didn't my class reload?"

Ask the agent: **Tools > Why Didn't My Class Reload?**, or right-click in the
editor. It reports what it found for that class, into the reload log: whether a
compiled file exists under a watched directory and when it was last built,
whether the JVM has loaded it, whether the agent prepared it, and what the last
reload attempt did. Most of the time the answer is that no reload was ever
attempted, and none of those reasons produce an error message on their own.

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
