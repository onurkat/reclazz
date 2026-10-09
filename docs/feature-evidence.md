# Core JVM feature evidence

This is the first checkpoint of the all-advertised-features evidence program. It classifies the listed core promises; it does not certify every feature, framework, program, JDK or operating system. Other evidence packages remain separate.

Records distinguish **PROVEN_WITH_SCOPE** (observed watcher behavior on both named lanes), **PARTIAL** (component observations only), **EXPECTED** (observed limitation/refusal), **FAILED** (a capability fails despite a passing characterization test) and **UNVERIFIED** (missing run/environment). Independent Claude review is pending until the package audit is archived.

The available lanes are stock SapMachine17/21 on macOS arm64. Other provider/OS/enhanced cells remain unverified. Deferred field-initializer concurrency stays deferred. Raw logs/XML are retained locally at the paths in the JSON; the JSON contains method results and provenance, not a substitute claim that every green receipt establishes behavior.

Reproduce with the repository tasks `:agent:unitTest --rerun` and `:agent:e2eTest --rerun` on each named runtime, then check `P1RegistryCompletenessTest`. The check rejects omitted cells, stale public surfaces, nonexistent test methods, component-as-watcher promotion and unrun-as-proven promotion. It does not automatically decide the meaning of arbitrary prose.

The [machine record](feature-evidence/p1-core.json) includes actual execution commands, runtime/agent hashes, test-source hashes, result counts and raw evidence locations. This document’s digest lives in that separate record to avoid a self-referential hash.

### P1-BODY-SUPER

**Verdict:** PROVEN_WITH_SCOPE.

Edited overrides continue dispatching super calls to the current original parent.

**Shipped surfaces:** `README.md:76` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:76` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:78` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `docs/usage.md:3124` (SHA-256 `20b3a9a145669205bd2885064566603f38f719f3a3789977598a6a5432744c27`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:78` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `docs/usage.md:3124` (SHA-256 `20b3a9a145669205bd2885064566603f38f719f3a3789977598a6a5432744c27`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:78` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `docs/usage.md:3124` (SHA-256 `20b3a9a145669205bd2885064566603f38f719f3a3789977598a6a5432744c27`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:78` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `docs/usage.md:3124` (SHA-256 `20b3a9a145669205bd2885064566603f38f719f3a3789977598a6a5432744c27`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:78` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `docs/usage.md:3124` (SHA-256 `20b3a9a145669205bd2885064566603f38f719f3a3789977598a6a5432744c27`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:78` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `docs/usage.md:3124` (SHA-256 `20b3a9a145669205bd2885064566603f38f719f3a3789977598a6a5432744c27`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:78` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `docs/usage.md:3124` (SHA-256 `20b3a9a145669205bd2885064566603f38f719f3a3789977598a6a5432744c27`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:78` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `docs/usage.md:3124` (SHA-256 `20b3a9a145669205bd2885064566603f38f719f3a3789977598a6a5432744c27`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:79` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `docs/usage.md:3091` (SHA-256 `20b3a9a145669205bd2885064566603f38f719f3a3789977598a6a5432744c27`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:79` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `docs/usage.md:3091` (SHA-256 `20b3a9a145669205bd2885064566603f38f719f3a3789977598a6a5432744c27`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:79` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `docs/usage.md:3091` (SHA-256 `20b3a9a145669205bd2885064566603f38f719f3a3789977598a6a5432744c27`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:79` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `docs/usage.md:3091` (SHA-256 `20b3a9a145669205bd2885064566603f38f719f3a3789977598a6a5432744c27`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:77` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `src/main/resources/META-INF/plugin.xml:68` (SHA-256 `2c2fd1b22dfa98debd2f53271c8fa3c43d72febf9479e14b84c7249df440acbe`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:77` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `src/main/resources/META-INF/plugin.xml:68` (SHA-256 `2c2fd1b22dfa98debd2f53271c8fa3c43d72febf9479e14b84c7249df440acbe`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:77` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `src/main/resources/META-INF/plugin.xml:68` (SHA-256 `2c2fd1b22dfa98debd2f53271c8fa3c43d72febf9479e14b84c7249df440acbe`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:77` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `src/main/resources/META-INF/plugin.xml:68` (SHA-256 `2c2fd1b22dfa98debd2f53271c8fa3c43d72febf9479e14b84c7249df440acbe`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:77` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `src/main/resources/META-INF/plugin.xml:68` (SHA-256 `2c2fd1b22dfa98debd2f53271c8fa3c43d72febf9479e14b84c7249df440acbe`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/usage.md:3064` (SHA-256 `20b3a9a145669205bd2885064566603f38f719f3a3789977598a6a5432744c27`); `README.md:693` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `CHANGELOG.md:11` (SHA-256 `a45409e74227f8bed020950d7306327b0558792356d8181fe37df5c77719319c`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:118` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:118` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:81` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:81` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:81` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:82` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:83` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:81` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:84` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:84` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:85` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `src/main/resources/META-INF/plugin.xml:68` (SHA-256 `2c2fd1b22dfa98debd2f53271c8fa3c43d72febf9479e14b84c7249df440acbe`); this block (digest in JSON).

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

**Shipped surfaces:** `docs/usage.md:3176` (SHA-256 `20b3a9a145669205bd2885064566603f38f719f3a3789977598a6a5432744c27`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:79` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `docs/usage.md:3091` (SHA-256 `20b3a9a145669205bd2885064566603f38f719f3a3789977598a6a5432744c27`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:80` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:79` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `docs/usage.md:3091` (SHA-256 `20b3a9a145669205bd2885064566603f38f719f3a3789977598a6a5432744c27`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:745` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:767` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); `src/main/resources/META-INF/plugin.xml:84` (SHA-256 `2c2fd1b22dfa98debd2f53271c8fa3c43d72febf9479e14b84c7249df440acbe`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:773` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:773` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:751` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:820` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:658` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); this block (digest in JSON).

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

**Shipped surfaces:** `README.md:81` (SHA-256 `0b397b585537801242d93515f9abbe769369ffafb9168f088f6bc91323314e84`); this block (digest in JSON).

**Default/opt-in:** Default companion reload after explicit agent attachment and class compilation.

**Bounds:** No observing acceptance method mapped or executed for this variant. The named SapMachine lanes establish no behavior for this record; the missing environment and limitation below govern it.

**Tests/assertions:** No observing method mapped; remains unverified.

**Observable:** No observing test executed for this current advertised promise.

**Control:** No executed observing control for this record.

**Execution:** UNRUN for this claim; suite-level results do not prove this unmapped variant. Exact commands, exit/counts/skips, runtime, source/artifact hashes and raw paths are in the machine record.

**Limitation / missing environment:** JDK26 is not installed/validated for this package; no inference from JDK17/21 or source/API names. Other OS/providers/JDK releases, enhanced JBR/DCEVM and unlisted object/loader shapes; no acceptance assumed.

**Reviewer / next action:** Astra mapped/read assertions; Claude independent evidence audit pending. Obtain the named runtime or a separately scoped observing test before claiming support.

## P2 — Ordinary Spring

This package maps current registration, recreation, injection, factory, lifecycle, transaction, cache, AOP and method-security promises to actual observing methods. It does not certify all Spring versions or shapes. P3–P8 and deferred/excluded work retain their separate owner decisions.

[Machine registry](feature-evidence/p2-ordinary-spring.json) and [cell manifest](feature-evidence/p2-cells.txt) contain source/surface hashes, method receipts, runtime/agent hashes, counts and raw local evidence. `P2RegistryCompletenessTest` rejects omissions, drift, false promotions and nonexistent methods, including inherited Spring 6 observers without loading Spring 6 into Spring 5. `:agent:unitTest` compiles that isolated test source set first.

### P2-BEAN-REGISTER-NEW

**Verdict:** PROVEN_WITH_SCOPE.

A new direct component becomes a live bean without restarting.

**Shipped surfaces:** `README.md:112`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:112`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:112`; this block. Digests in JSON.

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

**Shipped surfaces:** `docs/usage.md:380`; this block. Digests in JSON.

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

**Shipped surfaces:** `docs/usage.md:383`; this block. Digests in JSON.

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

**Shipped surfaces:** `docs/usage.md:376`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:112`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:86`; this block. Digests in JSON.

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

**Shipped surfaces:** `src/main/resources/META-INF/plugin.xml:47`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:88`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:88`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:88`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:87`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:87`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:87`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:87`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:87`; this block. Digests in JSON.

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

**Shipped surfaces:** `docs/usage.md:512`; this block. Digests in JSON.

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

**Shipped surfaces:** `docs/usage.md:519`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:87`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:87`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:87`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:87`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:87`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:87`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:87`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:87`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:87`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:87`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:87`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:87`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:87`; this block. Digests in JSON.

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

**Shipped surfaces:** `docs/usage.md:570`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:87`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:89`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:89`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:89`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:97`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:97`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:97`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:97`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:97`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:97`; this block. Digests in JSON.

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

**Shipped surfaces:** `docs/usage.md:1946`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:97`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:97`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:99`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:98`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:98`; this block. Digests in JSON.

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Same named Spring5/Security5 and Spring6/Security6 lanes as P2-SECURITY-PRE-POST; nested fixed markers, not runtime marker-definition edits.

**Tests/assertions:** `com.onurkat.reclazz.e2e.ComposedMethodSecurityReloadTest#heldNativeSecurityProxyFollowsPolicyBodyRemovalAndRestore` (watcher): Native pre/post authorization permits/denies real calls with exact body counts 1,2,4,5,5,6; unauthenticated denied, return policy enforced; body/policy/removal/restoration, same held proxy and native original control.; `com.onurkat.reclazz.e2e.ModernComposedMethodSecurityReloadTest#heldNativeSecurityProxyFollowsPolicyBodyRemovalAndRestore` (watcher): Native pre/post authorization permits/denies real calls with exact body counts 1,2,4,5,5,6; unauthenticated denied, return policy enforced; body/policy/removal/restoration, same held proxy and native original control.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Only listed assertions; no inference to unlisted annotation, proxy, context or dependency shapes. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-SECURITY-METADATA

**Verdict:** PARTIAL.

Resolved method-security cache entries are refilled from fresh Method objects.

**Shipped surfaces:** `src/main/resources/META-INF/plugin.xml:52`; this block. Digests in JSON.

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringSecurityMethodMetadataTest#anAskableRegistryIsRefilledFromFreshMethods` (component): Synthetic native-shaped registry stale guarded entry becomes fresh:guarded after direct cache refresh.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Synthetic cache-shape component test; real pre/post effect is measured separately, Secured/roles variants not implied. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-SECURITY-SECURED

**Verdict:** UNVERIFIED.

Edited Secured policy is enforced on real calls.

**Shipped surfaces:** `src/main/resources/META-INF/plugin.xml:52`; this block. Digests in JSON.

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** No observing method assigned; not promoted from a located filename.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** UNVERIFIED: no observing acceptance assigned on the required lanes.

**Limitation / missing environment:** Real pre/post watcher does not execute Secured/JSR250 role changes; no observing P2 receipt assigned. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. A separate bounded acceptance run is needed for this cell before promotion; do not infer it from adjacent green tests.

### P2-SECURITY-FILTER-CHAIN

**Verdict:** UNVERIFIED.

Edited web security configuration rebuilds a live filter chain and changes route authorization.

**Shipped surfaces:** `src/main/resources/META-INF/plugin.xml:52`; this block. Digests in JSON.

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** No observing method assigned; not promoted from a located filename.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** UNVERIFIED: no observing acceptance assigned on the required lanes.

**Limitation / missing environment:** P2 native method-security fixture has no servlet filter-chain request. No live HTTP filter-chain proof assigned in this package. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. A separate bounded acceptance run is needed for this cell before promotion; do not infer it from adjacent green tests.

### P2-CACHE-RELOAD-EVICTION

**Verdict:** PARTIAL.

Reloading an annotated class evicts existing native caches.

**Shipped surfaces:** `README.md:102`; this block. Digests in JSON.

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringCacheReloaderTest#annotatedUnknownClassKeepsExistingFallback` (component): Direct cache reloader empties prices/descriptions after warming both.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Direct reloader invocation only; added CacheEvict operation is a separate watcher cell. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-SPRING6-ORDINARY-MATRIX

**Verdict:** UNVERIFIED.

Ordinary registration/factories/injection/AOP watcher behavior is established on Spring 6 too.

**Shipped surfaces:** `src/main/resources/META-INF/plugin.xml:27`; this block. Digests in JSON.

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** No observing method assigned; not promoted from a located filename.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** UNVERIFIED: no observing acceptance assigned on the required lanes.

**Limitation / missing environment:** Spring6 watcher receipts here cover Jakarta lifecycle and native/composed pre-post security. They do not certify ordinary registration/factory/injection/AOP watchers or Boot3. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. A separate bounded acceptance run is needed for this cell before promotion; do not infer it from adjacent green tests.

### P2-EXISTING-TX-METADATA

**Verdict:** PARTIAL.

Edited original-method Transactional metadata is re-read.

**Shipped surfaces:** `README.md:113`; this block. Digests in JSON.

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringOperationSourceReloaderTest#theTransactionSourcesMapIsFoundByItsName` (component): Direct source refresh clears a synthetic warmed attributeCache map.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Synthetic cache-finder observation only; not a live original-method readOnly/rollback save. Added hidden methods are separately measured. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-EXISTING-CACHE-METADATA

**Verdict:** PARTIAL.

Edited original-method Cacheable metadata is re-read.

**Shipped surfaces:** `README.md:113`; this block. Digests in JSON.

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringOperationSourceReloaderTest#theCacheSourcesRenamedMapIsFoundToo` (component): Direct source refresh clears a synthetic warmed operationCache map.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Synthetic cache-finder observation only; not a live original-method cache-condition save. Added hidden methods are separately measured. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-TX-CLASS-DEFAULTS

**Verdict:** PARTIAL.

Added operations honor class-level transaction defaults.

**Shipped surfaces:** `README.md:97`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:97`; this block. Digests in JSON.

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

**Shipped surfaces:** `README.md:114`; this block. Digests in JSON.

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Stock SapMachine 17/21, macOS arm64, Spring 5.3.39. Only the named synthetic fixture shapes and loaders, not every Spring application.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringSecurityReloaderSwapTest#aStaleChainIsReplacedByTheRebuiltBean` (component): Direct duck-typed proxy swap replaces the stale fake chain with the rebuilt fake chain identity.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** Synthetic chain/proxy shapes only; no live servlet request/401 observation. P2-SECURITY-FILTER-CHAIN remains unverified. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

### P2-SPRING6-BEAN-CONDITION-METADATA

**Verdict:** PARTIAL.

Spring 6 added factory conditions match native startup and saved metadata.

**Shipped surfaces:** `docs/usage.md:405`; this block. Digests in JSON.

**Default/opt-in:** Default after explicit startup agent attachment and compiled class save; no additional P2 flag.

**Bounds:** Isolated Spring 6.1.14 on stock SapMachine17/21 macOS arm64. Direct adapter invocation, not watcher.

**Tests/assertions:** `com.onurkat.reclazz.spring.SpringSixAddedBeanConditionsTest#directConditionsMatchNativeStartupAndReceiveRealMethodMetadata` (component): Direct reload compares off/on admission and alias presence to native startup; condition called once in registration phase.

**Control:** Before/after saves and the unchanged/held-object controls named in the observing tests; hook-disabled scratch controls where recorded.

**Execution:** PASSED on both named SapMachine17/21 macOS arm64 lanes; exact per-method framework, command, counts/skips and source/artifact hashes in JSON.

**Limitation / missing environment:** No general Spring 6 registration/factory watcher or Boot 3 proof inferred. Other OS/JDK providers/enhanced VMs; Spring 6 watcher coverage unless explicitly named in this cell.

**Reviewer / next action:** Astra read/mapped assertions; Claude independent audit pending. Independent Claude audit of the record, complete diff, raw receipts and scratch negative witnesses before local commit.

## P3a — Adversarial HTTP validation and overlapping requests

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

## P3b — Remaining MVC request/response evidence

This package classifies the measured MVC slice and explicitly records missing variants. It does not complete P3c callbacks/data or every repository promise. Native Tomcat HTTP, HTTP-to-MockMvc bridges, direct ObjectMapper HTTP and component-only observations are distinct. Passing limitation characterizations do not mean feature success. Scratch negative witnesses run on SapMachine21 only. Whole-hook negative witnesses stop at the first failing assertion; they are not separate mutations for every later assertion or every component-only row. [Machine evidence](feature-evidence/p3b-mvc-web.json) contains the exact methods, invocation receipts, runtime/artifact/source hashes and controls. The interceptor gap remains a restart limitation; Spring6.1.14 added exception handlers are refused.

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

**Controls / remaining limits:** Warm fallback; other controller still400, existing local still409. Disable exception transformer: whole observer RED. Spring6.1.14 refuses the hook (separate FAILED cell). Spring5 native servlet deployment unverified. Exact metadata-class identity, proxies and extra advice outside scope. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-EXCEPTION-ADVICE

**Verdict:** PROVEN_WITH_SCOPE. Added controller-advice exception handler changes HTTP status/body, then edit/remove/restore/unannotate changes selection.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Spring5.3.39; normal/child loaders; loopback HttpServer -> MockMvc/DispatcherServlet (not native Tomcat). Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.e2e.AddedExceptionHandlerReloadTest#addedAdviceHandlerKeepsSelectorsLocalPriorityAndFollowsSaves` (watcher); `com.onurkat.reclazz.e2e.AddedExceptionHandlerReloadTest#adviceHandlersOnAddedEndpointsWorkAcrossChildLoaderSaves` (watcher)

**Controls / remaining limits:** Warm fallback; other controller still400, existing local still409. Disable exception transformer: whole observer RED. Spring6.1.14 refuses the hook (separate FAILED cell). Spring5 native servlet deployment unverified. Exact metadata-class identity, proxies and extra advice outside scope. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

### P3B-EXCEPTION-MODERN

**Verdict:** FAILED. Spring6.1.14 refuses the added-handler hook; newly added handler cannot replace the existing fallback.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Spring6.1.14; normal/child loaders; loopback HTTP -> MockMvc/DispatcherServlet. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.e2e.JakartaAddedExceptionHandlerLimitationTest#localAddedHandlerIsRefusedAndExistingFallbackRemains` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedExceptionHandlerLimitationTest#adviceAddedHandlerIsRefusedAndSelectorsRemain` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedExceptionHandlerLimitationTest#localRefusalPersistsAcrossChildLoaderSaves` (watcher); `com.onurkat.reclazz.e2e.JakartaAddedExceptionHandlerLimitationTest#adviceRefusalPersistsAcrossChildLoaderSaves` (watcher)

**Controls / remaining limits:** Exact resolver diagnostic1/2/1; unchanged native fallback effects. Scratch desired422 response must RED; GREEN characterization is not feature success. A version-specific support gap, not a repair. Supported Spring5 rows remain separate. Native Tomcat exception-handler deployment not measured. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

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

**Verdict:** EXPECTED. Registered interceptor keeps old constructor-owned dependency state despite native parent bean recreation.

**Bounds / opt-in:** SapMachine17/21, macOS arm64. Spring5.3.39/Spring6.1.14; normal/child, parent/web contexts, lookup/mapped/injected registration; file-triggered MockMvc requests, no TCP or deployed servlet container. Startup -javaagent before the framework loads; publish compiled watched classes.

**Observers:** `com.onurkat.reclazz.e2e.MvcInterceptorLimitationReloadTest#requestsKeepOldConstructorStateWhileParentIsRecreated` (watcher); `com.onurkat.reclazz.e2e.JakartaMvcInterceptorLimitationReloadTest#requestsKeepOldConstructorStateWhileParentIsRecreated` (watcher)

**Controls / remaining limits:** Current parentHn:Dn differs from dispatchedHn:D1; injected registration recreates context mapping while servlet retains original. Scratch desiredDn and reconciled mapping must RED. Known restart limitation. These GREEN characterization tests prove the gap, not interceptor lifecycle repair. Filters/security/proxies/concurrent requests/container deployment unverified. Other OS/providers, enhanced JVMs, late attach and unlisted framework versions are unverified.

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
