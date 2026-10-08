# Core JVM feature evidence

This is the first checkpoint of the all-advertised-features evidence program. It classifies the listed core promises; it does not certify every feature, framework, program, JDK or operating system. Other evidence packages remain separate.

Records distinguish **PROVEN_WITH_SCOPE** (observed watcher behavior on both named lanes), **PARTIAL** (component observations only), **EXPECTED** (observed limitation/refusal), **FAILED** (a capability fails despite a passing characterization test) and **UNVERIFIED** (missing run/environment). Independent Claude review is pending until the package audit is archived.

The available lanes are stock SapMachine17/21 on macOS arm64. Other provider/OS/enhanced cells remain unverified. Deferred field-initializer concurrency stays deferred. Raw logs/XML are retained locally at the paths in the JSON; the JSON contains method results and provenance, not a substitute claim that every green receipt establishes behavior.

Reproduce with the repository tasks `:agent:unitTest --rerun` and `:agent:e2eTest --rerun` on each named runtime, then check `P1RegistryCompletenessTest`. The check rejects omitted cells, stale public surfaces, nonexistent test methods, component-as-watcher promotion and unrun-as-proven promotion. It does not automatically decide the meaning of arbitrary prose.

The [machine record](feature-evidence/p1-core.json) includes actual execution commands, runtime/agent hashes, test-source hashes, result counts and raw evidence locations. This document’s digest lives in that separate record to avoid a self-referential hash.

### P1-BODY-SUPER

**Verdict:** PROVEN_WITH_SCOPE.

Edited overrides continue dispatching super calls to the current original parent.

**Shipped surfaces:** `README.md:76` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SuperCallAfterChildReloadTest#anEditedOverrideStillCallsTheParentItExtends` (watcher): Held Derived answers d1/b1, d2/b1, d2/b2, d3/b2; all printed values have two halves.

**Observable:** Held Derived answers d1/b1, d2/b1, d2/b2, d3/b2; all printed values have two halves.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-BODY-VIRTUAL

**Verdict:** PROVEN_WITH_SCOPE.

A reloaded parent still reaches the existing child override without restart.

**Shipped surfaces:** `README.md:76` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SelfInvocationAfterParentReloadTest#aReloadedParentStillReachesTheChildsOverrideFromItsOwnBody` (watcher): Held Derived answers b1:dh then b2:dh; startup PID/nonce unchanged and emitted once.

**Observable:** Held Derived answers b1:dh then b2:dh; startup PID/nonce unchanged and emitted once.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-FIELD-OLD

**Verdict:** PROVEN_WITH_SCOPE.

Isolated added instance initializers give existing objects their own first-read values.

**Shipped surfaces:** `README.md:78` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `docs/usage.md:3105` (SHA-256 `a1aa85b1016a7d36938eab683db908c7efdbe4fe543d96909e455d2a7207398d`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedFieldInitialiserTest#anAddedFieldReadsItsInitialiserValueOnAnObjectThatAlreadyExisted` (watcher): New list sizes1 then2, integer retries3 and computed label HOLDER!; first-read explanation and no initializer warning.

**Observable:** New list sizes1 then2, integer retries3 and computed label HOLDER!; first-read explanation and no initializer warning.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-FIELD-PARAM-REFUSAL

**Verdict:** EXPECTED.

Historical constructor arguments are not reconstructed for an added field.

**Shipped surfaces:** `README.md:78` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `docs/usage.md:3105` (SHA-256 `a1aa85b1016a7d36938eab683db908c7efdbe4fe543d96909e455d2a7207398d`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedFieldInitialiserTest#aFieldSetFromAConstructorArgumentIsStillNamed` (watcher): Held name x survives; added upper stays null and warning names upper/constructor parameter.

**Observable:** Held name x survives; added upper stays null and warning names upper/constructor parameter.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-FIELD-FAILURE

**Verdict:** PROVEN_WITH_SCOPE.

An initializer failure is isolated and may retry after a later initializer generation.

**Shipped surfaces:** `README.md:78` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `docs/usage.md:3105` (SHA-256 `a1aa85b1016a7d36938eab683db908c7efdbe4fe543d96909e455d2a7207398d`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedFieldInitialiserTest#failureIsIsolatedAndNextReloadRepairsOnlyTheFailedObject` (watcher): null:1|healthy:1 becomes recovered:2|healthy:1; successful neighbor is not reinitialized.

**Observable:** null:1|healthy:1 becomes recovered:2|healthy:1; successful neighbor is not reinitialized.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-FIELD-CONDITIONAL

**Verdict:** PROVEN_WITH_SCOPE.

An isolated conditional uses each existing receiver state.

**Shipped surfaces:** `README.md:78` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `docs/usage.md:3105` (SHA-256 `a1aa85b1016a7d36938eab683db908c7efdbe4fe543d96909e455d2a7207398d`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ConditionalInstanceInitialiserReloadTest#existingObjectsUseTheirStateWithoutRerunningConstructors` (watcher): Four held objects produce on1/true, off1/false and explicit-null outcomes with per-object branch counts.

**Observable:** Four held objects produce on1/true, off1/false and explicit-null outcomes with per-object branch counts.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-FIELD-NEW-ROUTES

**Verdict:** PROVEN_WITH_SCOPE.

Equivalent and delegated constructors preserve old/new initialization order.

**Shipped surfaces:** `README.md:78` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `docs/usage.md:3105` (SHA-256 `a1aa85b1016a7d36938eab683db908c7efdbe4fe543d96909e455d2a7207398d`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ConditionalInstanceInitialiserReloadTest#equivalentConstructorRoutesPreserveOldAndNewInstancesAcrossReloads` (watcher): Old constructor count4 stays fixed; new three constructor routes give off3/false with total7 constructors/2 delegations and one initializer call each.

**Observable:** Old constructor count4 stays fixed; new three constructor routes give off3/false with total7 constructors/2 delegations and one initializer call each.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-FIELD-UNREAD-LATER

**Verdict:** PROVEN_WITH_SCOPE.

A previously unread added field uses the current initializer generation.

**Shipped surfaces:** `README.md:78` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `docs/usage.md:3105` (SHA-256 `a1aa85b1016a7d36938eab683db908c7efdbe4fe543d96909e455d2a7207398d`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ConditionalInstanceInitialiserReloadTest#existingObjectsUseTheirStateWithoutRerunningConstructors` (watcher): Unread object uses on2/true after the second save, while earlier values remain unchanged.

**Observable:** Unread object uses on2/true after the second save, while earlier values remain unchanged.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-FIELD-NULL-KEEP

**Verdict:** PROVEN_WITH_SCOPE.

Application values, including an explicit null write before read, survive later saves.

**Shipped surfaces:** `README.md:78` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `docs/usage.md:3105` (SHA-256 `a1aa85b1016a7d36938eab683db908c7efdbe4fe543d96909e455d2a7207398d`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ConditionalInstanceInitialiserReloadTest#existingObjectsUseTheirStateWithoutRerunningConstructors` (watcher): user/true and explicit null survive repeated saves; existing application field77 remains77.

**Observable:** user/true and explicit null survive repeated saves; existing application field77 remains77.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-FIELD-SIDE-EFFECT-BOUNDS

**Verdict:** PROVEN_WITH_SCOPE.

Isolated switch arms and unrelated initializer handlers do not replay old constructors/blocks.

**Shipped surfaces:** `README.md:78` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `docs/usage.md:3105` (SHA-256 `a1aa85b1016a7d36938eab683db908c7efdbe4fe543d96909e455d2a7207398d`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ConditionalInstanceInitialiserReloadTest#switchAndUnrelatedHandlersAcrossConstructorRoutes` (watcher): Constructor count4 across edits, unrelated block count8 in each of four stages; selected arm counts and new constructor ordering verified.

**Observable:** Constructor count4 across edits, unrelated block count8 in each of four stages; selected arm counts and new constructor ordering verified.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-STATIC-CONDITIONAL

**Verdict:** PROVEN_WITH_SCOPE.

Only selected isolated conditional static initializer arms run.

**Shipped surfaces:** `README.md:79` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `docs/usage.md:3072` (SHA-256 `a1aa85b1016a7d36938eab683db908c7efdbe4fe543d96909e455d2a7207398d`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ConditionalStaticInitialiserReloadTest#conditionalFieldsInitializeOnceWithoutReplayingOldStaticCode` (watcher): Selected condition/left/right counters progress 1:1:0 then2:1:1 across three saves.

**Observable:** Selected condition/left/right counters progress 1:1:0 then2:1:1 across three saves.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-STATIC-NULL-KEEP

**Verdict:** PROVEN_WITH_SCOPE.

An initialized null and an application-written static value remain stored.

**Shipped surfaces:** `README.md:79` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `docs/usage.md:3072` (SHA-256 `a1aa85b1016a7d36938eab683db908c7efdbe4fe543d96909e455d2a7207398d`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ConditionalStaticInitialiserReloadTest#conditionalFieldsInitializeOnceWithoutReplayingOldStaticCode` (watcher): NULLABLE remains null and CHOICE=user survives later saves.

**Observable:** NULLABLE remains null and CHOICE=user survives later saves.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-STATIC-LATER-KEEP

**Verdict:** PROVEN_WITH_SCOPE.

A newly added later static field uses the current condition without resetting older fields.

**Shipped surfaces:** `README.md:79` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `docs/usage.md:3072` (SHA-256 `a1aa85b1016a7d36938eab683db908c7efdbe4fe543d96909e455d2a7207398d`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ConditionalStaticInitialiserReloadTest#conditionalFieldsInitializeOnceWithoutReplayingOldStaticCode` (watcher): LATER=right after the flag changes; existing77 remains77 and earlier CHOICE=user.

**Observable:** LATER=right after the flag changes; existing77 remains77 and earlier CHOICE=user.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-STATIC-BLOCK-NO-REPLAY

**Verdict:** PROVEN_WITH_SCOPE.

Unrelated static blocks are not replayed for isolated static initializers.

**Shipped surfaces:** `README.md:79` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `docs/usage.md:3072` (SHA-256 `a1aa85b1016a7d36938eab683db908c7efdbe4fe543d96909e455d2a7207398d`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ConditionalStaticInitialiserReloadTest#conditionalFieldsInitializeOnceWithoutReplayingOldStaticCode` (watcher): Original block count1 or2 stays fixed across three edits, including isolated switch and unrelated handler fixture.; `com.onurkat.reclazz.e2e.ConditionalStaticInitialiserReloadTest#switchAndUnrelatedHandlersDoNotReplayStaticBlocks` (watcher): Original block count1 or2 stays fixed across three edits, including isolated switch and unrelated handler fixture.

**Observable:** Original block count1 or2 stays fixed across three edits, including isolated switch and unrelated handler fixture.; Original block count1 or2 stays fixed across three edits, including isolated switch and unrelated handler fixture.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-METHOD-ADD

**Verdict:** PROVEN_WITH_SCOPE.

An updated caller can invoke an added method on the existing receiver.

**Shipped surfaces:** `README.md:77` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `src/main/resources/META-INF/plugin.xml:68` (SHA-256 `fd2cae03def4ceed4b5cb2292e1135d52152bab45a00fb8cabd6cbebf37e0a38`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.CoreMemberReloadTest#addedCallsAndRemovedReflectionKeepHeldMembersAcrossRestore` (watcher): Held object/Method/Field and pre-existing Legacy caller survive add/remove/restore/repeat; updated external Caller reaches added method/owner accessor; native meta-reflection hides/restores original gone/legacy, added extra/added remain invisible; same PID/nonce.

**Observable:** Held object/Method/Field and pre-existing Legacy caller survive add/remove/restore/repeat; updated external Caller reaches added method/owner accessor; native meta-reflection hides/restores original gone/legacy, added extra/added remain invisible; same PID/nonce.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-METHOD-REMOVE-HELD

**Verdict:** PROVEN_WITH_SCOPE.

Held Method and unchanged callers keep the last implementation when an original method is removed.

**Shipped surfaces:** `README.md:77` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `src/main/resources/META-INF/plugin.xml:68` (SHA-256 `fd2cae03def4ceed4b5cb2292e1135d52152bab45a00fb8cabd6cbebf37e0a38`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.CoreMemberReloadTest#addedCallsAndRemovedReflectionKeepHeldMembersAcrossRestore` (watcher): Held object/Method/Field and pre-existing Legacy caller survive add/remove/restore/repeat; updated external Caller reaches added method/owner accessor; native meta-reflection hides/restores original gone/legacy, added extra/added remain invisible; same PID/nonce.

**Observable:** Held object/Method/Field and pre-existing Legacy caller survive add/remove/restore/repeat; updated external Caller reaches added method/owner accessor; native meta-reflection hides/restores original gone/legacy, added extra/added remain invisible; same PID/nonce.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-REMOVED-REFLECTION

**Verdict:** PROVEN_WITH_SCOPE.

Original members removed by reload disappear from native reflection scans.

**Shipped surfaces:** `README.md:77` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `src/main/resources/META-INF/plugin.xml:68` (SHA-256 `fd2cae03def4ceed4b5cb2292e1135d52152bab45a00fb8cabd6cbebf37e0a38`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.CoreMemberReloadTest#addedCallsAndRemovedReflectionKeepHeldMembersAcrossRestore` (watcher): Held object/Method/Field and pre-existing Legacy caller survive add/remove/restore/repeat; updated external Caller reaches added method/owner accessor; native meta-reflection hides/restores original gone/legacy, added extra/added remain invisible; same PID/nonce.

**Observable:** Held object/Method/Field and pre-existing Legacy caller survive add/remove/restore/repeat; updated external Caller reaches added method/owner accessor; native meta-reflection hides/restores original gone/legacy, added extra/added remain invisible; same PID/nonce.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-MEMBER-RESTORE

**Verdict:** PROVEN_WITH_SCOPE.

Restoring an original removed member makes it visible and callable again.

**Shipped surfaces:** `README.md:77` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `src/main/resources/META-INF/plugin.xml:68` (SHA-256 `fd2cae03def4ceed4b5cb2292e1135d52152bab45a00fb8cabd6cbebf37e0a38`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.CoreMemberReloadTest#addedCallsAndRemovedReflectionKeepHeldMembersAcrossRestore` (watcher): Held object/Method/Field and pre-existing Legacy caller survive add/remove/restore/repeat; updated external Caller reaches added method/owner accessor; native meta-reflection hides/restores original gone/legacy, added extra/added remain invisible; same PID/nonce.

**Observable:** Held object/Method/Field and pre-existing Legacy caller survive add/remove/restore/repeat; updated external Caller reaches added method/owner accessor; native meta-reflection hides/restores original gone/legacy, added extra/added remain invisible; same PID/nonce.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-FIELD-REMOVE

**Verdict:** PROVEN_WITH_SCOPE.

A removed original field is hidden while a held Field and unchanged caller retain its value.

**Shipped surfaces:** `README.md:77` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `src/main/resources/META-INF/plugin.xml:68` (SHA-256 `fd2cae03def4ceed4b5cb2292e1135d52152bab45a00fb8cabd6cbebf37e0a38`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.CoreMemberReloadTest#addedCallsAndRemovedReflectionKeepHeldMembersAcrossRestore` (watcher): Held object/Method/Field and pre-existing Legacy caller survive add/remove/restore/repeat; updated external Caller reaches added method/owner accessor; native meta-reflection hides/restores original gone/legacy, added extra/added remain invisible; same PID/nonce.

**Observable:** Held object/Method/Field and pre-existing Legacy caller survive add/remove/restore/repeat; updated external Caller reaches added method/owner accessor; native meta-reflection hides/restores original gone/legacy, added extra/added remain invisible; same PID/nonce.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-FIELD-CROSS-CLASS

**Verdict:** PROVEN_WITH_SCOPE.

Direct watched cross-class instance-field reads and writes share the owning object store on the recorded declaring-type paths.

**Shipped surfaces:** `docs/usage.md:3045` (SHA-256 `a1aa85b1016a7d36938eab683db908c7efdbe4fe543d96909e455d2a7207398d`); `README.md:693` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `CHANGELOG.md:11` (SHA-256 `de73eb40fba7eb7d3b3c4310dafb830bd8e088d5acedfedb3e2f1dd1cbdb4eac`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.CoreMemberReloadTest#directAddedFieldCallerSharesOwnerValuesAcrossSaves` (watcher): External direct read sees42; direct write sets91 for both caller and owner; owner initializer edit does not reset91; accessor agrees with same PID/nonce.; `com.onurkat.reclazz.e2e.CoreCrossClassFieldReloadTest#directFieldsShareValuesAcrossObjectsTypesSavesAndNullReceivers` (watcher): Two held objects independently keep int/long/double/String first-read values; direct writes and null survive owner/caller saves; original fields and unwatched Point.x retain values; null receiver reads/writes throw NPE with unchanged PID/nonce.

**Observable:** External direct read sees42; direct write sets91 for both caller and owner; owner initializer edit does not reset91; accessor agrees with same PID/nonce.; Two held objects independently keep int/long/double/String first-read values; direct writes and null survive owner/caller saves; original fields and unwatched Point.x retain values; null receiver reads/writes throw NPE with unchanged PID/nonce.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Direct watched declaring-type paths with stable field types and the listed int/long/double/String shapes only; type changes and other inheritance/loader/enhanced cases are not certified. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-LAMBDA-RELOAD

**Verdict:** PROVEN_WITH_SCOPE.

Lambdas introduced in reloaded bodies execute with current captured values.

**Shipped surfaces:** `README.md:118` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.CoreLambdaReloadTest#introducedLambdasUseCurrentCapturesAndExposeSerializationLimit` (watcher): Three sequential body edits return current local/this captures, a-b stream value and added private helper; existing state user and PID/nonce retained; ObjectOutputStream fails with NotSerializableException.

**Observable:** Three sequential body edits return current local/this captures, a-b stream value and added private helper; existing state user and PID/nonce retained; ObjectOutputStream fails with NotSerializableException.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-LAMBDA-SERIALIZATION-LIMIT

**Verdict:** EXPECTED.

A serializable lambda introduced after reload cannot be serialized in the measured companion path.

**Shipped surfaces:** `README.md:118` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.CoreLambdaReloadTest#introducedLambdasUseCurrentCapturesAndExposeSerializationLimit` (watcher): Three sequential body edits return current local/this captures, a-b stream value and added private helper; existing state user and PID/nonce retained; ObjectOutputStream fails with NotSerializableException.

**Observable:** Three sequential body edits return current local/this captures, a-b stream value and added private helper; existing state user and PID/nonce retained; ObjectOutputStream fails with NotSerializableException.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-ENUM-APPEND-VALUES

**Verdict:** PROVEN_WITH_SCOPE.

Appending an enum tail updates native values/valueOf without changing surviving ordinals.

**Shipped surfaces:** `README.md:81` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.CoreEnumReloadTest#appendTailAndReorderKeepOldCollectionsAndExposeOldArrayLimit` (watcher): Original values RED/BLUE become RED/BLUE/GREEN; valueOf ordinal2; pre-existing map/set accept GREEN and preserve BLUE; old switch default other; old array still length2 throws on ordinal2; repeated edit does not duplicate; tail removal hides GREEN; reappend works; reorder preserves original ordinals and warns; same PID/nonce.

**Observable:** Original values RED/BLUE become RED/BLUE/GREEN; valueOf ordinal2; pre-existing map/set accept GREEN and preserve BLUE; old switch default other; old array still length2 throws on ordinal2; repeated edit does not duplicate; tail removal hides GREEN; reappend works; reorder preserves original ordinals and warns; same PID/nonce.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-ENUM-APPEND-SWITCH

**Verdict:** PROVEN_WITH_SCOPE.

An already-initialized old enum switch takes its default for an appended constant.

**Shipped surfaces:** `README.md:81` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.CoreEnumReloadTest#appendTailAndReorderKeepOldCollectionsAndExposeOldArrayLimit` (watcher): Original values RED/BLUE become RED/BLUE/GREEN; valueOf ordinal2; pre-existing map/set accept GREEN and preserve BLUE; old switch default other; old array still length2 throws on ordinal2; repeated edit does not duplicate; tail removal hides GREEN; reappend works; reorder preserves original ordinals and warns; same PID/nonce.

**Observable:** Original values RED/BLUE become RED/BLUE/GREEN; valueOf ordinal2; pre-existing map/set accept GREEN and preserve BLUE; old switch default other; old array still length2 throws on ordinal2; repeated edit does not duplicate; tail removal hides GREEN; reappend works; reorder preserves original ordinals and warns; same PID/nonce.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-ENUM-OLD-COLLECTIONS

**Verdict:** PROVEN_WITH_SCOPE.

Pre-existing EnumMap/EnumSet can use the appended constant.

**Shipped surfaces:** `README.md:81` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.CoreEnumReloadTest#appendTailAndReorderKeepOldCollectionsAndExposeOldArrayLimit` (watcher): Original values RED/BLUE become RED/BLUE/GREEN; valueOf ordinal2; pre-existing map/set accept GREEN and preserve BLUE; old switch default other; old array still length2 throws on ordinal2; repeated edit does not duplicate; tail removal hides GREEN; reappend works; reorder preserves original ordinals and warns; same PID/nonce.

**Observable:** Original values RED/BLUE become RED/BLUE/GREEN; valueOf ordinal2; pre-existing map/set accept GREEN and preserve BLUE; old switch default other; old array still length2 throws on ordinal2; repeated edit does not duplicate; tail removal hides GREEN; reappend works; reorder preserves original ordinals and warns; same PID/nonce.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-ENUM-TAIL

**Verdict:** PROVEN_WITH_SCOPE.

Removing an enum tail removes values/valueOf while preserving surviving ordinals.

**Shipped surfaces:** `README.md:82` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.CoreEnumReloadTest#appendTailAndReorderKeepOldCollectionsAndExposeOldArrayLimit` (watcher): Original values RED/BLUE become RED/BLUE/GREEN; valueOf ordinal2; pre-existing map/set accept GREEN and preserve BLUE; old switch default other; old array still length2 throws on ordinal2; repeated edit does not duplicate; tail removal hides GREEN; reappend works; reorder preserves original ordinals and warns; same PID/nonce.

**Observable:** Original values RED/BLUE become RED/BLUE/GREEN; valueOf ordinal2; pre-existing map/set accept GREEN and preserve BLUE; old switch default other; old array still length2 throws on ordinal2; repeated edit does not duplicate; tail removal hides GREEN; reappend works; reorder preserves original ordinals and warns; same PID/nonce.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-ENUM-REORDER-REFUSAL

**Verdict:** EXPECTED.

A reorder does not change live native ordinals and is restart-reported.

**Shipped surfaces:** `README.md:83` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.CoreEnumReloadTest#appendTailAndReorderKeepOldCollectionsAndExposeOldArrayLimit` (watcher): Original values RED/BLUE become RED/BLUE/GREEN; valueOf ordinal2; pre-existing map/set accept GREEN and preserve BLUE; old switch default other; old array still length2 throws on ordinal2; repeated edit does not duplicate; tail removal hides GREEN; reappend works; reorder preserves original ordinals and warns; same PID/nonce.

**Observable:** Original values RED/BLUE become RED/BLUE/GREEN; valueOf ordinal2; pre-existing map/set accept GREEN and preserve BLUE; old switch default other; old array still length2 throws on ordinal2; repeated edit does not duplicate; tail removal hides GREEN; reappend works; reorder preserves original ordinals and warns; same PID/nonce.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-ENUM-OLD-ARRAY-LIMIT

**Verdict:** EXPECTED.

Application-owned old values() arrays are not automatically grown.

**Shipped surfaces:** `README.md:81` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.CoreEnumReloadTest#appendTailAndReorderKeepOldCollectionsAndExposeOldArrayLimit` (watcher): Original values RED/BLUE become RED/BLUE/GREEN; valueOf ordinal2; pre-existing map/set accept GREEN and preserve BLUE; old switch default other; old array still length2 throws on ordinal2; repeated edit does not duplicate; tail removal hides GREEN; reappend works; reorder preserves original ordinals and warns; same PID/nonce.

**Observable:** Original values RED/BLUE become RED/BLUE/GREEN; valueOf ordinal2; pre-existing map/set accept GREEN and preserve BLUE; old switch default other; old array still length2 throws on ordinal2; repeated edit does not duplicate; tail removal hides GREEN; reappend works; reorder preserves original ordinals and warns; same PID/nonce.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-INTERFACE-STOCK-REFUSAL

**Verdict:** EXPECTED.

Stock-JVM interface identity stays unchanged while eligible edited bodies/later saves run.

**Shipped surfaces:** `README.md:84` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.CoreInterfaceRefusalTest#stockVmKeepsInterfaceIdentityWhileEditedBodiesAndLaterSavesRun` (watcher): Old Holder remains instanceof Tag=false, interfaces=[], same Class/state29; body v1->v2->v3, named interface refusal, unchanged PID/nonce.

**Observable:** Old Holder remains instanceof Tag=false, interfaces=[], same Class/state29; body v1->v2->v3, named interface refusal, unchanged PID/nonce.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-INTERFACE-ENHANCED

**Verdict:** UNVERIFIED.

Advertised enhanced-JVM added/removed interfaces require their own native acceptance.

**Shipped surfaces:** `README.md:84` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** No observing acceptance method mapped or executed for this variant. The named SapMachine lanes establish no behavior for this record; the missing environment and limitation below govern it.

**Tests/assertions:** No observing method mapped; remains unverified.

**Observable:** No observing test executed for this current advertised promise.

**Control:** No executed observing control for this record.

**Execution:** UNRUN for this claim; suite-level results do not prove this unmapped variant. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** No validated enhanced-redefinition JBR/DCEVM runtime or object/type acceptance run; IDE JBR presence is not proof. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Obtain the named runtime or a separately scoped observing test before claiming support.

### P1-SUPERCLASS-REFUSAL-SALVAGE

**Verdict:** PROVEN_WITH_SCOPE.

Reclazz preserves original hierarchy, applies eligible bodies, pins dependent methods and refuses unsafe signatures.

**Shipped surfaces:** `README.md:85` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `src/main/resources/META-INF/plugin.xml:68` (SHA-256 `fd2cae03def4ceed4b5cb2292e1135d52152bab45a00fb8cabd6cbebf37e0a38`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SuperclassReportingVerificationTest#receiptsAndPendingNeverClaimChangedHierarchyAcrossRepeatedSaves` (watcher): Seven versions: same original Class/loader/self/state19/superclassA; safe body edits apply, dependent c pinned, typed B saves refuse, recovery works with same status-session ID.

**Observable:** Seven versions: same original Class/loader/self/state19/superclassA; safe body edits apply, dependent c pinned, typed B saves refuse, recovery works with same status-session ID.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-CONCURRENCY

**Verdict:** UNVERIFIED.

Sequential initialization evidence does not certify concurrent initializer side effects or atomic multi-class reload.

**Shipped surfaces:** `docs/usage.md:3157` (SHA-256 `a1aa85b1016a7d36938eab683db908c7efdbe4fe543d96909e455d2a7207398d`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** No observing acceptance method mapped or executed for this variant. The named SapMachine lanes establish no behavior for this record; the missing environment and limitation below govern it.

**Tests/assertions:** No observing method mapped; remains unverified.

**Observable:** No observing test executed for this current advertised promise.

**Control:** No executed observing control for this record.

**Execution:** UNRUN for this claim; suite-level results do not prove this unmapped variant. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Owner deferred item33. Do not reopen implementation or concurrency tests; treat the published overlapping-computation text as unverified, not an exactly-once guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Remain deferred under owner item33 decision; no implementation or new concurrency acceptance without explicit approval.

### P1-STATIC-CONSTANTS

**Verdict:** PARTIAL.

Compile-time constants and narrow primitive constant conversions have component evidence.

**Shipped surfaces:** `README.md:79` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `docs/usage.md:3072` (SHA-256 `a1aa85b1016a7d36938eab683db908c7efdbe4fe543d96909e455d2a7207398d`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Direct helper/bytecode-transform/component fixtures in test-defined or throwaway loaders on stock SapMachine17/21, macOS arm64. These runs do not certify a packaged live watcher or normal application-loader integration.

**Tests/assertions:** `com.onurkat.reclazz.reload.StaticInitialiserTest#constantsComeFromTheFieldItself` (component): Constant plan reads MAX50 and NAMEreclazz with no refusal.; `com.onurkat.reclazz.reload.StaticInitialiserTest#narrowConstantsComeBackAsTheirOwnType` (component): Boolean/char/byte/short/int constant values keep native wrapper types.

**Observable:** Constant plan reads MAX50 and NAMEreclazz with no refusal.; Boolean/char/byte/short/int constant values keep native wrapper types.

**Control:** Read component assertions against fixture before/after values or refusal keys; no watcher/PID control is claimed for these methods.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-STATIC-ENTANGLED

**Verdict:** PARTIAL.

Intertwined/guarded static initializer shapes are refused by the component slicer.

**Shipped surfaces:** `README.md:80` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Direct helper/bytecode-transform/component fixtures in test-defined or throwaway loaders on stock SapMachine17/21, macOS arm64. These runs do not certify a packaged live watcher or normal application-loader integration.

**Tests/assertions:** `com.onurkat.reclazz.reload.StaticInitialiserTest#aSharedComputationIsRefused` (component): No emitted slice; computed-earlier reason recorded.; `com.onurkat.reclazz.reload.ConditionalStaticInitialiserTest#intertwinedInitialisersStayRefused` (component): Eleven named guarded/shared/local/loop/handler/object/array-write shapes emit no partial code and retain refusal keys.

**Observable:** No emitted slice; computed-earlier reason recorded.; Eleven named guarded/shared/local/loop/handler/object/array-write shapes emit no partial code and retain refusal keys.

**Control:** Read component assertions against fixture before/after values or refusal keys; no watcher/PID control is claimed for these methods.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-STATIC-LOWERINGS

**Verdict:** PARTIAL.

Nested conditionals, wide/decimal/object/null/source-order/short-circuit values have component observations, not universal watcher coverage.

**Shipped surfaces:** `README.md:79` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `docs/usage.md:3072` (SHA-256 `a1aa85b1016a7d36938eab683db908c7efdbe4fe543d96909e455d2a7207398d`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Direct helper/bytecode-transform/component fixtures in test-defined or throwaway loaders on stock SapMachine17/21, macOS arm64. These runs do not certify a packaged live watcher or normal application-loader integration.

**Tests/assertions:** `com.onurkat.reclazz.reload.ConditionalStaticInitialiserTest#aConditionalValueRunsOnlyItsSelectedBranch` (component): Two flag arms verify object identity, nested int, long, double, null, object constructor, short-circuit boolean and source order; existing99 and old blocks unchanged.; `com.onurkat.reclazz.reload.ConditionalStaticInitialiserTest#isolatedSwitchIsAccepted` (component): Isolated switch plan accepts VALUE:I.

**Observable:** Two flag arms verify object identity, nested int, long, double, null, object constructor, short-circuit boolean and source order; existing99 and old blocks unchanged.; Isolated switch plan accepts VALUE:I.

**Control:** Read component assertions against fixture before/after values or refusal keys; no watcher/PID control is claimed for these methods.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-LOAD-METADATA

**Verdict:** PARTIAL.

Load-time transformation retains selected reflection metadata in component fixtures.

**Shipped surfaces:** `README.md:745` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Direct helper/bytecode-transform/component fixtures in test-defined or throwaway loaders on stock SapMachine17/21, macOS arm64. These runs do not certify a packaged live watcher or normal application-loader integration.

**Tests/assertions:** `com.onurkat.reclazz.transform.ReflectionDepthTest#getDeclaredAnnotations` (component): Original method has one runtime annotation.; `com.onurkat.reclazz.transform.ReflectionDepthTest#repeatableAnnotations` (component): Three repeated annotations remain.; `com.onurkat.reclazz.transform.ReflectionDepthTest#genericReturnTypePreserved` (component): Native ParameterizedType remains List<String>.; `com.onurkat.reclazz.transform.ReflectionDepthTest#genericParameterTypesPreserved` (component): Native argument remains Map<String,Integer>.; `com.onurkat.reclazz.transform.ReflectionDepthTest#exceptionTypesPreserved` (component): IOException/SQLException declared exceptions remain.; `com.onurkat.reclazz.transform.ReflectionDepthTest#varargsFlagPreserved` (component): Varargs bit remains set.

**Observable:** Original method has one runtime annotation.; Three repeated annotations remain.; Native ParameterizedType remains List<String>.; Native argument remains Map<String,Integer>.; IOException/SQLException declared exceptions remain.; Varargs bit remains set.

**Control:** Read component assertions against fixture before/after values or refusal keys; no watcher/PID control is claimed for these methods.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-LOAD-SERIAL-UID

**Verdict:** PARTIAL.

Original serialVersionUID and selected no-agent-to-transformed deserialization have component evidence.

**Shipped surfaces:** `README.md:767` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); `src/main/resources/META-INF/plugin.xml:84` (SHA-256 `fd2cae03def4ceed4b5cb2292e1135d52152bab45a00fb8cabd6cbebf37e0a38`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Direct helper/bytecode-transform/component fixtures in test-defined or throwaway loaders on stock SapMachine17/21, macOS arm64. These runs do not certify a packaged live watcher or normal application-loader integration.

**Tests/assertions:** `com.onurkat.reclazz.transform.SerialVersionUidPreservedTest#theAgentDoesNotChangeWhatAClassSerializesAs` (component): Native computed UIDs match before/after transform.; `com.onurkat.reclazz.transform.SerialVersionUidPreservedTest#anObjectWrittenWithoutTheAgentIsReadWithIt` (component): Object written as plain Ser reads as transformed Ser with name n and count3.; `com.onurkat.reclazz.transform.SerialVersionUidPreservedTest#aDeclaredUidIsNotOverwritten` (component): Explicit UID4242 retained.

**Observable:** Native computed UIDs match before/after transform.; Object written as plain Ser reads as transformed Ser with name n and count3.; Explicit UID4242 retained.

**Control:** Read component assertions against fixture before/after values or refusal keys; no watcher/PID control is claimed for these methods.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-FIELD-CLONE

**Verdict:** PARTIAL.

Added-field store writes on a clone and original remain isolated in component fixtures.

**Shipped surfaces:** `README.md:773` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Direct helper/bytecode-transform/component fixtures in test-defined or throwaway loaders on stock SapMachine17/21, macOS arm64. These runs do not certify a packaged live watcher or normal application-loader integration.

**Tests/assertions:** `com.onurkat.reclazz.bootstrap.AddedFieldsAfterCloneTest#writingAnAddedFieldOnACloneLeavesTheOriginalAlone` (component): Clone second/original first values stay independent.; `com.onurkat.reclazz.bootstrap.AddedFieldsAfterCloneTest#writingOnTheOriginalAfterCloningLeavesTheCloneAlone` (component): Original changed/clone first values stay independent.; `com.onurkat.reclazz.bootstrap.AddedFieldsAfterCloneTest#copyingOnWriteKeepsTheOtherAddedFields` (component): Three named stored values round-trip.

**Observable:** Clone second/original first values stay independent.; Original changed/clone first values stay independent.; Three named stored values round-trip.

**Control:** Read component assertions against fixture before/after values or refusal keys; no watcher/PID control is claimed for these methods.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-FIELD-SERIALIZATION

**Verdict:** PARTIAL.

Transient added-field storage has component evidence; post-reload initializer/deserialization interaction needs watched acceptance.

**Shipped surfaces:** `README.md:773` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Direct helper/bytecode-transform/component fixtures in test-defined or throwaway loaders on stock SapMachine17/21, macOS arm64. These runs do not certify a packaged live watcher or normal application-loader integration.

**Tests/assertions:** `com.onurkat.reclazz.transform.InjectedFieldNotSerializedTest#theStorageForAddedFieldsIsTransient` (component): Injected __reclazz$ext carries the transient modifier.; `com.onurkat.reclazz.transform.InjectedFieldNotSerializedTest#anObjectWithAnUnserializableAddedFieldStillSerializes` (component): Unserializable stored Object does not block serialization; original name n survives and transient storage is null after deserialization.

**Observable:** Injected __reclazz$ext carries the transient modifier.; Unserializable stored Object does not block serialization; original name n survives and transient storage is null after deserialization.

**Control:** Read component assertions against fixture before/after values or refusal keys; no watcher/PID control is claimed for these methods.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** No observed post-reload initializer/deserialization acceptance mapped. Store-null inspection does not prove a supported first-read initializer returns the type default. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-LOAD-STACK

**Verdict:** UNVERIFIED.

Published stack-frame and logging-method-name observations need their own source/line acceptance.

**Shipped surfaces:** `README.md:751` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** No observing acceptance method mapped or executed for this variant. The named SapMachine lanes establish no behavior for this record; the missing environment and limitation below govern it.

**Tests/assertions:** No observing method mapped; remains unverified.

**Observable:** No observing test executed for this current advertised promise.

**Control:** No executed observing control for this record.

**Execution:** UNRUN for this claim; suite-level results do not prove this unmapped variant. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Obtain the named runtime or a separately scoped observing test before claiming support.

### P1-SYNCHRONIZED

**Verdict:** PROVEN_WITH_SCOPE.

Listed instance/static synchronized and exceptional-exit fixtures retain their monitor policy after structural reload.

**Shipped surfaces:** `README.md:820` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SynchronizedSurvivesReloadTest#twoThreadsStillExcludeEachOtherAfterAStructuralReload` (watcher): Two 300ms calls exclude before/after edit, elapsed >=540ms.; `com.onurkat.reclazz.e2e.SynchronizedSurvivesReloadTest#aBodyThatThrowsStillReleasesTheMonitor` (watcher): Different thread can reenter after exceptional exit: monitor FREE before/after.; `com.onurkat.reclazz.e2e.SynchronizedSurvivesReloadTest#aStaticSynchronizedMethodStillLocksItsClass` (watcher): Two static calls remain >=540ms after structural edit.

**Observable:** Two 300ms calls exclude before/after edit, elapsed >=540ms.; Different thread can reenter after exceptional exit: monitor FREE before/after.; Two static calls remain >=540ms after structural edit.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Specific two-thread fixtures and threshold; not a performance benchmark or universal concurrency proof. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-JDK-PROVIDERS

**Verdict:** UNVERIFIED.

The published any-JDK17+/provider matrix is broader than the two executed stock runtime cells.

**Shipped surfaces:** `README.md:658` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** No observing acceptance method mapped or executed for this variant. The named SapMachine lanes establish no behavior for this record; the missing environment and limitation below govern it.

**Tests/assertions:** No observing method mapped; remains unverified.

**Observable:** No observing test executed for this current advertised promise.

**Control:** No executed observing control for this record.

**Execution:** UNRUN for this claim; suite-level results do not prove this unmapped variant. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Oracle/OpenJDK/Corretto/Temurin/Zulu/Liberica/GraalVM and enhanced JBR/DCEVM builds are unrun. Two SapMachine releases cannot certify every listed vendor or future release. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Obtain the named runtime or a separately scoped observing test before claiming support.

### P1-JDK26-ENUM

**Verdict:** UNVERIFIED.

Advertised JDK26 enum/Unsafe fallback requires an actual JDK26 native run.

**Shipped surfaces:** `README.md:81` (SHA-256 `d3c8588069a889db42a9a9ac2794f9a83cd5df56a86b6dabd775204265552ac6`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** No observing acceptance method mapped or executed for this variant. The named SapMachine lanes establish no behavior for this record; the missing environment and limitation below govern it.

**Tests/assertions:** No observing method mapped; remains unverified.

**Observable:** No observing test executed for this current advertised promise.

**Control:** No executed observing control for this record.

**Execution:** UNRUN for this claim; suite-level results do not prove this unmapped variant. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** JDK26 is not installed/validated for this package; no inference from JDK17/21 or source/API names. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Obtain the named runtime or a separately scoped observing test before claiming support.
