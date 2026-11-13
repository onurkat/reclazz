# SAP Commerce: what we test live

Keep the server warm, then check the application. These scenarios save real
files against a running SAP Commerce 2211 installation and check what SAP
actually returns: an HTTP response, persisted query result, rendered component
or Backoffice view. A reload notification alone cannot pass them. Refusal cases
must also prove that the last working behavior and protected data stay intact.

Evidence is recorded through 13 November 2026.
It describes existing acceptance evidence, not a new run performed by building
this documentation. See the [setup guide](sap-commerce.md) to attach Reclazz,
and [reload conditions](usage.md#sap-reload-outcomes-and-verification) before
applying these results to your installation.

## How we run it

We use a clean, licensed SAP Commerce 2211 installation with HSQLDB, standard
SAP modules and synthetic test extensions. It is separate from customer systems.
The default suite now contains **64 scenarios, all required to PASS, with zero
SKIPs**. The archived acceptance for the preceding 58-case membership ran five
times on one server boot and once after a fresh boot: **six runs of 58/58 PASS**, with zero bean-refresh
failures and unchanged fixture files. Since 13 November 2026 each accepted change
runs the default suite twice, once on the same boot and once after a fresh boot;
the latest acceptance was 64/64 in both runs. Hibernate ORM is tested separately in the agent
regression suite; it is not a SAP live scenario.

The live tests run locally before relevant commits and releases. CI execution
is manual; we do not run a licensed SAP server test on every push. Tests restore
fixture bytes and verify their application baseline, use owned data and check
cleanup. This test cleanup is not an automatic undo feature for your application.

Expensive or stateful cases have separate opt-in profiles. Their recorded
acceptance differs from the default suite:

| Profile | Recorded independent acceptance | Recovery check |
|---|---|---|
| `beans.xml` generation | Three consecutive passes on one boot | Generated API and fixtures restored; default suite checked around the profile |
| `items.xml` schema update | Three positive cycles and one deliberate failure control | Stopped HSQLDB snapshot restored and verified; fresh normal boot, no added attribute or owned rows |
| Static initialization | Three positive off/on cycles and one deliberate failure control | Fresh normal boot with the option off and startup values restored |
| Solr targeted reindex | Three positive cycles and one deliberate failure control | Owned local Solr and temporary configuration removed; fresh normal boot |
| CMS component render | Three positive cycles and one deliberate failure control | Temporary CMS layer restored and verified; fresh normal boot |
| Backoffice | Three rendered config edit/revert cycles, plus socket add/edit/remove checks | Configuration media restored to the recorded baseline |

Profile provisioning and recovery can stop and restart the isolated test server.
The edit assertions below happen on the running server. A schema profile's
recovery boot is not evidence that arbitrary schema changes need no restart.
The [runner](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/ReclazzTestRunner.kt)
records default membership and separates the opt-in profiles.

## Class bodies and structure

**You edit:** Java method bodies, constructors, methods, fields, signatures,
annotations, lambdas or several related classes.

**Reclazz does:** Compiles and reloads prepared classes, refreshing affected
Spring beans where the scenario requires it.

**The test proves:** Requests execute the changed behavior, including added or
removed members, constructor effects and recovery after invalid source. These
are bounded fixtures, not a promise that arbitrary object state migrates.
See the [constructor scenario](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/ConstructorChangeTest.kt)
and [JVM limits](usage.md#known-limitations).

## Spring beans and XML

**You edit:** A bean body or constructor, an XML property/reference, or a new
bean definition in a loaded Spring resource.

**Reclazz does:** Refreshes the affected bean or applies the supported XML
definition change in its owning context.

**The test proves:** HTTP reads the new property, referenced service or recreated
bean result, then the restored value. A new XML singleton becomes usable.
See [XML edits](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/SpringXmlLiveTest.kt)
and [new beans](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/SpringXmlNewBeanLiveTest.kt).

A separate focused check on 13 November 2026 confirmed existing local XML import
support: adding an import to a loaded file made a plain singleton available, then
editing the imported file updated the same singleton in the same JVM. Without the
agent, the import save left the bean absent. The child file was prepared before
boot to isolate the import edit; live child-file creation, remote imports and
unloading were not measured. This adds no profile or default-suite membership.

## Spring scheduled metadata

**You edit:** The fixed delay on an existing prepared `@Scheduled` method.

**Reclazz does:** Uses the existing Spring scheduler reload path to replace the
method's registration.

**The test proves:** One owned native FixedDelayTask follows 1000 -> 300 ->
restored 1000 ms. Actual monotonic tick gaps follow the interval in each phase,
and the registered task and ticks use the current bean. The owner context stays
the same and exactly one owned registration remains. Cleanup clears the run's
observations and restores the one baseline timer. The round trip takes
about 12 s. Foreign schedules are excluded from its count and are not changed.
This records existing metadata reload; added/removed scheduled methods and
other scheduling modes are outside this case. See the
[metadata round trip](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/ScheduledMetadataLiveTest.kt).

## MVC and OCC mappings and field sets

**You edit:** A prepared controller's request mapping, OCC override priority or
field-set XML.

**Reclazz does:** Refreshes the relevant mappings or field-set configuration.

**The test proves:** The new route returns its expected payload while the old
route becomes 404; an override changes the winning response; field selection
changes the actual OCC response. New controller enrollment is a separate open
scope. See [routes](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/OccRequestMappingLiveTest.kt),
[field sets](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/OccFieldSetLiveTest.kt)
and [override priority](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/PlatformConfigLiveTest.kt).

**You edit:** The Java body of your own request date parser that an OCC
controller calls (a Spring `Converter<String, Date>` bean).

**The test proves:** The OCC response returns the edited parsed time, then the
restored one; invalid input keeps its 400 status and a time/locale control stays
unchanged. SAP's own `WsDateFormatter` and response serialization are not
covered by this case. See [OCC date parsing](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/OccDateParsingLiveTest.kt).

## Interceptors and mappings

**You edit:** A ValidateInterceptor, PrepareInterceptor, InitDefaultsInterceptor, LoadInterceptor or RemoveInterceptor body, add an XML
interceptor mapping, or change its interceptor reference and type code.

**Reclazz does:** Refreshes the interceptor and updates SAP's actual interceptor
registry, preserving its native registry implementation.

**The test proves:** Native model saves observe the changed validation behavior
and the intended mapping, including retargeting a mapping. The prepare case
commits a changed field through ModelService.save: v1, edited v2, then restored
v1. Independent detached reads require the same PK, one callback per save and
an unchanged control row. The permanent mapping also leaves DAO-, dynamic- and
schema-shaped nonces unchanged; cleanup proves zero remaining synthetic rows
and recorder entries.
The InitDefaults case checks v1, edited v2 and restored v1 immediately after
native ModelService.create, before a separate save. An unsaved model retained
across the edit keeps its original v1 default. Detached persisted reads agree,
with exactly one callback per create and none added by save. Unscoped creates
and DAO/dynamic/schema/prepare-shaped guard rows stay unchanged; cleanup releases
all held models, scopes, recorder entries and owned rows. This is request-thread
coverage, not concurrent tenant/session acceptance.
The Load case clears the model context, then loads a persisted row through native
ModelService.get. Three distinct callback-recorded objects prove v1/v2/v1, exactly
one callback each. A second get in the same request returns the attached object
without another callback; explicitly reattaching the retained old model across
edits also preserves its original proof. Models are detached in the request that
attached them. Persisted target/control data and five unrelated guard rows stay
unchanged; cleanup releases all rows, models, scopes and records. This proves
fresh model conversion, not a database or region-cache miss, and does not cover
refresh(). Withheld-edit and reuse-held negative controls both fail as required.
The Remove case commits three disposable nonce-owned rows, then invokes native
ModelService.remove once per step across v1/v2/v1. An unedited recorder verifies
one callback and exact model identity; a separate request verifies committed row
absence. A control row stays intact. Six unscoped synthetic guard removals and
cleanup cannot add callback evidence. Both focused runs passed; withholding the
edit and omitting native removal failed their receipt/callback oracles, with all
owned rows, scopes and records cleaned. This covers existing body reload, not
cascade/veto semantics or concurrent removal. It is part of the default suite
and passes in the six-run acceptance above.
See [native removal](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/RemoveInterceptorLiveTest.kt).
See [fresh and warm loads](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/LoadInterceptorLiveTest.kt).
See [defaults on create](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/InitDefaultsInterceptorLiveTest.kt).
See [prepare saves](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/PrepareInterceptorLiveTest.kt),
[mapping addition](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/InterceptorMappingAddLiveTest.kt)
and [mapping edits](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/InterceptorMappingEditLiveTest.kt).

## Bean Validation

| Default scenario | Verified behavior |
|---|---|
| Persisted validation constraints through ImpEx | The watcher reloads SAP's native validation engine after a constraint change; held and fresh validation see v2 and restored v1. Unrelated imports leave the engine unchanged. |

Diagnostic and no-reload control runs of this scenario remain opt-in.

**You edit:** A validator body or a Java constraint annotation parameter.

**Reclazz does:** Reloads the validator and refreshes validation metadata for
the prepared class.

**The test proves:** The existing validator/factory returns the changed violation
message or accepts a value after its limit changes. Invalid and valid controls
remain checked. This does not cover stored SAP validation-engine constraints.
See [validator scenarios](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/ValidatorLiveTest.kt).

## Native OCC exception error response

`OccErrorResponseLiveTest` exercises an existing Java body reload path through
SAP's native `RestExceptionResolver`, `DefaultWebserviceErrorFactory` and
`ExceptionConverter`. An owned service selects one of two preloaded exception
types. Editing only its throw site must change the public HTTP400 error type
First -> Second -> First; the exact parsed error document is checked each time.
The generic error message remains unchanged under the native default policy.

The controller does not construct error JSON or catch the exception. Its success
endpoint and independent control exception stay unchanged, as do the serving
context, service identity and JVM. An actual baseline response must fail the v2
oracle before the edit; source-correlated compile/reload receipts are required
in both directions. This is default case62 and coverage of existing behavior,
not a new exception handler or a change to SAP's error policy. The focused native
result and recovery are recorded at handoff; full default repeatability belongs
to the independent audit.

## New OCC controller after startup

**You add:** A controller Java source that was absent at server startup, in a
package covered by an already loaded plain XML component-scan. Each test run
uses a new class, bean name and route.

**Reclazz does:** Registers the new controller in the contexts that actually
loaded the matching scan, then uses SAP's native MVC mapping lifecycle. A
shared defining classloader does not place an OCC controller in the global
context. Unsupported scan filters, custom scanner settings and unresolved
package expressions do not establish a new scan owner.

**The test proves:** Native OCC changes from404 to200 and returns both the fresh
source nonce and request nonce, with actual `CommerceHandlerMapping` ownership,
unchanged existing OCC/probe endpoints and the same JVM. This is default case61.
Deleting the source/class files leaves the already loaded endpoint active until
restart; automatic bean/handler removal is not claimed. The test removes its
owned files, and install recovery resets runtime registrations. The focused
regression passed; full61-case repeatability remains an independent audit gate.
See [the new-controller case](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/NewOccControllerEvidenceTest.kt).

## Populators and Orika

**You edit:** A populator body or an OCC field mapping that adds and then removes
a mapped property.

**Reclazz does:** Refreshes the populator or conversion metadata.

**The test proves:** OCC returns the edited populated value with an unchanged
control field. The Orika rollback case proves that a removed property disappears
again from the response. Arbitrary converter/list directives are not certified.
See [populator](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/PopulatorOccLiveTest.kt)
and [Orika rollback](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/OrikaPropertyRollbackLiveTest.kt).

## Native facade and dependent service

**You edit:** Only the Java body of an owned service injected into a native
`DefaultProductFacade` child bean. The service delegates product lookup to SAP's
native product DAO and changes the selected owned product.

**The test proves:** Public HTTP returns `ProductData` from the native facade,
using an isolated SAP populating converter with native `ProductBasicPopulator`.
The selected product name changes v1 -> v2 -> v1; other converted fields and an
independent facade endpoint remain stable. Exact persisted product identities,
names, bean collaborators and JVM identity stay unchanged. All three owned
products and their catalog/version are removed afterward. An actual baseline
response rejects the withheld v2 oracle before the edit.

This is default case 60. It covers the dedicated native basic converter, not the
full storefront URL/image converter, cart/checkout, or arbitrary facade paths.
It adds a native facade/service consumer to existing populator/OCC coverage;
no new agent reload mechanism is claimed. Full default repeatability belongs
to independent audit. See
[the facade scenario](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/FacadeChainLiveTest.kt).

## DAO and FlexibleSearch

**You edit:** The query body of a plain XML singleton DAO.

**Reclazz does:** Reloads the DAO through its existing Java/bean path.

**The test proves:** Genuine persisted fixture rows yield exactly two A rows,
then one B row after the edit, then the original A rows after restoration.
Cleanup removes all four owned rows. This proves query execution, not native
region-cache invalidation or search-restriction coverage.
See [FlexibleSearch](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/FlexibleSearchLiveTest.kt).

## Dynamic attributes

**You edit:** The getter/setter body of an XML-wired DynamicAttributeHandler.

**Reclazz does:** Reloads the handler used by the native generated model.

**The test proves:** Both a warmed held model and a detached/requeried model
with the same PK return the changed getter value, then restore it. Distinct
setter tokens reach the changed callback. The setter records a nonpersistent
map value; it does not prove a stored database attribute write.
See [dynamic attributes](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/DynamicAttributeLiveTest.kt).

## ImpEx auto-import and REMOVE refusal

**You edit:** The watched fixture ImpEx data, with auto-import explicitly enabled.

**Reclazz does:** Imports the supported file into the running platform. With
REMOVE permission off, it refuses a file containing a REMOVE header.

**The test proves:** Imported values appear through the application. The refusal
case leaves the protected row unchanged and does not import a following bait
row. The dedicated macro UPDATE case below adds unresolved-header guard
recovery. Positive REMOVE, a full macro matrix and native parser rollback
are not covered by these live cases. See [auto-import](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/ImpexAutoImportTest.kt)
and [refusal](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/ImpexRemoveRefusalLiveTest.kt).

## ImpEx UPDATE macros and corrected-save recovery

`ImpexUpdateMacrosLiveTest` adds default case63 to the existing auto-import
coverage. A run-owned watched file uses a resolved UPDATE header macro, a
column macro and code/value macros. SAP's native import changes the owned
Title target from v0 to v1; detached database reads retain exact PKs, German
values and an independent control row.

A later save contains an unresolved header after a valid UPDATE bait row.
Reclazz's existing whole-file preflight refuses it: the exact warning names
the file and line, no success receipt is accepted, and both rows stay unchanged.
Correcting the same file imports v2; a final UPDATE restores v0 before owned
file/row cleanup. This demonstrates pre-import guard recovery, not native
parser atomicity or rollback. Positive REMOVE and the full macro matrix remain
outside this case. The actual baseline must fail the v1 oracle before any save.

## Spring Integration endpoint edit

**You edit:** The expression of a `<int:service-activator>` in a watched
`*-spring.xml`.

**Reclazz does:** Stops the old endpoint, which unsubscribes it from its
channel, replaces the endpoint and its generated handler definition under
their live names, then creates and starts the new endpoint, in every context
that loaded the file. If the new handler cannot be created the old endpoint is
started again and the save is reported as needing a restart.

`SpringIntegrationLiveTest` adds default case64. Before the save, four
request-reply messages through the owned `DirectChannel` answer `A:x` in each
owner context. After the save the same four messages answer `B:x`, the channel
has exactly one subscriber, and the save reports no restart item. The original
file is restored and `A:x` is verified again. Adding or removing endpoints,
channel type changes, gateways and Java DSL flows still ask for a restart.
Poller edits are restart-only except the file inbound adapter case in
[focused checks](#focused-checks-outside-the-default-suite).

## Localization and properties

**You edit:** An English type-localization resource or an application property.

**Reclazz does:** Refreshes the relevant localization cache or applies changed
platform configuration keys.

**The test proves:** A cached localization lookup returns the edited label;
application HTTP reads the changed property while a control key stays stable.
This does not certify every startup-only consumer, logger backend, locale
fallback or stored type-name update. See [localization](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/LocalizationLiveTest.kt)
and [properties](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/PlatformConfigLiveTest.kt).

## Platform logger levels

**You edit:** The level in a watched `log4j2.logger.<id>.name` / `.level`
property pair, from INFO to DEBUG and back.

**Reclazz does:** Finds the existing Log4j2 context owning the named logger,
changes only its explicit configuration and reads back the live logger level
before reporting success. A saved SAP alias can authorize several owners only
when every owner has the same alias/name/level declaration in its verified
in-memory property source. Each member is updated and read back; undeclared,
file/XML, unknown or oversized sources refuse the group. Plain level maps and
other ambiguous owners remain refused. Partial failure triggers verified
rollback; failed rollback reports an error and a restart concern. Several
contexts without a unique owner remain refused.
A cold logger is allowed in a sole existing context. Inherited levels receive
a named child configuration; parent and root remain unchanged. Discovery covers
listed contexts plus the initialized selector default, using a read-only
adapter verified against Log4j2 2.25.3. It does not initialize an unused default
or enumerate arbitrary thread-bound/custom contexts. Unknown or inaccessible
selector layouts and detected inventory changes warn without level mutation.

**The test proves:** A private bounded appender receives target DEBUG
only after the edit, with target/control INFO markers proving delivery in every
phase. The control logger, root, unrelated logger configuration and appender
attachments stay unchanged, in the same JVM. Withholding the property save and
omitting native emissions both fail the test; a final ordinary run passes again.
All four runs restored exact property bytes, removed the appender and left zero
active fixture runs. The source-backed-group candidate then passed a single
ordered 49-predecessor-plus-logger diagnostic: 50/50. Its installed jar/class
identity was verified. The prior unique-owner candidate had refused the two
owners and finished 49/50. It is part of the default suite and passes in the six-run acceptance
above. Separate installed-SDK tests cover ambiguous owners and isolated
framework classloaders; logging XML and Solr's separate JVM are outside this
scenario. See [logger delivery](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/LoggerLevelsLiveTest.kt).

## Business process XML

**You edit:** A process definition, or introduce a definition file and its bean
in either arrival order.

**Reclazz does:** Refreshes or registers the supported process definition;
malformed XML retains the last working definition.

**The test proves:** Native processes start and complete the selected wait/end
path with the expected definition version. New file-first and bean-first
definitions execute. This does not prove action-body reload or a process held
in flight across an edit. See [definition edits](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/ProcessXmlLiveTest.kt)
and [new definitions](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/NewProcessLiveTest.kt).

## Business process action body

**You edit:** The body of a prepared Java process action reading the inherited
protected `processParameterHelper` field directly, from `action-v1` to
`action-v2` and back, without changing its process definition.

**The test checks:** A native process is held at its wait node before the edit.
After the matching compile and reload receipts, releasing that same process
must persist `action-v2` in its own process parameter and reach `SUCCEEDED`.
A fresh process must also persist v2, and another must persist v1 after source
restoration. Process identity and definition version stay pinned. Cleanup
removes the owned processes, tasks, task logs and context parameters.

The initial direct-field probe failed after reload with `IllegalAccessError`.
Companion field access now links through the original class lookup; the focused
native case passes with the direct field, including the held process and source
restoration. This proves this inherited SAP helper field in the tested subclass,
not every protected-member or classloader shape.

Retry, error and split branches are outside this case. The case takes about
84 s. See [action and in-flight process](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/ProcessActionLiveTest.kt).

## Stored database scripts

**You edit:** The content of one owned Groovy `Script` through a watched ImpEx
UPDATE, from v1 to v2 and back. The same `model://` URI is resolved through
SAP's native `ScriptingLanguagesService.getExecutableByURI()` and executed
with a distinct binding in each phase.

**The test proves:** After two initial URI resolutions, withholding
the edit still returns v1. The first fresh URI execution after the completed
import returns v2, and the first after restoration returns v1. The independent
control script stays unchanged, with the same service, JVM and retained target
reference throughout. No restart or manual cache flush occurs between phases.
Owned target/control rows and all their revisions are removed afterward.

SAP already handles this database-script invalidation; no agent adapter was
added. The initially retained compiled executable continues returning v1 even
when a fresh lookup returns v2. The test records that distinction; it does not
claim retained objects are retargeted. File and classpath script repositories
are outside this case. It is part of the default suite and passes in the
six-run acceptance above. See [stored script execution](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/StoredScriptLiveTest.kt).

## Velocity database templates

**You edit:** The default script content of owned subject and body
`RendererTemplate` models through a watched ImpEx UPDATE, from v1 to v2 and
back. The renderer type is Velocity; output stays in an in-memory writer.
No email is sent.

**The test proves:** SAP's native `RendererService` renders the exact
subject and body text with a new binding in each phase. Withholding the save
keeps v1. The first render after the completed v2 import returns v2, and the
first after restoration returns v1. An independent control template stays
unchanged. The same service and JVM handle every phase without restart or
manual cache flush. Owned templates and their media are removed afterward.

This records SAP's existing default-content media behavior; no agent adapter
was added. At the explicit English locale, UTF-8 text and literal `<&>` in the
plain-text binding are checked exactly. This does not establish HTML escaping,
localized template selection, physical `.vm` file reload, or email delivery.
See [native Velocity template rendering](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/VelocityTemplateLiveTest.kt).

## CronJobs and native events

**You edit:** A JobPerformable body or an existing SAP event listener body.

**Reclazz does:** Reloads the prepared native consumer.

**The test proves:** CronJobService execution returns the edited version once,
with SUCCESS/FINISHED and owned job rows cleaned up. A published native SAP
event reaches the edited listener once, with nonce-isolated control results.
The CronJobService case invokes the job synchronously. The separate native
Trigger case persists one owned CronJob and three one-shot Triggers. SAP
creates and dispatches each linked TriggerTask; its actual firing observes
v1, the saved v2 body, then restored v1, each once with SUCCESS/FINISHED.
Task identity and timestamp ordering are checked; the native scheduling offset
is recorded rather than overridden. Owned tasks, triggers, job/history/log
rows and probe observations are removed. In the default suite the case takes
about 71 s, because SAP schedules each one-shot task after its activation time.
This records existing body reload through SAP scheduling; it adds no scheduler
adapter. Listener registration lifecycle remains open. See
[native Trigger firing](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/TriggerFiringLiveTest.kt),
[CronJob](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/CronJobLiveTest.kt)
and [events](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/SapEventLiveTest.kt).

## Generated DTOs from beans.xml

**You edit:** A DTO property in `beans.xml` in the codegen profile.

**Reclazz does:** Runs platform generation/build and reloads generated bytecode.

**The test proves:** The generated setter is usable by compiled typed code;
the new value appears in the FULL OCC response and stays absent from BASIC.
Restoration removes the generated property again. Generation alone is not the
oracle. See [DTO codegen](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/BeansCodegenLiveTest.kt).

## Persistent attributes from items.xml

**You edit:** One additive persistent attribute in the isolated schema profile.

**Reclazz does:** Generates the model and, with `autoUpdateRunningSystem=true`
explicitly enabled, requests the live type-system/schema update.

**The test proves:** The native descriptor exists and typed model code writes
the attribute; detach/requery returns a different model instance with the
persisted value. The driver restores the stopped database snapshot afterward.
This is not type removal, arbitrary relation/index migration or safe production
DDL. See [schema profile](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/ItemsSchemaLiveTest.kt)
and [option conditions](usage.md#agent-arguments).

## Static initialization with an explicit opt-in

**You edit:** An eligible static literal-map initializer in the statics profile.

**Reclazz does:** With `resetStatics=true`, resets the eligible field after
reload; the off lane and ineligible initializer cases retain their guardrails.

**The test proves:** Direct reads and a retained, warmed MethodHandle getter
both see the new map. Runtime-created state and identity controls remain intact.
This is not general static-state migration or field-initializer concurrency
coverage. See [statics profile](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/ResetStaticsLiveTest.kt).

## Solr targeted reindex

**You edit:** The fixture value-provider body in the isolated Solr profile.

**Reclazz does:** Reloads the provider and invokes the opted-in targeted reindex.

**The test proves:** The actual local Solr document's stored field changes,
with one targeted property/document; its stable field and a control document
stay unchanged. This does not certify Solr schema/facet changes, a remote
cluster or a different search backend.
See [Solr profile](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/SolrTargetedReindexLiveTest.kt).

## CMS component rendering

**You edit:** A Java marker in a controller using SAP's inherited CMS request
handling in the temporary CMS profile.

**Reclazz does:** Reloads the Java change used by that render path.

**The test proves:** The inherited controller renders an unsaved request
component through JSP: HTTP 200, HTML content type and one proof element with
the current nonce, edited marker and expected content. It does not save/edit
JSP bytes or a complete storefront. A separate persisted-UID case
creates one owned content catalog with two versions and four saved components.
SAP's inherited controller resolves the same UID through the session catalog
version, then renders JSP. Editing and reverting the selected component's Java
marker changes its HTML while the other version and independent controls remain
stable. Persisted identities stay unchanged, the previous session catalog scope
is restored, and owned rows are removed through SAP's model service. This case
is opt-in and does not increase the default 64-case suite. See the
[request-component case](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/CmsComponentLiveTest.kt)
and [persisted UID case](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/CmsUidLookupLiveTest.kt).

A third opt-in case selects an owned page by UID with `CMSPageService`, then
resolves its target/control positions through native `getContentSlotForPage`
and persisted `ContentSlotForPage` placements. The selected slot supplies the
component UID to the inherited render path. A Java marker edit and revert
changes only the selected target; the same-page control and both positions in
the other catalog version stay stable. Page, slot and component identities
stay unchanged, session catalog versions are restored, and owned placements,
pages, slots and templates are removed before catalog cleanup. See the
[page/slot case](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/CmsPageSlotLiveTest.kt).
This exercises existing Java reload after native selection; it does not prove
search restriction visibility, template inheritance or JSP resource hot edits.

A fourth opt-in case saves and restores the actual page-slot JSP source, with
an escaped literal marker `jsp-v1 -> jsp-v2 -> jsp-v1`. The native servlet
context must resolve that source file directly, and Jasper must use
`development=true`, `checkInterval=0`, `modificationTestInterval=1`. Rendered
HTML follows each save in the same JVM, with unchanged Java bytes, Java
markers, page/slot/component identities and an independent unchanged view.
No build, copy, cache clear or restart occurs between saves. This covers
existing SAP/Tomcat JSP serving with Reclazz present; it adds no JSP reload
mechanism to the agent. See the
[JSP resource case](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/CmsJspResourceLiveTest.kt).

## Search restrictions and session catalog visibility

**You edit:** The owned Java DAO query suffix, `one -> two -> one`.

**Reclazz does:** Reloads the query method while SAP keeps applying native
persisted `SearchRestriction` rows. No restriction cache or security bypass
is added by Reclazz.

**The test proves:** Two owned non-admin users each see their exact owned
Product PK in each of two session catalog versions. The restrictions combine
user-specific product codes with SAP's native `?session.catalogversions`
predicate. Changing and restoring the DAO query selects the expected second
and first Product PKs. A native-admin query still sees all eight owned products,
and an unrelated Title query stays unchanged. Product, user, catalog and
restriction identities remain stable. Local views restore the outer user,
catalog versions, enabled flag and session restrictions; exact owned cleanup
removes the test rows. No global or per-query restriction disable is used.
This normal-boot case is the 59th default scenario; full 59-case repeatability
must be established by its independent audit. Restriction text saves, role
hierarchies and CMS page-service restriction semantics are separate scopes.
See the [native restriction case](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/SearchRestrictionLiveTest.kt).

## Backoffice configuration and widgets

**You edit:** Backoffice config XML, or add/edit/remove a one-parameter public
non-static `@SocketEvent` handler in an agent-prepared default MVC widget class.

**Reclazz does:** A config save calls the active cockpit configuration service's
native `resetToDefaults()`, equivalent to the F4 config reset. Prepared Java
body edits use class reload; a newly composed view can bind an added handler.

**The test proves:** Chrome shows the edited config title in the next fresh
view through three edit/revert cycles without a server restart. Native widget
dispatch handles the added method, the same view executes its edited body,
and a fresh view has no handler after removal. Configuration media is restored.
The F4-equivalent reset also resets UI-made config changes; it does not reset
widgets. Open views retain bindings. Broader routing/MVVM and duplicate config
dispatch are not resolved by this acceptance.
See [Backoffice conditions](usage.md#backoffice-java-modules)
and the [live profile driver](../scripts/test-sap-backoffice-live.py).

## Focused checks outside the default suite

These changes were measured on a running SAP Commerce 2211 installation, but
they are not part of the 64 default scenarios. In each case a native-only run
(agent off, same save) first showed that SAP keeps the old behavior until a
restart. The agent run then showed the new behavior in the same JVM, and a
deliberately disabled agent step made the focused tests fail. Repeatable local
regressions use the installed SAP SDK classes; they start no server and change
no installation.

| You edit in Spring XML | What the running server shows | Repeatable regression |
|---|---|---|
| Append an ordinary singleton with `InitializingBean` or `init-method` | The bean is created once through Spring's own initialization; an identical save keeps the same instance | Agent tests (`XmlNewInitializingBeanTest`) |
| A `project.properties` value used as an exact `${key}` bean property | The existing bean receives the new value and keeps its identity | Agent tests (`XmlPlaceholderPropertiesTest`) |
| `add` or `remove` populator of an existing `ModifyPopulatorList` | The running converter uses the new populator list; the edit can be reverted | `scripts/test-sap-directives-sdk.py` |
| Append a plain SAP `AbstractEventListener` bean | The new listener receives the next event once; failed creation removes it | `scripts/test-sap-events-xml-sdk.py` |
| The `converter` of an existing hot-folder converter mapping | The next ingested file is converted by the new converter | `scripts/test-sap-hotfolder-sdk.py` |
| `filename-regex` or `fixed-rate` of an explicit-id file inbound adapter | The next poll uses the new pattern and rate; files already seen are not delivered again | Agent tests (`SpringIntegrationPollingAdapterReloadTest`) |
| An access expression in a `security:http` group | The servlet filter chain enforces the new rule; failure keeps the old chain | Agent tests (`SpringSixXmlSecurityReloadTest`) |

Conditions and limits for each case are in the [setup guide](sap-commerce.md)
and [usage](usage.md#sap-populator-and-list-merge-directives). A failed SAP code
generation no longer blocks later class reloads; agent tests cover that case.

Backoffice widget definitions and new widget folders were measured and are not
supported: SAP reads them from the Backoffice jars built by `ant build`, and
even with SAP's development switches F4 and Reset Everything did not show the
change. Use the native build and redeploy, or restart. Widget controller Java
bodies reload as ordinary classes.

## Not covered yet

Complete storefront rendering, CMS template inheritance/overrides, search
restriction visibility through CMS page services, precompiled/deployed-copy
JSP setups and tag/include invalidation remain open. The focused cases above
must not be read as their acceptance.
Recurring Trigger lifecycle, process actions held across an edit, broader
Backoffice bindings and the scenarios in the [coverage order](#coverage-order)
also need their own live oracles.

Positive destructive ImpEx REMOVE and automatic conversion-directive replay
remain outside the current acceptance. Field-initializer concurrency is deferred.
Installed SDK modules and existing reload paths are research evidence, not proof
that their application behavior has passed a live test.

### Coverage order

This is the current ordered research/build queue, not a release commitment or
support claim. Each scope needs a concrete plan, native baseline, application
oracle, cleanup and independent review before it can move into the tested list.
If SAP already handles an edit, its test records that existing behavior.

1. Duplicate Backoffice F4 dispatch (a bug investigation)
2. Nested/list DTO conversion slice
3. Locale/fallback missing slice
4. Workflow action/decision
5. TaskRunner retry/condition lifecycle
6. Solr config/facet slice
7. One commerce service, then bounded remaining domains

The other scopes of the earlier queue are either in the tested list above or
were closed without new work: SAP already applies data changes made through
ImpEx or Backoffice (saved queries, rules, Solr properties), AddOn staging is
done by SAP's own `ant build`, and native region caches and Backoffice label
bundles did not justify a reload path.

Later refinements include B2B approval, Adaptive Search, personalization,
rule compilation and Integration API virtual attributes. They do not bypass
the same setup and acceptance gates. This page will be updated as each scope
receives an independent PASS and its corresponding commit.


### Opt-in new item type

With `autoUpdateRunningSystem=true`, Reclazz picks up one new `generate=true`
item type live through native generation and the schema-only update. The
`items-new-type-attempt` profile proves the generated Model and Jalo, deployment
31992/table, typed create/save, detached requery and a separate committed JDBC
read without replacing the running JVM. This covers existing behavior.

Run the outer driver `scripts/test-sap-items-live.py --new-item-type --runs 1`
with `--fixture-manifest <reviewed JSON path-to-SHA256 manifest>`,
`--restore-repo <normal checkout>` and `--lock <collab installation lock>`.
Select the disposable install with `RECLAZZ_SAP_HOME` and `RECLAZZ_SAP_BASE`.
The manifest contains the exact normal fixture source membership from the shared suite contract, including any
reviewed edits. The mode takes eight complete stopped snapshots, including
concrete generated Jalo sources, and restores the database, config, bootstrap,
native outputs, fixtures, original agent and links. It then proves the new
type/table/classes absent in a fresh normal boot. Inner PASS with failed recovery
is FAIL. Never run the inner profile alone or undo its XML on a live server.
Native HAC credentials are read only from the disposable install's local config;
the recovery checks use read-only native metadata and class observations.

Default suite membership is unchanged by this mode. This bounded profile does not certify enums,
relations, incompatible edits or arbitrary schema changes. No new dependency or
production agent change is involved.

The pre-generation negative control inspects class resources rather than trying
to load a nonexistent class: SAP's enabled `CacheClassNotFoundRule` remembers
failed class loads. After generation the profile requires the real generated
Model/Jalo classes and rejects generic fallbacks. A class previously requested
and negatively cached before generation is outside this bounded coverage.


### Opt-in new relations

With `autoUpdateRunningSystem=true`, the existing native generation/schema path
supports the bounded one-to-many and many-to-many relation edit exercised here.
`items-new-relation-attempt` first creates two isolated generated endpoint types,
warms their model classes, then adds both relations in one XML save. It requires
new typed accessors on those same loaded classes and persisted asymmetric links
re-read detached from both ends in a separate request. The one-to-many qualifier
is `relationParent`; `owner` is inherited from SAP's base Item and is not a new
relation attribute. The many-to-many relation has its own deployment31995.

Run `scripts/test-sap-items-live.py --new-relation --runs 1` with the same explicit
`--fixture-manifest`, `--restore-repo` and `--lock` arguments as the new-item-type
mode. Set `RECLAZZ_SAP_HOME` and `RECLAZZ_SAP_BASE` to the disposable installation.
The eight-root stopped recovery is shared: database, generated sources/classes,
config and native outputs, original agent and extension links must all recover,
then experimental types/tables/resources must be absent after a fresh normal
boot. An inner PASS alone cannot pass the outer profile. Never run this inner
profile directly or undo schema changes live. Future classes are inspected by
resource lookup before generation to avoid seeding SAP's negative class cache.

This opt-in proof covers existing behavior and leaves default suite and normal
fixture membership unchanged. It does not certify arbitrary, localized, ordered,
cascading or incompatible relation edits. No production agent change or new
dependency is involved. Source membership comes from the shared suite contract;
do not copy a historical fixture/test count into new profile checks.
