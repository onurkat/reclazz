# Core JVM feature evidence

This is the first checkpoint of the all-advertised-features evidence program. It classifies the listed core promises; it does not certify every feature, framework, program, JDK or operating system. Other evidence packages remain separate.

Records distinguish **PROVEN_WITH_SCOPE** (observed watcher behavior on both named lanes), **PARTIAL** (component observations only), **EXPECTED** (observed limitation/refusal), **FAILED** (a capability fails despite a passing characterization test) and **UNVERIFIED** (missing run/environment). Independent Claude review is pending until the package audit is archived.

The available lanes are stock SapMachine17/21 on macOS arm64. Other provider/OS/enhanced cells remain unverified. Deferred field-initializer concurrency stays deferred. Raw logs/XML are retained locally at the paths in the JSON; the JSON contains method results and provenance, not a substitute claim that every green receipt establishes behavior.

Reproduce with the repository tasks `:agent:unitTest --rerun` and `:agent:e2eTest --rerun` on each named runtime, then check `P1RegistryCompletenessTest`. The check rejects omitted cells, stale public surfaces, nonexistent test methods, component-as-watcher promotion and unrun-as-proven promotion. It does not automatically decide the meaning of arbitrary prose.

The [machine record](feature-evidence/p1-core.json) includes actual execution commands, runtime/agent hashes, test-source hashes, result counts and raw evidence locations. This document’s digest lives in that separate record to avoid a self-referential hash.

### P1-BODY-SUPER

**Verdict:** PROVEN_WITH_SCOPE.

Edited overrides continue dispatching super calls to the current original parent.

**Shipped surfaces:** `docs/product-reference.md:20` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:20` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:22` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `docs/usage.md:3391` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:22` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `docs/usage.md:3391` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:22` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `docs/usage.md:3391` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:22` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `docs/usage.md:3391` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:22` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `docs/usage.md:3391` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:22` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `docs/usage.md:3391` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:22` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `docs/usage.md:3391` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:22` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `docs/usage.md:3391` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:23` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `docs/usage.md:3358` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:23` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `docs/usage.md:3358` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:23` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `docs/usage.md:3358` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:23` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `docs/usage.md:3358` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:21` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `src/main/resources/META-INF/plugin.xml:68` (SHA-256 `ce19b1013776d212e490107664eda272d27455c593d1002b71f40cbc5b555809`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.CoreMemberReloadTest#addedCallsAndRemovedReflectionKeepHeldMembersAcrossRestore` (watcher): Held object/Method/Field and pre-existing Legacy caller survive add/remove/restore/repeat; updated external Caller reaches added method/owner accessor; native meta-reflection hides/restores original gone/legacy, the added extra method is visible through meta-reflection and the added field is also visible; same PID/nonce.

**Observable:** Held object/Method/Field and pre-existing Legacy caller survive add/remove/restore/repeat; updated external Caller reaches added method/owner accessor; native meta-reflection hides/restores original gone/legacy, the added extra method is visible through meta-reflection and the added field is also visible; same PID/nonce.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-METHOD-REMOVE-HELD

**Verdict:** PROVEN_WITH_SCOPE.

A held Method and an unchanged caller of a removed method get NoSuchMethodError, as after a restart; restoring the method makes both answer with the restored body, and held objects and fields survive.

**Shipped surfaces:** `docs/product-reference.md:21` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `src/main/resources/META-INF/plugin.xml:68` (SHA-256 `ce19b1013776d212e490107664eda272d27455c593d1002b71f40cbc5b555809`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.CoreMemberReloadTest#addedCallsAndRemovedReflectionKeepHeldMembersAcrossRestore` (watcher): Held object/Field survive add/remove/restore/repeat; held Method and pre-existing Legacy caller get NoSuchMethodError while gone is removed and the restored body after restore; updated external Caller reaches added method/owner accessor; native meta-reflection hides/restores original gone/legacy, the added extra method is visible through meta-reflection and the added field is also visible; same PID/nonce.

**Observable:** Held object/Field survive add/remove/restore/repeat; held Method and pre-existing Legacy caller get NoSuchMethodError while gone is removed and the restored body after restore; updated external Caller reaches added method/owner accessor; native meta-reflection hides/restores original gone/legacy, the added extra method is visible through meta-reflection and the added field is also visible; same PID/nonce.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-REMOVED-REFLECTION

**Verdict:** PROVEN_WITH_SCOPE.

Original members removed by reload disappear from native reflection scans.

**Shipped surfaces:** `docs/product-reference.md:21` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `src/main/resources/META-INF/plugin.xml:68` (SHA-256 `ce19b1013776d212e490107664eda272d27455c593d1002b71f40cbc5b555809`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.CoreMemberReloadTest#addedCallsAndRemovedReflectionKeepHeldMembersAcrossRestore` (watcher): Held object/Method/Field and pre-existing Legacy caller survive add/remove/restore/repeat; updated external Caller reaches added method/owner accessor; native meta-reflection hides/restores original gone/legacy, the added extra method is visible through meta-reflection and the added field is also visible; same PID/nonce.

**Observable:** Held object/Method/Field and pre-existing Legacy caller survive add/remove/restore/repeat; updated external Caller reaches added method/owner accessor; native meta-reflection hides/restores original gone/legacy, the added extra method is visible through meta-reflection and the added field is also visible; same PID/nonce.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-MEMBER-RESTORE

**Verdict:** PROVEN_WITH_SCOPE.

Restoring an original removed member makes it visible and callable again.

**Shipped surfaces:** `docs/product-reference.md:21` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `src/main/resources/META-INF/plugin.xml:68` (SHA-256 `ce19b1013776d212e490107664eda272d27455c593d1002b71f40cbc5b555809`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.CoreMemberReloadTest#addedCallsAndRemovedReflectionKeepHeldMembersAcrossRestore` (watcher): Held object/Method/Field and pre-existing Legacy caller survive add/remove/restore/repeat; updated external Caller reaches added method/owner accessor; native meta-reflection hides/restores original gone/legacy, the added extra method is visible through meta-reflection and the added field is also visible; same PID/nonce.

**Observable:** Held object/Method/Field and pre-existing Legacy caller survive add/remove/restore/repeat; updated external Caller reaches added method/owner accessor; native meta-reflection hides/restores original gone/legacy, the added extra method is visible through meta-reflection and the added field is also visible; same PID/nonce.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-FIELD-REMOVE

**Verdict:** PROVEN_WITH_SCOPE.

A removed original field is hidden while a held Field and unchanged caller retain its value.

**Shipped surfaces:** `docs/product-reference.md:21` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `src/main/resources/META-INF/plugin.xml:68` (SHA-256 `ce19b1013776d212e490107664eda272d27455c593d1002b71f40cbc5b555809`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** Stock SapMachine17/21, macOS arm64, synthetic application in the normal application loader; existing instances and sequential edits as described by each observing test. Other loaders, providers, OS and enhanced modes are not certified.

**Tests/assertions:** `com.onurkat.reclazz.e2e.CoreMemberReloadTest#addedCallsAndRemovedReflectionKeepHeldMembersAcrossRestore` (watcher): Held object/Method/Field and pre-existing Legacy caller survive add/remove/restore/repeat; updated external Caller reaches added method/owner accessor; native meta-reflection hides/restores original gone/legacy, the added extra method is visible through meta-reflection and the added field is also visible; same PID/nonce.

**Observable:** Held object/Method/Field and pre-existing Legacy caller survive add/remove/restore/repeat; updated external Caller reaches added method/owner accessor; native meta-reflection hides/restores original gone/legacy, the added extra method is visible through meta-reflection and the added field is also visible; same PID/nonce.

**Control:** Named test before/after controls; same process/held-object controls where asserted. Scratch disabled-hook witnesses are separately retained for central paths.

**Execution:** sapmachine-17-macos-arm64: PASSED; sapmachine-21-macos-arm64: PASSED. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** Only the listed fixture shapes and named runtime lanes; no universal program guarantee. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Independent Claude audit of these records and their raw runs.

### P1-FIELD-CROSS-CLASS

**Verdict:** PROVEN_WITH_SCOPE.

Direct watched cross-class instance-field reads and writes share the owning object store on the recorded declaring-type paths.

**Shipped surfaces:** `docs/usage.md:3331` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); `docs/product-reference.md:597` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `CHANGELOG.md:653` (SHA-256 `775d25d26f62fbd5f86c468679259cbcfdda814333e55a783149960af4ee40a7`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:63` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:63` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:25` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:25` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:25` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:26` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:27` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:25` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:28` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:28` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:29` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `src/main/resources/META-INF/plugin.xml:68` (SHA-256 `ce19b1013776d212e490107664eda272d27455c593d1002b71f40cbc5b555809`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/usage.md:3522` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:23` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `docs/usage.md:3358` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:24` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:23` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `docs/usage.md:3358` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:665` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:687` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); `src/main/resources/META-INF/plugin.xml:84` (SHA-256 `ce19b1013776d212e490107664eda272d27455c593d1002b71f40cbc5b555809`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:693` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:693` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:671` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:740` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:578` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/product-reference.md:25` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** No observing acceptance method mapped or executed for this variant. The named SapMachine lanes establish no behavior for this record; the missing environment and limitation below govern it.

**Tests/assertions:** No observing method mapped; remains unverified.

**Observable:** No observing test executed for this current advertised promise.

**Control:** No executed observing control for this record.

**Execution:** UNRUN for this claim; suite-level results do not prove this unmapped variant. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** JDK26 is not installed/validated for this package; no inference from JDK17/21 or source/API names. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Obtain the named runtime or a separately scoped observing test before claiming support.

## P2: Ordinary Spring

This package maps current registration, recreation, injection, factory, lifecycle, transaction, cache, AOP and method-security promises to actual observing methods. It does not certify all Spring versions or shapes. P3–P8 and deferred/excluded work retain their separate owner decisions.

[Machine registry](feature-evidence/p2-ordinary-spring.json) and [cell manifest](feature-evidence/p2-cells.txt) contain source/surface hashes, method receipts, runtime/agent hashes, counts and raw local evidence. `P2RegistryCompletenessTest` rejects omissions, drift, false promotions and nonexistent methods, including inherited Spring 6 observers without loading Spring 6 into Spring 5. `:agent:unitTest` compiles that isolated test source set first.

### P2-BEAN-REGISTER-NEW

**Verdict:** PROVEN_WITH_SCOPE.

A new direct component becomes a live bean without restarting.

**Shipped surfaces:** `docs/product-reference.md:57` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.NewComponentConditionsReloadTest#newComponentsAreFilteredBeforeInitializationOnTheApplicationLoader` (watcher): Live context contains two admitted beans and no three rejected beans; created=2 initialized=2 conditions=3, normal/child loader and class/source saves.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-COMPONENT-PROFILE

**Verdict:** PROVEN_WITH_SCOPE.

A new component is admitted only by the active profile.

**Shipped surfaces:** `docs/product-reference.md:57` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.NewComponentConditionsReloadTest#newComponentsAreFilteredBeforeInitializationOnTheApplicationLoader` (watcher): Live context contains two admitted beans and no three rejected beans; created=2 initialized=2 conditions=3, normal/child loader and class/source saves.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-COMPONENT-CONDITIONAL

**Verdict:** PROVEN_WITH_SCOPE.

New component conditions use the live application context before class initialization.

**Shipped surfaces:** `docs/product-reference.md:57` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.NewComponentConditionsReloadTest#newComponentsAreFilteredBeforeInitializationOnTheApplicationLoader` (watcher): Live context contains two admitted beans and no three rejected beans; created=2 initialized=2 conditions=3, normal/child loader and class/source saves.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-COMPONENT-LAZY

**Verdict:** PROVEN_WITH_SCOPE.

New lazy singleton waits for first lookup.

**Shipped surfaces:** `docs/usage.md:436` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.NewComponentScopeReloadTest#newLazyAndPrototypeComponentsKeepTheirNativeLifetime` (watcher): BEFORE=0:0; LOOKUPS=true:true:1:2; CLOSED=1:0 on normal and child loaders.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-COMPONENT-PROTOTYPE

**Verdict:** PROVEN_WITH_SCOPE.

New prototype lookup creates a fresh instance; native prototype cleanup belongs to caller.

**Shipped surfaces:** `docs/usage.md:439` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.NewComponentScopeReloadTest#newLazyAndPrototypeComponentsKeepTheirNativeLifetime` (watcher): BEFORE=0:0; LOOKUPS=true:true:1:2; CLOSED=1:0 on normal and child loaders.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-COMPOSED-STEREOTYPE

**Verdict:** PARTIAL.

A custom component stereotype and AliasFor bean name register through native naming.

**Shipped surfaces:** `docs/usage.md:432` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringNewBeanRegistrarTest#customStereotypeWithAliasForNameRegistersAsABean` (component): Real Spring context gets ledger instance after direct registrar call.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Component-only: no watcher acceptance for this custom stereotype/name cell. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-NEW-CONSTRUCTOR-INJECTION

**Verdict:** UNVERIFIED.

New components support native constructor injection.

**Shipped surfaces:** `docs/product-reference.md:57` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** No observing method assigned; not promoted from a located filename.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** UNVERIFIED: no observing acceptance assigned on the required lanes.

**Limitation / missing environment:** New registration observers above do not assert constructor injection; no P2 observing method assigned. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. A separate bounded acceptance run is needed for this cell before promotion; do not infer it from adjacent green tests.

### P2-BEAN-REFRESH

**Verdict:** PROVEN_WITH_SCOPE.

An edited plain singleton is recreated and loses mutable instance state.

**Shipped surfaces:** `docs/product-reference.md:30` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.OrdinarySpringReloadEvidenceTest#editedSingletonRecreatesStateAndReachesHeldCallers` (watcher): AFTER body=2 state=0; old!=new, same held manual holder points to new bean, current autowired holder points to it; unrelated count=29 and identity retained, old state=41.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-HELD-CALLER

**Verdict:** PROVEN_WITH_SCOPE.

A live manually wired singleton holder is repointed to the recreated bean.

**Shipped surfaces:** `src/main/resources/META-INF/plugin.xml:47` (SHA-256 `ce19b1013776d212e490107664eda272d27455c593d1002b71f40cbc5b555809`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.OrdinarySpringReloadEvidenceTest#editedSingletonRecreatesStateAndReachesHeldCallers` (watcher): AFTER body=2 state=0; old!=new, same held manual holder points to new bean, current autowired holder points to it; unrelated count=29 and identity retained, old state=41.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Plain singleton field holder currently in the container; not arbitrary local variables or external object graphs. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-INJECTION-AUTOWIRED

**Verdict:** PROVEN_WITH_SCOPE.

Autowired added to an existing warmed field is satisfied after refresh.

**Shipped surfaces:** `docs/product-reference.md:32` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.OrdinarySpringReloadEvidenceTest#addedFieldInjectionAnnotationsReachRecreatedSingleton` (watcher): Existing warmed fields are null:0:null before adding annotations; live recreated bean becomes injected:42:injected, with holder/identity/state controls.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-INJECTION-VALUE

**Verdict:** PROVEN_WITH_SCOPE.

Value added to an existing warmed field resolves the live property.

**Shipped surfaces:** `docs/product-reference.md:32` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.OrdinarySpringReloadEvidenceTest#addedFieldInjectionAnnotationsReachRecreatedSingleton` (watcher): Existing warmed fields are null:0:null before adding annotations; live recreated bean becomes injected:42:injected, with holder/identity/state controls.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-INJECTION-RESOURCE

**Verdict:** PROVEN_WITH_SCOPE.

Resource added to an existing warmed field resolves the named bean.

**Shipped surfaces:** `docs/product-reference.md:32` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.OrdinarySpringReloadEvidenceTest#addedFieldInjectionAnnotationsReachRecreatedSingleton` (watcher): Existing warmed fields are null:0:null before adding annotations; live recreated bean becomes injected:42:injected, with holder/identity/state controls.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** javax.annotation 1.3.2 / Spring 5.3.39. Jakarta Resource / Spring 6 is not asserted here. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-LITE-CONFIG

**Verdict:** PROVEN_WITH_SCOPE.

New private instance Bean factory on lite Configuration registers, replaces, removes and recovers.

**Shipped surfaces:** `docs/product-reference.md:31` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedBeanMethodReloadTest#addedFactoryRegistersUpdatesRemovesAndRecovers` (watcher): Products missing→2→3→4→missing→6→missing→factory failure missing→9; singleton identity and original singleton stay intact, CLOSED=5.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-ARGS

**Verdict:** PROVEN_WITH_SCOPE.

Saved factory reference parameters use native qualifier selection.

**Shipped surfaces:** `docs/product-reference.md:31` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedBeanArgumentsReloadTest#parametersAndQualifierEditsReachTheCompanionAndRecoverFromMissingBeans` (watcher): Blue/red qualified dependencies and saved body versions update; missing dependency/removed annotation remove the product; correction recovers, dependency identities preserved, CLOSED=5.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-RICH-ARGS

**Verdict:** PROVEN_WITH_SCOPE.

Added factory list/map/Optional arguments preserve measured native resolution.

**Shipped surfaces:** `docs/product-reference.md:31` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedBeanRichArgumentsReloadTest#richArgumentsFollowSavesAndKeepProviderLazy` (watcher): Ordered list [red,blue], map names [blue,red], Optional absent→present, primitive int/long/double values; provider target is not created eagerly and PROVIDER=1:2:true after target retirement; invalid conversion/removal/recovery, CLOSED=4.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** List, Map<String,T>, Optional in this watcher; arrays/Set/Collection generic element filtering are separate unverified cells. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-PROVIDER

**Verdict:** PROVEN_WITH_SCOPE.

An added factory provider stays lazy and resolves the current target after retirement.

**Shipped surfaces:** `docs/product-reference.md:31` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedBeanRichArgumentsReloadTest#richArgumentsFollowSavesAndKeepProviderLazy` (watcher): Ordered list [red,blue], map names [blue,red], Optional absent→present, primitive int/long/double values; provider target is not created eagerly and PROVIDER=1:2:true after target retirement; invalid conversion/removal/recovery, CLOSED=4.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-PRIMITIVE-VALUE

**Verdict:** PROVEN_WITH_SCOPE.

Added factories convert measured int, long and double Value arguments.

**Shipped surfaces:** `docs/product-reference.md:31` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedBeanRichArgumentsReloadTest#richArgumentsFollowSavesAndKeepProviderLazy` (watcher): Ordered list [red,blue], map names [blue,red], Optional absent→present, primitive int/long/double values; provider target is not created eagerly and PROVIDER=1:2:true after target retirement; invalid conversion/removal/recovery, CLOSED=4.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Other five primitive conversions/reference nulls not observed by this watcher. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-ARRAY-SET-ARGS

**Verdict:** PARTIAL.

Factory Set/Collection/reference-array arguments use native resolution.

**Shipped surfaces:** `docs/usage.md:578` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.spring.AddedBeanRichArgumentsTest#containersMatchNativeFactoryOrderingAndEmptyFallbacks` (component): Direct adapter reload compares List/Set/Collection/Map/reference-array products to native startup for populated and empty candidates.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Real Spring component resolution only; no file-watcher receipt for Set/Collection/reference-array variants. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-ALL-PRIMITIVES

**Verdict:** PARTIAL.

All eight primitive Value conversions and reference nulls work.

**Shipped surfaces:** `docs/usage.md:585` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.spring.AddedBeanRichArgumentsTest#everyPrimitiveAndAnExplicitNullReachTheFactoryBody` (component): Direct adapter reload invokes factory once with true, byte7, charZ, short8, int9, long10, float1.25, double2.5 and an explicit null.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** All conversions observed by real Spring component test; watcher acceptance separately covers int/long/double only. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-VALUE-PROPERTY

**Verdict:** PROVEN_WITH_SCOPE.

Checked scalar property edits recreate an already added unproxied factory product.

**Shipped surfaces:** `docs/product-reference.md:31` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedFactoryValueArgumentReloadTest#addedFactoryValuesFollowPropertyEdits` (watcher): Scalar factory values produce CAPTURED=5000:20:old, then checked property edits yield 8000:25:new and recover; invalid/unsafe expressions retain accepted product and sideEffect count zero; CLOSED=5.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Bounded checked scalar expressions; unsafe/invalid edits retain accepted product. Broader property graph coverage belongs to P5. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-ALIASES

**Verdict:** PROVEN_WITH_SCOPE.

Factory names/aliases follow edits, removal and recovery without duplicate singleton products.

**Shipped surfaces:** `docs/product-reference.md:31` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedBeanAliasesReloadTest#aliasesFollowAddedFactoriesAcrossEditsFailureRecoveryAndRemoval` (watcher): Renames main/legacy/current resolve one identical product, stale aliases disappear, consumer points to it; factory failure/removal/restoration leave only owned names, CLOSED=5.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-QUALIFIER

**Verdict:** PROVEN_WITH_SCOPE.

Saved factory qualifiers and parameter qualifiers select matching native candidates.

**Shipped surfaces:** `docs/product-reference.md:31` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedBeanSelectionReloadTest#addedFactoryMetadataChangesSelectionAndRecoversAfterRemovalOrAmbiguity` (watcher): Selection first/second→second/first; no primary, missing qualifier and two primaries fail only affected consumers; corrected metadata recovers, no fast alias, CLOSED=9.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-PRIMARY

**Verdict:** PROVEN_WITH_SCOPE.

Saved Primary metadata updates default selection and reports ambiguity.

**Shipped surfaces:** `docs/product-reference.md:31` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedBeanSelectionReloadTest#addedFactoryMetadataChangesSelectionAndRecoversAfterRemovalOrAmbiguity` (watcher): Selection first/second→second/first; no primary, missing qualifier and two primaries fail only affected consumers; corrected metadata recovers, no fast alias, CLOSED=9.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-CONDITIONAL

**Verdict:** PROVEN_WITH_SCOPE.

Saved method Conditional admits/rejects against the live context and retires old products.

**Shipped surfaces:** `docs/product-reference.md:31` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedBeanNativeConditionsReloadTest#conditionalFactoriesFollowActivationErrorsRemovalAndLazyRestoration` (watcher): Disabled/admitted/throwing/removed/restored factories change presence and exact made/closed counts; saved MethodMetadata and live bean factory/registry/environment/loader/resource checked; final lazy made count 2 before lookup, CLOSED=3 CONDITIONS=6.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-BOOT-PROPERTY

**Verdict:** PROVEN_WITH_SCOPE.

Direct Boot ConditionalOnProperty uses the measured native decision on added factories.

**Shipped surfaces:** `docs/product-reference.md:31` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Boot 2.7.18 / Spring 5.3.39 on stock SapMachine17/21 macOS arm64, normal/child loaders and lite/full Configuration.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedBeanBootConditionsReloadTest#bootConditionsEvaluateHiddenFactoriesAndRetireTheirProducts` (watcher): Native property/bean/missing-bean decisions yield exact present/made/closed sequence; aliases agree, startup control identity retained, lazy restoration leaves made=3 until lookup; CLOSED=4.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** No Boot 3 dependency or watcher in this package; do not infer Boot 3 from Spring 6 security. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-BOOT-BEAN

**Verdict:** PROVEN_WITH_SCOPE.

Direct Boot ConditionalOnBean uses the measured native decision on added factories.

**Shipped surfaces:** `docs/product-reference.md:31` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Boot 2.7.18 / Spring 5.3.39 on stock SapMachine17/21 macOS arm64, normal/child loaders and lite/full Configuration.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedBeanBootConditionsReloadTest#bootConditionsEvaluateHiddenFactoriesAndRetireTheirProducts` (watcher): Native property/bean/missing-bean decisions yield exact present/made/closed sequence; aliases agree, startup control identity retained, lazy restoration leaves made=3 until lookup; CLOSED=4.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** No Boot 3 dependency or watcher in this package; do not infer Boot 3 from Spring 6 security. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-BOOT-MISSING-BEAN

**Verdict:** PROVEN_WITH_SCOPE.

Direct Boot ConditionalOnMissingBean uses the measured native decision on added factories.

**Shipped surfaces:** `docs/product-reference.md:31` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Boot 2.7.18 / Spring 5.3.39 on stock SapMachine17/21 macOS arm64, normal/child loaders and lite/full Configuration.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedBeanBootConditionsReloadTest#bootConditionsEvaluateHiddenFactoriesAndRetireTheirProducts` (watcher): Native property/bean/missing-bean decisions yield exact present/made/closed sequence; aliases agree, startup control identity retained, lazy restoration leaves made=3 until lookup; CLOSED=4.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** No Boot 3 dependency or watcher in this package; do not infer Boot 3 from Spring 6 security. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-PROFILE

**Verdict:** PROVEN_WITH_SCOPE.

Saved direct factory Profile expressions re-evaluate admitted products.

**Shipped surfaces:** `docs/product-reference.md:31` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedBeanConditionalReloadTest#lazyPrototypeAndProfileMetadataOnAddedFactoriesFollowSavesAndScopeLifecycle` (watcher): Added lazy/prototype factory creation deferred; profile annotations swap admitted names on saves; singleton reused after lookup, prototype distinct, CLOSED held=1 proto=0.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-SCOPE-PROTOTYPE

**Verdict:** PROVEN_WITH_SCOPE.

Added prototype factory creates distinct lookup products.

**Shipped surfaces:** `docs/product-reference.md:31` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedBeanConditionalReloadTest#lazyPrototypeAndProfileMetadataOnAddedFactoriesFollowSavesAndScopeLifecycle` (watcher): Added lazy/prototype factory creation deferred; profile annotations swap admitted names on saves; singleton reused after lookup, prototype distinct, CLOSED held=1 proto=0.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-LAZY

**Verdict:** PROVEN_WITH_SCOPE.

Added lazy factory waits for first lookup and keeps singleton identity.

**Shipped surfaces:** `docs/product-reference.md:31` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedBeanConditionalReloadTest#lazyPrototypeAndProfileMetadataOnAddedFactoriesFollowSavesAndScopeLifecycle` (watcher): Added lazy/prototype factory creation deferred; profile annotations swap admitted names on saves; singleton reused after lookup, prototype distinct, CLOSED held=1 proto=0.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-FULL-CONFIG

**Verdict:** PROVEN_WITH_SCOPE.

Full enhanced Configuration inter-bean calls preserve container singleton identity across saves.

**Shipped surfaces:** `docs/product-reference.md:31` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedFullConfigurationReloadTest#factoriesKeepSingletonIdentityAcrossCallsCyclesEditsAndRemoval` (watcher): Held configuration and saved method reference resolve one container singleton across saves; cycle is refused, removal clears owned names/aliases, restoration gets current version; static calls remain distinct Java calls.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-FULL-CONFIG-ARGS

**Verdict:** PROVEN_WITH_SCOPE.

Full factory calls preserve supported explicit/prototype arguments and held method references.

**Shipped surfaces:** `docs/product-reference.md:31` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedFullConfigurationArgumentsReloadTest#argumentsAndHeldReferencesFollowFiveSaves` (watcher): Five saves prove injected/full inter-bean identity, explicit prototype arguments and held method reference semantics; plain new Config retains Java arguments, removal/restoration observed.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-BEAN-STATIC

**Verdict:** PROVEN_WITH_SCOPE.

Static companion factories register/replace/retire products with native lifecycle.

**Shipped surfaces:** `docs/usage.md:636` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedStaticBeanReloadTest#staticCompanionFactoriesFollowEditsFailureRecoveryAndRemoval` (watcher): Static companion product body/alias/qualifier updates, factory failure/removal/restoration; mixed static/instance consumers use same dependency, original identity preserved, CLOSED=4.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-FULL-CONFIG-STATIC-JAVA

**Verdict:** PROVEN_WITH_SCOPE.

Static factories retain plain Java call semantics outside container lookup.

**Shipped surfaces:** `docs/product-reference.md:31` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedFullConfigurationReloadTest#factoriesKeepSingletonIdentityAcrossCallsCyclesEditsAndRemoval` (watcher): Held configuration and saved method reference resolve one container singleton across saves; cycle is refused, removal clears owned names/aliases, restoration gets current version; static calls remain distinct Java calls.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-LIFECYCLE-INIT

**Verdict:** PROVEN_WITH_SCOPE.

Entirely new PostConstruct initializes after injection and before InitializingBean.

**Shipped surfaces:** `docs/product-reference.md:33` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Plain singleton components: Spring 5.3.39 + javax.annotation 1.3.2 and Spring 6.1.14 + jakarta.annotation 2.1.1, SapMachine17/21 macOS arm64.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedLifecycleMethodReloadTest#addedCallbacksFollowInstanceLifetimeAcrossSaves` (watcher): Exact ordered event list on six saves, ordinary factory recreation and context close; injection before added init, then InitializingBean; each instance retains its owed added destroy, then DisposableBean; open=0 at close.; `com.onurkat.reclazz.e2e.JakartaLifecycleReloadTest#addedCallbacksFollowInstanceLifetimeAcrossSaves` (watcher): Exact ordered event list on six saves, ordinary factory recreation and context close; injection before added init, then InitializingBean; each instance retains its owed added destroy, then DisposableBean; open=0 at close.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only plain singleton component ownership/order shown by listed tests; proxied/unsupported callback shapes keep documented restrictions. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-LIFECYCLE-DESTROY

**Verdict:** PROVEN_WITH_SCOPE.

Entirely new PreDestroy remains owed per instance through metadata edits/removal and context close.

**Shipped surfaces:** `docs/product-reference.md:33` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Plain singleton components: Spring 5.3.39 + javax.annotation 1.3.2 and Spring 6.1.14 + jakarta.annotation 2.1.1, SapMachine17/21 macOS arm64.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedLifecycleMethodReloadTest#addedCallbacksFollowInstanceLifetimeAcrossSaves` (watcher): Exact ordered event list on six saves, ordinary factory recreation and context close; injection before added init, then InitializingBean; each instance retains its owed added destroy, then DisposableBean; open=0 at close.; `com.onurkat.reclazz.e2e.JakartaLifecycleReloadTest#addedCallbacksFollowInstanceLifetimeAcrossSaves` (watcher): Exact ordered event list on six saves, ordinary factory recreation and context close; injection before added init, then InitializingBean; each instance retains its owed added destroy, then DisposableBean; open=0 at close.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only plain singleton component ownership/order shown by listed tests; proxied/unsupported callback shapes keep documented restrictions. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-LIFECYCLE-NATIVE-LIFECYCLE

**Verdict:** PROVEN_WITH_SCOPE.

InitializingBean and DisposableBean retain native callback ordering during recreation.

**Shipped surfaces:** `docs/product-reference.md:33` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Plain singleton components: Spring 5.3.39 + javax.annotation 1.3.2 and Spring 6.1.14 + jakarta.annotation 2.1.1, SapMachine17/21 macOS arm64.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedLifecycleMethodReloadTest#addedCallbacksFollowInstanceLifetimeAcrossSaves` (watcher): Exact ordered event list on six saves, ordinary factory recreation and context close; injection before added init, then InitializingBean; each instance retains its owed added destroy, then DisposableBean; open=0 at close.; `com.onurkat.reclazz.e2e.JakartaLifecycleReloadTest#addedCallbacksFollowInstanceLifetimeAcrossSaves` (watcher): Exact ordered event list on six saves, ordinary factory recreation and context close; injection before added init, then InitializingBean; each instance retains its owed added destroy, then DisposableBean; open=0 at close.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only plain singleton component ownership/order shown by listed tests; proxied/unsupported callback shapes keep documented restrictions. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-ADDED-OPERATIONS-PLAIN

**Verdict:** PROVEN_WITH_SCOPE.

First transaction/cache operations added to a plain singleton use real native interceptors.

**Shipped surfaces:** `docs/product-reference.md:42` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedServiceOperationReloadTest#firstOperationsOnPlainSingletonUseRealTransactionsAndCaches` (watcher): Real H2 transaction commit active; checked exception rolls back (rows stays 1). Cached repeats execute once, put updates value, evict causes another call; saved keys/removal/restoration work; old target identity retained and self invocation stays false.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-ADDED-OPERATIONS-HELD

**Verdict:** PROVEN_WITH_SCOPE.

Added operations invoked through an old CGLIB reference use real interceptors and retain target identity.

**Shipped surfaces:** `docs/product-reference.md:42` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedServiceOperationReloadTest#addedOperationsUseRealTransactionsAndCachesThroughOldCglibReference` (watcher): Real H2 transaction commit active; checked exception rolls back (rows stays 1). Cached repeats execute once, put updates value, evict causes another call; saved keys/removal/restoration work; old target identity retained and self invocation stays false.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-TX-ROLLBACK

**Verdict:** PROVEN_WITH_SCOPE.

A checked exception from an added transactional method rolls back the real database write.

**Shipped surfaces:** `docs/product-reference.md:42` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedServiceOperationReloadTest#addedOperationsUseRealTransactionsAndCachesThroughOldCglibReference` (watcher): Real H2 transaction commit active; checked exception rolls back (rows stays 1). Cached repeats execute once, put updates value, evict causes another call; saved keys/removal/restoration work; old target identity retained and self invocation stays false.; `com.onurkat.reclazz.e2e.AddedServiceOperationReloadTest#firstOperationsOnPlainSingletonUseRealTransactionsAndCaches` (watcher): Real H2 transaction commit active; checked exception rolls back (rows stays 1). Cached repeats execute once, put updates value, evict causes another call; saved keys/removal/restoration work; old target identity retained and self invocation stays false.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-CACHE-HIT

**Verdict:** PROVEN_WITH_SCOPE.

Added Cacheable executes once for a repeated key.

**Shipped surfaces:** `docs/product-reference.md:42` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedServiceOperationReloadTest#addedOperationsUseRealTransactionsAndCachesThroughOldCglibReference` (watcher): Real H2 transaction commit active; checked exception rolls back (rows stays 1). Cached repeats execute once, put updates value, evict causes another call; saved keys/removal/restoration work; old target identity retained and self invocation stays false.; `com.onurkat.reclazz.e2e.AddedServiceOperationReloadTest#firstOperationsOnPlainSingletonUseRealTransactionsAndCaches` (watcher): Real H2 transaction commit active; checked exception rolls back (rows stays 1). Cached repeats execute once, put updates value, evict causes another call; saved keys/removal/restoration work; old target identity retained and self invocation stays false.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-CACHE-PUT

**Verdict:** PROVEN_WITH_SCOPE.

Added CachePut updates the native cache entry.

**Shipped surfaces:** `docs/product-reference.md:42` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedServiceOperationReloadTest#addedOperationsUseRealTransactionsAndCachesThroughOldCglibReference` (watcher): Real H2 transaction commit active; checked exception rolls back (rows stays 1). Cached repeats execute once, put updates value, evict causes another call; saved keys/removal/restoration work; old target identity retained and self invocation stays false.; `com.onurkat.reclazz.e2e.AddedServiceOperationReloadTest#firstOperationsOnPlainSingletonUseRealTransactionsAndCaches` (watcher): Real H2 transaction commit active; checked exception rolls back (rows stays 1). Cached repeats execute once, put updates value, evict causes another call; saved keys/removal/restoration work; old target identity retained and self invocation stays false.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-CACHE-EVICT

**Verdict:** PROVEN_WITH_SCOPE.

Added CacheEvict removes the native entry so next lookup executes again.

**Shipped surfaces:** `docs/product-reference.md:42` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedServiceOperationReloadTest#addedOperationsUseRealTransactionsAndCachesThroughOldCglibReference` (watcher): Real H2 transaction commit active; checked exception rolls back (rows stays 1). Cached repeats execute once, put updates value, evict causes another call; saved keys/removal/restoration work; old target identity retained and self invocation stays false.; `com.onurkat.reclazz.e2e.AddedServiceOperationReloadTest#firstOperationsOnPlainSingletonUseRealTransactionsAndCaches` (watcher): Real H2 transaction commit active; checked exception rolls back (rows stays 1). Cached repeats execute once, put updates value, evict causes another call; saved keys/removal/restoration work; old target identity retained and self invocation stays false.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-OPERATIONS-SELF-INVOKE

**Verdict:** EXPECTED.

Native self invocation stays outside transaction interception.

**Shipped surfaces:** `docs/usage.md:2060` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedServiceOperationReloadTest#addedOperationsUseRealTransactionsAndCachesThroughOldCglibReference` (watcher): Real H2 transaction commit active; checked exception rolls back (rows stays 1). Cached repeats execute once, put updates value, evict causes another call; saved keys/removal/restoration work; old target identity retained and self invocation stays false.; `com.onurkat.reclazz.e2e.AddedServiceOperationReloadTest#firstOperationsOnPlainSingletonUseRealTransactionsAndCaches` (watcher): Real H2 transaction commit active; checked exception rolls back (rows stays 1). Cached repeats execute once, put updates value, evict causes another call; saved keys/removal/restoration work; old target identity retained and self invocation stays false.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-COMPOSED-TRANSACTION

**Verdict:** PROVEN_WITH_SCOPE.

Supported composed transaction aliases follow saves through held proxy and affect DB rollback/readOnly.

**Shipped surfaces:** `docs/product-reference.md:42` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ComposedTransactionOperationReloadTest#composedTransactionsFollowSavesThroughHeldProxy` (watcher): Held proxy follows meta-transaction readOnly aliases; checked-exception DB counts distinguish rollback from annotation removal; same target retained, removal/restoration observed on normal/child loaders.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-COMPOSED-CACHE

**Verdict:** PROVEN_WITH_SCOPE.

Supported composed cache aliases/conditions follow saves through held proxy and affect real cache entries.

**Shipped surfaces:** `docs/product-reference.md:42` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ComposedCacheOperationReloadTest#composedCacheFollowsSavesThroughHeldProxy` (watcher): Held native proxy returns identical cached repeats; changed region/key aliases use actual cache entries, condition false/annotation removal execute twice, native control remains cached; removal/restoration and identity checked.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-AOP-REFRESH

**Verdict:** PROVEN_WITH_SCOPE.

Edited aspect pointcuts change advice on already injected mutable JDK/CGLIB singleton proxies.

**Shipped surfaces:** `docs/product-reference.md:44` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AspectPointcutReloadTest#factoryAspectSavesUpdateAlreadyInjectedJdkAndCglibProxies` (watcher): Already injected JDK and CGLIB references change advised methods across four saves; advisor count 2→1→2, target counter/identity and exception unchanged.; `com.onurkat.reclazz.e2e.AspectPointcutReloadTest#componentAspectSavesUpdateAlreadyInjectedJdkAndCglibProxies` (watcher): Already injected JDK and CGLIB references change advised methods across four saves; advisor count 2→1→2, target counter/identity and exception unchanged.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Mutable singleton proxies only; target counter/identity retained. Frozen/dynamic/raw proxy shapes are not certified by these watcher methods. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-SECURITY-PRE-POST

**Verdict:** PROVEN_WITH_SCOPE.

Added/saved PreAuthorize and PostAuthorize enforce real authentication/authorization through a held native proxy.

**Shipped surfaces:** `docs/product-reference.md:43` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Spring5.3.39/Security5.7.11 classic and Spring6.1.14/Security6.3.4 EnableMethodSecurity, stock SapMachine17/21 macOS arm64, normal/child loaders.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedMethodSecurityReloadTest#heldNativeSecurityProxyFollowsPolicyBodyRemovalAndRestore` (watcher): Native pre/post authorization permits/denies real calls with exact body counts 1,2,4,5,5,6; unauthenticated denied, return policy enforced; body/policy/removal/restoration, same held proxy and native original control.; `com.onurkat.reclazz.e2e.AddedModernMethodSecurityReloadTest#heldNativeSecurityProxyFollowsPolicyBodyRemovalAndRestore` (watcher): Native pre/post authorization permits/denies real calls with exact body counts 1,2,4,5,5,6; unauthenticated denied, return policy enforced; body/policy/removal/restoration, same held proxy and native original control.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-COMPOSED-SECURITY

**Verdict:** PROVEN_WITH_SCOPE.

Fixed composed policy markers enforce saved native pre/post authorization through the held proxy.

**Shipped surfaces:** `docs/product-reference.md:43` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Same named Spring5/Security5 and Spring6/Security6 lanes as P2-SECURITY-PRE-POST; nested fixed markers, not runtime marker-definition edits.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ComposedMethodSecurityReloadTest#heldNativeSecurityProxyFollowsPolicyBodyRemovalAndRestore` (watcher): Native pre/post authorization permits/denies real calls with exact body counts 1,2,4,5,5,6; unauthenticated denied, return policy enforced; body/policy/removal/restoration, same held proxy and native original control.; `com.onurkat.reclazz.e2e.ModernComposedMethodSecurityReloadTest#heldNativeSecurityProxyFollowsPolicyBodyRemovalAndRestore` (watcher): Native pre/post authorization permits/denies real calls with exact body counts 1,2,4,5,5,6; unauthenticated denied, return policy enforced; body/policy/removal/restoration, same held proxy and native original control.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-SECURITY-METADATA

**Verdict:** PROVEN_WITH_SCOPE.

Resolved method-security cache entries are refilled from fresh Method objects.

**Shipped surfaces:** `src/main/resources/META-INF/plugin.xml:52` (SHA-256 `ce19b1013776d212e490107664eda272d27455c593d1002b71f40cbc5b555809`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine17/21 on macOS arm64; Spring5.3.39/Security5.7.11 and isolated Spring6.1.14/Security6.3.4; normal/child application loader, four sequential saves. Original Secured methods on a held startup native CGLIB factory-product proxy; stereotype control uses the same factory registration.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SecuredMetadataReloadTest#heldOriginalSecuredPolicyMatchesNativeAfterFourSaves` (watcher); `com.onurkat.reclazz.e2e.LastSecuredMetadataReloadTest#heldOriginalSecuredPolicyMatchesNativeAfterFourSaves` (watcher); `com.onurkat.reclazz.e2e.ModernSecuredMetadataReloadTest#heldOriginalSecuredPolicyMatchesNativeAfterFourSaves` (watcher); `com.onurkat.reclazz.e2e.ModernLastSecuredMetadataReloadTest#heldOriginalSecuredPolicyMatchesNativeAfterFourSaves` (watcher). Held startup proxy enforces original Secured authority edits and last-annotation removal/restoration; denied calls execute zero bodies.

**Control:** Fresh agentless baseline per save, marker controls and unchanged control calls; helper hook-disabled restores stale authority, metadata-flag-disabled restores removed rule on held proxy.

**Execution:** PASSED on both named main JDK17/21 lanes with Security5.7.11/6.3.4; exact commands, two-invocation receipts and source/artifact digests in JSON. Full gate and independent audit recorded separately.

**Limitation / missing environment:** Named original Secured methods/proxies and sequential saves only; no implicit JSR250 RolesAllowed/DenyAll/PermitAll, added-policy adapter, arbitrary context/proxy graph or concurrent save support. Other OS/JDK providers, framework versions, reactive/remote providers and unlisted registrations; no universal security guarantee.

**Reviewer / next action:** Astra main source/test evidence mapped; Claude independent audit pending. Independent Claude audit of final diff, raw main receipts, flag-gated negative witnesses and full gate before local commit.

### P2-SECURITY-SECURED

**Verdict:** PROVEN_WITH_SCOPE.

Edited Secured policy is enforced on real calls.

**Shipped surfaces:** `src/main/resources/META-INF/plugin.xml:52` (SHA-256 `ce19b1013776d212e490107664eda272d27455c593d1002b71f40cbc5b555809`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine17/21 on macOS arm64; Spring5.3.39/Security5.7.11 and isolated Spring6.1.14/Security6.3.4; normal/child application loader, four sequential saves. Original Secured methods on a held startup native CGLIB factory-product proxy; stereotype control uses the same factory registration.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SecuredMetadataReloadTest#heldOriginalSecuredPolicyMatchesNativeAfterFourSaves` (watcher); `com.onurkat.reclazz.e2e.LastSecuredMetadataReloadTest#heldOriginalSecuredPolicyMatchesNativeAfterFourSaves` (watcher); `com.onurkat.reclazz.e2e.ModernSecuredMetadataReloadTest#heldOriginalSecuredPolicyMatchesNativeAfterFourSaves` (watcher); `com.onurkat.reclazz.e2e.ModernLastSecuredMetadataReloadTest#heldOriginalSecuredPolicyMatchesNativeAfterFourSaves` (watcher). Held startup proxy enforces original Secured authority edits and last-annotation removal/restoration; denied calls execute zero bodies.

**Control:** Fresh agentless baseline per save, marker controls and unchanged control calls; helper hook-disabled restores stale authority, metadata-flag-disabled restores removed rule on held proxy.

**Execution:** PASSED on both named main JDK17/21 lanes with Security5.7.11/6.3.4; exact commands, two-invocation receipts and source/artifact digests in JSON. Full gate and independent audit recorded separately.

**Limitation / missing environment:** Named original Secured methods/proxies and sequential saves only; no implicit JSR250 RolesAllowed/DenyAll/PermitAll, added-policy adapter, arbitrary context/proxy graph or concurrent save support. Other OS/JDK providers, framework versions, reactive/remote providers and unlisted registrations; no universal security guarantee.

**Reviewer / next action:** Astra main source/test evidence mapped; Claude independent audit pending. Independent Claude audit of final diff, raw main receipts, flag-gated negative witnesses and full gate before local commit.

### P2-SECURITY-FILTER-CHAIN

**Verdict:** PROVEN_WITH_SCOPE.

Edited web security configuration rebuilds a live filter chain and changes route authorization.

**Shipped surfaces:** `src/main/resources/META-INF/plugin.xml:52` (SHA-256 `ce19b1013776d212e490107664eda272d27455c593d1002b71f40cbc5b555809`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine17/21 on macOS arm64; Spring5.3.39/Security5.7.11 and isolated Spring6.1.14/Security6.3.4; normal/child application loader, four sequential saves. Embedded Tomcat9.0.121/10.1.60 servlet with native FilterChainProxy reached through a servlet-cached DelegatingFilterProxy; ordered overlapping chains in one local servlet fixture.

**Tests/assertions:** `com.onurkat.reclazz.e2e.HttpSecurityPolicyReloadTest#realOrderedServletRulesAndIgnoredRouteFollowFourSaves` (watcher); `com.onurkat.reclazz.e2e.ModernHttpSecurityPolicyReloadTest#realOrderedServletRulesAndIgnoredRouteFollowFourSaves` (watcher). Actual servlet status and body counters follow saved permit/deny rules; startup chain priority, ignored route, unrelated denial and application nonce retained.

**Control:** Fresh agentless servlet baseline for every save; denied requests leave body counts unchanged. Research HTTP-hook-disabled control returns stale403 for saved permit on both framework graphs.

**Execution:** PASSED on both named main JDK17/21 lanes with Security5.7.11/6.3.4; exact commands, two-invocation receipts and source/artifact digests in JSON. Full gate and independent audit recorded separately.

**Limitation / missing environment:** No native chain-count mismatch, failed rebuild, live Order edit, concurrent/inflight publication, other containers or tenant isolation. Existing HTTP rebuild receives watcher proof; no HTTP runtime repair. The static application nonce is not an independent Spring context-identity observation. Other OS/JDK providers, framework versions, reactive/remote providers and unlisted registrations; no universal security guarantee.

**Reviewer / next action:** Astra main source/test evidence mapped; Claude independent audit pending. Independent Claude audit of final diff, raw main receipts, flag-gated negative witnesses and full gate before local commit.

### P2-CACHE-RELOAD-EVICTION

**Verdict:** PROVEN_WITH_SCOPE.

Reloading an annotated class evicts existing native caches.

**Shipped surfaces:** `docs/product-reference.md:47` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine17/21 on macOS arm64; Spring5.3.39 and isolated6.1.14, H2 2.5.250, ConcurrentMapCacheManager. Plain @Bean startup CGLIB product in normal/child application loader; existing public methods and four sequential saves.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ExistingOperationMetadataReloadTest#bodyOnlySaveEvictsTheSameWarmedKey` (watcher); `com.onurkat.reclazz.e2e.ModernExistingOperationMetadataReloadTest#bodyOnlySaveEvictsTheSameWarmedKey` (watcher). Four body-only saves with unchanged Cacheable annotation evict the same warmed key; each generation executes once, then hits its new cached value. Bean identity/creation count is retained; lazy product remains cold.

**Control:** Each saved class is run in a fresh agent-less JVM before the held proxy observation; unchanged native tx/cache controls remain correct. Helper metadata disabled in scratch restores four stale-metadata failures. Same-key eviction is an already-working behavior, not a new repair.

**Execution:** PASSED on both named JDK17/21 lanes with Spring5.3.39 and6.1.14; exact focused receipts, invocations, source/artifact hashes and follow-up commands in JSON. Full gate and independent audit are separately required.

**Limitation / missing environment:** Only existing methods on the named plain factory product and local cache/DB graphs. Added class-default methods, distributed caches, arbitrary providers, concurrent saves and enhanced VMs remain separate. Other OS/JDK providers, other Spring releases/proxy shapes, remote/distributed providers and unlisted registrations; no universal application guarantee.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-SPRING6-ORDINARY-MATRIX

**Verdict:** UNVERIFIED.

Ordinary registration/factories/injection/AOP watcher behavior is established on Spring 6 too.

**Shipped surfaces:** `src/main/resources/META-INF/plugin.xml:27` (SHA-256 `ce19b1013776d212e490107664eda272d27455c593d1002b71f40cbc5b555809`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** No observing method assigned; not promoted from a located filename.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** UNVERIFIED: no observing acceptance assigned on the required lanes.

**Limitation / missing environment:** Spring6 watcher receipts here cover Jakarta lifecycle and native/composed pre-post security. They do not certify ordinary registration/factory/injection/AOP watchers or Boot3. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. A separate bounded acceptance run is needed for this cell before promotion; do not infer it from adjacent green tests.

### P2-EXISTING-TX-METADATA

**Verdict:** PROVEN_WITH_SCOPE.

Edited original-method Transactional metadata is re-read.

**Shipped surfaces:** `docs/product-reference.md:58` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine17/21 on macOS arm64; Spring5.3.39 and isolated6.1.14, H2 2.5.250, ConcurrentMapCacheManager. Plain @Bean startup CGLIB product in normal/child application loader; existing public methods and four sequential saves.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ExistingOperationMetadataReloadTest#originalTransactionRulesFollowSavesAndRealCheckedRollback` (watcher); `com.onurkat.reclazz.e2e.ModernExistingOperationMetadataReloadTest#originalTransactionRulesFollowSavesAndRealCheckedRollback` (watcher). Held startup CGLIB proxy observes readOnly changes, checked-exception rollback rule edits, removal and restoration over four saves using real H2 rows. Bean identity/creation count is retained; lazy product remains cold.

**Control:** Each saved class is run in a fresh agent-less JVM before the held proxy observation; unchanged native tx/cache controls remain correct. Helper metadata disabled in scratch restores four stale-metadata failures. Same-key eviction is an already-working behavior, not a new repair.

**Execution:** PASSED on both named JDK17/21 lanes with Spring5.3.39 and6.1.14; exact focused receipts, invocations, source/artifact hashes and follow-up commands in JSON. Full gate and independent audit are separately required.

**Limitation / missing environment:** Only existing methods on the named plain factory product and local cache/DB graphs. Added class-default methods, distributed caches, arbitrary providers, concurrent saves and enhanced VMs remain separate. Other OS/JDK providers, other Spring releases/proxy shapes, remote/distributed providers and unlisted registrations; no universal application guarantee.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-EXISTING-CACHE-METADATA

**Verdict:** PROVEN_WITH_SCOPE.

Edited original-method Cacheable metadata is re-read.

**Shipped surfaces:** `docs/product-reference.md:58` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine17/21 on macOS arm64; Spring5.3.39 and isolated6.1.14, H2 2.5.250, ConcurrentMapCacheManager. Plain @Bean startup CGLIB product in normal/child application loader; existing public methods and four sequential saves.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ExistingOperationMetadataReloadTest#originalCacheRulesFollowSavesAndRealKeysAndBodyCounts` (watcher); `com.onurkat.reclazz.e2e.ModernExistingOperationMetadataReloadTest#originalCacheRulesFollowSavesAndRealKeysAndBodyCounts` (watcher). Held startup proxy observes cache region/key/condition edits, annotation removal/restoration and actual entries/body counts over four saves. Bean identity/creation count is retained; lazy product remains cold.

**Control:** Each saved class is run in a fresh agent-less JVM before the held proxy observation; unchanged native tx/cache controls remain correct. Helper metadata disabled in scratch restores four stale-metadata failures. Same-key eviction is an already-working behavior, not a new repair.

**Execution:** PASSED on both named JDK17/21 lanes with Spring5.3.39 and6.1.14; exact focused receipts, invocations, source/artifact hashes and follow-up commands in JSON. Full gate and independent audit are separately required.

**Limitation / missing environment:** Only existing methods on the named plain factory product and local cache/DB graphs. Added class-default methods, distributed caches, arbitrary providers, concurrent saves and enhanced VMs remain separate. Other OS/JDK providers, other Spring releases/proxy shapes, remote/distributed providers and unlisted registrations; no universal application guarantee.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-TX-CLASS-DEFAULTS

**Verdict:** PARTIAL.

Added operations honor class-level transaction defaults.

**Shipped surfaces:** `docs/product-reference.md:42` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringAddedOperationsTest#classLevelTransactionMetadataAppliesToTheAddedMethod` (component): Direct bridge publication uses class transaction metadata; H2 commit returns 1:true and checked exception leaves one row.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Real transaction bridge/component observation, not a saved class-default watcher acceptance. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-CACHE-CLASS-DEFAULTS

**Verdict:** PARTIAL.

Added operations honor CacheConfig defaults and saved parameter names.

**Shipped surfaces:** `docs/product-reference.md:42` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringAddedOperationsTest#cacheConfigAndSavedParameterNamesAreAvailableToSpring` (component): Direct bridge publication returns named:1 twice under CacheConfig and saved parameter key.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Real cache bridge/component observation, not a saved class-default watcher acceptance. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-SECURITY-FILTER-CHAIN-SWAP

**Verdict:** PARTIAL.

Security chain replacement preserves the rebuilt chain identity.

**Shipped surfaces:** `docs/product-reference.md:59` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine17/21 on macOS arm64; Spring5.3.39/Security5.7.11 and isolated Spring6.1.14/Security6.3.4; normal/child application loader, four sequential saves. Embedded Tomcat9.0.121/10.1.60 servlet with native FilterChainProxy reached through a servlet-cached DelegatingFilterProxy; ordered overlapping chains in one local servlet fixture.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringSecurityReloaderSwapTest#aStaleChainIsReplacedByTheRebuiltBean` (component); `com.onurkat.reclazz.e2e.HttpSecurityPolicyReloadTest#realOrderedServletRulesAndIgnoredRouteFollowFourSaves` (watcher); `com.onurkat.reclazz.e2e.ModernHttpSecurityPolicyReloadTest#realOrderedServletRulesAndIgnoredRouteFollowFourSaves` (watcher). Actual servlet status and body counters follow saved permit/deny rules; startup chain priority, ignored route, unrelated denial and application nonce retained.

**Control:** Fresh agentless servlet baseline for every save; denied requests leave body counts unchanged. Research HTTP-hook-disabled control returns stale403 for saved permit on both framework graphs.

**Execution:** PASSED on both named main JDK17/21 lanes with Security5.7.11/6.3.4; exact commands, two-invocation receipts and source/artifact digests in JSON. Full gate and independent audit recorded separately.

**Limitation / missing environment:** No native chain-count mismatch, failed rebuild, live Order edit, concurrent/inflight publication, other containers or tenant isolation. Actual rebuilt-chain object identity inside the native delegate is not asserted; existing identity test uses component shapes, so this record stays PARTIAL. The static application nonce is not an independent Spring context-identity observation. Other OS/JDK providers, framework versions, reactive/remote providers and unlisted registrations; no universal security guarantee.

**Reviewer / next action:** Astra main source/test evidence mapped; Claude independent audit pending. Independent Claude audit of final diff, raw main receipts, flag-gated negative witnesses and full gate before local commit.

### P2-SPRING6-BEAN-CONDITION-METADATA

**Verdict:** PARTIAL.

Spring 6 added factory conditions match native startup and saved metadata.

**Shipped surfaces:** `docs/usage.md:471` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`); this block (digest in JSON).

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Isolated Spring 6.1.14 on stock SapMachine17/21 macOS arm64. Direct adapter invocation, not watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringSixAddedBeanConditionsTest#directConditionsMatchNativeStartupAndReceiveRealMethodMetadata` (component): Direct reload compares off/on admission and alias presence to native startup; condition called once in registration phase.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** No general Spring 6 registration/factory watcher or Boot 3 proof inferred. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

## P3a: Adversarial HTTP validation and overlapping requests

This nine-cell hardening slice does not complete P3 MVC/data/callback evidence. Native servlet/provider observations and disabled-hook controls replace inference from cache-shape unit tests. [Machine receipts](feature-evidence/p3a-web-hardening.json) record the exact scope, sources, runtime/artifact hashes and failures.

### P3A-VALIDATION-ADD

**Verdict:** PROVEN_WITH_SCOPE.

An existing DTO field gaining NotBlank/Size rejects invalid HTTP input.

**Tests:** `com.onurkat.reclazz.e2e.ValidationConstraintReloadTest#warmedNativeConstraintsFollowSixSavesBeforeControllerSideEffects`; `com.onurkat.reclazz.e2e.JakartaValidationConstraintReloadTest#warmedNativeConstraintsFollowSixSavesBeforeControllerSideEffects`

**Default/opt-in:** Startup javaagent and compiled annotation edits on existing DTO fields; no extra validation flag.

**Bounds:** Stock SapMachine17/21, macOS arm64; normal/child loaders; Spring5.3.39/Tomcat9.0.121 and Spring6.1.14/Tomcat10.1.60. Native HV6.2.5/HV8.0.2 on existing DTO fields; six sequential compiled annotation edits.

**Controls / limits:** Warm native MVC and held-validator caches; unchanged DTO Min constraint, held old DTO, exact controller body counter, malformed JSON rejection and unchanged nonce. Disabling the corresponding hook makes the whole observing test RED at its first failing assertion; this is not an independent mutation witness for every later assertion. Bounded P3a slice, not all P3 or universal program safety. No throughput claim, field-initializer concurrency, arbitrary async tasks or unlisted validation providers. Other OS/providers, enhanced JVMs, Boot variants and custom validation/async infrastructure unverified.

**Reviewer / next action:** Astra built/read actual observing assertions; independent Claude audit pending. Independent audit/full gate and local-only commit after actual matrix and negative witnesses.

### P3A-VALIDATION-CHANGE

**Verdict:** PROVEN_WITH_SCOPE.

A changed Size minimum is enforced after warmed native metadata.

**Tests:** `com.onurkat.reclazz.e2e.ValidationConstraintReloadTest#warmedNativeConstraintsFollowSixSavesBeforeControllerSideEffects`; `com.onurkat.reclazz.e2e.JakartaValidationConstraintReloadTest#warmedNativeConstraintsFollowSixSavesBeforeControllerSideEffects`

**Default/opt-in:** Startup javaagent and compiled annotation edits on existing DTO fields; no extra validation flag.

**Bounds:** Stock SapMachine17/21, macOS arm64; normal/child loaders; Spring5.3.39/Tomcat9.0.121 and Spring6.1.14/Tomcat10.1.60. Native HV6.2.5/HV8.0.2 on existing DTO fields; six sequential compiled annotation edits.

**Controls / limits:** Warm native MVC and held-validator caches; unchanged DTO Min constraint, held old DTO, exact controller body counter, malformed JSON rejection and unchanged nonce. Disabling the corresponding hook makes the whole observing test RED at its first failing assertion; this is not an independent mutation witness for every later assertion. Bounded P3a slice, not all P3 or universal program safety. No throughput claim, field-initializer concurrency, arbitrary async tasks or unlisted validation providers. Other OS/providers, enhanced JVMs, Boot variants and custom validation/async infrastructure unverified.

**Reviewer / next action:** Astra built/read actual observing assertions; independent Claude audit pending. Independent audit/full gate and local-only commit after actual matrix and negative witnesses.

### P3A-VALIDATION-REMOVE-RESTORE

**Verdict:** PROVEN_WITH_SCOPE.

Removing and restoring constraints changes acceptance without restarting.

**Tests:** `com.onurkat.reclazz.e2e.ValidationConstraintReloadTest#warmedNativeConstraintsFollowSixSavesBeforeControllerSideEffects`; `com.onurkat.reclazz.e2e.JakartaValidationConstraintReloadTest#warmedNativeConstraintsFollowSixSavesBeforeControllerSideEffects`

**Default/opt-in:** Startup javaagent and compiled annotation edits on existing DTO fields; no extra validation flag.

**Bounds:** Stock SapMachine17/21, macOS arm64; normal/child loaders; Spring5.3.39/Tomcat9.0.121 and Spring6.1.14/Tomcat10.1.60. Native HV6.2.5/HV8.0.2 on existing DTO fields; six sequential compiled annotation edits.

**Controls / limits:** Warm native MVC and held-validator caches; unchanged DTO Min constraint, held old DTO, exact controller body counter, malformed JSON rejection and unchanged nonce. Disabling the corresponding hook makes the whole observing test RED at its first failing assertion; this is not an independent mutation witness for every later assertion. Bounded P3a slice, not all P3 or universal program safety. No throughput claim, field-initializer concurrency, arbitrary async tasks or unlisted validation providers. Other OS/providers, enhanced JVMs, Boot variants and custom validation/async infrastructure unverified.

**Reviewer / next action:** Astra built/read actual observing assertions; independent Claude audit pending. Independent audit/full gate and local-only commit after actual matrix and negative witnesses.

### P3A-VALIDATION-HELD

**Verdict:** PROVEN_WITH_SCOPE.

A validator reference and DTO instance captured before edits use current constraints.

**Tests:** `com.onurkat.reclazz.e2e.ValidationConstraintReloadTest#warmedNativeConstraintsFollowSixSavesBeforeControllerSideEffects`; `com.onurkat.reclazz.e2e.JakartaValidationConstraintReloadTest#warmedNativeConstraintsFollowSixSavesBeforeControllerSideEffects`

**Default/opt-in:** Startup javaagent and compiled annotation edits on existing DTO fields; no extra validation flag.

**Bounds:** Stock SapMachine17/21, macOS arm64; normal/child loaders; Spring5.3.39/Tomcat9.0.121 and Spring6.1.14/Tomcat10.1.60. Native HV6.2.5/HV8.0.2 on existing DTO fields; six sequential compiled annotation edits.

**Controls / limits:** Warm native MVC and held-validator caches; unchanged DTO Min constraint, held old DTO, exact controller body counter, malformed JSON rejection and unchanged nonce. Disabling the corresponding hook makes the whole observing test RED at its first failing assertion; this is not an independent mutation witness for every later assertion. Bounded P3a slice, not all P3 or universal program safety. No throughput claim, field-initializer concurrency, arbitrary async tasks or unlisted validation providers. Other OS/providers, enhanced JVMs, Boot variants and custom validation/async infrastructure unverified.

**Reviewer / next action:** Astra built/read actual observing assertions; independent Claude audit pending. Independent audit/full gate and local-only commit after actual matrix and negative witnesses.

### P3A-VALIDATION-SIDE-EFFECTS

**Verdict:** PROVEN_WITH_SCOPE.

Rejected validation/malformed JSON never executes the controller body.

**Tests:** `com.onurkat.reclazz.e2e.ValidationConstraintReloadTest#warmedNativeConstraintsFollowSixSavesBeforeControllerSideEffects`; `com.onurkat.reclazz.e2e.JakartaValidationConstraintReloadTest#warmedNativeConstraintsFollowSixSavesBeforeControllerSideEffects`

**Default/opt-in:** Startup javaagent and compiled annotation edits on existing DTO fields; no extra validation flag.

**Bounds:** Stock SapMachine17/21, macOS arm64; normal/child loaders; Spring5.3.39/Tomcat9.0.121 and Spring6.1.14/Tomcat10.1.60. Native HV6.2.5/HV8.0.2 on existing DTO fields; six sequential compiled annotation edits.

**Controls / limits:** Warm native MVC and held-validator caches; unchanged DTO Min constraint, held old DTO, exact controller body counter, malformed JSON rejection and unchanged nonce. Disabling the corresponding hook makes the whole observing test RED at its first failing assertion; this is not an independent mutation witness for every later assertion. Bounded P3a slice, not all P3 or universal program safety. No throughput claim, field-initializer concurrency, arbitrary async tasks or unlisted validation providers. Other OS/providers, enhanced JVMs, Boot variants and custom validation/async infrastructure unverified.

**Reviewer / next action:** Astra built/read actual observing assertions; independent Claude audit pending. Independent audit/full gate and local-only commit after actual matrix and negative witnesses.

### P3A-VALIDATION-CONTROL

**Verdict:** PROVEN_WITH_SCOPE.

Unedited DTO constraints and context identity remain correct across saves.

**Tests:** `com.onurkat.reclazz.e2e.ValidationConstraintReloadTest#warmedNativeConstraintsFollowSixSavesBeforeControllerSideEffects`; `com.onurkat.reclazz.e2e.JakartaValidationConstraintReloadTest#warmedNativeConstraintsFollowSixSavesBeforeControllerSideEffects`

**Default/opt-in:** Startup javaagent and compiled annotation edits on existing DTO fields; no extra validation flag.

**Bounds:** Stock SapMachine17/21, macOS arm64; normal/child loaders; Spring5.3.39/Tomcat9.0.121 and Spring6.1.14/Tomcat10.1.60. Native HV6.2.5/HV8.0.2 on existing DTO fields; six sequential compiled annotation edits.

**Controls / limits:** Warm native MVC and held-validator caches; unchanged DTO Min constraint, held old DTO, exact controller body counter, malformed JSON rejection and unchanged nonce. Disabling the corresponding hook makes the whole observing test RED at its first failing assertion; this is not an independent mutation witness for every later assertion. Bounded P3a slice, not all P3 or universal program safety. No throughput claim, field-initializer concurrency, arbitrary async tasks or unlisted validation providers. Other OS/providers, enhanced JVMs, Boot variants and custom validation/async infrastructure unverified.

**Reviewer / next action:** Astra built/read actual observing assertions; independent Claude audit pending. Independent audit/full gate and local-only commit after actual matrix and negative witnesses.

### P3A-REQUEST-COMPLETE

**Verdict:** PROVEN_WITH_SCOPE.

Completing one request cannot release a second held worker’s code.

**Tests:** `com.onurkat.reclazz.e2e.OverlappingMvcRequestBoundaryReloadTest#oneCompletedRequestCannotReleaseAnotherWorkersCode`; `com.onurkat.reclazz.e2e.JakartaOverlappingMvcRequestBoundaryReloadTest#oneCompletedRequestCannotReleaseAnotherWorkersCode`

**Default/opt-in:** Startup javaagent with reloadBoundary=request.

**Bounds:** Stock SapMachine17/21, macOS arm64; normal/child loaders; Spring5.3.39/Tomcat9.0.121 and Spring6.1.14/Tomcat10.1.60. Two held native Callable workers, one optionally wrapped in WebAsyncTask with a 500ms timeout; two sequential compiled saves.

**Controls / limits:** Warm 1:1 baseline, each worker logs 1:1, native HTTP status/body, unchanged nonce; fast request remains 1:1 while either worker owns old code, then eventually 3:3. Disabling the corresponding hook makes the whole observing test RED at its first failing assertion; this is not an independent mutation witness for every later assertion. Bounded P3a slice, not all P3 or universal program safety. No throughput claim, field-initializer concurrency, arbitrary async tasks or unlisted validation providers. Other OS/providers, enhanced JVMs, Boot variants and custom validation/async infrastructure unverified.

**Reviewer / next action:** Astra built/read actual observing assertions; independent Claude audit pending. Independent audit/full gate and local-only commit after actual matrix and negative witnesses.

### P3A-REQUEST-TIMEOUT

**Verdict:** PROVEN_WITH_SCOPE.

A timeout503 and another completed request cannot release an interrupt-ignoring worker’s code.

**Tests:** `com.onurkat.reclazz.e2e.OverlappingMvcRequestBoundaryReloadTest#timedOutWorkerCannotReleaseAnotherWorkersCode`; `com.onurkat.reclazz.e2e.JakartaOverlappingMvcRequestBoundaryReloadTest#timedOutWorkerCannotReleaseAnotherWorkersCode`

**Default/opt-in:** Startup javaagent with reloadBoundary=request.

**Bounds:** Stock SapMachine17/21, macOS arm64; normal/child loaders; Spring5.3.39/Tomcat9.0.121 and Spring6.1.14/Tomcat10.1.60. Two held native Callable workers, one optionally wrapped in WebAsyncTask with a 500ms timeout; two sequential compiled saves.

**Controls / limits:** Warm 1:1 baseline, each worker logs 1:1, native HTTP status/body, unchanged nonce; fast request remains 1:1 while either worker owns old code, then eventually 3:3. Disabling the corresponding hook makes the whole observing test RED at its first failing assertion; this is not an independent mutation witness for every later assertion. Bounded P3a slice, not all P3 or universal program safety. No throughput claim, field-initializer concurrency, arbitrary async tasks or unlisted validation providers. Other OS/providers, enhanced JVMs, Boot variants and custom validation/async infrastructure unverified.

**Reviewer / next action:** Astra built/read actual observing assertions; independent Claude audit pending. Independent audit/full gate and local-only commit after actual matrix and negative witnesses.

### P3A-REQUEST-LATEST

**Verdict:** PROVEN_WITH_SCOPE.

Overlapping requests drain on old consistent code; two queued saves eventually serve latest consistent code.

**Tests:** `com.onurkat.reclazz.e2e.OverlappingMvcRequestBoundaryReloadTest#oneCompletedRequestCannotReleaseAnotherWorkersCode`; `com.onurkat.reclazz.e2e.JakartaOverlappingMvcRequestBoundaryReloadTest#oneCompletedRequestCannotReleaseAnotherWorkersCode`; `com.onurkat.reclazz.e2e.OverlappingMvcRequestBoundaryReloadTest#timedOutWorkerCannotReleaseAnotherWorkersCode`; `com.onurkat.reclazz.e2e.JakartaOverlappingMvcRequestBoundaryReloadTest#timedOutWorkerCannotReleaseAnotherWorkersCode`

**Default/opt-in:** Startup javaagent with reloadBoundary=request.

**Bounds:** Stock SapMachine17/21, macOS arm64; normal/child loaders; Spring5.3.39/Tomcat9.0.121 and Spring6.1.14/Tomcat10.1.60. Two held native Callable workers, one optionally wrapped in WebAsyncTask with a 500ms timeout; two sequential compiled saves.

**Controls / limits:** Warm 1:1 baseline, each worker logs 1:1, native HTTP status/body, unchanged nonce; fast request remains 1:1 while either worker owns old code, then eventually 3:3. Disabling the corresponding hook makes the whole observing test RED at its first failing assertion; this is not an independent mutation witness for every later assertion. Bounded P3a slice, not all P3 or universal program safety. No throughput claim, field-initializer concurrency, arbitrary async tasks or unlisted validation providers. Other OS/providers, enhanced JVMs, Boot variants and custom validation/async infrastructure unverified.

**Reviewer / next action:** Astra built/read actual observing assertions; independent Claude audit pending. Independent audit/full gate and local-only commit after actual matrix and negative witnesses.

## P3b: Remaining MVC request/response evidence

This package classifies the measured MVC slice and explicitly records missing variants. It does not complete P3c callbacks/data or every repository promise. Native Tomcat HTTP, HTTP-to-MockMvc bridges, direct ObjectMapper HTTP and component-only observations are distinct. Passing limitation characterizations do not mean feature success. Scratch negative witnesses run on SapMachine21 only. Whole-hook negative witnesses stop at the first failing assertion; they are not separate mutations for every later assertion or every component-only row. [Machine evidence](feature-evidence/p3b-mvc-web.json) contains the exact methods, invocation receipts, runtime/artifact/source hashes and controls. The registered interceptor gap has separate bounded singleton-routing repair evidence below; the original limitation remains historical. Spring6.1.14 added exception handlers have separate constructor-Function repair evidence below; the original FAILED observation remains historical.

### P3B-ENDPOINT-GET

**Verdict:** PROVEN_WITH_SCOPE. An added GET endpoint follows add/edit/remove/restore/unannotate across five saves.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Spring5.3.39/Tomcat9.0.121 and Spring6.1.14/Tomcat10.1.60; normal/child loaders; native DispatcherServlet; class-level prefix and explicit argument names. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.e2e.AddedEndpointHttpReloadTest#getAndPostEndpointsFollowFiveSavesWithoutRestart` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedEndpointHttpReloadTest#getAndPostEndpointsFollowFiveSavesWithoutRestart` (watcher)

**Controls / remaining limits:** Pre-edit404; existing route and native context nonce; wrong verb405. Disabled endpoint adapter must make whole observer RED. Only the named observers and versions. In-flight metadata publication is not atomic; other OS/JVMs and unlisted deployments are unverified. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-ENDPOINT-POST

**Verdict:** PROVEN_WITH_SCOPE. An added POST endpoint follows add/edit/remove/restore/unannotate across five saves.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Spring5.3.39/Tomcat9.0.121 and Spring6.1.14/Tomcat10.1.60; normal/child loaders; native DispatcherServlet; class-level prefix and explicit argument names. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.e2e.AddedEndpointHttpReloadTest#getAndPostEndpointsFollowFiveSavesWithoutRestart` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedEndpointHttpReloadTest#getAndPostEndpointsFollowFiveSavesWithoutRestart` (watcher)

**Controls / remaining limits:** Pre-edit404; existing route and native context nonce; wrong verb405. Disabled endpoint adapter must make whole observer RED. Only the named observers and versions. In-flight metadata publication is not atomic; other OS/JVMs and unlisted deployments are unverified. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-EXCEPTION-LOCAL

**Verdict:** PROVEN_WITH_SCOPE. Added local exception handler changes HTTP status/body, then edit/remove/restore/unannotate changes selection.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Spring5.3.39; normal/child loaders; loopback HttpServer -> MockMvc/DispatcherServlet (not native Tomcat). Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.e2e.AddedExceptionHandlerReloadTest#addedLocalHandlerCompetesWithExistingHandlersAndFollowsSaves` (watcher); `com.onurkat.reclazz.e2e.AddedExceptionHandlerReloadTest#localHandlersOnAddedEndpointsWorkAcrossChildLoaderSaves` (watcher)

**Controls / remaining limits:** Warm fallback; other controller still400, existing local still409. Disable exception transformer: whole observer RED. Spring6.1.14 has separate repair evidence below. Native servlet deployment unverified. Exact metadata-class identity, proxies and extra advice outside scope. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-EXCEPTION-ADVICE

**Verdict:** PROVEN_WITH_SCOPE. Added controller-advice exception handler changes HTTP status/body, then edit/remove/restore/unannotate changes selection.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Spring5.3.39; normal/child loaders; loopback HttpServer -> MockMvc/DispatcherServlet (not native Tomcat). Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.e2e.AddedExceptionHandlerReloadTest#addedAdviceHandlerKeepsSelectorsLocalPriorityAndFollowsSaves` (watcher); `com.onurkat.reclazz.e2e.AddedExceptionHandlerReloadTest#adviceHandlersOnAddedEndpointsWorkAcrossChildLoaderSaves` (watcher)

**Controls / remaining limits:** Warm fallback; other controller still400, existing local still409. Disable exception transformer: whole observer RED. Spring6.1.14 has separate repair evidence below. Native servlet deployment unverified. Exact metadata-class identity, proxies and extra advice outside scope. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-EXCEPTION-MODERN

**Verdict:** PROVEN_WITH_SCOPE. Spring6.1.14 added local/advice exception handlers change HTTP status/body across five saves after the constructor-Function repair.

**Bounds / opt-in:** SapMachine17/21, macOS arm64; normal/child loaders; loopback HTTP through MockMvc/DispatcherServlet. Startup -javaagent before MVC loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.e2e.JakartaAddedExceptionHandlerReloadTest#addedLocalHandlerCompetesWithExistingHandlersAndFollowsSaves`, `#addedAdviceHandlerKeepsSelectorsLocalPriorityAndFollowsSaves`, `#localHandlersOnAddedEndpointsWorkAcrossChildLoaderSaves`, `#adviceHandlersOnAddedEndpointsWorkAcrossChildLoaderSaves` (watcher).

**Controls / remaining limits:** Desired HTTP tests failed before repair: expected422 versus local409/advice400 in all four scenarios. After repair, exact422/423/fallback/425/fallback responses on old/new endpoints; unrelated controller400 and existing local409 remain. Unknown constructor-Function shapes refuse the entire hook. Native servlet-container exception-handler deployment, late attach, proxies, exact metadata-class identity and other versions remain unverified/outside scope. Independent audit pending.

**Historical failure:** P3b at326d1c4 measured a FAILED Spring6 hook (direct sites1/2/1); its GREEN limitation tests proved unchanged fallbacks, not support. The original claim, source hashes and lane observations remain in `history_before_spring6_factory_repair` in the machine registry. This follow-up supersedes that current verdict without changing the historical result.

### P3B-BINDER-LOCAL

**Verdict:** PROVEN_WITH_SCOPE. Added named InitBinder on local receiver changes HTTP binding/model across five saves.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Spring5.3.39/Spring6.1.14; normal/child loaders; loopback HttpServer -> MockMvc/DispatcherServlet (not deployed servlet container). Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.e2e.AddedMvcBindingReloadTest#localBindingAndModelMethodsFollowFiveSavesOnOldAndNewEndpoints` (watcher); `com.onurkat.reclazz.e2e.AddedMvcBindingReloadTest#localBindingAndModelMethodsWorkAcrossChildLoaderSaves` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedMvcBindingReloadTest#localBindingAndModelMethodsFollowFiveSavesOnOldAndNewEndpoints` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedMvcBindingReloadTest#localBindingAndModelMethodsWorkAcrossChildLoaderSaves` (watcher)

**Controls / remaining limits:** Warm global binder3; existing/new routes; unrelated parameter not multiplied; untouched other controller. Disable binding transformer -> whole observer RED. Plain Object receivers; explicit annotation names, private restoration. Void/generic model and refusal variants are separate component-only cells. Native servlet-container binding/model deployment unverified. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-BINDER-ADVICE

**Verdict:** PROVEN_WITH_SCOPE. Added named InitBinder on advice receiver changes HTTP binding/model across five saves.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Spring5.3.39/Spring6.1.14; normal/child loaders; loopback HttpServer -> MockMvc/DispatcherServlet (not deployed servlet container). Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.e2e.AddedMvcBindingReloadTest#adviceBindingAndModelMethodsFollowFiveSavesAndRespectSelectors` (watcher); `com.onurkat.reclazz.e2e.AddedMvcBindingReloadTest#adviceBindingAndModelMethodsWorkAcrossChildLoaderSaves` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedMvcBindingReloadTest#adviceBindingAndModelMethodsFollowFiveSavesAndRespectSelectors` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedMvcBindingReloadTest#adviceBindingAndModelMethodsWorkAcrossChildLoaderSaves` (watcher)

**Controls / remaining limits:** Warm global binder3; existing/new routes; unrelated parameter not multiplied; untouched other controller. Disable binding transformer -> whole observer RED. Plain Object receivers; explicit annotation names, private restoration. Void/generic model and refusal variants are separate component-only cells. Native servlet-container binding/model deployment unverified. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-MODEL-LOCAL

**Verdict:** PROVEN_WITH_SCOPE. Added ModelAttribute initializer on local receiver changes HTTP binding/model across five saves.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Spring5.3.39/Spring6.1.14; normal/child loaders; loopback HttpServer -> MockMvc/DispatcherServlet (not deployed servlet container). Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.e2e.AddedMvcBindingReloadTest#localBindingAndModelMethodsFollowFiveSavesOnOldAndNewEndpoints` (watcher); `com.onurkat.reclazz.e2e.AddedMvcBindingReloadTest#localBindingAndModelMethodsWorkAcrossChildLoaderSaves` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedMvcBindingReloadTest#localBindingAndModelMethodsFollowFiveSavesOnOldAndNewEndpoints` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedMvcBindingReloadTest#localBindingAndModelMethodsWorkAcrossChildLoaderSaves` (watcher)

**Controls / remaining limits:** Warm global binder3; existing/new routes; unrelated parameter not multiplied; untouched other controller. Disable binding transformer -> whole observer RED. Plain Object receivers; explicit annotation names, private restoration. Void/generic model and refusal variants are separate component-only cells. Native servlet-container binding/model deployment unverified. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-MODEL-ADVICE

**Verdict:** PROVEN_WITH_SCOPE. Added ModelAttribute initializer on advice receiver changes HTTP binding/model across five saves.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Spring5.3.39/Spring6.1.14; normal/child loaders; loopback HttpServer -> MockMvc/DispatcherServlet (not deployed servlet container). Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.e2e.AddedMvcBindingReloadTest#adviceBindingAndModelMethodsFollowFiveSavesAndRespectSelectors` (watcher); `com.onurkat.reclazz.e2e.AddedMvcBindingReloadTest#adviceBindingAndModelMethodsWorkAcrossChildLoaderSaves` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedMvcBindingReloadTest#adviceBindingAndModelMethodsFollowFiveSavesAndRespectSelectors` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedMvcBindingReloadTest#adviceBindingAndModelMethodsWorkAcrossChildLoaderSaves` (watcher)

**Controls / remaining limits:** Warm global binder3; existing/new routes; unrelated parameter not multiplied; untouched other controller. Disable binding transformer -> whole observer RED. Plain Object receivers; explicit annotation names, private restoration. Void/generic model and refusal variants are separate component-only cells. Native servlet-container binding/model deployment unverified. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-JACKSON-IN

**Verdict:** PROVEN_WITH_SCOPE. Added setters/fields populate both new and held mutable DTOs from HTTP JSON.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Jackson2.22.3, Spring5.3.39/Spring6.1.14 GenericApplicationContext; direct ObjectMapper via loopback HttpServer, not MVC conversion; property fixture normal/child; getter fixture normal loader. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.e2e.AddedJacksonPropertyReloadTest#addedSettersReadJsonAcrossSaves` (watcher); `com.onurkat.reclazz.e2e.AddedJacksonPropertyReloadTest#addedFieldsReadAndWriteJsonAcrossSaves` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedJacksonPropertyReloadTest#addedSettersReadJsonAcrossSaves` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedJacksonPropertyReloadTest#addedFieldsReadAndWriteJsonAcrossSaves` (watcher)

**Controls / remaining limits:** Warmed mappers and old DTO, existing value, original reflection; disabled Jackson accessor transformer must make central observers RED. Historical2.13.5 receipts are distinct. Jackson3/Afterburner, creator/builder additions, unmanaged caches and retained readers/writers are outside these watcher observations. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-JACKSON-OUT

**Verdict:** PROVEN_WITH_SCOPE. Added getters/fields appear in fresh mapper output with current saved metadata.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Jackson2.22.3, Spring5.3.39/Spring6.1.14 GenericApplicationContext; direct ObjectMapper via loopback HttpServer, not MVC conversion; property fixture normal/child; getter fixture normal loader. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.e2e.AddedJacksonPropertyReloadTest#addedSettersReadJsonAcrossSaves` (watcher); `com.onurkat.reclazz.e2e.AddedJacksonPropertyReloadTest#addedFieldsReadAndWriteJsonAcrossSaves` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedJacksonPropertyReloadTest#addedSettersReadJsonAcrossSaves` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedJacksonPropertyReloadTest#addedFieldsReadAndWriteJsonAcrossSaves` (watcher); `com.onurkat.reclazz.e2e.AddedJacksonGetterReloadTest#addedGettersReachHttpWithMapperPoliciesAcrossSaves` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedJacksonGetterReloadTest#addedGettersReachHttpWithMapperPoliciesAcrossSaves` (watcher)

**Controls / remaining limits:** Warmed mappers and old DTO, existing value, original reflection; disabled Jackson accessor transformer must make central observers RED. Historical2.13.5 receipts are distinct. Jackson3/Afterburner, creator/builder additions, unmanaged caches and retained readers/writers are outside these watcher observations. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-JACKSON-NAMING

**Verdict:** PROVEN_WITH_SCOPE. Fresh mapper discovery honors saved names and aliases across rename/removal/restoration.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Jackson2.22.3, Spring5.3.39/Spring6.1.14 GenericApplicationContext; direct ObjectMapper via loopback HttpServer, not MVC conversion; property fixture normal/child; getter fixture normal loader. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.e2e.AddedJacksonPropertyReloadTest#addedSettersReadJsonAcrossSaves` (watcher); `com.onurkat.reclazz.e2e.AddedJacksonPropertyReloadTest#addedFieldsReadAndWriteJsonAcrossSaves` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedJacksonPropertyReloadTest#addedSettersReadJsonAcrossSaves` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedJacksonPropertyReloadTest#addedFieldsReadAndWriteJsonAcrossSaves` (watcher); `com.onurkat.reclazz.e2e.AddedJacksonGetterReloadTest#addedGettersReachHttpWithMapperPoliciesAcrossSaves` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedJacksonGetterReloadTest#addedGettersReachHttpWithMapperPoliciesAcrossSaves` (watcher)

**Controls / remaining limits:** Warmed mappers and old DTO, existing value, original reflection; disabled Jackson accessor transformer must make central observers RED. Historical2.13.5 receipts are distinct. Jackson3/Afterburner, creator/builder additions, unmanaged caches and retained readers/writers are outside these watcher observations. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-JACKSON-INCLUSION

**Verdict:** PROVEN_WITH_SCOPE. HTTP output retains measured ignore, null and empty inclusion policies.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Jackson2.22.3, Spring5.3.39/Spring6.1.14 GenericApplicationContext; direct ObjectMapper via loopback HttpServer, not MVC conversion; property fixture normal/child; getter fixture normal loader. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.e2e.AddedJacksonGetterReloadTest#addedGettersReachHttpWithMapperPoliciesAcrossSaves` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedJacksonGetterReloadTest#addedGettersReachHttpWithMapperPoliciesAcrossSaves` (watcher)

**Controls / remaining limits:** Warmed mappers and old DTO, existing value, original reflection; disabled Jackson accessor transformer must make central observers RED. Historical2.13.5 receipts are distinct. Jackson3/Afterburner, creator/builder additions, unmanaged caches and retained readers/writers are outside these watcher observations. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-JACKSON-CUSTOM

**Verdict:** PROVEN_WITH_SCOPE. Custom property/DTO serializers and deserializers remain in control in the HTTP fixtures.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Jackson2.22.3, Spring5.3.39/Spring6.1.14 GenericApplicationContext; direct ObjectMapper via loopback HttpServer, not MVC conversion; property fixture normal/child; getter fixture normal loader. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.e2e.AddedJacksonPropertyReloadTest#addedSettersReadJsonAcrossSaves` (watcher); `com.onurkat.reclazz.e2e.AddedJacksonPropertyReloadTest#addedFieldsReadAndWriteJsonAcrossSaves` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedJacksonPropertyReloadTest#addedSettersReadJsonAcrossSaves` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedJacksonPropertyReloadTest#addedFieldsReadAndWriteJsonAcrossSaves` (watcher); `com.onurkat.reclazz.e2e.AddedJacksonGetterReloadTest#addedGettersReachHttpWithMapperPoliciesAcrossSaves` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedJacksonGetterReloadTest#addedGettersReachHttpWithMapperPoliciesAcrossSaves` (watcher)

**Controls / remaining limits:** Warmed mappers and old DTO, existing value, original reflection; disabled Jackson accessor transformer must make central observers RED. Historical2.13.5 receipts are distinct. Jackson3/Afterburner, creator/builder additions, unmanaged caches and retained readers/writers are outside these watcher observations. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-ASYNC-CALLABLE

**Verdict:** PROVEN_WITH_SCOPE. A held Callable response retains old code through its native completion.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Spring5.3.39/Tomcat9.0.121 and Spring6.1.14/Tomcat10.1.60; Callable/Deferred CSV normal/child; timeout/immediate normal only. reloadBoundary=request except explicit default immediate-mode control; startup javaagent.

**Observers:** `com.onurkat.reclazz.e2e.AsyncMvcRequestBoundaryReloadTest#heldAsyncResponseKeepsItsCodeUntilNativeCompletion` (watcher); `com.onurkat.reclazz.e2e.JakartaAsyncMvcRequestBoundaryReloadTest#heldAsyncResponseKeepsItsCodeUntilNativeCompletion` (watcher)

**Controls / remaining limits:** Actual worker and HTTP response; fast1:1 while pending then2:2 after exit; precompleted DeferredResult remains instant. Disable request boundary -> held observer RED; immediate control remains GREEN. Controlled one-worker schedules, not arbitrary tasks, throughput or overlapping claims; P3a contains separate overlap evidence. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-ASYNC-DEFERRED

**Verdict:** PROVEN_WITH_SCOPE. A held DeferredResult response retains the request boundary until native completion.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Spring5.3.39/Tomcat9.0.121 and Spring6.1.14/Tomcat10.1.60; Callable/Deferred CSV normal/child; timeout/immediate normal only. reloadBoundary=request except explicit default immediate-mode control; startup javaagent.

**Observers:** `com.onurkat.reclazz.e2e.AsyncMvcRequestBoundaryReloadTest#heldAsyncResponseKeepsItsCodeUntilNativeCompletion` (watcher); `com.onurkat.reclazz.e2e.JakartaAsyncMvcRequestBoundaryReloadTest#heldAsyncResponseKeepsItsCodeUntilNativeCompletion` (watcher)

**Controls / remaining limits:** Actual worker and HTTP response; fast1:1 while pending then2:2 after exit; precompleted DeferredResult remains instant. Disable request boundary -> held observer RED; immediate control remains GREEN. Controlled one-worker schedules, not arbitrary tasks, throughput or overlapping claims; P3a contains separate overlap evidence. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-ASYNC-TIMEOUT

**Verdict:** PROVEN_WITH_SCOPE. A timed-out WebAsyncTask503 does not release an interrupt-ignoring Callable worker before exit.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Spring5.3.39/Tomcat9.0.121 and Spring6.1.14/Tomcat10.1.60; Callable/Deferred CSV normal/child; timeout/immediate normal only. reloadBoundary=request except explicit default immediate-mode control; startup javaagent.

**Observers:** `com.onurkat.reclazz.e2e.AsyncMvcRequestBoundaryReloadTest#timedOutCallableThatIgnoresCancellationStillOwnsItsCode` (watcher); `com.onurkat.reclazz.e2e.JakartaAsyncMvcRequestBoundaryReloadTest#timedOutCallableThatIgnoresCancellationStillOwnsItsCode` (watcher)

**Controls / remaining limits:** Actual worker and HTTP response; fast1:1 while pending then2:2 after exit; precompleted DeferredResult remains instant. Disable request boundary -> held observer RED; immediate control remains GREEN. Controlled one-worker schedules, not arbitrary tasks, throughput or overlapping claims; P3a contains separate overlap evidence. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-ASYNC-IMMEDIATE

**Verdict:** EXPECTED. The default immediate mode may change the second method call of a held worker.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Spring5.3.39/Tomcat9.0.121 and Spring6.1.14/Tomcat10.1.60; Callable/Deferred CSV normal/child; timeout/immediate normal only. reloadBoundary=request except explicit default immediate-mode control; startup javaagent.

**Observers:** `com.onurkat.reclazz.e2e.AsyncMvcRequestBoundaryReloadTest#immediateModeStillReloadsWhileTheResponseIsPending` (watcher); `com.onurkat.reclazz.e2e.JakartaAsyncMvcRequestBoundaryReloadTest#immediateModeStillReloadsWhileTheResponseIsPending` (watcher)

**Controls / remaining limits:** Actual worker and HTTP response; fast1:1 while pending then2:2 after exit; precompleted DeferredResult remains instant. Disable request boundary -> held observer RED; immediate control remains GREEN. Controlled one-worker schedules, not arbitrary tasks, throughput or overlapping claims; P3a contains separate overlap evidence. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-INTERCEPTOR-LIMIT

**Verdict:** PROVEN_WITH_SCOPE. Named plain MVC singleton selection follows current native bean identities and dependency constructor state after watched class saves. The historical cell ID is retained.

**Bounds / opt-in:** SapMachine17/21, macOS arm64, Spring5.3.39/6.1.14. Normal/child loaders, parent/web contexts, lookup/mapped/injected registrations; file-triggered MockMvc requests. Startup javaagent before MVC loads, compiled watched saves.

**Observers:** `com.onurkat.reclazz.e2e.MvcInterceptorDependencyReloadTest#requestsUseCurrentInterceptorsAndMappingsAfterParentRecreation`; `com.onurkat.reclazz.e2e.JakartaMvcInterceptorDependencyReloadTest#requestsUseCurrentInterceptorsAndMappingsAfterParentRecreation` (watcher; six invocations each).

**Controls / remaining limits:** Initial/source-only controls pass. Before production repair all6 desired ordinary cases RED at changed interceptor identity; injected cases also show stale servlet mapping. After repair repeated saves use constructorD2/D3/D4, current dispatched interceptor and servlet mapping view, exact native callback order, stable unrelated beans and mapped include/exclude behavior. Named local plain singletons only; no prototype/scoped/FactoryBean/proxy/unowned object-graph repair, filters/security policies, late attach, native servlet deployment or atomic concurrent publication. Independent audit pending.

**Historical limitation:** At dd90638 the passing characterization recorded stale dependencyD1 and the servlet/context split. Original claim, source hashes and observing lane receipts remain in `history_before_mvc_singleton_repair`; current desired behavior supersedes that verdict without editing history. Weak identity, native schema/refusal and real lifecycle/ownership tests are supporting components, not additional watcher claims.

### P3B-MODEL-VOID

**Verdict:** PARTIAL. Void model initializer writes into Spring Model.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedMvcBindingMethodsTest#voidModelMethodWritesIntoSpringsActualModel` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-MODEL-GENERIC

**Verdict:** PARTIAL. Concrete generic model return metadata and value are retained.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedMvcBindingMethodsTest#concreteGenericReturnMetadataAndModelValueArePreserved` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-EXCEPTION-CAUSE

**Verdict:** PARTIAL. Native exception resolver selects the cause argument.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedExceptionHandlersTest#causeMatchingUsesSpringsResolverAndExposesTheActualCauseArgument` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-EXCEPTION-INFER

**Verdict:** PARTIAL. Inferred exception type, request argument and method response status affect native resolution.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedExceptionHandlersTest#realSpringInfersTheExceptionResolvesRequestAndUsesStatusAndBodyAnnotations` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-EXCEPTION-ENTITY

**Verdict:** PARTIAL. Concrete generic ResponseEntity preserves status, headers and body.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedExceptionHandlersTest#concreteGenericResponseEntityKeepsStatusHeadersAndBody` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-EXCEPTION-CLASS-STATUS

**Verdict:** PARTIAL. Class response status reaches native return-value handling.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedExceptionHandlersTest#classResponseStatusIsAvailableToTheActualReturnValuePipeline` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-JACKSON-GETTER-MIXIN

**Verdict:** PARTIAL. Jackson applies getter mix-in metadata.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.JacksonAddedGettersTest#mixInsAreAppliedByJacksonToTheAddedMethod` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-JACKSON-FIELD-MIXIN

**Verdict:** PARTIAL. Jackson field mix-in metadata participates in fresh discovery after removal/restoration.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.JacksonAddedPropertiesTest#fieldMixinNamingAndRenameRemovalRestorationUseFreshDiscovery` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-JACKSON-GETTER-ACCESS

**Verdict:** PARTIAL. Private getter respects each mapper access policy.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.JacksonAddedGettersTest#privateGetterRespectsDisabledAccessOverride` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-JACKSON-WRITE-ACCESS

**Verdict:** PARTIAL. Private setter/field respects each mapper access policy.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.JacksonAddedPropertiesTest#privateSettersAndFieldsRespectEachMappersAccessPolicy` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-JACKSON-READ-ONLY

**Verdict:** PARTIAL. Read-only property is not populated by JSON input.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.JacksonAddedPropertiesTest#fieldsUseTheRealStorageAndPreserveJsonAccessPolicies` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-JACKSON-WRITE-ONLY

**Verdict:** PARTIAL. Write-only property is omitted from JSON output.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.JacksonAddedPropertiesTest#fieldsUseTheRealStorageAndPreserveJsonAccessPolicies` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-JACKSON-GENERIC-IN

**Verdict:** PARTIAL. Concrete generic collection input retains converted element types.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.JacksonAddedPropertiesTest#setterMetadataUsesAliasNullPolicyAndGenericConversion` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-JACKSON-ERRORS

**Verdict:** PARTIAL. Invalid conversion and thrown setter retain native mapping errors/root cause.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.JacksonAddedPropertiesTest#invalidConversionAndSetterExceptionKeepJacksonErrors` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-JACKSON-GETTER-ERROR

**Verdict:** PARTIAL. Throwing getter retains native mapping error/root cause.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.JacksonAddedGettersTest#getterFailuresKeepJacksonMappingPathAndRootCause` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-JACKSON-HELD-WRITER

**Verdict:** PARTIAL. Retained writer follows retargeted bodies while fresh discovery follows new shape.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.JacksonAddedGettersTest#retainedWriterUsesNewBodiesWhileNewDiscoveryUsesNewMetadata` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-BINDER-PROXY-REFUSAL

**Verdict:** EXPECTED. Proxy callback receiver is refused before body invocation.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedMvcBindingMethodsTest#proxyReceiverIsRefusedBeforeAnyCallbackBodyRuns` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-EXCEPTION-PROXY-REFUSAL

**Verdict:** EXPECTED. Proxy exception receiver is refused before body invocation.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedExceptionHandlersTest#proxyReceiverIsNotUnwrappedAndNoBodyRunsWithoutItsAdvice` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-MODEL-MAPPED-EXCLUSION

**Verdict:** EXPECTED. Request-mapped model methods are excluded from initializer discovery.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedMvcBindingMethodsTest#requestMappedModelAttributesAreNotInvokedAsModelInitializers` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-BINDER-RETURN-REFUSAL

**Verdict:** EXPECTED. A non-void binder is refused before invocation.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedMvcBindingMethodsTest#invalidBinderReturnAndAsyncModelAreRefusedBeforeInvocation` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-MODEL-ASYNC-REFUSAL

**Verdict:** EXPECTED. Declared asynchronous model return is refused.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedMvcBindingMethodsTest#invalidBinderReturnAndAsyncModelAreRefusedBeforeInvocation` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-CALLBACK-ADVICE-REFUSAL

**Verdict:** EXPECTED. Extra advice annotation on an added callback is refused.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedMvcBindingMethodsTest#extraAdviceAndStaticCallbacksCannotBypassTheirSemantics` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-CALLBACK-STATIC-REFUSAL

**Verdict:** EXPECTED. Static added callback is refused.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedMvcBindingMethodsTest#extraAdviceAndStaticCallbacksCannotBypassTheirSemantics` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-CALLBACK-GENERIC-REFUSAL

**Verdict:** EXPECTED. Class/method type-variable callback metadata is refused.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedMvcBindingMethodsTest#typeVariablesInterfacesAndSessionMetadataAreRefused` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-CALLBACK-INTERFACE-REFUSAL

**Verdict:** EXPECTED. Callback receiver with interface metadata is refused.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedMvcBindingMethodsTest#typeVariablesInterfacesAndSessionMetadataAreRefused` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-CALLBACK-SESSION-REFUSAL

**Verdict:** EXPECTED. SessionAttributes callback receiver is refused.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedMvcBindingMethodsTest#typeVariablesInterfacesAndSessionMetadataAreRefused` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-EXCEPTION-AMBIGUITY

**Verdict:** EXPECTED. Ambiguous added exception mapping is rejected and clears the old adapter.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedExceptionHandlersTest#ambiguousMappingIsRejectedBySpringAndClearsTheOldAdapter` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-EXCEPTION-ASYNC-REFUSAL

**Verdict:** EXPECTED. Asynchronous added exception handler is refused.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedExceptionHandlersTest#asynchronousAndExtraAdviceMetadataAreExplicitlyRefused` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-EXCEPTION-ADVICE-REFUSAL

**Verdict:** EXPECTED. Extra advice on added exception handler is refused.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedExceptionHandlersTest#asynchronousAndExtraAdviceMetadataAreExplicitlyRefused` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-EXCEPTION-GENERIC-REFUSAL

**Verdict:** EXPECTED. Type-variable exception handler metadata is refused.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedExceptionHandlersTest#classTypeVariablesAndMethodTypeVariablesAreRefused` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-EXCEPTION-STATIC-REFUSAL

**Verdict:** EXPECTED. Static added exception handler metadata is refused.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedExceptionHandlersTest#staticAndInterfaceMetadataAreRefusedBeforePublishing` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-EXCEPTION-INTERFACE-REFUSAL

**Verdict:** EXPECTED. Interface-bearing exception receiver metadata is refused.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.AddedExceptionHandlersTest#staticAndInterfaceMetadataAreRefusedBeforePublishing` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-JACKSON-FINAL-REFUSAL

**Verdict:** EXPECTED. Final added fields cannot be written even with access override.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Ordinary component worker: Spring5.3.39/Jackson2.22.3 as applicable; direct metadata publication/bridge, no file watcher or application JVM. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.reload.JacksonAddedPropertiesTest#finalAddedFieldsNeverBypassTheReflectionWriteRestriction` (component)

**Controls / remaining limits:** Native Spring/Jackson effect and exact component assertion, not log/status inference. component_only. The component assertion does not prove watcher publication, framework6 or native deployment for this variant. No per-variant disabled-hook witness is claimed. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-ENDPOINT-PUT

**Verdict:** UNVERIFIED. An entirely new PUT-annotated endpoint needs separate watcher HTTP acceptance.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. No dedicated invocation in this P3b run. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** No executed acceptance observer; variant remains UNVERIFIED.

**Controls / remaining limits:** No acceptance result; implementation recognition alone is not behavior evidence. UNVERIFIED variant. GET/POST acceptance must not be used to certify this annotation. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-ENDPOINT-PATCH

**Verdict:** UNVERIFIED. An entirely new PATCH-annotated endpoint needs separate watcher HTTP acceptance.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. No dedicated invocation in this P3b run. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** No executed acceptance observer; variant remains UNVERIFIED.

**Controls / remaining limits:** No acceptance result; implementation recognition alone is not behavior evidence. UNVERIFIED variant. GET/POST acceptance must not be used to certify this annotation. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-ENDPOINT-DELETE

**Verdict:** UNVERIFIED. An entirely new DELETE-annotated endpoint needs separate watcher HTTP acceptance.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. No dedicated invocation in this P3b run. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** No executed acceptance observer; variant remains UNVERIFIED.

**Controls / remaining limits:** No acceptance result; implementation recognition alone is not behavior evidence. UNVERIFIED variant. GET/POST acceptance must not be used to certify this annotation. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-ENDPOINT-REQUEST-MAPPING

**Verdict:** UNVERIFIED. An entirely new REQUEST-MAPPING-annotated endpoint needs separate watcher HTTP acceptance.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. No dedicated invocation in this P3b run. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** No executed acceptance observer; variant remains UNVERIFIED.

**Controls / remaining limits:** No acceptance result; implementation recognition alone is not behavior evidence. UNVERIFIED variant. GET/POST acceptance must not be used to certify this annotation. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

## P3c: Callbacks and data

The original evidence package is committed at2ea1744. A separately owner-approved native Spring6 event-factory repair now has desired RED/GREEN proof on both JDK lanes; independent audit is pending. The two historical FAILURE cell IDs are retained with current bounded delivery verdicts, and immutable prior claims/lanes remain in history_before_spring6_event_factory_repair. Original observed_defects and negative witnesses describe the historical P3c tree; they are not current failures. Unrelated limits and verdicts remain unchanged. Registered scheduled Runnable controls do not certify timers; The original Spring Data @Query fixtures use JPQL. RF08 adds a separate SQL nativeQuery=true watcher on the measured H2/Spring6 graph; the SQL record below distinguishes current native dependent recreation from retained ordinary singleton healing and excluded retired external owners.

Machine record: [p3c-callbacks-data.json](feature-evidence/p3c-callbacks-data.json); manifest: [p3c-cells.txt](feature-evidence/p3c-cells.txt).

### P3C-SCHEDULED-TIMER

**Verdict:** PROVEN_WITH_SCOPE.

An added direct @Scheduled timer fires, follows repeated edits, stops on annotation/method removal and resumes on restoration.

**Shipped surfaces:** `docs/product-reference.md:94` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39 and isolated Spring 6.1.14; synthetic standalone context; supported direct annotations and singleton shapes; no servlet/HTTP or deployed container claim.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedScheduledMethodReloadTest#addedTaskRunsOncePerRegistrationFollowsEditsAndStopsOnRemoval` (watcher): PROBE0..7 exact task count/body generation and original-ok; stopped means counter unchanged across 150ms after settle. `com.onurkat.reclazz.e2e.JakartaAddedScheduledMethodReloadTest#addedTaskRunsOncePerRegistrationFollowsEditsAndStopsOnRemoval` (watcher): PROBE0..7 exact task count/body generation and original-ok; stopped means counter unchanged across 150ms after settle.

**Observable:** An added direct @Scheduled timer fires, follows repeated edits, stops on annotation/method removal and resumes on restoration.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-SCHEDULED-CURRENT

**Verdict:** PROVEN_WITH_SCOPE.

Scheduled callbacks resolve the current singleton after external replacement and cancel on context close.

**Shipped surfaces:** `docs/product-reference.md:94` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39 and isolated Spring 6.1.14; synthetic standalone context; supported direct annotations and singleton shapes; no servlet/HTTP or deployed container claim.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedScheduledMethodReloadTest#addedTaskRunsOncePerRegistrationFollowsEditsAndStopsOnRemoval` (watcher): BEAN-REPLACED, no STALE-BEAN at any stage; CLOSED=0:stable; existing original timer remains active. `com.onurkat.reclazz.e2e.JakartaAddedScheduledMethodReloadTest#addedTaskRunsOncePerRegistrationFollowsEditsAndStopsOnRemoval` (watcher): BEAN-REPLACED, no STALE-BEAN at any stage; CLOSED=0:stable; existing original timer remains active.

**Observable:** Scheduled callbacks resolve the current singleton after external replacement and cancel on context close.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-SCHEDULED-PROXY

**Verdict:** PROVEN_WITH_SCOPE.

A newly added timer on a standard cache CGLIB proxy updates its target and retains existing cache advice.

**Shipped surfaces:** `docs/product-reference.md:94` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39 and isolated Spring 6.1.14; synthetic standalone context; supported direct annotations and singleton shapes; no servlet/HTTP or deployed container claim.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedScheduledOnProxyReloadTest#scheduledMethodAddedToACacheProxiedSingletonRunsOnTheRealTarget` (watcher): R0 ticks=0; R1 ticks=growing and cache=ok; R2 ticks=stopped; both normal and child loaders. `com.onurkat.reclazz.e2e.JakartaAddedScheduledOnProxyReloadTest#scheduledMethodAddedToACacheProxiedSingletonRunsOnTheRealTarget` (watcher): R0 ticks=0; R1 ticks=growing and cache=ok; R2 ticks=stopped; both normal and child loaders.

**Observable:** A newly added timer on a standard cache CGLIB proxy updates its target and retains existing cache advice.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-SCHEDULES-REPEATABLE

**Verdict:** PARTIAL.

Repeatable @Schedules metadata produces two native registrations with saved time units.

**Shipped surfaces:** `docs/product-reference.md:94` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringSchedulerReloaderTest#aRepeatableScheduleKeepsBothDeclarationsAndTheirUnits` (component): Two native tasks, FixedRateTask interval=2000. Does not observe both timers firing.

**Observable:** Repeatable @Schedules metadata produces two native registrations with saved time units.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-SCHEDULED-ROLLBACK-PREPARATION

**Verdict:** PARTIAL.

Failed partial scheduler registration cancels partial work and a later valid save registers exactly once.

**Shipped surfaces:** `docs/product-reference.md:94` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringSchedulerReloaderTest#partialRegistrationIsCancelledAndTheNextValidSaveRecovers` (component): Invalid cron => false and zero tasks; valid retry => true and one task.

**Observable:** Failed partial scheduler registration cancels partial work and a later valid save registers exactly once.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-SCHEDULED-REFUSAL

**Verdict:** EXPECTED.

Unsupported return/argument/static/mixed callback declarations are refused during inspection.

**Shipped surfaces:** `docs/product-reference.md:94` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringSchedulerReloaderTest#unsupportedSignaturesAndAdviceAreExplained` (component): Inspect methods empty, refused size1; extra callback annotation named.

**Observable:** Unsupported return/argument/static/mixed callback declarations are refused during inspection.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-SCHEDULED-PROTOTYPE

**Verdict:** EXPECTED.

A prototype is not instantiated to schedule an added method; refusal is named.

**Shipped surfaces:** `docs/product-reference.md:94` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringSchedulerReloaderTest#prototypeIsNotCreatedToScheduleAnAddedMethod` (component): False reload, zero tasks, RestartLedger contains singleton.

**Observable:** A prototype is not instantiated to schedule an added method; refusal is named.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-EVENT-DELIVERY

**Verdict:** PROVEN_WITH_SCOPE.

An added listener receives only matching publications, follows body/condition edits and stops/restores without duplicate deliveries.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39 and isolated Spring 6.1.14; synthetic standalone context; supported direct annotations and singleton shapes; no servlet/HTTP or deployed container claim.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedEventListenerMethodReloadTest#addedListenerFiltersRunsOnceFollowsReplacementAndStopsOnRemoval` (watcher): PROBE0..8 exact added counts/value; go/no/integer/go publications; three unrelated deliveries remain; annotation and method removal. `com.onurkat.reclazz.e2e.JakartaAddedEventListenerMethodReloadTest#addedListenerFiltersRunsOnceFollowsReplacementAndStopsOnRemoval` (watcher): PROBE0..8 exact added counts/value; go/no/integer/go publications; three unrelated deliveries remain; annotation and method removal.

**Observable:** An added listener receives only matching publications, follows body/condition edits and stops/restores without duplicate deliveries.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-EVENT-CURRENT

**Verdict:** PROVEN_WITH_SCOPE.

An added event listener resolves its replacement singleton without duplicating existing listeners.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39 and isolated Spring 6.1.14; synthetic standalone context; supported direct annotations and singleton shapes; no servlet/HTTP or deployed container claim.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedEventListenerMethodReloadTest#addedListenerFiltersRunsOnceFollowsReplacementAndStopsOnRemoval` (watcher): BEAN-REPLACED and every PROBE=current, exact original counts 3 or0. `com.onurkat.reclazz.e2e.JakartaAddedEventListenerMethodReloadTest#addedListenerFiltersRunsOnceFollowsReplacementAndStopsOnRemoval` (watcher): BEAN-REPLACED and every PROBE=current, exact original counts 3 or0.

**Observable:** An added event listener resolves its replacement singleton without duplicating existing listeners.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-EVENT-PROXY

**Verdict:** PROVEN_WITH_SCOPE.

An added event listener on a standard cache proxy reaches the target while existing cache advice remains effective.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39 and isolated Spring 6.1.14; synthetic standalone context; supported direct annotations and singleton shapes; no servlet/HTTP or deployed container claim.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedEventListenerOnProxyReloadTest#eventListenerAddedToACacheProxiedSingletonRunsOnTheRealTarget` (watcher): events0 -> fired -> silent, proxied=true and cache=ok in ordinary/child loaders. `com.onurkat.reclazz.e2e.JakartaAddedEventListenerOnProxyReloadTest#eventListenerAddedToACacheProxiedSingletonRunsOnTheRealTarget` (watcher): events0 -> fired -> silent, proxied=true and cache=ok in ordinary/child loaders.

**Observable:** An added event listener on a standard cache proxy reaches the target while existing cache advice remains effective.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-EVENT-RESULT

**Verdict:** PROVEN_WITH_SCOPE.

A single returned event is republished once with its identity, updated body and null/condition/error/removal behavior.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39 and isolated Spring 6.1.14; synthetic standalone context; supported direct annotations and singleton shapes; no servlet/HTTP or deployed container claim.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedEventListenerResultReloadTest#returnedEventsFollowBodyConditionAndRemovalEdits` (watcher): RESULT0..9 exact calls:received:value:failures:2:identity-ok; null/failure/condition suppress publication; replacement and restore. `com.onurkat.reclazz.e2e.JakartaAddedEventListenerResultReloadTest#returnedEventsFollowBodyConditionAndRemovalEdits` (watcher): RESULT0..9 exact calls:received:value:failures:2:identity-ok; null/failure/condition suppress publication; replacement and restore.

**Observable:** A single returned event is republished once with its identity, updated body and null/condition/error/removal behavior.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-EVENT-ORDER-NAMES

**Verdict:** PARTIAL.

Named SpEL conditions, bean references and @Order are retained in component delivery.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringAddedEventListenerTest#namedConditionsBeanReferencesAndOrderAreHandledBySpring` (component): no yields no calls; yes yields second:yes then first:yes.

**Observable:** Named SpEL conditions, bean references and @Order are retained in component delivery.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-EVENT-DEBUG-NAMES

**Verdict:** PARTIAL.

Debug local-variable parameter names support a named event condition when MethodParameters is absent.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringAddedEventListenerTest#debugParameterNamesAreCarriedWhenMethodParametersAreAbsent` (component): Fixture really carries LVT message, MethodParameters null, yes delivers first:yes.

**Observable:** Debug local-variable parameter names support a named event condition when MethodParameters is absent.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-EVENT-PROTOTYPE-PROXY-REFUSAL

**Verdict:** EXPECTED.

Prototype and unsupported custom advisor proxies retire the prior added registration and report refusal.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringAddedEventListenerTest#proxiesAndPrototypesAreRefusedAndPreviousRegistrationIsRemoved` (component): Old calls empty, digest advisor/singleton; prototype not instantiated.

**Observable:** Prototype and unsupported custom advisor proxies retire the prior added registration and report refusal.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-EVENT-CUSTOM-FACTORY-REFUSAL

**Verdict:** EXPECTED.

Custom event listener factories are refused instead of bypassed.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringAddedEventListenerTest#customFactoryIsNotSilentlyBypassed` (component): No calls, RestartLedger custom event listener factories.

**Observable:** Custom event listener factories are refused instead of bypassed.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-EVENT-RESULT-REFUSAL

**Verdict:** EXPECTED.

Unsupported declared result shapes are refused before invocation or publication.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.AddedEventListenerResultTest#unsupportedDeclaredResultsAreRefusedBeforeInvocation` (component): Ten declared shapes: false reload, named ledger, zero body calls/results.

**Observable:** Unsupported declared result shapes are refused before invocation or publication.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-EVENT-ACTUAL-RESULT-REFUSAL

**Verdict:** EXPECTED.

Unsupported actual values behind Object fail before publication and a supported value subsequently recovers.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.AddedEventListenerResultTest#unsupportedActualResultsHiddenBehindObjectAreNotPublished` (component): Nine actual shapes throw single-event error; no results/receiver entries; supported Reply then passes.

**Observable:** Unsupported actual values behind Object fail before publication and a supported value subsequently recovers.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-TXEVENT-BEFORE-COMMIT

**Verdict:** PROVEN_WITH_SCOPE.

Added transactional delivery honors BEFORE-COMMIT with real JDBC commit/rollback effects.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64; native Spring5.3.39 or6.1.14 graph as named by each observer. Normal and child application loaders, startup javaagent and compiled watched saves. Only the exact tested singleton/callback shapes.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedTransactionalEventListenerReloadTest#transactionPhasesAndQueuedEventsSurviveEditsRemovalAndRestoration` (watcher): MATRIX1/4 exact ordered tape: before callback appears only after transaction boundary and only on commit; row counts1/4; no callback runs early inside transaction. `com.onurkat.reclazz.e2e.JakartaAddedTransactionalEventListenerReloadTest#transactionPhasesAndQueuedEventsSurviveEditsRemovalAndRestoration` (watcher): MATRIX1/4 exact ordered tape: before callback appears only after transaction boundary and only on commit; row counts1/4; no callback runs early inside transaction.

**Observable:** Added transactional delivery honors BEFORE-COMMIT with real JDBC commit/rollback effects.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Spring6.1.14 with native transaction management is observed FAILED in the separate P3C-SPRING6-NATIVE-* cells; do not promote this successful Spring5 observation to Spring6. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-TXEVENT-AFTER-COMMIT

**Verdict:** PROVEN_WITH_SCOPE.

Added transactional delivery honors AFTER-COMMIT with real JDBC commit/rollback effects.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64; native Spring5.3.39 or6.1.14 graph as named by each observer. Normal and child application loaders, startup javaagent and compiled watched saves. Only the exact tested singleton/callback shapes.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedTransactionalEventListenerReloadTest#transactionPhasesAndQueuedEventsSurviveEditsRemovalAndRestoration` (watcher): MATRIX1/4 exact ordered tape: after callback appears on commit and not rollback; row counts1/4; no callback runs early inside transaction. `com.onurkat.reclazz.e2e.JakartaAddedTransactionalEventListenerReloadTest#transactionPhasesAndQueuedEventsSurviveEditsRemovalAndRestoration` (watcher): MATRIX1/4 exact ordered tape: after callback appears on commit and not rollback; row counts1/4; no callback runs early inside transaction.

**Observable:** Added transactional delivery honors AFTER-COMMIT with real JDBC commit/rollback effects.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Spring6.1.14 with native transaction management is observed FAILED in the separate P3C-SPRING6-NATIVE-* cells; do not promote this successful Spring5 observation to Spring6. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-TXEVENT-AFTER-ROLLBACK

**Verdict:** PROVEN_WITH_SCOPE.

Added transactional delivery honors AFTER-ROLLBACK with real JDBC commit/rollback effects.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64; native Spring5.3.39 or6.1.14 graph as named by each observer. Normal and child application loaders, startup javaagent and compiled watched saves. Only the exact tested singleton/callback shapes.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedTransactionalEventListenerReloadTest#transactionPhasesAndQueuedEventsSurviveEditsRemovalAndRestoration` (watcher): MATRIX1/4 exact ordered tape: rollback callback appears only on rollback; row counts1/4; no callback runs early inside transaction. `com.onurkat.reclazz.e2e.JakartaAddedTransactionalEventListenerReloadTest#transactionPhasesAndQueuedEventsSurviveEditsRemovalAndRestoration` (watcher): MATRIX1/4 exact ordered tape: rollback callback appears only on rollback; row counts1/4; no callback runs early inside transaction.

**Observable:** Added transactional delivery honors AFTER-ROLLBACK with real JDBC commit/rollback effects.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Spring6.1.14 with native transaction management is observed FAILED in the separate P3C-SPRING6-NATIVE-* cells; do not promote this successful Spring5 observation to Spring6. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-TXEVENT-AFTER-COMPLETION

**Verdict:** PROVEN_WITH_SCOPE.

Added transactional delivery honors AFTER-COMPLETION with real JDBC commit/rollback effects.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64; native Spring5.3.39 or6.1.14 graph as named by each observer. Normal and child application loaders, startup javaagent and compiled watched saves. Only the exact tested singleton/callback shapes.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedTransactionalEventListenerReloadTest#transactionPhasesAndQueuedEventsSurviveEditsRemovalAndRestoration` (watcher): MATRIX1/4 exact ordered tape: complete callback appears once on each commit and rollback; row counts1/4; no callback runs early inside transaction. `com.onurkat.reclazz.e2e.JakartaAddedTransactionalEventListenerReloadTest#transactionPhasesAndQueuedEventsSurviveEditsRemovalAndRestoration` (watcher): MATRIX1/4 exact ordered tape: complete callback appears once on each commit and rollback; row counts1/4; no callback runs early inside transaction.

**Observable:** Added transactional delivery honors AFTER-COMPLETION with real JDBC commit/rollback effects.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Spring6.1.14 with native transaction management is observed FAILED in the separate P3C-SPRING6-NATIVE-* cells; do not promote this successful Spring5 observation to Spring6. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-TXEVENT-RETIRE

**Verdict:** PROVEN_WITH_SCOPE.

Replacement/removal retires old queued transaction callbacks; new events use current declarations and restoration recovers.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64; native Spring5.3.39 or6.1.14 graph as named by each observer. Normal and child application loaders, startup javaagent and compiled watched saves. Only the exact tested singleton/callback shapes.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedTransactionalEventListenerReloadTest#transactionPhasesAndQueuedEventsSurviveEditsRemovalAndRestoration` (watcher): QUEUED2 before reload; HELD2 contains only ok-new generation2; HELD3 empty after removal; MATRIX4 restored. `com.onurkat.reclazz.e2e.JakartaAddedTransactionalEventListenerReloadTest#transactionPhasesAndQueuedEventsSurviveEditsRemovalAndRestoration` (watcher): QUEUED2 before reload; HELD2 contains only ok-new generation2; HELD3 empty after removal; MATRIX4 restored.

**Observable:** Replacement/removal retires old queued transaction callbacks; new events use current declarations and restoration recovers.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Spring6.1.14 with native transaction management is observed FAILED in the separate P3C-SPRING6-NATIVE-* cells; do not promote this successful Spring5 observation to Spring6. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-TXEVENT-FALLBACK

**Verdict:** PROVEN_WITH_SCOPE.

Fallback enabled transaction listeners deliver outside a transaction while saved conditions remain active.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64; native Spring5.3.39 or6.1.14 graph as named by each observer. Normal and child application loaders, startup javaagent and compiled watched saves. Only the exact tested singleton/callback shapes.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedTransactionalEventListenerReloadTest#transactionPhasesAndQueuedEventsSurviveEditsRemovalAndRestoration` (watcher): First tape entry fallback1/4:outside; other phases absent outside; ok condition gates transactional delivery. `com.onurkat.reclazz.e2e.JakartaAddedTransactionalEventListenerReloadTest#transactionPhasesAndQueuedEventsSurviveEditsRemovalAndRestoration` (watcher): First tape entry fallback1/4:outside; other phases absent outside; ok condition gates transactional delivery.

**Observable:** Fallback enabled transaction listeners deliver outside a transaction while saved conditions remain active.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Spring6.1.14 with native transaction management is observed FAILED in the separate P3C-SPRING6-NATIVE-* cells; do not promote this successful Spring5 observation to Spring6. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-TXEVENT-BEFORE-FAILURE

**Verdict:** PARTIAL.

A before-commit listener exception rolls back actual JDBC writes.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.AddedTransactionalEventListenerTest#beforeCommitExceptionRollsBackTheActualDatabase` (component): IllegalStateException listener refused, rows0.

**Observable:** A before-commit listener exception rolls back actual JDBC writes.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-TXEVENT-AFTER-FAILURE

**Verdict:** PARTIAL.

An after-commit listener failure cannot undo committed rows or suppress completion listeners.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.AddedTransactionalEventListenerTest#afterCommitFailureCannotUndoCommittedDataOrPreventOtherCompletionListeners` (component): Rows1, sorted complete:committed/fail:committed.

**Observable:** An after-commit listener failure cannot undo committed rows or suppress completion listeners.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-TXEVENT-CONTEXT-REFUSAL

**Verdict:** EXPECTED.

Missing/custom transactional factories refuse delivery and name the missing infrastructure.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.AddedTransactionalEventListenerTest#missingOrCustomTransactionalFactoryIsNotBypassed` (component): Both missing and custom contexts have zero calls and specific ledger.

**Observable:** Missing/custom transactional factories refuse delivery and name the missing infrastructure.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-TXEVENT-PHASE-EDIT

**Verdict:** PARTIAL.

Editing an added listener from AFTER_COMMIT to BEFORE_COMMIT while a transaction is open retires its old queued event.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.AddedTransactionalEventListenerTest#replacingAPhaseWhileTransactionIsOpenCancelsItsOldQueuedCallback` (component): Publish old, replace phase, publish new; no early calls; only after:new after completion.

**Observable:** Editing an added listener from AFTER_COMMIT to BEFORE_COMMIT while a transaction is open retires its old queued event.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-ASYNC-SERVICE-COMPLETABLE

**Verdict:** PROVEN_WITH_SCOPE.

An added CompletableFuture service method submits on saved executors and retained old/current receivers see refreshed behavior.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39 and isolated Spring 6.1.14; synthetic standalone context; supported direct annotations and singleton shapes; no servlet/HTTP or deployed container claim.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedAsyncServiceReloadTest#heldCallsGetNativeFuturesAcrossExecutorChangesRemovalAndRestore` (watcher): R1 pending=true calls0; R2 captured/old/current true across one->two; reflected=false; normal/child loaders. `com.onurkat.reclazz.e2e.JakartaAddedAsyncServiceReloadTest#heldCallsGetNativeFuturesAcrossExecutorChangesRemovalAndRestore` (watcher): R1 pending=true calls0; R2 captured/old/current true across one->two; reflected=false; normal/child loaders.

**Observable:** An added CompletableFuture service method submits on saved executors and retained old/current receivers see refreshed behavior.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-ASYNC-SERVICE-CANCEL-ROLLBACK

**Verdict:** PROVEN_WITH_SCOPE.

Checked worker failure rolls back writes and cancelling before execution prevents body execution.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39 and isolated Spring 6.1.14; synthetic standalone context; supported direct annotations and singleton shapes; no servlet/HTTP or deployed container claim.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedAsyncServiceReloadTest#heldCallsGetNativeFuturesAcrossExecutorChangesRemovalAndRestore` (watcher): R2 failed=true verifies rows unchanged and no void error handler; cancelled=true verifies no added calls. `com.onurkat.reclazz.e2e.JakartaAddedAsyncServiceReloadTest#heldCallsGetNativeFuturesAcrossExecutorChangesRemovalAndRestore` (watcher): R2 failed=true verifies rows unchanged and no void error handler; cancelled=true verifies no added calls.

**Observable:** Checked worker failure rolls back writes and cancelling before execution prevents body execution.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-ASYNC-SERVICE-REMOVE

**Verdict:** PROVEN_WITH_SCOPE.

Async annotation removal restores synchronous calls; method removal refuses held call sites; restoration and self-call semantics persist.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39 and isolated Spring 6.1.14; synthetic standalone context; supported direct annotations and singleton shapes; no servlet/HTTP or deployed container claim.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedAsyncServiceReloadTest#heldCallsGetNativeFuturesAcrossExecutorChangesRemovalAndRestore` (watcher): R3 sync=true, R4 removed=true, R5 restored/self/original true; old callsites retained. `com.onurkat.reclazz.e2e.JakartaAddedAsyncServiceReloadTest#heldCallsGetNativeFuturesAcrossExecutorChangesRemovalAndRestore` (watcher): R3 sync=true, R4 removed=true, R5 restored/self/original true; old callsites retained.

**Observable:** Async annotation removal restores synchronous calls; method removal refuses held call sites; restoration and self-call semantics persist.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-ASYNC-SERVICE-VOID

**Verdict:** PARTIAL.

Standalone void async calls use configured default executor and native uncaught handler.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringAddedAsyncServiceTest#standaloneVoidUsesTheDefaultExecutorAndUncaughtHandler` (component): Plain/proxy: no immediate calls; one worker call and exact receive:fail:failure handler.

**Observable:** Standalone void async calls use configured default executor and native uncaught handler.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-ASYNC-SERVICE-FUTURE

**Verdict:** PARTIAL.

Future and CompletableFuture result/error behavior is compared with native Spring async proxy behavior.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringAddedAsyncServiceTest#futuresReturnImmediatelyAndPreserveNativeResultAndErrorBehavior` (component): Plain/proxy, ok/throw/exceptional/null native comparison; Future result future:two.

**Observable:** Future and CompletableFuture result/error behavior is compared with native Spring async proxy behavior.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-ASYNC-SERVICE-GENERICS

**Verdict:** PARTIAL.

Concrete nested generic metadata is retained for native consumers.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringAddedAsyncServiceTest#concreteNestedGenericMetadataIsPreservedForNativeConsumers` (component): Exact nested generic type names and List a,b,two result.

**Observable:** Concrete nested generic metadata is retained for native consumers.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-ASYNC-SERVICE-REFUSAL

**Verdict:** EXPECTED.

Unsupported declared async return types and unresolved generics refuse before submission.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringAddedAsyncServiceTest#invalidReturnTypesAndUnresolvedGenericMetadataAreRefusedBeforeSubmission` (component): Six unsupported types, covered empty, errors, queues/calls empty; missing qualifier refuses.

**Observable:** Unsupported declared async return types and unresolved generics refuse before submission.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-ASYNC-SERVICE-EXCEPTIONAL-FUTURE

**Verdict:** PARTIAL.

Returning an exceptional Future does not retroactively roll back the worker transaction.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringAddedAsyncServiceTest#checkedThrowRollsBackButAnExceptionalReturnedFutureDoesNotRetroactivelyRollback` (component): Rows ok and exceptional persist; throw does not; all worker tx=true; no void errors.

**Observable:** Returning an exceptional Future does not retroactively roll back the worker transaction.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-ASYNC-SERVICE-CACHE

**Verdict:** PARTIAL.

Spring 5 async cache stores the body-returned inner Future while callers receive distinct outer Futures.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringAddedAsyncServiceTest#nativeCacheStoresTheInnerFutureAndEachCallGetsItsOwnOuterFuture` (component): Two outer Futures notSame, same result, body once, stored CompletableFuture notSame first.

**Observable:** Spring 5 async cache stores the body-returned inner Future while callers receive distinct outer Futures.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-ASYNC-EVENT

**Verdict:** PROVEN_WITH_SCOPE.

Added async event delivery honors executor changes, conditions, captured admitted work, worker rollback and removal.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64; native Spring5.3.39 or6.1.14 graph as named by each observer. Normal and child application loaders, startup javaagent and compiled watched saves. Only the exact tested singleton/callback shapes.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedAsyncEventReloadTest#asyncEventsUseNativeExecutorAndKeepAdmittedBodyAcrossReloadAndRemoval` (watcher): R1..5 exact queue/captured/rerouted/original/refused/rollback/handler/removed checks; reflected=false. `com.onurkat.reclazz.e2e.JakartaAddedAsyncEventReloadTest#asyncEventsUseNativeExecutorAndKeepAdmittedBodyAcrossReloadAndRemoval` (watcher): R1..5 exact queue/captured/rerouted/original/refused/rollback/handler/removed checks; reflected=false.

**Observable:** Added async event delivery honors executor changes, conditions, captured admitted work, worker rollback and removal.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Spring6.1.14 with native transaction management is observed FAILED in the separate P3C-SPRING6-NATIVE-* cells; do not promote this successful Spring5 observation to Spring6. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-ASYNC-SCHEDULED

**Verdict:** PROVEN_WITH_SCOPE.

Invoking the native registered scheduled Runnable submits to current async executors while already admitted work keeps its body/target.

**Shipped surfaces:** `docs/product-reference.md:94` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39 and isolated Spring 6.1.14; synthetic standalone context; supported direct annotations and singleton shapes; no servlet/HTTP or deployed container claim.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedAsyncScheduledReloadTest#asyncSchedulesKeepAcceptedWorkAndRefreshFutureTicks` (watcher): R1..7 exact queue/captured/rerouted/refusal/rollback/error/sync/remove/restore/close checks; registered Runnable explicitly fired, no actual timer claim. `com.onurkat.reclazz.e2e.JakartaAddedAsyncScheduledReloadTest#asyncSchedulesKeepAcceptedWorkAndRefreshFutureTicks` (watcher): R1..7 exact queue/captured/rerouted/refusal/rollback/error/sync/remove/restore/close checks; registered Runnable explicitly fired, no actual timer claim.

**Observable:** Invoking the native registered scheduled Runnable submits to current async executors while already admitted work keeps its body/target.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-ASYNC-DEFAULT-EXECUTOR

**Verdict:** PARTIAL.

Configured/default and saved qualified executors match native Spring async proxy submission.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringAddedAsyncEventTest#defaultAndQualifiedExecutorsMatchNativeSpringOnTheOriginalTarget` (component): Plain/proxy no inline call; one then two queues; exact worker thread targets plus nativeControl.

**Observable:** Configured/default and saved qualified executors match native Spring async proxy submission.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-ASYNC-NAMED-DEFAULT

**Verdict:** PARTIAL.

Native named taskExecutor is selected without an explicit AsyncConfigurer.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringAddedAsyncEventTest#nativeNamedDefaultExecutorIsUsedWithoutAnExplicitConfigurer` (component): Worker one chosen; queue two empty.

**Observable:** Native named taskExecutor is selected without an explicit AsyncConfigurer.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-ASYNC-INFRASTRUCTURE-REFUSAL

**Verdict:** EXPECTED.

Missing, duplicate, custom and reordered async infrastructure refuse before body execution.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringAddedAsyncEventTest#absentDuplicateCustomAndReorderedInfrastructureRefuseBeforeBody` (component): Each invalid configuration throws; queues/calls remain empty.

**Observable:** Missing, duplicate, custom and reordered async infrastructure refuse before body execution.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-ASYNC-ADVISOR-REFUSAL

**Verdict:** EXPECTED.

Custom annotation, foreign and duplicated async advisors cannot be silently bypassed.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringAddedAsyncEventTest#customAnnotationForeignAndDuplicateAsyncAdvisorsAreNotSilentlyBypassed` (component): Three advisor mutations throw; no submission/body.

**Observable:** Custom annotation, foreign and duplicated async advisors cannot be silently bypassed.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-CALLBACK-TRANSACTIONS

**Verdict:** PROVEN_WITH_SCOPE.

Added public callbacks use native transactions with real commit/checked rollback across advice removal and restoration.

**Shipped surfaces:** `docs/usage.md:1883` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64; native Spring5.3.39 or6.1.14 graph as named by each observer. Normal and child application loaders, startup javaagent and compiled watched saves. Only the exact tested singleton/callback shapes.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedCallbackOperationsReloadTest#callbacksUseNativeTransactionsAndCachesAcrossSaves` (watcher): R1/4 rows2 txtrue, R2 rows4 txfalse, R3 removed rows0; actual H2 writes; registered scheduled Runnable invoked explicitly. `com.onurkat.reclazz.e2e.JakartaAddedCallbackOperationsReloadTest#callbacksUseNativeTransactionsAndCachesAcrossSaves` (watcher): R1/4 rows2 txtrue, R2 rows4 txfalse, R3 removed rows0; actual H2 writes; registered scheduled Runnable invoked explicitly.

**Observable:** Added public callbacks use native transactions with real commit/checked rollback across advice removal and restoration.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Spring6.1.14 with native transaction management is observed FAILED in the separate P3C-SPRING6-NATIVE-* cells; do not promote this successful Spring5 observation to Spring6. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-CALLBACK-CACHE

**Verdict:** PROVEN_WITH_SCOPE.

Successful callback eviction, failed eviction retention and cached returned-event publication retain native cache semantics.

**Shipped surfaces:** `docs/usage.md:1883` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64; native Spring5.3.39 or6.1.14 graph as named by each observer. Normal and child application loaders, startup javaagent and compiled watched saves. Only the exact tested singleton/callback shapes.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedCallbackOperationsReloadTest#callbacksUseNativeTransactionsAndCachesAcrossSaves` (watcher): R1/4 cacheevicted lookups1 receipts2; R2 cacheretained lookups2; failed callback leaves after-success cache entry. `com.onurkat.reclazz.e2e.JakartaAddedCallbackOperationsReloadTest#callbacksUseNativeTransactionsAndCachesAcrossSaves` (watcher): R1/4 cacheevicted lookups1 receipts2; R2 cacheretained lookups2; failed callback leaves after-success cache entry.

**Observable:** Successful callback eviction, failed eviction retention and cached returned-event publication retain native cache semantics.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Spring6.1.14 with native transaction management is observed FAILED in the separate P3C-SPRING6-NATIVE-* cells; do not promote this successful Spring5 observation to Spring6. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-CALLBACK-INSPECT-REFUSAL

**Verdict:** EXPECTED.

Advised private/final/generic and unsupported owner shapes are refused at callback inspection.

**Shipped surfaces:** `docs/usage.md:1883` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.AddedCallbackAdviceTest#advisedPrivateFinalAndGenericCallbacksAreRefused` (component): Public/private/final/generic/owner inheritance/interface matrix accepted0; only metadata scanner, no watcher.

**Observable:** Advised private/final/generic and unsupported owner shapes are refused at callback inspection.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-CALLBACK-COMPOSED

**Verdict:** UNVERIFIED.

Composed transaction/cache/security operation annotations on added callbacks behave at runtime.

**Shipped surfaces:** `docs/usage.md:1883` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Outside advertised supported callback-operation subset; no expansion authorized.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39 and isolated Spring 6.1.14; synthetic standalone context; supported direct annotations and singleton shapes; no servlet/HTTP or deployed container claim.

**Tests/assertions:** No observing test mapped; UNVERIFIED.

**Observable:** Composed transaction/cache/security operation annotations on added callbacks behave at runtime.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** No current-session lane promotion.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Current callback docs explicitly exclude composed operation and method-security annotations; no mapped supported-runtime watcher.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-JPA-NEW-ENTITY

**Verdict:** PROVEN_WITH_SCOPE.

A new entity on a prepared schema becomes available to an already-injected JPA client and round-trips real H2 rows.

**Shipped surfaces:** `docs/product-reference.md:56` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** jpaRefresh=true; sole standard Spring ORM/Hibernate unit with prepared validate schema; autoCompile varied explicitly.

**Bounds:** SapMachine17/21, macOS arm64. Spring ORM6.1.14/Hibernate6.5.3.Final/H2 2.5.250; direct native factory fixture, no Boot application.

**Tests/assertions:** `com.onurkat.reclazz.e2e.NewEntityPreparedSchemaReloadTest#newEntityUsesPreparedSchemaAndFailedValidationCanRetry` (watcher): Six loader/retry/autocompile variants; P=false:true:kept before retry then true:false:kept, ROUNDTRIP=new:kept; later body edit does not rebuild.

**Observable:** A new entity on a prepared schema becomes available to an already-injected JPA client and round-trips real H2 rows.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-JPA-PENDING-METADATA

**Verdict:** PROVEN_WITH_SCOPE.

A failed new-entity validation retry refuses changed field/column metadata until the original mapping is restored.

**Shipped surfaces:** `docs/product-reference.md:56` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** jpaRefresh=true; supported validate mode and original pending mapping.

**Bounds:** SapMachine17/21, macOS arm64. Spring ORM6.1.14/Hibernate6.5.3.Final/H2 2.5.250; direct native factory.

**Tests/assertions:** `com.onurkat.reclazz.e2e.NewEntityPreparedSchemaReloadTest#changedPendingMappingMustBeRestoredBeforeRetry` (watcher): Three field/annotation/autocompile cases; pending entity metadata changed warning, live factory retained, restored mapping roundtrip.

**Observable:** A failed new-entity validation retry refuses changed field/column metadata until the original mapping is restored.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-JPA-VALIDATION-FAILURE

**Verdict:** PARTIAL.

Missing table/column or wrong type retains the old factory/data; prepared retry installs mapping and clears only its own reason.

**Shipped surfaces:** `docs/product-reference.md:56` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring ORM6.1.14/Hibernate6.5.3.Final/H2 2.5.250; direct component.

**Tests/assertions:** `com.onurkat.reclazz.reload.JpaPreparedSchemaTest#validationFailureKeepsLiveFactoryAndManagedNamesThenCanRetry` (component): Three validation problems; factory same/open, managed list same, kept row; retry installs and preserves separate structural concern.

**Observable:** Missing table/column or wrong type retains the old factory/data; prepared retry installs mapping and clears only its own reason.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-JPA-PREPARED-SCHEMA

**Verdict:** PARTIAL.

Successful validate rebuild retires old native factory and already-injected clients see persisted new/existing entities.

**Shipped surfaces:** `docs/product-reference.md:56` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring ORM6.1.14/Hibernate6.5.3.Final/H2 2.5.250; component.

**Tests/assertions:** `com.onurkat.reclazz.reload.JpaPreparedSchemaTest#preparedTableMapsThroughTheAlreadyInjectedClientAndSharedEntityManager` (component): Native factory notSame, old closed, new/kept rows; second identical entity does not rebuild.

**Observable:** Successful validate rebuild retires old native factory and already-injected clients see persisted new/existing entities.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-JPA-SCHEMA-OVERRIDES

**Verdict:** EXPECTED.

Conflicting schema/custom-provider overrides cannot turn a supported prepared-schema validation into DDL or skip validation.

**Shipped surfaces:** `docs/product-reference.md:56` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring ORM6.1.14/Hibernate6.5.3.Final/H2 2.5.250; component.

**Tests/assertions:** `com.onurkat.reclazz.reload.JpaPreparedSchemaTest#schemaOverridesCannotTurnValidationIntoDdlOrSkipIt` (component): Eight override keys in both property maps retain old factory/open and unmodified managed names.

**Observable:** Conflicting schema/custom-provider overrides cannot turn a supported prepared-schema validation into DDL or skip validation.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-JPA-OPTIN-AMBIGUITY

**Verdict:** EXPECTED.

Missing opt-in or ambiguous persistence units retain existing factory identities.

**Shipped surfaces:** `docs/product-reference.md:56` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring ORM6.1.14/Hibernate6.5.3.Final/H2 2.5.250; component.

**Tests/assertions:** `com.onurkat.reclazz.reload.JpaPreparedSchemaTest#absentOptInAndAmbiguousUnitsKeepTheirIdentityAndData` (component): Opt-in false and two units remain same/open.

**Observable:** Missing opt-in or ambiguous persistence units retain existing factory identities.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-JPA-CLOSE-REPORT

**Verdict:** PARTIAL.

An old-factory close failure after successful swap is reported as installed with retirement failure.

**Shipped surfaces:** `docs/product-reference.md:56` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring ORM6.1.14/Hibernate6.5.3.Final/H2 2.5.250; component with synthetic failing-close holder.

**Tests/assertions:** `com.onurkat.reclazz.reload.JpaPreparedSchemaTest#closeFailureAfterSwapIsReportedWithoutClaimingTheOldFactoryWasRetained` (component): Fresh factory same/open; warning contains mapping installed and retiring.

**Observable:** An old-factory close failure after successful swap is reported as installed with retirement failure.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-JPA-QUERY-WATCHER

**Verdict:** PROVEN_WITH_SCOPE.

A JPQL @Query edit reaches an existing HTTP holder and current repository without restarting the app.

**Shipped surfaces:** `docs/usage.md:3118` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring Data JPA3.3.4/Spring6.1.14/Hibernate6.5.3.Final/H2 2.5.250; JPQL, not nativeQuery=true.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringDataRepositoryReloadTest#watchedQueryEditReachesExistingHolderWithoutRestart` (watcher): Four annotated/normal/child combinations; same PID/nonce/holder identity; before/after alternated three saves over native local HTTP.

**Observable:** A JPQL @Query edit reaches an existing HTTP holder and current repository without restarting the app.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-JPA-QUERY-COMPONENT

**Verdict:** PARTIAL.

Native Spring Data JPQL query caches are refreshed through wired holders and tracked dependents.

**Shipped surfaces:** `docs/usage.md:3118` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring Data JPA3.3.4/Spring6.1.14/Hibernate6.5.3.Final/H2 2.5.250; direct redefine+orchestrator, no watcher; native framework query means JPQL, not SQL nativeQuery.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringDataNativeQueryTest#changedNativeQueryReachesExistingManuallyWiredHolderAndTrackedDependent` (component): Redefine alone leaves cached old query; orchestrator changes product identity, keeps holder identity, both holders get before/after.

**Observable:** Native Spring Data JPQL query caches are refreshed through wired holders and tracked dependents.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-JPA-REPOSITORY-AFTER-EMF-SWAP

**Verdict:** PROVEN_WITH_SCOPE.

An already-injected Spring Data repository remains usable after a native EntityManagerFactory swap.

**Shipped surfaces:** `docs/usage.md` (Held repositories after a prepared mapping refresh; current location/digest pinned in JSON).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** Stock SapMachine17/21 on macOS arm64; isolated Spring6.1.14 / Spring Data JPA3.3.4 / Hibernate6.5.3.Final / H22.5.250; startup watcher, normal/child application loader, jpaRefresh=true, single-unit new-entity prepared-schema mapping with validate. Original Existing-root repository with custom fragment holding original SharedEntityManager.

**Tests/assertions:** `com.onurkat.reclazz.e2e.RepositoryAfterPreparedEntityFactorySwapReloadTest#heldRepositoryReadsAndWritesAfterPreparedSwapAndValidationRetry` (watcher): Four prepared/missing-table-retry normal/child cases. Warm original JpaRepository<Existing,Long> find/count/cached JPQL and original shared-EntityManager custom fragment; same held repo writes/reads Existing and fragment queries Added after actual watched validate-only new-entity swap. Exact expected results/fresh agentless oracle; original repo/holder/injected-client/shared identities, old native factory different/closed, unrelated anchor retained; body-only mapped save does not rebuild.; `com.onurkat.reclazz.e2e.RepositoryAfterPreparedEntityFactorySwapReloadTest#optOutAndTwoUnitsNeverSwapHeldRepositoryInfrastructure` (watcher): Four opt-out/two-unit ambiguity normal/child cases. Added remains UNMAPPED, existing rows/native identity/open state retained, held injected repository/holder/EMF-client/shared identities and unrelated context anchor retained; original second native factory stays open. Explicit opt-in/ambiguity log checks, no second-unit ownership inference.

**Observable:** After prepared-schema native EMF swap, the original held Existing-root repository writes/reads Existing through saveAndFlush/findById/cached JPQL/count and its original-shared-EntityManager fragment queries newly mapped Added. Old native factory retires; injected identities/unrelated anchor remain; missing-table retry and opt-out/two-unit refusals match native data/state controls.

**Control:** Fresh agentless native oracle per mapping state plus exact data/nativeSame/oldOpen/otherOpen values and identity guards. Research prepared install withheld4/4 RED, restored identical production/SAME8/8 GREEN. Independent audit rechecks the newly ported observer, not a claimed current main negative run.

**Execution:** Ported main observer8/8 PASSED and26 existing prepared-schema/new-entity neighbors PASSED on each JDK17/21 lane. Exact commands, XML invocations, source/artifact digests and native controls recorded in JSON. Approved evidence-only targeted gate; no full-suite result claimed.

**Limitation / missing environment:** H2 prepared NEW entity mapping only; repository domain root stays Existing, custom fragment queries Added. No enhanced existing persistent-field edits, Spring5/Boot2 javax graph, lazy/cold products, active transactions/open persistence contexts, other dialects/vendors/providers, generic root becoming a new type, startup Query initially naming unmapped entity, real-provider failing-close integration, simultaneous publication, multi-unit ownership inference or schema migration. The other-unit control observes its original factory open state and anchor, not an arbitrary replacement identity graph. All unlisted JVM providers/OS/framework/database graphs, late attachment and broader lifecycle shapes remain unverified; enhanced VM unavailable in inspected environment.

**Reviewer / next action:** Astra tracked main source and JDK17/21 receipts mapped; Claude independent audit pending. Independent Claude audit, class-name-only source comparison, ported scratch install-disabled RED/restored GREEN, then local-only commit. Other eight RF09 records and environment/lifecycle axes remain pending.

### P3C-JPA-FIELD-DETECTION

**Verdict:** PARTIAL.

Persistent field additions/removals are detected before redefinition without mistaking injected/unmapped fields for schema edits.

**Shipped surfaces:** `docs/product-reference.md:56` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Component descriptor inspection on ordinary test classpath; no enhanced VM.

**Tests/assertions:** `com.onurkat.reclazz.reload.JpaEntityChangeTest#aNewPersistentFieldOnAnEntityIsReported` (component): Currency added and gained text; bytecode scanner only, no DB roundtrip. `com.onurkat.reclazz.reload.JpaEntityChangeTest#aRemovedPersistentFieldIsReportedToo` (component): Currency removed list and lost text. `com.onurkat.reclazz.reload.JpaEntityChangeTest#fieldsOutsideTheMappingDoNotCount` (component): Static/transient/@Transient changes do not count. `com.onurkat.reclazz.reload.JpaEntityChangeTest#theInjectedMembersAreNotMistakenForMapping` (component): Injected __reclazz$ext not falsely treated as removal. `com.onurkat.reclazz.reload.JpaEntityChangeTest#theReloadEngineComparesBeforeRedefining` (component): Source guard checks comparison precedes actual redefine call; no live enhanced VM proof.

**Observable:** Persistent field additions/removals are detected before redefinition without mistaking injected/unmapped fields for schema edits.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-JPA-UPDATE-ADVICE

**Verdict:** PARTIAL.

For ddl-auto update the restart advice does not falsely demand manual column creation.

**Shipped surfaces:** `docs/product-reference.md:56` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Component advice function; no real DDL run.

**Tests/assertions:** `com.onurkat.reclazz.reload.JpaSchemaAdviceTest#withUpdateARestartIsTheWholeFix` (component): Advice contains restart enough and excludes add column; text contract only.

**Observable:** For ddl-auto update the restart advice does not falsely demand manual column creation.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-JPA-VALIDATE-ADVICE

**Verdict:** PARTIAL.

For ddl-auto validate the advice requires the column before restarting.

**Shipped surfaces:** `docs/product-reference.md:56` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Component advice function; no DB startup claim.

**Tests/assertions:** `com.onurkat.reclazz.reload.JpaSchemaAdviceTest#withValidateTheColumnHasToComeFirst` (component): Advice contains Add column first and refuse to start; text contract only.

**Observable:** For ddl-auto validate the advice requires the column before restarting.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-JPA-FIELD-REFRESH-GATE

**Verdict:** EXPECTED.

Existing-field refresh on a stock VM declines silently and only schema-creating settings qualify for the enhanced path.

**Shipped surfaces:** `docs/product-reference.md:56` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Component stock VM capability gate; enhanced branch UNVERIFIED.

**Tests/assertions:** `com.onurkat.reclazz.reload.JpaMappingRefreshTest#withoutEnhancedRedefinitionTheDeclineIsSilent` (component): Refreshed=false and empty appendix; no JBR/DCEVM run or DB mapping refresh.

**Observable:** Existing-field refresh on a stock VM declines silently and only schema-creating settings qualify for the enhanced path.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-JPA-DDL-GATE

**Verdict:** EXPECTED.

Existing-field refresh ddl policy accepts update/create/create-drop and refuses validate/none/unknown/unreadable.

**Shipped surfaces:** `docs/product-reference.md:56` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Component policy gate, no DDL run.

**Tests/assertions:** `com.onurkat.reclazz.reload.JpaMappingRefreshTest#onlyTheSchemaCreatingSettingsQualify` (component): Exact true/false policy assertions; distinct from new-entity prepared validate path.

**Observable:** Existing-field refresh ddl policy accepts update/create/create-drop and refuses validate/none/unknown/unreadable.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-JPA-ENHANCED-FIELD

**Verdict:** UNVERIFIED.

Existing entity persistent-field edits reach rebuilt mappings and database on enhanced redefinition VMs.

**Shipped surfaces:** `docs/product-reference.md:56` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** jpaRefresh=true plus enhanced structural redefinition and supported schema-creating setting.

**Bounds:** SapMachine17/21, macOS arm64. JBR/DCEVM + ddl-auto update/create/create-drop; not stock new-entity path.

**Tests/assertions:** No observing test mapped; UNVERIFIED.

**Observable:** Existing entity persistent-field edits reach rebuilt mappings and database on enhanced redefinition VMs.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** No current-session lane promotion.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. No enhanced JBR/DCEVM lane or live existing-field DB watcher in this package.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-JPA-SPRING5

**Verdict:** UNVERIFIED.

The prepared-schema/new-entity path works on Spring5/Boot2 JPA.

**Shipped surfaces:** `docs/product-reference.md:56` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring5/Boot2 persistence integration.

**Tests/assertions:** No observing test mapped; UNVERIFIED.

**Observable:** The prepared-schema/new-entity path works on Spring5/Boot2 JPA.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** No current-session lane promotion.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. No isolated Spring5 ORM/Hibernate JPA acceptance environment mapped; source-level declarations are not runtime proof.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-HIBERNATE-L2-ENTITY

**Verdict:** PARTIAL.

Explicit DAO invalidation evicts real cached entity data so a later load sees an external SQL update.

**Shipped surfaces:** `agent/src/main/java/com/onurkat/reclazz/platform/HibernateCacheInvalidator.java:16` (SHA-256 `a8e7242c4429c923454365c8cfd8b7657bcac082d7b7cf0a2ab0971978414d6b`).

**Default/opt-in:** Optional Hibernate ORM with L2/query caching as the fixture enables; direct invalidator invocation only. Startup-agent DAO watcher behavior remains unverified.

**Bounds:** SapMachine17/21, macOS arm64. Hibernate6.5.3.Final/JCache Caffeine3.3.0/H2 2.5.250/Spring5.3.39; direct invalidator call, no DAO watcher.

**Tests/assertions:** `com.onurkat.reclazz.hybris.hibernate.HibernateL2CacheTest#daoReloadEvictsRealEntityAndQueryCaches` (component): Real L2 entry and cache hits; SQL update still reads old until invalidator, then new.

**Observable:** Explicit DAO invalidation evicts real cached entity data so a later load sees an external SQL update.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-HIBERNATE-L2-QUERY

**Verdict:** PARTIAL.

Explicit DAO invalidation evicts query cache so a later cacheable query sees an external SQL update.

**Shipped surfaces:** `agent/src/main/java/com/onurkat/reclazz/platform/HibernateCacheInvalidator.java:16` (SHA-256 `a8e7242c4429c923454365c8cfd8b7657bcac082d7b7cf0a2ab0971978414d6b`).

**Default/opt-in:** Optional Hibernate ORM with L2/query caching as the fixture enables; direct invalidator invocation only. Startup-agent DAO watcher behavior remains unverified.

**Bounds:** SapMachine17/21, macOS arm64. Hibernate6.5.3.Final/JCache Caffeine3.3.0/H2 2.5.250/Spring5.3.39; direct invalidator call.

**Tests/assertions:** `com.onurkat.reclazz.hybris.hibernate.HibernateL2CacheTest#daoReloadEvictsRealEntityAndQueryCaches` (component): Real query hits, old result after SQL update before invalidation, new after.

**Observable:** Explicit DAO invalidation evicts query cache so a later cacheable query sees an external SQL update.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-HIBERNATE-L2-ALIASES

**Verdict:** PARTIAL.

Aliases and repeated contexts evict a single native Hibernate factory once.

**Shipped surfaces:** `agent/src/main/java/com/onurkat/reclazz/platform/HibernateCacheInvalidator.java:16` (SHA-256 `a8e7242c4429c923454365c8cfd8b7657bcac082d7b7cf0a2ab0971978414d6b`).

**Default/opt-in:** Optional Hibernate ORM with L2/query caching as the fixture enables; direct invalidator invocation only. Startup-agent DAO watcher behavior remains unverified.

**Bounds:** SapMachine17/21, macOS arm64. Hibernate6.5.3.Final/JCache Caffeine3.3.0/H2 2.5.250/Spring5.3.39; direct component.

**Tests/assertions:** `com.onurkat.reclazz.hybris.hibernate.HibernateL2CacheTest#aliasesAndRepeatedContextsEvictAFactoryOnlyOnce` (component): Two names same factory, repeated same context; invalidated count1, no cached entity.

**Observable:** Aliases and repeated contexts evict a single native Hibernate factory once.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-HIBERNATE-L2-DISABLED

**Verdict:** EXPECTED.

Disabled Hibernate L2 is not reported as an eviction.

**Shipped surfaces:** `agent/src/main/java/com/onurkat/reclazz/platform/HibernateCacheInvalidator.java:16` (SHA-256 `a8e7242c4429c923454365c8cfd8b7657bcac082d7b7cf0a2ab0971978414d6b`).

**Default/opt-in:** Optional Hibernate ORM with L2/query caching as the fixture enables; direct invalidator invocation only. Startup-agent DAO watcher behavior remains unverified.

**Bounds:** SapMachine17/21, macOS arm64. Hibernate6.5.3.Final/JCache Caffeine3.3.0/H2 2.5.250/Spring5.3.39; direct component.

**Tests/assertions:** `com.onurkat.reclazz.hybris.hibernate.HibernateL2CacheTest#disabledL2IsNotReportedAsAnEviction` (component): Direct invalidator returns0 when L2/query caches disabled.

**Observable:** Disabled Hibernate L2 is not reported as an eviction.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-HIBERNATE-L2-WATCHER

**Verdict:** UNVERIFIED.

A watched DAO class edit triggers real native L2/query eviction.

**Shipped surfaces:** `agent/src/main/java/com/onurkat/reclazz/platform/HibernateCacheInvalidator.java:16` (SHA-256 `a8e7242c4429c923454365c8cfd8b7657bcac082d7b7cf0a2ab0971978414d6b`).

**Default/opt-in:** Optional Hibernate ORM with L2/query caching as the fixture enables; direct invalidator invocation only. Startup-agent DAO watcher behavior remains unverified.

**Bounds:** SapMachine17/21, macOS arm64. Optional Hibernate integration, synthetic DAO watcher.

**Tests/assertions:** No observing test mapped; UNVERIFIED.

**Observable:** A watched DAO class edit triggers real native L2/query eviction.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** No current-session lane promotion.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Mapped HibernateL2CacheTest invokes invalidator directly; real DAO watcher is not observed.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-SPRING6-NATIVE-EVENT-FAILURE

**Verdict:** PROVEN_WITH_SCOPE.

Added ordinary and async event callbacks work in the measured Spring6 native transaction-enabled context; the historical cell ID is retained.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Native @EnableTransactionManagement, plus EnableAsync or EnableCaching where the observing fixture uses it; startup javaagent and compiled watched saves. No new agent option.

**Bounds:** SapMachine17/21, macOS arm64; native Spring6.1.14 @EnableTransactionManagement, normal/child application loaders, startup javaagent and compiled saves.

**Tests/assertions:** `com.onurkat.reclazz.e2e.JakartaAddedAsyncEventReloadTest#asyncEventsUseNativeExecutorAndKeepAdmittedBodyAcrossReloadAndRemoval` (watcher): R1..5 exact queue/captured/rerouted/original/refused/rollback/handler/removed checks; reflected=false. `com.onurkat.reclazz.e2e.JakartaAddedCallbackOperationsReloadTest#callbacksUseNativeTransactionsAndCachesAcrossSaves` (watcher): R1/4 rows2 txtrue, R2 rows4 txfalse, R3 removed rows0; actual H2 writes; registered scheduled Runnable invoked explicitly.

**Observable:** Exact worker queues/rollback/cache/returned-event counts for ordinary async/operation callbacks across compiled saves.

**Control:** Desired watcher cases fail before repair (6/6 on21) and pass after repair on17/21. Native component controls separately verify custom/mixed/duplicate/order refusal and no old listener survival.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Exact native factory recognition only. Custom subclasses, duplicate transactional family, wrong factory order and extra advice on phase callbacks remain refused. No broader callback subset or framework-version promise. Unlisted Spring versions/OS, late attach and atomic concurrent publication remain unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-SPRING6-NATIVE-TXEVENT-FAILURE

**Verdict:** PROVEN_WITH_SCOPE.

Added transaction-phase callbacks work with Spring6 native RestrictedTransactionalEventListenerFactory across commit, rollback, queued replacement, removal and restoration; the historical cell ID is retained.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Native @EnableTransactionManagement, plus EnableAsync or EnableCaching where the observing fixture uses it; startup javaagent and compiled watched saves. No new agent option.

**Bounds:** SapMachine17/21, macOS arm64; native Spring6.1.14 @EnableTransactionManagement, normal/child application loaders, startup javaagent and compiled saves.

**Tests/assertions:** `com.onurkat.reclazz.e2e.JakartaAddedTransactionalEventListenerReloadTest#transactionPhasesAndQueuedEventsSurviveEditsRemovalAndRestoration` (watcher): QUEUED2 before reload; HELD2 contains only ok-new generation2; HELD3 empty after removal; MATRIX4 restored.

**Observable:** Exact commit/rollback phase tape, SQL row counts and queued retirement/removal/restoration across compiled saves.

**Control:** Desired watcher cases fail before repair (6/6 on21) and pass after repair on17/21. Native component controls separately verify custom/mixed/duplicate/order refusal and no old listener survival.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Exact native factory recognition only. Custom subclasses, duplicate transactional family, wrong factory order and extra advice on phase callbacks remain refused. No broader callback subset or framework-version promise. Unlisted Spring versions/OS, late attach and atomic concurrent publication remain unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-SCHEDULED-REREGISTER

**Verdict:** PROVEN_WITH_SCOPE.

An existing reflected scheduled timer follows saved intervals/current singleton and cancels every retired instance across repeated saves.

**Shipped surfaces:** `docs/product-reference.md:94` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64; Spring5.3.39/6.1.14; normal application loader, native fixed-delay timer, three sequential saves, fast callbacks; no in-flight cancellation guarantee.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ExistingScheduledMetadataReloadTest#existingTimerUsesSavedIntervalAndCurrentSingletonWithoutRetiredTicks` (watcher): One real native task with intervals25/40/60/25; body values1/2/3/4; current receiver ticks, all retired instance counters remain stable; context close cancels future ticks. `com.onurkat.reclazz.e2e.JakartaExistingScheduledMetadataReloadTest#existingTimerUsesSavedIntervalAndCurrentSingletonWithoutRetiredTicks` (watcher): One real native task with intervals25/40/60/25; body values1/2/3/4; current receiver ticks, all retired instance counters remain stable; context close cancels future ticks.

**Observable:** One real native task with intervals25/40/60/25; body values1/2/3/4; current receiver ticks, all retired instance counters remain stable; context close cancels future ticks.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-EVENT-EXISTING-COMPONENT

**Verdict:** PARTIAL.

Reflected existing event adapters refresh only the affected class without multiplying unrelated/manual listeners.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringEventReloaderTest#reloadingOneClassDoesNotMultiplyUnrelatedListeners` (component): Four refresh cycles: First/Second/manual counters advance once each; native context registry size unchanged.

**Observable:** Four refresh cycles: First/Second/manual counters advance once each; native context registry size unchanged.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-ASYNC-EXISTING-COMPONENT

**Verdict:** PARTIAL.

Repeated native async reprocessing retains exactly one configured advisor and one delivery per invocation.

**Shipped surfaces:** `docs/product-reference.md:98` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** SapMachine17/21, macOS arm64. Spring 5.3.39; direct component invocation, no file watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringAddedAsyncEventTest#repeatedAsyncRefreshDoesNotInstallTheSameAdvisorTwice` (component): Three direct reloader calls; proxy advisors length1; native callback count advances once; executor queue empty.

**Observable:** Three direct reloader calls; proxy advisors length1; native callback count advances once; executor queue empty.

**Control:** Exact positive/negative assertions in the named methods; whole-observer scratch disabled-hook witnesses are recorded separately and do not certify every sub-assertion.

**Execution:** Exact lane commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the listed observables and fixture shapes; no atomic in-flight publication or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment and unlisted framework versions are unverified.

**Reviewer / next action:** Astra read source assertions; independent Claude audit pending. Independent Claude audit and local-only commit. Missing combined scenarios stay unverified; the native Spring6 factory repair now has separate RED/GREEN evidence; its independent audit is pending.

### P3C-JPA-SQL-NATIVE-QUERY

**Verdict:** PROVEN_WITH_SCOPE.

An edited SQL @Query(nativeQuery=true) reaches existing wired repositories after watcher reload.

**Shipped surfaces:** `docs/usage.md:3118` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent; compile watched class files; existing relevant native Spring infrastructure.

**Bounds:** Stock SapMachine17/21 on macOS arm64; isolated Spring6.1.14 / Spring Data JPA3.3.4 / Hibernate6.5.3.Final / H22.5.250; existing wired repository interface with/without Repository stereotype, normal/child application loader; retained Spring-managed ordinary singleton field and current Spring-tracked dependent.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringDataSqlNativeQueryReloadTest#currentTrackedDependentAndRetainedManualHolderMatchNative` (watcher). Four normal/child loader x plain/Repository interfaces; six compiled SQL-native saves: predicate, UPPER scalar expression, missing-column SQL_ERROR:42122, valid recovery, CONCAT scalar expression, revert. Exact expected result and fresh agentless native JVM per version. Retained managed manual-holder identity, current tracked owner distinct from startup holder/current injected-product identity; unrelated and other-context repository identities, both EMF identities/open state,3 rows.

**Observable:** Six saved SQL-native predicate/scalar-expression/error/recovery/revert stages match exact H2 results and a fresh agentless native JVM; current tracked owner uses current injected repository, retained managed manual holder follows saves without replacement, unrelated repositories/EMFs remain unchanged.

**Control:** Fresh agentless native control for each version plus exact expected strings and stable unrelated/context/EMF/row controls. Research retained-holder hook-disabled counterexample4/4 RED then byte-for-byte restoration/SAME4/4 GREEN; independent audit must recheck the ported tracked-owner observer. No fixture cache reset/context restart.

**Execution:** Main tracked observer4/4 PASSED and15 existing Spring Data neighbors PASSED on each SapMachine17/21 lane. Exact commands, XML invocations and source/artifact digests are recorded in JSON. This evidence-only change uses the approved targeted gate; no new full-suite result is claimed.

**Limitation / missing environment:** H2 scalar SQL only. Current native recorded dependents follow normal recreation; externally-held retired owners/repository proxies are not rewritten. No other DB dialects/vendors, new interface methods, pagination, projection interfaces, parameter-binding variants, non-singleton products, other Data modules/versions/proxy shapes/custom factories, concurrent publication or EMF swap (RF09). Other OS/JDK providers and all unlisted frameworks/databases/late attachment remain unverified.

**Reviewer / next action:** Astra main tracked source and JDK17/21 receipts mapped; independent Claude audit pending. Independent Claude audit of source equivalence, main receipts and a scratch hook-disabled counterexample on the ported observer, then local-only commit.

## P5: Properties, XML, messages and templates

This evidence package separates actual fields, bound values, singleton identities, native message lookups and cached template renders from component-only contracts and missing environments. Unsupported XML guards prove refusal, not live metadata/product refresh. Native engine dependencies are test-only; agent production sources are unchanged.

Machine record: [p5-resources.json](feature-evidence/p5-resources.json); manifest: [p5-cells.txt](feature-evidence/p5-cells.txt). Independent Claude audit is pending.

### P5-VALUE-FIELD

**Verdict:** PROVEN_WITH_SCOPE.

Supported computed scalar fields change with the accepted property candidate while singleton identity stays the same.

**Shipped surfaces:** `docs/product-reference.md:60` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ComputedValueFieldReloadTest#computedFieldsUpdateRejectInvalidSavesAndRecoverWithoutRestart` (watcher): PROBE1=8000:25:new:8:4:new:0:same; PROBE3 recovered=10000:20:recovered; context/field values both checked. `com.onurkat.reclazz.e2e.ModernComputedValueFieldReloadTest#computedFieldsUpdateRejectInvalidSavesAndRecoverWithoutRestart` (watcher): PROBE1=8000:25:new:8:4:new:0:same; PROBE3 recovered=10000:20:recovered; context/field values both checked.

**Observable:** Supported computed scalar fields change with the accepted property candidate while singleton identity stays the same.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-VALUE-FIELD-REJECT

**Verdict:** EXPECTED.

Division-by-zero candidate keeps field values and Environment at the last accepted values.

**Shipped surfaces:** `docs/product-reference.md:60` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ComputedValueFieldReloadTest#computedFieldsUpdateRejectInvalidSavesAndRecoverWithoutRestart` (watcher): PROBE2 identical to PROBE1 after rejected divisor0 save. `com.onurkat.reclazz.e2e.ModernComputedValueFieldReloadTest#computedFieldsUpdateRejectInvalidSavesAndRecoverWithoutRestart` (watcher): PROBE2 identical to PROBE1 after rejected divisor0 save.

**Observable:** Division-by-zero candidate keeps field values and Environment at the last accepted values.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-VALUE-FIELD-UNSAFE

**Verdict:** EXPECTED.

Unsupported method-call expression does not run its body or change live field/configuration values.

**Shipped surfaces:** `docs/product-reference.md:60` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ComputedValueFieldReloadTest#computedFieldsUpdateRejectInvalidSavesAndRecoverWithoutRestart` (watcher): Uncheckable plus PROBE4 kept recovered values and sideEffect calls0. `com.onurkat.reclazz.e2e.ModernComputedValueFieldReloadTest#computedFieldsUpdateRejectInvalidSavesAndRecoverWithoutRestart` (watcher): Uncheckable plus PROBE4 kept recovered values and sideEffect calls0.

**Observable:** Unsupported method-call expression does not run its body or change live field/configuration values.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-VALUE-CONSTRUCTOR

**Verdict:** PROVEN_WITH_SCOPE.

Accepted computed constructor values recreate the singleton and heal an already-held registered singleton reference.

**Shipped surfaces:** `docs/product-reference.md:61` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ComputedValueConstructorReloadTest#computedConstructorUpdatesRejectsAndRecoversWithoutRestart` (watcher): PROBE1 constructions2, healed; recovery constructions3, healed. `com.onurkat.reclazz.e2e.ModernComputedValueConstructorReloadTest#computedConstructorUpdatesRejectsAndRecoversWithoutRestart` (watcher): PROBE1 constructions2, healed; recovery constructions3, healed.

**Observable:** Accepted computed constructor values recreate the singleton and heal an already-held registered singleton reference.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-VALUE-CONSTRUCTOR-REJECT

**Verdict:** EXPECTED.

Rejected computed constructor candidate does not construct a replacement or alter the holder/configuration.

**Shipped surfaces:** `docs/product-reference.md:61` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ComputedValueConstructorReloadTest#computedConstructorUpdatesRejectsAndRecoversWithoutRestart` (watcher): PROBE2 same as1; constructions2, calls0, healed. `com.onurkat.reclazz.e2e.ModernComputedValueConstructorReloadTest#computedConstructorUpdatesRejectsAndRecoversWithoutRestart` (watcher): PROBE2 same as1; constructions2, calls0, healed.

**Observable:** Rejected computed constructor candidate does not construct a replacement or alter the holder/configuration.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-VALUE-CONSTRUCTOR-UNSAFE

**Verdict:** EXPECTED.

Unsupported computed constructor expression is held before its side-effect or replacement constructor runs.

**Shipped surfaces:** `docs/product-reference.md:61` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ComputedValueConstructorReloadTest#computedConstructorUpdatesRejectsAndRecoversWithoutRestart` (watcher): PROBE4 equals recovered, constructions3, calls0. `com.onurkat.reclazz.e2e.ModernComputedValueConstructorReloadTest#computedConstructorUpdatesRejectsAndRecoversWithoutRestart` (watcher): PROBE4 equals recovered, constructions3, calls0.

**Observable:** Unsupported computed constructor expression is held before its side-effect or replacement constructor runs.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-VALUE-TEXT-TEMPLATE

**Verdict:** PROVEN_WITH_SCOPE.

A scalar mixed text/SpEL @Value template follows supported property saves.

**Shipped surfaces:** `docs/product-reference.md:60` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ComputedValueTemplateReloadTest#templateValuesFollowPropertyChangesAndHoldOnUncheckable` (watcher): host:9 -> host:11 -> host:21; [1/2] -> [3/4] -> [5/6]; plain label new/recovered. `com.onurkat.reclazz.e2e.ModernComputedValueTemplateReloadTest#templateValuesFollowPropertyChangesAndHoldOnUncheckable` (watcher): host:9 -> host:11 -> host:21; [1/2] -> [3/4] -> [5/6]; plain label new/recovered.

**Observable:** A scalar mixed text/SpEL @Value template follows supported property saves.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-VALUE-TEXT-UNSAFE

**Verdict:** EXPECTED.

Unsupported expression inside a scalar @Value text template holds all fields and can recover.

**Shipped surfaces:** `docs/product-reference.md:60` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ComputedValueTemplateReloadTest#templateValuesFollowPropertyChangesAndHoldOnUncheckable` (watcher): Uncheckable segment keeps host:11/[3/4]/new, correction changes all. `com.onurkat.reclazz.e2e.ModernComputedValueTemplateReloadTest#templateValuesFollowPropertyChangesAndHoldOnUncheckable` (watcher): Uncheckable segment keeps host:11/[3/4]/new, correction changes all.

**Observable:** Unsupported expression inside a scalar @Value text template holds all fields and can recover.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-VALUE-COLLECTION

**Verdict:** PROVEN_WITH_SCOPE.

Supported inline SpEL List/Map/Set/primitive-array fields update their actual elements.

**Shipped surfaces:** `docs/product-reference.md:60` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ComputedValueCollectionReloadTest#inlineCollectionValuesFollowPropertyChangesAndHoldOnUncheckable` (watcher): Exact List[7,8], Map{x=7,y=8}, Set[7,8], int[][7,8], recovery10/11. `com.onurkat.reclazz.e2e.ModernComputedValueCollectionReloadTest#inlineCollectionValuesFollowPropertyChangesAndHoldOnUncheckable` (watcher): Exact List[7,8], Map{x=7,y=8}, Set[7,8], int[][7,8], recovery10/11.

**Observable:** Supported inline SpEL List/Map/Set/primitive-array fields update their actual elements.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-VALUE-COLLECTION-REJECT

**Verdict:** EXPECTED.

Invalid collection element conversion holds every participating field and correction recovers.

**Shipped surfaces:** `docs/product-reference.md:60` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ComputedValueCollectionReloadTest#inlineCollectionValuesFollowPropertyChangesAndHoldOnUncheckable` (watcher): oops keeps all accepted7/8 and labelnew, correction10/11. `com.onurkat.reclazz.e2e.ModernComputedValueCollectionReloadTest#inlineCollectionValuesFollowPropertyChangesAndHoldOnUncheckable` (watcher): oops keeps all accepted7/8 and labelnew, correction10/11.

**Observable:** Invalid collection element conversion holds every participating field and correction recovers.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-INDIRECT-CHAIN

**Verdict:** PROVEN_WITH_SCOPE.

A changed source behind chained placeholders updates computed fields and recreates the constructor consumer/holder.

**Shipped surfaces:** `docs/product-reference.md:60` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.IndirectValueDependencyReloadTest#indirectValuesUpdateRejectAndRecoverThroughWatchedSaves` (watcher): PROBE1=8000:8000:25:new:8:4:new:0:2:healed; recovery10000 and constructions3. `com.onurkat.reclazz.e2e.ModernIndirectValueDependencyReloadTest#indirectValuesUpdateRejectAndRecoverThroughWatchedSaves` (watcher): PROBE1=8000:8000:25:new:8:4:new:0:2:healed; recovery10000 and constructions3.

**Observable:** A changed source behind chained placeholders updates computed fields and recreates the constructor consumer/holder.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-INDIRECT-INVALID

**Verdict:** EXPECTED.

Invalid indirect expression holds the prior values and correction retries all pending keys.

**Shipped surfaces:** `docs/product-reference.md:60` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.IndirectValueDependencyReloadTest#indirectValuesUpdateRejectAndRecoverThroughWatchedSaves` (watcher): Rejected divisor0 yields PROBE2 equal1; PROBE3 recovered. `com.onurkat.reclazz.e2e.ModernIndirectValueDependencyReloadTest#indirectValuesUpdateRejectAndRecoverThroughWatchedSaves` (watcher): Rejected divisor0 yields PROBE2 equal1; PROBE3 recovered.

**Observable:** Invalid indirect expression holds the prior values and correction retries all pending keys.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-INDIRECT-CYCLE

**Verdict:** EXPECTED.

An introduced placeholder cycle is held without changing accepted live values.

**Shipped surfaces:** `docs/product-reference.md:60` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.IndirectValueDependencyReloadTest#indirectValuesUpdateRejectAndRecoverThroughWatchedSaves` (watcher): PROBE4 equals recovered; cyclic save Uncheckable. `com.onurkat.reclazz.e2e.ModernIndirectValueDependencyReloadTest#indirectValuesUpdateRejectAndRecoverThroughWatchedSaves` (watcher): PROBE4 equals recovered; cyclic save Uncheckable.

**Observable:** An introduced placeholder cycle is held without changing accepted live values.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-FACTORY-ARGUMENT

**Verdict:** PROVEN_WITH_SCOPE.

Native @Bean scalar @Value arguments recreate a product and heal an existing holder.

**Shipped surfaces:** `docs/product-reference.md:61` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.FactoryValueArgumentReloadTest#factoryArgumentsReloadInOrdinaryLoader` (watcher): Both loader methods PROBE1 constructions2/healed and3 constructions3/healed. `com.onurkat.reclazz.e2e.FactoryValueArgumentReloadTest#factoryArgumentsReloadInChildLoader` (watcher): Both loader methods PROBE1 constructions2/healed and3 constructions3/healed. `com.onurkat.reclazz.e2e.ModernFactoryValueArgumentReloadTest#factoryArgumentsReloadInOrdinaryLoader` (watcher): Both loader methods PROBE1 constructions2/healed and3 constructions3/healed. `com.onurkat.reclazz.e2e.ModernFactoryValueArgumentReloadTest#factoryArgumentsReloadInChildLoader` (watcher): Both loader methods PROBE1 constructions2/healed and3 constructions3/healed.

**Observable:** Native @Bean scalar @Value arguments recreate a product and heal an existing holder.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-FACTORY-REJECT

**Verdict:** EXPECTED.

Invalid native factory argument candidate retains all live values without extra construction.

**Shipped surfaces:** `docs/product-reference.md:61` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.FactoryValueArgumentReloadTest#factoryArgumentsReloadInOrdinaryLoader` (watcher): PROBE2 equals1 including constructions2 and calls0. `com.onurkat.reclazz.e2e.FactoryValueArgumentReloadTest#factoryArgumentsReloadInChildLoader` (watcher): PROBE2 equals1 including constructions2 and calls0. `com.onurkat.reclazz.e2e.ModernFactoryValueArgumentReloadTest#factoryArgumentsReloadInOrdinaryLoader` (watcher): PROBE2 equals1 including constructions2 and calls0. `com.onurkat.reclazz.e2e.ModernFactoryValueArgumentReloadTest#factoryArgumentsReloadInChildLoader` (watcher): PROBE2 equals1 including constructions2 and calls0.

**Observable:** Invalid native factory argument candidate retains all live values without extra construction.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-FACTORY-UNSAFE

**Verdict:** EXPECTED.

Unsafe factory argument expression is held without application-code execution.

**Shipped surfaces:** `docs/product-reference.md:61` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.FactoryValueArgumentReloadTest#factoryArgumentsReloadInOrdinaryLoader` (watcher): PROBE4 equals recovered and sideEffect calls0. `com.onurkat.reclazz.e2e.FactoryValueArgumentReloadTest#factoryArgumentsReloadInChildLoader` (watcher): PROBE4 equals recovered and sideEffect calls0. `com.onurkat.reclazz.e2e.ModernFactoryValueArgumentReloadTest#factoryArgumentsReloadInOrdinaryLoader` (watcher): PROBE4 equals recovered and sideEffect calls0. `com.onurkat.reclazz.e2e.ModernFactoryValueArgumentReloadTest#factoryArgumentsReloadInChildLoader` (watcher): PROBE4 equals recovered and sideEffect calls0.

**Observable:** Unsafe factory argument expression is held without application-code execution.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-ADDED-FACTORY-ARGUMENT

**Verdict:** PROVEN_WITH_SCOPE.

A factory added after startup recreates its product when a scalar @Value dependency changes.

**Shipped surfaces:** `docs/product-reference.md:61` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedFactoryValueArgumentReloadTest#addedFactoryValuesFollowPropertyEdits` (watcher): Four normal/child and full/lite configuration combinations; PROBE1 current product+holder+alias=true, physical added factory absent. `com.onurkat.reclazz.e2e.ModernAddedFactoryValueArgumentReloadTest#addedFactoryValuesFollowPropertyEdits` (watcher): Four normal/child and full/lite configuration combinations; PROBE1 current product+holder+alias=true, physical added factory absent.

**Observable:** A factory added after startup recreates its product when a scalar @Value dependency changes.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-ADDED-FACTORY-LIFECYCLE

**Verdict:** PROVEN_WITH_SCOPE.

Repeated property recreation retires old added-factory products and closes the current one once.

**Shipped surfaces:** `docs/product-reference.md:61` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedFactoryValueArgumentReloadTest#addedFactoryValuesFollowPropertyEdits` (watcher): Built2/3/5 and closed1/2/4; CLOSED5 at context close. `com.onurkat.reclazz.e2e.ModernAddedFactoryValueArgumentReloadTest#addedFactoryValuesFollowPropertyEdits` (watcher): Built2/3/5 and closed1/2/4; CLOSED5 at context close.

**Observable:** Repeated property recreation retires old added-factory products and closes the current one once.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-ADDED-FACTORY-DEPENDENCY-EDIT

**Verdict:** PROVEN_WITH_SCOPE.

Editing the saved added factory installs its new @Value dependency for subsequent property saves.

**Shipped surfaces:** `docs/product-reference.md:61` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedFactoryValueArgumentReloadTest#addedFactoryValuesFollowPropertyEdits` (watcher): RECAPTURED6000 followed by cfg.next9 yields PROBE5 millis9000, healed holder/alias. `com.onurkat.reclazz.e2e.ModernAddedFactoryValueArgumentReloadTest#addedFactoryValuesFollowPropertyEdits` (watcher): RECAPTURED6000 followed by cfg.next9 yields PROBE5 millis9000, healed holder/alias.

**Observable:** Editing the saved added factory installs its new @Value dependency for subsequent property saves.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-ADDED-FACTORY-REJECT

**Verdict:** EXPECTED.

Invalid or unsafe values for an added factory hold configuration, existing product and lifecycle counters.

**Shipped surfaces:** `docs/product-reference.md:61` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.AddedFactoryValueArgumentReloadTest#addedFactoryValuesFollowPropertyEdits` (watcher): PROBE2=1; PROBE4=3 with calls0 and unchanged build/destroy counters. `com.onurkat.reclazz.e2e.ModernAddedFactoryValueArgumentReloadTest#addedFactoryValuesFollowPropertyEdits` (watcher): PROBE2=1; PROBE4=3 with calls0 and unchanged build/destroy counters.

**Observable:** Invalid or unsafe values for an added factory hold configuration, existing product and lifecycle counters.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-TRANSACTION-PROXY

**Verdict:** PROVEN_WITH_SCOPE.

Property recreation preserves native transactions for constructor/factory products and heals held proxies.

**Shipped surfaces:** `docs/product-reference.md:61` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context. Security5.7.11/6.3.4; H2 2.5.250; native JDBC transactions, no reactive manager.

**Tests/assertions:** `com.onurkat.reclazz.e2e.TransactionProxyValueReloadTest#watchedSettingsRecreateBothTransactionProductsAndRetainRollback` (watcher): PROBE1=8000:8000:4:26000:4:2:healed; recovery10000 both. `com.onurkat.reclazz.e2e.SecurityProxyValueReloadTest#watchedSettingsRecreateBothTransactionProductsAndRetainRollback` (watcher): PROBE1=8000:8000:4:26000:4:2:healed; recovery10000 both. `com.onurkat.reclazz.e2e.ModernSecurityProxyValueReloadTest#watchedSettingsRecreateBothTransactionProductsAndRetainRollback` (watcher): PROBE1=8000:8000:4:26000:4:2:healed; recovery10000 both.

**Observable:** Property recreation preserves native transactions for constructor/factory products and heals held proxies.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-TRANSACTION-ROLLBACK

**Verdict:** PROVEN_WITH_SCOPE.

Recreated property consumers keep real JDBC rollback rather than retaining failed writes.

**Shipped surfaces:** `docs/product-reference.md:61` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context. Security5.7.11/6.3.4; H2 2.5.250; native JDBC transactions, no reactive manager.

**Tests/assertions:** `com.onurkat.reclazz.e2e.TransactionProxyValueReloadTest#watchedSettingsRecreateBothTransactionProductsAndRetainRollback` (watcher): Each probe performs successful and failing writes; SQL count4/6/8 and sum26000/42000/62000 exclude failed rows. `com.onurkat.reclazz.e2e.SecurityProxyValueReloadTest#watchedSettingsRecreateBothTransactionProductsAndRetainRollback` (watcher): Each probe performs successful and failing writes; SQL count4/6/8 and sum26000/42000/62000 exclude failed rows. `com.onurkat.reclazz.e2e.ModernSecurityProxyValueReloadTest#watchedSettingsRecreateBothTransactionProductsAndRetainRollback` (watcher): Each probe performs successful and failing writes; SQL count4/6/8 and sum26000/42000/62000 exclude failed rows.

**Observable:** Recreated property consumers keep real JDBC rollback rather than retaining failed writes.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-SECURITY-PROXY

**Verdict:** PROVEN_WITH_SCOPE.

Property recreation retains unauthenticated/unauthorized refusal and authorized calls on supported native security+transaction proxies.

**Shipped surfaces:** `docs/product-reference.md:61` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context. Security5.7.11/6.3.4; H2 2.5.250; native JDBC transactions, no reactive manager.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SecurityProxyValueReloadTest#watchedSettingsRecreateBothTransactionProductsAndRetainRollback` (watcher): Security fixture clears authentication, requires READ/WRITE and asserts credential/access denial before actual SQL; holders healed after replacement. `com.onurkat.reclazz.e2e.ModernSecurityProxyValueReloadTest#watchedSettingsRecreateBothTransactionProductsAndRetainRollback` (watcher): Security fixture clears authentication, requires READ/WRITE and asserts credential/access denial before actual SQL; holders healed after replacement.

**Observable:** Property recreation retains unauthenticated/unauthorized refusal and authorized calls on supported native security+transaction proxies.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-CANDIDATE-REJECT

**Verdict:** EXPECTED.

Invalid Boot2 configuration-properties conversion holds Environment, bound properties and direct fields together.

**Shipped surfaces:** `docs/usage.md:2143` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Boot2.7.18/Spring5.3.39 physical properties startup-agent fixture.

**Tests/assertions:** `com.onurkat.reclazz.e2e.PropertyChangeIsAllOrNothingTest#rejectedSaveKeepsAllValuesAndFixRetriesBothKeys` (watcher): All six values old10 after invalidtimeoutabc, then all new20 after fix.

**Observable:** Invalid Boot2 configuration-properties conversion holds Environment, bound properties and direct fields together.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-CANDIDATE-RECOVERY

**Verdict:** PROVEN_WITH_SCOPE.

Fixing a rejected Boot2 properties save applies both pending keys to existing consumers.

**Shipped surfaces:** `docs/usage.md:2143` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Boot2.7.18/Spring5.3.39 physical properties startup-agent fixture.

**Tests/assertions:** `com.onurkat.reclazz.e2e.PropertyChangeIsAllOrNothingTest#rejectedSaveKeepsAllValuesAndFixRetriesBothKeys` (watcher): All six values new20 after correcting pendingtimeout/address.

**Observable:** Fixing a rejected Boot2 properties save applies both pending keys to existing consumers.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-KEY-REMOVAL

**Verdict:** PROVEN_WITH_SCOPE.

Removing loaded properties keys reveals startup/default values on existing beans.

**Shipped surfaces:** `docs/usage.md:2199` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Boot2.7.18/Spring5.3.39/SnakeYAML1.30; real local HTTP, no servlet-container or Boot3 claim.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ConfigFileRemovalReloadTest#removedPropertiesRevealDefaultsAndRestoreAcrossSaves` (watcher): HTTP10:old:[a]:10:old:10:true ->5:fallback:[default]:5:fallback:5:true.

**Observable:** Removing loaded properties keys reveals startup/default values on existing beans.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-FILE-DELETE

**Verdict:** PROVEN_WITH_SCOPE.

Deleting and restoring a previously loaded properties file updates existing HTTP consumers.

**Shipped surfaces:** `docs/usage.md:2199` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Boot2.7.18/Spring5.3.39/SnakeYAML1.30; real local HTTP, no servlet-container or Boot3 claim.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ConfigFileRemovalReloadTest#removedPropertiesRevealDefaultsAndRestoreAcrossSaves` (watcher): Delete ->default5; restoration ->40:again:[q], last true proves same Settings singleton.

**Observable:** Deleting and restoring a previously loaded properties file updates existing HTTP consumers.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-YAML-LIST

**Verdict:** PROVEN_WITH_SCOPE.

Loaded nested YAML keys and indexed lists reach existing HTTP consumers.

**Shipped surfaces:** `docs/usage.md:2199` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Boot2.7.18/Spring5.3.39/SnakeYAML1.30; real local HTTP, no servlet-container or Boot3 claim.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ConfigFileRemovalReloadTest#yamlNestedValuesListsRemovalAndSyntaxRecoveryReachExistingBeans` (watcher): HTTP10/old/[a] ->defaults ->30/restored/[z] ->40/again/[q].

**Observable:** Loaded nested YAML keys and indexed lists reach existing HTTP consumers.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-YAML-DELETE

**Verdict:** PROVEN_WITH_SCOPE.

Deletion/restoration of an identified YAML file reveals defaults then restores its values.

**Shipped surfaces:** `docs/usage.md:2199` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Boot2.7.18/Spring5.3.39/SnakeYAML1.30; real local HTTP, no servlet-container or Boot3 claim.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ConfigFileRemovalReloadTest#yamlNestedValuesListsRemovalAndSyntaxRecoveryReachExistingBeans` (watcher): Delete ->5/fallback/[default]; recreate ->40/again/[q]; Settings identitytrue.

**Observable:** Deletion/restoration of an identified YAML file reveals defaults then restores its values.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-YAML-REJECT

**Verdict:** EXPECTED.

Malformed YAML and invalid bound values retain last accepted configuration and correction recovers.

**Shipped surfaces:** `docs/usage.md:2199` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Boot2.7.18/Spring5.3.39/SnakeYAML1.30; real local HTTP, no servlet-container or Boot3 claim.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ConfigFileRemovalReloadTest#yamlNestedValuesListsRemovalAndSyntaxRecoveryReachExistingBeans` (watcher): Invalid timeout and svc:[broken leave default HTTP body; fixed values then30/restored/[z].

**Observable:** Malformed YAML and invalid bound values retain last accepted configuration and correction recovers.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-PRECEDENCE

**Verdict:** PARTIAL.

Owned file replacement preserves higher/lower property-source precedence and does not stack empty sources.

**Shipped surfaces:** `docs/usage.md:2199` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringPropertyFilesTest#fileReplacementPreservesPrecedenceAndEmptyFileOwnership` (component): CommandLine99 masks20/empty; after remove ->30; empty ->lower7; exactly one fixture source.

**Observable:** Owned file replacement preserves higher/lower property-source precedence and does not stack empty sources.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-PROFILES

**Verdict:** EXPECTED.

Active YAML documents keep their source order; activation topology edits are held.

**Shipped surfaces:** `docs/usage.md:2199` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringPropertyFilesTest#activeProfileDocumentsKeepTheirOrderAndTopologyChangesAreHeld` (component): dev21 accepted despite inactive invalid; changedselector UNCHECKABLE retains21; fix23.

**Observable:** Active YAML documents keep their source order; activation topology edits are held.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-MULTI-CONTEXT

**Verdict:** PARTIAL.

A conversion rejection in one owning context holds all contexts; valid retry reaches each active profile.

**Shipped surfaces:** `docs/usage.md:2199` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringPropertyFilesTest#rejectionInOneContextHoldsAllContextsAndFixRetries` (component): Profiles other/dev retain10/20 onbad; correction12/22.

**Observable:** A conversion rejection in one owning context holds all contexts; valid retry reaches each active profile.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-CAPTURED-CANDIDATE

**Verdict:** PARTIAL.

A boundary applies captured file bytes and a nonrunning boundary does not accept them.

**Shipped surfaces:** `docs/usage.md:2199` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringPropertyFilesTest#boundaryUsesOneReadAndANonRunningBoundaryDoesNotAccept` (component): NOT_RUN keeps10; laterwrite30 while captured20 queued applies20 then30 onnext.

**Observable:** A boundary applies captured file bytes and a nonrunning boundary does not accept them.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-PARTIAL-RETRY

**Verdict:** PARTIAL.

A live setter failure is PARTIAL and an identical save remains pending for retry.

**Shipped surfaces:** `docs/usage.md:2199` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringPropertyFilesTest#partialSetterFailureRemainsPendingOnAnIdenticalSave` (component): Settings10 but Environment20 afterthrow; identicalretry20, samebean.

**Observable:** A live setter failure is PARTIAL and an identical save remains pending for retry.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-UNOWNED

**Verdict:** EXPECTED.

An inactive/unowned file is refused before becoming an override.

**Shipped surfaces:** `docs/usage.md:2199` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringPropertyFilesTest#inactiveOrUnownedFilesNeverBecomeAnOverride` (component): UNCHECKABLE retains10 for application-prod.properties.

**Observable:** An inactive/unowned file is refused before becoming an override.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-OWNERSHIP-REPLACED

**Verdict:** EXPECTED.

External property-source replacement is preserved rather than overwritten by file reload.

**Shipped surfaces:** `docs/usage.md:2199` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringPropertyFilesTest#externalSourceReplacementIsNotOverwritten` (component): External88 retained, reload UNCHECKABLE.

**Observable:** External property-source replacement is preserved rather than overwritten by file reload.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-YAML-IMPORT-REFUSAL

**Verdict:** EXPECTED.

Malformed YAML or new config imports do not change live file sources.

**Shipped surfaces:** `docs/usage.md:2199` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringPropertyFilesTest#malformedYamlAndImportsDoNotChangeLiveSources` (component): Each UNCHeCKABLE retains10; fixed30 applies.

**Observable:** Malformed YAML or new config imports do not change live file sources.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-READONLY-REMOVAL

**Verdict:** EXPECTED.

Read-only collection removal is held rather than reporting stale values as applied.

**Shipped surfaces:** `docs/usage.md:2199` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringPropertyFilesTest#readOnlyCollectionRemovalIsHeldInsteadOfReportingStaleStateAsApplied` (component): UNCHECKABLE, items[a], Environment itema.

**Observable:** Read-only collection removal is held rather than reporting stale values as applied.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-MIXED-ORIGINS

**Verdict:** EXPECTED.

A source containing mixed resource origins cannot own a reloaded file.

**Shipped surfaces:** `docs/usage.md:2199` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringPropertyFilesTest#aSourceWithMixedResourceOriginsCannotOwnAFile` (component): UNCHECKABLE,10 and separate other=value retained.

**Observable:** A source containing mixed resource origins cannot own a reloaded file.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-VALIDATION

**Verdict:** EXPECTED.

Boot validation fallback rejects constraint violations without a validator bean.

**Shipped surfaces:** `docs/usage.md:2143` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.PropertyChangeCheckTest#fallbackValidationRejectsWithoutAValidatorBean` (component): No Validator beans; timeout0 leaves Environment10, bean10, Value10.

**Observable:** Boot validation fallback rejects constraint violations without a validator bean.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-RECORD

**Verdict:** PARTIAL.

A self-validating constructor-bound Boot2 record rejects invalid candidates and binds a valid replacement.

**Shipped surfaces:** `docs/usage.md:2143` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.PropertyChangeCheckTest#selfValidatingRecordUsesTheNewInstance` (component): Record0/200 REJECTED stays10;50 APPLIED andrecord50; holder/watcher not observed.

**Observable:** A self-validating constructor-bound Boot2 record rejects invalid candidates and binds a valid replacement.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-TRACKING

**Verdict:** EXPECTED.

Rejected precheck does not write Boot bound-property tracking or live values.

**Shipped surfaces:** `docs/usage.md:2143` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.PropertyChangeCheckTest#rejectedCheckDoesNotWriteBootTrackingOrAnyLiveValue` (component): Tracking map equalsbefore; invalidrecord0 holds bothbeans/field10/addressold.

**Observable:** Rejected precheck does not write Boot bound-property tracking or live values.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-NATIVE-HOOKS

**Verdict:** PARTIAL.

Named configuration validator and bind-handler advisor participate in candidate validation.

**Shipped surfaces:** `docs/usage.md:2143` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.PropertyChangeCheckTest#namedValidatorAndAdvisorAreHonoured` (component): Customlimit50 REJECTED, advisorcallcount grows, bean10.

**Observable:** Named configuration validator and bind-handler advisor participate in candidate validation.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-PLACEHOLDERS

**Verdict:** PARTIAL.

Candidate placeholder keys are resolved against the candidate even with a live placeholder configurer.

**Shipped surfaces:** `docs/usage.md:2143` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.PropertyChangeCheckTest#placeholdersUseCandidateKeysEvenWithALiveConfigurer` (component): bad svc.limit rejects; candidate/api applied to Bootbean and Valuefield.

**Observable:** Candidate placeholder keys are resolved against the candidate even with a live placeholder configurer.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-IGNORE-FLAGS

**Verdict:** PARTIAL.

Boot strict/lenient binding ignore flags retain native semantics.

**Shipped surfaces:** `docs/usage.md:2143` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.PropertyChangeCheckTest#bootIgnoreFlagsArePreserved` (component): Stricttypo REJECTED; lenientabc APPLIED withbean10.

**Observable:** Boot strict/lenient binding ignore flags retain native semantics.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-SCRATCH-REFUSAL

**Verdict:** EXPECTED.

Unsupported scratch constructor or inaccessible native binder prevents candidate application.

**Shipped surfaces:** `docs/usage.md:2143` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.PropertyChangeCheckTest#unsupportedScratchConstructorHoldsTheChange` (component): UNCHECKABLE, Env10, unsupportedbean10.

**Observable:** Unsupported scratch constructor or inaccessible native binder prevents candidate application.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-BINDER-REFUSAL

**Verdict:** EXPECTED.

Missing internal Boot binder holds the candidate rather than guessing its behavior.

**Shipped surfaces:** `docs/usage.md:2143` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.PropertyChangeCheckTest#missingInternalBinderHoldsTheChange` (component): Classloader hides ConfigurationPropertiesBinder, UNCHECKABLE andEnv10.

**Observable:** Missing internal Boot binder holds the candidate rather than guessing its behavior.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT-APPLY-PARTIAL

**Verdict:** PARTIAL.

Failure only on live application is PARTIAL and is not accepted as an applied candidate.

**Shipped surfaces:** `docs/usage.md:2143` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.PropertyChangeCheckTest#postCheckFailureIsPartialAndNeverAccepted` (component): Partial, acceptedfalse, namedfinding, Environment20; no all-or-nothing live rollback.

**Observable:** Failure only on live application is PARTIAL and is not accepted as an applied candidate.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-VALUE-CONSTRUCTOR-PARTIAL

**Verdict:** PARTIAL.

A live constructor failure is reported PARTIAL and does not advance accepted state.

**Shipped surfaces:** `docs/product-reference.md:61` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.ComputedValueConstructorsTest#liveConstructorFailureIsPartialAndNotAccepted` (component): Constructorcalledtwice, Environment8, acceptedfalse, PARTIAL.

**Observable:** A live constructor failure is reported PARTIAL and does not advance accepted state.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-VALUE-CONSTRUCTOR-NOCHECK-SIDE-EFFECT

**Verdict:** PARTIAL.

Prechecking and unrelated property saves do not construct or replace the computed constructor consumer.

**Shipped surfaces:** `docs/product-reference.md:61` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.ComputedValueConstructorsTest#checkDoesNotConstructAndUnrelatedSaveDoesNotReplace` (component): CheckAPPLIED withmade1; unrelatedsave rebuiltempty, sameoriginal.

**Observable:** Prechecking and unrelated property saves do not construct or replace the computed constructor consumer.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-VALUE-FACTORY-PARTIAL

**Verdict:** PARTIAL.

A live native factory failure can retire the prior product and is explicitly PARTIAL, not a claim that the old bean survived.

**Shipped surfaces:** `docs/product-reference.md:61` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.FactoryValueArgumentsTest#liveFailureIsPartialDoesNotAcceptAndDoesNotClaimOldBeanSurvived` (component): PARTIAL, acceptedfalse, destroyed1, no singletonproduct, Environment8.

**Observable:** A live native factory failure can retire the prior product and is explicitly PARTIAL, not a claim that the old bean survived.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-VALUE-FACTORY-DEFERRED

**Verdict:** PARTIAL.

Prechecking and application do not instantiate cold lazy/prototype native factories.

**Shipped surfaces:** `docs/product-reference.md:61` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.FactoryValueArgumentsTest#lazyAndPrototypeAreNotInstantiatedByCheckOrApplication` (component): APPLIED, built0, rebuiltempty; later creation reads8.

**Observable:** Prechecking and application do not instantiate cold lazy/prototype native factories.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-VALUE-FACTORY-COLLECTION-REFUSAL

**Verdict:** EXPECTED.

Nonscalar native factory argument is held before recreation.

**Shipped surfaces:** `docs/product-reference.md:61` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.FactoryValueArgumentsTest#genericCollectionArgumentIsHeldBeforeNativeRecreation` (component): UNCHECKABLE, primitive/scalar reason, sameproduct.

**Observable:** Nonscalar native factory argument is held before recreation.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-VALUE-FACTORY-OWNER-REFUSAL

**Verdict:** EXPECTED.

A FactoryBean owner is not confused with its cached product during @Value recreation.

**Shipped surfaces:** `docs/product-reference.md:61` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.FactoryValueArgumentsTest#factoryBeanOwnershipIsNotConfusedWithItsProduct` (component): UNCHECKABLE/FactoryBean reason, samefactory andproduct.

**Observable:** A FactoryBean owner is not confused with its cached product during @Value recreation.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-VALUE-FACTORY-RESOLVER-REFUSAL

**Verdict:** EXPECTED.

A custom expression resolver is refused before candidate precheck can invoke it.

**Shipped surfaces:** `docs/product-reference.md:61` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.FactoryValueArgumentsTest#customResolverCannotExecuteDuringDirectPlaceholderPrecheck` (component): UNCHECKABLE, resolvercalls0, sameoriginal.

**Observable:** A custom expression resolver is refused before candidate precheck can invoke it.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-INDIRECT-CONTEXT-ISOLATION

**Verdict:** PARTIAL.

Indirect dependencies stay context-specific, while a failing relevant context holds a shared candidate.

**Shipped surfaces:** `docs/product-reference.md:60` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.IndirectValueDependenciesTest#contextsKeepSeparateDependenciesAndAllHoldOnOneFailure` (component): Source8 updatesoneconsumer only; otherliteral7 sameidentity; badsource holds labelold inboth.

**Observable:** Indirect dependencies stay context-specific, while a failing relevant context holds a shared candidate.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-IMPORT-OWNER

**Verdict:** PROVEN_WITH_SCOPE.

Loaded local XML imports outside configured roots update their owner without altering an unrelated context.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringXmlResourceReloadTest#loadedImportsReloadOutsideWatchedRootsWithoutChangingAnotherContext` (watcher): PROBE1 after:2:root-before:other; rootedit ->root-after; isolatedotherunchanged. `com.onurkat.reclazz.e2e.ModernSpringXmlResourceReloadTest#loadedImportsReloadOutsideWatchedRootsWithoutChangingAnotherContext` (watcher): PROBE1 after:2:root-before:other; rootedit ->root-after; isolatedotherunchanged.

**Observable:** Loaded local XML imports outside configured roots update their owner without altering an unrelated context.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-IMPORT-NOOP

**Verdict:** PROVEN_WITH_SCOPE.

An identical imported XML save does not call its setter again.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringXmlResourceReloadTest#loadedImportsReloadOutsideWatchedRootsWithoutChangingAnotherContext` (watcher): PROBE2 still after:2, not extra setter invocation. `com.onurkat.reclazz.e2e.ModernSpringXmlResourceReloadTest#loadedImportsReloadOutsideWatchedRootsWithoutChangingAnotherContext` (watcher): PROBE2 still after:2, not extra setter invocation.

**Observable:** An identical imported XML save does not call its setter again.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-PROTOTYPE-REFUSAL

**Verdict:** EXPECTED.

A scope change to prototype is refused while preserving prior live values.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringXmlResourceReloadTest#loadedImportsReloadOutsideWatchedRootsWithoutChangingAnotherContext` (watcher): scope=prototype warning; PROBE4 still after:2/root-after/other. `com.onurkat.reclazz.e2e.ModernSpringXmlResourceReloadTest#loadedImportsReloadOutsideWatchedRootsWithoutChangingAnotherContext` (watcher): scope=prototype warning; PROBE4 still after:2/root-after/other.

**Observable:** A scope change to prototype is refused while preserving prior live values.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-NATIVE-WATCHER

**Verdict:** PROVEN_WITH_SCOPE.

Repeated XML saves use native scalar/generic conversion and an overloaded String setter.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringXmlNativePropertyBindingReloadTest#nativeTypesSurviveRepeatedWatchedEditsAndInvalidValuesKeepMetadata` (watcher): Class,enum,char,BigDecimal,URI,typed lists/maps andwriterSTRING; repeatednext/before exactprobes. `com.onurkat.reclazz.e2e.ModernSpringXmlNativePropertyBindingReloadTest#nativeTypesSurviveRepeatedWatchedEditsAndInvalidValuesKeepMetadata` (watcher): Class,enum,char,BigDecimal,URI,typed lists/maps andwriterSTRING; repeatednext/before exactprobes.

**Observable:** Repeated XML saves use native scalar/generic conversion and an overloaded String setter.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-NATIVE-INVALID

**Verdict:** EXPECTED.

Invalid XML conversion retains the failed property value/raw metadata while a supported sibling still changes.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringXmlNativePropertyBindingReloadTest#nativeTypesSurviveRepeatedWatchedEditsAndInvalidValuesKeepMetadata` (watcher): Fourth INVALIDmode probe remainsbefore:A:1.0:urn:old:OLD:STRING andmetadataOLD; other4 applies. `com.onurkat.reclazz.e2e.ModernSpringXmlNativePropertyBindingReloadTest#nativeTypesSurviveRepeatedWatchedEditsAndInvalidValuesKeepMetadata` (watcher): Fourth INVALIDmode probe remainsbefore:A:1.0:urn:old:OLD:STRING andmetadataOLD; other4 applies.

**Observable:** Invalid XML conversion retains the failed property value/raw metadata while a supported sibling still changes.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-PROPERTY-INTEGRITY

**Verdict:** PROVEN_WITH_SCOPE.

Missing references/invalid primitives keep failed raw definitions and valid correction can be natively recreated.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringXmlPropertyFailureReloadTest#failedEditsPreserveRawValuesAndAllowCorrection` (watcher): PROBE1..3 old1/a;4 new2/b;5 keptvaliddefinition; RECREATED=true:new:2. `com.onurkat.reclazz.e2e.ModernSpringXmlPropertyFailureReloadTest#failedEditsPreserveRawValuesAndAllowCorrection` (watcher): PROBE1..3 old1/a;4 new2/b;5 keptvaliddefinition; RECREATED=true:new:2.

**Observable:** Missing references/invalid primitives keep failed raw definitions and valid correction can be natively recreated.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-METADATA-REFUSAL

**Verdict:** EXPECTED.

Existing depends-on/primary/autowire/candidate edits are named and refused before owner property mutation.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringXmlDefinitionMetadataReloadTest#metadataEditsWarnBeforePropertiesAndRepeatedSavesRemainSafe` (watcher): Three warnings, raw/mergedidentitytrue, beforelabel while siblingsother2..4; metadatarevert permitscorrected. `com.onurkat.reclazz.e2e.ModernSpringXmlDefinitionMetadataReloadTest#metadataEditsWarnBeforePropertiesAndRepeatedSavesRemainSafe` (watcher): Three warnings, raw/mergedidentitytrue, beforelabel while siblingsother2..4; metadatarevert permitscorrected.

**Observable:** Existing depends-on/primary/autowire/candidate edits are named and refused before owner property mutation.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-LAZY-FLAG-REFUSAL

**Verdict:** EXPECTED.

Changed effective lazy-init metadata retains old creation state and values.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringXmlLazyInitReloadTest#watchedLazyMetadataPreservesCreationStateAndDefaultsRemainEquivalent` (watcher): Warm/coldflags edits produceexactwarnings2; coldcount1 until nativecreation ->2. `com.onurkat.reclazz.e2e.ModernSpringXmlLazyInitReloadTest#watchedLazyMetadataPreservesCreationStateAndDefaultsRemainEquivalent` (watcher): Warm/coldflags edits produceexactwarnings2; coldcount1 until nativecreation ->2.

**Observable:** Changed effective lazy-init metadata retains old creation state and values.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-LAZY-EQUIVALENCE

**Verdict:** PROVEN_WITH_SCOPE.

Equivalent explicit/default lazy flags permit subsequent supported properties without eager creation.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringXmlLazyInitReloadTest#watchedLazyMetadataPreservesCreationStateAndDefaultsRemainEquivalent` (watcher): Six saves exactidentityflags/count1 and correctedlabels; CREATED=true:corrected:2 onlyatend. `com.onurkat.reclazz.e2e.ModernSpringXmlLazyInitReloadTest#watchedLazyMetadataPreservesCreationStateAndDefaultsRemainEquivalent` (watcher): Six saves exactidentityflags/count1 and correctedlabels; CREATED=true:corrected:2 onlyatend.

**Observable:** Equivalent explicit/default lazy flags permit subsequent supported properties without eager creation.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-INNER-VALUES

**Verdict:** PROVEN_WITH_SCOPE.

Changed plain inner bean values use native value resolution and preserve the outer singleton.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringXmlInnerBeanReloadTest#innerNoOpsEditsAndResourceRefusalsFollowWatcher` (watcher): PROBE2 true:true:new:after:after:1:1:0; changedplain stage new while resourcekept. `com.onurkat.reclazz.e2e.ModernSpringXmlInnerBeanReloadTest#innerNoOpsEditsAndResourceRefusalsFollowWatcher` (watcher): PROBE2 true:true:new:after:after:1:1:0; changedplain stage new while resourcekept.

**Observable:** Changed plain inner bean values use native value resolution and preserve the outer singleton.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-INNER-NOOP

**Verdict:** PROVEN_WITH_SCOPE.

Unchanged inner declarations do not reconstruct their cached live object.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringXmlInnerBeanReloadTest#innerNoOpsEditsAndResourceRefusalsFollowWatcher` (watcher): PROBE1 and3 same resource construction/init counters1/1, closeonlyoriginal. `com.onurkat.reclazz.e2e.ModernSpringXmlInnerBeanReloadTest#innerNoOpsEditsAndResourceRefusalsFollowWatcher` (watcher): PROBE1 and3 same resource construction/init counters1/1, closeonlyoriginal.

**Observable:** Unchanged inner declarations do not reconstruct their cached live object.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-INNER-RESOURCE-REFUSAL

**Verdict:** EXPECTED.

Inner resource/lifecycle edits are refused before updating that owner; independent beans still apply.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringXmlInnerBeanReloadTest#innerNoOpsEditsAndResourceRefusalsFollowWatcher` (watcher): PROBE4/5 resourceafter kept, siblingother4/5; warnings2, CLOSED1:1:1. `com.onurkat.reclazz.e2e.ModernSpringXmlInnerBeanReloadTest#innerNoOpsEditsAndResourceRefusalsFollowWatcher` (watcher): PROBE4/5 resourceafter kept, siblingother4/5; warnings2, CLOSED1:1:1.

**Observable:** Inner resource/lifecycle edits are refused before updating that owner; independent beans still apply.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-UNSUPPORTED-NOOP

**Verdict:** PROVEN_WITH_SCOPE.

Proven unchanged abstract/prototype/resource XML definitions stay quiet during no-op/sibling saves.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringXmlUnsupportedNoOpReloadTest#unchangedShapesStayQuietAndRealEditsStillWarn` (watcher): First3 saves no warnings, identitytrue, labelsbefore, made1/init0/count3, siblingschange. `com.onurkat.reclazz.e2e.ModernSpringXmlUnsupportedNoOpReloadTest#unchangedShapesStayQuietAndRealEditsStillWarn` (watcher): First3 saves no warnings, identitytrue, labelsbefore, made1/init0/count3, siblingschange.

**Observable:** Proven unchanged abstract/prototype/resource XML definitions stay quiet during no-op/sibling saves.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-UNSUPPORTED-EDIT-REFUSAL

**Verdict:** EXPECTED.

Real edits to unsupported XML shapes retain original values and name restart.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringXmlUnsupportedNoOpReloadTest#unchangedShapesStayQuietAndRealEditsStillWarn` (watcher): Saves4/5 restartheaders1/2 and3require; labelsbefore, closeoriginalonce. `com.onurkat.reclazz.e2e.ModernSpringXmlUnsupportedNoOpReloadTest#unchangedShapesStayQuietAndRealEditsStillWarn` (watcher): Saves4/5 restartheaders1/2 and3require; labelsbefore, closeoriginalonce.

**Observable:** Real edits to unsupported XML shapes retain original values and name restart.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-ALIAS-REFUSAL

**Verdict:** EXPECTED.

Alias retarget/removal/addition is refused, keeping already-resolved alias lookups.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringXmlAliasReloadTest#aliasOnlyEditsReportRestartAndPreserveEveryLookup` (watcher): chosen staysa; newaliasabsent; newbean c present; original listener count1 unchanged. `com.onurkat.reclazz.e2e.ModernSpringXmlAliasReloadTest#aliasOnlyEditsReportRestartAndPreserveEveryLookup` (watcher): chosen staysa; newaliasabsent; newbean c present; original listener count1 unchanged.

**Observable:** Alias retarget/removal/addition is refused, keeping already-resolved alias lookups.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-INLINE-ALIAS-REFUSAL

**Verdict:** EXPECTED.

Inline alias removal is captured without replacing the application reader listener.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringXmlAliasReloadTest#inlineAliasEventsAreCapturedWithoutReplacingApplicationListener` (watcher): Removedchosen warning, actual lookup stilla andlistenercount0. `com.onurkat.reclazz.e2e.ModernSpringXmlAliasReloadTest#inlineAliasEventsAreCapturedWithoutReplacingApplicationListener` (watcher): Removedchosen warning, actual lookup stilla andlistenercount0.

**Observable:** Inline alias removal is captured without replacing the application reader listener.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-LOOKUP-REFUSAL

**Verdict:** EXPECTED.

Lookup-method edits preserve the native factory and require restart; restoring the original lookup allows simple properties.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringXmlLookupMethodReloadTest#lookupChangesPreserveFactoryAndReportRestart` (watcher): Three warnings, identitiestrue, beforelabel despite targetb/removal; revertedmetadata final/final. `com.onurkat.reclazz.e2e.ModernSpringXmlLookupMethodReloadTest#lookupChangesPreserveFactoryAndReportRestart` (watcher): Three warnings, identitiestrue, beforelabel despite targetb/removal; revertedmetadata final/final.

**Observable:** Lookup-method edits preserve the native factory and require restart; restoring the original lookup allows simple properties.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-LOADER-LIFECYCLE-REFUSAL

**Verdict:** EXPECTED.

Lifecycle/infrastructure definitions are retained in ordinary/child loaders without side-effect creation.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringXmlLoaderLifecycleReloadTest#lifecycleEditsRefuseInNormalAndChildLoadersWhileSiblingsReload` (watcher): Eachsave5refusals; original raw/mergedidentitytrue, counter0; siblingsother2/3; COLD=before:before. `com.onurkat.reclazz.e2e.ModernSpringXmlLoaderLifecycleReloadTest#lifecycleEditsRefuseInNormalAndChildLoadersWhileSiblingsReload` (watcher): Eachsave5refusals; original raw/mergedidentitytrue, counter0; siblingsother2/3; COLD=before:before.

**Observable:** Lifecycle/infrastructure definitions are retained in ordinary/child loaders without side-effect creation.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-FACTORYBEAN-REFUSAL

**Verdict:** EXPECTED.

Existing XML FactoryBean definitions are not mutated to pretend their products refreshed.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.SpringXmlFactoryBeanProductReloadTest#factoryEditsWarnBeforeStateChangesAndNoOpStaysQuiet` (watcher): Warm/cold/nonsingleton products before and samecachedidentities;3refusals peredit; coldnativecreationbefore. `com.onurkat.reclazz.e2e.ModernSpringXmlFactoryBeanProductReloadTest#factoryEditsWarnBeforeStateChangesAndNoOpStaysQuiet` (watcher): Warm/cold/nonsingleton products before and samecachedidentities;3refusals peredit; coldnativecreationbefore.

**Observable:** Existing XML FactoryBean definitions are not mutated to pretend their products refreshed.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-SINGLETON-RECREATE

**Verdict:** PROVEN_WITH_SCOPE.

Constructor and explicit static/instance factory XML edits recreate supported products and heal held consumers.

**Shipped surfaces:** `docs/usage.md:375` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.XmlSingletonLifecycleReloadTest#xmlRecreatesConstructorAndFactoryProductsAndRepairsHeldConsumer` (watcher): XML1=2:2:2:6:3 thenXML2=3:13:13:36:6; constructor/static/instance values and lifecycle counts. `com.onurkat.reclazz.e2e.ModernXmlSingletonLifecycleReloadTest#xmlRecreatesConstructorAndFactoryProductsAndRepairsHeldConsumer` (watcher): XML1=2:2:2:6:3 thenXML2=3:13:13:36:6; constructor/static/instance values and lifecycle counts.

**Observable:** Constructor and explicit static/instance factory XML edits recreate supported products and heal held consumers.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-SINGLETON-NOOP

**Verdict:** PROVEN_WITH_SCOPE.

An identical rich XML save does not recreate products or repeat lifecycle effects.

**Shipped surfaces:** `docs/usage.md:375` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context.

**Tests/assertions:** `com.onurkat.reclazz.e2e.XmlSingletonLifecycleReloadTest#xmlRecreatesConstructorAndFactoryProductsAndRepairsHeldConsumer` (watcher): XML3 equals2 includinginit36/destroy6; CLOSED36 nativecurrentcallbacks. `com.onurkat.reclazz.e2e.ModernXmlSingletonLifecycleReloadTest#xmlRecreatesConstructorAndFactoryProductsAndRepairsHeldConsumer` (watcher): XML3 equals2 includinginit36/destroy6; CLOSED36 nativecurrentcallbacks.

**Observable:** An identical rich XML save does not recreate products or repeat lifecycle effects.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-NATIVE-TYPE

**Verdict:** PARTIAL.

XML type conversion matches a freshly initialized native Spring bean.

**Shipped surfaces:** `docs/usage.md:192` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlNativePropertyBindingTest#matchesNativeFreshForScalarGenericAndOverloadedProperties` (component): Parameterized type snapshot equals native fresh; live singleton/raw definition same; siblingafter.

**Observable:** XML type conversion matches a freshly initialized native Spring bean.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-NATIVE-MODE

**Verdict:** PARTIAL.

XML mode conversion matches a freshly initialized native Spring bean.

**Shipped surfaces:** `docs/usage.md:192` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlNativePropertyBindingTest#matchesNativeFreshForScalarGenericAndOverloadedProperties` (component): Parameterized mode snapshot equals native fresh; live singleton/raw definition same; siblingafter.

**Observable:** XML mode conversion matches a freshly initialized native Spring bean.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-NATIVE-CODE

**Verdict:** PARTIAL.

XML code conversion matches a freshly initialized native Spring bean.

**Shipped surfaces:** `docs/usage.md:192` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlNativePropertyBindingTest#matchesNativeFreshForScalarGenericAndOverloadedProperties` (component): Parameterized code snapshot equals native fresh; live singleton/raw definition same; siblingafter.

**Observable:** XML code conversion matches a freshly initialized native Spring bean.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-NATIVE-AMOUNT

**Verdict:** PARTIAL.

XML amount conversion matches a freshly initialized native Spring bean.

**Shipped surfaces:** `docs/usage.md:192` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlNativePropertyBindingTest#matchesNativeFreshForScalarGenericAndOverloadedProperties` (component): Parameterized amount snapshot equals native fresh; live singleton/raw definition same; siblingafter.

**Observable:** XML amount conversion matches a freshly initialized native Spring bean.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-NATIVE-LOCATION

**Verdict:** PARTIAL.

XML location conversion matches a freshly initialized native Spring bean.

**Shipped surfaces:** `docs/usage.md:192` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlNativePropertyBindingTest#matchesNativeFreshForScalarGenericAndOverloadedProperties` (component): Parameterized location snapshot equals native fresh; live singleton/raw definition same; siblingafter.

**Observable:** XML location conversion matches a freshly initialized native Spring bean.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-NATIVE-TYPES

**Verdict:** PARTIAL.

XML types conversion matches a freshly initialized native Spring bean.

**Shipped surfaces:** `docs/usage.md:192` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlNativePropertyBindingTest#matchesNativeFreshForScalarGenericAndOverloadedProperties` (component): Parameterized types snapshot equals native fresh; live singleton/raw definition same; siblingafter.

**Observable:** XML types conversion matches a freshly initialized native Spring bean.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-NATIVE-MODES

**Verdict:** PARTIAL.

XML modes conversion matches a freshly initialized native Spring bean.

**Shipped surfaces:** `docs/usage.md:192` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlNativePropertyBindingTest#matchesNativeFreshForScalarGenericAndOverloadedProperties` (component): Parameterized modes snapshot equals native fresh; live singleton/raw definition same; siblingafter.

**Observable:** XML modes conversion matches a freshly initialized native Spring bean.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-NATIVE-MAPPINGS

**Verdict:** PARTIAL.

XML mappings conversion matches a freshly initialized native Spring bean.

**Shipped surfaces:** `docs/usage.md:192` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlNativePropertyBindingTest#matchesNativeFreshForScalarGenericAndOverloadedProperties` (component): Parameterized mappings snapshot equals native fresh; live singleton/raw definition same; siblingafter.

**Observable:** XML mappings conversion matches a freshly initialized native Spring bean.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-NATIVE-SELECTION

**Verdict:** PARTIAL.

XML selection conversion matches a freshly initialized native Spring bean.

**Shipped surfaces:** `docs/usage.md:192` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlNativePropertyBindingTest#matchesNativeFreshForScalarGenericAndOverloadedProperties` (component): Parameterized selection snapshot equals native fresh; live singleton/raw definition same; siblingafter.

**Observable:** XML selection conversion matches a freshly initialized native Spring bean.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-NATIVE-INTEGERSELECTION

**Verdict:** PARTIAL.

XML integerSelection conversion matches a freshly initialized native Spring bean.

**Shipped surfaces:** `docs/usage.md:192` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlNativePropertyBindingTest#matchesNativeFreshForScalarGenericAndOverloadedProperties` (component): Parameterized integerSelection snapshot equals native fresh; live singleton/raw definition same; siblingafter.

**Observable:** XML integerSelection conversion matches a freshly initialized native Spring bean.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-NATIVE-BIGINTEGER

**Verdict:** PARTIAL.

XML bigInteger conversion matches a freshly initialized native Spring bean.

**Shipped surfaces:** `docs/usage.md:192` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlNativePropertyBindingTest#matchesNativeFreshForScalarGenericAndOverloadedProperties` (component): Parameterized bigInteger snapshot equals native fresh; live singleton/raw definition same; siblingafter.

**Observable:** XML bigInteger conversion matches a freshly initialized native Spring bean.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-NATIVE-MODESET

**Verdict:** PARTIAL.

XML modeSet conversion matches a freshly initialized native Spring bean.

**Shipped surfaces:** `docs/usage.md:192` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlNativePropertyBindingTest#matchesNativeFreshForScalarGenericAndOverloadedProperties` (component): Parameterized modeSet snapshot equals native fresh; live singleton/raw definition same; siblingafter.

**Observable:** XML modeSet conversion matches a freshly initialized native Spring bean.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-NATIVE-ENUMKEYS

**Verdict:** PARTIAL.

XML enumKeys conversion matches a freshly initialized native Spring bean.

**Shipped surfaces:** `docs/usage.md:192` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlNativePropertyBindingTest#matchesNativeFreshForScalarGenericAndOverloadedProperties` (component): Parameterized enumKeys snapshot equals native fresh; live singleton/raw definition same; siblingafter.

**Observable:** XML enumKeys conversion matches a freshly initialized native Spring bean.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-INHERITED-GENERIC

**Verdict:** PARTIAL.

Inherited generic collection type resolves against its actual containing class.

**Shipped surfaces:** `docs/usage.md:192` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlNativePropertyBindingTest#inheritedGenericParameterUsesActualContainingClass` (component): Derived Mode.NEXT actual element.

**Observable:** Inherited generic collection type resolves against its actual containing class.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-INHERITED-SCALAR

**Verdict:** PARTIAL.

Inherited scalar type variable uses its resolved target rather than Object erasure.

**Shipped surfaces:** `docs/usage.md:192` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlNativePropertyBindingTest#inheritedScalarTypeVariableUsesResolvedTargetRatherThanObjectErasure` (component): Actual item identity Mode.NEXT matches nativefresh.

**Observable:** Inherited scalar type variable uses its resolved target rather than Object erasure.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-CUSTOM-CONVERTERS

**Verdict:** PARTIAL.

Admitted registered editor/conversion service participates in native XML binding.

**Shipped surfaces:** `docs/usage.md:192` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlNativePropertyBindingTest#admittedCustomEditorAndConversionServiceMatchNativeFactoryBehavior` (component): RegisteredcharBEGIN ->B; customURI ->urn:custom:NEXT.

**Observable:** Admitted registered editor/conversion service participates in native XML binding.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-UNKNOWN-CONVERTER-REFUSAL

**Verdict:** EXPECTED.

Unknown type converter is refused before the new route invokes it.

**Shipped surfaces:** `docs/usage.md:192` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlNativePropertyBindingTest#unknownTypeConverterIsNotInvokedByTheNewBindingRoute` (component): Number1 remains, unknownconvertercalls0, restartwarning.

**Observable:** Unknown type converter is refused before the new route invokes it.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-RESOURCE-CONVERSION-REFUSAL

**Verdict:** EXPECTED.

Resource target/explicit resource value is refused before editor value resolution.

**Shipped surfaces:** `docs/usage.md:192` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlNativePropertyBindingTest#resourceTargetAndExplicitResourceValueRefuseBeforeValueResolution` (component): Stream/value null, StreamEditorcalls0.

**Observable:** Resource target/explicit resource value is refused before editor value resolution.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-MISSING-SETTER-REFUSAL

**Verdict:** EXPECTED.

Absent or ambiguous physical setters do not acquire fake committed XML values.

**Shipped surfaces:** `docs/usage.md:192` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlNativePropertyBindingTest#absentAndAmbiguousPhysicalSettersRequireRestart` (component): Virtual/hiddenrawvaluesnull, virtualfieldnull; warning.

**Observable:** Absent or ambiguous physical setters do not acquire fake committed XML values.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-RECREATE-INIT-FAILURE

**Verdict:** PARTIAL.

A failing new init callback restores the prior XML definition and permits a later correction.

**Shipped surfaces:** `docs/usage.md:375` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlBeanRecreationTest#initFailureReportsSideEffectsAndRestoresThePreviousDefinition` (component): Holdervalue1/nativeidentity retained, initdefinition restored, namedinitrefused; next3.

**Observable:** A failing new init callback restores the prior XML definition and permits a later correction.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-RECREATE-RESTORE-FAILURE

**Verdict:** PARTIAL.

Failed restoration is reported explicitly without claiming success or a retained live singleton.

**Shipped surfaces:** `docs/usage.md:375` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlBeanRecreationTest#failedRestorationIsExplicitAndDoesNotReportSuccess` (component): Nosingletontarget, restorationfailed ledger.

**Observable:** Failed restoration is reported explicitly without claiming success or a retained live singleton.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-RECREATE-FINAL-HOLDER

**Verdict:** EXPECTED.

A final direct holder prevents destruction before rich XML recreation.

**Shipped surfaces:** `docs/usage.md:375` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlBeanRecreationTest#finalDirectHolderFieldIsRefusedBeforeDestruction` (component): Sameoldtarget, destroyed0, finalholderreason.

**Observable:** A final direct holder prevents destruction before rich XML recreation.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-RECREATE-CONTAINER-HOLDER

**Verdict:** EXPECTED.

A collection holder prevents destruction before rich XML recreation.

**Shipped surfaces:** `docs/usage.md:375` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlBeanRecreationTest#containerHolderIsRefusedBeforeDestruction` (component): Sameoldtarget, destroyed0, collectionholderreason.

**Observable:** A collection holder prevents destruction before rich XML recreation.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-RECREATE-CONTEXTS

**Verdict:** PARTIAL.

Each owning context resolves the same placeholder using its own live factory.

**Shipped surfaces:** `docs/usage.md:375` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlBeanRecreationTest#contextsResolveTheSamePlaceholderIndependently` (component): Contextoneproduct2/contexttwoproduct3.

**Observable:** Each owning context resolves the same placeholder using its own live factory.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-PARTIAL-SETTER

**Verdict:** PARTIAL.

A setter that mutates then throws leaves its application effect but preserves raw XML metadata; correction can recover.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlPropertyFailureIntegrityTest#mutatingThrowingSetterPreservesMetadataOnly` (component): Live labelpartial, rawsameold, failure; nextcorrected nofailure.

**Observable:** A setter that mutates then throws leaves its application effect but preserves raw XML metadata; correction can recover.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-LAZY-DEFERRED-VALIDATION

**Verdict:** PARTIAL.

Invalid values on an uncreated lazy XML bean remain deferred until native creation.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlPropertyFailureIntegrityTest#lazyInvalidValueRetainsDeferredValidation` (component): Colduncreated, rawinvalid, noreloadfailure; nativegetBean throws BeansException.

**Observable:** Invalid values on an uncreated lazy XML bean remain deferred until native creation.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-REMOVAL-REFUSAL

**Verdict:** EXPECTED.

Removing an XML property retains the old value/raw definition and requires restart while a sibling edit applies.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlPropertyRemovalDiagnosticsTest#removalWithEditWarnsAndKeepsRemovedValue` (component): Retainedold notdefault, rawsameretained, editedafter, namedrestart.

**Observable:** Removing an XML property retains the old value/raw definition and requires restart while a sibling edit applies.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-FACTORYBEAN-PREFLIGHT

**Verdict:** EXPECTED.

Native FactoryBean refusal does not query/create the product or call factory setters during classification.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlFactoryBeanProductDiagnosticsTest#warmedAndUnwarmedProductsRefuseBeforeMutatingFactory` (component): Four warm/cold+singleton/nonsingleton cases: countersunchanged before explicitgetBean, raw/mergedidentity preserved.

**Observable:** Native FactoryBean refusal does not query/create the product or call factory setters during classification.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-XML-METADATA-CONTEXTS

**Verdict:** EXPECTED.

Changed XML definition metadata protects both owning contexts while unrelated same-named context remains untouched.

**Shipped surfaces:** `docs/usage.md:181` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.xml.XmlDefinitionMetadataDiagnosticsTest#bothOwningContextsAreGuardedAndUnrelatedContextIsUntouched` (component): Bothsettingsbefore/sameidentity, bothsiblingsafter, isolatedsame/isolated; primaryfalse.

**Observable:** Changed XML definition metadata protects both owning contexts while unrelated same-named context remains untouched.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-MESSAGE-NATIVE-WATCHER

**Verdict:** PROVEN_WITH_SCOPE.

Native ResourceBundleMessageSource and ReloadableResourceBundleMessageSource return changed formatted locale text after repeated watched saves.

**Shipped surfaces:** `docs/product-reference.md:67` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context. UTF-8 physical messages_tr.properties, Turkish locale, fallbackmessages.properties; cachingforever.

**Tests/assertions:** `com.onurkat.reclazz.e2e.NativeMessageSourceReloadTest#cachedNativeMessagesFollowRepeatedWatchedLocaleSaves` (watcher): Four source-kind/application-loader cases; Before Ada ->Yeni Ada ->İyi günler Ada ->Again Ada with stablefallback. `com.onurkat.reclazz.e2e.ModernNativeMessageSourceReloadTest#cachedNativeMessagesFollowRepeatedWatchedLocaleSaves` (watcher): Four source-kind/application-loader cases; Before Ada ->Yeni Ada ->İyi günler Ada ->Again Ada with stablefallback.

**Observable:** Native ResourceBundleMessageSource and ReloadableResourceBundleMessageSource return changed formatted locale text after repeated watched saves.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-MESSAGE-NATIVE-CACHE-CONTROL

**Verdict:** PARTIAL.

A native message source stays stale after a file edit alone and reads the new text after the actual reset path.

**Shipped surfaces:** `docs/product-reference.md:67` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39 and isolated Spring6.1.14; ordinary/child application loader only when the named parameterized observer includes both; standalone synthetic local context. UTF-8 physical messages_tr.properties, Turkish locale, fallbackmessages.properties; cachingforever.

**Tests/assertions:** `com.onurkat.reclazz.e2e.NativeMessageSourceReloadTest#nativeCacheKeepsOldLookupUntilExplicitReset` (component): Both nativesource kinds: warmBefore, editYeni stillBefore, explicit productionclear returns true thenYeni. `com.onurkat.reclazz.e2e.ModernNativeMessageSourceReloadTest#nativeCacheKeepsOldLookupUntilExplicitReset` (component): Both nativesource kinds: warmBefore, editYeni stillBefore, explicit productionclear returns true thenYeni.

**Observable:** A native message source stays stale after a file edit alone and reads the new text after the actual reset path.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-MESSAGE-ANCESTOR-HOOK

**Verdict:** PARTIAL.

When reset methods coexist, the ancestor-reset method is preferred.

**Shipped surfaces:** `docs/product-reference.md:67` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringMessageSourceReloaderTest#theAncestorResetIsPreferredWhenBothExist` (component): Mockshape recordsincluding-ancestors; does not read a real parent source.

**Observable:** When reset methods coexist, the ancestor-reset method is preferred.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-MESSAGE-UNKNOWN-REFUSAL

**Verdict:** EXPECTED.

An unrecognized message source is not counted as successfully reset.

**Shipped surfaces:** `docs/product-reference.md:67` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringMessageSourceReloaderTest#aSourceWithNoResetIsNotCountedAsDone` (component): Mocksourceclearfalse.

**Observable:** An unrecognized message source is not counted as successfully reset.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-MESSAGE-THROWING-REFUSAL

**Verdict:** EXPECTED.

A reset that throws is not counted as done.

**Shipped surfaces:** `docs/product-reference.md:67` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringMessageSourceReloaderTest#aResetThatThrowsIsNotCountedEither` (component): Throwingmockclearfalse.

**Observable:** A reset that throws is not counted as done.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BUNDLE-PARENT-CACHE

**Verdict:** PARTIAL.

Explicit JDK bundle invalidation clears an ancestor loader cache.

**Shipped surfaces:** `docs/product-reference.md:67` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Spring5.3.39/Boot2.7.18 where used; direct component invocation, not a watched save.

**Tests/assertions:** `com.onurkat.reclazz.util.BundleCacheTest#aLoaderFurtherUpTheChainIsClearedToo` (component): Native URLloaderparentwarmold; childclear ->newtext.

**Observable:** Explicit JDK bundle invalidation clears an ancestor loader cache.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-TEMPLATE-NATIVE-RENDER

**Verdict:** PROVEN_WITH_SCOPE.

Native FreeMarker and Thymeleaf enabled caches serve new rendered text after repeated watched physical-file saves.

**Shipped surfaces:** `docs/product-reference.md:68` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. FreeMarker2.3.34/Thymeleaf3.1.3.RELEASE core engines, native jars on parent; physical file resolver, Thymeleaf HTML standard dialect; child application does not imply child-isolated engine.

**Tests/assertions:** `com.onurkat.reclazz.e2e.NativeTemplateReloadTest#cachedNativeRenderFollowsWatchedSavesAndRecoversFromMalformedTemplate` (watcher): Four engine/application-loader cases; Before Ada ->Yeni Ada ->Again Ada; native cache is enabled throughout.

**Observable:** Native FreeMarker and Thymeleaf enabled caches serve new rendered text after repeated watched physical-file saves.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-TEMPLATE-MALFORMED-RECOVERY

**Verdict:** PROVEN_WITH_SCOPE.

A malformed template produces a native render error; correcting its physical file restores rendered output.

**Shipped surfaces:** `docs/product-reference.md:68` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. FreeMarker2.3.34/Thymeleaf3.1.3.RELEASE core engines, native jars on parent; physical file resolver, Thymeleaf HTML standard dialect; child application does not imply child-isolated engine.

**Tests/assertions:** `com.onurkat.reclazz.e2e.NativeTemplateReloadTest#cachedNativeRenderFollowsWatchedSavesAndRecoversFromMalformedTemplate` (watcher): PROBE3 ERROR, correctedPROBE4 Recovered Ada; no promise of last-valid template rollback.

**Observable:** A malformed template produces a native render error; correcting its physical file restores rendered output.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-TEMPLATE-NATIVE-CACHE-CONTROL

**Verdict:** PARTIAL.

Native enabled template caches stay stale after a file edit alone and reread after explicit cache clear.

**Shipped surfaces:** `docs/product-reference.md:68` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. FreeMarker2.3.34/Thymeleaf3.1.3.RELEASE core engines, native jars on parent; physical file resolver, Thymeleaf HTML standard dialect; child application does not imply child-isolated engine.

**Tests/assertions:** `com.onurkat.reclazz.e2e.NativeTemplateReloadTest#nativeCacheKeepsOldRenderUntilExplicitClear` (component): Both engines Before ->editYeni stillBefore ->clear ->Yeni; no developer-mode cache bypass.

**Observable:** Native enabled template caches stay stale after a file edit alone and reread after explicit cache clear.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-TEMPLATE-NO-ENGINE

**Verdict:** EXPECTED.

A template reload with no registered engine reports zero work.

**Shipped surfaces:** `docs/product-reference.md:68` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Agent component, no native renderer.

**Tests/assertions:** `com.onurkat.reclazz.reload.TemplateReloadTest#anApplicationWithNoTemplateEngineHearsNothing` (component): Registrycleared, reload(view.html)=0.

**Observable:** A template reload with no registered engine reports zero work.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-TEMPLATE-UNKNOWN-ENGINE

**Verdict:** EXPECTED.

An unrecognized registered object is not mistaken for a native template engine.

**Shipped surfaces:** `docs/product-reference.md:68` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. Agent component, no native renderer.

**Tests/assertions:** `com.onurkat.reclazz.reload.TemplateReloadTest#anUnrecognisedObjectIsNotMistakenForAnEngine` (component): RegisterString, reloadreturns0.

**Observable:** An unrecognized registered object is not mistaken for a native template engine.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** Both named lanes observed; exact commands/counts/runtime/source/artifact hashes and raw paths in the machine record.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Other JVM vendors, operating systems, late attachment, unlisted framework versions and packaged/remote resources are unverified.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-BOOT3-PROPERTIES

**Verdict:** UNVERIFIED.

The complete properties/YAML/binding package works on native Boot3.

**Shipped surfaces:** `docs/usage.md:2143` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. No current observing method in this bounded package.

**Tests/assertions:** No observing test mapped; UNVERIFIED.

**Observable:** The complete properties/YAML/binding package works on native Boot3.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** No current-session lane promotion.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. No native Boot3 dependency graph/current watcher or validation/record acceptance in this package; isolated Spring6 does not supply Boot3.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-PACKAGED-RESOURCES

**Verdict:** UNVERIFIED.

Edits to templates/messages/config inside a packaged JAR/WAR are live.

**Shipped surfaces:** `docs/product-reference.md:68` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. No current observing method in this bounded package.

**Tests/assertions:** No observing test mapped; UNVERIFIED.

**Observable:** Edits to templates/messages/config inside a packaged JAR/WAR are live.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** No current-session lane promotion.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Tests edit physical files, not packaged archive entries; no current archive mutation/loader acceptance.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-MESSAGE-ANCESTOR-NATIVE

**Verdict:** UNVERIFIED.

A watched ancestor message bundle updates child-context native lookups.

**Shipped surfaces:** `docs/product-reference.md:67` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. No current observing method in this bounded package.

**Tests/assertions:** No observing test mapped; UNVERIFIED.

**Observable:** A watched ancestor message bundle updates child-context native lookups.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** No current-session lane promotion.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Only mock ancestor-method preference and single nativecontext observers; real parent/child source watcher absent.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-MESSAGE-CUSTOM-BASENAME

**Verdict:** UNVERIFIED.

Arbitrary custom message basenames are automatically classified as localization changes.

**Shipped surfaces:** `docs/product-reference.md:67` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. No current observing method in this bounded package.

**Tests/assertions:** No observing test mapped; UNVERIFIED.

**Observable:** Arbitrary custom message basenames are automatically classified as localization changes.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** No current-session lane promotion.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Classifier deliberately recognizes default messages/validation names; no custom-basename discovery watcher. Not advertised support.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-TEMPLATE-WEB-INTEGRATION

**Verdict:** UNVERIFIED.

Native Spring MVC/servlet template view resolvers and every HTML dialect render changed files.

**Shipped surfaces:** `docs/product-reference.md:68` (SHA-256 `a1686774b5a0d7ddec0ea36eea91ea048b2216093ce375bc5687cbf8b891c7cb`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. No current observing method in this bounded package.

**Tests/assertions:** No observing test mapped; UNVERIFIED.

**Observable:** Native Spring MVC/servlet template view resolvers and every HTML dialect render changed files.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** No current-session lane promotion.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Current observer covers core native engine file resolution; no servlet MVC view resolver/dialect acceptance.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-SOURCE-OUTPUT-COPY

**Verdict:** UNVERIFIED.

Editing source resources automatically copies every resource to the runtime location.

**Shipped surfaces:** `docs/usage.md:2143` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. No current observing method in this bounded package.

**Tests/assertions:** No observing test mapped; UNVERIFIED.

**Observable:** Editing source resources automatically copies every resource to the runtime location.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** No current-session lane promotion.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. Current resource observers directly edit the actual watched/runtime file; no source-resource build-copy acceptance.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

### P5-SAP-CONFIGURATION

**Verdict:** UNVERIFIED.

P5 native Spring resource proofs certify SAP tenant behavior.

**Shipped surfaces:** `docs/usage.md:2143` (SHA-256 `572c043fca4e7223eca3b574ede956c50bb8dde0dcefc0e09d8aca0e0293e8e0`).

**Default/opt-in:** Startup agent and actual physical resources in watched directories, or captured loaded local XML/Boot sources; documented supported singleton subset.

**Bounds:** SapMachine17/21, macOS arm64. No current observing method in this bounded package.

**Tests/assertions:** No observing test mapped; UNVERIFIED.

**Observable:** P5 native Spring resource proofs certify SAP tenant behavior.

**Control:** Exact assertions described at each named method; family-level scratch RED witnesses are separate and do not certify each sub-assertion independently.

**Execution:** No current-session lane promotion.

**Limitation / missing environment:** Only the stated observable and fixture shape; no blanket atomic rollback, arbitrary resource/provider or universal framework compatibility claim. No live licensed SAPtenant/resource/engine acceptance; synthetic standalone Spring context is a different environment.

**Reviewer / next action:** Astra read observing assertions; independent Claude audit pending. Independent Claude audit/full gate and local-only commit. Missing environments/combined observers remain explicitly unverified; no inferred new capability.

## P4: Messaging

Fresh local broker evidence: Kafka2.9.13/Kafka3.2.3, JMS5.3.39/ActiveMQ5.19.11 and Rabbit2.4.17/RabbitMQ4.3.5, each on stock SapMachine17/21 macOS arm64. Normal/child application loaders apply only where named; the framework/broker libraries remain on the parent. The exact-empty Kafka group predicate is repaired; no dependency or shared harness change. Other pre-existing assertions remain intact.

Classification: 72 PROVEN_WITH_SCOPE, 40 EXPECTED, 22 PARTIAL, 18 UNVERIFIED. Cells are scoped observations or explicit missing/excluded variants, not a count of independent product features.

Follow-up: all seven owner-selected records now have scoped real broker/watch proof. Exact `groupId=""` now follows ordinary native Spring own-ID/factory fallback after the owner-authorized repair; omitted groupId, `#{null}` and nonempty overrides are also observed. The desired empty-group watcher failed with the original production predicate in an isolated scratch copy; fresh current-tree observations passed on both required lanes. Whitespace-only group literals remain outside this repair.

Machine record: [p4-messaging.json](feature-evidence/p4-messaging.json); frozen manifest: [p4-cells.txt](feature-evidence/p4-cells.txt). Independent Claude audit/full gate pending. Bounded single-delivery windows are not exactly-once guarantees. Registration/adapter controls do not establish watcher behavior.

### P4-KAFKA-FIRST-ADD

**Verdict:** PROVEN_WITH_SCOPE. A first listener added to an already used plain singleton consumes a real broker message.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsTopicsRemovalAndRestore` (watcher): use=used before save; stage1 ADDED:1:owner:<route>:save1.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-EXISTING-PRESERVED

**Verdict:** PROVEN_WITH_SCOPE. Adding and editing beside an original listener preserves its real message handling.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): BASE:initial and BASE:save1..6 occur once each.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-BODY-EDIT

**Verdict:** PROVEN_WITH_SCOPE. Subsequent watched saves execute the changed callback body.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsTopicsRemovalAndRestore` (watcher): stage1/2/3/5 ADDED prefix contains current generation. `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): stage1/2/3/5 ADDED prefix contains current generation.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-ROUTE-EDIT

**Verdict:** PROVEN_WITH_SCOPE. A watched destination edit moves the consumer and stops delivery from the old route.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsTopicsRemovalAndRestore` (watcher): stage3 uses second route; old-topic messages have no ADDED output during bounded300ms wait. `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): stage3 uses second route; old-topic messages have no ADDED output during bounded300ms wait.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-METHOD-REMOVE

**Verdict:** PROVEN_WITH_SCOPE. Removing the added method removes its consumer registration.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsTopicsRemovalAndRestore` (watcher): stage4 state contains no added container. `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): stage4 state contains no added container.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-PRIVATE-RESTORE

**Verdict:** PROVEN_WITH_SCOPE. A later save restores a private callback and consumes a real message.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsTopicsRemovalAndRestore` (watcher): stage5 private method emits ADDED:5 for save5. `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): stage5 private method emits ADDED:5 for save5.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-ANNOTATION-REMOVE

**Verdict:** PROVEN_WITH_SCOPE. Removing just the listener annotation unregisters the added callback.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsTopicsRemovalAndRestore` (watcher): stage6 state contains no added container. `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): stage6 state contains no added container.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-BOUNDED-DUPLICATES

**Verdict:** PROVEN_WITH_SCOPE. Unique test messages produce one callback output in the observed duplicate window.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsTopicsRemovalAndRestore` (watcher): each deliver waits for real line then asserts exactly1 after200ms; not exactly-once guarantee. `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): each deliver waits for real line then asserts exactly1 after200ms; not exactly-once guarantee.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-RETIRED-CONSUMERS

**Verdict:** PROVEN_WITH_SCOPE. Retired containers are stopped after each observed save.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsTopicsRemovalAndRestore` (watcher): retiredRunning=0 across six saves. `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): retiredRunning=0 across six saves.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-UNRELATED-IDENTITY

**Verdict:** PROVEN_WITH_SCOPE. Reloading one bean preserves unrelated container identity and message handling.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsTopicsRemovalAndRestore` (watcher): otherSame=true and OTHER:save1..6 actual deliveries. `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): otherSame=true and OTHER:save1..6 actual deliveries.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-CONTEXT-CLOSE

**Verdict:** PROVEN_WITH_SCOPE. Closing the native application context stops all observed containers.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsTopicsRemovalAndRestore` (watcher): allStopped=true from native container states after ctx.close. `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): allStopped=true from native container states after ctx.close.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-PAYLOAD-HEADER

**Verdict:** PROVEN_WITH_SCOPE. Native argument conversion supplies payload and header to the added callback.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsTopicsRemovalAndRestore` (watcher): actual output owner:<received route/header>:saveN; supported void String payload/Header case. `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): actual output owner:<received route/header>:saveN; supported void String payload/Header case.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-FIRST-ADD

**Verdict:** PROVEN_WITH_SCOPE. A first listener added to an already used plain singleton consumes a real broker message.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): use=used before save; stage1 ADDED:1:owner:<route>:save1.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-EXISTING-PRESERVED

**Verdict:** PROVEN_WITH_SCOPE. Adding and editing beside an original listener preserves its real message handling.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): BASE:initial and BASE:save1..6 occur once each.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-BODY-EDIT

**Verdict:** PROVEN_WITH_SCOPE. Subsequent watched saves execute the changed callback body.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): stage1/2/3/5 ADDED prefix contains current generation. `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): stage1/2/3/5 ADDED prefix contains current generation.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-ROUTE-EDIT

**Verdict:** PROVEN_WITH_SCOPE. A watched destination edit moves the consumer and stops delivery from the old route.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): stage3 uses second route; old-topic messages have no ADDED output during bounded300ms wait. `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): stage3 uses second route; old-topic messages have no ADDED output during bounded300ms wait.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-METHOD-REMOVE

**Verdict:** PROVEN_WITH_SCOPE. Removing the added method removes its consumer registration.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): stage4 state contains no added container. `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): stage4 state contains no added container.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-PRIVATE-RESTORE

**Verdict:** PROVEN_WITH_SCOPE. A later save restores a private callback and consumes a real message.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): stage5 private method emits ADDED:5 for save5. `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): stage5 private method emits ADDED:5 for save5.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-ANNOTATION-REMOVE

**Verdict:** PROVEN_WITH_SCOPE. Removing just the listener annotation unregisters the added callback.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): stage6 state contains no added container. `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): stage6 state contains no added container.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-BOUNDED-DUPLICATES

**Verdict:** PROVEN_WITH_SCOPE. Unique test messages produce one callback output in the observed duplicate window.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): each deliver waits for real line then asserts exactly1 after200ms; not exactly-once guarantee. `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): each deliver waits for real line then asserts exactly1 after200ms; not exactly-once guarantee.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-RETIRED-CONSUMERS

**Verdict:** PROVEN_WITH_SCOPE. Retired containers are stopped after each observed save.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): retiredRunning=0 across six saves. `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): retiredRunning=0 across six saves.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-UNRELATED-IDENTITY

**Verdict:** PROVEN_WITH_SCOPE. Reloading one bean preserves unrelated container identity and message handling.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): otherSame=true and OTHER:save1..6 actual deliveries. `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): otherSame=true and OTHER:save1..6 actual deliveries.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-CONTEXT-CLOSE

**Verdict:** PROVEN_WITH_SCOPE. Closing the native application context stops all observed containers.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): allStopped=true from native container states after ctx.close. `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): allStopped=true from native container states after ctx.close.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-PAYLOAD-HEADER

**Verdict:** PROVEN_WITH_SCOPE. Native argument conversion supplies payload and header to the added callback.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): actual output owner:<received route/header>:saveN; supported void String payload/Header case. `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): actual output owner:<received route/header>:saveN; supported void String payload/Header case.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-LOCAL-ROLLBACK

**Verdict:** PROVEN_WITH_SCOPE. A local transaction failure redelivers the message to the added callback.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): first retry throws before ADDED, native redelivery flag permits one ADDED; one ROLLBACK:retry output. `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): first retry throws before ADDED, native redelivery flag permits one ADDED; one ROLLBACK:retry output.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-FIRST-ADD

**Verdict:** PROVEN_WITH_SCOPE. A first listener added to an already used plain singleton consumes a real broker message.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): use=used before save; stage1 ADDED:1:owner:<route>:save1.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-EXISTING-PRESERVED

**Verdict:** PROVEN_WITH_SCOPE. Adding and editing beside an original listener preserves its real message handling.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): BASE:initial and BASE:save1..6 occur once each.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-BODY-EDIT

**Verdict:** PROVEN_WITH_SCOPE. Subsequent watched saves execute the changed callback body.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): stage1/2/3/5 ADDED prefix contains current generation. `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): stage1/2/3/5 ADDED prefix contains current generation.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-ROUTE-EDIT

**Verdict:** PROVEN_WITH_SCOPE. A watched destination edit moves the consumer and stops delivery from the old route.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): stage3 uses second route; old-topic messages have no ADDED output during bounded300ms wait. `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): stage3 uses second route; old-topic messages have no ADDED output during bounded300ms wait.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-METHOD-REMOVE

**Verdict:** PROVEN_WITH_SCOPE. Removing the added method removes its consumer registration.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): stage4 state contains no added container. `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): stage4 state contains no added container.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-PRIVATE-RESTORE

**Verdict:** PROVEN_WITH_SCOPE. A later save restores a private callback and consumes a real message.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): stage5 private method emits ADDED:5 for save5. `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): stage5 private method emits ADDED:5 for save5.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-ANNOTATION-REMOVE

**Verdict:** PROVEN_WITH_SCOPE. Removing just the listener annotation unregisters the added callback.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): stage6 state contains no added container. `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): stage6 state contains no added container.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-BOUNDED-DUPLICATES

**Verdict:** PROVEN_WITH_SCOPE. Unique test messages produce one callback output in the observed duplicate window.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): each deliver waits for real line then asserts exactly1 after200ms; not exactly-once guarantee. `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): each deliver waits for real line then asserts exactly1 after200ms; not exactly-once guarantee.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-RETIRED-CONSUMERS

**Verdict:** PROVEN_WITH_SCOPE. Retired containers are stopped after each observed save.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): retiredRunning=0 across six saves. `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): retiredRunning=0 across six saves.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-UNRELATED-IDENTITY

**Verdict:** PROVEN_WITH_SCOPE. Reloading one bean preserves unrelated container identity and message handling.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): otherSame=true and OTHER:save1..6 actual deliveries. `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): otherSame=true and OTHER:save1..6 actual deliveries.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-CONTEXT-CLOSE

**Verdict:** PROVEN_WITH_SCOPE. Closing the native application context stops all observed containers.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): allStopped=true from native container states after ctx.close. `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): allStopped=true from native container states after ctx.close.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-PAYLOAD-HEADER

**Verdict:** PROVEN_WITH_SCOPE. Native argument conversion supplies payload and header to the added callback.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): actual output owner:<received route/header>:saveN; supported void String payload/Header case. `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): actual output owner:<received route/header>:saveN; supported void String payload/Header case.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-LOCAL-ROLLBACK

**Verdict:** PROVEN_WITH_SCOPE. A local transaction failure redelivers the message to the added callback.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Normal application loader, unchanged agent JVM, six saves; original/no-original configurations as named.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): first retry throws before ADDED, native redelivery flag permits one ADDED; one ROLLBACK:retry output. `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): first retry throws before ADDED, native redelivery flag permits one ADDED; one ROLLBACK:retry output.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-IDLESS-ORIGINAL

**Verdict:** PROVEN_WITH_SCOPE. An existing listener with a Spring-generated ID survives additions and subsequent saves.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Normal application loader; original id omitted, new listener literal ID.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#addingBesideAnIdlessExistingListenerPreservesItsConsumerAcrossSaves` (watcher): existingCount=1 and real BASE messages on each save; retiredRunning=0 and otherSame=true.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-PLACEHOLDER

**Verdict:** PROVEN_WITH_SCOPE. A property-placeholder route resolves through native Spring and consumes a real message.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Normal loader; literal added ID and native placeholder resolver; JMS includes selector placeholder.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerPlaceholderReloadTest#placeholderTopicResolvesAgainstTheEnvironmentAndConsumes` (watcher): ADDED:hello, resolved-kafka-group, removal leaves no added ID.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-PLACEHOLDER

**Verdict:** PROVEN_WITH_SCOPE. A property-placeholder route resolves through native Spring and consumes a real message.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal loader; literal added ID and native placeholder resolver; JMS includes selector placeholder.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsListenerPlaceholderReloadTest#placeholderDestinationResolvesAgainstTheEnvironmentAndConsumes` (watcher): ADDED:kept, no ADDED:dropped with native selector; removal leaves no added ID.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-WATCHED-SELECTOR

**Verdict:** PROVEN_WITH_SCOPE. Native selector filtering on a watched added listener delivers the accepted message and excludes the rejected one.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal loader; kept/dropped message labels, bounded no-output observation.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsListenerPlaceholderReloadTest#placeholderDestinationResolvesAgainstTheEnvironmentAndConsumes` (watcher): ADDED:kept, no ADDED:dropped with native selector; removal leaves no added ID.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-PLACEHOLDER

**Verdict:** PROVEN_WITH_SCOPE. A property-placeholder route resolves through native Spring and consumes a real message.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Normal loader; literal added ID and native placeholder resolver; JMS includes selector placeholder.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerPlaceholderReloadTest#placeholderQueueResolvesAgainstTheEnvironmentAndConsumes` (watcher): ADDED:hello, removal leaves no added ID.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-SELF-EXPRESSIONS

**Verdict:** PROVEN_WITH_SCOPE. Self-referencing topic/group expressions route real messages across watched saves.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Both normal and child application loaders; native framework/broker classes on parent; five watched saves.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerExpressionReloadTest#selfReferencingExpressionsRouteTopicsAndGroupsAcrossSaves` (watcher): routes one/two and group1/2 verified with real ADDED1/2 outputs.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-PROPERTY-ONLY-LIMIT

**Verdict:** EXPECTED. Changing expression inputs alone does not reroute the existing consumer.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Both normal and child application loaders; native framework/broker classes on parent; five watched saves.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerExpressionReloadTest#selfReferencingExpressionsRouteTopicsAndGroupsAcrossSaves` (watcher): /move followed by unchanged topicone/group1 before next class save.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-EXPRESSION-FAILURE

**Verdict:** EXPECTED. A non-String group expression leaves the failed registration absent.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Both normal and child application loaders; native framework/broker classes on parent; five watched saves.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerExpressionReloadTest#selfReferencingExpressionsRouteTopicsAndGroupsAcrossSaves` (watcher): version3 topics/group=none and must resolve to a String diagnostic.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-MULTITOPIC-RECOVERY

**Verdict:** PROVEN_WITH_SCOPE. A valid subsequent save recovers registration for multiple topics.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Both normal and child application loaders; native framework/broker classes on parent; five watched saves.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerExpressionReloadTest#selfReferencingExpressionsRouteTopicsAndGroupsAcrossSaves` (watcher): version4 consumes both newly sent topic messages, topics one,two/group3.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-GROUP-REPLAY

**Verdict:** PROVEN_WITH_SCOPE. Changing to a fresh group replays retained records according to the fixture offset policy.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Both normal and child application loaders; native framework/broker classes on parent; five watched saves.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerExpressionReloadTest#selfReferencingExpressionsRouteTopicsAndGroupsAcrossSaves` (watcher): ADDED4:v1-q0 and ADDED4:v2-q0 actual retained-record outputs.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-EXPRESSION-REMOVE

**Verdict:** PROVEN_WITH_SCOPE. Removing the expression listener removes its container.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Both normal and child application loaders; native framework/broker classes on parent; five watched saves.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerExpressionReloadTest#selfReferencingExpressionsRouteTopicsAndGroupsAcrossSaves` (watcher): version5 topics/group=none; six ADDED outputs total.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-HIDDEN-DELEGATE

**Verdict:** PROVEN_WITH_SCOPE. Expression callback additions remain absent from physical reflection while real messages invoke them.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Both normal and child application loaders; native framework/broker classes on parent; five watched saves.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerExpressionReloadTest#selfReferencingExpressionsRouteTopicsAndGroupsAcrossSaves` (watcher): /original=false on every watched save.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-BEAN-EXPRESSIONS

**Verdict:** PROVEN_WITH_SCOPE. Bean-driven queue expressions route real messages across watched saves.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Both normal and child application loaders; native framework/broker classes on parent; five watched saves.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerExpressionReloadTest#queueExpressionsMoveConsumersAndRecoverAfterErrors` (watcher): one then two queue; actual ADDED generation output.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-PROPERTY-ONLY-LIMIT

**Verdict:** EXPECTED. Changing the routing bean alone does not move the existing consumer.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Both normal and child application loaders; native framework/broker classes on parent; five watched saves.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerExpressionReloadTest#queueExpressionsMoveConsumersAndRecoverAfterErrors` (watcher): /move then queues stillone before class save.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-EXPRESSION-FAILURE

**Verdict:** EXPECTED. An invalid queue expression leaves the failed registration absent.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Both normal and child application loaders; native framework/broker classes on parent; five watched saves.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerExpressionReloadTest#queueExpressionsMoveConsumersAndRecoverAfterErrors` (watcher): version3 queues=none, no added ID and cannot resolve diagnostic.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-MULTIQUEUE-RECOVERY

**Verdict:** PROVEN_WITH_SCOPE. A valid subsequent save recovers consumers for multiple queues.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Both normal and child application loaders; native framework/broker classes on parent; five watched saves.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerExpressionReloadTest#queueExpressionsMoveConsumersAndRecoverAfterErrors` (watcher): version4 consumes one/two messages, four ADDED outputs total.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-EXPRESSION-REMOVE

**Verdict:** PROVEN_WITH_SCOPE. Removing the expression listener removes its container.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Both normal and child application loaders; native framework/broker classes on parent; five watched saves.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerExpressionReloadTest#queueExpressionsMoveConsumersAndRecoverAfterErrors` (watcher): version5 queues=none and no added ID.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-HIDDEN-DELEGATE

**Verdict:** PROVEN_WITH_SCOPE. Expression callback additions remain absent from physical reflection while actual messages invoke them.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Both normal and child application loaders; native framework/broker classes on parent; five watched saves.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerExpressionReloadTest#queueExpressionsMoveConsumersAndRecoverAfterErrors` (watcher): /original=false on every save.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-WATCHED-REPLY

**Verdict:** PROVEN_WITH_SCOPE. Added synchronous callbacks send actual text replies across watched saves.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal/child application loaders, six saves; native TextMessage, explicit correlation, local session transaction; no XA.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsRequestReplyReloadTest#addedRepliesFollowSavesDestinationsRetirementAndRestore` (watcher): REPLY currentgeneration:owner:<header>:saveN matches returned TextMessage.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-WATCHED-CORRELATION

**Verdict:** PROVEN_WITH_SCOPE. Watched added replies preserve an explicit incoming JMSCorrelationID.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal/child application loaders, six saves; native TextMessage, explicit correlation, local session transaction; no XA.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsRequestReplyReloadTest#addedRepliesFollowSavesDestinationsRetirementAndRestore` (watcher): response correlation-<value> equals request ID.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-WATCHED-REPLYTO

**Verdict:** PROVEN_WITH_SCOPE. JMSReplyTo overrides the added callback default reply queue.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal/child application loaders, six saves; native TextMessage, explicit correlation, local session transaction; no XA.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsRequestReplyReloadTest#addedRepliesFollowSavesDestinationsRetirementAndRestore` (watcher): stage2 reply arrives at chosen queue; default queue remains empty for200ms.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-WATCHED-REPLY-ROUTE

**Verdict:** PROVEN_WITH_SCOPE. A later watched save changes the default reply destination, including a placeholder.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal/child application loaders, six saves; native TextMessage, explicit correlation, local session transaction; no XA.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsRequestReplyReloadTest#addedRepliesFollowSavesDestinationsRetirementAndRestore` (watcher): stage3 new default queue and stage5 configured-placeholder queue receive actual replies.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-WATCHED-REPLY-RESTORE

**Verdict:** PROVEN_WITH_SCOPE. Removal and private restoration of an added reply callback preserve original/unrelated consumers.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal/child application loaders, six saves; native TextMessage, explicit correlation, local session transaction; no XA.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsRequestReplyReloadTest#addedRepliesFollowSavesDestinationsRetirementAndRestore` (watcher): stages4/6 no added, stage5 private reply; BASE/OTHER messages, retiredRunning0, otherSame=true.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-WATCHED-REPLY-ROLLBACK

**Verdict:** PROVEN_WITH_SCOPE. A failed local JMS attempt produces a reply after broker redelivery.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal/child application loaders, six saves; native TextMessage, explicit correlation, local session transaction; no XA.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsRequestReplyReloadTest#addedRepliesFollowSavesDestinationsRetirementAndRestore` (watcher): one ROLLBACK:retry, expected REPLY1 once, duplicate reply absent for200ms.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-WATCHED-REPLY-REFLECTION

**Verdict:** PARTIAL.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal/child application loaders, six saves; native TextMessage, explicit correlation, local session transaction; no XA.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsRequestReplyReloadTest#addedRepliesFollowSavesDestinationsRetirementAndRestore` (watcher): Baseline and stage4 have no added method; stages1,2,3,5,6 expose it through getDeclaredMethods, including private restoration and an annotation-free retained method.

**Limit:** Updated Reflection assertion verified in this session only on SapMachine21 application/child loaders. Historical SapMachine17 receipts predate this assertion refresh; no fresh JDK17 JMS run is claimed. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** The updated reflection assertions have current-source JDK21 request/reply observations. The updated JDK17 reflection expectation has not been rerun in this fix; the verdict remains PARTIAL. Historical lanes and fresh candidate receipts remain separate in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate.

### P4-KAFKA-COMPONENT-STANDARD-PROCESSOR-CONVERTS-MESSAGES-AND-PRIVATE-DELEGATE-USES-CURRENT-SINGLETON

**Verdict:** PARTIAL. Direct component contract: standard Processor Converts Messages And Private Delegate Uses Current Singleton.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringKafkaReloaderTest#standardProcessorConvertsMessagesAndPrivateDelegateUsesCurrentSingleton` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-COMPONENT-ORIGINAL-AND-ADDED-CONTAINERS-ARE-REMOVED-BEFORE-RECREATION-AND-UNRELATED-ONE-SURVIVES

**Verdict:** PARTIAL. Direct component contract: original And Added Containers Are Removed Before Recreation And Unrelated One Survives.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringKafkaReloaderTest#originalAndAddedContainersAreRemovedBeforeRecreationAndUnrelatedOneSurvives` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-COMPONENT-DUPLICATE-ID-CANNOT-STEAL-ANOTHER-BEANS-CONTAINER

**Verdict:** PARTIAL. Direct component contract: duplicate Id Cannot Steal Another Beans Container.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringKafkaReloaderTest#duplicateIdCannotStealAnotherBeansContainer` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-COMPONENT-INVALID-DECLARATIONS-DO-NOT-START-APARTIAL-REGISTRATION

**Verdict:** EXPECTED. Direct component contract: invalid Declarations Do Not Start APartial Registration.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringKafkaReloaderTest#invalidDeclarationsDoNotStartAPartialRegistration` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-COMPONENT-ACTUAL-SECOND-REGISTRATION-FAILURE-REMOVES-THE-FIRST-AND-KEEPS-UNRELATED-CONTAINER

**Verdict:** EXPECTED. Direct component contract: actual Second Registration Failure Removes The First And Keeps Unrelated Container.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringKafkaReloaderTest#actualSecondRegistrationFailureRemovesTheFirstAndKeepsUnrelatedContainer` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-COMPONENT-PROXY-IS-REFUSED-AND-NO-ADVICE-IS-BYPASSED

**Verdict:** EXPECTED. Direct component contract: proxy Is Refused And No Advice Is Bypassed.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringKafkaReloaderTest#proxyIsRefusedAndNoAdviceIsBypassed` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-COMPONENT-FILTERED-FACTORIES-ARE-REFUSED-BEFORE-CREATING-ANY-CONTAINER

**Verdict:** EXPECTED. Direct component contract: filtered Factories Are Refused Before Creating Any Container.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringKafkaReloaderTest#filteredFactoriesAreRefusedBeforeCreatingAnyContainer` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-COMPONENT-A-WRAPPED-EXISTING-LISTENER-IS-REFUSED-BEFORE-UNREGISTERING-IT

**Verdict:** EXPECTED. Direct component contract: a Wrapped Existing Listener Is Refused Before Unregistering It.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringKafkaReloaderTest#aWrappedExistingListenerIsRefusedBeforeUnregisteringIt` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-COMPONENT-CONCRETE-CONSUMER-RECORD-GENERIC-METADATA-REACHES-THE-REAL-KAFKA-ADAPTER

**Verdict:** PARTIAL. Direct component contract: concrete Consumer Record Generic Metadata Reaches The Real Kafka Adapter.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringKafkaReloaderTest#concreteConsumerRecordGenericMetadataReachesTheRealKafkaAdapter` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-COMPONENT-UNSUPPORTED-KAFKA-INFRASTRUCTURE-DOES-NOT-BLOCK-AN-UNRELATED-BEAN-RELOAD

**Verdict:** EXPECTED. Direct component contract: unsupported Kafka Infrastructure Does Not Block An Unrelated Bean Reload.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringKafkaReloaderTest#unsupportedKafkaInfrastructureDoesNotBlockAnUnrelatedBeanReload` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-COMPONENT-INTERRUPTED-STOP-RETAINS-REGISTRY-OWNERSHIP-AND-CAN-BE-RETRIED

**Verdict:** EXPECTED. Direct component contract: interrupted Stop Retains Registry Ownership And Can Be Retried.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringKafkaReloaderTest#interruptedStopRetainsRegistryOwnershipAndCanBeRetried` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-COMPONENT-TOPIC-AND-GROUP-EXPRESSIONS-MATCH-NATIVE-STARTUP

**Verdict:** PARTIAL. Direct component contract: topic And Group Expressions Match Native Startup.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringKafkaReloaderTest#topicAndGroupExpressionsMatchNativeStartup` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-COMPONENT-EXPRESSION-SCOPE-RESTORES-ITS-PREVIOUS-BINDING-AFTER-SUCCESS-AND-FAILURE

**Verdict:** EXPECTED. Direct component contract: expression Scope Restores Its Previous Binding After Success And Failure.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringKafkaReloaderTest#expressionScopeRestoresItsPreviousBindingAfterSuccessAndFailure` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-COMPONENT-EXPRESSION-ROUTING-BELONGS-TO-EACH-CONTEXT-AND-REEVALUATES-ON-SAVE

**Verdict:** PARTIAL. Direct component contract: expression Routing Belongs To Each Context And Reevaluates On Save.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringKafkaReloaderTest#expressionRoutingBelongsToEachContextAndReevaluatesOnSave` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-COMPONENT-EXPRESSION-FAILURES-CLEAN-PARTIAL-REGISTRATIONS-AND-SCOPE

**Verdict:** EXPECTED. Direct component contract: expression Failures Clean Partial Registrations And Scope.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringKafkaReloaderTest#expressionFailuresCleanPartialRegistrationsAndScope` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-COMPONENT-UNSUPPORTED-OPTIONS-SHAPES-AND-EXTRA-ADVICE-ARE-EXPLICITLY-REJECTED

**Verdict:** EXPECTED. Direct component contract: unsupported Options Shapes And Extra Advice Are Explicitly Rejected.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringKafkaReloaderTest#unsupportedOptionsShapesAndExtraAdviceAreExplicitlyRejected` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-COMPONENT-ACTUAL-SPRING-ADAPTER-USES-PRIVATE-METHOD-AND-CURRENT-SINGLETON

**Verdict:** PARTIAL. Direct component contract: actual Spring Adapter Uses Private Method And Current Singleton.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsReloaderTest#actualSpringAdapterUsesPrivateMethodAndCurrentSingleton` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-COMPONENT-OWNED-CONTAINERS-ARE-DESTROYED-AND-UNRELATED-IDENTITY-SURVIVES

**Verdict:** PARTIAL. Direct component contract: owned Containers Are Destroyed And Unrelated Identity Survives.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsReloaderTest#ownedContainersAreDestroyedAndUnrelatedIdentitySurvives` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-COMPONENT-DUPLICATE-ID-CANNOT-STEAL-ANOTHER-BEANS-CONTAINER

**Verdict:** PARTIAL. Direct component contract: duplicate Id Cannot Steal Another Beans Container.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsReloaderTest#duplicateIdCannotStealAnotherBeansContainer` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-COMPONENT-SECOND-REAL-REGISTRATION-FAILURE-CLEANS-ONLY-THIS-ATTEMPT

**Verdict:** EXPECTED. Direct component contract: second Real Registration Failure Cleans Only This Attempt.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsReloaderTest#secondRealRegistrationFailureCleansOnlyThisAttempt` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-COMPONENT-PROXY-RECEIVER-IS-REFUSED-WITHOUT-BYPASSING-ADVICE

**Verdict:** EXPECTED. Direct component contract: proxy Receiver Is Refused Without Bypassing Advice.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsReloaderTest#proxyReceiverIsRefusedWithoutBypassingAdvice` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-COMPONENT-UNSUPPORTED-INFRASTRUCTURE-DOES-NOT-BLOCK-AN-UNRELATED-BEAN

**Verdict:** EXPECTED. Direct component contract: unsupported Infrastructure Does Not Block An Unrelated Bean.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsReloaderTest#unsupportedInfrastructureDoesNotBlockAnUnrelatedBean` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-COMPONENT-MIXED-BROKER-REFUSAL-PRECEDES-ANY-JMS-OR-KAFKA-RETIREMENT

**Verdict:** EXPECTED. Direct component contract: mixed Broker Refusal Precedes Any JMS Or Kafka Retirement.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsReloaderTest#mixedBrokerRefusalPrecedesAnyJmsOrKafkaRetirement` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-COMPONENT-MIXED-RABBIT-JMS-OWNER-WITH-REMOVED-ANNOTATIONS-IS-REFUSED-BEFORE-EITHER-RETIRES

**Verdict:** EXPECTED. Direct component contract: mixed Rabbit JMS Owner With Removed Annotations Is Refused Before Either Retires.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsReloaderTest#mixedRabbitJmsOwnerWithRemovedAnnotationsIsRefusedBeforeEitherRetires` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-COMPONENT-UNFINISHED-SHUTDOWN-AND-REPEATED-WAIT-KEEP-REGISTRY-OWNERSHIP

**Verdict:** EXPECTED. Direct component contract: unfinished Shutdown And Repeated Wait Keep Registry Ownership.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsReloaderTest#unfinishedShutdownAndRepeatedWaitKeepRegistryOwnership` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-COMPONENT-UNSUPPORTED-FACTORIES-ARE-REFUSED-BEFORE-REGISTRATION

**Verdict:** EXPECTED. Direct component contract: unsupported Factories Are Refused Before Registration.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsReloaderTest#unsupportedFactoriesAreRefusedBeforeRegistration` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-COMPONENT-SELECTOR-AND-CONCURRENCY-USE-THE-ACTUAL-FACTORY-AND-BROKER

**Verdict:** PARTIAL. Direct component contract: selector And Concurrency Use The Actual Factory And Broker.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsReloaderTest#selectorAndConcurrencyUseTheActualFactoryAndBroker` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-COMPONENT-CUSTOM-EXISTING-ADAPTER-IS-REFUSED-BEFORE-CONTAINER-SHUTDOWN

**Verdict:** EXPECTED. Direct component contract: custom Existing Adapter Is Refused Before Container Shutdown.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsReloaderTest#customExistingAdapterIsRefusedBeforeContainerShutdown` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-COMPONENT-INVALID-METADATA-CANNOT-CREATE-PARTIAL-REGISTRATION

**Verdict:** EXPECTED. Direct component contract: invalid Metadata Cannot Create Partial Registration.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsReloaderTest#invalidMetadataCannotCreatePartialRegistration` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-COMPONENT-UNSUPPORTED-SHAPES-AND-METHOD-ADVICE-ARE-EXPLICITLY-REFUSED

**Verdict:** EXPECTED. Direct component contract: unsupported Shapes And Method Advice Are Explicitly Refused.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsReloaderTest#unsupportedShapesAndMethodAdviceAreExplicitlyRefused` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-REPLY-COMPONENT-DEFAULT-DESTINATION-REPLY-TO-AND-CORRELATION-MATCH-NATIVE-SPRING

**Verdict:** PARTIAL. Direct component contract: default Destination Reply To And Correlation Match Native Spring.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsRequestReplyTest#defaultDestinationReplyToAndCorrelationMatchNativeSpring` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-REPLY-COMPONENT-REPLY-TO-NEEDS-NO-SEND-TO-ANNOTATION

**Verdict:** PARTIAL. Direct component contract: reply To Needs No Send To Annotation.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsRequestReplyTest#replyToNeedsNoSendToAnnotation` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-REPLY-COMPONENT-CONVERTER-PRESERVES-BYTES-MAP-AND-NATIVE-AND-SPRING-MESSAGES

**Verdict:** PARTIAL. Direct component contract: converter Preserves Bytes Map And Native And Spring Messages.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsRequestReplyTest#converterPreservesBytesMapAndNativeAndSpringMessages` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-REPLY-COMPONENT-NULL-RESULT-SENDS-NOTHING-AFTER-THE-BODY-RUNS

**Verdict:** PARTIAL. Direct component contract: null Result Sends Nothing After The Body Runs.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsRequestReplyTest#nullResultSendsNothingAfterTheBodyRuns` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-REPLY-COMPONENT-FAILED-TRANSACTED-ATTEMPT-PRODUCES-ONE-REPLY-ONLY-AFTER-REDELIVERY

**Verdict:** PARTIAL. Direct component contract: failed Transacted Attempt Produces One Reply Only After Redelivery.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsRequestReplyTest#failedTransactedAttemptProducesOneReplyOnlyAfterRedelivery` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-REPLY-COMPONENT-INVALID-SEND-TO-LEAVES-NO-CONTAINER-AND-NEXT-CORRECTED-SAVE-RECOVERS

**Verdict:** EXPECTED. Direct component contract: invalid Send To Leaves No Container And Next Corrected Save Recovers.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsRequestReplyTest#invalidSendToLeavesNoContainerAndNextCorrectedSaveRecovers` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-REPLY-COMPONENT-UNSUPPORTED-RESULTS-AND-ADVICE-STAY-REFUSED

**Verdict:** EXPECTED. Direct component contract: unsupported Results And Advice Stay Refused.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringJmsRequestReplyTest#unsupportedResultsAndAdviceStayRefused` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-COMPONENT-EXISTING-LISTENER-METADATA-DOES-NOT-BLOCK-ORDINARY-RECREATION

**Verdict:** PARTIAL. Direct component contract: existing Listener Metadata Does Not Block Ordinary Recreation.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringRabbitReloaderTest#existingListenerMetadataDoesNotBlockOrdinaryRecreation` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-COMPONENT-ACTUAL-SPRING-ADAPTER-USES-PRIVATE-METHOD-AND-CURRENT-SINGLETON

**Verdict:** PARTIAL. Direct component contract: actual Spring Adapter Uses Private Method And Current Singleton.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringRabbitReloaderTest#actualSpringAdapterUsesPrivateMethodAndCurrentSingleton` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-COMPONENT-OWNED-CONTAINERS-ARE-DESTROYED-AND-UNRELATED-IDENTITY-SURVIVES

**Verdict:** PARTIAL. Direct component contract: owned Containers Are Destroyed And Unrelated Identity Survives.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringRabbitReloaderTest#ownedContainersAreDestroyedAndUnrelatedIdentitySurvives` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-COMPONENT-DUPLICATE-ID-CANNOT-STEAL-ANOTHER-BEANS-CONTAINER

**Verdict:** PARTIAL. Direct component contract: duplicate Id Cannot Steal Another Beans Container.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringRabbitReloaderTest#duplicateIdCannotStealAnotherBeansContainer` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-COMPONENT-SECOND-REAL-REGISTRATION-FAILURE-CLEANS-ONLY-THIS-ATTEMPT

**Verdict:** EXPECTED. Direct component contract: second Real Registration Failure Cleans Only This Attempt.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringRabbitReloaderTest#secondRealRegistrationFailureCleansOnlyThisAttempt` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-COMPONENT-PROXY-RECEIVER-IS-REFUSED-WITHOUT-BYPASSING-ADVICE

**Verdict:** EXPECTED. Direct component contract: proxy Receiver Is Refused Without Bypassing Advice.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringRabbitReloaderTest#proxyReceiverIsRefusedWithoutBypassingAdvice` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-COMPONENT-UNSUPPORTED-INFRASTRUCTURE-DOES-NOT-BLOCK-AN-UNRELATED-BEAN

**Verdict:** EXPECTED. Direct component contract: unsupported Infrastructure Does Not Block An Unrelated Bean.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringRabbitReloaderTest#unsupportedInfrastructureDoesNotBlockAnUnrelatedBean` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-COMPONENT-MIXED-BROKER-REFUSAL-PRECEDES-ANY-RABBIT-OR-KAFKA-RETIREMENT

**Verdict:** EXPECTED. Direct component contract: mixed Broker Refusal Precedes Any Rabbit Or Kafka Retirement.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringRabbitReloaderTest#mixedBrokerRefusalPrecedesAnyRabbitOrKafkaRetirement` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-COMPONENT-UNSUPPORTED-FACTORIES-ARE-REFUSED-BEFORE-REGISTRATION

**Verdict:** EXPECTED. Direct component contract: unsupported Factories Are Refused Before Registration.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringRabbitReloaderTest#unsupportedFactoriesAreRefusedBeforeRegistration` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-COMPONENT-QUEUE-EXPRESSIONS-MATCH-NATIVE-ANNOTATION-PROCESSING

**Verdict:** PARTIAL. Direct component contract: queue Expressions Match Native Annotation Processing.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringRabbitReloaderTest#queueExpressionsMatchNativeAnnotationProcessing` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-COMPONENT-QUEUE-EXPRESSIONS-REEVALUATE-ON-SAVE-AND-RECOVER-AFTER-RESOLUTION-FAILURE

**Verdict:** EXPECTED. Direct component contract: queue Expressions Reevaluate On Save And Recover After Resolution Failure.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringRabbitReloaderTest#queueExpressionsReevaluateOnSaveAndRecoverAfterResolutionFailure` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-COMPONENT-QUEUE-EXPRESSION-FAILURES-REMOVE-PARTIAL-REGISTRATIONS

**Verdict:** EXPECTED. Direct component contract: queue Expression Failures Remove Partial Registrations.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringRabbitReloaderTest#queueExpressionFailuresRemovePartialRegistrations` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-COMPONENT-INVALID-METADATA-CANNOT-CREATE-PARTIAL-REGISTRATION

**Verdict:** EXPECTED. Direct component contract: invalid Metadata Cannot Create Partial Registration.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringRabbitReloaderTest#invalidMetadataCannotCreatePartialRegistration` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-COMPONENT-UNSUPPORTED-SHAPES-AND-METHOD-ADVICE-ARE-EXPLICITLY-REFUSED

**Verdict:** EXPECTED. Direct component contract: unsupported Shapes And Method Advice Are Explicitly Refused.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringRabbitReloaderTest#unsupportedShapesAndMethodAdviceAreExplicitlyRefused` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-COMPONENT-REFUSED-SAVE-DOES-NOT-RETIRE-EXISTING-CONSUMERS

**Verdict:** EXPECTED. Direct component contract: refused Save Does Not Retire Existing Consumers.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringRabbitReloaderTest#refusedSaveDoesNotRetireExistingConsumers` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-COMPONENT-CUSTOM-EXISTING-ADAPTER-IS-REFUSED-BEFORE-RETIREMENT

**Verdict:** EXPECTED. Direct component contract: custom Existing Adapter Is Refused Before Retirement.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringRabbitReloaderTest#customExistingAdapterIsRefusedBeforeRetirement` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-COMPONENT-EXISTING-CONTAINER-ADVICE-IS-REFUSED-BEFORE-RETIREMENT

**Verdict:** EXPECTED. Direct component contract: existing Container Advice Is Refused Before Retirement.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringRabbitReloaderTest#existingContainerAdviceIsRefusedBeforeRetirement` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-COMPONENT-EXPLICIT-FACTORY-AND-CONCURRENCY-USE-SPRING-SETTINGS

**Verdict:** PARTIAL. Direct component contract: explicit Factory And Concurrency Use Spring Settings.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringRabbitReloaderTest#explicitFactoryAndConcurrencyUseSpringSettings` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-COMPONENT-SHUTDOWN-DOES-NOT-MEAN-BLOCKED-ORIGINAL-CALLBACK-HAS-FINISHED

**Verdict:** EXPECTED. Direct component contract: shutdown Does Not Mean Blocked Original Callback Has Finished.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.spring.SpringRabbitLifecycleTest#shutdownDoesNotMeanBlockedOriginalCallbackHasFinished` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-WORKER-COMPONENT-QUEUED-WORKER-AND-NORMAL-OR-EXCEPTIONAL-EXIT-STAY-TRACKED

**Verdict:** PARTIAL. Direct component contract: queued Worker And Normal Or Exceptional Exit Stay Tracked.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.transform.RabbitConsumerTransformerTest#queuedWorkerAndNormalOrExceptionalExitStayTracked` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-WORKER-COMPONENT-EXACT-SPRING-BYTES-VERIFY-AND-UNKNOWN-SHAPES-OR-LATE-ATTACH-ARE-REFUSED

**Verdict:** EXPECTED. Direct component contract: exact Spring Bytes Verify And Unknown Shapes Or Late Attach Are Refused.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Direct registry/adapter/transform invocation; broker is real only where source starts it. Native method/declaring-type comparison is not watched broker proof.

**Observation:** `com.onurkat.reclazz.transform.RabbitConsumerTransformerTest#exactSpringBytesVerifyAndUnknownShapesOrLateAttachAreRefused` (component): Direct assertion excerpts and full source hash in the machine record.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other OS/JVM providers, unlisted brokers/frameworks, hosted/external clusters, late attach and native Spring6/Jakarta messaging are unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-MODERN

**Verdict:** PROVEN_WITH_SCOPE.

Native Spring6/Jakarta messaging listener reload.

**Shipped surfaces:** `docs/usage.md` (dedicated Kafka/JMS/Rabbit modern compatibility paragraphs; current location/digest pinned in JSON).

**Default/opt-in:** Startup -javaagent for watcher records; configured native listener processor/factory and documented plain singleton subset.

**Bounds:** Stock SapMachine17/21 macOS arm64; startup agent, normal application loader, EXACT Spring Kafka3.2.4 / kafka-clients+embedded broker3.7.1 / Framework6.1.14, standard plain singleton factory, verified package version3.2.4 only.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ModernKafkaListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsTopicsRemovalAndRestore` (watcher): Actual first/existing added listener delivery over six saves, body/topic edits, removal/private restore/unannotation; native agentless public/private/routing control before each watcher; retired consumers stop, unrelated identity and context close retained. Every original desired assertion retained; native control runs first.; `com.onurkat.reclazz.e2e.ModernKafkaListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): Actual first/existing added listener delivery over six saves, body/topic edits, removal/private restore/unannotation; native agentless public/private/routing control before each watcher; retired consumers stop, unrelated identity and context close retained. Every original desired assertion retained; native control runs first.

**Observable:** Actual first/existing added listener delivery over six saves, body/topic edits, removal/private restore/unannotation; native agentless public/private/routing control before each watcher; retired consumers stop, unrelated identity and context close retained.

**Control:** Native agentless startup controls precede each desired observer. Kafka/JMS research patch removal4/4 behavior RED then v2 identical desired4 GREEN on eachJDK; research receipts kept distinct from independent ported audit. No universal exactly-once claim.

**Execution:** Actual isolated modernMessagingTest57/57 PASSED on eachJDK17/21. Fresh native controls before watchers; source/artifact/XML/runtime provenance in JSON. Kafka/JMS are bounded runtime repairs; Rabbit is existing-behavior evidence. JDK21 gate task coverage completed in sequential runs (2783 tests, zero remaining failures/errors, 5 existing skips); first full command failed a nonreproducing RF09 probe, independent Claude audit pending.

**Limitation / missing environment:** Not every Kafka3.x minor. Custom/filter/batch and retry-topic factories remain refused; other versions, direct child-loader watcher, modern reply, transactions, rebalance, hosted/multinode/late attach and RF14 dependency cascade unverified.

**Reviewer / next action:** Astra actual main JDK17/21 source/XML/native observations mapped; independent Claude audit pending. Independent Claude full gate and ported patch-removal RED/restored GREEN, then local-only commit. All unlisted RF13 axes and RF14 remain separate.

### P4-KAFKA-OTHER-ENVIRONMENTS

**Verdict:** UNVERIFIED. Messaging support on other operating systems, JVM providers and broker versions.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. 

**Observation:** No observing test mapped; UNVERIFIED.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. No current Windows/Linux/JBR/DCEVM/other-provider/broker execution.

**Execution/control:** No verdict promotion. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-HOSTED

**Verdict:** UNVERIFIED. Messaging reload against an external or hosted cluster.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. 

**Observation:** No observing test mapped; UNVERIFIED.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. No external service/cluster acceptance; local single-broker results do not certify it.

**Execution/control:** No verdict promotion. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-LATE-ATTACH

**Verdict:** UNVERIFIED. Messaging additions after late agent attachment.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. 

**Observation:** No observing test mapped; UNVERIFIED.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Startup agent only; no late-attachment watcher acceptance.

**Execution/control:** No verdict promotion. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-CASCADE

**Verdict:** PROVEN_WITH_SCOPE. Bounded Kafka helper/property consumer recreation.

**Bounds:** Startup agent, normal application loader, stock JDK17/21 on macOS arm64; Framework5.3.39/Kafka2.9.13 and Framework6.1.14/Kafka3.2.4. One plain directly constructed local helper, one plain leaf owner, one literal-id void record callback and standard native infrastructure. Nontransactional explicit RECORD and native-default BATCH acknowledgment; no batch-listener callback claim.

**Observation:** `KafkaConsumerCascadeReloadTest` and `ModernKafkaConsumerCascadeReloadTest` inherit12 cases from `KafkaConsumerCascadeContract.helperAndPropertyChangesCoordinateConsumer`: four fresh agentless10/20/30/40 controls, then watched helper/property saves20/30/40 for original/added/coaffected callbacks. Actual mode, current helper/own scalar messages, accepted save, unchanged listener, live owner/edges, native worker completion before replacement and at close, unrelated identity/delivery and bounded single-observed delivery are asserted.

**Limit:** No generic graph engine, rollback or exactly-once/transaction/rebalance/offset guarantee. Timeout may leave a retiring consumer with original singleton/registry ownership; failed reconstruction may be partial. Batch-listener APIs, richer callbacks/options, multiple workers/partitions/owners, custom infrastructure/advice, parent-child/child-loader watcher, concurrent configuration, hosted providers and two-argument security/XML/Boot reconstruction remain outside scope. Rabbit cascade stays UNVERIFIED; JMS evidence is unchanged.

**Execution/control:** Main38/0 per graph/JDK (12 watchers,23 native lifecycle/refusal guards,3 optional-stack controls). Current-base48 desired RED after192 native controls; source-identical caller removal24 behavioral RED and relevance removal4 optional RED on21; byte-identical restoration30 GREEN. Owned second-processor refusal and native completion futures are exercised; component controls do not certify watchers. JSON binds exact commands, source/artifact hashes and raw XML. Independent Claude audit/full gate/commit pending.

### P4-JMS-MODERN

**Verdict:** PROVEN_WITH_SCOPE.

Native Spring6/Jakarta messaging listener reload.

**Shipped surfaces:** `docs/usage.md` (dedicated Kafka/JMS/Rabbit modern compatibility paragraphs; current location/digest pinned in JSON).

**Default/opt-in:** Startup -javaagent for watcher records; configured native listener processor/factory and documented plain singleton subset.

**Bounds:** Stock SapMachine17/21 macOS arm64; startup agent, normal/child application loaders, SpringJMS6.1.14 / JakartaJMS3.1.0 / ActiveMQClassic6.1.3; synchronous String/native jakarta Message replies and void delivery.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ModernJmsListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): Real void delivery and native/String replies follow six saves on normal/child loaders; SendTo/JMSReplyTo/correlation/rollback-redelivery/private restoration, destination edits, single observed reply, retired consumers/unrelated identity/close. Native agentless public/private controls before each desired observer. Every original desired assertion retained; native control runs first.; `com.onurkat.reclazz.e2e.ModernJmsListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): Real void delivery and native/String replies follow six saves on normal/child loaders; SendTo/JMSReplyTo/correlation/rollback-redelivery/private restoration, destination edits, single observed reply, retired consumers/unrelated identity/close. Native agentless public/private controls before each desired observer. Every original desired assertion retained; native control runs first.; `com.onurkat.reclazz.e2e.ModernJmsReplyReloadTest#addedRepliesFollowSavesDestinationsRetirementAndRestore` (watcher): Real void delivery and native/String replies follow six saves on normal/child loaders; SendTo/JMSReplyTo/correlation/rollback-redelivery/private restoration, destination edits, single observed reply, retired consumers/unrelated identity/close. Native agentless public/private controls before each desired observer. Every original desired assertion retained; native control runs first.; `com.onurkat.reclazz.e2e.ModernJmsNativeReplyReloadTest#addedRepliesFollowSavesDestinationsRetirementAndRestore` (watcher): Real void delivery and native/String replies follow six saves on normal/child loaders; SendTo/JMSReplyTo/correlation/rollback-redelivery/private restoration, destination edits, single observed reply, retired consumers/unrelated identity/close. Native agentless public/private controls before each desired observer. Every original desired assertion retained; native control runs first.

**Observable:** Real void delivery and native/String replies follow six saves on normal/child loaders; SendTo/JMSReplyTo/correlation/rollback-redelivery/private restoration, destination edits, single observed reply, retired consumers/unrelated identity/close. Native agentless public/private controls before each desired observer.

**Control:** Native agentless startup controls precede each desired observer. Kafka/JMS research patch removal4/4 behavior RED then v2 identical desired4 GREEN on eachJDK; research receipts kept distinct from independent ported audit. No universal exactly-once claim.

**Execution:** Actual isolated modernMessagingTest57/57 PASSED on eachJDK17/21. Fresh native controls before watchers; source/artifact/XML/runtime provenance in JSON. Kafka/JMS are bounded runtime repairs; Rabbit is existing-behavior evidence. JDK21 gate task coverage completed in sequential runs (2783 tests, zero remaining failures/errors, 5 existing skips); first full command failed a nonreproducing RF09 probe, independent Claude audit pending.

**Limitation / missing environment:** Existing javax Message retained, not a migration engine. No XA/durable/shared/topics/provider-wide, late attach, hosted, arbitrary advice/signatures/async or dependency-cascade guarantee.

**Reviewer / next action:** Astra actual main JDK17/21 source/XML/native observations mapped; independent Claude audit pending. Independent Claude full gate and ported patch-removal RED/restored GREEN, then local-only commit. All unlisted RF13 axes and RF14 remain separate.

### P4-JMS-OTHER-ENVIRONMENTS

**Verdict:** UNVERIFIED. Messaging support on other operating systems, JVM providers and broker versions.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. 

**Observation:** No observing test mapped; UNVERIFIED.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. No current Windows/Linux/JBR/DCEVM/other-provider/broker execution.

**Execution/control:** No verdict promotion. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-HOSTED

**Verdict:** UNVERIFIED. Messaging reload against an external or hosted cluster.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. 

**Observation:** No observing test mapped; UNVERIFIED.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. No external service/cluster acceptance; local single-broker results do not certify it.

**Execution/control:** No verdict promotion. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-LATE-ATTACH

**Verdict:** UNVERIFIED. Messaging additions after late agent attachment.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. 

**Observation:** No observing test mapped; UNVERIFIED.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Startup agent only; no late-attachment watcher acceptance.

**Execution/control:** No verdict promotion. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-CASCADE

**Verdict:** PROVEN_WITH_SCOPE. Bounded JMS helper/property dependency refresh.

**Bounds:** Startup agent, normal application loader, one known root context; Spring/JMS5.3.39 with javax/ActiveMQ5.19.11 or Spring/JMS6.1.14 with JakartaJMS3.1.0/ActiveMQ6.1.3; stock SapMachine17/21 on macOS arm64. Measured @Component helper and leaf listener, original/added callback, literal IDs and standard default/literal factory. Helper body/property saves include a same-key scalar on the listener constructor; direct-only consumer properties require restart.

**Observation:** `JmsConsumerCascadeReloadTest` and `ModernJmsConsumerCascadeReloadTest` inherit `JmsConsumerCascadeContract.helperAndPropertyChangesCoordinateConsumer`: fresh agentless10/20/30/40 controls, then three watched helper/property saves with actual current helper/own-scalar messages and an accepted-save signal, unchanged Listener.java, current owner/native edges, old worker retired before new owner, unrelated delivery/identity, bounded single-observed delivery and asserted clean close.

**Limit:** No Kafka/Rabbit cascade promotion, generic graph reconstruction, atomic rollback or universal exactly-once. Unsupported creation/context/owner shapes refuse before singleton destruction. Indirect timeout can leave a retiring consumer with original singleton/registry ownership and requires restart on later indirect saves. Failed creation/property application can be partial. Child-loader delivery, multiple-owner/save batches, security/XML/Boot constructor-bound paths, hosted providers and XA remain unverified.

**Execution/control:** Main24/24 on each graph and each JDK (6 watcher cases plus18 native component guard invocations per run). The source-identical port with class/property routing removed is6/6 behavioral RED on each graph/JDK21 after24 fresh native controls each. Component guards cover a real30s blocked shutdown, failed creation and post-registration cleanup, direct-only property refusal, native parent-only edges and foreign-broker/creation-policy refusals; they do not certify file-watcher behavior. Full source/artifact/command/XML provenance is in JSON; independent Claude audit/commit pending.

### P4-RABBIT-MODERN

**Verdict:** PROVEN_WITH_SCOPE.

Native Spring6/Jakarta messaging listener reload.

**Shipped surfaces:** `docs/usage.md` (dedicated Kafka/JMS/Rabbit modern compatibility paragraphs; current location/digest pinned in JSON).

**Default/opt-in:** Startup -javaagent for watcher records; configured native listener processor/factory and documented plain singleton subset.

**Bounds:** Stock SapMachine17/21 macOS arm64; startup agent, normal application loader, SpringRabbit3.1.7 / Framework6.1.14 / pinned RabbitMQ4.3.5 sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ModernRabbitListenerReloadTest#firstListenerOnAnAlreadyUsedBeanFollowsEditsDestinationsRemovalAndRestore` (watcher): Direct first/existing/idless listener delivery over six saves, queue changes, rollback/redelivery, removal/private restore/unannotation; native agentless controls before watchers, retired consumers, unrelated identity and close. Already-working behavior, no Rabbit runtime repair. Every original desired assertion retained; native control runs first.; `com.onurkat.reclazz.e2e.ModernRabbitListenerReloadTest#addingBesideAnExistingListenerKeepsOneConsumerAndLeavesOtherBeanAlone` (watcher): Direct first/existing/idless listener delivery over six saves, queue changes, rollback/redelivery, removal/private restore/unannotation; native agentless controls before watchers, retired consumers, unrelated identity and close. Already-working behavior, no Rabbit runtime repair. Every original desired assertion retained; native control runs first.; `com.onurkat.reclazz.e2e.ModernRabbitListenerReloadTest#addingBesideAnIdlessExistingListenerPreservesItsConsumerAcrossSaves` (watcher): Direct first/existing/idless listener delivery over six saves, queue changes, rollback/redelivery, removal/private restore/unannotation; native agentless controls before watchers, retired consumers, unrelated identity and close. Already-working behavior, no Rabbit runtime repair. Every original desired assertion retained; native control runs first.

**Observable:** Direct first/existing/idless listener delivery over six saves, queue changes, rollback/redelivery, removal/private restore/unannotation; native agentless controls before watchers, retired consumers, unrelated identity and close. Already-working behavior, no Rabbit runtime repair.

**Control:** Native agentless startup controls precede each desired observer. Kafka/JMS research patch removal4/4 behavior RED then v2 identical desired4 GREEN on eachJDK; research receipts kept distinct from independent ported audit. No universal exactly-once claim.

**Execution:** Actual isolated modernMessagingTest57/57 PASSED on eachJDK17/21. Fresh native controls before watchers; source/artifact/XML/runtime provenance in JSON. Kafka/JMS are bounded runtime repairs; Rabbit is existing-behavior evidence. JDK21 gate task coverage completed in sequential runs (2783 tests, zero remaining failures/errors, 5 existing skips); first full command failed a nonreproducing RF09 probe, independent Claude audit pending.

**Limitation / missing environment:** Modern Rabbit reply/async, direct child-loader watcher, other versions/providers/OS, late attach/hosted and RF14 dependency cascade unverified.

**Reviewer / next action:** Astra actual main JDK17/21 source/XML/native observations mapped; independent Claude audit pending. Independent Claude full gate and ported patch-removal RED/restored GREEN, then local-only commit. All unlisted RF13 axes and RF14 remain separate.

### P4-RABBIT-OTHER-ENVIRONMENTS

**Verdict:** UNVERIFIED. Messaging support on other operating systems, JVM providers and broker versions.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. 

**Observation:** No observing test mapped; UNVERIFIED.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. No current Windows/Linux/JBR/DCEVM/other-provider/broker execution.

**Execution/control:** No verdict promotion. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-HOSTED

**Verdict:** UNVERIFIED. Messaging reload against an external or hosted cluster.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. 

**Observation:** No observing test mapped; UNVERIFIED.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. No external service/cluster acceptance; local single-broker results do not certify it.

**Execution/control:** No verdict promotion. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-LATE-ATTACH

**Verdict:** UNVERIFIED. Messaging additions after late agent attachment.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. 

**Observation:** No observing test mapped; UNVERIFIED.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Startup agent only; no late-attachment watcher acceptance.

**Execution/control:** No verdict promotion. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-CASCADE

**Verdict:** PROVEN_WITH_SCOPE. Bounded Rabbit helper/property consumer recreation.

**Bounds:** Startup agent, normal application loader, stock SapMachine17/21 on macOS arm64; Framework5.3.39/AMQP2.4.17 and Framework6.1.14/AMQP3.1.7, pinned RabbitMQ4.3.5. One root context, one plain directly constructed helper, one plain local leaf owner, one void literal-id String callback and standard native simple infrastructure. Ordinary native AUTO acknowledgment and nontransacted channels.

**Observation:** `RabbitConsumerCascadeReloadTest` and `ModernRabbitConsumerCascadeReloadTest` inherit six cases from `RabbitConsumerCascadeContract.helperAndPropertyChangesCoordinateConsumer`: four fresh agentless10/20/30/40 controls, then three watched helper/property saves20/30/40 for original/added/coaffected callbacks. Exact captured/current helper and own scalar messages, accepted save, unchanged listener/direct reload count, live singleton/native edges, complete worker retirement before replacement and at close, unrelated identity/delivery and bounded single-observed delivery are asserted.

**Limit:** No generic graph engine, rollback or transactional/manual/batch acknowledgment, retry/recovery/distributed/exactly-once guarantee. Timeout can leave a retiring worker with original singleton/registry ownership; failed reconstruction can be partial. Child-context/child-loader delivery, transitive/multiple owner/callback and concurrent-save scope, custom conversion/advice/providers, late attachment, other versions/JVMs/OSs and two-argument security/XML/Boot reconstruction remain outside scope. JMS and Kafka retain their separately committed measured bounds; this does not close every RF14 axis.

**Execution/control:** Main33/0 per graph/JDK (6 watchers,24 native lifecycle/refusal guards,3 optional-stack controls). Historical pre-SS01 research24 desired RED after96 native controls; rename-only frozen observer port, source-identical main caller removal12 behavioral RED on21 and exact restoration12 GREEN. Physical connection close while a real callback remains blocked proves complete-worker waiting beyond native zero counters; ownership-first optional-stack and owned second-processor refusals are separate component controls. JSON binds exact commands/source/artifact hashes and raw XML. Independent Claude audit/full gate/commit pending.

### P4-KAFKA-TRANSACTION-REBALANCE

**Verdict:** UNVERIFIED. Transactional/rebalance/failure delivery guarantees across Kafka reload. 

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. 

**Observation:** No observing test mapped; UNVERIFIED.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. No native Kafka transactional or controlled rebalance/failure observer; configured acknowledgment/offset policy is not a guarantee.

**Execution/control:** No verdict promotion. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-XA-JAKARTA-DURABLE

**Verdict:** UNVERIFIED. XA, Jakarta Messaging3 and durable/shared/topic-provider variants. 

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. 

**Observation:** No observing test mapped; UNVERIFIED.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. javax.jms ActiveMQ Classic local queue/session transaction only; these variants explicitly excluded.

**Execution/control:** No verdict promotion. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-REPLY-ASYNC

**Verdict:** UNVERIFIED. Added Rabbit reply/async return methods. 

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. 

**Observation:** No observing test mapped; UNVERIFIED.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Added Rabbit void callbacks only; return/reply/async variants explicitly excluded, not an offered live capability.

**Execution/control:** No verdict promotion. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-STEREOTYPES

**Verdict:** PROVEN_WITH_SCOPE. Added listeners on plain @Service and @Repository receiver variants.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Normal application loader; plain non-proxied Service/Repository receivers, first added callback beside an original listener; six add/body/route/remove/private-restore/unannotate saves.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#serviceReceiverFollowsBrokerDeliveryAndRepeatedReloads` (watcher): Plain Service receiver: actual BASE/ADDED/OTHER messages across six saves; current generation/prefix/header; retiredRunning=0, unrelated identity retained and native context close allStopped=true. `com.onurkat.reclazz.e2e.AddedKafkaListenerReloadTest#repositoryReceiverFollowsBrokerDeliveryAndRepeatedReloads` (watcher): Plain Repository receiver: actual BASE/ADDED/OTHER messages across six saves; current generation/prefix/header; retiredRunning=0, unrelated identity retained and native context close allStopped=true.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Proxied/composed stereotypes, other classloader shapes and other provider/framework/OS/JVM variants are not certified by these fixtures.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Normal native control consumes real messages; actual file edits observe generation-specific callback output. Bounded200ms duplicates/300ms no-delivery windows do not prove exactly-once. Git-free scratch controls separately corrupt delegation/options. Independent Claude audit pending.

### P4-KAFKA-HEADERS-MAP

**Verdict:** UNVERIFIED. Native @Headers map and all supported callback parameter combinations on a watched addition.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. 

**Observation:** No observing test mapped; UNVERIFIED.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Named watcher observers exercise payload/Header/native message subset, not a complete Headers-map/signature matrix.

**Execution/control:** No verdict promotion. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-PRODUCTION-TIMEOUT

**Verdict:** UNVERIFIED. Exact production 30-second consumer shutdown deadline behavior.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. 

**Observation:** No observing test mapped; UNVERIFIED.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Short internal waits exercise component refusal/retry. No actual full30s controlled production-deadline measurement; timing remains separate evidence.

**Execution/control:** No verdict promotion. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-STEREOTYPES

**Verdict:** PROVEN_WITH_SCOPE. Added listeners on plain @Service and @Repository receiver variants.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. Normal application loader; plain non-proxied Service/Repository receivers, first added callback beside an original listener; six add/body/route/remove/private-restore/unannotate saves.

**Observation:** `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#serviceReceiverFollowsBrokerDeliveryAndRepeatedReloads` (watcher): Plain Service receiver: actual BASE/ADDED/OTHER messages across six saves; current generation/prefix/header; retiredRunning=0, unrelated identity retained and native context close allStopped=true. `com.onurkat.reclazz.e2e.AddedJmsListenerReloadTest#repositoryReceiverFollowsBrokerDeliveryAndRepeatedReloads` (watcher): Plain Repository receiver: actual BASE/ADDED/OTHER messages across six saves; current generation/prefix/header; retiredRunning=0, unrelated identity retained and native context close allStopped=true.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Proxied/composed stereotypes, other classloader shapes and other provider/framework/OS/JVM variants are not certified by these fixtures.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Normal native control consumes real messages; actual file edits observe generation-specific callback output. Bounded200ms duplicates/300ms no-delivery windows do not prove exactly-once. Git-free scratch controls separately corrupt delegation/options. Independent Claude audit pending.

### P4-JMS-HEADERS-MAP

**Verdict:** UNVERIFIED. Native @Headers map and all supported callback parameter combinations on a watched addition.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. 

**Observation:** No observing test mapped; UNVERIFIED.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Named watcher observers exercise payload/Header/native message subset, not a complete Headers-map/signature matrix.

**Execution/control:** No verdict promotion. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-JMS-PRODUCTION-TIMEOUT

**Verdict:** UNVERIFIED. Exact production 30-second consumer shutdown deadline behavior.

**Bounds:** SapMachine17/21, macOS arm64; Spring JMS5.3.39 javax.jms/embedded ActiveMQ Classic5.19.11. 

**Observation:** No observing test mapped; UNVERIFIED.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Short internal waits exercise component refusal/retry. No actual full30s controlled production-deadline measurement; timing remains separate evidence.

**Execution/control:** No verdict promotion. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-STEREOTYPES

**Verdict:** PROVEN_WITH_SCOPE. Added listeners on plain @Service and @Repository receiver variants.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. Normal application loader; plain non-proxied Service/Repository receivers, first added callback beside an original listener; six add/body/route/remove/private-restore/unannotate saves.

**Observation:** `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#serviceReceiverFollowsBrokerDeliveryAndRepeatedReloads` (watcher): Plain Service receiver: actual BASE/ADDED/OTHER messages across six saves; current generation/prefix/header; retiredRunning=0, unrelated identity retained and native context close allStopped=true. `com.onurkat.reclazz.e2e.AddedRabbitListenerReloadTest#repositoryReceiverFollowsBrokerDeliveryAndRepeatedReloads` (watcher): Plain Repository receiver: actual BASE/ADDED/OTHER messages across six saves; current generation/prefix/header; retiredRunning=0, unrelated identity retained and native context close allStopped=true.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Proxied/composed stereotypes, other classloader shapes and other provider/framework/OS/JVM variants are not certified by these fixtures.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Normal native control consumes real messages; actual file edits observe generation-specific callback output. Bounded200ms duplicates/300ms no-delivery windows do not prove exactly-once. Git-free scratch controls separately corrupt delegation/options. Independent Claude audit pending.

### P4-RABBIT-HEADERS-MAP

**Verdict:** UNVERIFIED. Native @Headers map and all supported callback parameter combinations on a watched addition.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. 

**Observation:** No observing test mapped; UNVERIFIED.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Named watcher observers exercise payload/Header/native message subset, not a complete Headers-map/signature matrix.

**Execution/control:** No verdict promotion. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-RABBIT-PRODUCTION-TIMEOUT

**Verdict:** UNVERIFIED. Exact production 30-second consumer shutdown deadline behavior.

**Bounds:** SapMachine17/21, macOS arm64; Spring Rabbit2.4.17/Spring5.3.39/RabbitMQ4.3.5 pinned sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd. 

**Observation:** No observing test mapped; UNVERIFIED.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Short internal waits exercise component refusal/retry. No actual full30s controlled production-deadline measurement; timing remains separate evidence.

**Execution/control:** No verdict promotion. Named assertions observe actual message output or direct component state. Bounded no-duplicate/no-delivery windows do not establish universal exactly-once. Family-level scratch disabled-delegation witnesses are separate. Independent Claude audit pending.

### P4-KAFKA-FACTORY-OPTION

**Verdict:** PROVEN_WITH_SCOPE. Added Kafka explicit containerFactory selection.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Two exact standard ConcurrentKafkaListenerContainerFactory instances; distinct100/125ms poll settings; literal names, normal application loader, four saves.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerOptionsReloadTest#explicitFactoryAndConcurrencyFollowDeliveryReloadAndRetirement` (watcher): Two standard factories have distinct actual container pollTimeout100/125; watched saves switch explicit selected/default/selected factories and concurrency2/1/2; both partitions deliver current-generation output; old routes/removed callback have no output in300ms; retired parent/child containers stop, ordinary native control remains same and delivers; context closes all stopped.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Custom factories/customizers/batch/advice, arbitrary factory options and other environments remain excluded or unverified.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Normal native control consumes real messages; actual file edits observe generation-specific callback output. Bounded200ms duplicates/300ms no-delivery windows do not prove exactly-once. Git-free scratch controls separately corrupt delegation/options. Independent Claude audit pending.

### P4-KAFKA-CONCURRENCY-OPTION

**Verdict:** PROVEN_WITH_SCOPE. Added Kafka positive concurrency option behavior.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Positive literal concurrency2/1/2, two topic partitions; each current child assigned, parent/child old containers stopped; normal loader, four saves.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerOptionsReloadTest#explicitFactoryAndConcurrencyFollowDeliveryReloadAndRetirement` (watcher): Two standard factories have distinct actual container pollTimeout100/125; watched saves switch explicit selected/default/selected factories and concurrency2/1/2; both partitions deliver current-generation output; old routes/removed callback have no output in300ms; retired parent/child containers stop, ordinary native control remains same and delivers; context closes all stopped.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Other concurrency values/topologies, rebalances under injected failures and exactly-once guarantees are not established.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Normal native control consumes real messages; actual file edits observe generation-specific callback output. Bounded200ms duplicates/300ms no-delivery windows do not prove exactly-once. Git-free scratch controls separately corrupt delegation/options. Independent Claude audit pending.

### P4-KAFKA-AUTOSTART-IDGROUP

**Verdict:** PROVEN_WITH_SCOPE. Added Kafka annotation autoStartup and idIsGroup option combinations.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Explicit true/false autoStartup x true/false idIsGroup x true/false native alwaysStartAfterRefresh (8 combinations); omitted groupId with configured factory group; native controls registered after context refresh.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerOptionsReloadTest#lateStartupAndIdGroupMatchNativeControls` (watcher): Eight boolean combinations autoStartup/idIsGroup/alwaysStartAfterRefresh. Added and ordinary late native control have matching running/auto properties and group semantics (own literal ID vs factory group); stopped additions do not deliver for300ms, then native start delivers queued message; both partitions deliver edited callbacks across three additions/saves, removal and native context close.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Only native2.9.13 lifecycle policy and named combinations; not a guarantee that autoStartup=false prevents late startup when alwaysStartAfterRefresh=true.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Normal native control consumes real messages; actual file edits observe generation-specific callback output. Bounded200ms duplicates/300ms no-delivery windows do not prove exactly-once. Git-free scratch controls separately corrupt delegation/options. Independent Claude audit pending.

### P4-KAFKA-NULL-GROUP

**Verdict:** PROVEN_WITH_SCOPE. Native null/default Kafka group expression behavior.

**Bounds:** SapMachine17/21, macOS arm64; Spring Kafka2.9.13/Spring5.3.39/embedded Kafka3.2.3. Omitted groupId, #{null}, explicit nonempty override and exact empty literal, each with true/false idIsGroup; native late-registration controls and actual broker/file-watcher reload.

**Observation:** `com.onurkat.reclazz.e2e.AddedKafkaListenerOptionsReloadTest#lateStartupAndIdGroupMatchNativeControls` (watcher): Eight boolean combinations autoStartup/idIsGroup/alwaysStartAfterRefresh. Added and ordinary late native control have matching running/auto properties and group semantics (own literal ID vs factory group); stopped additions do not deliver for300ms, then native start delivers queued message; both partitions deliver edited callbacks across three additions/saves, removal and native context close. `com.onurkat.reclazz.e2e.AddedKafkaListenerOptionsReloadTest#nullAndDefaultGroupExpressionsMatchNativeControls` (watcher): Four null-expression/explicit override x idIsGroup cases: native control and added group equality to each own ID/factory/override; both partitions deliver after three watched saves, routes move, removal and stopped old containers. `com.onurkat.reclazz.e2e.AddedKafkaListenerOptionsReloadTest#explicitEmptyGroupMatchesNativeAcrossBrokerReloads` (watcher): Two idIsGroup cases: exact empty literal matches native own-ID/factory fallback, both partitions consume generation-specific output after three watched saves, topic moves leave no old-route output in300ms, retired containers stop, removal prevents callback output, native control identity/delivery remains and context closes all stopped.

**Limit:** Only the named fixture shape and assertions. A component test is never file-watcher evidence; no atomic reload or arbitrary advice/provider guarantee. Whitespace-only group literals, other expressions/framework versions/loaders/OS/JVMs are outside this proof. No universal exactly-once or atomic reload guarantee.

**Execution/control:** Both required lanes observed; actual invocations/raw XML/source and artifact hashes in the machine record. Normal native control consumes real messages; actual file edits observe generation-specific callback output. Bounded200ms duplicates/300ms no-delivery windows do not prove exactly-once. Git-free scratch controls separately corrupt delegation/options. Independent Claude audit pending.

## Changed static initializer reset

`resetStatics=true` is an opt-in, default-off path for changed isolated pure
initializers of existing prepared physical statics. See the
[scope and refusal vocabulary](usage.md#changed-static-initializers) and
[machine evidence](feature-evidence/static-initializer-reset.json). Warmed native,
MethodHandle and Reclazz bootstrap readers are tested on stock SapMachine 17/21.
Separate controls cover default-off preservation, constant transitions, unchanged
runtime/null values, failed candidate retention, purity refusals and loader
identity. These tests do not certify arbitrary application code, captured aliases,
general concurrency or live SAP boot; live acceptance remains an independent
Claude audit gate.
