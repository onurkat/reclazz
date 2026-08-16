# Changelog

All notable changes to Reclazz will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Fixed

- Adding a method and calling it from an existing method of the same class
  works. The JVM will not accept a redefinition that adds a member, so an added
  method lives in the companion, and after a structural reload the original
  bodies are trampolines and the real code runs there. The call was resolved
  against the original class, which cannot have the method, and failed with a
  BootstrapMethodError. On a live SAP Commerce server, adding one helper to a
  servlet filter turned every request into an HTTP 500 while the reload was
  reported as successful.

- Spring XML reload works on files that use a namespace. Spring finds the
  handler for `context:`, `util:` and the rest by reading `META-INF/spring
  .handlers` from the classpath, and the parser was reading it through the
  agent's own classloader, which contains no Spring at all. Every file
  declaring a namespace failed to parse, which on a real project is nearly all
  of them. Measured on a live server: the same file went from
  `Unable to locate Spring NamespaceHandler` to 415 changes applied, and a bean
  added to it was resolvable in the running context.

## [1.0.16] - 2026-08-16

### Added

- The platform's own `config` directory is watched, so editing
  `config/local.properties` or a numbered file under `config/<env>/props`
  reaches the running server. Until now nothing watched the file a developer
  actually edits, and the property reloader could only ever fire on files that
  are not configuration.

### Fixed

- A saved property file is compared against its own previous content instead of
  against the running configuration, and a value still holding a `${...}`
  placeholder is never written. The configuration is not a copy of any one
  file: the platform expands placeholders as it loads, and layers files so the
  last one to define a key wins. Comparing against it made untouched lines look
  edited. On a live server, adding one line to a `config/dev/props` file
  reported nine keys applied, two of them the SSO keystore and metadata
  locations, whose working absolute paths were replaced by the literal
  characters `${HYBRIS_CONFIG_DIR}`.

### Changed

- Only the platform's own configuration files are applied to the running
  server: `local.properties`, `project.properties`, and the numbered files the
  platform keeps in a `props` directory. Everything else spelled `.properties`
  is left alone. On a mid-sized project 353 property files held 8,728 keys and
  350 of those files were message bundles for e-mails and OCC responses, so an
  e-mail subject line could end up in the configuration every component reads,
  and it took no edit to get there: checking out a branch writes the files.
- A localization file is read in a bounded way. It is streamed rather than read
  whole, and one over 8 MB is not read at all, so a stray file in a watched
  directory cannot take the heap out from under the server the agent is running
  inside.

- A localization file that is saved without its text changing no longer clears
  the caches. Clearing them is instant, but the next reader pays for the
  rebuild: measured on a 2211 server, 830ms for the platform's localizations
  and just under four seconds for ZK's labels. Saves that change nothing are
  common, because the platform's build re-copies resource files and one `ant
  build` touches every localization file in every extension. The file is now
  compared against what it held last time, which costs 0.002ms.

## [1.0.15] - 2026-08-15

### Added

- Editing a SAP Commerce localization file takes effect without a restart.
  Save an `<ext>-locales_<iso>.properties` and the type or enum name you
  changed is served on the next read, with no database write and no system
  update. Backoffice label files refresh the same way: reopen the view and the
  new text is there.

### Fixed

- Locales files are no longer mistaken for property files, which is what made
  saving one report a successful reload and change nothing on screen.

## [1.0.14] - 2026-08-15

### Added

- Editing a SAP Commerce property file applies the changed keys to the running
  server. The platform reads its property files at startup and never again, so
  an edit reached nothing; the keys whose value differs are now applied through
  the same call the HAC console uses. Only the differences are applied, and
  Reclazz notes that values consumed once at startup still need a restart.

## [1.0.13] - 2026-08-15

### Added

- Editing a Thymeleaf or Freemarker template takes effect without a restart.
  A template is data rather than code: the engine parsed it once and served
  that copy until something dropped it, which made the change that looks
  easiest to see the one that needed a restart. Engines register themselves
  from their constructor, because application code and Spring build them and
  keep them private, and a template change now clears their caches.

  Measured against both engines with caching configured the way production
  configures it: neither picked up an edited template, and both do now.
  Applications with no template engine hear nothing.

### Fixed

- A class referenced from instrumented bytecode is no longer skipped by the
  instrumentation itself. Computing stack map frames used ASM's default
  superclass resolver, which calls `Class.forName`, so transforming a class
  that contains `new Product()` loaded `Product` from inside the transform.
  The JVM does not re-enter transformers for a class loaded that way, and the
  infrastructure can only be added while a class loads, so that class could
  never be reloaded structurally afterwards.

  It failed quietly and asymmetrically, which is why it lasted: a class
  referenced from transformed bytecode lost, the same class named only in a
  string did not. JPA entities lost, because whatever builds the
  EntityManagerFactory references them, so adding a field to an entity failed
  with a JVM message about schemas that pointed nowhere near the cause. Entity
  classes now reload like any other.

  Hibernate's own mapping still describes the entity as it was at startup, so
  a field added to an entity is not persisted until a restart. That is the
  next piece of work, not this one.

- Adding a static field no longer kills the thread. The companion fell
  through to a plain `GETSTATIC` against a class whose schema does not have
  the field, on the reasoning that adding a static field was unusual. It is
  not, and the resulting `NoSuchFieldError` brought the application down
  after the reload had already reported success. Added static fields now go
  to the same store the companion already used for instance fields.

- A field added by a reload is initialised on objects created afterwards.
  Previously they came back with the default, because the loaded class kept
  the constructor compiled before the field existed, and because a write to
  an unresolvable field resolved to `MethodHandles.empty`: a call site that
  accepted the value and dropped it. The redefine payload now has the added
  members stripped, which is what makes the JVM accept it and lets the new
  constructor through, and unresolvable field access falls back to the
  companion store instead of a no-op.

  Objects that already existed keep the default. They were built before the
  field did, and no hot-reload tool can change that.

### Changed

- Two limits that used to be reported as one are now reported separately. An
  added static field reads as null or zero until a restart, because its
  initialiser lives in `<clinit>` and re-running that would reset every other
  static the class holds; that is now said out loud rather than looking like
  a null bug. Adding a value to an enum is still refused, for the same reason
  plus the ordinal-indexed structures across the application that would be
  sized for the old constant count.

## [1.0.12] - 2026-08-15

### Fixed

- Notifications can be dismissed again. A user reported one that would not
  delete and went away only after restarting the IDE. The platform starts a
  balloon's fade-out timer either immediately, when the IDE is the active
  application, or on the next activation event; a balloon posted while a
  project is still opening can fall between the two, never start its timer,
  and then never fade. While the balloon is still live its entry cannot be
  removed from the event log either, which is what made it look undeletable.
  Reclazz now waits for the IDE to be active before posting, which puts the
  platform on the branch that starts the timer straight away.

- The JDK capability message is written to the Reclazz tool window instead of
  arriving as a balloon. It says which JDK you are on and what that means for
  structural reload, which is context worth having where you are already
  looking rather than something to dismiss. A JDK that cannot hot-reload at
  all still warns.

## [1.0.11] - 2026-08-14

### Fixed

- The reload log no longer reports a failure that did not happen. Once a
  class has gained a member, the members live in a companion rather than in
  the loaded class, so the JVM refuses the constructor-body refresh with
  "attempted to add a method". That is the companion engine working as
  designed and the reload itself succeeds, but it was reported as a warning
  with the raw exception attached, on the tool window, every time you touched
  a class you had once added a method to. Genuine failures are still
  reported.

- Reconnecting to the agent says what happened rather than what was being
  attempted. It used to post "Reconnecting to agent..." and stop there, so
  anyone whose server was not running never learned that nothing had been
  found. It now names that case and points at Attach.

- The introduction no longer tells you Reclazz is switched off when it is on.
  It is shown once per installation rather than once per project, so anyone
  who had already enabled it somewhere met a notification contradicting their
  own IDE, offering a button to enable what was already enabled.

## [1.0.10] - 2026-08-14

### Fixed

- The agent no longer breaks SAP Commerce OCC. With Reclazz attached, every
  OCC response came back an empty 400, for as long as it was attached: one
  server's log carried the failure 210 times in a day, and the only known
  workaround was to comment out the `-javaagent` line.

  Reclazz writes members into the classes it transforms, an
  `__reclazz$ext` array, an `__reclazz$lookup` handle and `__reclazz$v0$...`
  copies of renamed methods, and reflection reported all of it as if the
  user had written it. SAP Commerce builds its JAXB context by walking every
  declared field of every DTO, with no filter on synthetic or static, and
  adding each field's type to the set of classes it hands to MOXy. So
  `__reclazz$lookup` put `MethodHandles$Lookup` into that set, MOXy followed
  it into JDK internals, and building the context failed. Any framework that
  walks members could hit this; OCC is simply the one that walks types
  recursively.

  Those members are now invisible to reflection, including when asked for by
  name. Members you add yourself and reload structurally are unaffected and
  still show up.

## [1.0.9] - 2026-08-14

### Fixed

- Switching Reclazz on in a project now brings up the tool window straight
  away. It used to appear only after an IDE restart, because the platform
  asks a tool window whether it belongs in a project when that project
  opens and never asks again. The status bar item had the same problem and
  is fixed by the same change; the two are now one decision rather than
  two that can drift apart.

## [1.0.8] - 2026-08-14

### Changed

- ImpEx auto-import refuses a file containing a `REMOVE` header instead of
  running it. Auto-import executes against your live database the moment
  the file is saved, with no confirmation step and nothing that undoes it,
  and saving a file is not the same act as asking for rows to be deleted.
  The refusal names the line so you can go and look at it. Set
  `impexAllowRemove=true` on the agent if deleting is what you meant.

  Insert and update files, which is what the edit-and-see-it loop is
  actually for, are unaffected.

- The status bar item appears only in projects where Reclazz is switched
  on, matching the tool window, which already scoped itself that way. It
  used to occupy the status bar of every project you opened. Ticking or
  unticking the setting now takes effect immediately rather than at the
  next restart.

### Fixed

- Clicking the status bar item opens the Reclazz tool window. It
  previously ignored clicks, so the one part of the plugin that is always
  on screen could report "Not connected" and offer no way to act on it.

## [1.0.7] - 2026-08-14

### Fixed

- Updating the plugin now updates the agent your SAP Commerce server
  loads. The server starts outside the IDE and reads a staged copy of the
  agent from your home directory, and that copy was only ever written
  during agent installation. So the plugin could report one version while
  the server's console printed the one it was first installed with, for
  as long as the installation lasted, with nothing anywhere saying they
  disagreed.

  The staged copy is refreshed when a project opens, and only when one is
  already there. Because a running server has the old agent in memory
  until it restarts, Reclazz says so rather than leaving you to wonder
  why a fix did not arrive.

## [1.0.6] - 2026-08-14

### Changed

- The Marketplace description leads with SAP Commerce. Reclazz competes
  with a free, bundled tool on plain Spring, and with nothing at all on
  Hybris, where a restart costs minutes and an items.xml change costs a
  rebuild. The listing described the second case seventh, in one line.
  Nothing about the plugin changed.

## [1.0.5] - 2026-08-13

### Fixed

- Changing an annotation now takes effect without a restart on any JDK
  17+, where it previously required JetBrains Runtime or DCEVM. Editing
  a `@RequestMapping` value moves the endpoint, and the old path stops
  responding.

  This was never a JVM limitation, which is what the earlier releases
  documented. A stock JVM accepts an annotation change and reflection
  reports it. Three things stood in the way, each hiding the next: the
  structural diff ignored annotations, so no framework was told anything
  had changed; three reflective lookups in the MVC reloader were wrong,
  so a re-scan could never run; and Spring caches its reflection per
  class, so once the re-scan did run it faithfully re-registered the
  methods it had parsed at startup. Those caches are cleared now.

- A failed MVC re-scan says so. Every one of the faults above was
  silent, which is why an annotation change looked like it was being
  ignored rather than like something was broken.

## [1.0.4] - 2026-08-13

### Changed

- Reclazz now installs on IntelliJ IDEA 2023.3 and later, down from
  2024.1. Nothing in the code required the higher floor; 2023.3 is the
  oldest platform the build tooling itself supports. It matters for SAP
  Commerce work in particular, where the IDE version is often set by
  corporate policy rather than by the developer.
- The plugin is now compiled against 2023.3 rather than 2024.1, so an
  API that only exists in a newer IDE cannot slip in unnoticed.

## [1.0.3] - 2026-08-12

### Changed

- The contact address on the Marketplace listing is now
  `onur@onurkat.com`, matching the one `SECURITY.md` gives for
  vulnerability reports. The two used to differ, so the two places
  people look for a way to reach the maintainer gave two answers.

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
