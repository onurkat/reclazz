/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.ReloadEffects;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.util.Reflect;

import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Registers only added factory methods, without rerunning configuration parsing. */
public final class SpringAddedBeanReloader {
    private final PlatformContext platform;
    // Values must not retain the factory key through a supplier/bean/context.
    private final Map<Object, Registry> owned = new WeakHashMap<>();
    private record Owned(WeakReference<Class<?>> owner, WeakReference<Object> definition,
                         AtomicReference<WeakReference<Object>> instance, List<String> aliases,
                         String targetName, WeakReference<Object> targetDefinition, boolean jacksonCustomizer) { }
    private final Set<Object> jacksonChanges = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final class Registry {
        final Map<String, Owned> definitions = new ConcurrentHashMap<>();
        final Map<String,String> hierarchyOwners = new HashMap<>();
        final Map<String,byte[]> hierarchyMethods = new HashMap<>();
        boolean observing;
        final Map<String, byte[]> rawMethods = new HashMap<>();
    }

    public SpringAddedBeanReloader(PlatformContext platform) { this.platform = platform; }

    static void reportBootApplicationFactories(Class<?> type, Set<String> added, byte[] bytes) {
        for (String method : AddedBeanAdapter.addedBootApplicationFactories(bytes, added))
            refuse(type, method + " was not registered on @SpringBootApplication. "
                    + "Restart to register it, or declare the factory on a direct @Configuration class.");
    }

    public synchronized boolean reloadBeanMethods(Class<?> type, Set<String> added, byte[] bytes) {
        boolean registered = reloadFactories(type, added, bytes);
        if (batchDepth > 0) {
            if (!registered && !jacksonChanges.isEmpty()) jacksonRegistrationFailed = true;
            return registered;
        }
        return finishJackson(registered) && registered;
    }

    private boolean finishJackson(boolean registered) {
        boolean applied = true;
        try {
            for (Object factory : jacksonChanges) {
                if (!registered || !JacksonMapperRefresh.refresh(factory)) {
                    if (!registered) JacksonMapperRefresh.restart();
                    applied = false;
                }
            }
        } finally { jacksonChanges.clear(); }
        return applied;
    }

    private boolean reloadFactories(Class<?> type, Set<String> added, byte[] bytes) {
        if (bytes == null) return !hierarchyEdits.containsKey(type) || batchDepth > 0 || finishHierarchy();
        var plan = AddedBeanAdapter.inspect(bytes, added);
        try {
            PreparedRawConfigs raw = prepareRaw(type, added, bytes);
            if (hierarchyEdits.containsKey(type)) return batchDepth > 0 || finishHierarchy();
            if (!raw.scopes.isEmpty()) return reloadRaw(type, plan, bytes, raw);
        } catch (Throwable failure) {
            refuse(type, Failures.describe(failure)); return false;
        }
        SpringConfigurationCalls.publish(type, plan);
        for (String reason : plan.refused()) refuse(type, reason);
        boolean success = plan.refused().isEmpty();
        boolean foundConfiguration = false;
        for (Object context : platform.getAllApplicationContexts()) {
            Object factory = SpringBeans.getBeanFactory(context);
            if (factory == null) continue;
            try {
                Registry registry = owned.computeIfAbsent(factory, ignored -> new Registry());
                Map<String, Owned> registrations = registry.definitions;
                // Removal/annotation removal still reaches here with an empty plan.
                for (var entry : List.copyOf(registrations.entrySet())) {
                    if (entry.getValue().owner().get() != type) continue;
                    if (removeOwned(factory, entry.getKey(), entry.getValue()) && !entry.getValue().jacksonCustomizer()) {
                        ReloadEffects.note("added bean " + entry.getKey() + " removed");
                    }
                    registrations.remove(entry.getKey());
                }
                if (plan.factories().isEmpty()) continue;
                String[] configs = (String[]) factory.getClass().getMethod("getBeanNamesForType",
                        Class.class, boolean.class, boolean.class).invoke(factory, type, true, false);
                if (configs.length == 0) continue;
                foundConfiguration = true;
                if (configs.length != 1) throw new IllegalStateException("requires exactly one local configuration bean");
                String configName = configs[0];
                if (!Boolean.TRUE.equals(call(factory, "isSingleton", configName)))
                    throw new IllegalStateException("configuration must be a singleton");
                Object config = call(factory, "getBean", configName);
                if (!SpringConfigurationCalls.matches(config, type, factory, plan.full()))
                    throw new IllegalStateException("requires the native singleton configuration without additional proxies");
                observeCreations(factory, registry);
                Object environment = context.getClass().getMethod("getEnvironment").invoke(context);
                ClassLoader spring = factory.getClass().getClassLoader();
                List<AddedBeanAdapter.Factory> prepared = new ArrayList<>();
                for (var method : plan.factories()) {
                    try {
                        // A @Profile that the active environment does not accept
                        // leaves the bean unregistered, the same as Spring at
                        // startup. The remove-then-add above already dropped a
                        // prior registration whose profile no longer matches.
                        // When Conditional is present, Profile must join the
                        // same ordered native evaluation rather than bypass an
                        // earlier custom condition with a separate profile gate.
                        boolean skipped = method.conditional()
                                ? SpringBeanConditions.shouldSkip(bytes, method.method(), context, factory, spring)
                                : !profileAccepted(environment, spring, method.profiles());
                        if (skipped) {
                            ReloadEffects.note("added bean " + method.name() + " not registered: "
                                    + (method.conditional() ? "condition did not match" : "profile inactive"));
                            continue;
                        }
                        registerDefinition(type, factory, configName, method, registrations, plan.full());
                        prepared.add(method);
                    } catch (Throwable failure) {
                        success = false;
                        refuse(type, method.method().name + method.method().desc + ": " + Failures.describe(failure));
                    }
                }
                // An added product can inject another product from this save,
                // even when its factory appears first in the source file. A lazy
                // or prototype bean is left for its first getBean instead.
                for (var method : prepared) {
                    try {
                        if (method.onDemand()) {
                            if (registrations.get(method.name()).jacksonCustomizer()) continue;
                            ReloadEffects.note("added bean " + method.name()
                                    + " registered (" + (method.lazy() ? "lazy" : method.scope()) + ", created on demand)");
                            StatusReporter.detail("Added @Bean registered: " + method.name()
                                    + " (" + (method.lazy() ? "lazy" : method.scope()) + ")");
                            continue;
                        }
                        initialize(factory, method.name(), registrations);
                    } catch (Throwable failure) {
                        success = false;
                        refuse(type, method.method().name + method.method().desc + ": " + Failures.describe(failure));
                    }
                }
            } catch (Throwable failure) {
                success = false;
                refuse(type, Failures.describe(failure));
            }
        }
        if (!plan.factories().isEmpty() && !foundConfiguration) {
            refuse(type, "no local configuration bean found");
            success = false;
        }
        return success;
    }

    private record RawScope(Object context, Object factory, Object definition, String name,
                            Object receiver, List<AddedBeanAdapter.Factory> methods) { }

    static final class PreparedRawConfigs {
        private final List<RawScope> scopes;
        PreparedRawConfigs(List<RawScope> scopes) { this.scopes = List.copyOf(scopes); }
        boolean preserves(Object context, String name, Object receiver) throws Exception {
            for (RawScope scope : scopes) if (scope.context == context && scope.name.equals(name)
                    && scope.receiver == receiver) {
                requireCurrent(scope);
                return true;
            }
            return false;
        }
    }

    // A package-private seam for the race-between-preflight-and-registration test.
    // No global hook, and never retained by a published route or weak registry.
    private Runnable afterRawPreflight = () -> { };
    void afterRawPreflight(Runnable action) { afterRawPreflight = Objects.requireNonNull(action); }

    synchronized PreparedRawConfigs prepareRaw(Class<?> type, Set<String> added, byte[] bytes) throws Exception {
        if (bytes == null) {
            byte[] inherited = hierarchyEdits.get(type);
            return inherited == null ? new PreparedRawConfigs(List.of()) : captureHierarchy(type, inherited);
        }
        var plan = AddedBeanAdapter.inspect(bytes, added);
        if (!plan.full()) return captureHierarchy(type, bytes);
        List<RawScope> scopes = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object context : platform.getAllApplicationContexts()) {
            Object factory = SpringBeans.getBeanFactory(context);
            if (factory == null || !seen.add(factory)) continue;
            String[] names = (String[]) factory.getClass().getMethod("getBeanNamesForType",
                    Class.class, boolean.class, boolean.class).invoke(factory, type, true, false);
            boolean raw = false, enrolled = false;
            for (String name : names) {
                Object receiver = call(factory, "getSingleton", name);
                if (receiver != null && receiver.getClass() == type) {
                    raw = true; enrolled |= SpringConfigurationCalls.enrolled(receiver, type);
                }
            }
            if (!raw) continue; // The native enhanced path stays exactly as before.
            if (plan.factories().isEmpty() && !hasRawOwner(factory, type) && !enrolled) continue;
            if (!plan.refused().isEmpty()) throw new IllegalStateException(String.join("; ", plan.refused()));
            if (names.length != 1) throw new IllegalStateException("requires exactly one local raw configuration");
            String name = names[0];
            Object receiver = call(factory, "getSingleton", name);
            Object definition = call(factory, "getBeanDefinition", name);
            requireStateless(type, bytes);
            if (!SpringConfigurationCalls.rawShape(type, definition))
                throw new IllegalStateException("requires the measured raw full-configuration definition");
            List<AddedBeanAdapter.Factory> methods = new ArrayList<>();
            Object environment = context.getClass().getMethod("getEnvironment").invoke(context);
            for (var method : plan.factories()) {
                if (method.conditional()
                        ? SpringBeanConditions.shouldSkip(bytes, method.method(), context, factory, factory.getClass().getClassLoader())
                        : !profileAccepted(environment, factory.getClass().getClassLoader(), method.profiles())) continue;
                preflightName(type, factory, method);
                rejectInfrastructure(MethodType.fromMethodDescriptorString(method.method().desc, type.getClassLoader()).returnType(),
                        factory.getClass().getClassLoader());
                methods.add(method);
            }
            RawScope scope = new RawScope(context, factory, definition, name, receiver, methods);
            requireCurrent(scope);
            if (SpringConfigurationCalls.enrolled(receiver, type)
                    && !SpringConfigurationCalls.matches(receiver, type, factory, true))
                throw new IllegalStateException("raw receiver ownership changed");
            scopes.add(scope);
        }
        return new PreparedRawConfigs(scopes);
    }

    private int batchDepth;
    private boolean jacksonRegistrationFailed;
    private final Map<Class<?>,byte[]> hierarchyEdits = new LinkedHashMap<>();
    private final Map<SpringHierarchyBeans.Scope,RawScope> hierarchyScopes = new LinkedHashMap<>();

    synchronized void beginBatch() { batchDepth++; }
    synchronized void endBatch() {
        if (batchDepth > 0 && --batchDepth == 0) {
            try { finishJackson(finishHierarchy() && !jacksonRegistrationFailed); }
            finally { jacksonRegistrationFailed = false; }
        }
    }

    synchronized void clearUnbatchedHierarchy() {
        if (batchDepth == 0) {
            hierarchyScopes.clear();
            hierarchyEdits.clear();
        }
    }

    synchronized void nativeHierarchy(Class<?> type, byte[] bytes) {
        if (type == null || bytes == null) return;
        try { captureHierarchy(type, bytes); }
        catch (Throwable failure) { refuse(type, Failures.describe(failure)); }
    }

    synchronized void hierarchyHelper(Class<?> type, byte[] bytes) {
        if (bytes == null) {
            if (batchDepth == 0 && hierarchyEdits.containsKey(type)) finishHierarchy();
            return;
        }
        try {
            captureHierarchy(type, bytes);
            if (batchDepth == 0) finishHierarchy();
        } catch (Throwable failure) { refuse(type, Failures.describe(failure)); }
    }

    private PreparedRawConfigs captureHierarchy(Class<?> type, byte[] bytes) throws Exception {
        List<RawScope> preserve = new ArrayList<>();
        for (var scope : SpringHierarchyBeans.scopes(platform.getAllApplicationContexts(), type)) {
            Registry registry = owned.get(scope.factory());
            boolean previouslyOwned = registry != null && registry.hierarchyOwners.containsValue(scope.name());
            Map<Class<?>,byte[]> current = new LinkedHashMap<>(hierarchyEdits);
            current.put(type, bytes);
            if (!SpringHierarchyBeans.relevant(scope, current, previouslyOwned)) {
                // Keep the latest creation evidence even for a save that adds no factory.
                scope.inventory().savedHierarchy.put(type.getName(), bytes);
                continue;
            }
            var raw = new RawScope(scope.context(), scope.factory(), call(scope.factory(), "getBeanDefinition", scope.name()),
                    scope.name(), scope.receiver(), List.of());
            requireCurrent(raw);
            hierarchyScopes.putIfAbsent(scope, raw);
            hierarchyEdits.put(type, bytes);
            if (scope.receiver().getClass() == type) preserve.add(raw);
        }
        return new PreparedRawConfigs(preserve);
    }

    private record Product(SpringHierarchyBeans.Scope scope, SpringHierarchyBeans.Selection selected, Registry registry) {
        String name() { return selected.method().name(); }
    }

    private boolean finishHierarchy() {
        if (hierarchyScopes.isEmpty()) return true;
        var desired = new ArrayList<Product>();
        var staged = new LinkedHashMap<Product,Owned>();
        try {
            Map<Object,Set<String>> claimed = new IdentityHashMap<>();
            for (var entry : hierarchyScopes.entrySet()) {
                var scope = entry.getKey();
                requireCurrent(entry.getValue());
                Registry registry = owned.computeIfAbsent(scope.factory(), ignored -> new Registry());
                ClassLoader spring = scope.factory().getClass().getClassLoader();
                Object environment = scope.context().getClass().getMethod("getEnvironment").invoke(scope.context());
                for (var selected : SpringHierarchyBeans.select(scope, hierarchyEdits)) {
                    var method = selected.method();
                    if (method.conditional()
                            ? SpringBeanConditions.shouldSkip(selected.classfile(), method.method(), scope.context(), scope.factory(), spring)
                            : !profileAccepted(environment, spring, method.profiles())) continue;
                    if (startupOverride(scope, selected, registry)) continue;
                    rejectInfrastructure(selected.resolved().getReturnType(), spring);
                    preflightName(selected.declaring(), scope.factory(), method);
                    Set<String> names = claimed.computeIfAbsent(scope.factory(), ignored -> new HashSet<>());
                    for (String name : method.names()) if (!names.add(name))
                        throw new IllegalStateException("multiple inherited factories claim " + name);
                    desired.add(new Product(scope, selected, registry));
                }
                for (var previous : registry.hierarchyOwners.entrySet()) {
                    if (scope.name().equals(previous.getValue())
                            && !stillOwned(scope.factory(), previous.getKey(), registry.definitions.get(previous.getKey())))
                        throw new IllegalStateException("inherited registration was replaced: " + previous.getKey());
                }
            }
            for (Product product : desired) {
                requireCurrent(hierarchyScopes.get(product.scope()));
                observeCreations(product.scope().factory(), product.registry());
                if (!product.registry().definitions.containsKey(product.name())) {
                    preflightName(product.selected().declaring(), product.scope().factory(), product.selected().method());
                    stageInherited(product, staged);
                }
            }
            for (var product : staged.keySet()) {
                requireCurrent(hierarchyScopes.get(product.scope()));
                if (!stillOwned(product.scope().factory(), product.name(), product.registry().definitions.get(product.name())))
                    throw new IllegalStateException("staged inherited factory changed: " + product.name());
            }
        } catch (Throwable failure) {
            discardStaged(staged, failure);
            return hierarchyFailure(failure);
        }
        try {
            for (var scope : hierarchyScopes.keySet()) {
                requireCurrent(hierarchyScopes.get(scope));
                var registry = owned.get(scope.factory());
                for (var entry : List.copyOf(registry.hierarchyOwners.entrySet())) {
                    if (!entry.getValue().equals(scope.name())) continue;
                    var next = desired.stream().filter(p -> p.scope().equals(scope) && p.name().equals(entry.getKey()))
                            .findFirst().orElse(null);
                    if (next != null && Arrays.equals(registry.hierarchyMethods.get(next.name()), fingerprint(next.selected().method())))
                        continue;
                    var old = registry.definitions.get(entry.getKey());
                    if (!removeOwned(scope.factory(), entry.getKey(), old))
                        throw new IllegalStateException("inherited registration was replaced: " + entry.getKey());
                    registry.definitions.remove(entry.getKey(), old);
                    registry.hierarchyOwners.remove(entry.getKey());
                    registry.hierarchyMethods.remove(entry.getKey());
                    if (next != null) {
                        stageInherited(next, staged);
                    }
                }
            }
            for (var product : staged.keySet()) {
                var method = product.selected().method();
                if (!method.onDemand())
                    initialize(product.scope().factory(), product.name(), product.registry().definitions);
            }
            for (var scope : hierarchyScopes.keySet()) {
                hierarchyEdits.forEach((type, bytes) -> {
                    if (type.isInstance(scope.receiver())) scope.inventory().savedHierarchy.put(type.getName(), bytes);
                });
            }
            return true;
        } catch (Throwable failure) {
            discardStaged(staged, failure);
            return hierarchyFailure(failure);
        } finally { hierarchyScopes.clear(); hierarchyEdits.clear(); }
    }

    private void stageInherited(Product product, Map<Product,Owned> staged) throws Throwable {
        byte[] signature = fingerprint(product.selected().method());
        registerInherited(product.scope(), product.selected(), product.registry());
        staged.put(product, product.registry().definitions.get(product.name()));
        product.registry().hierarchyOwners.put(product.name(), product.scope().name());
        product.registry().hierarchyMethods.put(product.name(), signature);
    }

    private void discardStaged(Map<Product,Owned> staged, Throwable failure) {
        var products = List.copyOf(staged.keySet());
        for (int i = products.size() - 1; i >= 0; i--) {
            Product product = products.get(i);
            var registry = product.registry();
            var registration = registry.definitions.get(product.name());
            if (registration != null && registration == staged.get(product))
                discardFailed(product.scope().factory(), product.name(), registration, registry.definitions, failure);
            if (!registry.definitions.containsKey(product.name())) {
                registry.hierarchyOwners.remove(product.name());
                registry.hierarchyMethods.remove(product.name());
            }
        }
    }

    private boolean hierarchyFailure(Throwable failure) {
        for (Class<?> changed : hierarchyEdits.keySet()) refuse(changed, Failures.describe(failure));
        hierarchyScopes.clear();
        hierarchyEdits.clear();
        return false;
    }

    private static boolean startupOverride(SpringHierarchyBeans.Scope scope, SpringHierarchyBeans.Selection selected,
                                           Registry registry) throws Exception {
        String name = selected.method().name();
        if (registry.definitions.containsKey(name) || !Boolean.TRUE.equals(call(scope.factory(), "containsBeanDefinition", name)))
            return false;
        Object definition = call(scope.factory(), "getMergedBeanDefinition", name);
        Object owner = definition.getClass().getMethod("getFactoryBeanName").invoke(definition);
        if (!scope.name().equals(owner)) return false;
        var method = startupFactoryMethod(scope.factory(), name, definition, scope.receiver().getClass());
        return method != null
                && selected.declaring() != method.getDeclaringClass() && selected.declaring().isAssignableFrom(method.getDeclaringClass())
                && method.getName().equals(selected.resolved().getName())
                && Arrays.equals(method.getParameterTypes(), selected.resolved().getParameterTypes());
    }

    static java.lang.reflect.Method startupFactoryMethod(Object factory, String name, Object definition,
                                                         Class<?> configuration) throws Exception {
        Object resolved = definition.getClass().getMethod("getResolvedFactoryMethod").invoke(definition);
        if (resolved instanceof java.lang.reflect.Method method) return method;
        // A scanned lazy definition has ASM method metadata before any product is instantiated.
        Object original = call(factory, "getBeanDefinition", name);
        var getter = Reflect.findMethod(original.getClass(), "getFactoryMethodMetadata");
        Object metadata = getter == null ? null : getter.invoke(original);
        if (metadata == null) return null;
        var api = Class.forName("org.springframework.core.type.MethodMetadata", false, metadata.getClass().getClassLoader());
        String declaringName = (String) api.getMethod("getDeclaringClassName").invoke(metadata);
        Class<?> declaring = Class.forName(declaringName, false, configuration.getClassLoader());
        if (!declaring.isAssignableFrom(configuration)) return null;
        String methodName = (String) api.getMethod("getMethodName").invoke(metadata);
        String result = (String) api.getMethod("getReturnTypeName").invoke(metadata);
        java.lang.reflect.Method match = null;
        for (var candidate : declaring.getDeclaredMethods()) {
            if (candidate.isBridge() || !candidate.getName().equals(methodName)
                    || !candidate.getReturnType().getTypeName().equals(result)) continue;
            if (match != null) return null;
            match = candidate;
        }
        return match;
    }

    private boolean hasRawOwner(Object factory, Class<?> type) {
        Registry registry = owned.get(factory);
        return registry != null && registry.definitions.values().stream().anyMatch(o -> o.owner().get() == type);
    }

    private static void requireCurrent(RawScope scope) throws Exception {
        if (!Boolean.TRUE.equals(scope.context.getClass().getMethod("isActive").invoke(scope.context))
                || SpringBeans.getBeanFactory(scope.context) != scope.factory
                || !Boolean.TRUE.equals(call(scope.factory, "containsBeanDefinition", scope.name))
                || call(scope.factory, "getBeanDefinition", scope.name) != scope.definition
                || call(scope.factory, "getSingleton", scope.name) != scope.receiver
                || !Boolean.TRUE.equals(call(scope.factory, "isSingleton", scope.name)))
            throw new IllegalStateException("raw configuration identity changed; restart required");
    }

    private static final Set<String> LIFECYCLE = Set.of("Ljakarta/annotation/PostConstruct;", "Ljakarta/annotation/PreDestroy;",
            "Ljavax/annotation/PostConstruct;", "Ljavax/annotation/PreDestroy;");
    private static final Set<String> INFRASTRUCTURE = Set.of("org.springframework.beans.factory.FactoryBean",
            "org.springframework.beans.factory.config.BeanPostProcessor",
            "org.springframework.beans.factory.config.BeanFactoryPostProcessor");

    /**
     * The live raw configuration singleton was created, injected and initialized at startup. Its existing state is fine
     * for an added factory to read: instance fields, the constructor Spring used, lifecycle callbacks and lifecycle
     * interfaces all already ran. What this save must not do is change that state, because nothing would run again:
     * an added field is never injected, a changed constructor is never called, an added callback never fires.
     */
    private static void requireStateless(Class<?> type, byte[] bytes) {
        if (type.getSuperclass() != Object.class)
            throw new IllegalStateException("raw configuration inheritance requires a restart");
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node, org.objectweb.asm.ClassReader.SKIP_FRAMES);
        if (!"java/lang/Object".equals(node.superName))
            throw new IllegalStateException("raw configuration inheritance requires a restart");
        Set<String> loadedInterfaces = new TreeSet<>();
        for (Class<?> iface : type.getInterfaces()) loadedInterfaces.add(iface.getName().replace('.', '/'));
        if (!loadedInterfaces.equals(new TreeSet<>(node.interfaces)))
            throw new IllegalStateException("raw configuration interfaces changed; restart required");
        for (String name : INFRASTRUCTURE) {
            try { if (Class.forName(name, false, type.getClassLoader()).isAssignableFrom(type))
                throw new IllegalStateException("raw configuration that is also Spring infrastructure requires a restart"); }
            catch (ClassNotFoundException absent) { /* not visible to the application: cannot be implemented */ }
        }
        Set<String> loadedFields = new TreeSet<>();
        for (var field : com.onurkat.reclazz.bootstrap.ReflectionBridge.physicalDeclaredFields(type))
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) && !field.getName().startsWith(com.onurkat.reclazz.bootstrap.InjectedNames.PREFIX))
                loadedFields.add(field.getName() + ":" + field.getType().descriptorString());
        Set<String> savedFields = new TreeSet<>();
        for (var field : node.fields)
            if ((field.access & org.objectweb.asm.Opcodes.ACC_STATIC) == 0 && !field.name.startsWith(com.onurkat.reclazz.bootstrap.InjectedNames.PREFIX))
                savedFields.add(field.name + ":" + field.desc);
        if (!loadedFields.equals(savedFields))
            throw new IllegalStateException("raw configuration instance fields changed (an added field is never injected); restart required");
        Set<String> loadedConstructors = new TreeSet<>();
        for (var constructor : com.onurkat.reclazz.bootstrap.ReflectionBridge.physicalDeclaredConstructors(type))
            loadedConstructors.add(org.objectweb.asm.Type.getConstructorDescriptor(constructor));
        Set<String> savedConstructors = new TreeSet<>();
        Set<String> savedLifecycle = new TreeSet<>();
        for (var method : node.methods) {
            if (method.name.equals("<init>")) savedConstructors.add(method.desc);
            if (method.visibleAnnotations != null) for (var annotation : method.visibleAnnotations)
                if (LIFECYCLE.contains(annotation.desc)) savedLifecycle.add(annotation.desc + method.name + method.desc);
        }
        if (!loadedConstructors.equals(savedConstructors))
            throw new IllegalStateException("raw configuration constructors changed (the singleton is not constructed again); restart required");
        Set<String> loadedLifecycle = new TreeSet<>();
        for (var method : type.getDeclaredMethods()) for (var annotation : method.getDeclaredAnnotations()) {
            String descriptor = "L" + annotation.annotationType().getName().replace('.', '/') + ";";
            if (LIFECYCLE.contains(descriptor))
                loadedLifecycle.add(descriptor + method.getName() + org.objectweb.asm.Type.getMethodDescriptor(method));
        }
        if (!loadedLifecycle.equals(savedLifecycle))
            throw new IllegalStateException("raw configuration lifecycle callbacks changed (they do not run again); restart required");
        // Signatures are not enough: a same-signature constructor, callback or injection edit changes what a fresh
        // creation would produce. Compare with what was witnessed at first load, never with the current reflection.
        String startup = com.onurkat.reclazz.transform.CreationBaseline.startup(type);
        if (startup == null)
            throw new IllegalStateException("raw configuration startup creation state was not recorded; restart required");
        if (!startup.equals(com.onurkat.reclazz.transform.CreationBaseline.digest(bytes)))
            throw new IllegalStateException("raw configuration constructor, lifecycle or injection code changed since startup "
                    + "(the singleton is not created again); restart required");
    }

    private void preflightName(Class<?> type, Object factory, AddedBeanAdapter.Factory method) throws Exception {
        Registry registry = owned.get(factory);
        Owned previous = registry == null ? null : registry.definitions.get(method.name());
        boolean ours = previous != null && previous.owner().get() == type && stillOwned(factory, method.name(), previous);
        Map<?, ?> bindings = method.aliases().isEmpty() ? Map.of() : aliasBindings(factory);
        requireScope(factory, method);
        for (String name : method.registrationNames()) {
            boolean occupied = Boolean.TRUE.equals(call(factory, "containsBean", name))
                    || Boolean.TRUE.equals(call(factory, "containsBeanDefinition", name)) || Boolean.TRUE.equals(call(factory, "isAlias", name));
            if (occupied && !(ours && (name.equals(method.name()) || name.equals(previous.targetName()) || previous.aliases().contains(name) && method.name().equals(bindings.get(name)))))
                throw new IllegalStateException("bean name or alias '" + name + "' is already in use");
        }
    }

    private static byte[] fingerprint(AddedBeanAdapter.Factory method) {
        var writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, "FactoryFingerprint", null, "java/lang/Object", null);
        method.method().accept(writer); writer.visitEnd(); return writer.toByteArray();
    }

    private boolean reloadRaw(Class<?> type, AddedBeanAdapter.Plan plan, byte[] bytes, PreparedRawConfigs prepared) throws Throwable {
        // P already checked every scope, without changing old registrations/routes.
        afterRawPreflight.run();
        Map<RawScope, Map<String, Owned>> staged = new IdentityHashMap<>();
        Map<RawScope, SpringConfigurationCalls.Enrollment> tickets = new IdentityHashMap<>();
        try {
            // S: only genuinely new names; no prior product has been destroyed.
            for (RawScope scope : prepared.scopes) {
                requireCurrent(scope);
                tickets.put(scope, SpringConfigurationCalls.pending(scope.receiver, type, scope.context, scope.factory, scope.definition, scope.name));
                Registry registry = owned.computeIfAbsent(scope.factory, ignored -> new Registry());
                observeCreations(scope.factory, registry);
                Map<String, Owned> additions = new LinkedHashMap<>(); staged.put(scope, additions);
                for (var method : scope.methods) if (!registry.definitions.containsKey(method.name())) {
                    registerDefinition(type, scope.factory, scope.name, method, registry.definitions, true);
                    additions.put(method.name(), registry.definitions.get(method.name()));
                }
            }
            for (var scope : staged.entrySet()) for (var entry : scope.getValue().entrySet())
                if (!stillOwned(scope.getKey().factory, entry.getKey(), entry.getValue()))
                    throw new IllegalStateException("staged registration changed before commit");
        } catch (Throwable failure) {
            for (var scope : staged.entrySet()) for (var entry : scope.getValue().entrySet())
                discardFailed(scope.getKey().factory, entry.getKey(), entry.getValue(), owned.get(scope.getKey().factory).definitions, failure);
            for (var ticket : tickets.entrySet())
                SpringConfigurationCalls.abort(ticket.getKey().receiver, type, ticket.getValue());
            throw failure;
        }
        // C: destruction/initialization is irreversible; failed methods are not
        // authorized, and arbitrary user lifecycle work is never rolled back.
        boolean success = true;
        Map<RawScope, Set<String>> failed = new IdentityHashMap<>();
        for (RawScope scope : prepared.scopes) {
            Registry registry = owned.get(scope.factory);
            Set<String> failures = new HashSet<>(); failed.put(scope, failures);
            Map<String, AddedBeanAdapter.Factory> desired = new LinkedHashMap<>();
            for (var method : scope.methods) desired.put(method.name(), method);
            for (var entry : List.copyOf(registry.definitions.entrySet())) {
                if (entry.getValue().owner().get() != type || staged.get(scope).containsKey(entry.getKey())) continue;
                var method = desired.get(entry.getKey());
                if (method != null && Arrays.equals(registry.rawMethods.get(entry.getKey()), fingerprint(method))) continue;
                try {
                    requireCurrent(scope);
                    if (!removeOwned(scope.factory, entry.getKey(), entry.getValue()))
                        throw new IllegalStateException("owned raw product was replaced: " + entry.getKey());
                    registry.definitions.remove(entry.getKey(), entry.getValue()); registry.rawMethods.remove(entry.getKey());
                    if (method != null) registerDefinition(type, scope.factory, scope.name, method, registry.definitions, true);
                } catch (Throwable failure) {
                    failures.add(entry.getKey()); success = false; refuse(type, Failures.describe(failure));
                }
            }
            Map<String, SpringConfigurationCalls.MethodOwnership> authorizations = new HashMap<>();
            for (var method : scope.methods) {
                if (failures.contains(method.name())) continue;
                Owned registration = registry.definitions.get(method.name());
                try {
                    if (registration == null || registration.owner().get() != type || !stillOwned(scope.factory, method.name(), registration))
                        throw new IllegalStateException("raw factory registration was replaced: " + method.name());
                    authorizations.put(method.name(), new SpringConfigurationCalls.MethodOwnership(registration.definition(), registration.instance()));
                    registry.rawMethods.put(method.name(), fingerprint(method));
                } catch (Throwable failure) {
                    failures.add(method.name()); success = false; refuse(type, Failures.describe(failure));
                }
            }
            try {
                requireCurrent(scope);
                SpringConfigurationCalls.activate(scope.receiver, type, scope.context, scope.factory, scope.definition, scope.name, authorizations);
            } catch (Throwable failure) {
                SpringConfigurationCalls.retire(scope.receiver, type);
                for (var method : scope.methods) failures.add(method.name());
                success = false; refuse(type, Failures.describe(failure));
            }
        }
        SpringConfigurationCalls.publish(type, plan);
        for (RawScope scope : prepared.scopes) for (var method : scope.methods) {
            if (method.onDemand() || failed.get(scope).contains(method.name())) continue;
            try { initialize(scope.factory, method.name(), owned.get(scope.factory).definitions); }
            catch (Throwable failure) { success = false; refuse(type, Failures.describe(failure)); }
        }
        return success;
    }

    // Delegate profile evaluation to Spring's own Environment so profile
    // expressions (negation, and/or) behave exactly as at startup.
    private static boolean profileAccepted(Object environment, ClassLoader spring, List<String> profiles) throws Exception {
        if (profiles.isEmpty()) return true;
        Class<?> profilesType = Class.forName("org.springframework.core.env.Profiles", false, spring);
        Class<?> environmentType = Class.forName("org.springframework.core.env.Environment", false, spring);
        Object parsed = profilesType.getMethod("of", String[].class).invoke(null, (Object) profiles.toArray(new String[0]));
        return Boolean.TRUE.equals(environmentType.getMethod("acceptsProfiles", profilesType).invoke(environment, parsed));
    }

    private void registerDefinition(Class<?> type, Object factory, String configName,
                          AddedBeanAdapter.Factory method, Map<String, Owned> registrations, boolean full) throws Throwable {
        String name = method.name();
        requireScope(factory, method);
        for (String candidate : method.registrationNames()) {
            if (Boolean.TRUE.equals(call(factory, "containsBean", candidate))
                    || Boolean.TRUE.equals(call(factory, "containsBeanDefinition", candidate))
                    || Boolean.TRUE.equals(call(factory, "isAlias", candidate)))
                throw new IllegalStateException("bean name or alias '" + candidate + "' is already in use");
        }
        // Refuse before registration if later alias ownership cannot be checked.
        if (!method.aliases().isEmpty()) aliasBindings(factory);
        Class<?> resultType = MethodType.fromMethodDescriptorString(method.method().desc, type.getClassLoader()).returnType();
        ClassLoader spring = factory.getClass().getClassLoader();
        rejectInfrastructure(resultType, spring);
        AtomicReference<java.lang.reflect.Method> savedParameters = new AtomicReference<>();
        Supplier<?> delegate = AddedBeanAdapter.create(type, () -> {
            try {
                Object current = call(factory, "getBean", configName);
                if (!SpringConfigurationCalls.matchesMethod(current, type, factory, full, name)
                        || !Boolean.TRUE.equals(call(factory, "isSingleton", configName)))
                    throw new IllegalStateException("configuration is no longer a supported singleton");
                return current;
            } catch (Exception failure) { throw new IllegalStateException("configuration cannot be resolved", failure); }
        }, method, (metadata, descriptor) -> {
            savedParameters.set(metadata);
            Supplier<Object[]> resolved = AddedBeanArguments.prepare(factory, name, metadata, descriptor);
            return full && (method.method().access & org.objectweb.asm.Opcodes.ACC_STATIC) == 0
                    && metadata.getParameterCount() != 0
                    ? SpringConfigurationCalls.arguments(factory, type, method, resolved) : resolved;
        });
        Supplier<?> checked = () -> {
            Object product = Objects.requireNonNull(delegate.get(), "added @Bean factory returned null");
            try { rejectInfrastructure(product.getClass(), spring); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            return product;
        };
        Class<?> definitionType = Class.forName("org.springframework.beans.factory.support.RootBeanDefinition", true, spring);
        Object definition = definitionType.getConstructor(Class.class).newInstance(resultType);
        definitionType.getMethod("setInstanceSupplier", Supplier.class).invoke(definition, checked);
        SpringAddedFactoryValues.attach(factory, definition, type, configName, method.method().name,
                full, savedParameters.get(), checked);
        publishDefinition(type, factory, definition, method, registrations, resultType);
    }

    private void registerInherited(SpringHierarchyBeans.Scope scope, SpringHierarchyBeans.Selection selected,
                                   Registry registry) throws Throwable {
        var method = selected.method();
        var resolved = selected.resolved();
        ClassLoader loader = scope.factory().getClass().getClassLoader();
        var root = Class.forName("org.springframework.beans.factory.support.RootBeanDefinition", false, loader);
        Object definition = root.getConstructor().newInstance();
        root.getMethod("setTargetType", Class.class).invoke(definition, resolved.getReturnType());
        call(definition, "setFactoryBeanName", scope.name());
        call(definition, "setUniqueFactoryMethodName", resolved.getName());
        root.getMethod("setResolvedFactoryMethod", java.lang.reflect.Method.class).invoke(definition, resolved);
        root.getMethod("setAutowireMode", int.class).invoke(definition, 3);
        publishDefinition(selected.declaring(), scope.factory(), definition, method, registry.definitions, resolved.getReturnType());
    }

    private void publishDefinition(Class<?> type, Object factory, Object definition,
            AddedBeanAdapter.Factory method, Map<String,Owned> registrations, Class<?> resultType) throws Throwable {
        ClassLoader spring = factory.getClass().getClassLoader();
        Class<?> definitionType = definition.getClass();
        Class<?> definitionInterface = Class.forName("org.springframework.beans.factory.config.BeanDefinition", false, spring);
        String name = method.name();
        call(definition, "setInitMethodName", method.init());
        call(definition, "setDestroyMethodName", method.destroy());
        definitionType.getMethod("setPrimary", boolean.class).invoke(definition, method.primary());
        definitionType.getMethod("setLazyInit", boolean.class).invoke(definition, method.lazy());
        if (!"singleton".equals(method.scope()))
            definitionType.getMethod("setScope", String.class).invoke(definition, method.scope());
        if (method.qualifier() != null) {
            // Selection metadata belongs on the definition: the original class
            // cannot expose an added factory method through reflection.
            Class<?> qualifierType = Class.forName("org.springframework.beans.factory.support.AutowireCandidateQualifier", true, spring);
            Object qualifier = qualifierType.getConstructor(String.class, Object.class).newInstance(
                    "org.springframework.beans.factory.annotation.Qualifier", method.qualifier());
            definitionType.getMethod("addQualifier", qualifierType).invoke(definition, qualifier);
        }
        requireScope(factory, method);
        String targetName = method.proxied() ? method.targetName() : null;
        Object publicDefinition = definition;
        Owned registration = null;
        try {
            if (targetName != null) {
                for (String reserved : method.registrationNames()) {
                    if (Boolean.TRUE.equals(call(factory, "containsBean", reserved))
                            || Boolean.TRUE.equals(call(factory, "containsBeanDefinition", reserved))
                            || Boolean.TRUE.equals(call(factory, "isAlias", reserved)))
                        throw new IllegalStateException("bean name or alias '" + reserved + "' is already in use");
                }
                Class<?> holder = Class.forName("org.springframework.beans.factory.config.BeanDefinitionHolder", false, spring);
                Class<?> registry = Class.forName("org.springframework.beans.factory.support.BeanDefinitionRegistry", false, spring);
                Object original = holder.getConstructor(definitionInterface, String.class).newInstance(definition, name);
                Class<?> utils = Class.forName("org.springframework.aop.scope.ScopedProxyUtils", false, spring);
                Object proxy = utils.getMethod("createScopedProxy", holder, registry, boolean.class)
                        .invoke(null, original, factory, "TARGET_CLASS".equals(method.proxyMode()));
                publicDefinition = holder.getMethod("getBeanDefinition").invoke(proxy);
            }
            registration = new Owned(new WeakReference<>(type), new WeakReference<>(publicDefinition),
                    new AtomicReference<>(new WeakReference<>(null)), method.aliases(), targetName, new WeakReference<>(definition), JacksonMapperRefresh.isCustomizer(resultType));
            factory.getClass().getMethod("registerBeanDefinition", String.class, definitionInterface)
                    .invoke(factory, name, publicDefinition);
            registrations.put(name, registration);
            if (registration.jacksonCustomizer()) jacksonChanges.add(factory);
            for (String alias : method.aliases())
                factory.getClass().getMethod("registerAlias", String.class, String.class).invoke(factory, name, alias);
        } catch (Throwable failure) {
            if (registration != null) discardFailed(factory, name, registration, registrations, failure);
            else if (targetName != null) {
                try { removeScopedTarget(factory, targetName, definition); }
                catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    private static void requireScope(Object factory, AddedBeanAdapter.Factory method) throws Exception {
        if (method.scoped() && call(factory, "getRegisteredScope", method.scope()) == null)
            throw new IllegalArgumentException("scope '" + method.scope() + "' is not registered in this bean factory");
    }

    private static boolean removeScopedTarget(Object factory, String name, Object definition) throws Exception {
        if (!Boolean.TRUE.equals(call(factory, "containsBeanDefinition", name))) return false;
        if (call(factory, "getBeanDefinition", name) != definition) {
            StatusReporter.detail("Added @Bean keeps externally replaced definition " + name);
            return false;
        }
        call(factory, "removeBeanDefinition", name);
        return true;
    }

    private void initialize(Object factory, String name, Map<String, Owned> registrations) throws Throwable {
        Owned registration = registrations.get(name);
        if (registration == null) throw new IllegalStateException("added definition was removed before initialization: " + name);
        try {
            if (!stillOwned(factory, name, registration))
                throw new IllegalStateException("added registration was replaced before initialization: " + name);
            Object product = call(factory, "getBean", name);
            registration.instance().set(new WeakReference<>(product));
            if (registration.jacksonCustomizer()) return;
            ReloadEffects.note("added bean " + name + " registered");
            StatusReporter.detail("Added @Bean registered: " + name);
        } catch (Throwable failure) {
            // Only our definition is removed; callbacks and dependent destruction
            // are live application work and cannot be rolled back here.
            discardFailed(factory, name, registration, registrations, failure);
            throw failure;
        }
    }

    private void discardFailed(Object factory, String name, Owned registration,
                                      Map<String, Owned> registrations, Throwable failure) {
        try {
            removeOwned(factory, name, registration);
            registrations.remove(name, registration);
        } catch (Throwable cleanup) {
            // Keep the registration and report cleanup without hiding the original failure.
            failure.addSuppressed(cleanup);
        }
    }

    private boolean removeOwned(Object factory, String name, Owned registration) throws Exception {
        if (registration.targetName() != null) {
            if (!ownsPublicDefinition(factory, name, registration)) {
                StatusReporter.detail("Added @Bean keeps externally replaced binding " + name);
                return removeScopedTarget(factory, registration.targetName(), registration.targetDefinition().get());
            }
        } else if (!stillOwned(factory, name, registration)) return false;
        if (registration.jacksonCustomizer()) jacksonChanges.add(factory);
        Map<?, ?> bindings = registration.aliases().isEmpty() ? Map.of() : aliasBindings(factory);
        call(factory, "removeBeanDefinition", name);
        if (registration.targetName() != null)
            removeScopedTarget(factory, registration.targetName(), registration.targetDefinition().get());
        // Destruction callbacks may have installed a new owner of this name.
        if (Boolean.TRUE.equals(call(factory, "containsBeanDefinition", name))
                || call(factory, "getSingleton", name) != null) return true;
        // Never hold the alias lock while destroying beans/application callbacks.
        synchronized (bindings) {
            for (String alias : registration.aliases()) {
                // Comparing the canonical root would wrongly remove an alias
                // externally retargeted through a different intermediate alias.
                if (name.equals(bindings.get(alias))) call(factory, "removeAlias", alias);
            }
        }
        return true;
    }

    private static Map<?, ?> aliasBindings(Object factory) {
        Object bindings = Reflect.readField(factory, "aliasMap");
        if (!(bindings instanceof Map<?, ?> map))
            throw new IllegalStateException("cannot inspect direct alias bindings for ownership");
        return map;
    }

    private static boolean ownsPublicDefinition(Object factory, String name, Owned registration) throws Exception {
        return Boolean.TRUE.equals(call(factory, "containsBeanDefinition", name))
                && call(factory, "getBeanDefinition", name) == registration.definition().get();
    }

    private static boolean stillOwned(Object factory, String name, Owned registration) throws Exception {
        if (!ownsPublicDefinition(factory, name, registration)) return false;
        if (registration.targetName() != null)
            return Boolean.TRUE.equals(call(factory, "containsBeanDefinition", registration.targetName()))
                    && call(factory, "getBeanDefinition", registration.targetName()) == registration.targetDefinition().get();
        Object singleton = call(factory, "getSingleton", name);
        // A vanished singleton may have been destroyed during configuration
        // refresh. An externally installed replacement is somebody else's bean.
        return singleton == null || singleton == registration.instance().get().get();
    }

    private static void observeCreations(Object factory, Registry registry) throws Exception {
        if (registry.observing) return;
        Class<?> processor = Class.forName("org.springframework.beans.factory.config.BeanPostProcessor",
                false, factory.getClass().getClassLoader());
        WeakReference<Object> factoryRef = new WeakReference<>(factory);
        Object observer = Proxy.newProxyInstance(processor.getClassLoader(), new Class<?>[]{processor}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "Reclazz added bean ownership observer";
                default -> throw new UnsupportedOperationException(method.getName());
            };
            if (method.getName().equals("postProcessAfterInitialization")) {
                Owned registration = registry.definitions.get((String) args[1]);
                Object currentFactory = factoryRef.get();
                if (registration != null && currentFactory != null) {
                    try {
                        if (call(currentFactory, "getBeanDefinition", (String) args[1]) == registration.definition().get())
                            registration.instance().set(new WeakReference<>(args[0]));
                    } catch (Exception unavailable) {
                        // Fail closed on the next ownership check, without
                        // breaking an application's otherwise successful creation.
                        registration.instance().set(new WeakReference<>(null));
                    }
                }
            }
            // Registered after the application's processors, so this observes
            // their finished object, including a proxy. It changes no bean.
            // No reloader lock: creation can hold Spring's singleton lock on
            // another thread while a reload is waiting to enter the factory.
            return args[0];
        });
        factory.getClass().getMethod("addBeanPostProcessor", processor).invoke(factory, observer);
        registry.observing = true;
    }

    private static void rejectInfrastructure(Class<?> type, ClassLoader spring) throws ClassNotFoundException {
        for (String name : List.of("org.springframework.beans.factory.FactoryBean",
                "org.springframework.beans.factory.config.BeanPostProcessor",
                "org.springframework.beans.factory.config.BeanFactoryPostProcessor"))
            if (Class.forName(name, false, spring).isAssignableFrom(type))
                throw new IllegalArgumentException("infrastructure factory products are not supported: " + type.getName());
    }

    private static Object call(Object target, String method, String name) throws Exception {
        return target.getClass().getMethod(method, String.class).invoke(target, name);
    }

    private static void refuse(Class<?> type, String reason) {
        String message = "Added @Bean on " + type.getName() + " needs a restart: " + reason;
        StatusReporter.warn(message);
        RestartLedger.note(type.getName(), message);
    }
}
