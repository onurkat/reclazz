# Integration tests

The end-to-end suite. It edits real source files in a running SAP
Commerce server, then asks the server over HTTP whether the change took
effect. A reported pass requires the expected application behavior; a compile
or reload event alone is not proof. Portable checks and live SAP checks are
reported separately.

**You need a licensed SAP Commerce installation for the live suite.**
The portable HTTP proof checks run as part of this module's `build` without
SAP. The SDK contract script additionally needs an installed licensed SDK;
it does not start the server. Report these checks separately from a live run.

## What it covers

| Area | Tests |
| --- | --- |
| Class redefinition | method body, add and remove method, add and remove field, change signature, constructor, annotation |
| Spring | bean refresh, MVC re-scan, cache eviction, scheduler, event listener |
| SAP Commerce (live suite) | interceptor model save with rollback, ImpEx auto-import |
| SAP Commerce (isolated SDK + real agent) | dynamic attribute get/set, CronJob performable body/helper reload, exact-byte receipts |
| Optional Hibernate ORM | separate real L2 provider regression; the SAP fixture has no ORM provider and reports SKIP |
| Behaviour under load | multi-class reload, large class, rapid successive changes, syntax error recovery |

## Running it

The suite drives the `reclazztest` extension, which has to be deployed
into your Hybris installation first. See `../reclazztest/README.md`.

### The agent needs two flags

The suite writes `.java` files and waits for the agent to compile them, and one
scenario saves an `.impex` and waits for the import. Neither is on by default,
so the agent has to be started with both:

```
-javaagent:/path/to/reclazz-agent.jar=hybrisHome=...,autoCompile=true,autoImpex=true
```

Without `autoCompile` the very first scenario fails with "Timeout waiting for
COMPILE event", which reads like a broken agent and is a missing flag. Without
`autoImpex` the ImpEx scenario is the only one that fails, which reads like a
defect and is the same thing.

With the server running and Reclazz attached:

```bash
export RECLAZZ_TEST_EXT_PATH=/path/to/hybris/bin/custom/reclazztest
export RECLAZZ_TEST_PORT_FILE=/path/to/project/.idea/reclazz/agent.port

./gradlew :integration-test:run
```

| Variable | Default | |
| --- | --- | --- |
| `RECLAZZ_TEST_EXT_PATH` | required | the deployed `reclazztest` extension |
| `RECLAZZ_TEST_PORT_FILE` | required | where the agent wrote its port |
| `RECLAZZ_TEST_BASE_URL` | `https://localhost:9002` | |
| `RECLAZZ_TEST_MODE` | `companion` | `enhanced` on JetBrains Runtime or DCEVM |
| `RECLAZZ_TEST_EVENT_TIMEOUT` | `30000` | ms to wait for a reload event |
| `RECLAZZ_TEST_SETTLE_DELAY` | ignored | legacy compatibility only; waits use event ordering and HTTP deadlines |
| `RECLAZZ_TEST_ONLY` | all | comma-separated scenario class names, e.g. `ConstructorChangeTest`, to re-run one alone |

## Why `RECLAZZ_TEST_MODE` matters

Structural reload behaves differently by JVM, and the tests assert the
documented semantics of each rather than pretending they are the same.
On a standard JVM (`companion`) new members live on a hidden nestmate:
code that calls them directly works, reflection on the original class
does not. On JetBrains Runtime or DCEVM (`enhanced`) reflection sees
them too. A test that passes in one mode is not evidence about the
other, so run both if you are changing the reload engine.

## Reading a failure

The runner connects to the agent's event stream and waits for the reload
it expects, so a failure tells you whether the reload never happened,
happened but was not applied, or was applied and the assertion about the
result still failed. Those are three different bugs and the report
distinguishes them. The recurring one in practice has been the second:
Reclazz reporting success while doing nothing.


## SAP reliability checks

`InterceptorReloadTest` uses POST `/test/interceptor-save` with a fresh nonce.
The test extension creates a catalog/version/product in a transaction, invokes
`modelService.save`, observes that request's validator version and call count,
and rolls back. Redeploy the updated `ValidationProbe` and
`SapVerificationController` before running it. HTTP failures, old versions,
old nonces, zero calls and duplicate calls fail. Run only in a test environment:
rollback does not undo external side effects from application interceptors.

The previous Hibernate test only returned `dao-v2`; it never populated or read
an L2 cache. It now reports SKIP in this Commerce fixture. SAP Commerce's native
persistence cache and Hibernate Validator are not Hibernate ORM L2 caches.

Without starting SAP or touching its database, use the installed SDK for the
registry/mapping contract and compilation of the model-save fixture:

```bash
./gradlew :agent:compileJava :integration-test:build
python3 scripts/test-sap-sdk.py /path/to/hybris
python3 scripts/test-sap-sdk.py /path/to/hybris --disable-refresh
```

The last command is a negative control and **must fail** with the new target
missing from the registry. The SDK check uses real SAP/Spring classes while
isolating database type resolution and session policy. It does not claim to
execute `modelService.save`. `:integration-test:sapProofTest` separately checks
eight acceptance cases against a real local HTTP server, without a SAP server.

## Isolated dynamic-attribute and CronJob flows

Build the agent, then pass its versioned jar and an installed SDK explicitly:

```bash
./gradlew :agent:shadowJar :integration-test:build
python3 scripts/test_sap_flows_proof.py
python3 scripts/test-sap-flows-sdk.py /path/to/hybris --agent agent/build/libs/agent-1.5.0.jar
```

The runner compiles synthetic `com.example` fixtures in a temporary directory and
starts its own local HTTP application with the real agent. It uses the SDK's Spring
jars, `DefaultDynamicAttributesProvider`, `DynamicAttributeHandler`,
`AbstractJobPerformable`, `CronJobModel` and `PerformResult`. Body edits, an added
private helper, and a return to the earlier body must change both retained-instance
and current-bean behavior. Dynamic get/set output, job result/status and invocation
counts are checked using fresh nonces, in the same JVM, with unrelated behavior
preserved. Each change also requires an `applied` VERIFY receipt matching the
request token, class, input SHA-256 and agent session. Stale hashes must return
`mismatch`; a receipt alone cannot pass the behavioral check.

Add `--withhold-edit` as a negative control: edited bytes stay outside the watched
directory, so acceptance must fail. `test_sap_flows_proof.py` separately verifies
that mismatched receipts, stale results, wrong nonces/PIDs/versions, incomplete
invocation counts and failed HTTP responses cannot pass.

This is **isolated SDK acceptance**, not a live SAP run. Model persistence/type
resolution is an explicit in-memory proxy boundary; job execution directly invokes
the genuine performable contract. It does not install item types, persist models,
call CronJobService, start a tenant/scheduler or prove cluster/scheduled execution.
SDK jars remain local and are not distributed. No private installation is changed.

Existing OCC/converter coverage is reused through `test-sap-occ-sdk.py`,
`test-sap-directives-sdk.py` and `test-sap-mappings-sdk.py`. These prove field-set
refresh, list-directive restart diagnostics and bounded request mapping selection
respectively; they do not establish automatic converter-list reconstruction or
support for synthetic added OCC endpoints. See [the test guide](../docs/test-guide.md).


## Runner ordering and restoration

Run from this repository's root. Before the first scenario the runner reads the
ten committed `reclazztest` baseline files from HEAD. It writes only files whose
bytes differ. Each changed Java source requires its own COMPILE followed by its
exact fully qualified owner's RELOAD or STRUCTURAL_RELOAD after the write cursor.
All sources in a multi-class edit must finish. Events for another class, its
inner classes, or text in framework effects cannot satisfy the wait. Clearing
the event list does not reset its sequence. A quiet period is not a completion
signal.

HTTP checks poll to the event-timeout deadline and require both the expected
status and body (or the scenario's existing body predicate). The failure includes
the last status/body; each request shares the remaining deadline. Cache-condition
checks still require two different responses with caching disabled. The interceptor
model-save proof remains a single POST with a fresh nonce after its correlated
reload: rollback does not undo arbitrary external side effects, so that POST is
not retried.

ImpEx additionally establishes HTTP 200 and the v1 test title before requesting
v2. A stale v2 database value cannot pass. If the file is already at baseline but
the database is not, a comment-only variant requests the v1 data again; the runner
waits for the file's existing terminal import outcome and actual v1 visibility,
then restores committed bytes and waits again. Each subsequent save has its own
cursor and terminal outcome; refusal/error outcomes fail explicitly. After the
scenario it restores and verifies v1. This uses the existing socket protocol,
not a new operation receipt; it serializes this runner's own resource writes and
does not coordinate arbitrary external editors.

A final block restores all touched baseline files even after a scenario failure
or exception. Byte differences from HEAD or failed reload/import restoration fail
the run. Do not edit the fixture concurrently with a live run. The fixture's
synthetic Title row is changed during ImpEx testing and restored to v1; record its
initial value when running against an existing local test installation. The
runner must not be used against customer data or a production server.

### Portable harness contract

```bash
./gradlew :integration-test:harnessContractTest --rerun
./gradlew :integration-test:sapProofTest --rerun
./gradlew :integration-test:build
```

The harness target executes thirteen named checks (the twelve planned cases plus a batch-message regression) against a fake agent socket and
real loopback HTTP. It starts actual runner child JVMs over temporary synthetic
fixture copies for restoration, split batches, stale responses, ImpEx and failure
cleanup; it never starts SAP or edits the deployed extension. Its baseline
compatibility adapter calls the old client's actual methods when cursor-aware
methods are absent, allowing the same tests to demonstrate the original defects.
The eight existing model-save HTTP proof checks remain separate. Neither target
establishes live SAP determinism: record five identical full runs on one boot and
one after a fresh boot separately. New SAP scenario families require their own
coverage/feasibility review.


## Serialized beans codegen profile

The default suite retains all 44 committed scenarios, including Orika rollback.
The slow profile is opt-in and cannot be combined with `RECLAZZ_TEST_ONLY`:

```bash
export RECLAZZ_TEST_PROFILE=codegen
export RECLAZZ_TEST_HYBRIS_HOME=/path/to/isolated/hybris
./gradlew :integration-test:run --console=plain
```

Deploy the updated synthetic gate controller first. Preflight reads effective
server configuration and the running JVM's matching Reclazz agent launch options
as typed booleans; it never exposes raw arguments. Missing/disabled/mismatched
prerequisites report SKIP before any baseline restoration or file write.
The profile needs the normal option-off boot, `autoCompile=true`, its deployed
fixture, platform ant and existing generated DTO sources/classes. Run it only
on the owned test installation, with no concurrent ant, test or fixture editor.

It adds `batchValue` to both DTO definitions in the fixture beans XML, waits
for the exact extension's ant result and both generated-class apply receipts,
then compiles a typed setter call and updates the explicit FULL field mapping.
PASS requires repeated native OCC FULL responses with the new value and the
original controls; BASIC must still exclude it. Ant success alone cannot pass.
The forward phase and cleanup each have up to six minutes within a twelve-minute
profile deadline. A failure after preflight is FAIL, including failed cleanup.

Cleanup restores controller and field mapping before removing the generated
property, waits for codegen/apply again, verifies baseline OCC and exact fixture
bytes, and checks regenerated source shape against baseline (only SAP's generated
header timestamp is ignored) and absence of the property in compiled output.
Generated files are regenerated by SAP; vendor output is not copied into this
repository. Run three consecutive profile passes, then unset the profile and
verify the normal 44-case suite (43 PASS plus the known Hibernate-provider SKIP).
Items schema, static reset, Solr, CMS and Chrome remain separate profiles and are
not covered by the beans profile.

### Items schema profile (disposable clean HSQLDB only)

The serialized entry point is `python3 scripts/test-sap-items-live.py`. It runs
three complete schema attempts and recoveries, followed by the unchanged
44-case default suite. Add `--failure-control` to exercise an additional
intentional failure after a real schema update and persisted round trip.
`--runs 1` selects one full cycle for diagnosis. An inner `items-attempt` result
alone is **not** a completed profile: only `ITEMS_PROFILE PASS` from the outer
driver includes database recovery and normal-boot acceptance.

This profile is pinned to the separate `reclazz-sap/hybris` installation beside
the repository, HTTPS9102, file HSQLDB, and the repository fixture symlink.
It refuses any foreign tenant database. Normal agent options must have
`autoCompile=true`, `autoUpdateRunningSystem=false`, `resetStatics=false`,
`autoSolrReindex=false`, and `impexAllowRemove=false`. Missing or mismatched
running-server gates SKIP before fixture/config/database writes.

Before first use, deploy the fixture's new **synthetic** `ReclazzSchemaProbe`
baseline (nonce only, table `ReclazzSchemaProbes`, type code 31991). Check native
loaded types for code/name/table collisions first and take a complete stopped
HSQLDB backup. Build the fixture and apply the platform's schema-only running
system update on this disposable installation, with all data/localization/drop
steps off. No initialization is needed. The driver SKIPs until this baseline
exists with zero rows and without `batchValue`; it never bootstraps an unknown
installation itself. The normal master and junit tenants must already be
initialized for the default suite.

Each cycle stops the server, waits for the JVM/native ant writers and DB lock
to disappear, copies the **entire** HSQLDB directory (all tenant prefixes), and
verifies every file's size/hash. It also snapshots generated model/source
outputs and saves the original configuration and agent hash. The schema boot
adds only `autoUpdateRunningSystem=true` to the exact owned agent entry in
the temporary launcher wrapper; local.properties remains unchanged. The
original launcher bytes are restored before the normal boot. A responding
server with mismatched options fails immediately. The attempt adds one persistent
String attribute, requires exact items-file/codegen/generated-owner/schema
outcomes, and verifies the descriptor, distinct detached model instances, and
an independent-request FlexibleSearch value from the saved PK.

Every exit after capture stops the server, restores the original fixture,
configuration, generated outputs and full DB directory, removes extra DB
files, verifies hashes **before** opening it, then checks a fresh normal JVM,
baseline metadata/zero rows, both tenant health queries and fixture bytes.
There is no inverse DDL. Failed recovery fails the whole profile; an open DB
or invalid snapshot is never replaced. The recovery folder also retains `state.json`: original configuration and
items source bytes as hex, their SHA-256 checksums, the ordered generated
output targets, and the original agent hash. These survive loss of the test
process. For manual recovery, stop and prove the writer/lock checks first;
restore the recorded configuration/source bytes, restore each numbered
generated snapshot to its recorded target, then restore the entire verified
`db` snapshot with `sap_items_snapshot.restore`. Verify hashes before starting
the normal server, then verify baseline metadata, zero rows, tenant health
and the default suite. Never restore an open database.

Recovery folders and logs remain under
`/private/tmp/reclazz-items-*`, including on failure, for diagnosis and manual
recovery. Do not delete the recovery folder until acceptance is complete.

Budgets: native build/startup 600s each, stop 90s followed by a bounded 330s writer
drain, inner schema attempt 600s, runner 900s. A native command exceeding its
budget is preserved and recovery refuses to proceed while its writer remains.
Portable checks: `python3 scripts/test-sap-items-snapshot.py`,
`python3 scripts/test-sap-items-driver.py`, and the Gradle harness contracts.

### Serialized static-initializer boot profile

Run `python3 scripts/test-sap-statics-live.py` on the same clean installation.
It runs three complete cycles; each has a fresh normal-option boot and a fresh
`resetStatics=true` boot, followed by stopped recovery to the normal option.
`--failure-control` prepends a cycle whose enabled attempt deliberately fails
after the reload/refusal checks and runtime mutation; recovery must still pass.
`--runs 1` is a diagnostic full cycle. Only `STATICS_PROFILE PASS` includes both
lanes, recovery and the final default suite (44 registered, 43 PASS + Hibernate
SKIP). Internal `statics-on`/`statics-off` results alone are not acceptance.

Read-only preflight uses the running JVM's typed gates and effective config,
requires the owned clean fixture and its immutable source baselines, and
SKIPs before any test write if unavailable/mismatched. After the outer driver
has written recovery files or changed the server, a failed/skipped attempt is
a failure. The driver builds the fixture while stopped before each fresh JVM;
it adds only `resetStatics=true` to the exact owned wrapper agent argument.
No late attachment or option change on an already-loaded holder is used.

`StaticProbe` exposes a supported static final Map literal, a nonconstant-to-
constant refusal, an application-call refusal, an initializer invocation count,
and unchanged runtime nonce/identity state. A controller warms both an ordinary
Java read and a retained MethodHandle getter before each edit. The lane requires
the exact source COMPILE/owner reload plus fresh exact per-field reset/advice
outcomes, checks both readers and body convergence, and verifies that unrelated
state and refused values stay unchanged. It restores the live baseline source
and observes the rollback; the outer driver still recovers on every exit.

The outer recovery folder `/private/tmp/reclazz-statics-*` retains original
source/config bytes as hex, fixture hashes, agent hash, native logs and per-lane
results. Recovery refuses while an inner runner survives, stops/drains native
writers, restores the saved source and launcher/config bytes, rebuilds, verifies
all fixture/config hashes and the unchanged agent, then proves a fresh normal
JVM with startup values and master/junit health. This profile changes no database
records/schema and needs no inverse DDL/database restoration. If manual recovery
is needed, prove writers stopped first, restore the `recovery.json` originals,
run native `ant build`, verify originals/hashes, boot with the original normal
wrapper, then verify baseline/health/default44. Keep the private recovery folder
until independent acceptance is complete. Timeouts: 600s build/start, 90s stop
plus 330s writer drain, 180s inner lane, 900s runner. Timed-out writers are
preserved and recovery refuses to race them.

Portable checks: `python3 scripts/test-sap-statics-driver.py` and the Gradle
harness contracts. Deferred field-initializer concurrency is outside this profile.
