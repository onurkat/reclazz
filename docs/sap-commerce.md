# Reclazz on SAP Commerce, for a coding agent

## Install from Marketplace

1. In IntelliJ IDEA, open **Settings → Plugins → Marketplace**, search **Reclazz** and choose **Install**. Restart IntelliJ if prompted.
2. Open your SAP Commerce project. In **Settings → Tools → Reclazz**, tick **Enable Reclazz in this project** and choose **Apply**.
3. Choose **Install Agent** in the **SAP Commerce** section. The plugin includes the agent and backs up the startup-properties file it updates.
4. Follow the setup check: if it reports different wrapper options, regenerate them with `ant server`, then restart the development server. See the [command-line steps](#enable-it-from-the-shell) if you need help with those commands.
5. Choose **Check setup**. Once the agent connects, change an existing method, compile with your usual build, and repeat the same application action.

It worked when the running server returns your changed result without another restart. The check reports suggested fixes; it does not silently repair them.

[Install from Marketplace](https://plugins.jetbrains.com/plugin/33498-reclazz) · [Türkçe kurulum rehberi](https://reclazz.com/tr/sap-commerce-kurulum/)

## Command-line setup for coding agents



This is the SAP Commerce (Hybris) counterpart of
[for-ai-agents.md](for-ai-agents.md): how a coding agent working on a Hybris
project enables Reclazz from the shell, verifies it, and keeps its edit-run loop
hot instead of restarting a server that takes minutes to come up. For the full
human setup and every agent argument, see the manual setup in
[usage.md](usage.md#option-1-manual-setup); this page is the agent-oriented
recipe on top of it.

For application-level proof, see [what we test live on SAP Commerce](sap-commerce-live-tests.md):
64 default scenarios, separate opt-in profiles, their repeatability and the
areas that still need live acceptance.

## Check setup before troubleshooting

In IntelliJ, open **Settings → Tools → Reclazz → Check setup**. The read-only
check lists each result and its suggested fix: effective normal/debug agent
options, generated wrapper arguments, preserved debug flags, agent jar version,
server JDK and required flags, other agents and watched extension names. When an
agent is reachable it also shows the running version and a DOCTOR summary.
A stopped server is reported as skipped; it does not prevent the file checks.

After **Install Agent** or **Reinstall**, the success message appears immediately
and the same setup check runs automatically. A follow-up notification reports
its summary. A wrapper mismatch suggests `ant server`;
an unrelated properties-file edit alone does not require rebuilding. Apply the
suggested fixes and check again. The check does not install or repair anything.

## Why Hybris is different from Spring Boot

A Spring Boot app is one JVM you start yourself, so a build plugin or a plain
flag attaches the agent. A SAP Commerce server starts outside the IDE, through
the Tanuki wrapper, and its `tomcat/conf/wrapper.conf` is generated: any
`-javaagent` line added there by hand is wiped the next time `ant` regenerates
the config, with no error. The supported seam is the platform's own append
properties, `tomcat.javaoptions` and `tomcat.debugjavaoptions`, which are folded
into the generated wrapper config. Write there and the agent is put back by the
very regeneration that used to remove it.

## Enable it from the shell

1. Get the agent jar. It is on Maven Central as
   `com.onurkat.reclazz:reclazz-agent`; resolve or download
   `reclazz-agent-1.9.0.jar` and note its absolute path.

2. Append the agent to the last properties layer your project reads, commonly
   `${HYBRIS_HOME}/config/local.properties`. Append, do not replace:
   `tomcat.debugjavaoptions` normally carries the JDWP flags, and dropping those
   breaks debugging.

   ```properties
   tomcat.javaoptions=<your existing options> -javaagent:/abs/path/reclazz-agent.jar=platform=hybris,autoCompile=true
   tomcat.debugjavaoptions=<your existing debug options> -javaagent:/abs/path/reclazz-agent.jar=platform=hybris,autoCompile=true
   ```

   `platform=hybris` and `hybrisHome=` are optional under `hybrisserver.sh`: the
   agent detects SAP Commerce from its own `HYBRIS_BIN_DIR` startup property.
   Set `hybrisHome=` for a JVM started another way or an intentional override;
   a conflict warns but preserves that override. Reinstall omits it only from
   the managed Tanuki line; direct IDE launch and attach keep it. Other options
   remain startup defaults before an IDE connects and while it is absent.
   On JetBrains Runtime or DCEVM add
   `-XX:+AllowEnhancedClassRedefinition` after the flag for full structural
   reloads; on JDK 24 and newer add `--sun-misc-unsafe-memory-access=allow` so
   appending an enum constant keeps working (do not add it on a JDK older than
   23: the launcher refuses to start).

3. Regenerate the wrapper config and restart. This is not just a restart, the
   config has to be rebuilt from the properties first:

   ```bash
   cd ${HYBRIS_HOME}/bin/platform
   . ./setantenv.sh
   ant server
   ./hybrisserver.sh    # or hybrisserver.sh debug for tomcat.debugjavaoptions
   ```

The path in these lines is specific to this machine, so keep the change out of
version control (a per-developer config layer or an untracked `local.properties`).

## Spring Security XML access rules

For a successfully observed local security XML file containing one `security:http`
group, saving only its `intercept-url` access expressions can replace the served
chain without restarting SAP Commerce. The candidate is built in a child context
with the owning live context as parent; authentication services retain their
existing identities. The servlet keeps its original `FilterChainProxy`, and only
the exact owned chain slot changes. Other files' chains retain their order and
identity. A failed candidate or proxy publication keeps the previous policy.

Ownership comes from native namespace component events, not generated bean-name
suffixes. Unobserved files, multiple http groups per file, matcher/route changes,
authentication changes and other XML changes require restart. The focused native
proof uses stateless HTTP Basic, an ant matcher and expression authorization;
this does not claim OAuth, external identity-provider or method-security reload.

Live Spring bean definitions and the native chain-list bean remain as booted.
Consequently, `getBean` of a generated chain name still returns the boot object
until restart. Reclazz separately records the parse currently served by the proxy
so unchanged saves and repeated A→B→A edits compare with the active policy. The
child stays open while served and is closed after replacement or owner shutdown.

## Adding an ordinary XML bean with initialization

Append a new ordinary singleton bean to a Spring XML file already loaded by its
owning context. Its class must already be visible there. Spring's native
`getBean` performs `InitializingBean.afterPropertiesSet` and an XML `init-method`;
Reclazz does not invoke those callbacks itself. An identical save retains the
same initialized object. If native creation fails, its singleton and definition
are removed so a corrected save can retry. This does not undo arbitrary external
side effects performed by application callbacks.

BeanPostProcessor, BeanFactoryPostProcessor, FactoryBean, SmartLifecycle,
DisposableBean, destroy-method and non-plain construction remain unsupported for
this path. Event-listener initialization retains the separate restrictions below.
Existing lifecycle property edits retain their previous guards or recreation
path. This does not add delivery of new Java classes or expand the default live
suite. The focused SAP Commerce proof covers a precompiled InitializingBean and
an existing consumer's lookup in the captured master request context; init-method
is covered by focused Spring tests.

## Updating a simple XML configuration placeholder

A SAP Commerce property-file save can update an already-created plain singleton
whose XML scalar property is exactly `${key}`. The setter receives the current
native Config value, with Spring's existing scalar conversion; the bean and its
raw definition remain in place. A second change to the key updates the same bean.
Only unchanged, successfully captured local XML and its actual owners are used.

This path does not recreate beans, initialize lazy singletons or replay lifecycle,
FactoryBean, infrastructure or listener registrations. Constructor/factory values,
composite/default/nested placeholders, SpEL, collections and key deletion are outside
this path. A setter failure names the key and bean in restart guidance; Config may
already hold the new value. Native configuration acceptance is independent. A later
save with no changed property values retries eligible placeholders from that file
using native values, without storing pending keys. Setter side effects cannot be
undone and setters should tolerate another invocation. No default-suite expansion
is implied by the focused master-context proof.

## Adding a plain XML event listener while running

A newly added public class directly extending SAP Commerce's
`AbstractEventListener` can be registered by saving a Spring XML file that was
successfully loaded by the owning context. The class must already be visible to
that context. Add a singleton bean with explicit `tenantService` and
`clusterService` references to those existing native services. Spring performs
its ordinary context injection, inherited initialization and listener registration;
Reclazz does not add a second EventService registration. Saving the same XML
again retains the listener and delivers each fresh event once.

This path is deliberately limited to a class with an empty no-argument
constructor, no fields or extra callbacks, and only its `onEvent` implementation
(including the compiler's bridge). Custom constructors, lifecycle overrides,
init/destroy methods, extra properties, application advice, parent/factory
construction and changes to existing listeners retain restart guidance. A
processor that returns a proxy is refused; failed creation removes the exact
object registered by Spring, even if a later processor throws before `getBean`
returns. Listener removal/re-addition and dependency/lifecycle changes are outside
this path. Existing body-edit support is separate.

The opt-in SDK regression uses the installed SDK without changing a running
installation:

```bash
python3 scripts/test-sap-events-xml-sdk.py "$HYBRIS_HOME" --agent /abs/path/reclazz-agent.jar
```

Its tenant and cluster service proxies are isolated mechanics controls; real
native delivery requires a live tenant. The native experiment uses genuine
services and EventService delivery, with a restart-needing agent-off control and
an identical-second-save check. It does not add a default live-suite scenario.

## Persisted validation constraints after ImpEx

The ImpEx watcher compares the persisted count and latest modification time of
`AbstractConstraint` (including its subtypes) and `ConstraintGroup` before and
after a finished import without errors. When either changes, it reloads the
native validation engine once and reports why. Unrelated imports do not reload
it. This is part of the watcher behavior; there is no separate refresh flag.
The reload affects the service as a whole and is not atomic with concurrent
validation requests. A reload failure keeps the successful import result and
adds a pending validation-engine concern; a later successful reload clears that
concern, leaving other pending reasons intact.

- Explicit `modifiedtime` writes can hide a constraint change; reload the engine manually.
- Same-tick changes that preserve both count and maximum time can be missed.
- A native query/service failure reports that refresh was skipped; the data import can still succeed. Check native validation and reload manually if needed.

## The human one-click alternative

A developer in IntelliJ does not do any of the above by hand: with the Reclazz
plugin installed, **Tools > Reclazz** writes exactly these properties into the
right config layer (its own `99-reclazz.properties` when an optional config
directory exists), preserves existing values, adds the JDK flag only when the
server's JDK needs it, and reminds you to run `ant server`. The shell recipe
above is for an agent or a headless environment.

## Verify it attached, and the edit-run loop

The agent writes its status port to `${HYBRIS_HOME}/.reclazz/agent.port` once the
server is up. Check it machine-readably with the
[MCP server](mcp-server.md), pointing it at the Hybris home:

- `reclazz_status` with `hybrisHome` set reports attached, agent version and
  health.
- After `ant build`, `reclazz_scan` nudges a reload instead of waiting for the
  next poll; `reclazz_pending` lists what still needs a restart.

The loop on Hybris:

1. Edit a `.java` file in an extension.
2. `ant build` (or let the IDE compile). The agent hot-swaps the changed classes.
3. Exercise the endpoint or backoffice. Only restart for what `reclazz_pending`
   reports (a changed superclass, a reordered enum).

Java sources held by a compilation failure are tried once more after successful
beans.xml/items.xml codegen admits its generated classes. Saving the Java and XML
files together needs no second Java save; a failed retry stays held until another
save or successful codegen.

Hybris-specific saves also reload without a restart: `*-items.xml` and
`*-beans.xml` regenerate and reload their models and DTOs (`autoCompile=true`),
changed properties and log levels reach the running server, `.impex` files import
on save with `autoImpex=true` (a REMOVE header is refused), and interceptor
registrations and backoffice labels are restored. A new items.xml attribute still
needs its database column: by default Reclazz prints a reminder to run
**HAC > Update Running System**, and with `autoUpdateRunningSystem=true` it runs that
schema-only update itself (add columns, no data, never drop) so the attribute is live
without the manual click. That option runs DDL on the live database, so it is opt-in and
for development only. With it, a saved locales file also writes the names it keys onto
the types (what Backoffice shows), only those keys rather than the whole "Localize
types" step. A property added to a WsDTO shows in OCC responses after the regen.

Business-process actions (`AbstractProceduralAction`, `AbstractSimpleDecisionAction`)
are prototype-scoped beans the process engine resolves at each transition, so editing an
action body takes effect on the next step with no restart: the next resolution is a fresh
instance of the reloaded class. The same holds for a cronjob performable on its next run.

## Drop-in AGENTS.md recipe

Paste this into the Hybris project's `AGENTS.md` so the agent that works on it
keeps using hot reload. Replace the paths with the project's own.

```markdown
## Hot reload with Reclazz (SAP Commerce)

This project runs its SAP Commerce server with the Reclazz agent attached
(wired into tomcat.javaoptions with autoCompile=true), so a running server
picks up changes without a restart. Prefer this loop over restarting: a
restart costs minutes. The reclazz MCP server is configured with
--project-dir ${HYBRIS_HOME} (its .reclazz/agent.port is found automatically).

Apply a Java change while the server runs (autoCompile=true, no ant):
1. Call reclazz_await_reload with action checkpoint. Keep the returned
   sessionId, subscriptionId and cursor. Do this before saving.
2. Edit and save the source file in the extension.
3. Call reclazz_await_reload with action wait, the three values from step 1
   as sessionId, subscriptionId and afterCursor, the absolute .java path as
   filePath and the class's binary name as ownerClassName.
4. Act on the status:
   - applied: the exact compiled bytes were loaded. Exercise the endpoint or
     run the test; applied alone does not prove behavior.
   - compile_error: read the diagnostics (file and line), fix the code and go
     back to step 1. The server keeps running the last good code.
   - held: automatic reload is paused or a BUILD is open; finish or release it,
     then wait again with the same checkpoint.
   - restart_required or refused: the change cannot be applied live; tell the
     user and check reclazz_pending.
   - timeout or unavailable: not a success. Check reclazz_status and
     reclazz_diagnose with className before retrying.
5. Do not restart the server for anything that reached applied.

If you must compile with ant instead:
1. Call reclazz_build with state started and a unique owner token; require
   it to be acknowledged before compiling.
2. Run `cd ${HYBRIS_HOME}/bin/platform && ant build`.
3. Call reclazz_build with the same owner and state ok on exit zero, or state
   failed otherwise. Classes from a failed or half-finished build are not
   loaded.
4. Confirm each changed class with reclazz_verify (className and the
   lower-case SHA-256 of its compiled .class file); only status applied
   counts. Then exercise the endpoint or test.

For *-items.xml / *-beans.xml changes the agent regenerates and reloads the
models; a genuinely new attribute needs HAC > Update Running System for its
column. Restart only for what reclazz_pending reports (changed superclass,
reordered enum). If the agent is not attached, it was added to
tomcat.javaoptions and needs `ant server` then a server restart to take effect.
```


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
to load a nonexistent class: SAP Commerce's enabled `CacheClassNotFoundRule` remembers
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
is `relationParent`; `owner` is inherited from SAP Commerce's base Item and is not a new
relation attribute. The many-to-many relation has its own deployment31995.

Run `scripts/test-sap-items-live.py --new-relation --runs 1` with the same explicit
`--fixture-manifest`, `--restore-repo` and `--lock` arguments as the new-item-type
mode. Set `RECLAZZ_SAP_HOME` and `RECLAZZ_SAP_BASE` to the disposable installation.
The eight-root stopped recovery is shared: database, generated sources/classes,
config and native outputs, original agent and extension links must all recover,
then experimental types/tables/resources must be absent after a fresh normal
boot. An inner PASS alone cannot pass the outer profile. Never run this inner
profile directly or undo schema changes live. Future classes are inspected by
resource lookup before generation to avoid seeding SAP Commerce's negative class cache.

This opt-in proof covers existing behavior and leaves default suite and normal
fixture membership unchanged. It does not certify arbitrary, localized, ordered,
cascading or incompatible relation edits. No production agent change or new
dependency is involved. Source membership comes from the shared suite contract;
do not copy a historical fixture/test count into new profile checks.

## Existing hot-folder converter references

Changing the `converter` reference of an existing `DefaultConverterMapping` in
captured Spring XML refreshes the native `ImpexTransformerTask` cache for that
owner. Both referenced `DefaultImpexConverter` beans must already be plain local
singletons, with an unchanged literal mapping prefix. Reclazz initializes a
detached native task and publishes its completed cache to the held task, retaining
the task and any static `SingletonTargetSource` proxy. Failed preparation restores
that owner's previous reference and cache; it does not retry initialization on the
live task.

The focused live proof used real native file ingestion, conversion, import and
cleanup: the reference edit produced the B row once, and an identical save followed
by another fresh file also produced B once in the same JVM. Without the agent,
the same edit kept using A. This proof is opt-in and does not expand the default
suite or certify the whole hot-folder configuration surface.

New/removed mappings, prefix edits, custom or lazy mappings, proxy converters,
dynamic task proxies and unverifiable collectors require a restart. This does
not reprocess a file already in flight or provide a transaction across tenants.
The local regression `scripts/test-sap-hotfolder-sdk.py` uses installed SDK sources
and proves native converter selection and row output; its standalone JVM does not
prove tenant activation, CSV import or database persistence.

## Existing file inbound adapter regex and rate

In captured Spring XML, an existing explicit-id standard
`file:inbound-channel-adapter` can accept literal `filename-regex` and positive
millisecond `fixed-rate` edits. Reclazz prepares the new pattern and trigger,
stops that adapter, updates the native objects and restarts it only if it was
running. The source, scanner, composite filter and `AcceptOnce` history remain
in place, so an already accepted file left in the input directory is not delivered
again. Failed preparation leaves the old adapter untouched; a failed start restores
the old pattern, trigger, raw settings and running state.

This boundary requires the standard default file scanner/filter chain, no custom
poller settings, and `max-messages-per-poll="1"`. Directory, filename-pattern,
fixed-delay, initial-delay, custom source/filter/scanner/scheduler, executor,
advice/transaction settings, added/removed adapters and nonliteral settings still
require restart. Generated filter/trigger names are matched through the explicit
adapter's own references, including when other XML files were loaded first.
There is no promise to reverse a file delivery already in flight or to reprocess
queued files. The default live suite and fixture membership are unchanged.

The focused SAP Commerce proof measured the two edits separately in the same JVM:
after changing A to B, fresh A/B files yielded counts `[0, 1]`, and the fixed-rate
edit changed receipt gaps from about 2000 ms to 497–499 ms. The agent-off control
kept A and about 2000 ms. The complete stopped twelve-root setup recovered and
normal master/junit health passed. This proof used the literal A/B patterns;
inline regex flags are covered by the local real-Spring regression, not a further
live retry.
