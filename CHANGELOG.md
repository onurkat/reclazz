# Changelog

All notable changes to Reclazz will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Added

- An appended enum constant now round-trips through Jackson without a restart.
  Jackson builds an enum's serializer and deserializer once per ObjectMapper
  and keeps them sized to the constants that existed at first use; measured on
  Spring Boot 3.3.4 (stock JDK 21, Jackson via spring-boot-starter-web) after
  appending URGENT to a two-constant enum whose endpoints had already served
  traffic: serialising the new constant answered HTTP 500
  (`HttpMessageNotWritableException: Could not write JSON: Index 2 out of
  bounds for length 2`) and deserialising its name answered HTTP 400 (`not one
  of the values accepted for Enum class: [HIGH, LOW]`), while old constants
  kept working both ways. After a successful append the agent now flushes the
  serializer cache, the deserializer cache and the root-deserializer map of
  every ObjectMapper registered as a Spring bean, purely reflectively: Reclazz
  gains no Jackson dependency, and an application without Jackson or without
  Spring is measurably untouched (the plain-Java append run is byte for byte
  the same). Verified live after the fix: the same endpoints serialise
  `{"code":"T-URGENT","priority":"URGENT"}` and deserialise
  `deser:URGENT:ordinal=2`, old constants unchanged, an unknown name still
  400. A mapper is only counted, and only touched, when all three members are
  located first, so a Jackson that renames one is skipped rather than
  half-flushed; mappers that are not Spring beans stay stale and no message
  claims otherwise. The append success line reports the flush only when at
  least one mapper was actually flushed.

- The plugin now writes `--sun-misc-unsafe-memory-access=allow` where it
  writes JVM options, gated on the real version of the JVM that will start.
  The flag is what keeps enum constant appends working on JDK 26, which
  refuses the needed Unsafe access by default (JEP 471), and it silences the
  JVM's deprecation warning on 24 and 25; but the option only exists from JDK
  23, and starting an older launcher with it fails outright, measured on
  SapMachine 21: `Unrecognized option: --sun-misc-unsafe-memory-access=allow
  ... Could not create the Java Virtual Machine`. So the gate is detection,
  not guessing. For SAP Commerce installs the platform's own JDK is read from
  the generated `tomcat/conf/wrapper.conf` (`wrapper.java.command`, verified
  on a 2211-jdk21 project to name the server's real JDK binary) and that
  JDK's `release` file; a project that has never generated its wrapper config
  yields no answer and no flag, because a missing flag costs a declining enum
  append with a message naming it while a wrong one costs the server refusing
  to boot. For run configurations the gate is the configuration's own JVM
  (`params.jdk`, which may be an alternate JRE rather than the project SDK),
  and the flag is added only from feature version 24, where it starts having
  an effect.

- JPA mapping refresh, opt-in via the agent argument `jpaRefresh=true`. When a
  reload adds or removes a persistent field on a mapped class, and the VM has
  enhanced class redefinition (JBR/DCEVM), and `hbm2ddl.auto` is update, create
  or create-drop, and the entity's owning factory bean is Spring's
  `AbstractEntityManagerFactoryBean`, Reclazz rebuilds the persistence unit
  instead of only warning: it invokes the bean's protected
  `createNativeEntityManagerFactory()` (which runs the schema action, so the
  column is created), swaps the bean's private `nativeEntityManagerFactory`
  field, and closes the old factory. Spring resolves every injected
  EntityManagerFactory, `@PersistenceContext` EntityManager and Spring Data
  repository through that field at call time, so nothing else needs recreating.

  Measured before implementing, on Spring Boot 3.3.4 with Hibernate 6.5.3 and
  file-based H2, JBR 25 with `-XX:+AllowEnhancedClassRedefinition`,
  `ddl-auto=update`, adding a field `currency` to an entity: the rebuild took
  95ms scripted externally and 65ms through the agent path, the CURRENCY
  column appeared in the table, the new metamodel carried the field, and a
  persist-flush-clear-find round trip through both an EntityManager and a
  repository injected before the swap returned the written value ("EUR" and
  "GBP", previously null). A second ordinary request after the swap worked;
  the rebuild ran on a request thread and on the agent's reloader thread
  without deadlock. The stated scope is printed with the result: open
  persistence contexts from before the rebuild are closed.

  Every non-qualifying case keeps the existing warning byte for byte. The only
  addition is the sentence "Start the agent with jpaRefresh=true to rebuild
  the persistence unit automatically.", appended only when the flag is the one
  thing missing, verified live: JBR without the flag prints the warning plus
  that sentence, and a stock JDK (SapMachine 21) with the flag prints the old
  warning unchanged, because a rebuilt metamodel cannot see a field the loaded
  class never physically gained.

- A save that changes the superclass AND has a method body needing the new
  parent no longer costs the whole save. Every other edited body reloads;
  the entangled method is pinned to the implementation it had, and the
  warning names it with its reason: "The method bodies in this save were
  applied, except c (calls yalnizB2, which only Base2 provides), which keeps
  the implementation it had." Anything a pin cannot cover still refuses the
  whole class, which was the previous behaviour and stays the floor: a field
  typed as the new superclass, a constructor body needing it, a missing
  old-superclass constructor, a save that also removes members, or a class
  with no cached previous version.

  The first attempt at this crashed a live application, and the fix is built
  around that measurement. Skipping the entangled method in the companion
  was not enough: the redefine payload still carried the method's NEW body,
  the transformer renamed it over `__reclazz$v0$...`, and the trampoline's
  fallback dispatched into a body calling a member only the new superclass
  provides (`UnsupportedOperationException: Reclazz: method not found:
  Service.yalnizB2`, on the application's own thread). So pinning now means
  the OLD body is what the loaded class ends up holding: the transformer
  keeps its last emitted bytecode per class (deflated; the cache size is
  logged when the watcher starts, and measured at 3 classes / 2 KB on the
  plain-Java replay and 55 classes / 298 KB deflated on the live SAP
  Commerce install watching all extensions, nowhere near the 30 MB that
  would have forced restricting it to developer source roots),
  the reverted payload is transformed up front, the pinned methods' previous
  `__reclazz$v0$` bodies and trampolines are spliced in from that cache, and
  the spliced payload is what `redefineClasses` applies. The transformer
  became idempotent for this (its own output, recognised by the injected
  `__reclazz$lookup` field, passes through untouched), which is what makes
  an already-transformed payload safe to redefine with the transformer still
  registered. The pinned methods' MutableCallSites are left alone.

  Replayed live on SapMachine 21 with the exact crash scenario (Service
  moves from Base1 to Base2, a() and b() edited, c() calls yalnizB2()):
  a() and b() served their new bodies on the next tick, c() kept serving its
  old one, the warning named c with its reason, zero exceptions in the
  application, and both a further pinned save and an ordinary reload of the
  same class afterwards worked. When the cache has no entry for the class,
  the reload refuses the whole class up front and nothing is half-applied,
  which the unit suite holds. The cache is warm for every class the
  transformer emitted, because the transform runs at class load and the
  cache is written on its way out; the states without an entry (loaded
  before attach, never loaded, or the validation fallback that loads a class
  untransformed) never reach the salvage with something to pin.

- Reclazz's injected members are now hidden at the root of reflection, inside
  the JDK, not only at rewritten call sites. The existing bridge rewrites
  `Class.getDeclaredMethods()` call sites, and that can never be complete:
  invoking `getDeclaredFields` through a `Method` object
  (`Class.class.getMethod("getDeclaredFields").invoke(target)`) reaches the JDK
  with no call site to rewrite, and a `__reclazz$` field surfacing in a
  framework scan through such a route was observed once in the field and never
  reproduced. The agent now registers every `__reclazz$` member with the JDK's
  own filter (`jdk.internal.reflect.Reflection.registerFieldsToFilter` and
  `registerMethodsToFilter`, the mechanism that hides `IMPL_LOOKUP` from scans
  of `MethodHandles.Lookup`), reached via `Instrumentation.redefineModule`.

  Measured on SapMachine 21 and JBR 25, in a live app under the agent: before
  the first reload, meta-reflection listed `__reclazz$ext`, `__reclazz$lookup`
  and two `__reclazz$v0$` method copies; after it, direct scans, meta-reflection
  and `getDeclaredField` by name all answer as if the members did not exist,
  and `Method.invoke` on real methods is untouched. Registration happens per
  class on its first reload (the JDK accepts exactly one registration per
  class, so it carries every name at once), and everything the engine itself
  reads reflectively, the class's lookup and the ext field, is captured before
  the filter goes on; the second and later reloads were verified live on both
  JVMs. A JVM that refuses any part of the probe gets one info line and exactly
  the previous behaviour. Scans that a framework cached before the class's
  first reload are unaffected either way, which is why the call-site bridge
  stays.

### Evaluated and deferred

- Throwing stubs for removed methods: the idea was to re-point a removed
  method's call sites at a handle that throws `NoSuchMethodError` naming the
  method, so stale calls fail loudly instead of running code the source no
  longer contains. The named kill condition was framework machinery invoking
  every visible method during normal operation, and it was measured to happen:
  on Spring Boot 3.3.4 (Java 21, Jackson via spring-boot-starter-web), a DTO
  whose getter was removed by a reload turned its JSON endpoint into
  `HTTP 500` with `HttpMessageNotWritableException: Could not write JSON:
  Reclazz: getRemovable was removed by a reload`. Jackson's serializer is
  built from a member scan and invokes every getter it found on every
  serialization, and neither route avoids the stub: the cached accessor
  dispatches through the same trampoline call site a re-scan would find,
  because the removed member stays in the loaded class (no JVM can take it
  out). So a throwing target fires on traffic that never names the removed
  method, and shipping it default-on would turn a getter removal into a broken
  endpoint. A CGLIB-proxied `@Cacheable` bean losing an unrelated method kept
  answering normally on its other methods, so proxies alone would not have
  killed it; Jackson did. Opt-in was ruled out in the proposal itself as
  halving the value, so nothing shipped.

  The same measurement recorded what happens today, and it is two different
  behaviours. When the class has no members added by an earlier reload, the
  constructor-body redefinition succeeds and carries the
  `AddedMemberStripper` stub for the removed method, whose renamed copy
  replaces the old implementation, so existing callers already meet
  `UnsupportedOperationException: Reclazz: <name> was removed by a reload`
  (measured on both the direct caller and the Jackson path above). When the
  class does carry added members, that redefinition is refused and existing
  callers really do keep the previous implementation, which is the only case
  the reloader's warning sentence describes correctly today.

### Changed

- The enum append success message now tells the measured truth about Java 21
  switches. What each switch shape does with an appended constant was measured
  on stock JDK 21 and JetBrains Runtime 25, with and without enhanced
  redefinition, all three agreeing: a classic switch with a written default
  takes it (the grown `$SwitchMap` slot is javac's "not one of my cases"); an
  exhaustive switch with no written default throws `MatchException` on the new
  constant, because javac plants a throwing default in every switch it proved
  exhaustive (`IncompatibleClassChangeError` when compiled at source levels
  before 21); and a pattern switch with a guard or type pattern, which
  compiles to `invokedynamic SwitchBootstraps.enumSwitch` and not to a table,
  needs no help at all: a call site linked before the append matched the new
  constant with its total type pattern and threw `MatchException` where
  exhaustive, never a wrong branch and never a stale answer. The suspected
  correctness bug, an indy call site caching a mapping sized to the old
  constant universe and misrouting the new one, does not exist on any tested
  JDK; `PatternSwitchAppendTest` pins all of this so a JDK that starts
  caching will be caught. The old message claimed switches "take their
  default branch instead of throwing", which is wrong for the exhaustive
  shape, where the default branch IS a throw.

- The log says which SAP Commerce line the server is: `Platform version:
  2211-jdk21.8`, read from the platform's own `build.number`. Two lines are in
  the field at once, because SAP moved the platform to Java 21 and Spring 6.2 in
  the 2211-jdk21 update and blocks new Java 17 builds after 31 August 2026, so
  every installation is migrating or has just migrated. It was the first
  question worth asking about any report, and the answer was two directories
  away from a log that said only "Platform: Hybris (auto-detected)".

- The verified matrix now says what it was actually measured on. The 20
  integration scenarios have been running against 2211-jdk21.8 with Spring
  6.2.12 on Java 21, and the table said "2211", which now reads as the Java 17
  line that is being retired. Spring 6.2 is listed too: it was covered through
  the SAP Commerce run and only Spring Boot's 5.3 and 6.1 were claimed.

### Fixed

- The removed-method warning now says what actually happens to existing
  callers, which was measured to be three cases where one sentence claimed
  one. The old warning always said "existing callers will continue using the
  previous implementation". When the constructor-body redefinition lands, it
  installs the remover's stub over the removed method's renamed fallback, so
  a call site never retargeted to a companion throws
  `UnsupportedOperationException: Reclazz: <name> was removed by a reload`
  (measured on Spring Boot 3.3.4: a removed getter turned its JSON endpoint
  into HTTP 500 while the message said old code was still serving). A site
  that WAS retargeted by an earlier reload keeps dispatching to that
  companion body and never reaches the stub (measured on the SAP Commerce
  integration run: reload the method, then remove it, and callers keep the
  reloaded body). And when the redefinition is refused, every caller keeps
  what it had.

  The discriminator is knowable at warn time and is used: the warning moved
  to the redefinition site, where its outcome is in hand, and consults the
  dispatch table for whether each removed method's site was ever retargeted.
  Methods whose callers will throw are reported with "restore the method or
  restart"; methods whose callers keep serving are reported with "existing
  callers keep the previous implementation until restart". Writing the test
  for the refused case measured one more thing worth recording: the
  transformer rewrites the class's metadata record during every
  redefinition, so a healthy record re-detects an earlier-added member as
  added and the payload re-strips it, and the redefinition lands again; the
  refusal arises when the record still lists the added member as existing.
  Dispatch behaviour is unchanged, message only.

- The ImpEx integration scenario stopped failing at random. Importing an ImpEx
  is not synchronous with saving one: the agent hands the file to the platform's
  own cronjob, so "the agent reported the import" and "the data is readable" are
  different moments. The test slept for a fixed delay and read once, and on one
  run it reported `Expected 'Reclazz Test Title v2', got 'Reclazz Test Title v1'`
  while the log showed the import twice, five seconds apart, with the read
  landing between them.

  It now waits for the agent's own import event, the signal the rest of the
  suite is built on, and then polls the value instead of guessing when it will
  appear. Three consecutive runs after the change: 20/20 each, with the scenario
  taking 3.5 to 3.7 seconds, which is no slower than the sleep it replaced.

- Attaching to a JVM that was started with dynamic agent loading disabled now
  says what to do about it. JEP 451 warns from Java 21 and will disallow the
  attach in a future release; a server started with
  `-XX:-EnableDynamicAgentLoading` refuses today. The JVM's own sentence names
  the flag and stops there, and it was reaching the developer wrapped in two
  that added nothing: "Failed to attach to PID 1234: Failed to load agent
  library: Dynamic agent loading is not enabled...". The message now says where
  the flag goes, which differs by platform, and that a server started with the
  agent already on its command line never meets this at all.

- A JVM that refuses `sun.misc.Unsafe` no longer costs the whole class reload.
  JEP 471 is phasing those methods out, and JDK 26 refuses them by default;
  appending an enum constant needs them, because writing a final field has no
  supported alternative (a VarHandle from `unreflectVarHandle` answers
  `UnsupportedOperationException` for a final field, with or without
  `setAccessible`, instance and static alike).

  Tested by bringing JDK 26's behaviour forward with
  `--sun-misc-unsafe-memory-access=deny`. Before: `Hot-swap failed for Status:
  Structural reload failed: staticFieldBase`, with a method body changed in the
  same save lost along with the enum, and a JDK internal name presented as the
  reason. Now the enum declines with a sentence naming the policy and the flag
  that reproduces it, and everything else in the save reloads.

  On Java 24 and 25 the JVM prints its own deprecation warning for this, ending
  with a request to report it to the maintainers of a Reclazz class. Reclazz now
  answers that in the same breath, so nobody has to open an issue about a
  deliberate choice.

  Both messages name `--sun-misc-unsafe-memory-access=allow`, which was measured
  rather than assumed: it silences the warning on Java 25 and is what keeps the
  feature working on JDK 26. The claim in the comparison table now says JDK 17
  to 25 rather than any JDK 17+, with that flag as the way past it.


## [1.0.25] - 2026-08-18

### Changed

- The agent no longer ships Byte Buddy, and is 0.7 MB instead of 10.2 MB. It was
  there to emit two probe classes that ask the JVM whether it accepts a
  structural redefinition, and in one file only for the copy of ASM bundled
  inside it. Both are ASM's job and ASM was already shipped. The agent is loaded
  into the JVM that runs your application, so every library inside it is a
  library inside that application: this was 25 MB of classes to save sixty
  lines. Byte Buddy remains a test dependency, where it never reaches anyone.

- The file watcher starts when the application reports ready instead of after a
  fixed thirty seconds. Spring's context refresh is already instrumented, so the
  moment a context finishes is known exactly; the configured delay stays as the
  cap, because a plain Java application has no context to refresh and an
  application that fails to start must not leave the watcher waiting. Measured:
  a Spring Boot application starts watching after 4.2 seconds instead of 30, and
  SAP Commerce after 4.6, with all 20 integration scenarios still passing.

### Fixed

- A class the JVM has never loaded is no longer reported as having been "loaded
  before Reclazz could instrument it". Missing metadata has two causes and only
  one of them is worth telling anyone: a class that really did miss the
  load-time transform. The other is an ordinary new file, and the message was
  appearing every time one was compiled beside a changed one, describing a
  startup-ordering problem the reader did not have.

- The integration suite stopped under-reporting what the product does. Two
  scenarios checked an old endpoint and noted that a newly added one was
  "invisible (documented)", which stopped being true when handler methods added
  by a reload started being mapped. Both now ask for the new endpoint, and both
  pass on SAP Commerce with a stock JDK.

### Known limitation

- A documented limitation turned out not to exist, and two mechanisms for the
  remaining reflection gap were proven in spikes; both are recorded here so the
  next step starts from measurements.

  The companion generator's javadoc claimed intra-class calls were not
  retargeted, so a method changed together with its callee would keep invoking
  the old callee. Measured live across two generations (change A and B, then
  only B, for private and protected callees): A observes the newest B every
  time. The claim predates the trampoline transform and is now replaced by the
  measured behaviour.

  For hiding injected members from every reflective path at once: the JDK's own
  root-level filter (`jdk.internal.reflect.Reflection.registerFieldsToFilter`),
  reached via `Instrumentation.redefineModule` opening
  `jdk.internal.reflect`, was measured working on JDK 21 and 25. It hides
  `__reclazz$` members from direct scans, from meta-reflection (which no
  call-site rewrite can ever catch), and from `getDeclaredField` by name, with
  no `sun.misc.Unsafe` involved. This is the planned replacement for chasing
  reflective call sites one at a time, and the JDK-26-safe route for forged
  members.


- Adding an interface on a stock JDK stays refused, and a subclass-based
  workaround was evaluated, measured and deferred. The mechanics work: a
  generated nestmate subclass of the loaded class carries the new interface,
  and an instance of it answers `instanceof` true for both the original class
  and the interface, with inherited and default methods working. The honest
  blockers, for whoever picks this up, are not the ones that first come to
  mind. Split identity (`getClass()` returning a generated name, a
  `getClass()`-based `equals` treating old and new instances as foreign) is
  exactly what every CGLIB-proxied Spring bean already exhibits, so for
  container-managed instances it is a tolerated cost, not a new one. The real
  costs are: interface methods newly implemented on the class live in the
  companion engine behind invokedynamic, so the subclass would need bridge
  stubs kept consistent across later reloads; serialization and session
  persistence would see a class name that does not exist after a restart; final
  classes and constructor wiring need Spring's own bean instantiation rather
  than `newInstance()`. A viable shape exists (opt-in, Spring-beans-only,
  instantiated through the BeanFactory, refused for classes the application
  also instantiates via `new`), and the first real user request for this is the
  trigger to build it rather than re-deriving it from zero.


- Appending an enum constant costs about 1.5 seconds on a large server, and it
  is worth knowing why before anyone tries to shave it. Measured on SAP
  Commerce: the reload that appends takes 1673ms, the same enum reloaded again
  with nothing to append takes 135ms, and an ordinary class takes 334ms. The
  time is the switch-table scan, which asks every loaded class whether it holds
  a table for this enum, and a Hybris JVM has tens of thousands of them.

  Narrowing the scan by classloader visibility was tried and measured at
  1673ms, which is no gain: on that platform almost every class can see the
  enum, so the filter excludes nothing. It was reverted rather than kept as an
  unmeasured improvement. The cost is paid once per enum, since a constant that
  is already live is not appended again, and it buys a server restart of four
  to ten minutes.


- A superclass change still costs the whole class when one method body needs
  what the class is losing. The other methods edited in the same save are
  refused with it.

  Applying them per method was tried and reverted, because the first attempt
  crashed a running application, and the mechanics are worth recording so the
  next attempt does not rediscover them. A transformed class keeps its original
  body under a renamed method, `__reclazz$v0$<name>$<hash>`, and the method that
  keeps the original name becomes a pure trampoline: `aload_0`,
  `invokedynamic`, `areturn`. The real body is either in the companion or in
  that renamed method.

  So leaving an entangled method out of the companion is not enough. The
  redefinition payload still carries its new body, the transform renames that
  body over the old one, dispatch falls back to it, and the call it could not
  resolve throws `UnsupportedOperationException: method not found` in
  application code.

  Doing it properly needs the old body carried into the payload: capture the
  loaded class's bytes before redefining (a retransform-capture, which re-enters
  our own transformer, or storing last-known-good bytes, which costs memory per
  class), lift `__reclazz$v0$<name>$<hash>` out of them, and splice it in. The
  captured body is already transformed, with invokedynamic call sites of its
  own, so the second pass over it has to be measured rather than assumed.


## [1.0.24] - 2026-08-18

### Added

- An added static field now gets the value its initialiser would have given it,
  instead of reading as null or 0 until a restart.

  Two routes, because javac uses two. A compile-time constant such as
  `static final int MAX = 50` never reaches `<clinit>` at all: it is stored in a
  `ConstantValue` attribute on the field, so the value is read straight off it
  and no code runs. Everything else is a run of instructions in `<clinit>` that
  starts with an empty operand stack and ends at that field's `PUTSTATIC`, and
  that run is lifted out and executed on its own.

  What this deliberately does not do is re-run `<clinit>`. Doing that would
  reset every other static the application has been mutating since startup, and
  re-fire whatever side effects the static block has. So an initialiser that
  cannot be separated from the rest of the block, because it shares a computed
  value with another field, because it branches, or because it sits inside a
  try/catch, is left alone: the field reads as the type default exactly as
  before, and the log now names the field and says which of those it was.

  The field is initialised once. A field a reload adds never enters the loaded
  class's schema, since the JVM cannot add one, so every later reload of that
  class diffs it as added again; initialising it each time would have emptied a
  cache because somebody edited a method body in the same class.

- An enum constant added on the end is now applied to the running JVM, on any
  JDK 17 or newer. `values()` and `valueOf` return it, switches compiled before
  it existed take their default branch instead of throwing, and the EnumMap and
  EnumSet instances that were built before the reload accept it.

  Three pieces. The constant is built and the enum's private array grown, which
  the class initialiser would have done and will not do twice. The synthetic
  tables javac generates for a switch over an enum from another file are grown,
  with the new slot left at zero, which is the value javac uses for "not one of
  my cases". And EnumMap and EnumSet, which size their storage from the enum
  when they are built, are taught to notice that it grew: their instances cannot
  be found on the heap, so a repair call is injected at the head of their
  instance methods, installed on the first append and never before.

  Only an append. Inserting, removing or reordering renumbers every constant
  after the change, and nothing in memory or in a database survives that, so it
  is refused with its own message. Reordering counts even though no name moved,
  which a comparison by set would have called no change at all.

  Every JDK internal this reaches for is located and identified before anything
  is written, so a release that moves one makes Reclazz decline and print what
  it printed before this existed. It does not half-apply.

### Fixed

- Adding an interface to a class was reported as a successful reload and did not
  happen. The analyser never compared interfaces at all, so the reload took the
  ordinary path: the method bodies landed, the redefinition that would have
  carried the interface was refused by the JVM, and the refusal was swallowed by
  the handler written for a different and harmless case. What reached the
  developer was `Reloaded Service (19ms)` and a class whose `instanceof` was
  false against an interface their source clearly declares, which sends them
  looking for a bug in their own code.

  Interfaces are now compared. On a stock JDK the change is refused by the JVM,
  and Reclazz names the interface, says an `instanceof` or a cast still answers
  the old way, and records it in the restart ledger; everything else in the class
  still reloads. On JetBrains Runtime or DCEVM with
  `-XX:+AllowEnhancedClassRedefinition` the change is applied for real, including
  to objects created before the reload, and there is nothing to warn about.

- A field added to a JPA entity reloaded onto the class and was never persisted,
  and the log said only "Reloaded". Hibernate builds its metamodel and entity
  persisters once, when the SessionFactory is created, so the mapping keeps the
  old shape however well the class redefinition went. Measured on Spring Boot 3.3
  with Hibernate, on JetBrains Runtime with enhanced class redefinition, which is
  the most capable configuration this has: the class gained the field, the
  metamodel did not, the table had no column, and a value written and read back
  through a cleared persistence context came back null.

  Reclazz now names the field and says it is neither saved nor loaded, and reads
  the running persistence unit to say what to do about it, because one answer
  fits only one of the three configurations. At `ddl-auto=update` a restart is
  the whole fix and Hibernate writes the column itself. At `validate` a restart
  is the wrong move: the application refuses to start while a mapped column is
  missing, so the column has to come first. At `none`, or where the setting
  cannot be read, it says restart and add the column, which is the instruction
  that is always safe. It does not try to refresh the
  mapping: that means rebuilding the SessionFactory, which takes the persistence
  context, the open transactions and every repository proxy with it, and it would
  still leave the missing column, so on a project with `ddl-auto` at validate or
  none it would turn a working application into one that fails its next query.
  The same trade already applies on SAP Commerce, where a new items.xml attribute
  reloads the model class and prints a reminder to run Update Running System
  rather than writing to the database.

- Adding an enum value was reported as a plain success on JetBrains Runtime. The
  warning lived inside the companion engine, and that engine is switched off on
  a VM with enhanced class redefinition: the JVM accepted the redefinition,
  added the field, left it null, and the log read `Reloaded Status (61ms)` while
  `values()` returned the old set and `valueOf` still threw. The sentence now
  lives in one place that both engines use, and a removed value is reported the
  same way.

- A structural change that carried its own explanation printed the word `null`
  underneath it. Both places that print the extra advice did so without checking
  whether there was any.

### Changed

- A changed superclass no longer costs the rest of the save. The hierarchy is
  still not applied, because no JVM applies one to a loaded class, but the
  method bodies edited in the same file now are: the payload is rewritten to
  keep the superclass the JVM already has, and the `super()` call, which is the
  only place javac names the new one, is pointed back with it. An inherited call
  compiles to an invocation on the class itself, so it resolves against the
  hierarchy the class keeps.

  It refuses rather than half-applies when a body genuinely needs what it is
  losing: a call or field that only the new superclass provides, a cast to it, a
  member typed as it, or an old superclass without a matching constructor. The
  message names the method and the member that blocked it.

- A changed superclass now says why it cannot work rather than only that it
  cannot. It was measured on three VMs before the wording was written: a stock
  JDK, JetBrains Runtime, and JetBrains Runtime with enhanced class redefinition
  all reject it, because every existing object already has the old layout and
  identity. Changing the superclass and changing the interface list used to share
  one verdict, and they have different answers.

## [1.0.23] - 2026-08-17

### Added

- The release carries `LICENSE`, `NOTICE` and a new `THIRD-PARTY.md` beside the
  code, listing everything shipped inside the agent, its version, its licence
  and why it is there. Byte Buddy is Apache 2.0 and ASM is BSD 3-Clause, and
  both ask for their terms to travel with the distribution; keeping them in the
  repository served everyone except the person reviewing a downloaded zip on
  behalf of an employer.

### Fixed

- A class file built for a newer Java than the server runs is refused with an
  explanation instead of being reported as reloaded. The JVM cannot load it, and
  that rejection used to arrive as a passing warning in the middle of a reload
  that then announced success and a refreshed bean, while the application went
  on running the old code. A module left on a newer toolchain than the server is
  an ordinary mismatch, and a green log next to unchanged behaviour is the one
  failure that stops a developer from looking. The message now names both
  versions and which end to change. Found on a Spring Boot 2.7 application while
  verifying JDK 17.

## [1.0.22] - 2026-08-16

### Fixed

- The agent no longer keeps a class, or the classloader that defined it, alive
  after the application has dropped it. Three places held per-class state in a
  static map: the dispatch table (a WeakHashMap, which did nothing, because
  every value holds a call-site handle bound to the class that is its key), the
  field store's cache of the added-field array, and the reflection bridge's
  record of added members, whose forged Method objects hold their declaring
  class. All three now live on the class itself, through a ClassValue, so they
  die with it. Nothing shows in a reload loop, where the class stays loaded
  anyway; the cost lands on a discarded classloader, a redeployed web
  application being the usual one, whose entire loader was being held for the
  life of the server.

### Changed

- On SAP Commerce only the platform's own configuration files are snapshotted
  at startup, as before. Snapshotting every `.properties` file in an
  installation measured 434 files and 1.6 MB of retained strings for property
  paths that refuse them anyway. Outside SAP Commerce every file is still
  snapshotted, which is what makes a changed log level or a rebindable property
  distinguishable from the rest of an `application.properties`.

## [1.0.21] - 2026-08-16

### Added

- Spring Boot applies a changed property to the beans that read it. The values
  go into the running Environment and every `@ConfigurationProperties` bean
  whose prefix the save touched is put back through the binding post-processor,
  the same one that filled it in at startup, so a timeout or a feature flag
  takes effect on the next call. A bean bound through its constructor, a record
  being the common shape, cannot be rebound: Spring accepts the request and
  does nothing, so Reclazz names it instead of reporting it as applied, and it
  goes on the restart list. Verified on a Spring Boot 3.3 application.
- **Tools > What Still Needs a Restart?** lists what this session did that a
  restart would still change: a static field that reads null, an enum that
  gained a value, a superclass change, a Spring bean the XML reload could not
  apply, a property the platform refused, a handler method the mapping scan
  cannot see. Each is already announced as it happens, once, in a log that
  keeps moving, and an hour later there is no way back to it. The two usual
  endings are a long debugging session or a restart out of superstition, and
  both are what this tool exists to avoid. When there is nothing, it says so.
- A diagnosis for the question the log cannot answer: **Tools > Why Didn't My
  Class Reload?**, also on the editor's right-click menu. Nearly every time it
  is asked, no reload was ever attempted, and none of the reasons produce an
  error: the build did not reach the class, it is built somewhere Reclazz does
  not watch, the rebuilt bytes came out identical, the JVM has not loaded the
  class yet, or it was loaded before the agent arrived. The agent reports which
  of those it is, with the file it found, when that file was last built, and
  the outcome of the last attempt, into the reload log.
- Log levels take effect without a restart. On SAP Commerce they live in the
  property files rather than in a `log4j2.xml`, as a
  `log4j2.logger.<key>.name` and `.level` pair, and saving one now sets the
  level on the running Log4j2 context. Spring Boot's `logging.level.<logger>`
  works the same way, and a saved `logback.xml` or `log4j2.xml` is applied to
  the running context in full, appenders and patterns included. Only the
  loggers a save touched are set, so a level raised from the console is not
  pushed back by an unrelated property edit. Verified on a live SAP Commerce
  2211 server (`DEBUG` to `ERROR` and back, read from inside the JVM, with the
  untouched loggers unchanged) and on a Spring Boot application for the Logback
  path.

## [1.0.20] - 2026-08-16

### Added

- A Spring MVC endpoint added by a reload answers without a restart, on a stock
  JDK as well. The mapping scan reads the controller through reflection and a
  method the reload added is not there to be read, so Reclazz hands the scan a
  small class carrying a copy of that method, with its annotations and the
  controller's own, delegating to the implementation. Writing a new endpoint is
  the moment a reload is worth the most, and it was the one thing that still
  needed a restart. Verified on a Spring Boot application and on a live SAP
  Commerce server: add an endpoint, change its body, add a second one with a
  path variable, remove one; each takes effect on the next request, and the
  endpoints the reload did not touch keep serving.

## [1.0.19] - 2026-08-16

### Fixed

- Attaching to a running JVM can actually reload something. Classes loaded
  before the agent arrived have none of its infrastructure, and a redefinition
  cannot introduce it, so every swap failed with "attempted to change the
  schema (add/remove fields)": the entire point of attaching. Those classes are
  now left as they are and reload their method bodies, which is what attaching
  can offer.

- A Spring Boot application starts again on a JVM with native enhanced
  redefinition. The agent's bootstrap classes were installed only when its own
  companion engine was on, and that engine is switched off on such a JVM
  because the JVM does the work itself, so a rewritten template engine
  constructor called a class that was not there. An application using Thymeleaf
  failed to start at all, on the runtime this project recommends for structural
  work.
- A handler method added to a controller is mapped without a restart on a JVM
  with enhanced redefinition. The method really is on the class there, but
  nothing re-scanned the mappings, because the agent marks nothing structural
  in a mode where the JVM applies the change. Measured on JBR 25: the new
  endpoint went from 404 to serving.

## [1.0.18] - 2026-08-16

### Fixed

- A constructor change reaches newly created objects even when the same reload
  removed something. A redefinition has to hand the JVM exactly the members the
  loaded class has, and the payload was stripped of what the reload added but
  still missing what it removed, so the JVM refused all of it and the new
  constructor body went with it. Measured on a Spring Boot application: a field
  added by the reload and assigned in the constructor came back null on every
  new bean. Removed members are now handed back as stubs, which nothing calls:
  existing callers keep dispatching to the implementation they were linked to,
  as they already did.
- An added field is one field whichever way its class is spelled. The companion
  writes through the internal name and a constructor's assignment through the
  binary one, and they were separate keys, so the value was stored under one and
  read under the other.

- A call to a bean reaches its Spring proxy again. Reclazz dispatches calls to
  a renamed copy of the method so a reload can swap it, and that copy lives on
  the class being reloaded, so a receiver whose class overrides the method,
  which is exactly what a CGLIB proxy is, was walked straight past. Measured on
  a Spring Boot application: a `@Cacheable` method ran on every call with the
  agent attached and once in total without it, and an around advice never ran.
  The same applies to `@Transactional`, `@Async` and method security. The
  receiver now decides, at the first call and again after every reload.

## [1.0.17] - 2026-08-16

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
