# Reclazz on SAP Commerce, for a coding agent

This is the SAP Commerce (Hybris) counterpart of
[for-ai-agents.md](for-ai-agents.md): how a coding agent working on a Hybris
project enables Reclazz from the shell, verifies it, and keeps its edit-run loop
hot instead of restarting a server that takes minutes to come up. For the full
human setup and every agent argument, see the manual setup in
[usage.md](usage.md#option-1-manual-setup); this page is the agent-oriented
recipe on top of it.

## Why Hybris is different from Spring Boot

A Spring Boot app is one JVM you start yourself, so a build plugin or a plain
flag attaches the agent. A SAP Commerce server starts outside the IDE, through
the Tanuki wrapper, and its `tomcat/conf/wrapper.conf` is generated: any
`-javaagent` line added there by hand is wiped the next time `ant` regenerates
the config, with no error. The supported seam is the platform's own append
properties, `tomcat.javaoptions` and `tomcat.debugjavaoptions`, which are folded
into the generated wrapper config. Write there and the agent is put back by the
very regeneration that used to remove it.

## Enable it from the shell

1. Get the agent jar. It is on Maven Central as
   `com.onurkat.reclazz:reclazz-agent`; resolve or download
   `reclazz-agent-1.3.0.jar` and note its absolute path.

2. Append the agent to the last properties layer your project reads, commonly
   `${HYBRIS_HOME}/config/local.properties`. Append, do not replace:
   `tomcat.debugjavaoptions` normally carries the JDWP flags, and dropping those
   breaks debugging.

   ```properties
   tomcat.javaoptions=<your existing options> -javaagent:/abs/path/reclazz-agent.jar=platform=hybris,hybrisHome=${HYBRIS_HOME},autoCompile=true
   tomcat.debugjavaoptions=<your existing debug options> -javaagent:/abs/path/reclazz-agent.jar=platform=hybris,hybrisHome=${HYBRIS_HOME},autoCompile=true
   ```

   `platform=hybris` is optional (the agent infers it from `hybrisHome`), but
   naming it is unambiguous. On JetBrains Runtime or DCEVM add
   `-XX:+AllowEnhancedClassRedefinition` after the flag for full structural
   reloads; on JDK 24 and newer add `--sun-misc-unsafe-memory-access=allow` so
   appending an enum constant keeps working (do not add it on a JDK older than
   23: the launcher refuses to start).

3. Regenerate the wrapper config and restart. This is not just a restart, the
   config has to be rebuilt from the properties first:

   ```bash
   cd ${HYBRIS_HOME}/bin/platform
   . ./setantenv.sh
   ant server
   ./hybrisserver.sh    # or hybrisserver.sh debug for tomcat.debugjavaoptions
   ```

The path in these lines is specific to this machine, so keep the change out of
version control (a per-developer config layer or an untracked `local.properties`).

## The human one-click alternative

A developer in IntelliJ does not do any of the above by hand: with the Reclazz
plugin installed, **Tools > Reclazz** writes exactly these properties into the
right config layer (its own `99-reclazz.properties` when an optional config
directory exists), preserves existing values, adds the JDK flag only when the
server's JDK needs it, and reminds you to run `ant server`. The shell recipe
above is for an agent or a headless environment.

## Verify it attached, and the edit-run loop

The agent writes its status port to `${HYBRIS_HOME}/.reclazz/agent.port` once the
server is up. Check it machine-readably with the
[MCP server](mcp-server.md), pointing it at the Hybris home:

- `reclazz_status` with `hybrisHome` set reports attached, agent version and
  health.
- After `ant build`, `reclazz_scan` nudges a reload instead of waiting for the
  next poll; `reclazz_pending` lists what still needs a restart.

The loop on Hybris:

1. Edit a `.java` file in an extension.
2. `ant build` (or let the IDE compile). The agent hot-swaps the changed classes.
3. Exercise the endpoint or backoffice. Only restart for what `reclazz_pending`
   reports (a changed superclass, a reordered enum).

Hybris-specific saves also reload without a restart: `*-items.xml` and
`*-beans.xml` regenerate and reload their models and DTOs (`autoCompile=true`),
changed properties and log levels reach the running server, `.impex` files import
on save with `autoImpex=true` (a REMOVE header is refused), and interceptor
registrations and backoffice labels are restored. A new items.xml attribute still
needs its database column, so Reclazz prints a reminder to run
**HAC > Update Running System** rather than writing the database itself.

## Drop-in AGENTS.md recipe

Paste this into the Hybris project's `AGENTS.md` so the agent that works on it
keeps using hot reload. Replace the paths with the project's own.

```markdown
## Hot reload with Reclazz (SAP Commerce)

This project runs its SAP Commerce server with the Reclazz agent attached
(wired into tomcat.javaoptions), so a running server picks up compiled changes
without a restart. Prefer this loop over restarting: a restart costs minutes.

Apply a Java change while the server runs:
1. Edit the code in the extension.
2. Rebuild: `cd ${HYBRIS_HOME}/bin/platform && ant build`.
3. The agent hot-swaps the changed classes. Do not restart.
4. Verify with the reclazz_status MCP tool (hybrisHome set), or check
   ${HYBRIS_HOME}/.reclazz/agent.port exists and the log shows the reload.

For *-items.xml / *-beans.xml changes the agent regenerates and reloads the
models; a genuinely new attribute needs HAC > Update Running System for its
column. Restart only for what reclazz_pending reports (changed superclass,
reordered enum). If the agent is not attached, it was added to
tomcat.javaoptions and needs `ant server` then a server restart to take effect.
```
