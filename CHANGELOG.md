# Changelog

All notable changes to Reclazz will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [1.0.2] - 2026-08-12

### Fixed

- The introduction shown after installing no longer opens as a dialog
  while the IDE is starting. A modal dialog on the startup path holds the
  event thread until someone clicks it, so the IDE was unusable until
  then. Marketplace automated review hit exactly that and timed out after
  ten minutes, and its second failure, about a missing trial widget, was
  the same stuck IDE rather than a separate fault.

  Reclazz now introduces itself with a sticky notification carrying the
  same two choices. The full introduction is still one click away, and it
  is a dialog again once you ask for it.

## [1.0.1] - 2026-08-12

Marketplace review fixes. No change to what the plugin does.

### Fixed

- Looks the plugin up through the public `PluginManager` API rather than
  an internal one. Marketplace review rejects the internal call; the
  public entry point returns the same descriptor, so behaviour is
  identical.
- The agent's thin and shaded jars were written to the same filename and
  overwrote each other, so the packaged agent was whichever task ran
  last. Released builds carried the shaded jar, but the thin one is
  missing the dependencies the agent loads at startup and would have
  failed on launch. The two are named apart now.

## [1.0.0] - 2026-08-10

First public release.

### Hot reload

- Redefines classes in a running JVM through the Instrumentation API,
  preserving application state.
- Adding and removing methods and fields works on any JDK 17+, through a
  companion-class engine (hidden nestmates plus invokedynamic
  rewriting). JetBrains Runtime and DCEVM additionally make the new
  members visible to reflection and to caches built at startup.
- Compiles changed sources in-process when asked to, batching a
  save-all into one compiler invocation per module.

### Spring

Bean refresh, MVC mapping re-scan, cache eviction, `@Scheduled`
re-registration, `@EventListener` refresh, AOP proxy cache clearing,
`@Async` re-processing, Spring Data repository refresh and Spring
Security change notification. Every live application context is covered,
not only the root one, and references still holding a replaced singleton
are re-pointed at the new instance.

### SAP Commerce

Extension-aware watching, interceptor reload, ImpEx auto-import
(opt-in), and regeneration for `*-items.xml` and `*-beans.xml`. The
agent is installed through the platform's documented append properties,
so it survives `ant clean all` rather than being wiped by it, and the
install verifies that it changed nothing but the agent.

### IntelliJ IDEA plugin

Ships switched off and asks once before enabling, since enabling means
putting an agent into the JVM that runs your code. Injects the agent
into run configurations, configures JVM flags for the detected JDK,
attaches to an already-running server, and reports reloads in a tool
window and status widget.

### Known limitations

- Superclass and interface changes require a restart. No tool can work
  around this.
- On a standard JDK, new members are reachable from code that calls them
  directly, but not from reflection on the original class or from caches
  built at startup, until a restart.
- Annotations can only be changed under enhanced redefinition (JetBrains
  Runtime or DCEVM).
- Attach mode cannot apply structural changes to classes that were
  already loaded, and does not yet discover Spring Boot embedded-Tomcat
  contexts.
- Windows is untested.
