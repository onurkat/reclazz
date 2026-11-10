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

The default suite retains all 46 committed scenarios, including Orika rollback.
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
verify the normal 46-case suite (45 PASS plus the known Hibernate-provider SKIP).
Items schema, static reset, Solr, CMS and Chrome remain separate profiles and are
not covered by the beans profile.

### Items schema profile (disposable clean HSQLDB only)

The serialized entry point is `python3 scripts/test-sap-items-live.py`. It runs
three complete schema attempts and recoveries, followed by the unchanged
46-case default suite. Add `--failure-control` to exercise an additional
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
baseline (nonce plus the cold-installed dynamicLabel attribute, table `ReclazzSchemaProbes`, type code 31991). Check native
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
lanes, recovery and the final default suite (46 registered, 45 PASS + Hibernate
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
wrapper, then verify baseline/health/default46. Keep the private recovery folder
until independent acceptance is complete. Timeouts: 600s build/start, 90s stop
plus 330s writer drain, 180s inner lane, 900s runner. Timed-out writers are
preserved and recovery refuses to race them.

Portable checks: `python3 scripts/test-sap-statics-driver.py` and the Gradle
harness contracts. Deferred field-initializer concurrency is outside this profile.

### Serialized native Solr profile

`python3 scripts/test-sap-solr-live.py` runs three complete native Solr cycles
on the clean sibling SAP installation, then the normal default46 suite.
`--failure-control` adds an expected-failure cycle after a real targeted v2
reindex and stored-field read; it must still recover fully. `--runs 1` is a
full diagnostic cycle. Only `SOLR_PROFILE PASS` includes native results,
complete stopped recovery and the final45 PASS + Hibernate SKIP. Internal
`solr-attempt` is not acceptance by itself.

The normal installation must have no running Solr, Solr facet configuration,
indexed type/index or `ReclazzSchemaProbe` rows; both normal fixtures and the
nonce-only synthetic type must already be deployed. Actual launched/config
options must match, with autoCompile on and Solr/resetStatics/schema/REMOVE
off. Native tenant database URLs must all identify the owned file HSQLDB.
Preflight SKIPs before test writes for missing/unavailable/mismatched gates.
After provisioning starts, any missing service, inner SKIP or cleanup failure
is FAIL/ERROR, never a successful or retroactively skipped profile.

The profile-only `reclazztestsolr` extension contains synthetic providers and
native Solr facts/setup endpoints. It is added with `solrserver` only during
the stopped profile boot; the normal fixture's dependencies do not change.
Native default Solr auth/SSL are retained, credentials stay in native memory,
loopback8983 is pinned and proven through lsof and the JVM's owned directory
arguments. A foreign existing service/custom symlink is refused.

Setup creates one nonce-owned `ReclazzSchemaProbe` row and one native search
config with distinct target/control physical cores. Initial full indexing is
restricted to these owned types. The test edits only the changed provider's
body, requires exact file/owner reload then a fresh exact native discovery tag
FINISHED with one partial property and one document, and reads the actual
uniqueKey, PK, physical field and v2 through native SolrJ. Stable field and
full control document must be unchanged. FINISHED_UNVERIFIED, FAILED, no docs,
stale value or a missing terminal cannot pass. Rollback requires its own
reload/FINISHED and stored v1 read.

Before the first configuration/data mutation the driver stops native writers
and captures full `hybris/config`, `hybris/data`, generated outputs and the
SDK's Solr distribution directory, and the full Tomcat conf directory. A
temporary native Context is derived from the existing owned fixture Context
to deploy the new profile webapp; ant build alone does not add it. All
unrelated server XML bytes and the original configuration are restored. The
distribution snapshot is necessary because native
Solr installation callbacks can remove previous distribution versions.
Original launcher bytes, profile source bytes, custom listing, fixture/agent
hashes and ordered snapshot targets are retained in a private recovery folder.
Recovery first proves inner runner exit, stops only the owned Tomcat/Solr,
then requires every native writer, HSQLDB lock and Solr listener gone. It removes
only its own symlink, restores full trees (including deleting extra core/data
files), rebuilds normally and verifies snapshots again before normal boot.
No live deleteIndex/deleteAll or inverse DDL is used. Fresh normal JVM, original
gates, both normal fixtures, absence of profile extensions/service/records,
all source/config/agent hashes and tenant health are required.

Recovery folders `/private/tmp/reclazz-solr-*` remain for diagnosis/manual use.
`state.json` contains original wrapper/source bytes and snapshot targets;
private snapshots retain all original files and hashes. For manual recovery,
prove every writer stopped first, remove only the recorded owned symlink,
restore source/wrapper and each verified snapshot, run native normal ant build,
restore any build-touched snapshot again, verify before boot, then check normal
runtime/empty Solr facts/default46. Never restore under a live writer or stop an
unknown listener. Budgets:600s native build/start,90s stop +330s writer drain,
300s owned bootstrap index,180s inner edit and180s live rollback,900s runner.
Timed-out writers are preserved and recovery refuses to race them.

Portable checks: `python3 scripts/test-sap-solr-driver.py`, existing snapshot
tests and the Gradle harness/HTTP contracts. The normal46 suite does not deploy
or run this optional extension. CMS and Backoffice remain separate profiles.

### Native CMS request-component/JSP profile

`python3 scripts/test-sap-cms-live.py --failure-control` adds an expected failure
only after a real rendered v2, then requires three complete positive cycles and
the restored normal46 suite (45 PASS + Hibernate SKIP). `--runs 1` is a complete
diagnostic. `--setup-probe` only builds the native SDK/commonweb classes, records
their API and restores everything; `CMS_SETUP_PROBE PASS` is **not** render
acceptance. Only `CMS_PROFILE PASS` includes the full attempt and normal suite.

This profile covers the real inherited `AbstractCMSComponentController`
request-attribute `component` branch and a Spring MVC/JSP render. It does not
cover UID lookup, persisted content catalogs, page-slot routing or full
storefront composition; those remain OPEN. The normal install and default46
membership do not acquire CMS dependencies.

The temporary `reclazztestcms` extension depends on the native accelerator/CMS
modules. Its native commonweb callback copies/compiles vendor files into ignored
build outputs; no vendor source/jars belong in templates or Git. The outer driver
compares copied source to installed SDK source and records the compiled native
superclass hash. The inner attempt verifies that provenance, the actual inherited
handler, runtime beans/options/tenant, watched fixture and recovery snapshots.
Each request creates an unsaved native paragraph, sets an owned nonce/content
and forwards to the inherited handler. The JSP must return HTTP200 text/html,
that nonce, the exact marker, stable content and unsaved=true. JSON200, stale HTML
or a changed native paragraph count fails. Only fillModel's marker body changes;
each direction requires its own exact COMPILE/owner reload and rendered proof.

Before provisioning, all native/inner writers stop and the HSQLDB lock must be
gone.17 full roots are captured: config, data, Tomcat, bootstrap, both normal
fixtures, the owned CMS fixture, native temp and9 new SDK dependency directories. This covers
commonweb source/lib/classes, generated models and Ant server/JSP outputs.
After capture, only the profile dependency build markers are invalidated so
restored model/classes cannot be hidden by stale native item/source markers.
The complete temp tree is restored with the other roots.
The standalone update is master-only: `ant updatesystem -Dtenant=master
-DtypeSystemOnly=true -DdryRun=false -DconfigFile=<private schema-only JSON>`.
Essential/project data, HMC clearing, localization and Lucene rebuilding are off;
`droptables` is deliberately absent (the native legacy API checks its presence).
No junit update/initialize or inverse DDL is used. Missing types after
provisioning are FAIL, never retroactive SKIP. Agent unsafe options stay off;
autoCompile stays on.

Every exit after capture stops/drains writers, removes only the recorded owned
symlink, validates all snapshots before deleting any target, restores complete
trees, rebuilds normally, restores build-touched trees again and verifies hashes
before a fresh normal boot. Both normal fixtures, original extension set/options,
absence of CMS types/classes/services,93 fixture hashes and tenant health must
return. Backups and durable state stay in private `/private/tmp/reclazz-cms-*`.
Manual recovery follows that same order; never restore under a writer or remove
an unowned link. Native setup/update budget900s, boot600s, stop90s plus330s drain,
edit/render180s and a separate180s rollback, runner900s. Timed-out writers remain
preserved. Portable checks: `python3 scripts/test-sap-cms-driver.py`, the snapshot
checks and Gradle harness/HTTP contracts. Full :agent:test remains release-only.


### Backoffice Chrome profile (reviewed temporary setup)

`python3 -B scripts/test-sap-backoffice-live.py prepare` performs the native setup
and prints a private recovery directory. It never drives Chrome and never prints
a rendered PASS. Claude runs the browser acceptance during independent audit.
Keep the printed directory as `RECLAZZ_BO_STATE`; pass it with `--state` to every
subsequent command. Do not start a second profile or edit these fixtures by hand.

The config node/labels live in the profile-only `reclazztestbackofficecfg`
extension. Its three sources are checked against immutable templates. It is never
in normal localextensions. The 13 normal Backoffice sources and Solr84/CMS93
memberships stay unchanged. The normal controller is edited only during the socket
experiment and restored exactly afterwards. Default registered membership stays46.

Before provisioning the driver requires the physical clean installation, launched
safe options, both normal fixtures/tenants, exact custom symlinks, no inner/native
competing writer and no reset overrides. Preflight failure is SKIP with zero
writes. A durable private lease blocks another preparation even between commands;
only the owning state may edit or restore. After capture any failure is FAIL with
recovery attempted. Nine complete stopped roots cover config,data,Tomcat,bootstrap,
native temp,both normal fixtures,the new profile,and native Backoffice SDK outputs.
Immutable inventories are verified before deletion. Recovery removes the owned
profile link and localextensions entry BEFORE restoring and booting original data,
rebuilds normally, restores again, checks inventories, and boots a fresh normal JVM.
Original media checksum and owned v1/v2 absence must match exactly. Snapshots remain
in the private state directory. Never reuse a state marked restored.

Setup first boots the added module with normal settings. If its v1 node is absent
from persisted media, one stopped setup boot may append exactly
`backoffice.cockpitng.reset.triggers=start` and
`backoffice.cockpitng.reset.scope=cockpitConfig`, then stop, remove exactly those
lines, and boot normally again. **That optional setup invokes native
resetToDefaults for the complete cockpitConfig.** It is the reviewed snapshotted
clean-DB setup exception. During edits the owner-approved agent F4 cockpit-config
reset is the operation under test; no additional manual reset, media purge or
rebuild may force an edit to pass. The chosen setup path,checksums,timings,events and native
bean-refresh failure count are stored in state.json. Count starts before setup;
zero is required through recovery and the default suite. Missing/truncated logs
cannot prove zero. A genuine native prepare->restore smoke must pass before Claude
starts Chrome acceptance, then prepare a NEW state for the experiment.

Claude's exact assertions, three config cycles and one socket cycle:

1. Open `https://localhost:9102/backoffice` in Chrome with the clean installation's
   local credentials. Capture only the synthetic UI after login. Locate the actual
   **Reclazz regression v1** explorer node and **Reclazz probe** widget. Record
   screenshots and time. Keep an already composed view open; its first **Send probe**
   must show `sent 1, no handler`. Missing readiness after setup is FAIL.
2. Run `python3 -B scripts/test-sap-backoffice-live.py config-v2 --state "$RECLAZZ_BO_STATE"`.
   The helper records a fresh connection cursor, exact config change and nonzero
   native F4 cockpit-config reset, then requires v2 in changed persisted media.
   Compose a genuinely new view and capture **Reclazz regression v2**. A receipt
   or media probe is not rendered proof. The owner-directed agent reset rebuilds
   cockpit configuration and resets UI-made configuration too, without resetting
   widgets. Do not manually force v2 with an extra global reset.
   Run the corresponding `config-v1` command to restore the source with its own
   receipt. Record the restored baseline UI and checksum.
3. Run `socket-add` with the same `--state`. It adds exactly one public String
   `@SocketEvent(socketId="probeInput")` handler and waits for exact controller
   COMPILE/STRUCTURAL_RELOAD. Open a newly composed widget; first **Send probe** must
   show `handled v1: ping-1`. Record the native added-handler advice and screenshot.
   The particular reopen action is verified in Chrome, not assumed by the helper.
4. Run `socket-edit`; await its exact COMPILE/reload. Click the same composed view;
   require `handled v2: ping-2` (record the actual counter if additional clicks).
5. Run `socket-remove`; await exact RELOAD or STRUCTURAL_RELOAD for the owner. Held old listeners may give
   the documented NoSuchMethodError. A fresh composed widget must show `sent 1,
   no handler`. Old views are not required to rebind. Never replay the whole native
   binding helper: the SDK duplicate control shows existing listeners would double.
6. Complete three config v1/v2/restore cycles, with fresh view proof each time,
   and one socket add/edit/remove cycle. Retain screenshots/events/media
   for every failure; a stale view or media value is FAIL, never PASS/SKIP.
7. Close the experiment's Chrome views and run `restore --state "$RECLAZZ_BO_STATE"`.
   The driver refuses unowned fixture differences or surviving writers. It restores
   the original persisted media checksum, all snapshots and source hashes, then
   starts a fresh normal JVM with the original extension set and safe options.
   Run the normal46 suite (45 PASS + existing Hibernate SKIP) after recovery and
   count native bean-refresh failures through the suite's end: zero required.

Every edit waits at most120s. Native build900s, boot600s, stop90s plus330s writer
drain. Timed-out native writers are preserved; never restore while one survives.
A failed edit remains FAIL; use the full restore command and retain recovery files.
`status --state ...` reports metadata/media only, never rendered acceptance.
Record actual elapsed times and screenshot paths in AUDIT.md; no credentials.
Portable contracts: `python3 -B scripts/test-sap-backoffice-driver.py`, plus the
unchanged Solr/CMS driver tests and snapshot integrity tests. Full :agent:test
remains release-only.

### DAO / FlexibleSearch query body

The default46 suite includes a plain XML singleton DAO querying persisted
`ReclazzSchemaProbe` rows. Each run owns a fresh `reclazz-dao-<uuid>` prefix.
An atomic seed creates A1/A2, B1 and a control row; the Java body edit must
change the actual query result from A1/A2 to B1, after its own COMPILE and
exact owner reload. Rollback must show A1/A2 again. Bean identity is diagnostic.
The test always cleans the owned rows, verifies zero owned/global synthetic
rows and exact source bytes, and fails on any error after seed is attempted.

`scripts/sap_suite_contract.py` holds the shared default46 outcome (45 PASS
plus only the Hibernate L2 SKIP), exact report membership and74 normal fixture
files. Solr adds10 files and CMS adds9; optional profiles remain separate.
Native acceptance requires five consecutive full default46 runs in one JVM
and one in a fresh JVM, plus a withheld-save RED run using a copy outside the
watched tree. Portable harness tests alone do not establish that acceptance.


### Native dynamic attribute handler

The default46 suite includes `DynamicAttributeLiveTest` (report label
`Dynamic attribute handler`). The normal synthetic `ReclazzSchemaProbe` has
`dynamicLabel`, a native dynamic descriptor backed by the plain XML
`reclazzDynamicLabelHandler` bean. The generated model's get/set methods call
the SAP persistence context's dynamic provider. The setter's map entry is
nonpersistent callback evidence; it is not a stored database attribute.

On an unprovisioned clean sibling installation, run:

```sh
python3 -B scripts/test-sap-dynamic-setup-driver.py
python3 -B scripts/setup-sap-dynamic-attribute.py --inject-failure
python3 -B scripts/setup-sap-dynamic-attribute.py
```

The one-time setup refuses an existing descriptor, partial source provisioning
or unrelated source edits. It accepts the original72-file source baseline or
the reviewed74-file checkout before an older database has the descriptor.
Existing source bytes must match HEAD or the exact owned templates.
It verifies the clean installation, safe effective agent flags, typed tenant
facts and writer ownership before any recovery directory or native write.
While stopped, it snapshots seven complete roots: config, data, bootstrap,
tomcat, native temp and both normal fixture trees. The new handler/metadata
are built first; the actual generated accessor source and javap signatures
must pass before installing the typed controller. The master-only schema
update uses the same schema-only arguments and import-disabled properties as
the CMS driver, with droptables absent. It keeps the installed agent and safe
launcher arguments. No database reset or project/essential data import is used.

Any setup failure restores matching whole DB/build/config/source snapshots,
including commonweb and fixture web outputs. All snapshots are verified before
any target is deleted. A surviving native writer prevents restoration; evidence
and the durable lease are retained for recovery. An expected injected failure
is reported separately and is never native reload acceptance. Successful setup
keeps the new descriptor and74 normal fixture files; this is not a repeatable
metadata-migration or removal test. Preserve its private recovery directory.

The live scenario creates one UUID-owned persisted row and warms/retains its
actual generated model. Each pure read also obtains a distinct native model
for the same PK. Both lanes must see v1, edited getter v2 and restored v1;
separate handler saves require fresh COMPILE then exact owner RELOAD receipts.
Three single setter POSTs use distinct run/step tokens to prove v1, edited v2
and restored v1 callbacks. No handler call or provider replacement substitutes
for the generated accessors. Finally it restores source bytes and removes the
owned row, retained model and map entry; owned/global rows and state must be zero.

Portable socket/HTTP negatives cover stale/wrong-owner receipts, stale held
models, aliased fresh models, changed identity/PK, missing native metadata,
wrong setter tokens/versions, partial seed and row/map leftovers. Native
acceptance additionally requires a withheld-save RED and five complete
same-JVM default46 runs plus one after a fresh JVM, each45 PASS with only the
existing Hibernate SKIP and no bean-refresh failures. Full `:agent:test`
remains release-only.
