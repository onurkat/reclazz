# Reclazz — Test Guide for SAP Commerce (Hybris)

## Prerequisites

- SAP Commerce (Hybris) 2211 or later
- IntelliJ IDEA 2023.3+ (Ultimate or Community)
- JDK 17 or 21 — any vendor. Structural hot-reload works on standard JVMs
  (SapMachine, OpenJDK, Temurin, Corretto…) via Reclazz's companion-class
  reloader, and on JBR/DCEVM via enhanced redefinition.
- Project configured and `ant all` completed successfully

## 1. Plugin Installation

1. Build the plugin: `./gradlew clean buildPlugin`
2. In IntelliJ: **Settings → Plugins → ⚙️ → Install Plugin from Disk**
3. Select `build/distributions/reclazz-*.zip`
4. Restart IDE

### Verify
- **Settings → Tools → Reclazz** section exists
- Status bar shows `Reclazz: Idle`

## 2. Hybris Project Detection

1. Open your SAP Commerce project in IntelliJ
2. Wait for project indexing to complete

### Verify
- Notification balloon: "SAP Commerce project detected"
- **View → Tool Windows → Reclazz** is available
- Check IDE log (`Help → Show Log in Explorer`) for:
  ```
  Reclazz: Detected Hybris home at /path/to/hybris
  Reclazz: JDK detection — version=17, isJBR=true/false
  ```

## 3. JDK Detection

### Test with Standard OpenJDK (including SapMachine)
1. Set project SDK to OpenJDK / SapMachine / Temurin / Corretto 17 or 21
2. Check Reclazz tool window log

**Expected**: `Detected JDK 21 (SapMachine) — structural hot-reload enabled (companion-class mode)`

The notification also mentions the one caveat of companion mode:
reflective caches (e.g. Hybris `ModelService`) won't see newly added
members until server restart, because `Class.getMethod(...)` on the
original class can't reach the hidden companion nestmate.

### Test with JetBrains Runtime
1. Set project SDK to JBR 17 or 21
2. Check Reclazz tool window log

**Expected**: `Detected JDK 21 (JetBrains Runtime) — structural hot-reload enabled (enhanced redefinition)`

Enhanced redefinition does not have the reflective-visibility caveat
— new members are applied to the original `Class` object directly.

## 4. Agent Injection

1. Enable Reclazz: **Settings → Tools → Reclazz → Enable**
2. Create or edit a Run Configuration for Hybris server
3. Run the server

### Verify
- In the console output, look for:
  ```
  [Reclazz] Agent initialized
  [Reclazz] Hybris home: /path/to/hybris
  [Reclazz] Watching N directories for changes
  [Reclazz] Status server listening on port XXXXX
  ```
- Check `.idea/reclazz/agent.port` file exists
- Run Configuration VM options should contain `-javaagent:.../reclazz-agent.jar=...`

### Verify JVM flags
- OpenJDK: `--add-opens=java.base/java.lang=ALL-UNNAMED` present
- JBR: above + `-XX:+AllowEnhancedClassRedefinition` present

## 5. Plugin-Agent Connection

1. After server is fully started (Spring context loaded)
2. Check status bar widget

### Verify
- Status bar shows `Reclazz: Connected` (may take a few seconds after server starts)
- Tool window shows "Connected to Reclazz agent"

## 6. Hot-Reload: Method Body Change

This tests the most common scenario — changing method logic.

1. Find a simple service class in your custom extension, e.g.:
   ```java
   public class MyService {
       public String getMessage() {
           return "Hello";
       }
   }
   ```
2. Change the return value:
   ```java
   return "Hello World";
   ```
3. Compile the project: **Build → Build Project** (Ctrl+F9)
4. Wait for the `.class` file to be picked up by the file watcher

### Verify
- Tool window shows: `Hot-swapped: com.example.MyService (XXms)`
- Status bar reload count increments: `Reclazz: 1 reloads`
- Calling the service in HAC or via API returns the new value

## 7. Hot-Reload: Spring Bean Refresh

1. Modify a Spring-managed `@Service` or bean defined in XML
2. Change a method body and compile

### Verify
- Tool window shows:
  ```
  Reloaded com.example.MyService (XXms): bean myService re-created
  ```
- New singleton instance is created with updated logic
- Note: existing injected references still point to old instance

## 8. Hot-Reload: Structural Change (any JDK 17+)

Works on any JDK 17+ — standard or enhanced-redefinition.

1. Add a new method or field to an existing class:
   ```java
   private String newField;
   public String getNewMethod() {
       return "new";
   }
   ```
2. Compile

### Verify (any JDK 17+)
- Tool window shows: `Structural reload: com.example.MyClass (XXms)`
- New method/field is callable/readable from other hot-compiled code that
  calls it directly (via `invokedynamic` routed through the companion
  nestmate)

### Reflective-visibility caveat (standard JVMs only)
On standard JVMs (companion-class mode), the new members live on a
hidden nestmate rather than on the original `Class` object. This means:
- `Class.getMethod("getNewMethod")` on the original class still throws
  `NoSuchMethodException` until server restart
- Reflective caches (Hybris `ModelService`, Jackson, Gson, etc.) built
  from the original class at boot time also won't see the new members
- Jalo-layer property access and flexible search (which go through the
  type dictionary rather than Java reflection) do see the new members
  after a HAC `updatesystem` for Hybris items.xml attributes

On JBR/DCEVM with `-XX:+AllowEnhancedClassRedefinition`, the original
`Class` object itself gains the new members, so reflection and
reflective caches work without restart.

## 9. Interceptor Reload

1. Modify an interceptor class (implements `Interceptor<T>`)
2. Compile

### Verify
- Run the integration `InterceptorReloadTest`: it POSTs a unique nonce to
  `/reclazztest/v2/test/interceptor-save`, which calls `modelService.save` on a
  test product and always rolls back its transaction.
- Require HTTP 200 and `validated-v2:reclazz-probe-<nonce>|calls=1`.
  A reload event, stale value, `none`, duplicate invocation or HTTP failure is
  not evidence of success.
- The fixture requires its own transaction and a test-only Commerce environment.
  A portable SDK registry check is also available via `scripts/test-sap-sdk.py`;
  it does not replace the live model-save test.

### Isolated OCC field mapping acceptance

With an installed SAP Commerce SDK, run:

```bash
./gradlew :agent:compileJava
python3 scripts/test-sap-occ-sdk.py /path/to/hybris
```

The runner compiles synthetic DTOs against the local SDK, uses real XML reload,
`DefaultDataMapper` and a Spring cache proxy, and checks BASIC/DEFAULT/FULL field
additions/removals, repeated saves, parent/child visibility, merged declarations,
unrelated contexts/caches and custom-helper restart reporting. It never starts SAP,
contacts a database or writes the SDK. The SDK jars are not distributed.
Add `--disable-refresh` for a negative control in a temporary compiled copy; this
must fail on stale mapping output. This is an isolated SDK check, not live OCC HTTP
acceptance. Use a JDK compatible with the installed SDK.

### Isolated SAP list-directive diagnostics

After `./gradlew :agent:compileJava`, run
`python3 scripts/test-sap-directives-sdk.py /path/to/hybris` with an installed SDK
containing compiled `platformservices/classes`. The synthetic fixture uses real SDK
processors and converter output to verify restart diagnostics, unchanged live
definitions/lists, repeated saves, inherited/subclass/lazy directives, shared targets,
and unrelated XML property updates. Add `--disable-guard` to remove the guard in a
temporary compiled copy: the restart assertion must fail. The runner neither starts
SAP nor writes the SDK; it is not live application acceptance.

### Isolated OCC request mapping acceptance

After `./gradlew :agent:compileJava`, run:

```bash
python3 scripts/test-sap-mappings-sdk.py /path/to/hybris
```

The SDK must contain compiled `commercewebservices` web classes, `commerceservices`
classes and its Spring test/servlet jars. The runner uses the project's cached ASM
9.10.1 and a JDK compatible with the SDK; no new dependency is installed. Synthetic
controllers are redefined in a temporary instrumented JVM around the genuine SDK
`CommerceHandlerMapping`. Real Spring request lookup is compared with fresh SDK
initialization after mapping removal/move, override addition/removal, lower priority,
base fallback and API-version changes, including repeated saves and unrelated routes.

It also checks registration-failure rollback, missing-lock/inspection refusal,
generated-adapter preservation, sticky added-method refusal across saves, independent
context diagnostics, lookup locking during rebuild and ordinary Spring MVC behavior.
Add `--disable-refresh` to compile a temporary copy without the SDK rescan hook;
the removed-winner fallback assertion must fail. SDK jars and sources are not copied
into the project, and the runner does not start or write a SAP application.

The fixture substitutes only configuration-service priority lookup with numeric
synthetic keys. It proves priority selection and registry refresh on Spring 5.3.43
and 6.2.12, not property-file reload, live SAP HTTP acceptance, or custom/programmatic
mapping discovery.

### Isolated dynamic-attribute and CronJob reload acceptance

Run `python3 scripts/test_sap_flows_proof.py` for the portable acceptance predicate.
After `./gradlew :agent:shadowJar`, run:

```bash
python3 scripts/test-sap-flows-sdk.py /path/to/hybris --agent agent/build/libs/agent-1.3.0.jar
```

Use the jar version built by your checkout and a JDK compatible with the SDK. The
runner reads the SDK Spring version instead of upgrading the project's existing
Spring lanes. Genuine SDK dynamic get/set dispatch and job performable results run
under the real agent, with v1 → v2 → added-helper v3 → v2 edits. Each step checks
fresh HTTP behavior on retained and current instances, exact-byte VERIFY receipts,
stable agent session/JVM, and unchanged unrelated behavior. The negative control
`--withhold-edit` must fail; a compiled class never published to the watcher must
not be certified as applied.

Persistence/type resolution is isolated in memory and jobs are invoked directly.
This does not test CronJobService scheduling, stored models, item-type metadata
changes, real tenant startup or cluster behavior. Report a live SAP lane separately
as unrun unless it was actually executed in an authorized test environment.
Cross-check the existing OCC/list-directive/request-mapping runners above, keeping
their support boundaries unchanged. No proprietary jars or private source belong
in the fixture. See [integration acceptance](../integration-test/README.md).

### SAP Solr provider/resolver reload guidance

A successful code reload for a class implementing SAP Commerce
`FieldValueProvider`, `ValueResolver` or `TypeValueResolver` emits a visible INFO advisory:
Reclazz did not reindex stored documents. Validate a subsequent indexing operation
and explicitly reindex affected documents if needed. The code receipt/VERIFY
still describes class bytes only; it is not an indexing receipt. This advice
neither requests a JVM restart nor triggers an index operation.

Recognition follows implemented interfaces through superclass/interface inheritance.
It does not infer index names, document IDs or downstream dependencies of ordinary
helper classes. A class name containing `Solr` or `Provider` is insufficient.
Failure to resolve the loaded hierarchy produces no indexing claim. Absence of a
advisory is not evidence that no index can be affected.

```bash
./gradlew :agent:unitTest --tests '*SapIndexingGuidanceTest' --rerun
./gradlew :agent:e2eTest --tests '*SapIndexingGuidanceTest' --rerun
./gradlew :agent:shadowJar
python3 scripts/test-sap-indexing-sdk.py /path/to/hybris --agent agent/build/libs/agent-1.3.0.jar
# Negative control: must exit nonzero because edited class bytes are withheld.
python3 scripts/test-sap-indexing-sdk.py /path/to/hybris --agent agent/build/libs/agent-1.3.0.jar --withhold-edit
```

The portable tests use synthetic interface identities and a snapshot. The optional
SDK acceptance instead compiles synthetic application classes against installed
SAP interfaces, invokes genuine `FieldValue` / `DefaultSolrInputDocument` paths
with the real agent, and stores immutable document snapshots locally. It checks
method-body edits, a newly added helper method, a revert, same-session exact-byte
receipts, changed newly built documents, unchanged older snapshots until explicit
reindex, and ordinary-class exclusion. The installed SDK stays outside the repo.

This is isolated document-building acceptance, not live Solr or full SAP indexer
acceptance. It starts no tenant, indexer CronJob or Solr server, and writes no live
index. Schema changes, index configuration, cluster distribution, commit/query
visibility and selecting affected documents/indexes remain unverified here.

### Isolated custom-cache characterization

```bash
./gradlew :agent:shadowJar
python3 scripts/test-sap-caches-sdk.py /path/to/hybris --agent agent/build/libs/agent-1.3.0.jar
# Desired-contract probe: currently exits nonzero after proving helper code is live.
python3 scripts/test-sap-caches-sdk.py /path/to/hybris --agent agent/build/libs/agent-1.3.0.jar --require-fresh-helper
# Negative control: unpublished edited bytes must fail the receipt check.
python3 scripts/test-sap-caches-sdk.py /path/to/hybris --agent agent/build/libs/agent-1.3.0.jar --withhold-edit
```

The optional runner uses installed SDK platform jars and synthetic Spring singleton
beans with a genuine Guava cache and an AtomicReference/TTL cache. Observed on
Spring 5.3.43 / Guava 33.1.0-jre and Spring 6.2.12 / Guava 33.4.8-jre:

| Method-body reload | Guava owner | Custom TTL owner | Unrelated caches |
|---|---|---|---|
| Registered, autowired bean dependency | Fresh result through recreated owner | Fresh result through recreated owner | Warm entries retained |
| Cache owner itself | Fresh result when its owner reloads | Fresh result when its owner reloads | Warm entries retained |
| Plain static helper, not a Spring bean | **Old cached result retained** | **Old cached result retained** | Warm entries retained |
| Subsequent explicit owner edit | Fresh result | Fresh result | Warm entries retained |

The helper limitation is deliberate characterization, **not a freshness pass**.
Default success means this entire observed matrix was reproduced; the stricter
`--require-fresh-helper` mode exposes the unsupported case as a failure. Each reload
requires an applied receipt with the exact edited class hash in the same JVM/session.
The helper changes from 1 to 2 and uncached results become 122/222, while warmed
cached results remain 121/221. Receipt success alone does not establish cache freshness.

Both clocks stay fixed through all reloads, before their 60-second expiry; repeated
reads and computation counts establish actual cache hits. An explicit 61-second
clock advance finally proves expiry in both target and unrelated caches. Constructor
counts, managed consumer references and unrelated singleton identity are checked.
No cache hooks or application runtime dependencies were added. Existing Spring
bean refresh covers the tested owner/registered-dependency paths; custom caches
on plain helper dependencies require separate treatment if immediate freshness is
needed. No automatic general invalidation is claimed.

This is isolated Spring/Guava acceptance using SDK libraries. It starts no SAP
tenant and contacts no external service or cluster. Structural edits, external
references to destroyed beans, static/shared caches, FactoryBeans, scoped/prototype
owners and concurrent TTL computation are outside this experiment. The simple
AtomicReference fixture publishes immutable entries under serial test operations;
it is not a concurrency algorithm recommendation.

### SAP process definition diagnostics

Changes to local XML referenced by an existing `ProcessDefinitionResource`
singleton in a captured active Spring context produce a `WARN`: **not applied;
restart required**. This is resource-change guidance, not a successful code reload
or a VERIFY receipt. No process definition is refreshed, and no process is started,
migrated or replayed. Inspect/validate the changed definition before restarting;
restart guidance does not certify that edited XML is valid.

Recognition uses the exact registered local path, including arbitrary XML names.
The watcher polls registered files outside native roots, subject to existing file
and module exclusions. An unregistered `*-process.xml` is not enough evidence.
Closed contexts, remote/packed resources, lazy registrations and FactoryBean
products are not claimed. Existing dedicated kinds such as items/backoffice/logging
configuration keep their meaning; a registered process with a Spring XML suffix
receives process diagnostics instead of being parsed as bean XML.

```bash
./gradlew :agent:unitTest --tests '*SapProcessResourceRecognitionTest' --rerun
./gradlew :agent:e2eTest --tests '*SapProcessDefinitionDiagnosticTest' --rerun
python3 scripts/test-sap-process-sdk.py /path/to/hybris
```

The portable tests use a synthetic API fixture and a real agent/watcher JVM. The
SDK runner independently checks genuine resource registrations against the
installed Spring/SDK jars after `:agent:compileJava`. It does not start a tenant,
parse a business process or exercise a scheduler. These checks are not live SAP
acceptance. Automatic definition refresh requires a separate design: the SDK
factory/cache APIs alone do not establish isolation to newly started processes.

### Isolated SAP event-listener acceptance

```bash
./gradlew :agent:shadowJar
python3 scripts/test-sap-events-sdk.py /path/to/hybris --agent agent/build/libs/agent-1.3.0.jar
# Negative control: an unpublished edit must fail the receipt/behavior check.
python3 scripts/test-sap-events-sdk.py /path/to/hybris --agent agent/build/libs/agent-1.3.0.jar --withhold-edit
```

The runner compiles a synthetic `com.example` subclass of the genuine SDK
`AbstractEventListener` plus a `AbstractEvent` and registers it with a real Spring
context under the built agent. Events are published through Spring's multicaster, so
the SDK's final `onApplicationEvent` cluster and tenant guard runs before `onEvent`;
local stub `ClusterService`/`TenantService` and a locally scoped event make that guard
pass honestly. Editing the listener body and a registered dependency, then reverting,
each requires an applied VERIFY receipt with the exact class hash in one JVM/session,
and is checked for changed event output, exactly one delivery per publish, preserved
listener identity and an unaffected unrelated bean.

This confirms the existing hot-swap already covers SAP event-listener reload; no new
production hook was needed. It is isolated acceptance, not a live run: it starts no
tenant or cluster, sends no external events, and the `afterPropertiesSet` tenant-scope
initialization path is **unverified** here because it needs live SAP infrastructure.

### Isolated ConstraintValidator acceptance

```bash
./gradlew :agent:shadowJar
python3 scripts/test-sap-validators-sdk.py /path/to/hybris --agent agent/build/libs/agent-1.3.0.jar
# Negative control: an unpublished edit must fail the receipt/behavior check.
python3 scripts/test-sap-validators-sdk.py /path/to/hybris --agent agent/build/libs/agent-1.3.0.jar --withhold-edit
```

The runner builds a genuine Hibernate Validator (the installed provider, jakarta
namespace) with a synthetic constraint, a custom `ConstraintValidator` and an injected
dependency, and validates a bean through the real provider under the built agent.
Editing the validator's `isValid` body and the injected dependency, then reverting, each
requires an applied VERIFY receipt with the exact class hash in one JVM/session and is
checked for the changed violation message, exactly one validation per request, and an
`initialize` count that stays at one. A stable init count proves the provider keeps a
single cached validator instance whose method bodies the agent hot-swaps in place, so
the existing `SpringValidatorReloader` metadata flush is not needed for a body or
dependency edit. No new production hook was required.

This is isolated acceptance, not a live run. It distinguishes a validator-instance body
edit (covered) from constraint-metadata changes (handled separately by the metadata
flush) and from structural annotation edits, which stay **unverified** here along with
provider lifecycle beyond instance reuse.

## 10. Extension Watching Scope

### Verify only custom extensions are watched
1. Check tool window startup log for watched directory count
2. Should only include your custom extensions, NOT platform extensions like:
   - `core`, `catalog`, `europe1`, `platformservices`, etc.

### Verify with settings
1. In **Settings → Tools → Reclazz → Watch Extensions**, enter specific extension names
2. Restart server
3. Only the specified extensions should be watched

## 11. Reconnection

1. With server running and plugin connected, stop the server
2. Status bar should show `Reclazz: Disconnected`
3. Start the server again
4. Wait for agent to initialize

### Verify
- Plugin reconnects automatically (with backoff: 1s, 2s, 4s, up to 10s)
- Status bar returns to `Reclazz: Connected`
- Tool window logs the reconnection

## 12. Error Scenarios

### Server not started
- Status bar: `Reclazz: Idle` or `Reclazz: Disconnected`
- No errors in IDE log

### Agent JAR missing
- Check IDE log for warning about agent JAR not found
- Run configuration should NOT have `-javaagent` flag

### Wrong JDK version
- JDK < 17: Agent may not load — check for error in console

### Non-Hybris project
- Plugin should be invisible — no tool window, no status widget activity
- Settings section still available but disabled state

## 13. Settings Persistence

1. Change settings in **Settings → Tools → Reclazz**
2. Close and reopen the project
3. Verify settings are preserved

### Settings to test
| Setting | Default | Test Value |
|---------|---------|------------|
| Enable | false | true |
| Auto Compile | false | true |
| Auto ImpEx | false | true |
| Watch Extensions | (empty) | "myext1,myext2" |
| Debounce (ms) | 500 | 1000 |
| Verbose Logging | false | true |
| Auto-detect JDK | true | false |

## 14. Performance

### File watcher overhead
- Monitor CPU usage after server starts
- File watcher should be idle when no changes occur
- With 30-second startup delay, server boot should not be impacted

### Status socket
- Agent StatusServer binds to loopback (127.0.0.1) only
- Max 5 concurrent client connections
- JSON line events are lightweight (< 200 bytes each)

## Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| Server hangs on startup | Too many watched dirs | Check custom extension filter |
| No console logs after agent | SLF4J conflict | Ensure agent JAR has no `slf4j-simple` |
| `Reclazz: Disconnected` persists | Port mismatch | Delete `.idea/reclazz/agent.port`, restart |
| Hot-swap fails | Schema change on OpenJDK | Use JetBrains Runtime |
| Spring bean not refreshed | Non-singleton bean | Expected — prototypes recreate automatically |
| Agent not injected | Plugin disabled or non-Hybris project | Check Settings → Tools → Reclazz |
