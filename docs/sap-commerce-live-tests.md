# SAP Commerce: what we test live

Keep the server warm, then check the application. These scenarios save real
files against a running SAP Commerce 2211 installation and check what SAP
actually returns: an HTTP response, persisted query result, rendered component
or Backoffice view. A reload notification alone cannot pass them. Refusal cases
must also prove that the last working behavior and protected data stay intact.

Evidence is recorded through 11 November 2026. RemoveInterceptor and platform logger focused checks
passed; independent default-suite acceptance of the logger addition is pending. LoadInterceptor acceptance
extends the earlier documentation baseline at commit `f0ee45f`.
It describes existing acceptance evidence, not a new run performed by building
this documentation. See the [setup guide](sap-commerce.md) to attach Reclazz,
and [reload conditions](usage.md#sap-reload-outcomes-and-verification) before
applying these results to your installation.

## How we run it

We use a clean, licensed SAP Commerce 2211 installation with HSQLDB, standard
SAP modules and synthetic test extensions. It is separate from customer systems.
The default suite now contains **53 scenarios, all required to PASS, with zero
SKIPs**. The latest recorded acceptance ran five times on one server boot and
once after a fresh boot: **six runs of 53/53 PASS**, with zero bean-refresh
failures and unchanged fixture files. Hibernate ORM is tested
separately in the agent regression suite; it is not a SAP live scenario.

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
cascade/veto semantics or concurrent removal. Independent default49 acceptance
is pending.
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

## Populators and Orika

**You edit:** A populator body or an OCC field mapping that adds and then removes
a mapped property.

**Reclazz does:** Refreshes the populator or conversion metadata.

**The test proves:** OCC returns the edited populated value with an unchanged
control field. The Orika rollback case proves that a removed property disappears
again from the response. Arbitrary converter/list directives are not certified.
See [populator](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/PopulatorOccLiveTest.kt)
and [Orika rollback](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/OrikaPropertyRollbackLiveTest.kt).

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
row. Positive REMOVE, a full macro matrix and malformed-import recovery are
not covered by these live cases. See [auto-import](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/ImpexAutoImportTest.kt)
and [refusal](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/ImpexRemoveRefusalLiveTest.kt).

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

**The focused test proves:** A private bounded appender receives target DEBUG
only after the edit, with target/control INFO markers proving delivery in every
phase. The control logger, root, unrelated logger configuration and appender
attachments stay unchanged, in the same JVM. Withholding the property save and
omitting native emissions both fail the test; a final ordinary run passes again.
All four runs restored exact property bytes, removed the appender and left zero
active fixture runs. The source-backed-group candidate then passed a single
ordered 49-predecessor-plus-logger diagnostic: 50/50. Its installed jar/class
identity was verified. The prior unique-owner candidate had refused the two
owners and finished 49/50. This diagnostic is not independent default-suite
acceptance; Claude's audit and five-plus-one gate remain pending. Separate installed-SDK tests cover ambiguous owners and isolated
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

## CronJobs and native events

**You edit:** A JobPerformable body or an existing SAP event listener body.

**Reclazz does:** Reloads the prepared native consumer.

**The test proves:** CronJobService execution returns the edited version once,
with SUCCESS/FINISHED and owned job rows cleaned up. A published native SAP
event reaches the edited listener once, with nonce-isolated control results.
The CronJob invocation is synchronous; Trigger firing and listener registration
lifecycle remain open. See [CronJob](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/CronJobLiveTest.kt)
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
JSP bytes or exercise UID lookup, persisted catalogs, page slots or a complete
storefront. See [CMS profile](../integration-test/src/main/kotlin/com/onurkat/reclazz/inttest/tests/CmsComponentLiveTest.kt).

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

## Not covered yet

CMS UID lookup, persisted catalogs, page/slot selection and complete storefront
rendering remain open. The CMS profile above must not be read as their acceptance.
Native SAP region caches, Trigger scheduling, process actions held across an edit,
new OCC controller enrollment, broader Backoffice bindings and the scenarios in
the [coverage order](#coverage-order) also need their own live oracles.

Positive destructive ImpEx REMOVE and automatic conversion-directive replay
remain outside the current acceptance. Field-initializer concurrency is deferred.
Installed SDK modules and existing reload paths are research evidence, not proof
that their application behavior has passed a live test.

### Coverage order

This is the current ordered research/build queue, not a release commitment or
support claim. Each scope needs a concrete plan, native baseline, application
oracle, cleanup and independent review before it can move into the tested list.
If SAP already handles an edit, its test records that existing behavior.

1. Duplicate Backoffice F4 dispatch
2. Native SAP Trigger firing
3. Spring scheduled metadata lifecycle
4. Process action/in-flight version
5. Stored script/executable cache
6. Velocity RendererTemplate
7. SCAN wire completion
8. CMS UID/persisted catalogs
9. CMS page/slot selection
10. Deployed JSP/resource edit
11. Search restriction/session visibility
12. Facade consumer chain
13. Nested/list DTO conversion slice
14. New OCC controller enrollment
15. OCC native error response/serialization
16. ImpEx UPDATE/macros/malformed recovery
17. Locale/fallback missing slice
18. Backoffice label bundles
19. Native SAP region cache
20. ZUL/widget/VM definition slice
21. Workflow action/decision
22. TaskRunner retry/condition lifecycle
23. Spring Integration handler/channel
24. Hot-folder ingestion
25. Solr config/facet slice
26. One additive metadata shape
27. Native event listener lifecycle
28. Local SAP security/filter rule
29. Stored AdvancedSavedQuery parameters
30. Legacy AddOn source staging
31. One commerce service, then bounded remaining domains

Later refinements include B2B approval, Adaptive Search, personalization,
rule compilation and Integration API virtual attributes. They do not bypass
the same setup and acceptance gates. This page will be updated as each scope
receives an independent PASS and its corresponding commit.
