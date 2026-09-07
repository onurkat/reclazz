# Usage Guide

Reclazz supports four modes of operation. You can use them independently or combine them depending on your workflow.

---

## Option 1: Manual Setup

Use this option when you run the SAP Commerce server **outside of IntelliJ** (e.g., from the terminal with `./hybrisserver.sh`) or when you want full control over JVM arguments.

### How It Works

You add the `-javaagent` flag to your Hybris Tomcat configuration. The agent loads inside the SAP Commerce JVM, watches for compiled `.class` files, and hot-swaps them when you run `ant build`.

### Setup

Edit `hybris/config/local.properties` and add the agent to `tomcat.generaloptions` or `tomcat.debugjavaoptions`:

**Any JDK 17+ (works for structural reloads too):**

```properties
tomcat.generaloptions=-javaagent:/path/to/reclazz-agent.jar=hybrisHome=/path/to/hybris ${tomcat.generaloptions}
```

Structural hot-reload (adding/removing methods and fields) works on any
JDK 17+ via Reclazz's companion-class reloader — no extra JVM flags
required. On standard JVMs, the new members live on a hidden nestmate
and are reached through `invokedynamic` routed from hot-compiled
callers; reflective access (`Class.getMethod(...)`, `ModelService`) is
limited to members that existed at boot time. See
[Companion-class caveat](#companion-class-reflective-visibility-caveat)
below for details.

**JetBrains Runtime / DCEVM (full reflective visibility):**

```properties
tomcat.generaloptions=-javaagent:/path/to/reclazz-agent.jar=hybrisHome=/path/to/hybris -XX:+AllowEnhancedClassRedefinition ${tomcat.generaloptions}
```

Enhanced redefinition applies structural changes to the original
`Class` object itself, so reflection and reflective caches see new
members without restart.

> **Tip:** If you already have debug options configured, you can append the agent to `tomcat.debugjavaoptions` instead. This property is applied when you start the server in debug mode:
>
> ```properties
> tomcat.debugjavaoptions=-javaagent:/path/to/reclazz-agent.jar=hybrisHome=/path/to/hybris -Xdebug -Xnoagent -Xrunjdwp:transport=dt_socket,server=y,address=8000,suspend=n
> ```

### Finding the Agent JAR Path

The location of `reclazz-agent.jar` depends on how you installed Reclazz:

- **Installed via IntelliJ plugin:** The agent JAR is bundled inside the plugin directory. You can find it at:

  ```
  # macOS
  ~/Library/Application Support/JetBrains/IntelliJIdea<version>/plugins/reclazz/agent/reclazz-agent.jar

  # Linux
  ~/.local/share/JetBrains/IntelliJIdea<version>/plugins/reclazz/agent/reclazz-agent.jar

  # Windows
  %APPDATA%\JetBrains\IntelliJIdea<version>\plugins\reclazz\agent\reclazz-agent.jar
  ```

- **Downloaded from GitHub:** Use the absolute path wherever you saved the JAR.

- **Built from source:** Use the absolute path to `agent/build/libs/reclazz-agent.jar`.

### Agent Arguments

Arguments are passed as a comma-separated string after the `=` sign:

```properties
-javaagent:/path/to/reclazz-agent.jar=hybrisHome=/opt/hybris,watchExtensions=mycore;mystorefront,autoImpex=true,verbose=true
```

| Argument | Default | Description |
|---|---|---|
| `hybrisHome` | auto-detect | Absolute path to the `hybris/` directory |
| `watchExtensions` | all custom | Semicolon-separated extension names to watch (empty = all) |
| `excludePatterns` | (none) | Semicolon-separated glob patterns to exclude (e.g., `*Test.class;*Mock*`) |
| `autoCompile` | `false` | Compile `.java` files internally instead of watching `.class` files |
| `autoImpex` | `false` | Auto-import changed `.impex` files |
| `debounceMs` | `500` | Milliseconds to wait before processing changes (batches rapid file writes) |
| `startupDelaySec` | `30` | Seconds to wait after agent startup before watching files |
| `verbose` | `false` | Enable verbose logging in the console, including each framework step's own line after a reload (the reload line carries the summary either way) |
| `statusPort` | `0` | TCP port for plugin communication (0 = auto-assign) |
| `portFile` | (none) | Path where agent writes its actual port after binding |
| `watchDirs` | auto-detect | Semicolon-separated class output directories to watch, for a project the detection does not know |
| `excludeClasses` | (none) | Semicolon-separated class name patterns the transform leaves alone; the way out when instrumenting one class is the problem |
| `impexAllowRemove` | `false` | Let auto-imported ImpEx files run `REMOVE` lines |
| `jpaRefresh` | `false` | Rebuild the persistence unit when an entity gains a field or a new entity appears (JBR/DCEVM, `ddl-auto` at update/create) |
| `structuralReload` | `true` | The companion engine that adds and removes members on a stock JDK; `false` leaves method-body reloads only |
| `platform` | `auto` | Skip detection and name the platform (`hybris`, `spring`, `generic`) |
| `wrapOutput` | `auto` | Wrap console lines to the terminal width: `auto`, `true`, `false` |
| `transformDumpDir` | (none) | Write every transformed class file here, for looking at what the agent emitted |
| `verifyTransform` | `false` | Run the bytecode verifier over every transformed class and print what it says |
| `sessionLog` | (none) | Append every status line to this file with an ISO timestamp and level, no colour: the session's record, to read back or attach to a report |
| `reloadBoundary` | `immediate` | `request` waits for synchronous Spring MVC dispatches to finish before applying a class reload batch; requires `-javaagent` at JVM startup. See [Reload between requests](#reload-between-requests) |

Arguments are never removed or renamed within a major version: a line that
worked with an older 1.x agent works with a newer one. An argument the agent
does not know is named in the console at start-up and ignored, not fatal, so a
line written for a newer agent still starts an older one. Put such arguments
first: the line is split at a comma only where a known argument follows, so
that a value may contain a comma, which means an unknown argument placed after
another becomes part of that one's value (and is still named). The plugin's own
test (`AgentArgumentContractTest`) keeps what it passes inside this table, and
the agent's (`AgentArgumentsAreDocumentedTest`) keeps this table equal to what
the agent accepts.

### Cache dependencies after reload

Spring Cache observation is automatic when the companion engine instruments
application methods. No agent argument is needed. If `PriceService` caches
its result in `prices` and calls an unannotated `DiscountRules` helper,
reloading the helper invalidates `prices`. An unrelated `descriptions` cache
stays populated. This works for externally compiled classes and AutoCompile.

The agent records the watched classes executed inside Spring's synchronous
cache interceptor and the actual cache instances involved. If a cached
`quotes` calculation reads an already cached `prices` result, their dependency
is recorded too: changing the price helper invalidates both regions. Equal
cache names in separate managers or application contexts are distinct, as are
classes with the same name in different classloaders. Caches supplied directly
by a `CacheResolver` are included. This is region-level eviction, not per-key
invalidation. Dependencies are conservative unions: a dependency that stops
being used can still cause eviction until its class or cache is collected.

A cache operation overlapping a class mutation is conservatively invalidated
when its outermost synchronous interceptor returns. Its caller can still get
the result calculated with old code, but that result is not retained for the
next call after the invalidation succeeds. The interval between Spring storing
a value and the interceptor exiting is not isolated: another thread can read
that value, and thread scheduling can extend this interval. Nested operations
defer their invalidation until the outermost exit so an inner exit does not
clear caches while an outer synchronous cache loader still holds its lock.
Normal and exceptional exits release observation. Failed clears are reported,
do not replace the application's result or exception, and retain dependencies
for another reload to retry. Operations overlapping unrelated reloads may also
be cleared conservatively.

Attach mode and any cache-hook failure mark observation incomplete. For an
annotated class the agent then retains its existing fallback of clearing all
manager caches; known resolver-only caches are also cleared. An unannotated
helper invalidates only its observed dependents, even with incomplete history.
Without observations, an annotated class keeps the same fallback. Tracking
stops growing at 512 cache instances or 8192 class/dependency links and marks
coverage incomplete for the session; existing dependency records remain usable.
Persistent class and cache identities are weakly held.

Limits: native JBR/DCEVM redefinition does not use the method instrumentation
and therefore keeps the annotation fallback. Constructors, class initializers,
excluded methods/classes, unwatched libraries, cache access outside Spring's
interceptor, pre-attach entries and asynchronous/reactive computations do not
have complete dependency coverage. The synchronous paths, including
`@Cacheable(sync=true)`, are tested against Spring 5.3.39. The hook matches the
Spring 6 signatures as well, but this change does not add a real Spring 6 E2E
matrix. A watched method pays a volatile activity check even when no cache
operation is open; during observation it also records a class identity.
`verbose=true` names targeted evictions and computations crossing reloads.

### Reload between requests

Add `reloadBoundary=request` to the agent arguments to keep a synchronous
Spring MVC request from crossing a class reload. For example:

```bash
java -javaagent:/path/to/reclazz-agent.jar=reloadBoundary=request -jar app.jar
```

If a request calls a pricing method, pauses, and calls a discount method,
an ordinary reload can put the first call on the old code and the second
on the new code. With this option, the request finishes before the reload
starts. New requests wait while the class batch and its framework follow-up
run, then enter against the updated code. The default, `immediate`, keeps
the existing reload behavior and installs no request hook.

The agent waits up to one second for active dispatches to drain. If a long
request or a breakpoint outlasts that deadline, it prints
`Request boundary deferred`, reopens admission, and retains the queued edit.
It then waits for a natural idle boundary instead of repeatedly stopping new
requests. The next safe opportunity retries automatically, without another
save. New edits remain queued on the same reload thread. The one-second
limit covers draining requests, not applying the reload; a slow framework
follow-up can keep new requests waiting longer. After thirty seconds,
`HEALTH` reports the waiting work through the existing reload status;
`sessionLog` records deferrals immediately.

The boundary is the execution of Spring MVC's `FrameworkServlet.processRequest`
in any application classloader in this JVM. It supports the `javax.servlet`
and `jakarta.servlet` signatures. Nested forwards/includes on the same thread
stay within the outer boundary. Normal returns and exceptions both release it.
AutoCompile still compiles outside the boundary and applies its compiled
batch inside it.

The guarantee covers successful method-body changes during synchronous MVC
dispatches. Servlet filters before or after that dispatch, asynchronous MVC
work, WebFlux, scheduled jobs and other background threads are outside it.
Resource/configuration reloads are also outside this boundary. It is not a
database snapshot or an all-or-nothing deployment: structural changes and a
partially failed batch retain the existing reload limitations.

Use this option at JVM startup. Attach mode, missing bootstrap support, or
Spring MVC already loaded by an earlier agent refuses initialization rather
than silently running without protection. A missing or failed MVC hook keeps
class edits deferred. A non-MVC application therefore should use `immediate`.
An invalid `reloadBoundary` value refuses initialization. Application startup
itself is not aborted.

### Workflow

```
1. Start the SAP Commerce server (with agent in JVM args)
   └── Reclazz banner appears in the console output

2. Edit your Java source files in IntelliJ or any editor

3. Compile with ant:
   cd hybris/bin/platform
   ant build

4. Reclazz detects the new .class files and hot-swaps them instantly
   └── "Hot-swapped: com.example.MyService (12ms)" appears in the console

5. Test your changes — no server restart needed
```

---

## Option 2: IntelliJ Automatic Mode (Recommended)

Use this option when you run the SAP Commerce server **from IntelliJ IDEA** using a Run/Debug configuration. The plugin handles everything automatically.

### How It Works

When you launch a Run/Debug configuration in IntelliJ IDEA, the Reclazz plugin:

1. **Detects** that the project is an SAP Commerce (Hybris) project
2. **Injects** the `-javaagent` flag into the JVM arguments automatically
3. **Detects** your JDK version (17 or 21) and whether you are using JetBrains Runtime
4. **Adds** recommended JVM flags (e.g., `-XX:+AllowEnhancedClassRedefinition` for JBR)
5. **Connects** to the agent after the server starts
6. **Watches** for file changes and shows reload status in the IDE

You do not need to edit any properties files or manage the agent JAR path.

### Setup

1. Enable the plugin: **Settings** > **Tools** > **Reclazz** > check **Enable Reclazz**
2. That's it. Run your Hybris server from IntelliJ as usual.

### Workflow with `ant build`

This is the default and safest workflow. The IntelliJ plugin injects the agent, but compilation is still handled by `ant`:

```
1. Start the SAP Commerce server from IntelliJ (Run/Debug)
   └── Reclazz agent is automatically injected
   └── Plugin connects to the agent
   └── Status bar shows "Reclazz: Connected"

2. Edit your Java source files in IntelliJ

3. Compile with ant (from terminal or IntelliJ terminal):
   cd hybris/bin/platform
   ant build

4. Reclazz detects the new .class files and hot-swaps them
   └── Reclazz tool window shows reload details
   └── Status bar updates: "Reclazz: 1 reloads"

5. Test your changes immediately
```

### Workflow with IntelliJ Build (AutoCompile mode)

If you prefer to skip `ant build` entirely, enable **AutoCompile**. In this mode, Reclazz watches `.java` source files and compiles them internally using the JDK compiler API (`javax.tools`):

1. Go to **Settings** > **Tools** > **Reclazz**
2. Check **AutoCompile (compile .java files internally instead of using ant)**
3. Start the server from IntelliJ

```
1. Start the SAP Commerce server from IntelliJ (Run/Debug)
   └── Reclazz agent is injected with autoCompile=true

2. Edit a Java source file in IntelliJ

3. Save the file (Ctrl+S / Cmd+S)
   └── Reclazz detects the .java change
   └── Compiles it internally using javax.tools
   └── Hot-swaps the resulting bytecode
   └── "Compiled: MyService.java (45ms) → Hot-swapped: com.example.MyService (8ms)"

4. Test your changes immediately — no ant build, no server restart
```

> **Note:** AutoCompile works for single-file changes. For multi-file refactors or changes that affect generated code, use `ant build` instead.

### What the Plugin Shows

- **Status bar widget:** Bottom-right corner shows connection status and reload count
  - `Reclazz: Idle` — Plugin enabled, server not running
  - `Reclazz: Connected` — Agent connected, watching for changes
  - `Reclazz: 5 reloads` — Number of successful hot-swaps in this session
  - `Reclazz: Error` — Last reload failed (check tool window for details)

- **Tool window:** Bottom panel labeled "Reclazz" shows a detailed log of all events:
  - Compilation results (autoCompile mode)
  - One line per reload, with the timing and what the save touched:
    `Reloaded com.acme.OrderService (12ms): bean orderService re-created,
    mappings re-scanned, caches evicted`. When the save added something that
    only a restart completes, the line ends with `1 thing now waits on a
    restart, ask PENDING`. The same line is the status bar widget's tooltip.
  - Each framework step's own sentence, under `verbose=true`
  - Errors and warnings

- **Notifications:** Balloon notifications for important events (JDK detection, connection status)

---

## Option 3: Auto-Build on Idle (Optional)

> **This feature is off by default and must be explicitly enabled.** It can increase CPU usage because it triggers a build every time you stop typing, even for incomplete changes.

### How It Works

When enabled, Reclazz monitors your editing activity. After you stop typing for a configurable threshold, it automatically triggers a build. Combined with the hot-swap pipeline, this means your changes are compiled and reloaded without any manual action.

### Setup

1. Go to **Settings** > **Tools** > **Reclazz**
2. Check **AutoCompile** — this is required for auto-build to work
3. The debounce timer controls how long Reclazz waits after your last edit before triggering compilation

### How the Debounce Timer Works

The **Debounce (ms)** setting (default: 500ms) controls the delay:

```
You type... type... type... [stop]
                                  |
                                  ├── 500ms timer starts
                                  |
                                  └── No more edits detected
                                      └── Reclazz compiles the changed file
                                          └── Hot-swaps the result
```

If you resume typing before the timer expires, it resets. This prevents unnecessary compilations while you are still editing.

### Adjusting the Timer

| Setting | Effect |
|---|---|
| **100ms** | Very aggressive — compiles almost instantly after every pause. High CPU usage. |
| **500ms** (default) | Good balance — compiles after a brief pause. Suitable for most workflows. |
| **1000-2000ms** | Conservative — waits for a longer pause. Lower CPU usage, but slower feedback. |
| **3000-5000ms** | Very conservative — only compiles after you clearly stop editing. |

Adjust the timer in **Settings** > **Tools** > **Reclazz** > **Debounce (ms)**.

### Performance Considerations

- **CPU:** Each compilation invokes the JDK compiler. Frequent compilations can increase CPU usage, especially on large files or slow machines.
- **File descriptors:** The file watcher opens file descriptors for every watched directory. On large Hybris installations with many extensions, this can approach OS limits. The **Startup delay** setting (default: 30s) helps by delaying the watcher until the server has finished starting.
- **Incomplete code:** If you pause mid-edit, Reclazz may try to compile syntactically invalid code. Compilation errors are shown in the tool window but do not affect the running server.

### Recommendation

Use auto-build for rapid iteration on small changes (fixing a service method, tweaking business logic). For larger changes involving multiple files, use `ant build` or IntelliJ's Build Project (Ctrl+F9 / Cmd+F9) instead.

---

## Option 4: Attach to Running Server

Use this option when the SAP Commerce server is **already running** without the `-javaagent` flag — for example, started from a terminal or by another team member. Reclazz uses the JVM Attach API to load the agent into the running process without restarting it.

### How It Works

The IntelliJ plugin scans for running JVMs, identifies SAP Commerce processes (by detecting the Tanuki wrapper or Hybris bootstrap loader), and dynamically loads `reclazz-agent.jar` into the selected process. The agent initializes inside the running JVM and begins watching for class changes.

### Requirements

- IntelliJ IDEA and the Hybris server must run as the **same OS user** (JVM Attach API restriction)
- JDK 17+ (the `jdk.attach` module must be available)

### Usage

**From the menu:**

1. Go to **Tools** > **Attach Reclazz to Running Server**
2. If multiple SAP Commerce processes are detected, pick the one you want
3. The agent loads and the plugin connects automatically
4. Status bar shows "Reclazz: Connected"

**From settings:**

1. Go to **Settings** > **Tools** > **Reclazz**
2. In the **Connection** group, click **Attach to Running Server...**

### Workflow

```
1. Start SAP Commerce from terminal (without -javaagent)
   ./hybrisserver.sh

2. In IntelliJ: Tools > Attach Reclazz to Running Server
   └── Plugin detects the running JVM
   └── Agent is loaded dynamically
   └── Reclazz banner appears in the server console
   └── Status bar shows "Reclazz: Connected"

3. Edit Java source files

4. Compile with ant:
   cd hybris/bin/platform
   ant build

5. Reclazz detects the new .class files and hot-swaps them
   └── No server restart needed
```

### Limitations

- **JBR flags cannot be added retroactively**: If the server was started without `-XX:+AllowEnhancedClassRedefinition`, the JVM cannot switch to enhanced redefinition mid-run. The companion-class reloader still works on the attached JVM, so structural changes are still applied — but reflective access to the new members carries the standard-JVM caveat (see below).
- **Startup delay is skipped**: Since the server is already running, the agent starts watching immediately (no `startupDelaySec` wait).

### Troubleshooting

- **"No running SAP Commerce JVM found"**: The server must be running before you attach. Reclazz looks for processes containing `org.tanukisoftware.wrapper.WrapperSimpleApp`, `-Dplatform.home=`, `-DHYBRIS_BIN_DIR=`, or `de.hybris.bootstrap.loader.Loader`.
- **"Permission denied"**: IntelliJ and the server must run as the same OS user. If the server runs as `hybris` and IntelliJ runs as your user, the attach will be refused.
- **"Agent is already loaded"**: The agent was already attached. The plugin will reconnect to the existing agent instance.

---

## When a Reload Goes Wrong

A reload is a change to a running program, and the way back is the same as
the way in: change the file back and save. The old bytes are reloaded like
any other change, so an edit that throws on every request is undone in the
editor, without a restart. What the agent offers on top:

| Ask | Answer |
|---|---|
| `DIAGNOSE <class>` (Tools menu, or a line on the status socket) | what happened to the class the last time it was saved, and the file the bytes came from |
| `PENDING` | what this session has done that only a restart completes; when the list is empty, nothing is |
| `HEALTH` | reloads, failures, latency, what is watched, and a reload that is still running |
| `sessionLog=<path>` | the whole session, timestamped, for reading back after the fact |

When the trouble is the agent rather than the edit: `excludeClasses=<pattern>`
takes a class out of instrumentation (method-body reloads still work for it),
`structuralReload=false` turns the companion engine off for the session, and
removing the `-javaagent` line runs the application with no agent at all. None
of these loses application state that a restart would have kept; a restart is
the last step, and `PENDING` says when it is the only one.

## Debugging Reloaded Code

Breakpoints keep working in code Reclazz has reloaded. On a stock JDK an
edited method body runs in a companion class named after yours
(`Greeter$$Reclazz$v1`), and the debugger finds it the way it finds anonymous
classes and lambdas: it carries the original source file name and the new
body's line numbers, so a breakpoint on a line of the edited method binds and
hits, the frame shows `Greeter.greet` in `Greeter.java`, and stepping into
the method is not filtered out. Set or move the breakpoint after the edit if
the line you want did not exist before; a breakpoint on a line that the old
body had and the new one does not has nothing to bind to, as in any rebuild.

## Other Clients and Build Tools

Everything the IntelliJ plugin sees comes over a small loopback socket, and
anything else can read it or send the four commands, `DIAGNOSE`, `PENDING`,
`HEALTH` and `SCAN`: another IDE's extension, a Gradle or Maven build that
nudges the agent when it has finished writing class files, a log shipper.
The port file, the JSON lines and the commands are in
[protocol.md](protocol.md), with a Gradle and a shell recipe for `SCAN`.

## Seeing Reloads in JDK Flight Recorder

Every reload is also a Flight Recorder event, so it sits on the same timeline
as the garbage collections, safepoints and JIT compilations around it. Nothing
to configure: the events are emitted whenever a recording is running.

```bash
jcmd <pid> JFR.start name=reclazz settings=default
# ... save a few files ...
jcmd <pid> JFR.dump name=reclazz filename=reclazz.jfr
jfr print --events reclazz.Reload,reclazz.ReloadFailed reclazz.jfr
```

| Event | Fields |
|---|---|
| `reclazz.Reload` | `className` (the JVM's name, `Outer$Inner` included), `structural` (members added or removed, as opposed to bodies changing), `measured` (the measured time, or -1 for one of a batch timed as a whole), `shape` (what changed, as `v2, +1 method`, when known), `source` (the class file the bytes came from, or the `.java` file that was compiled in AutoCompile mode) |
| `reclazz.ReloadFailed` | `className`, `reason`, `source` |

In JDK Mission Control they appear under the **Reclazz** category in the event
browser. A recording of a slow session, sent along with a report, shows what
the agent did and when without the console log.

## Choosing the Right Mode

| Scenario | Recommended Mode |
|---|---|
| Server runs from terminal (`./hybrisserver.sh`) | [Manual Setup](#option-1-manual-setup) |
| Server runs from IntelliJ, compile with `ant build` | [IntelliJ Auto + ant build](#workflow-with-ant-build) |
| Server runs from IntelliJ, want instant feedback | [IntelliJ Auto + AutoCompile](#workflow-with-intellij-build-autocompile-mode) |
| Rapid iteration on single files | [Auto-Build on Idle](#option-3-auto-build-on-idle-optional) |
| Server already running without agent | [Attach to Running Server](#option-4-attach-to-running-server) |
| CI/CD or headless environments | [Manual Setup](#option-1-manual-setup) with standalone agent |

---

## Supported JDK Providers

Reclazz works with **any JDK 17+** that supports the standard Java Instrumentation API (`java.lang.instrument`). Structural hot-reload (adding/removing methods and fields) works on every supported vendor — the difference between enhanced-redefinition VMs (JBR/DCEVM) and standard JVMs is reflective visibility, not whether the change takes effect.

| JDK Provider | Method Body | Structural Reload | Reflective Visibility of New Members |
|---|---|---|---|
| **JetBrains Runtime (JBR)** | Yes | Yes (enhanced redefinition) | Full — reflection sees new members |
| **DCEVM** | Yes | Yes (enhanced redefinition) | Full — reflection sees new members |
| **Oracle JDK** | Yes | Yes (companion-class mode) | Hot-compiled callers only |
| **SapMachine** | Yes | Yes (companion-class mode) | Hot-compiled callers only |
| **OpenJDK** (generic) | Yes | Yes (companion-class mode) | Hot-compiled callers only |
| **Amazon Corretto** | Yes | Yes (companion-class mode) | Hot-compiled callers only |
| **Eclipse Temurin (Adoptium)** | Yes | Yes (companion-class mode) | Hot-compiled callers only |
| **Azul Zulu** | Yes | Yes (companion-class mode) | Hot-compiled callers only |
| **BellSoft Liberica** | Yes | Yes (companion-class mode) | Hot-compiled callers only |
| **GraalVM** | Limited | Limited | — (not recommended) |

> **Important:** The `-XX:+AllowEnhancedClassRedefinition` flag is **JBR/DCEVM-only**. Do not add this flag manually when using Oracle JDK, SapMachine, OpenJDK, or any other standard JVM — it will cause a startup crash. The plugin only adds this flag when it detects a compatible JDK.

> **GraalVM note:** GraalVM has known limitations with `Instrumentation.redefineClasses()`. Some method body changes may fail. If you experience issues, consider switching to a standard OpenJDK or JBR.

### Companion-class reflective visibility caveat

On standard JVMs, structural reloads use a hidden companion nestmate
instead of modifying the original `Class` object (which the JVM
doesn't allow). This has one consequence worth knowing:

**Works after a structural reload (any JDK 17+):**
- Hot-compiled Java code that calls a new method directly — the
  invocation is rewritten through the companion via `invokedynamic`
- Hot-compiled Java code that reads/writes a new field
- Hybris Jalo-layer property access (`jaloItem.setProperty(...)`)
- Flexible search with new attribute columns (after HAC
  `updatesystem` for items.xml changes)

**Needs server restart on standard JVMs (works immediately on JBR/DCEVM):**
- `Class.getMethod("setNewThing", ...)` on the original class
- Reflective caches built at boot time (Hybris `ModelService`'s
  attribute dispatch, Jackson, Gson, etc.)
- Groovy console reflection that reaches through
  `ModelService.setAttributeValue` / `getAttributeValue`

If your workflow relies heavily on reflective access to
newly-added members (e.g. scripted type-system exploration), use
JBR or DCEVM for full visibility. For the typical edit-compile-run
loop on hand-written Java, companion-class mode is transparent.

### Setting Up JBR

1. Download JBR 17 or 21 from [JetBrains releases](https://github.com/JetBrains/JetBrainsRuntime/releases)
2. Set `JAVA_HOME` to the JBR installation
3. If using Manual Setup, add `-XX:+AllowEnhancedClassRedefinition` to your JVM args
4. If using IntelliJ Auto mode, the plugin detects JBR automatically and adds the flag for you

---

## Known Limitations

### New Field Values on Objects That Already Existed

When a structural reload adds an instance field, objects created after the
reload run the new constructor and get the field's initialiser value. An
object that already existed did not, so Reclazz lifts the initialiser's own
instructions out of the constructor and runs them for that object on the
field's first read. Adding `private final List<String> cache = new
ArrayList<>();` to a live Spring singleton therefore reads a list, and
`private int retries = 3;` reads 3. The initialiser may read the object's
other fields and call its methods, which the live object has; each object
computes its own value; a value the application wrote before the first read,
null included, is kept; and the initialiser runs once per object.

What cannot be lifted keeps the type default (`null`, `0`, `false`) on
pre-existing objects, and the reload names the field and the reason:

- an assignment that reads a constructor argument (`this.upper =
  name.toUpperCase()` in the constructor body), because the object no longer
  has the argument;
- an initialiser that branches, sits in a try/catch, or shares a computation
  with another field;
- an initialiser that throws on first read: the field reads the default and
  the initialiser is not tried again.

On JetBrains Runtime or DCEVM the field is a real field on the redefined
class, so reflection sees it, and objects from before the reload keep the type
default there.

---

## What Cannot Be Hot-Reloaded

These are fundamental JVM and SAP Commerce limitations that no tool can overcome:

| Change | Why | What To Do |
|---|---|---|
| `items.xml` changes | Type system is loaded once at startup | `ant all` + Update Running System (HAC) + restart |
| Generated model classes | Bound to the platform classloader at startup | `ant all` + restart |
| New extensions | Extension list is fixed at startup | `ant all` + restart |
| Class hierarchy changes | JVM does not allow changing superclass/interfaces | Restart |
| New JAR dependencies | Classpath is fixed at JVM startup | Restart |
| Spring XML bean definitions | Application context is loaded once | Restart (or use HAC context refresh) |
