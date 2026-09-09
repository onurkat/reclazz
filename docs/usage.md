# Usage Guide

Reclazz supports four modes of operation. You can use them independently or combine them depending on your workflow.

---

## Option 1: Manual Setup

Use this option when you run the SAP Commerce server **outside of IntelliJ** (e.g., from the terminal with `./hybrisserver.sh`) or when you want full control over JVM arguments.

### How It Works

You add the `-javaagent` flag to your Hybris Tomcat configuration. The agent loads inside the SAP Commerce JVM, watches for compiled `.class` files, and hot-swaps them when you run `ant build`.

### Setup

Edit `hybris/config/local.properties` and add the agent to `tomcat.generaloptions` or `tomcat.debugjavaoptions`:

**Any JDK 17+ (works for structural reloads too):**

```properties
tomcat.generaloptions=-javaagent:/path/to/reclazz-agent.jar=hybrisHome=/path/to/hybris ${tomcat.generaloptions}
```

Structural hot-reload (adding/removing methods and fields) works on any
JDK 17+ via Reclazz's companion-class reloader — no extra JVM flags
required. On standard JVMs, the new members live on a hidden nestmate
and are reached through `invokedynamic` routed from hot-compiled
callers; reflective access (`Class.getMethod(...)`, `ModelService`) is
limited to members that existed at boot time. See
[Companion-class caveat](#companion-class-reflective-visibility-caveat)
below for details.

**JetBrains Runtime / DCEVM (full reflective visibility):**

```properties
tomcat.generaloptions=-javaagent:/path/to/reclazz-agent.jar=hybrisHome=/path/to/hybris -XX:+AllowEnhancedClassRedefinition ${tomcat.generaloptions}
```

Enhanced redefinition applies structural changes to the original
`Class` object itself, so reflection and reflective caches see new
members without restart.

> **Tip:** If you already have debug options configured, you can append the agent to `tomcat.debugjavaoptions` instead. This property is applied when you start the server in debug mode:
>
> ```properties
> tomcat.debugjavaoptions=-javaagent:/path/to/reclazz-agent.jar=hybrisHome=/path/to/hybris -Xdebug -Xnoagent -Xrunjdwp:transport=dt_socket,server=y,address=8000,suspend=n
> ```

### Finding the Agent JAR Path

The location of `reclazz-agent.jar` depends on how you installed Reclazz:

- **Installed via IntelliJ plugin:** The agent JAR is bundled inside the plugin directory. You can find it at:

  ```
  # macOS
  ~/Library/Application Support/JetBrains/IntelliJIdea<version>/plugins/reclazz/agent/reclazz-agent.jar

  # Linux
  ~/.local/share/JetBrains/IntelliJIdea<version>/plugins/reclazz/agent/reclazz-agent.jar

  # Windows
  %APPDATA%\JetBrains\IntelliJIdea<version>\plugins\reclazz\agent\reclazz-agent.jar
  ```

- **Downloaded from GitHub:** Use the absolute path wherever you saved the JAR.

- **Built from source:** Use the absolute path to `agent/build/libs/reclazz-agent.jar`.

### Agent Arguments

Arguments are passed as a comma-separated string after the `=` sign:

```properties
-javaagent:/path/to/reclazz-agent.jar=hybrisHome=/opt/hybris,watchExtensions=mycore;mystorefront,autoImpex=true,verbose=true
```

| Argument | Default | Description |
|---|---|---|
| `hybrisHome` | auto-detect | Absolute path to the `hybris/` directory |
| `watchExtensions` | all custom | Semicolon-separated extension names to watch (empty = all) |
| `excludePatterns` | (none) | Semicolon-separated glob patterns to exclude (e.g., `*Test.class;*Mock*`) |
| `autoCompile` | `false` | Compile `.java` files internally instead of watching `.class` files |
| `autoImpex` | `false` | Auto-import changed `.impex` files |
| `debounceMs` | `500` | Milliseconds to wait before processing changes (batches rapid file writes) |
| `startupDelaySec` | `30` | Seconds to wait after agent startup before watching files |
| `verbose` | `false` | Enable verbose logging in the console, including each framework step's own line after a reload (the reload line carries the summary either way) |
| `statusPort` | `0` | TCP port for plugin communication (0 = auto-assign) |
| `portFile` | (none) | Path where agent writes its actual port after binding |
| `watchDirs` | auto-detect | Semicolon-separated class output directories to watch, for a project the detection does not know |
| `excludeClasses` | (none) | Semicolon-separated class name patterns the transform leaves alone; the way out when instrumenting one class is the problem |
| `impexAllowRemove` | `false` | Let auto-imported ImpEx files run `REMOVE` lines |
| `jpaRefresh` | `false` | Rebuild the persistence unit when an entity gains a field or a new entity appears (JBR/DCEVM, `ddl-auto` at update/create) |
| `structuralReload` | `true` | The companion engine that adds and removes members on a stock JDK; `false` leaves method-body reloads only |
| `platform` | `auto` | Skip detection and name the platform (`hybris`, `spring`, `generic`) |
| `wrapOutput` | `auto` | Wrap console lines to the terminal width: `auto`, `true`, `false` |
| `transformDumpDir` | (none) | Write every transformed class file here, for looking at what the agent emitted |
| `verifyTransform` | `false` | Run the bytecode verifier over every transformed class and print what it says |
| `sessionLog` | (none) | Append every status line to this file with an ISO timestamp and level, no colour: the session's record, to read back or attach to a report |
| `reloadBoundary` | `immediate` | `request` waits for synchronous Spring MVC dispatches to finish before applying a class reload batch; requires `-javaagent` at JVM startup. See [Reload between requests](#reload-between-requests) |

Arguments are never removed or renamed within a major version: a line that
worked with an older 1.x agent works with a newer one. An argument the agent
does not know is named in the console at start-up and ignored, not fatal, so a
line written for a newer agent still starts an older one. Put such arguments
first: the line is split at a comma only where a known argument follows, so
that a value may contain a comma, which means an unknown argument placed after
another becomes part of that one's value (and is still named). The plugin's own
test (`AgentArgumentContractTest`) keeps what it passes inside this table, and
the agent's (`AgentArgumentsAreDocumentedTest`) keeps this table equal to what
the agent accepts.

### Bean methods added after startup

On a stock JDK with the agent attached at startup, add a factory to an existing
configuration class, compile, and look up the new singleton through Spring:

```java
@Configuration(proxyBeanMethods = false)
public class ClientConfiguration {
    // Add this method after the application has started:
    @Bean
    public Client client(Transport transport) {
        return new Client(transport);
    }
}
```

The new method lives in the companion. A hidden supplier calls its current body
on the current configuration singleton, and a Spring bean definition creates the
product. Spring applies dependency injection, bean postprocessors and lifecycle
callbacks. A later save recreates the added product, including body-only edits.
Removing the method or its `@Bean` annotation removes its owned definition.
Changing its name removes the previous owned name and registers the new one.
Closing the context runs normal product destruction. Repeated saves leave one
registration per supported name.

Definitions from the same save are registered before products are initialized,
so an added product can inject another added bean regardless of declaration order,
including through factory parameters. Only contexts containing the edited
configuration participate.

Factory parameters are required reference beans. Spring selects candidates by
type, honors primary candidates and direct parameter `@Qualifier("beanName")`,
and uses the original parameter name to break ties when javac preserved it with
`-parameters` or debug local-variable information. Without a preserved name, no
name is invented: unique type/primary/qualifier selection still works, and an
unresolved ambiguity is reported. Parent-context candidates and dependency
proxies are passed through as the objects Spring resolves.

Arguments are resolved on every product creation. Dependency names are registered
with the creating bean factory so its normal dependency destruction applies,
including for dependencies that were already cached singletons. If an argument
is missing, ambiguous or resolves to null, the factory body is not called and
its failed owned definition is removed. The message identifies the parameter
and Spring's failure. Fix the dependency or qualifier and compile the
configuration again to recover; registering a missing dependency alone does not
restore a definition removed after a failed reload.

Supported factories carry direct `@Bean`, return an object
(not a primitive, array or `void`), and have no generic signature. Private instance
methods work; static, native and abstract methods do not. The class must carry
direct `@Configuration(proxyBeanMethods=false)`, extend only `Object`, implement
no interfaces and have exactly one local, unproxied singleton configuration in
each affected bean factory. Extra runtime class/method annotations are limited to
`@Deprecated`. Conditions, profiles, scopes, advice, lazy/primary/qualifier metadata,
composed annotations and proxied configurations require a restart.
The parameter-level `@Qualifier` described above is the exception. Primitive,
array, collection/map, optional, stream and provider parameters are unsupported,
as are generic signatures and extra runtime parameter/type annotations such as
`@Value`, `@Lazy`, nullable annotations and composed qualifiers. Parameter names
come from the saved bytecode; custom name-discovery policies and custom lazy
resolution hooks are not used by this path. No-argument factories remain supported.

The default method name or one explicit `name`/`value` is supported, along with
`initMethod` and `destroyMethod`. The default inferred `close`/`shutdown` destruction
and an explicit empty destroy method are preserved. Multiple aliases and other
explicit `@Bean` options are refused. `FactoryBean`, bean postprocessors and bean
factory postprocessors cannot be introduced through this path, including when
hidden behind an `Object` return. Null results are refused.

An existing definition, singleton, alias or parent bean blocks the new name.
Duplicate supported added names are refused. Before removal, Reclazz checks that
the definition and any live singleton still belong to its registration; externally
replaced registrations are left alone. Registration is not a transaction with
other threads concurrently changing the Spring registry. A product recreated by
Spring from the owned definition remains reloadable, including a product wrapped
by the application's existing bean postprocessors. Runtime changes to the
postprocessor chain are outside this support.

Factories are created eagerly. Replacement/removal destroys the old product and
may destroy its dependents through Spring. Existing holders, collections and local
variables are not globally rewired by this feature; retrieve the current product
from the context. Factory or initialization failure is reported and the failed
owned definition is removed. The previous product may already have been destroyed,
and callback side effects are not rolled back. Correct the factory and compile
again to recover. The ordinary configuration-bean refresh still runs before this
registration step, with its existing lifecycle effects. This feature does not
rerun configuration parsing or add `@Bean` to methods that existed at startup.

### Scheduled methods added after startup

With the agent attached at startup, add this method to an existing, unproxied
singleton `@Service` or `@Component`, compile, and it starts running without a
restart. Scheduling must already be enabled in the application, for example
with `@EnableScheduling`.

```java
@Scheduled(fixedDelayString = "${cleanup.delay:1000}")
public void cleanup() {
    cache.clear();
}
```

On a stock JDK the new method lives in a companion and reflection on the
original class cannot see it. Reclazz supplies Spring with a hidden adapter
carrying the scheduling annotation and delegating to the current implementation.
Spring resolves the timing attributes. Each successful save replaces the
previous adapter registration. Each invocation reads the current singleton, so
replacing that bean through a dependency refresh does not leave the task calling
a destroyed instance. Invocations skip a temporarily absent bean without
creating it. Removing
the method or its scheduling annotation cancels that registration. Closing the
Spring context cancels it too. Reload does not pause background scheduling
while bytecode and Spring metadata are updated. Cancellation takes effect
during scheduling re-registration; callbacks that start before cancellation
may finish. Scheduled work is outside the MVC request reload boundary.

The added-method path supports direct `@Scheduled` and repeated `@Scheduled`
declarations (`@Schedules`) on no-argument, `void` instance methods, including
private methods, on singleton classes recognized by the agent's Spring
stereotype detection (`@Service`, `@Component`, and the other directly supported
Spring stereotypes). It also works when the application had no scheduled tasks
at startup. The existing scheduler, unrelated tasks and scheduling configuration
are retained.

Static methods, parameters, non-void/reactive return types, proxies/subclass
instances and additional runtime method annotations (other than `@Deprecated`)
are reported as unsupported for the added-method path. In particular, Reclazz
does not schedule an added method while silently bypassing its advice.
Custom composed scheduling annotations and classes registered only through
XML or `@Bean` without a recognized class stereotype are outside this support.
Existing methods still use the ordinary Spring scheduling path.

If registration fails, for example because of invalid cron syntax, the previous
adapter is cancelled and any partially registered replacement tasks are
cancelled too. The failure is reported; the previous schedule is not restored.
Correct the declaration and compile again to retry.

### Event listener methods added after startup

With the agent attached at startup, add a listener to an existing, unproxied
singleton `@Service` or `@Component` and compile. It can be the class's first
listener. Spring's event listener processor must already be present (as in an
annotation-configured Spring context).

```java
@EventListener(condition = "#a0.priority > 5")
@Order(10)
public void onOrder(OrderCreated event) {
    notifications.send(event);
}
```

The added method receives matching events without a restart. Spring selects
the event type, unwraps payload events, evaluates `condition`, and applies
`@Order`. `classes`/`value` and explicit listener `id` attributes are carried
across. Indexed conditions (`#a0`, `#p0`) work without compiler parameter names;
named parameters also work when the class file includes `-parameters` or debug
local variable metadata.

Saving again replaces the added registrations. Removing the method or its
annotation stops it from receiving subsequent publications after reload has
completed. Refreshing existing listeners scans only the affected beans;
other beans and manually registered listeners keep their registrations.
Multiple singleton instances and multiple contexts are handled separately.
Each invocation reads the current singleton, so dependency refreshes can replace
the bean without leaving its listener on a destroyed instance. An absent bean
is skipped without being created; a replacement proxy is skipped and reported.

The added-method scope is a direct `@EventListener` on a `void` instance method,
including a private method, with exactly one reference event parameter and no
generic method signature. The bean class must carry a Spring stereotype
recognized by Reclazz. Extra runtime method annotations are limited to
`@Order` and `@Deprecated`. Added `@Async` and `@TransactionalEventListener`
methods, composed listener annotations, generic signatures, proxies/subclasses,
prototype beans, and classes registered only through XML or `@Bean` without a
recognized stereotype require a restart. The added path requires the default
Spring event listener factory; contexts with custom or transactional listener
factories are reported as unsupported for additions. Existing reflected
listeners continue to use the application's factories.

Event delivery is not paused during reload. A callback already selected by the
multicaster can finish; the MVC request boundary does not cover event publication
from other threads. If registration fails, previous added registrations and any
partially registered replacements for that class are removed in the affected
context. There is no rollback to the previous listener declaration. Correct the
declaration and compile again. Spring expression syntax and user exceptions
remain subject to Spring's normal event delivery behavior.

This path is runtime-tested on Spring 5.3.39 with JDK 21. Spring 6 compatibility
has source-level checks but has not been verified in a running application.

### Property changes keep the last working values

For non-SAP Spring applications, Reclazz checks a saved `.properties` change
before putting it into the running Environment. It binds affected
`@ConfigurationProperties` beans on separate objects and resolves and converts
direct `@Value` fields and constructor parameters, including the supported
[computed field expressions](#computed-value-fields). A save containing both a
valid service address and an invalid timeout is held as one candidate. The
Environment, property beans, direct fields and logger levels keep their old
values. Fix the timeout and save again: both pending keys are retried.

The check uses the application's Boot binding code with separate property
sources and targets. Boot's validation fallback, a named
`configurationPropertiesValidator`, self-validating records, bind-handler
advisors, conversion services, property editors and the annotation's ignore
flags apply. In particular, `ignoreInvalidFields=true` still permits values
Boot chooses to ignore. The check does not write Boot's live bound-property
records. The integration suite uses Boot 2.7.18. A separate probe against
Boot 3.3.4 also verified Jakarta validation, record binding, live tracking
isolation and successful application.

No new option is needed. With `reloadBoundary=request`, successful changes
wait for the existing request boundary before application. A save made during
that wait becomes another candidate; accepting the earlier candidate never
reads the newer file or its logger levels. An interrupted wait accepts nothing.

The log distinguishes these outcomes:

| Outcome | Running values | File baseline |
|---|---|---|
| Rejected | Kept because binding, conversion or validation failed | Pending |
| Uncheckable | Kept because the binding check could not run | Pending |
| Wait interrupted | Kept because application never ran | Pending |
| Applied | Updated through the existing rebind path | Accepted candidate |
| Partially applied | Some values may already have changed; failures are named | Pending for another save |

Limits: this is a validation step before application, not rollback for arbitrary
application code. Constructors, setters, converters, validators and advisors
can have side effects; Reclazz cannot isolate state they keep themselves.
If a setter or bean rebuild fails after a clean check, changes already made
are not rolled back. Request isolation covers the synchronous MVC scope of
[the existing boundary](#reload-between-requests); background readers are not
paused. Connection pools still have their existing limitations.

A JavaBean target needs a usable no-argument constructor. Constructor-bound
targets use Boot's constructor binding. Missing or incompatible Boot internals
hold the change as uncheckable. Computed `@Value` constructors use the
[restricted check below](#computed-value-constructor-parameters). YAML, key
removal and SAP Commerce's `Config` path retain their existing behavior and
are outside this check.
Only added or changed keys are applied. A syntactically valid truncated file
cannot be distinguished from an intentional save; malformed or unreadable
files do not advance the baseline.

### Computed @Value fields

An existing singleton field can compute a value from changed properties:

```java
@Value("#{${timeout.seconds:5} * 1000L}")
private long timeoutMillis;

@Value("#{${retry.count:2} > 3 ? 'extended' : 'normal'}")
private String retryMode;
```

Save the `.properties` file and the field is recalculated without recreating
its bean. The expression is checked against the candidate property values
before any live property is changed. Syntax errors, integer division by zero,
non-finite numeric results and failed type conversions reject the whole
candidate, including its other keys and logger levels. Fix the value and save
again to retry the pending keys.

The supported subset is one whole `#{...}` expression on a writable instance
field of primitive, boxed primitive or `String` type. A changed key can be
referenced directly or through a [property-value chain](#indirect-value-dependencies);
placeholder defaults and nested placeholders are resolved. Expressions can use numeric, string, boolean and null literals,
`+`, `-`, `*`, `/`, `%`, comparisons, boolean operators, `?:` conditionals and
Elvis defaults. Arithmetic follows Spring's operator semantics (including its
integer arithmetic); this is not an overflow checker. The bean factory's
conversion service is used inside expressions, and its type converter performs
the final field conversion.

Bean references, property/method access, `T(...)`, `new`, variables, assignment,
collections, regex and mixed text/expression templates are unsupported. Every
AST branch is checked before evaluation, even a branch that would not execute.
An affected unsupported field holds the whole candidate as **Uncheckable** and
names the field and reason. It is not evaluated through the application's bean
expression resolver. Custom resolver/parser implementations and non-default
expression delimiters are also held. Resolved expressions and string results
are limited to 2048 characters, with at most 256 AST nodes and depth 32.

Computed constructor parameters have their own [rebuild scope](#computed-value-constructor-parameters).
Static/final fields and non-scalar field types are outside the field support.
Existing placeholder resolver ordering still applies during live reinjection;
the precheck uses the
candidate Environment. As with ordinary property rebinding, custom converters
may have side effects, and a failure during live application is reported as
partial rather than rolled back. Background readers are not paused.

Verified with Spring 5.3.39 / Boot 2.7.18 on JDK 21, including a real agent JVM
watching property saves. Spring 6, JDK 17 and live SAP runtime tests have not
been run for this feature.

### Computed @Value constructor parameters

A constructor argument can use the same scalar arithmetic/conditional subset:

```java
@Component
public class Client {
    private final long timeoutMillis;

    public Client(@Value("#{${timeout.seconds:5} * 1000L}") long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }
}
```

Saving `timeout.seconds=8` rebuilds this singleton with `timeoutMillis=8000`.
Before destroying anything, Reclazz resolves and checks every `@Value` argument
on the affected constructor, including unchanged arguments that recreation will
re-evaluate. A direct placeholder that resolves to `#{...}` is checked too.
The precheck evaluates values and conversions; it does not invoke the constructor.
A syntax, arithmetic or conversion error rejects the whole candidate. An
unsupported constructor expression or creation policy holds it as **Uncheckable**
and identifies the bean and constructor parameter. A corrected save retries the
pending keys. Unrelated property saves do not recreate the bean.

This support requires an existing, unproxied singleton, one declared constructor,
and a bean definition that directly constructs that class. Spring's cached
constructor and re-resolvable arguments must be readable and agree with that
constructor. Factory methods (including `@Bean`), instance suppliers, manually
registered singletons, explicit constructor arguments, method overrides and
`@ConfigurationProperties` beans are outside this new path. Non-singleton beans
are not swept. Ordinary injected bean dependencies may accompany the scalar
`@Value` parameters. The expression operators, resolver restrictions and limits
are the same as for [computed fields](#computed-value-fields).

The instance is replaced: its local state resets, and Spring runs destruction,
construction, injection and initialization callbacks. Existing writable reference
fields in surviving singletons in the same context are re-pointed to the new
instance. Spring may destroy dependent beans too; retrieving them again constructs
them with the new dependency. References held by application locals, collections,
other contexts or already-destroyed holders are not guaranteed to change.

This is not a dry run of the bean's lifecycle. Ordinary field/method injection and
application callbacks still run during successful recreation. Custom converters
can run application code during checking too. If construction or initialization
fails after the precheck, the outcome is **Partial**: the Environment may already
have changed, the old bean may already be destroyed, and there is no rollback.
The file remains pending. Background readers are not paused. Existing placeholder
resolver ordering still applies; [indirect dependencies](#indirect-value-dependencies)
are followed within the scope below.

Verified on Spring 5.3.39 / Boot 2.7.18 test dependencies and JDK 21, including a
real agent JVM watching saves, rejection, recovery and holder replacement.
Spring 6, JDK 17 and live SAP runtime tests have not been run for this feature.

### Indirect @Value dependencies

Properties can refer to other properties:

```properties
shared.timeout=5
client.timeout=${shared.timeout}
```

```java
@Value("#{${client.timeout} * 1000L}")
private long timeoutMillis;
```

Saving only `shared.timeout=8` updates this field to `8000`. The same dependency
selection applies to ordinary `${...}` fields and constructor parameters, and to
supported computed constructor parameters. Fields are re-injected and constructor
beans are rebuilt under their existing scope and lifecycle rules.

Reclazz follows the placeholder reads through the candidate property sources,
using Spring's placeholder helper and source priority. Several alias hops,
nested keys such as `${route.${profile}.timeout}`, and placeholder defaults are
supported. Sources need not enumerate their keys. A higher-priority literal masks
a lower-priority alias. Retarget an alias to another key and later saves follow
the new chain. Alias values remain `${...}` in their source; only the actual saved
keys are written to the Environment and accepted into the file baseline.

Tracing stops as soon as it reaches a changed key. The selected target then goes
through the existing candidate check: invalid arithmetic, conversion, or a newly
introduced circular reference rejects the whole save before live writes. If a
trace cannot determine whether a target is affected (for example an already-cyclic
source or an unreadable source), the save is **Uncheckable**. Fixing the candidate
allows it to be retried. An unrelated cycle elsewhere is not scanned.

Indirect tracing allows at most 8192 characters per input/raw value, 128 placeholder
occurrences across the traversal, and 256 lookups per expression. Exceeding a trace
limit holds the candidate as uncheckable. These are dependency-selection limits;
the existing expression checks still apply after a target is selected. Results
are cached only within a selection pass, up to 1024 expressions, and are discarded
after that pass. Direct references retain their conservative matching: a mentioned
fallback key may select a target even when the resulting value stays the same.

This tracks `@Value` dependencies through the candidate Environment's default
`${...}`/colon syntax. It does not add indirect `@ConfigurationProperties` binding,
custom placeholder-language support, arbitrary SpEL, YAML/key removal or SAP
Commerce `Config` support. Custom embedded resolvers may use different sources or
ordering during live injection; that existing precheck/live limitation remains.
Application property-source lookups and conversions can have side effects. Mutable
sources changed independently of Reclazz are not frozen across the request boundary.
Successful constructor recreation retains its existing state-reset and reference
limits; failures during live application remain partial without rollback.

Verified with Spring 5.3.39 / Boot 2.7.18 test dependencies on JDK 21, including
watched saves, rejection and recovery in a real agent JVM. Spring 6, JDK 17 and a
live SAP runtime have not been run for this feature.

### Cache dependencies after reload

Spring Cache observation is automatic when the companion engine instruments
application methods. No agent argument is needed. If `PriceService` caches
its result in `prices` and calls an unannotated `DiscountRules` helper,
reloading the helper invalidates `prices`. An unrelated `descriptions` cache
stays populated. This works for externally compiled classes and AutoCompile.

The agent records the watched classes executed inside Spring's synchronous
cache interceptor and the actual cache instances involved. If a cached
`quotes` calculation reads an already cached `prices` result, their dependency
is recorded too: changing the price helper invalidates both regions. Equal
cache names in separate managers or application contexts are distinct, as are
classes with the same name in different classloaders. Caches supplied directly
by a `CacheResolver` are included. This is region-level eviction, not per-key
invalidation. Dependencies are conservative unions: a dependency that stops
being used can still cause eviction until its class or cache is collected.

A cache operation overlapping a class mutation is conservatively invalidated
when its outermost synchronous interceptor returns. Its caller can still get
the result calculated with old code, but that result is not retained for the
next call after the invalidation succeeds. The interval between Spring storing
a value and the interceptor exiting is not isolated: another thread can read
that value, and thread scheduling can extend this interval. Nested operations
defer their invalidation until the outermost exit so an inner exit does not
clear caches while an outer synchronous cache loader still holds its lock.
Normal and exceptional exits release observation. Failed clears are reported,
do not replace the application's result or exception, and retain dependencies
for another reload to retry. Operations overlapping unrelated reloads may also
be cleared conservatively.

Attach mode and any cache-hook failure mark observation incomplete. For an
annotated class the agent then retains its existing fallback of clearing all
manager caches; known resolver-only caches are also cleared. An unannotated
helper invalidates only its observed dependents, even with incomplete history.
Without observations, an annotated class keeps the same fallback. Tracking
stops growing at 512 cache instances or 8192 class/dependency links and marks
coverage incomplete for the session; existing dependency records remain usable.
Persistent class and cache identities are weakly held.

Limits: native JBR/DCEVM redefinition does not use the method instrumentation
and therefore keeps the annotation fallback. Constructors, class initializers,
excluded methods/classes, unwatched libraries, cache access outside Spring's
interceptor, pre-attach entries and asynchronous/reactive computations do not
have complete dependency coverage. The synchronous paths, including
`@Cacheable(sync=true)`, are tested against Spring 5.3.39. The hook matches the
Spring 6 signatures as well, but this change does not add a real Spring 6 E2E
matrix. A watched method pays a volatile activity check even when no cache
operation is open; during observation it also records a class identity.
`verbose=true` names targeted evictions and computations crossing reloads.

### Reload between requests

Add `reloadBoundary=request` to the agent arguments to keep a synchronous
Spring MVC request from crossing a class reload. For example:

```bash
java -javaagent:/path/to/reclazz-agent.jar=reloadBoundary=request -jar app.jar
```

If a request calls a pricing method, pauses, and calls a discount method,
an ordinary reload can put the first call on the old code and the second
on the new code. With this option, the request finishes before the reload
starts. New requests wait while the class batch and its framework follow-up
run, then enter against the updated code. The default, `immediate`, keeps
the existing reload behavior and installs no request hook.

The agent waits up to one second for active dispatches to drain. If a long
request or a breakpoint outlasts that deadline, it prints
`Request boundary deferred`, reopens admission, and retains the queued edit.
It then waits for a natural idle boundary instead of repeatedly stopping new
requests. The next safe opportunity retries automatically, without another
save. New edits remain queued on the same reload thread. The one-second
limit covers draining requests, not applying the reload; a slow framework
follow-up can keep new requests waiting longer. After thirty seconds,
`HEALTH` reports the waiting work through the existing reload status;
`sessionLog` records deferrals immediately.

The boundary is the execution of Spring MVC's `FrameworkServlet.processRequest`
in any application classloader in this JVM. It supports the `javax.servlet`
and `jakarta.servlet` signatures. Nested forwards/includes on the same thread
stay within the outer boundary. Normal returns and exceptions both release it.
AutoCompile still compiles outside the boundary and applies its compiled
batch inside it.

The guarantee covers successful method-body changes during synchronous MVC
dispatches. Servlet filters before or after that dispatch, asynchronous MVC
work, WebFlux, scheduled jobs and other background threads are outside it.
Resource/configuration reloads are also outside this boundary. It is not a
database snapshot or an all-or-nothing deployment: structural changes and a
partially failed batch retain the existing reload limitations.

Use this option at JVM startup. Attach mode, missing bootstrap support, or
Spring MVC already loaded by an earlier agent refuses initialization rather
than silently running without protection. A missing or failed MVC hook keeps
class edits deferred. A non-MVC application therefore should use `immediate`.
An invalid `reloadBoundary` value refuses initialization. Application startup
itself is not aborted.

### Workflow

```
1. Start the SAP Commerce server (with agent in JVM args)
   └── Reclazz banner appears in the console output

2. Edit your Java source files in IntelliJ or any editor

3. Compile with ant:
   cd hybris/bin/platform
   ant build

4. Reclazz detects the new .class files and hot-swaps them instantly
   └── "Hot-swapped: com.example.MyService (12ms)" appears in the console

5. Test your changes — no server restart needed
```

---

## Option 2: IntelliJ Automatic Mode (Recommended)

Use this option when you run the SAP Commerce server **from IntelliJ IDEA** using a Run/Debug configuration. The plugin handles everything automatically.

### How It Works

When you launch a Run/Debug configuration in IntelliJ IDEA, the Reclazz plugin:

1. **Detects** that the project is an SAP Commerce (Hybris) project
2. **Injects** the `-javaagent` flag into the JVM arguments automatically
3. **Detects** your JDK version (17 or 21) and whether you are using JetBrains Runtime
4. **Adds** recommended JVM flags (e.g., `-XX:+AllowEnhancedClassRedefinition` for JBR)
5. **Connects** to the agent after the server starts
6. **Watches** for file changes and shows reload status in the IDE

You do not need to edit any properties files or manage the agent JAR path.

### Setup

1. Enable the plugin: **Settings** > **Tools** > **Reclazz** > check **Enable Reclazz**
2. That's it. Run your Hybris server from IntelliJ as usual.

### Workflow with `ant build`

This is the default and safest workflow. The IntelliJ plugin injects the agent, but compilation is still handled by `ant`:

```
1. Start the SAP Commerce server from IntelliJ (Run/Debug)
   └── Reclazz agent is automatically injected
   └── Plugin connects to the agent
   └── Status bar shows "Reclazz: Connected"

2. Edit your Java source files in IntelliJ

3. Compile with ant (from terminal or IntelliJ terminal):
   cd hybris/bin/platform
   ant build

4. Reclazz detects the new .class files and hot-swaps them
   └── Reclazz tool window shows reload details
   └── Status bar updates: "Reclazz: 1 reloads"

5. Test your changes immediately
```

### Workflow with IntelliJ Build (AutoCompile mode)

If you prefer to skip `ant build` entirely, enable **AutoCompile**. In this mode, Reclazz watches `.java` source files and compiles them internally using the JDK compiler API (`javax.tools`):

1. Go to **Settings** > **Tools** > **Reclazz**
2. Check **AutoCompile (compile .java files internally instead of using ant)**
3. Start the server from IntelliJ

```
1. Start the SAP Commerce server from IntelliJ (Run/Debug)
   └── Reclazz agent is injected with autoCompile=true

2. Edit a Java source file in IntelliJ

3. Save the file (Ctrl+S / Cmd+S)
   └── Reclazz detects the .java change
   └── Compiles it internally using javax.tools
   └── Hot-swaps the resulting bytecode
   └── "Compiled: MyService.java (45ms) → Hot-swapped: com.example.MyService (8ms)"

4. Test your changes immediately — no ant build, no server restart
```

AutoCompile treats the sources collected in one attempt as a package. All
modules compile into a temporary directory, in dependency order, before any
output is published. If compilation fails, the output directories stay
unchanged and the running classes keep their previous version. The log
names the compiler error and the number of held source files. Fixing any
source retries the held sources together with the new save. Deleting a
blocking source removes it and retries the remaining sources.

While one source is broken, other saves join its held package. Two saves
collected in separate attempts are separate packages. This is not an
editor-wide refactoring transaction. Use the normal build tool for generated
code and resources that depend on build tasks or annotation-processor setup.
Do not run AutoCompile and an external compiler against the same output tree
at the same time.

Each successful output file is replaced through a temporary file and rename.
If publication fails partway through, no agent reload starts, but earlier
replacements remain on disk. The error names the failed file; fixing the I/O
problem and saving again retries the package. Class loaders can observe mixed
old and new files during publication. Reload and framework operations still
have their existing per-class failure behavior.

For external builds, the IntelliJ plugin holds class changes from build start
until successful compilation. Errors or cancellation keep them held until the
next successful build. A lost connection never implies success. `HEALTH`
reports the held count and time; a missing result produces a warning after
five minutes. The [BUILD protocol](protocol.md#holding-a-build-until-it-succeeds)
explains recovery and a Gradle/Maven wrapper. Without BUILD signals, the
existing file-watching behavior continues.

### What the Plugin Shows

- **Status bar widget:** Bottom-right corner shows connection status and reload count
  - `Reclazz: Idle` — Plugin enabled, server not running
  - `Reclazz: Connected` — Agent connected, watching for changes
  - `Reclazz: 5 reloads` — Number of successful hot-swaps in this session
  - `Reclazz: Error` — Last reload failed (check tool window for details)

- **Tool window:** Bottom panel labeled "Reclazz" shows a detailed log of all events:
  - Compilation results (autoCompile mode)
  - One line per reload, with the timing and what the save touched:
    `Reloaded com.acme.OrderService (12ms): bean orderService re-created,
    mappings re-scanned, caches evicted`. When the save added something that
    only a restart completes, the line ends with `1 thing now waits on a
    restart, ask PENDING`. The same line is the status bar widget's tooltip.
  - Each framework step's own sentence, under `verbose=true`
  - Errors and warnings

- **Notifications:** Balloon notifications for important events (JDK detection, connection status)

---

## Option 3: Auto-Build on Idle (Optional)

> **This feature is off by default and must be explicitly enabled.** It can increase CPU usage because it triggers a build every time you stop typing, even for incomplete changes.

### How It Works

When enabled, Reclazz monitors your editing activity. After you stop typing for a configurable threshold, it automatically triggers a build. Combined with the hot-swap pipeline, this means your changes are compiled and reloaded without any manual action.

### Setup

1. Go to **Settings** > **Tools** > **Reclazz**
2. Check **AutoCompile** — this is required for auto-build to work
3. The debounce timer controls how long Reclazz waits after your last edit before triggering compilation

### How the Debounce Timer Works

The **Debounce (ms)** setting (default: 500ms) controls the delay:

```
You type... type... type... [stop]
                                  |
                                  ├── 500ms timer starts
                                  |
                                  └── No more edits detected
                                      └── Reclazz compiles the changed file
                                          └── Hot-swaps the result
```

If you resume typing before the timer expires, it resets. This prevents unnecessary compilations while you are still editing.

### Adjusting the Timer

| Setting | Effect |
|---|---|
| **100ms** | Very aggressive — compiles almost instantly after every pause. High CPU usage. |
| **500ms** (default) | Good balance — compiles after a brief pause. Suitable for most workflows. |
| **1000-2000ms** | Conservative — waits for a longer pause. Lower CPU usage, but slower feedback. |
| **3000-5000ms** | Very conservative — only compiles after you clearly stop editing. |

Adjust the timer in **Settings** > **Tools** > **Reclazz** > **Debounce (ms)**.

### Performance Considerations

- **CPU:** Each compilation invokes the JDK compiler. Frequent compilations can increase CPU usage, especially on large files or slow machines.
- **File descriptors:** The file watcher opens file descriptors for every watched directory. On large Hybris installations with many extensions, this can approach OS limits. The **Startup delay** setting (default: 30s) helps by delaying the watcher until the server has finished starting.
- **Incomplete code:** If you pause mid-edit, Reclazz may try to compile syntactically invalid code. Compilation errors are shown in the tool window but do not affect the running server.

### Recommendation

Use auto-build for rapid iteration on small changes (fixing a service method, tweaking business logic). For larger changes involving multiple files, use `ant build` or IntelliJ's Build Project (Ctrl+F9 / Cmd+F9) instead.

---

## Option 4: Attach to Running Server

Use this option when the SAP Commerce server is **already running** without the `-javaagent` flag — for example, started from a terminal or by another team member. Reclazz uses the JVM Attach API to load the agent into the running process without restarting it.

### How It Works

The IntelliJ plugin scans for running JVMs, identifies SAP Commerce processes (by detecting the Tanuki wrapper or Hybris bootstrap loader), and dynamically loads `reclazz-agent.jar` into the selected process. The agent initializes inside the running JVM and begins watching for class changes.

### Requirements

- IntelliJ IDEA and the Hybris server must run as the **same OS user** (JVM Attach API restriction)
- JDK 17+ (the `jdk.attach` module must be available)

### Usage

**From the menu:**

1. Go to **Tools** > **Attach Reclazz to Running Server**
2. If multiple SAP Commerce processes are detected, pick the one you want
3. The agent loads and the plugin connects automatically
4. Status bar shows "Reclazz: Connected"

**From settings:**

1. Go to **Settings** > **Tools** > **Reclazz**
2. In the **Connection** group, click **Attach to Running Server...**

### Workflow

```
1. Start SAP Commerce from terminal (without -javaagent)
   ./hybrisserver.sh

2. In IntelliJ: Tools > Attach Reclazz to Running Server
   └── Plugin detects the running JVM
   └── Agent is loaded dynamically
   └── Reclazz banner appears in the server console
   └── Status bar shows "Reclazz: Connected"

3. Edit Java source files

4. Compile with ant:
   cd hybris/bin/platform
   ant build

5. Reclazz detects the new .class files and hot-swaps them
   └── No server restart needed
```

### Limitations

- **JBR flags cannot be added retroactively**: If the server was started without `-XX:+AllowEnhancedClassRedefinition`, the JVM cannot switch to enhanced redefinition mid-run. The companion-class reloader still works on the attached JVM, so structural changes are still applied — but reflective access to the new members carries the standard-JVM caveat (see below).
- **Startup delay is skipped**: Since the server is already running, the agent starts watching immediately (no `startupDelaySec` wait).

### Troubleshooting

- **"No running SAP Commerce JVM found"**: The server must be running before you attach. Reclazz looks for processes containing `org.tanukisoftware.wrapper.WrapperSimpleApp`, `-Dplatform.home=`, `-DHYBRIS_BIN_DIR=`, or `de.hybris.bootstrap.loader.Loader`.
- **"Permission denied"**: IntelliJ and the server must run as the same OS user. If the server runs as `hybris` and IntelliJ runs as your user, the attach will be refused.
- **"Agent is already loaded"**: The agent was already attached. The plugin will reconnect to the existing agent instance.

---

## When a Reload Goes Wrong

A reload is a change to a running program, and the way back is the same as
the way in: change the file back and save. The old bytes are reloaded like
any other change, so an edit that throws on every request is undone in the
editor, without a restart. What the agent offers on top:

| Ask | Answer |
|---|---|
| `DIAGNOSE <class>` (Tools menu, or a line on the status socket) | what happened to the class the last time it was saved, and the file the bytes came from |
| `PENDING` | what this session has done that only a restart completes; when the list is empty, nothing is |
| `HEALTH` | reloads, failures, latency, what is watched, and a reload that is still running |
| `sessionLog=<path>` | the whole session, timestamped, for reading back after the fact |

When the trouble is the agent rather than the edit: `excludeClasses=<pattern>`
takes a class out of instrumentation (method-body reloads still work for it),
`structuralReload=false` turns the companion engine off for the session, and
removing the `-javaagent` line runs the application with no agent at all. None
of these loses application state that a restart would have kept; a restart is
the last step, and `PENDING` says when it is the only one.

## Debugging Reloaded Code

Breakpoints keep working in code Reclazz has reloaded. On a stock JDK an
edited method body runs in a companion class named after yours
(`Greeter$$Reclazz$v1`), and the debugger finds it the way it finds anonymous
classes and lambdas: it carries the original source file name and the new
body's line numbers, so a breakpoint on a line of the edited method binds and
hits, the frame shows `Greeter.greet` in `Greeter.java`, and stepping into
the method is not filtered out. Set or move the breakpoint after the edit if
the line you want did not exist before; a breakpoint on a line that the old
body had and the new one does not has nothing to bind to, as in any rebuild.

## Other Clients and Build Tools

Everything the IntelliJ plugin sees comes over a small loopback socket, and
anything else can read it or send the four commands, `DIAGNOSE`, `PENDING`,
`HEALTH` and `SCAN`: another IDE's extension, a Gradle or Maven build that
nudges the agent when it has finished writing class files, a log shipper.
The port file, the JSON lines and the commands are in
[protocol.md](protocol.md), with a Gradle and a shell recipe for `SCAN`.

## Seeing Reloads in JDK Flight Recorder

Every reload is also a Flight Recorder event, so it sits on the same timeline
as the garbage collections, safepoints and JIT compilations around it. Nothing
to configure: the events are emitted whenever a recording is running.

```bash
jcmd <pid> JFR.start name=reclazz settings=default
# ... save a few files ...
jcmd <pid> JFR.dump name=reclazz filename=reclazz.jfr
jfr print --events reclazz.Reload,reclazz.ReloadFailed reclazz.jfr
```

| Event | Fields |
|---|---|
| `reclazz.Reload` | `className` (the JVM's name, `Outer$Inner` included), `structural` (members added or removed, as opposed to bodies changing), `measured` (the measured time, or -1 for one of a batch timed as a whole), `shape` (what changed, as `v2, +1 method`, when known), `source` (the class file the bytes came from, or the `.java` file that was compiled in AutoCompile mode) |
| `reclazz.ReloadFailed` | `className`, `reason`, `source` |

In JDK Mission Control they appear under the **Reclazz** category in the event
browser. A recording of a slow session, sent along with a report, shows what
the agent did and when without the console log.

## Choosing the Right Mode

| Scenario | Recommended Mode |
|---|---|
| Server runs from terminal (`./hybrisserver.sh`) | [Manual Setup](#option-1-manual-setup) |
| Server runs from IntelliJ, compile with `ant build` | [IntelliJ Auto + ant build](#workflow-with-ant-build) |
| Server runs from IntelliJ, want instant feedback | [IntelliJ Auto + AutoCompile](#workflow-with-intellij-build-autocompile-mode) |
| Rapid iteration on single files | [Auto-Build on Idle](#option-3-auto-build-on-idle-optional) |
| Server already running without agent | [Attach to Running Server](#option-4-attach-to-running-server) |
| CI/CD or headless environments | [Manual Setup](#option-1-manual-setup) with standalone agent |

---

## Supported JDK Providers

Reclazz works with **any JDK 17+** that supports the standard Java Instrumentation API (`java.lang.instrument`). Structural hot-reload (adding/removing methods and fields) works on every supported vendor — the difference between enhanced-redefinition VMs (JBR/DCEVM) and standard JVMs is reflective visibility, not whether the change takes effect.

| JDK Provider | Method Body | Structural Reload | Reflective Visibility of New Members |
|---|---|---|---|
| **JetBrains Runtime (JBR)** | Yes | Yes (enhanced redefinition) | Full — reflection sees new members |
| **DCEVM** | Yes | Yes (enhanced redefinition) | Full — reflection sees new members |
| **Oracle JDK** | Yes | Yes (companion-class mode) | Hot-compiled callers only |
| **SapMachine** | Yes | Yes (companion-class mode) | Hot-compiled callers only |
| **OpenJDK** (generic) | Yes | Yes (companion-class mode) | Hot-compiled callers only |
| **Amazon Corretto** | Yes | Yes (companion-class mode) | Hot-compiled callers only |
| **Eclipse Temurin (Adoptium)** | Yes | Yes (companion-class mode) | Hot-compiled callers only |
| **Azul Zulu** | Yes | Yes (companion-class mode) | Hot-compiled callers only |
| **BellSoft Liberica** | Yes | Yes (companion-class mode) | Hot-compiled callers only |
| **GraalVM** | Limited | Limited | — (not recommended) |

> **Important:** The `-XX:+AllowEnhancedClassRedefinition` flag is **JBR/DCEVM-only**. Do not add this flag manually when using Oracle JDK, SapMachine, OpenJDK, or any other standard JVM — it will cause a startup crash. The plugin only adds this flag when it detects a compatible JDK.

> **GraalVM note:** GraalVM has known limitations with `Instrumentation.redefineClasses()`. Some method body changes may fail. If you experience issues, consider switching to a standard OpenJDK or JBR.

### Companion-class reflective visibility caveat

On standard JVMs, structural reloads use a hidden companion nestmate
instead of modifying the original `Class` object (which the JVM
doesn't allow). This has one consequence worth knowing:

**Works after a structural reload (any JDK 17+):**
- Hot-compiled Java code that calls a new method directly — the
  invocation is rewritten through the companion via `invokedynamic`
- Hot-compiled Java code that reads/writes a new field
- Hybris Jalo-layer property access (`jaloItem.setProperty(...)`)
- Flexible search with new attribute columns (after HAC
  `updatesystem` for items.xml changes)

**Needs server restart on standard JVMs (works immediately on JBR/DCEVM):**
- `Class.getMethod("setNewThing", ...)` on the original class
- Reflective caches built at boot time (Hybris `ModelService`'s
  attribute dispatch, Jackson, Gson, etc.)
- Groovy console reflection that reaches through
  `ModelService.setAttributeValue` / `getAttributeValue`

If your workflow relies heavily on reflective access to
newly-added members (e.g. scripted type-system exploration), use
JBR or DCEVM for full visibility. For the typical edit-compile-run
loop on hand-written Java, companion-class mode is transparent.

### Setting Up JBR

1. Download JBR 17 or 21 from [JetBrains releases](https://github.com/JetBrains/JetBrainsRuntime/releases)
2. Set `JAVA_HOME` to the JBR installation
3. If using Manual Setup, add `-XX:+AllowEnhancedClassRedefinition` to your JVM args
4. If using IntelliJ Auto mode, the plugin detects JBR automatically and adds the flag for you

---

## Known Limitations

### New Field Values on Objects That Already Existed

When a structural reload adds an instance field, objects created after the
reload run the new constructor and get the field's initialiser value. An
object that already existed did not, so Reclazz lifts the initialiser's own
instructions out of the constructor and runs them for that object on the
field's first read. Adding `private final List<String> cache = new
ArrayList<>();` to a live Spring singleton therefore reads a list, and
`private int retries = 3;` reads 3. The initialiser may read the object's
other fields and call its methods, which the live object has; each object
computes its own value; a value the application wrote before the first read,
null included, is kept; and the initialiser runs once per object.

What cannot be lifted keeps the type default (`null`, `0`, `false`) on
pre-existing objects, and the reload names the field and the reason:

- an assignment that reads a constructor argument (`this.upper =
  name.toUpperCase()` in the constructor body), because the object no longer
  has the argument;
- an initialiser that branches, sits in a try/catch, or shares a computation
  with another field;
- an initialiser that throws on first read: the field reads the default and
  the initialiser is not tried again.

On JetBrains Runtime or DCEVM the field is a real field on the redefined
class, so reflection sees it, and objects from before the reload keep the type
default there.

---

## What Cannot Be Hot-Reloaded

These are fundamental JVM and SAP Commerce limitations that no tool can overcome:

| Change | Why | What To Do |
|---|---|---|
| `items.xml` changes | Type system is loaded once at startup | `ant all` + Update Running System (HAC) + restart |
| Generated model classes | Bound to the platform classloader at startup | `ant all` + restart |
| New extensions | Extension list is fixed at startup | `ant all` + restart |
| Class hierarchy changes | JVM does not allow changing superclass/interfaces | Restart |
| New JAR dependencies | Classpath is fixed at JVM startup | Restart |
| Spring XML bean definitions | Application context is loaded once | Restart (or use HAC context refresh) |
