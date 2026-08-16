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
| `verbose` | `false` | Enable verbose logging in the console |
| `statusPort` | `0` | TCP port for plugin communication (0 = auto-assign) |
| `portFile` | (none) | Path where agent writes its actual port after binding |

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
  - Hot-swap results with timing
  - Spring bean refresh events
  - Interceptor reload events
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

### New Field Default Values

When structural reload adds new instance fields to an existing class, **existing object instances** will have default values (`null`, `0`, `false`) for those fields. Only instances created after the reload will have field initializer values applied.

This is a fundamental consequence of how the JVM manages object memory — existing instances cannot be resized to accommodate new fields. Reclazz stores new fields in an external array (`__reclazz$ext`), which starts empty for pre-existing instances.

Objects created after the reload do get the initializer, because they run the
new constructor. A Spring bean is recreated by the reload, so in practice a new
field on a bean holds the value you wrote; it is long-lived objects the reload
did not recreate that keep the default.

**Workaround:** If a new field needs a specific initial value on objects that
already exist, add a null-check or lazy initialization pattern in your code:

```java
private String newField; // Added via structural reload

public String getNewField() {
    if (newField == null) {
        newField = "default value";
    }
    return newField;
}
```

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
