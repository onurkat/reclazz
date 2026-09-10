# Usage Guide

Reclazz supports four modes of operation. You can use them independently or combine them depending on your workflow.

---

## Option 1: Manual Setup

Use this option when you run the SAP Commerce server **outside of IntelliJ** (e.g., from the terminal with `./hybrisserver.sh`) or when you want full control over JVM arguments.

### How It Works

You add the `-javaagent` flag to your Hybris Tomcat configuration. The agent loads inside the SAP Commerce JVM, watches for compiled `.class` files, and hot-swaps them when you run `ant build`.

### Setup

Edit `hybris/config/local.properties` and add the agent to `tomcat.javaoptions`, preserving any existing options in that value:

**Any JDK 17+ (works for structural reloads too):**

```properties
tomcat.javaoptions=-javaagent:/path/to/reclazz-agent.jar=hybrisHome=/path/to/hybris
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
tomcat.javaoptions=-javaagent:/path/to/reclazz-agent.jar=hybrisHome=/path/to/hybris -XX:+AllowEnhancedClassRedefinition
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

### XML singleton recreation

For an existing bean in a watched `*-spring.xml`, edits to constructor arguments,
a public static or instance factory method, or explicit `init-method` and
`destroy-method` metadata can recreate the singleton without restarting:

```xml
<bean id="client" class="example.Client" init-method="start" destroy-method="stop">
    <constructor-arg value="30"/>
</bean>
```

Changing `30` to `60` replaces the definition and lets Spring create and initialize
the new product. The old product receives its old destroy callback; the new
definition controls subsequent destruction. Arguments and properties support
scalar values, bean references and the live factory's placeholder resolution.
Unchanged definitions do not recreate products. A lazy singleton that has not
been created stays lazy. Ordinary setter-only edits retain the existing in-place
path and bean identity.

Reclazz snapshots Spring's dependent graph before replacement. Supported plain
dependents are recreated too. Writable direct fields in known singleton holders
are updated by identity, including previously replaced plain holders still held
by application code. These old holders are remembered weakly across saves.
Their own state is not reset or globally copied from the new holder.

Preflight requires exact file ownership, the same concrete product type, stable
class/factory-bean/alias metadata and an ordinary singleton definition. An instance
factory must already be a local singleton; factory methods must be public and
unambiguous with a concrete return type. Scalar/reference argument and property
edits, factory-method selection and explicit init/destroy names are the allowed
metadata changes. Scope, parent, autowire policy, depends-on, method overrides,
custom suppliers, infrastructure metadata and other definition changes are refused.

New constructor/factory/lifecycle definitions and bean removal still require a
restart. Recreated definitions cannot use nested beans or managed collections.
FactoryBean, proxies, AutoCloseable, Thread, Executor, DataSource, Spring lifecycle
interfaces and infrastructure types are excluded. Dependents must have plain
definitions without constructor/factory/lifecycle metadata or lifecycle annotations.
Known direct final fields and collection/map/array holders referencing replaced
beans are refused before destruction. Local variables, static fields, nested
object graphs, inaccessible fields and holders outside the owning factory are
not repaired; a raw reference to the old target remains the old object.

If creation fails, Reclazz attempts to restore the previous definitions, recreate
the affected instances and repair holders. Both the original failure and a failed
restoration are reported. Fix the input or dependency and save again to retry.
This is not rollback of constructor or callback side effects; restoration can run
callbacks again. Spring retains its own callback-error handling. Replacement is
not a transaction with concurrent requests, registry edits or external resources.

Verified with Spring 5.3.39 and SapMachine 21, including startup-agent reloads in
ordinary and child classloaders, factory edits, callback counts and repeated saves.
Multiple owning contexts resolve placeholders independently, using the same Spring
classes. Other Spring versions, separate Spring installations, SAP Commerce runtime
integration and a JDK 17 runtime have not been verified for this extension.

### Lifecycle methods added after startup

On a stock JDK, supported `javax.annotation.PostConstruct` and
`javax.annotation.PreDestroy` methods added to a running Spring singleton now
participate in its lifecycle. Verified with real Spring 5.3.39 and a startup
agent, including private callbacks and a resource field added in the same save.

The lifetime policy is per instance:

- Adding init never runs it retroactively on the old bean. Normal singleton
  recreation destroys that bean, then injects the new one before invoking its
  added init callback. Existing annotation init callbacks run first; the added
  callback runs before `InitializingBean.afterPropertiesSet` and configured init.
- A newly created instance retains the added destroy entry body associated with
  its initialization. Editing or removing the method/annotation affects future
  instances; it does not erase cleanup owed to an existing instance. A destroy
  callback added without an init callback also applies only to new instances.
- Existing annotation destroy callbacks remain Spring's responsibility. Added
  destroy runs after those and before `DisposableBean.destroy` and configured
  destroy methods. Normal later bean recreation and context close use the same
  registration, with one callback invocation per instance.
- An added init exception fails bean creation. Spring's existing refresh path
  has already destroyed the previous singleton; this is not a rollback to it.
  As with Spring annotation initialization, a failed init does not automatically
  receive destroy, so partially acquired resources need cleanup in the init's
  own failure path. An added destroy exception is reported and does not prevent
  remaining normal destruction callbacks or other beans from closing.

The supported added methods are direct, nonstatic, no-argument `void` methods,
at most one added method per phase, with no other method annotations except
`@Deprecated`. Init and destroy must be distinct methods. Existing reflected
methods are not subject to these added-method restrictions. The bean must be an
already initialized local singleton, have its exact declared runtime class and
`Object` superclass, and not be a factory-method product or Spring infrastructure
bean. The saved class may carry direct Spring component/service/repository/
controller/rest-controller stereotypes and `@Deprecated`; other class annotations
are outside this subset. A callback also named as a configured init/destroy
method is refused to avoid duplicate invocation. One standard, unmodified
`CommonAnnotationBeanPostProcessor` using the javax annotations must be present.

Proxy, inherited, lazy/uninitialized, non-singleton, custom lifecycle processor,
additional advice/annotation and `jakarta.annotation` cases are not covered by
this adapter. Unsupported additions are named as requiring a restart. Validation
runs before singleton recreation and does not replace a previously installed
callback plan on refusal. The compiled application code has already reloaded;
this preflight is not a transaction over the entire reload.

Only the added callback entry body is captured. Methods it calls, field access,
injected collaborators and the rest of the application keep their normal reload
behavior; this does not freeze an object graph or guarantee cleanup after
incompatible changes to resource state. Existing callback bodies also retain
their existing live dispatch behavior.

Regression tests check exact callback order and instance identity through six
saves, method/annotation removal and restoration, external bean recreation and
context close. Real NIO channels must close, with zero left open. Annotation API
1.3.2 is a test-only dependency and is not included in the production agent.

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

The new method lives in the companion. A hidden supplier calls its current body,
using the current configuration singleton for an instance method or a receiver-free
static call for a static method. A Spring bean definition creates the product.
Spring applies dependency injection, bean postprocessors and lifecycle
callbacks. A later save recreates the added product, including body-only edits.
Removing the method or its `@Bean` annotation removes its owned definition.
Changing its name removes the previous owned name and registers the new one.
Closing the context runs normal product destruction. Repeated saves leave one
registration per supported name.

Definitions from the same save are registered before products are initialized,
so an added product can inject another added bean regardless of declaration order,
including through factory parameters. Only contexts containing the edited
configuration participate.

Factory parameters can be required reference beans. Spring selects candidates by
type, honors primary candidates and direct parameter `@Qualifier("fast")`,
and uses the original parameter name to break ties when javac preserved it with
`-parameters` or debug local-variable information. Without a preserved name, no
name is invented: unique type/primary/qualifier selection still works, and an
unresolved ambiguity is reported. Parent-context candidates and dependency
proxies are passed through as the objects Spring resolves.

Concrete generic parameters also work: `List<T>`, `Set<T>`, `Collection<T>`,
`Map<String,T>`, `Optional<T>`, and Spring `ObjectProvider<T>`/`ObjectFactory<T>`.
One-dimensional reference arrays such as `Transport[]` are supported. Spring
filters generic element types and qualifiers, orders lists/arrays, and supplies
empty containers or an empty optional when no candidate exists. Providers keep
Spring's lazy lookup semantics: creating the added product does not instantiate
their target, and subsequent provider calls look up the current bean.

Direct parameter `@Value` uses the application's Spring value resolution and
conversion, including all eight primitive types and explicit reference nulls:

```java
@Bean
Client client(List<Transport> transports, Optional<Metrics> metrics,
              ObjectProvider<Token> tokens, @Value("${client.retries:3}") int retries) {
    return new Client(transports, metrics, tokens, retries);
}
```

Values are resolved when the product is created or recreated. Property-only
edits do not automatically recreate these added factories; compile the
configuration again to apply a new factory argument value.

Added factory methods can also carry direct `@Primary` and `@Qualifier`.
For example, a method named `remoteTransport` carrying `@Bean @Qualifier("fast")`
can supply a parameter declared as `@Qualifier("fast") Transport transport`:
the qualifier value need not be the bean name and does not create an alias.
With several candidates of the required type, `@Primary` selects the default;
an explicit parameter qualifier first restricts the matching candidates. If
several candidates match that qualifier, one primary candidate can break the tie.
The default empty `@Qualifier` value is preserved, distinct from no qualifier.

Selection metadata is installed before any added products are initialized, so
these rules also apply to providers added in the same save. Newly created ordinary
Spring consumers can use it too. Moving, changing or removing either annotation
and compiling again updates selection for newly created products. Missing matches
or unresolved ambiguity are reported; correcting the annotations and compiling
again recovers. Existing singleton holders are not globally re-injected.

Arguments are resolved on every product creation. Dependency names are registered
with the creating bean factory so its normal dependency destruction applies,
including for dependencies that were already cached singletons. If an argument
is required but missing, ambiguous, or cannot be converted, the factory body is not called and
its failed owned definition is removed. The message identifies the parameter
and Spring's failure. Fix the dependency or qualifier and compile the
configuration again to recover; registering a missing dependency alone does not
restore a definition removed after a failed reload.

Static factories are supported too, including private methods and required bean
arguments. For example, add this method to the supported configuration:

```java
@Bean({"staticClient", "legacyStaticClient"})
private static Client staticClient(Transport transport) {
    return new Client(transport);
}
```

Static and instance factories share argument resolution, alias registration,
selection metadata and product lifecycle. Registration still requires the
configuration singleton described below; supporting a static method does not
enable configuration-free registration or early infrastructure factories.

Supported factories carry direct `@Bean` and return a non-generic object
(not a primitive, array or `void`). Private instance methods work with
`@Configuration(proxyBeanMethods=false)`; static methods can also be private.
Native and abstract methods do not work. The class must carry direct
`@Configuration`, extend only `Object`, implement no interfaces and have exactly
one local configuration singleton in each affected bean factory. Lite
configuration (`proxyBeanMethods=false`) requires an unproxied instance; default
configuration requires Spring's direct enhanced subclass as described below. Extra runtime class annotations are limited to
`@Deprecated`; methods additionally allow direct `@Primary` and `@Qualifier`.
Conditions, profiles, scopes, advice, lazy metadata, class-level primary/qualifier
policies, composed annotations and additional proxies require a restart.
Parameters allow direct `@Qualifier` and `@Value`. Primitive parameters without
`@Value`, primitive/multidimensional arrays, raw generic parameters, wildcard or
unresolved type variables, generic return types and generic factory methods
remain unsupported. Collection/map implementations other than the interfaces
listed above, maps with non-String keys, streams, suppliers, custom provider
interfaces and JSR provider APIs are also unsupported. Extra runtime
parameter/type annotations such as `@Lazy`, nullable annotations and composed
qualifiers require a restart. Parameter names come from the saved bytecode;
custom name-discovery policies are not used by this path. No-argument factories
remain supported.

Default `@Configuration` (including explicit `proxyBeanMethods=true`) supports
added instance factories with no parameters that are neither private nor final.
For example:

```java
@Configuration
public class Clients {
    @Bean({"transport", "legacyTransport"})
    public Transport transport() { return new Transport(); }

    @Bean
    public Client client() { return new Client(transport()); }
}
```

The call to `transport()` resolves the same singleton as `getBean("transport")`
and its alias. Calls from an existing configuration reference or another
instrumented application class use that same reference path. The container's
factory supplier executes the method body to create a product; nested instance
factory calls go back through Spring. A method reference such as `this::transport`
created in the edited configuration follows the same route, including when it is
retained across later saves. Reflection, arbitrary method handles and method
references created in other classes are outside this added-factory scope.
Spring tracks their dependencies and rejects
unresolvable creation cycles. Correcting the factory and saving again recovers.
Static factory calls and manually constructed configurations retain ordinary Java
semantics; calling a static factory directly can create another object.

Parameterized instance factories in full configuration remain unsupported: the
added supplier path does not implement explicit argument calls to those methods.
Their parameter annotations do not make them supported. The richer parameter
support above remains available for lite configuration and static factories.
Full configuration must be Spring's direct enhanced subclass with its native bean
factory binding; additional AOP proxies, user subclasses, inheritance and changing
configuration mode at runtime are outside this scope. Existing lifecycle and
replacement rules below still apply: a saved configuration can recreate existing
products, so references to old products are not promised to stay current.

The default method name or explicit `name`/`value` names are supported, along with
`initMethod` and `destroyMethod`. For `@Bean({"transport", "legacyTransport"})`, the
first name identifies the bean definition and the remaining names are aliases
for the same singleton. The method name is not another implicit alias. Aliases
are available before added products initialize, including for a parameter such
as `@Qualifier("legacyTransport") Transport transport`. Editing the names replaces
the owned name/alias registrations; removing the method or its `@Bean` annotation
removes them. Each product has one lifecycle regardless of how many names it has.

Every name must be nonblank, must not start with `&`, and must be distinct within
the factory. Conflicting `name` and `value` arrays are refused. The default inferred
`close`/`shutdown` destruction and an explicit empty destroy method are preserved.
Other explicit `@Bean` options are refused. `FactoryBean`, bean postprocessors and bean
factory postprocessors cannot be introduced through this path, including when
hidden behind an `Object` return. Null results are refused.

An existing definition, singleton, local alias (even a dangling one) or parent bean
blocks any requested name or alias; none of that factory's names are installed
when a collision is found. Overlapping names/aliases across supported added
factories in the same save refuse every affected factory. Before removal, Reclazz checks that
the definition and any live singleton still belong to its registration; externally
replaced registrations and their aliases are left alone. Alias cleanup removes
only aliases registered by Reclazz that still point directly to the owned name.
Externally retargeted aliases and externally added alias chains are preserved;
they can remain dangling if their target was removed. Replacing an alias with an
identical direct binding cannot be distinguished from leaving it unchanged.
If current direct alias bindings cannot be inspected, aliased factories are
refused before registration. Failed alias registration or product initialization
cleans up the owned definition and its unchanged aliases.
Registration is not a transaction with
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

The richer argument path is covered with Spring 5.3.39 on SapMachine 21,
including real startup-agent reloads in both application and child classloaders.
Those tests cover successive saves, same-save dependencies, lazy lookup,
conversion failure, removal, recovery and destruction. Other Spring versions
and a JDK 17 runtime have not been verified for this extension.

### Exception handlers added after startup

On a stock JDK, add a handler to an already running plain controller or
`@ControllerAdvice` / `@RestControllerAdvice` class and compile it:

```java
@ExceptionHandler(IllegalArgumentException.class)
public ResponseEntity<String> invalidInput(IllegalArgumentException failure) {
    return ResponseEntity.status(422).body(failure.getMessage());
}
```

The next matching exception can reach this new method without restarting the
context. Spring still selects the handler: local controller methods take
priority over global advice, exception specificity and causes are matched by
Spring, and advice order and scope selectors keep their normal role. An endpoint
added by Reclazz is associated with its original controller for this lookup, so
its local handlers and controller-specific advice apply too.

The adapter supplies saved handler metadata and delegates to the actual
controller/advice instance chosen by Spring. Runtime method and parameter
annotations, saved parameter names, concrete generic signatures such as
`ResponseEntity<String>`, and class/method `@ResponseStatus` are available to
Spring's normal argument and response pipeline. Both public and private handler
methods are covered. Class-level response-body metadata is copied as well.

Later saves update the metadata and method body. Removing the method or its
`@ExceptionHandler` annotation removes it from the next cache rebuild; restoring
it makes it eligible again. The complete declared handler set is validated by
Spring before publishing a metadata adapter, so ambiguous exception mappings
are rejected. Rejection disables that class's added-handler adapter and reports
the failure; handlers still visible on the original class remain available to
the ordinary scan. This is not rollback of already reloaded method bodies.
Exception-cache rescans also preserve the existing response-body advice list,
so repeated handler saves do not accumulate advice registrations.

The supported scope uses the standard MVC exception resolver and plain
controller/advice receivers. The adapter refuses proxy/subclass receivers
instead of unwrapping them and bypassing advice. Controller class/interface
inheritance, class or method type variables, static/abstract/native/bridge
handlers, composed annotations and extra class/method advice annotations are
outside this path. Accepted class metadata is the direct MVC controller/advice
stereotypes, `@Component`, `@RequestMapping`, `@ResponseBody`, `@ResponseStatus`,
`@Order` and `@Deprecated`; handler metadata is direct `@ExceptionHandler`,
`@ResponseBody`, `@ResponseStatus` and `@Deprecated`. Declared asynchronous,
reactive and streaming return types are refused. Async behavior hidden behind
an `Object` return or performed inside a body, scoped/prototype configurations,
custom resolver subclasses and changes to advice class ordering/selectors were
not validated here. Newly added `@InitBinder` / `@ModelAttribute` methods use
the separate adapter described below.

Spring sees a hidden metadata class for an adapted handler: its reflective
declaring/containing class is not the original controller. Custom argument,
return-value or response-advice code relying on that exact class identity is
not covered. Original application reflection still cannot find the added
method. Ordinary bean recreation and lifecycle callbacks remain in effect;
this feature does not preserve destroyed bean resources or make metadata
publication atomic with concurrent requests.

Verified on stock JDK 21 and Spring Framework 5.3.39. Real-agent tests send
loopback HTTP requests through Spring MVC's MockMvc/DispatcherServlet pipeline
and check existing/new endpoints across five saves; focused tests exercise the
actual exception resolver. This requires startup with `-javaagent`, before MVC
loads. An unavailable/incompatible resolver hook is reported. Other Spring
versions, JDK 17 runtime, attach to an already loaded MVC stack, and a servlet
container deployment were not exercised. No dependencies were added.

### Binding and model methods added after startup

A supported plain controller or controller advice can gain entirely new
`@InitBinder` and `@ModelAttribute` methods on a stock JDK. For example, compile
these additions to a running `@RestController`:

```java
@InitBinder("name")
public void trimName(WebDataBinder binder) {
    binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
}

@ModelAttribute("greeting")
public String greeting() {
    return "Hello";
}
```

Spring's normal binder and model pipeline sees the saved callback metadata.
Binder names still restrict which arguments/objects a binder affects. Global
callbacks run before local ones; advice ordering and controller selectors are
applied by Spring. Existing model callbacks stay in the scan, and model
attribute dependencies, return values and void methods that populate `Model`
use Spring's normal behavior. Model-annotated request mappings are excluded
from initialization, as in Spring's own method filter.

Public and private callbacks delegate to the actual controller/advice bean.
Method/parameter annotations, `MethodParameters` names and concrete generic
signatures are copied. Use explicit annotation argument names (for example
`@ModelAttribute("base")`) or compile with `-parameters` when names are needed;
local-variable-table parameter-name discovery is not reproduced. Existing
endpoints and Reclazz-added endpoints share the original controller's callbacks
and advice scope. Later saves can edit, remove, restore or remove the annotation
from an added callback. Cache rebuilding uses the existing advice reloader.

The scope is plain receivers whose class directly extends `Object` without
interfaces or type variables. Supported class metadata is direct MVC
controller/advice stereotypes, `@Component`, `@RequestMapping`, `@ResponseBody`,
`@ResponseStatus`, `@Order` and `@Deprecated`; callback annotations are direct
`@InitBinder`, `@ModelAttribute` and `@Deprecated`. Extra callback advice
annotations, static/abstract/native/bridge/synthetic callbacks, type-variable
signatures, non-void binders and declared async/reactive/streaming model returns
are refused. Proxy/subclass receivers are refused at invocation, so callback
bodies do not bypass their advice. `@SessionAttributes`, composed metadata,
custom adapter subclasses, scoped/prototype beans, changes to advice class
selectors/order and asynchronous request behavior are outside this support.

A rejected publication clears that class's added-callback adapter; methods
still visible on the original class remain available to Spring. This does not
roll back already reloaded bodies. The hidden metadata class differs from the
original controller's reflective declaring/containing class, so custom argument
resolvers relying on exact class identity are not covered. Normal bean
recreation/lifecycle effects remain; this does not guarantee atomic metadata
publication with concurrent requests. Added methods remain absent from ordinary
application reflection.

Verified with Spring Framework 5.3.39 on stock JDK 21, using real-agent loopback
HTTP requests into MockMvc/DispatcherServlet and focused tests of the actual
Spring adapter. Start with `-javaagent` before MVC loads. The transformer checks
its expected bytecode sites and reports an unavailable/incompatible hook.
Other Spring versions, JDK 17 runtime, late attach and a servlet-container
deployment were not exercised. No dependencies were added.

### Kafka listeners added after startup

With Spring Kafka already configured, compile a new listener method on a
running plain singleton `@Component` / `@Service` / `@Repository`:

```java
@KafkaListener(id = "orders-created", topics = "orders", groupId = "orders-service")
public void onOrder(String payload) {
    processOrder(payload);
}
```

The saved metadata is passed to the application's Kafka listener processor and
container factory. Spring performs message conversion and argument resolution;
`@Payload`, `@Header`, `@Headers` and concrete generic parameter signatures such
as `ConsumerRecord<Integer, String>` are carried. Public and private void
instance methods with one or more arguments are supported. Calls delegate to
the current plain singleton; a missing or proxied replacement throws instead
of silently treating the message as handled.

Unsupported Kafka infrastructure does not block unrelated bean reloads.
Before the edited bean is recreated, Reclazz identifies its standard Kafka
record adapters, requests consumer stop and waits for the stop callbacks before
unregistering their container IDs. Normal Spring initialization can then
re-register existing methods without ID collisions. The new methods register
afterward. Later saves update bodies/topics, remove listeners or annotations,
and restore them. Unrelated containers retain their identity. A duplicate ID
belonging to another container is refused. A failed registration attempt cleans
up the containers it created; failures are reported in the restart ledger.

The supported addition uses literal nonempty `id` and `topics`; optional literal
`groupId`, `containerFactory`, `idIsGroup`, `autoStartup` and positive integer
`concurrency` are accepted. Other explicitly supplied `@KafkaListener` options
are refused. The class directly extends `Object`, has no interfaces/type
variables and carries only the component stereotypes above or `@Deprecated`.
Callback annotations are direct `@KafkaListener` and optional `@Deprecated`;
extra advice annotations, non-void/static/abstract/native/bridge/synthetic
methods and type-variable signatures are refused. Parameter names require
explicit annotation names or compilation with `-parameters`.

Use the standard `KafkaListenerAnnotationBeanPostProcessor`,
`KafkaListenerEndpointRegistry` and `ConcurrentKafkaListenerContainerFactory`
in record mode. Added listeners refuse annotation enhancers, retry-topic
configuration, filtered/retry-template/batch factories and container
customizers. Proxy/subclass receivers are not unwrapped past advice. This
scope does not cover class-level or repeatable listeners, SpEL/property-based
listener attributes, container groups, arbitrary wrapper/container types,
scoped/prototype beans or sharing one registry between multiple contexts.

If consumer stop does not complete within 30 seconds, container entries remain
registered and this class's bean refresh/framework follow-up is deferred with
a diagnostic. Already reloaded method bodies are not rolled back. The Kafka
coordination here runs for direct class reload through the Spring orchestrator;
property-triggered or dependent-bean recreation through other paths is not
covered. Existing Spring bean recreation/lifecycle effects remain. The hidden
method's declaring class differs from the original, so custom conversion or
argument factories depending on exact declaring-class identity are outside the
verified scope. This is not an atomic reload transaction or a Kafka exactly-once
delivery guarantee; application acknowledgments, retries and offset policy
continue to determine redelivery.

Verified with Spring Kafka 2.9.13, Spring Framework 5.3.39 and a real embedded
Kafka 3.2.3 broker on stock JDK 21. Real-agent tests cover both the first listener
on an already used bean and addition beside an existing listener, unique test
message delivery across six saves, topic changes, retired consumer state,
unrelated container identity and context close. Start with `-javaagent` before
application classes load. Other broker/framework versions, JDK 17 runtime,
external clusters, transactions and rebalance/failure delivery guarantees were
not exercised. Kafka libraries and the embedded broker are test dependencies
only. Beans mixing Rabbit, JMS or Kafka listeners require a restart before
any of their consumers are retired.

### JMS listeners added after startup

With Spring JMS already configured, add a listener to a running plain singleton
`@Component`, `@Service` or `@Repository` and compile:

```java
@JmsListener(id = "orders-created", destination = "orders")
public void onOrder(@Payload String payload, @Header("tenant") String tenant) {
    processOrder(tenant, payload);
}
```

Spring's actual listener processor and container factory register the saved
metadata. Public/private instance `void` methods with arguments are supported.
Spring resolves the payload, headers and JMS message/session arguments. Calls
reach the current plain singleton; a missing/proxied replacement throws rather
than silently consuming a message. Later saves can edit bodies or queue
destinations, remove a method or its annotation, and restore it. Original
listeners on the same bean are registered again after recreation. Containers
belonging to unrelated beans keep their identity.

Use literal nonempty `id` and `destination`. Optional literal `containerFactory`,
`selector` and positive `concurrency` (a number or ascending range) are supported.
The class must directly extend `Object`, have no interfaces/type variables, and
carry only the stereotypes above or `@Deprecated`. Added callback annotations
are direct `@JmsListener` and optional `@Deprecated`; parameter annotations are
`@Payload`, `@Header` and `@Headers`. Extra advice, static/non-void/synthetic
methods, type-variable signatures and other explicitly supplied listener
options are refused. Parameter names require explicit annotation names or
compilation with `-parameters`.

The verified infrastructure is the standard `JmsListenerAnnotationBeanPostProcessor`,
`JmsListenerEndpointRegistry`, `DefaultJmsListenerContainerFactory` and
`DefaultMessageListenerContainer` with Spring's `MessagingMessageListenerAdapter`.
The application factory retains its conversion, selector and local session
transaction settings. Added listeners refuse custom factories/executors,
external transaction managers and topic/durable/shared subscription factories.
Proxies and subclass receivers are not unwrapped. Repeatable/composed listeners,
reply methods, scoped/prototype beans and registries shared between contexts are
outside this scope. Custom argument factories that depend on the exact original
declaring class are not verified: the added method is reflected on a hidden
metadata delegate.

Before singleton recreation, Reclazz identifies owned containers and waits for
complete destruction, including their consumers, sessions and connection.
Spring 5.3 has no registry unregister API; matching entries are removed under
the registry's registration lock only after destruction finishes. A duplicate
ID on another container is refused. Failed registration cleans up containers
registered by that attempt. Unsupported JMS infrastructure leaves unrelated
bean reloads on their ordinary path. A bean mixing Kafka and JMS consumers, or
a JMS bean with uninspectable Kafka ownership, is deferred before shutdown.

Shutdown waits up to 30 seconds. On timeout or interruption, registry ownership
is retained and bean recreation/framework follow-up is deferred with a restart
ledger diagnostic. Destruction continues in the background; a later save waits
for that same operation before releasing the ID. Retained entries may therefore
refer to already stopped containers until retry or application restart. Already
reloaded bodies are not rolled back. This coordination covers direct class
reloads through the Spring orchestrator; property/dependency-cascade recreation
through other paths is outside scope. It does not make a reload atomic or promise
global exactly-once delivery. Broker and application acknowledgment/redelivery
policies still apply.

Verified with Spring JMS 5.3.39 (`javax.jms`), a real embedded ActiveMQ Classic
5.19.11 broker, and stock JDK 21 using startup `-javaagent`. Tests cover the first
listener on an already used bean, addition beside an original listener, six
saves, payload/header/message conversion, local transaction rollback/redelivery,
selector filtering, consumer retirement, and context close. A deliberately
blocked real consumer proves timeout/interruption and retry using a shorter
internal test wait. Other JMS providers, Spring versions, JDK 17 runtime,
Jakarta Messaging 3, late attach, XA and durable topics were not exercised.

Spring JMS and ActiveMQ are isolated test dependencies. Existing tests retain
their Jackson/SLF4J versions. `:agent:unitTest`, `:agent:e2eTest` and `:agent:test`
also run the matching isolated JMS suites; those results have separate XML
reports under `jmsUnitTest`, `jmsE2eTest` and `jmsTest`. Run just JMS with
`./gradlew :agent:jmsTest --rerun`. No JMS/broker classes enter the production JAR.

### Rabbit listeners added after startup

With Spring Rabbit already configured, add a method to a running plain singleton
`@Component`, `@Service` or `@Repository`, then compile:

```java
@RabbitListener(id = "orders-created", queues = "orders")
public void onOrder(@Payload String payload, @Header("tenant") String tenant) {
    processOrder(tenant, payload);
}
```

The application’s real processor and `SimpleRabbitListenerContainerFactory`
register the saved metadata. Spring converts payloads/headers and supplies the
AMQP message. The callback uses the current plain singleton; missing or proxied
receivers fail explicitly. Public/private instance `void` methods with arguments
are supported. Later saves can edit bodies and queues, remove methods or their
annotations, and restore them. Original listeners are registered again after
bean recreation; unrelated containers retain their identity.

For newly added methods, use an explicit nonempty literal `id` and literal `queues`
naming existing queues. Optional literal `containerFactory` and positive `concurrency` (number or ascending
range) are accepted. Explicit `autoStartup` is outside this stage: Spring's late
registration can start a container even when that flag is false. The class directly extends
`Object`, has no interfaces/type variables, and uses only the stereotypes above
and optional `@Deprecated`. Callback annotations are direct `@RabbitListener`
and optional `@Deprecated`; parameter annotations are `@Payload`, `@Header` and
`@Headers`. Composed/repeatable/class-level handlers, expression/property queue
names, inline queue/exchange/binding declarations, return/reply/async methods,
proxies, method advice and inheritance are outside this support.

Existing methods are re-scanned by Spring and do not use the added-method option
allowlist: an existing `@RabbitListener(queues="orders")` keeps working with
Spring-generated IDs, including beside a new listener. Existing `ackMode="AUTO"`
and `autoStartup` metadata also stay on Spring's ordinary registration path.
Spring's late registration may start an existing `autoStartup="false"` container;
Reclazz preserves that framework behavior.

Both original and added consumers require the standard
`RabbitListenerAnnotationBeanPostProcessor`, `RabbitListenerEndpointRegistry`,
`MessagingMessageListenerAdapter`, a plain receiver and
`SimpleMessageListenerContainer`, with AUTO acknowledgment and the default
`SimpleAsyncTaskExecutor`. Newly added consumers additionally require the standard
simple factory without a customizer. Local channel transactions are preserved.
Manual/automatic-without-ack modes, external transaction managers, container
advice, custom executors, direct/stream/batch containers and custom listener
adapters are refused for retirement of either original or added consumers.
Unsupported added-method metadata, foreign added IDs and mixed Rabbit/JMS/Kafka
owners are checked before owned consumers are retired. Original consumers are
checked against the runtime lifecycle restrictions above, not the added-method
metadata restrictions. Unknown broker ownership also requires a restart. Unsupported Rabbit infrastructure does not block unrelated
beans.

Start with `-javaagent`. The agent tracks each actual simple-container worker
from construction through its complete `run()` exit, including queued tasks and
channel/transaction cleanup. Before recreation it prevents new workers on the
retired container, starts destruction, and waits up to 30 seconds for both
destruction and worker completion. A zero Spring consumer count alone is not
accepted: channel shutdown can release that counter while a callback still runs.
An unavailable startup hook, timeout or interruption retains registry ownership,
defers bean recreation and records a restart diagnostic. A later save waits for
the same retirement; it may find stopped containers still holding their IDs.

This covers direct listener-class reloads through the Spring orchestrator.
Property/dependency-cascade recreation is outside scope. Already reloaded bodies
are not rolled back, and this does not make reload atomic or promise exactly-once
delivery. Broker/application acknowledgment and redelivery policies still apply.
Custom conversion depending on the callback’s exact declaring-class identity is
outside the verified scope because added callbacks use a hidden delegate.

Verified locally with Spring Rabbit 2.4.17, Spring Framework 5.3.39, RabbitMQ
4.3.5 and stock JDK 21. Real-agent tests cover six saves both on an already used
bean with no listeners and beside both explicitly named and ID-less original
listeners, including payload/header
conversion, rollback/redelivery and context close. A blocked real original
callback checks force-close, timeout, repeat, interruption and retry. Other
framework/broker versions, late attach and JDK 17 runtime were not verified
locally. Linux CI is configured for JDK 17/21; those runs are separate evidence.

Spring Rabbit is a test dependency; no AMQP/framework/broker classes ship in the
agent. The `:agent:rabbitTest` suite requires Docker with Linux containers and a
pre-pulled official image. Prepare and run it with:

```bash
docker pull rabbitmq@sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd
./gradlew :agent:rabbitTest --rerun
```

Tests create uniquely named temporary containers, bind random loopback ports and
remove their own resources on completion. Missing Docker/image fails the suite.
`:agent:test` and `:agent:e2eTest` include it and report results separately under
`agent/build/test-results/rabbitTest`. Count these alongside `test` and `jmsTest`
for the full gate. Portable Rabbit checks run in `:agent:unitTest`; the blocked
broker test belongs to `rabbitTest`. Windows CI explicitly omits only this Linux
broker suite with `-Preclazz.skipRabbitBroker=true`, retaining portable tests.
That option reports NOT RUN and is not a complete broker validation.

### Operations on added service methods

On a stock JDK, add a public instance method to a running singleton service and
compile it together with its caller:

```java
@Transactional(rollbackFor = Exception.class)
public void submit(int id) throws Exception {
    jdbc.update("insert into orders (id) values (?)", id);
    if (!accepted(id)) throw new Exception("order rejected");
}

@Cacheable(cacheNames = "orders", key = "#p0")
public Order load(int id) {
    return repository.find(id);
}
```

Calls from other watched application classes now run the application's real
Spring transaction/cache interceptors around the added companion method. This
covers direct `@Transactional`, `@Cacheable`, `@CachePut`, `@CacheEvict` and
`@Caching` metadata, plus class-level transaction settings and `@CacheConfig`.
Spring owns manager selection, rollback rules, cache keys, conditions, `unless`
and advisor ordering. Saved parameter names (when compiled with `-parameters`)
and indexed arguments such as `#p0` are available. A configured key generator
receives the actual target and saved method metadata.

Both a direct singleton gaining its first operation method and an existing
supported CGLIB proxy are covered. A call through an old proxy reaches its real
target, rather than running against the proxy object's fields. Same-class
self-invocation retains its existing bypass of Spring advice. Repeated saves
replace metadata and bodies. Removing an operation method makes its retained
external call sites fail explicitly; restoring it makes those sites usable
again. Removing only its annotation removes that interception. Application
cache values retain normal region/key semantics; metadata refresh does not by
itself flush their values.

The ordinary service-bean refresh still applies: editing a `@Service` can replace
the registered bean and run its lifecycle callbacks. The adapter captures old
singleton identities before that refresh and discovers the replacement for new
calls. It does not undo destruction callbacks or guarantee that resources on a
destroyed old target remain usable. Factory/programmatically registered classes
without a Spring stereotype retain their existing bean-refresh behavior.

Supported calls require a captured live singleton, a direct target or mutable
CGLIB proxy with `SingletonTargetSource`, and one standard Spring
`InfrastructureAdvisorAutoProxyCreator` or `AnnotationAwareAspectJAutoProxyCreator`.
The configured candidate/proxy advisor sets must contain only the standard
transaction/cache advisors, interceptors and annotation sources. Additional
aspect, security or other custom advisors are refused, including candidate
advisors that have not been attached to this proxy. Missing infrastructure,
unknown receivers and unsupported metadata also cause an explicit exception
before the operation body runs. No unmanaged/prototype instance is assumed to
be a singleton merely because its class matches one.

This does not add interface methods to existing JDK proxies, add reflective
methods to the original class, or cover new overrides of already-resolvable
inherited methods. Static/private/final methods, final service classes, generic
metadata, composed/additional advice annotations, scoped/dynamic/opaque/nested
proxies and declared async/reactive return types are outside this path. Existing
methods follow their existing dispatch; this boundary is installed only for
external calls whose method is absent from the loaded class. Framework reflection
calls, method references/lambdas, and async values hidden behind a declared
`Object` result are not validated by this feature.

Spring sees a separate metadata `Method`: its name, parameters and annotations
are supplied, but its declaring class is a collectible hidden metadata class.
Custom code depending on the exact declaring class is not given the original
class's reflective identity. Metadata caches are cleared on saves to release old
metadata. Bean/context references kept by the adapter are weak. Reloading is not
an atomic transaction with concurrent application calls; this feature does not
add a concurrent consistency guarantee.

Verified with Spring Framework 5.3.39, H2 2.2.224 and a stock JDK 21: real database
commit/checked-exception rollback, cache hit/put/evict, combined transaction/cache,
parameter names, custom key generation, annotation changes, method removal and
restoration, and old/new bean references. Other Spring versions and a JDK 17
runtime were not exercised. Spring JDBC/TX and H2 are test-only dependencies of
Reclazz and are absent from the production agent.

### Edited aspect pointcuts

Change the expression on an existing advice method and compile the aspect:

```java
@Around("execution(* com.example.OrderService.submit(..))")
public Object observe(ProceedingJoinPoint call) throws Throwable {
    return call.proceed();
}
```

If `OrderService` already has a supported Spring proxy, previously injected
references now use the edited pointcut. The same proxy and target remain alive:
service fields are not reset and the service is not destroyed/recreated by this
AOP step. An existing proxy can gain this aspect's advice even if it previously
carried only another advisor. Widening, narrowing, removing all matches and
restoring matches update the chain without duplicating advice.

Spring resolves matching with the bean name, supplies its AspectJ invocation
context and orders the changed advice. Unrelated advisors retain their identity
and relative order. If Spring's sorting would reorder those advisors, the bean
is declined with a reason. All captured contexts are checked, including child
contexts using an aspect defined in a parent. Aspects registered by factories or
programmatically reach this path even without `@Component`.

The supported shape is a mutable JDK or CGLIB singleton proxy with a direct
`SingletonTargetSource`, and one standard
`AnnotationAwareAspectJAutoProxyCreator` in that context. Previously unproxied
singletons cannot acquire a proxy in place; matching beans are named in the log
and restart ledger. Frozen, opaque, nested and dynamic/custom-target proxies,
custom/multiple auto-proxy creators, per-clause aspects and introductions are
outside this path. The scan does not instantiate lazy/prototype service beans;
already-issued prototype/scoped instances and FactoryBean products are not
tracked. Adding new advice methods on a stock JDK, changing proxy interfaces or
removing the `@Aspect` annotation is not covered.

Invalid pointcut syntax is detected before changing living chains; a corrected
save can recover. This is not an atomic update across concurrent invocations:
Spring's advisor mutation API clears method-chain caches, but an invocation
already running can finish with its old chain. Calls overlapping the mutation
can observe an intermediate chain, including both old and new aspect advisors;
concurrent refresh is not covered by the single-call correctness tests. The existing request-boundary mode has its own
[documented scope](#reload-between-requests). The aspect's ordinary bean refresh,
when applicable, retains its existing lifecycle effects.

Verified with Spring Framework 5.3.39, AspectJ 1.9.22.1 and a stock JDK 21 using
real JDK/CGLIB proxies and HTTP calls across four aspect saves. Other Spring/
AspectJ versions and a JDK 17 runtime were not exercised for this feature.
AspectJ is only a test dependency of Reclazz; the production agent ships neither
Spring nor AspectJ and does not enable AspectJ weaving.

<a id="jackson-getters-added-after-startup"></a>

### Jackson properties added after startup

Start with `-javaagent`, then add a getter to an already loaded DTO and compile:

```java
private int count = 7;
public int getCount() { return count; }
```

The next JSON response from a Spring-managed Jackson mapper includes `"count":7`,
including when the endpoint returns an object created before the edit and the
field has a supported [initialiser](#new-field-values-on-objects-that-already-existed).
Add a setter too and a JSON request can populate the new property:

```java
public void setCount(int count) { this.count = count; }
```

Direct instance fields work as well, including private annotated fields:

```java
@JsonProperty("display_name")
@JsonAlias("label")
private String displayName;
```

Jackson writes the same added-field storage that application code reads. Both
deserializing a new mutable DTO and `readerForUpdating` an object created before
the reload are covered. Repeated saves update metadata and bodies; renaming,
removing and restoring a property changes fresh mapper discovery in both directions.

Jackson still decides which properties to read and write. Reclazz supplies saved
field/method metadata and routes calls to the current companion or field storage.
`@JsonProperty`, `@JsonGetter`, `@JsonSetter`, `@JsonAlias`, `@JsonIgnore`, read/write-only
access, null policies, inclusion, naming and field/method mix-ins reach the normal
discovery path. Concrete generic collections retain their element types. Private
members follow each mapper's access-override policy; one mapper granting access
does not grant it to another. Custom DTO and property serializers/deserializers
remain in charge. Invalid input conversions and throwing setters/getters retain
Jackson's mapping-error path. An ignored field can suppress its associated getter.

This covers concrete, non-static, no-argument, non-void methods named `getX`, boolean
`isX`, or directly annotated with `@JsonProperty`/`@JsonGetter`. Synthetic/bridge
methods are excluded. Added setters must be nonstatic, concrete, single-argument
`void` methods named `setX` or directly annotated with `@JsonProperty`/`@JsonSetter`.
Nonstatic, nonsynthetic fields carry their saved annotations, type annotations
and generic signature. Jackson retains its normal visibility/transient filtering.
Final added fields can be read but cannot be written, even with access overriding
enabled. Fluent setters, new constructor/record creators and builder mutation are
outside this addition. The original class's ordinary reflection still cannot see
the added members. The Jackson hook must load before Jackson databind; attaching
after Jackson loaded does not install it retroactively.

Cache refresh discovers ObjectMapper beans in captured Spring contexts. A mapper
created privately outside those contexts can retain its previous shape. An
ObjectWriter retained from before a change can retain its property list, while its
added getters dispatch to updated bodies; obtain a new writer after reload for
the new shape. Retained ObjectReaders can likewise keep an earlier input shape;
obtain a fresh reader after reload. In-flight conversion is not paused or rolled
back. Application setter side effects are not a transaction: an error in a later
property does not undo earlier writes to an object being updated.

Verified on stock JDK 21 with Jackson 2.13.5 and Spring 5.3.39: real loopback HTTP,
old/new DTO objects, three mapper policies, nested/generic values, private getters,
null/empty inclusion, custom serializers and four successive edits. Input/field
regressions additionally cover four saves with real HTTP JSON, ordinary and child
application classloaders, alias/name changes, removal/restoration, private setters
and fields, generic conversion, null skipping, custom deserializers and original
reflection staying unchanged. Jackson 3,
other Jackson 2 versions and accessor-generating extensions such as Afterburner
are not verified; this support targets Jackson's normal reflective accessor path.

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

An added listener can also return a single event object:

```java
@EventListener
private OrderAccepted onOrder(OrderCreated event) {
    return new OrderAccepted(event.id());
}
```

Spring publishes the returned object through the same application context; it
retains its identity as an `ApplicationEvent` or a payload event's payload. A
`null` return, a rejected condition or an absent/skipped singleton publishes no
result. User exceptions follow Spring's normal delivery behavior. The return
can be declared as `Object`, but its actual value is checked too: primitive/array,
Iterable/collection, Map, stream, Future/CompletionStage, JDK Flow.Publisher and
reactive-streams Publisher returns are outside this single-event scope. Unsupported
declared types prevent registration; an unsupported actual result throws before
Spring can publish, expand or subscribe to it. The handler's preceding side
effects are not rolled back. Existing reflected listeners keep their usual Spring
return behavior. Use distinct input/output event types when chaining listeners;
Reclazz does not prevent application event feedback loops.

Saving again replaces the added registrations. Removing the method or its
annotation stops it from receiving subsequent publications after reload has
completed. Refreshing existing listeners scans only the affected beans;
other beans and manually registered listeners keep their registrations.
Multiple singleton instances and multiple contexts are handled separately.
Each invocation reads the current singleton, so dependency refreshes can replace
the bean without leaving its listener on a destroyed instance. An absent bean
is skipped without being created; a replacement proxy is skipped and reported.

The added-method scope is a direct `@EventListener` on an instance method returning
`void` or a single event object, including a private method, with exactly one
reference event parameter and no generic method signature. The bean class must carry a Spring stereotype
recognized by Reclazz. Extra runtime method annotations are limited to
`@Order` and `@Deprecated`. Added `@TransactionalEventListener` methods have the
[separate scope below](#transactional-event-listeners-added-after-startup).
Added `@Async` methods, composed listener annotations, generic signatures, proxies/subclasses,
prototype beans, and classes registered only through XML or `@Bean` without a
recognized stereotype require a restart. The added path requires the default
Spring event listener factory; the standard transactional factory can coexist
with it. Custom factories are reported as unsupported for additions. Existing reflected
listeners continue to use the application's factories.

Event delivery is not paused during reload. Retired added registrations skip
processing; a callback body already executing can finish. The MVC request boundary does not cover event publication
from other threads. If registration fails, previous added registrations and any
partially registered replacements for that class are removed in the affected
context. There is no rollback to the previous listener declaration. Correct the
declaration and compile again. Spring expression syntax and user exceptions
remain subject to Spring's normal event delivery behavior.

This path is runtime-tested on Spring 5.3.39 with JDK 21. Spring 6 compatibility
has source-level checks but has not been verified in a running application.

### Transactional event listeners added after startup

On a supported unproxied Spring singleton, add a direct transaction listener
and compile the class:

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void accepted(OrderAccepted event) {
    notifications.record(event.id());
}
```

The application's standard `TransactionalEventListenerFactory` must be present
and precede the default event factory. Reclazz uses Spring's native transactional
adapter and synchronization lifecycle. `BEFORE_COMMIT`, `AFTER_COMMIT`,
`AFTER_ROLLBACK` and `AFTER_COMPLETION`, direct `@Order`, event filtering,
conditions and `fallbackExecution` are supported. Default fallback skips events
published without a transaction. Preserved argument names work in conditions;
use `#a0` or `#p0` when the compiler did not preserve names.

Callbacks retain Spring's transaction semantics: a `BEFORE_COMMIT` exception can
roll back the transaction, while an after-completion callback cannot undo an
already completed commit. This feature does not give callback writes a new
transaction or promise to commit them separately.

An added registration is retired before removal or replacement. Events already
queued for that registration are skipped when their transaction phase arrives,
including condition evaluation. They are not replayed through the new listener.
Newly published events use the replacement; a method body already executing is
not interrupted. This retirement policy applies to added registrations, not to
listeners reflected at application startup. Failed removal keeps the retired
registration indexed so a later save can retry cleanup without duplicate delivery.
Callbacks also skip an inactive context or an absent singleton; they resolve the
current unproxied singleton when invocation begins.

Support requires one reference event parameter, a `void` return, a direct
`@TransactionalEventListener` and the existing stereotype/singleton restrictions.
Do not combine it with a direct `@EventListener` on the same method. Extra advice
such as `@Transactional` or `@Async`, result publication, generic signatures,
composed annotations, proxies, custom factories and reactive transaction context
remain outside this added-method scope. Ordinary added listeners continue to work
in contexts with the standard transaction factory.

Verified with Spring 5.3.39, JDBC/H2 and SapMachine 21, including real commit and
rollback, native phase/order comparison, pending events during replacement/removal,
restoration and ordinary/child classloaders. No production dependency was added.
Other Spring versions, reactive transaction managers and a JDK 17 runtime have
not been verified for this extension.

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
[restricted check below](#computed-value-constructor-parameters). SAP Commerce's
`Config` path retains its separate behavior. The legacy path for `.properties`
files without Boot resource origins applies only added or changed keys. For
Boot-owned files, see [YAML and removal](#yaml-and-removed-configuration-keys).
A syntactically valid truncated file
cannot be distinguished from an intentional save; malformed or unreadable
files do not advance the baseline.

### YAML and removed configuration keys

Already-loaded local Spring Boot `.properties`, `.yml` and `.yaml` files use the
application's own loaders. Nested YAML keys and indexed lists reach the same
binding path as startup. Reclazz identifies the file through Boot's resource
origins and replaces its sources at their existing positions. Command-line and
other higher-priority sources continue to win; an edited inactive profile file
is not promoted into a new override.

Deleting a key reveals its value from lower-priority sources, or the consumer's
default when no source supplies it. Emptying or deleting a previously identified
single-document file works, and recreating it restores its values. For mutable
`@ConfigurationProperties` beans, removal copies freshly bound, validated JavaBean
properties into the existing object. The bean's identity and existing holders
are preserved; nested property objects can be replaced by their setters. Types
with read-only or write-only bean properties are held as uncheckable on removal.
Constructor-bound beans retain the existing recreation path and its reference
limits. `@Value` fields resolve against the resulting Environment.

Validation covers every affected context before any file source is changed.
Malformed input or invalid conversion keeps the previous sources and values.
A live setter failure can partially apply values; it is reported as partial and
the same file remains pending for an identical-save retry. A waiting request
boundary uses the captured file bytes. It does not reread a later save.

Supported YAML documents use the current active profiles and
`spring.config.activate.on-profile` expressions. The set and positions of active
documents must stay the same. Changing activation, imports, config locations,
profile groups or cloud-platform selection requires a restart. Files containing
other `spring.config.*` or `spring.profiles*` controls are held. New or initially
empty files with no identifiable Boot source, custom source loaders and changed
source ownership are outside this path. Applications with Boot file sources do
not fall back to a global override for an unowned file. SAP configuration removal
is outside this feature. Removing a logging level without a lower configured
level still needs a logger reset/restart.

Verified with Spring Boot 2.7.18, Spring 5.3.39, SnakeYAML 1.30 and stock JDK 21:
real startup-agent HTTP checks for properties and YAML, old bean holders,
lists/defaults, invalid conversion/syntax recovery, deletion and restoration;
unit regressions cover precedence, active/inactive documents, multiple contexts,
immutable candidates, external source replacement and partial-application retry.
Other Boot/SnakeYAML versions are not verified. SnakeYAML is a test dependency;
the production agent uses the application's parser and packages no YAML library.

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
- Jackson JSON input/output through the [added property adapter](#jackson-properties-added-after-startup)
- Hybris Jalo-layer property access (`jaloItem.setProperty(...)`)
- Flexible search with new attribute columns (after HAC
  `updatesystem` for items.xml changes)

**Needs server restart on standard JVMs (works immediately on JBR/DCEVM):**
- `Class.getMethod("setNewThing", ...)` on the original class
- Reflective consumers without a supported adapter (Hybris `ModelService`'s
  attribute dispatch, Gson, and Jackson use outside the property adapter's scope)
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

### Added Static Field Values

On a stock JDK, a static field added after startup gets its compile-time constant
or the value computed by an isolated initializer. Conditional expressions work
too, for example:

```java
private static final String MODE =
    System.getProperty("app.mode") != null ? "on" : "off";
```

Reclazz runs only the new field's initializer, including its condition and the
selected branch. Nested conditions, short-circuit boolean expressions, object
construction and primitive/reference results are supported when the expression
can be separated. Other static blocks and existing static field values are not
reset. Initializers for newly added fields run in source order. Later saves keep
an initialized field's current value, including null or a value the application
wrote; changing the initializer does not reinitialize that field.

The conditional path accepts forward branches that finish at one field
assignment. An outside condition that can skip the assignment, loops, switches,
multiple assignments to the field, shared field/array writes, local variables,
locking and separate void/discarded-result calls are refused. Conditional
initializers in a class initializer containing any try/catch are also refused,
even if that handler is elsewhere. An unsupported initializer leaves the field
at its type default and the reload names the reason. Calls that compute the
condition or selected value retain their usual application side effects; this
does not make initialization transactional or add rollback on failure.

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
null included, is kept; and subsequent reads reuse the stored value.

Isolated conditional expressions also work for classes with one constructor.
For example, add this field when `enabled` is already part of the object:

```java
private String mode = enabled ? "on" : "off";
```

The condition uses that object's state at the field's first read. Only the
selected branch runs; nested conditions, short-circuit booleans, object
construction and primitive/reference values are supported. Existing objects can
therefore receive different values from the same added initializer. A later
save preserves values already stored; an object that has not read the field yet
uses the current initializer. The constructor and preceding initializer blocks
are not replayed.

This conditional path requires one assignment to the field in a class with
exactly one constructor. Multiple constructors are refused even when their
initializers appear equivalent. Constructor arguments and other local variables,
outside branches, loops, switches, shared field/array writes, locking and
separate void/discarded-result calls are unsupported. Any try/catch in the
constructor also refuses conditional initialization, even if the handler is
elsewhere. The existing concurrent-first-read behavior is unchanged: computations
can overlap, and the first stored value wins. This does not add rollback for
side effects or change how a throwing initializer is retired.

What cannot be lifted keeps the type default (`null`, `0`, `false`) on
pre-existing objects, and the reload names the field and the reason:

- an assignment that reads a constructor argument (`this.upper =
  name.toUpperCase()` in the constructor body), because the object no longer
  has the argument;
- an initialiser whose control flow leaves the assignment, loops, uses a switch
  or try/catch, or shares a computation with another field;
- an initialiser that throws on first read: the field reads the default and
  the initialiser is not tried again.

On JetBrains Runtime or DCEVM the field is a real field on the redefined
class, so reflection sees it, and objects from before the reload keep the type
default there.

---

## What Cannot Be Hot-Reloaded

The action depends on which part changed. Regeneration and reload do not update the database schema or recreate the platform:

| Change | Why | What To Do |
|---|---|---|
| `*-items.xml` changes | Reclazz runs platform `ant build` and reloads generated bytecode; persistence changes also need a type-system/schema update | Run Update Running System in HAC for new persistent attributes; original-class reflection on added members needs enhanced redefinition or restart |
| Generated model/DTO classes | Method bodies and supported structural changes reload after code generation | Use the same companion/enhanced reflection limits as handwritten classes |
| New extensions | Extension list is fixed at startup | `ant all` + restart |
| Class hierarchy changes | JVM does not allow changing superclass/interfaces | Restart |
| New JAR dependencies | Classpath is fixed at JVM startup | Restart |
| `*-spring.xml` bean definitions | Supported bean-definition changes are diffed and applied | Unsupported changes are reported; follow that diagnostic instead of assuming every XML edit needs a restart |


### SAP reload outcomes and verification

An identical property save retries keys whose runtime write failed. Successful
keys are acknowledged individually, so a later HAC edit to one of those keys is
not overwritten while another key retries. Unresolved placeholders remain
pending; applying a value does not recreate startup-only consumers.

Code generation distinguishes the watcher's own timestamp echo from changed XML
contents. A second edit during `ant build` queues another build, even within the
three-second echo window.

ImpEx auto-import refuses literal and macro-expanded REMOVE headers unless
`impexAllowRemove=true`. Unresolved/recursive header macros are also refused;
use HAC for inputs whose safety the bounded header scan cannot establish.
Macros in ordinary value rows remain usable. This scan is not a full ImpEx
parser or a sandbox for executable import scripts.

SAP interceptor refresh captures Spring mapping identities before bean refresh
and explicitly unregisters/registers them in the tenant's registry afterwards.
Missing registry/mapping or a failed update is not reported as re-registration.
The registry contract is checked against the owner's SAP 2211-jdk21.8 SDK;
model-save behavior additionally needs the live test extension described in
[the integration tests](../integration-test/README.md).
