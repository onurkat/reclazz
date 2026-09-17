# Agentic adoption: getting AI coding agents to use Reclazz

There are two different goals. One is being recommended: when a developer asks
an assistant in chat, Reclazz gets named. That is covered in
[llm-visibility-strategy.md](llm-visibility-strategy.md). The other, this
document, is being used: when a coding agent (Cursor, Copilot, Claude Code,
Windsurf, Aider and the like) sets up or runs a Spring or SAP Commerce project,
it actually wires Reclazz in and relies on it.

Recommendation is mostly a content and discovery problem. Adoption is mostly a
product-ergonomics problem: an agent works in the terminal and in build files,
not in the IntelliJ GUI, so whatever an agent cannot do from a shell or a build
edit, it will not adopt on its own.

## What already helps

Reclazz starts from a good base for agents:

- A standalone agent with a plain `-javaagent` flag and no IDE dependency, so
  it can be enabled from a shell or a container env var.
- Zero config: the flag alone works, no config file required.
- No telemetry and open source, so an agent (and any safety layer around it)
  can adopt it without a phone-home or a trust problem.

## Where an agent gets stuck today

- The agent jar is fetched from GitHub releases, not resolved from a package
  registry. An agent cannot add a coordinate and let the build resolve it; it
  has to download a file from a URL, which is a less standard, less reliable
  step for a build.
- There is no Gradle or Maven plugin, so the agent has to hand-wire the
  `-javaagent` flag into `bootRun`, the test task, or the Hybris Tomcat config
  rather than applying a plugin and letting it wire itself.
- There is no MCP server, so an agent that speaks MCP cannot drive Reclazz as a
  callable tool during its edit-run loop (reload now, status, what changed).
- The repository has an AGENTS.md, but it is the working agreement for agents
  building Reclazz itself, not a drop-in recipe telling a downstream project's
  agent how to run that project with Reclazz. Those are different files with
  different audiences.
- There is no machine-readable status or health output, so an agent cannot
  cleanly confirm that Reclazz attached and a reload happened, and cannot
  self-correct when it did not.
- There is no starter or template, so an agent scaffolding a new project has
  nothing to pull that turns Reclazz on by default in development.

## Levers, in priority order

1. Make it resolvable and self-wiring from the build. Publish the agent jar to
   Maven Central so it has a coordinate, and ship a Gradle plugin and a Maven
   plugin that add the `-javaagent` to the dev run and test tasks. This is the
   single change that turns "an IntelliJ GUI plugin" into "one line an agent
   adds to build.gradle."
2. Give the agent a copy-pasteable, deterministic dev-run command in the
   README, phrased as the exact command to run, so the agent lifts it verbatim.
3. Ship a downstream AGENTS.md recipe and a "For AI agents" section on the site
   and README: the exact dev command with Reclazz, in the format an agent reads
   first. Keep an llms.txt for agent and dev-doc retrieval (it is weak for
   consumer chat but useful here).
4. Provide an MCP server (reclazz-mcp) exposing reload, status and
   what-changed, so Reclazz becomes a capability an agent calls, not only a
   background process.
5. Add a machine-readable status command (for example `reclazz status` printing
   a small JSON: attached, watched paths, last reload, JDK mode), so an agent
   can verify success and recover from failure.
6. Publish a Spring Boot starter (reclazz-spring-boot-starter) that
   auto-configures in the dev profile, and a GitHub template repository, so a
   scaffolding agent gets Reclazz on by default.
7. Keep feeding the agent's reflexes: the published articles, GitHub topics and
   README, honest disclosed answers on forums, and any framework-blessed
   recipe (SAP Commerce guides, Spring how-tos). Agents fetch these at run time
   and are trained on them.

## Product work versus marketing

Items 1, 4, 5 and 6 are code, a new capability to build, not a listing to
submit. Items 2, 3 and 7 are documentation and content. The highest-leverage
work is the build plugin plus Maven Central plus the dev-run command, because
until an agent can enable Reclazz by editing a file it already edits, no amount
of recommendation converts into use.

## Task list

Owner column: BUILD means a code change for the build phase (Astra plus audit),
DOCS means documentation or content, OWNER means an account or publishing
action only the owner can take.

Distribution and build integration
- [ ] Publish the agent jar to Maven Central under a stable coordinate. (BUILD + OWNER)
- [ ] Gradle plugin that applies the `-javaagent` to `bootRun`, `test` and a dev run task. (BUILD)
- [ ] Maven plugin equivalent for `spring-boot:run` and the surefire or dev run. (BUILD)
- [ ] Publish the Gradle plugin to the Gradle Plugin Portal. (BUILD + OWNER)
- [ ] One copy-pasteable dev-run command block at the top of the README. (DOCS)

Agent-facing docs
- [ ] A downstream AGENTS.md recipe file that projects can drop in, naming the exact dev command with Reclazz. (DOCS)
- [ ] A "For AI agents" section on reclazz.com and in the README. (DOCS)
- [ ] An llms.txt aimed at agent and dev-doc retrieval. (DOCS)

MCP and verification
- [ ] reclazz-mcp server exposing reload, status and what-changed. (BUILD)
- [ ] `reclazz status` (or an agent flag) printing machine-readable JSON: attached, watched paths, last reload, JDK mode. (BUILD)

Starters and templates
- [ ] reclazz-spring-boot-starter that auto-configures under the dev profile. (BUILD)
- [ ] A GitHub template repository: Spring Boot plus Reclazz, dev-ready. (BUILD + OWNER)
- [ ] A SAP Commerce equivalent recipe or template where feasible. (BUILD + DOCS)

Discovery that reaches agents at run time
- [ ] GitHub repo topics current and specific. (OWNER)
- [ ] Keep the published articles and any framework recipes accurate and linked. (DOCS)
- [ ] One honest, disclosed answer per genuinely relevant forum thread. (OWNER)
