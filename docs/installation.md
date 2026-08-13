# Installation

Reclazz can be installed either from the JetBrains Marketplace or directly from GitHub.

---

## Option 1: JetBrains Marketplace (Recommended)

The easiest way to install Reclazz. The plugin includes the agent JAR bundled inside it, so there is nothing else to download.

### From inside IntelliJ IDEA

1. Open IntelliJ IDEA
2. Go to **Settings** (Ctrl+Alt+S / Cmd+,)
3. Navigate to **Plugins** > **Marketplace**
4. Search for **"Reclazz"**
5. Click **Install**
6. Restart IntelliJ IDEA when prompted

### From the JetBrains website

1. Visit [Reclazz on JetBrains Marketplace](https://plugins.jetbrains.com/plugin/com.onurkat.reclazz)
2. Click **Get** or **Install to IDE**
3. If your IDE is running, it will open directly and prompt you to install
4. Alternatively, download the `.zip` file and install manually:
   - Go to **Settings** > **Plugins** > gear icon > **Install Plugin from Disk...**
   - Select the downloaded `.zip` file
   - Restart IntelliJ IDEA

### What gets installed

When installed from the Marketplace, the plugin directory contains:

```
plugins/reclazz/
├── lib/
│   └── reclazz-<version>.jar          # IntelliJ plugin
└── agent/
    └── reclazz-agent.jar               # Java agent (injected into Hybris JVM)
```

The plugin automatically locates the bundled `reclazz-agent.jar` when injecting the agent into your run configurations. You do not need to manage the agent JAR separately.

---

## Option 2: GitHub

### From GitHub Releases

Pre-built plugin archives are available on the [Releases](https://github.com/onurkat/reclazz/releases) page.

1. Go to [github.com/onurkat/reclazz/releases](https://github.com/onurkat/reclazz/releases)
2. Download the latest `reclazz-<version>.zip` from the release assets
3. In IntelliJ IDEA, go to **Settings** > **Plugins** > gear icon > **Install Plugin from Disk...**
4. Select the downloaded `.zip` file
5. Restart IntelliJ IDEA

### Building from Source

If you want to build the plugin yourself:

1. Clone the repository:

   ```bash
   git clone https://github.com/onurkat/reclazz.git
   cd reclazz
   ```

2. Build the plugin:

   ```bash
   ./gradlew clean build
   ```

   This produces:
   - `build/distributions/reclazz-<version>.zip` — the installable plugin archive
   - `agent/build/libs/reclazz-agent.jar` — the standalone agent JAR (also bundled in the zip)

3. Install the built plugin:
   - Go to **Settings** > **Plugins** > gear icon > **Install Plugin from Disk...**
   - Select `build/distributions/reclazz-<version>.zip`
   - Restart IntelliJ IDEA

### Standalone Agent JAR (for manual setup)

If you only need the agent JAR without the IntelliJ plugin (e.g., for CI environments or manual JVM configuration), you can download or build just the agent:

**From releases:**
- Download `reclazz-agent.jar` from the [Releases](https://github.com/onurkat/reclazz/releases) page

**From source:**
```bash
cd agent
../gradlew shadowJar
# Output: agent/build/libs/reclazz-agent.jar
```

Place the JAR anywhere on your system. You will reference its absolute path in your Hybris configuration (see [Usage Guide — Manual Setup](usage.md#option-1-manual-setup)).

---

## Requirements

| Requirement | Details |
|---|---|
| **IntelliJ IDEA** | 2023.3 or later (Community or Ultimate) |
| **JDK** | 17 or 21 (matching your SAP Commerce version) |
| **SAP Commerce** | 2211 or later (older versions may work but are untested) |
| **JetBrains Runtime** | Optional but recommended for structural changes (add/remove methods/fields) |

---

## Post-Installation: First-Time Configuration

After installing the plugin, you need to enable it for your project:

1. Open your SAP Commerce project in IntelliJ IDEA
2. Reclazz automatically detects that it is a Hybris project (looks for `hybris/bin/platform`)
3. A notification appears: _"SAP Commerce project detected. Enable Reclazz?"_
4. Click **Enable**, or go to **Settings** > **Tools** > **Reclazz** and check **Enable Reclazz**

### Settings Overview

| Setting | Default | Description |
|---|---|---|
| **Enable Reclazz** | Off | Master switch for the plugin |
| **Auto-detect JDK** | On | Detects JDK version and JBR, adds recommended JVM flags automatically |
| **Verbose logging** | Off | Extra detail in the Reclazz tool window |
| **AutoCompile** | Off | Compile `.java` files internally instead of relying on `ant build` |
| **Auto-import ImpEx** | Off | Auto-import changed `.impex` files into the running system |
| **Watch extensions** | (empty = all) | Semicolon-separated list of extension names to watch |
| **Exclude patterns** | (empty) | Glob patterns for files to ignore (e.g., `*Test.class;*Mock*`) |
| **Debounce (ms)** | 500 | Delay before processing file changes (batches rapid changes) |
| **Startup delay (s)** | 30 | Wait before watching files (avoids fd exhaustion during server startup) |

See the [Usage Guide](usage.md) for detailed instructions on each usage mode.

---

## Verifying the Installation

1. Open the **Reclazz** tool window at the bottom of IntelliJ IDEA
2. The status bar shows **"Reclazz: Idle"** when the plugin is enabled but no server is running
3. Start your SAP Commerce server — the status changes to **"Reclazz: Connected"**
4. Click **Settings** > **Tools** > **Reclazz** > **Test Agent Connection** to verify connectivity

If the connection test fails, see the [Troubleshooting](../README.md#troubleshooting) section.
