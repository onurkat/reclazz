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
                         AtomicReference<WeakReference<Object>> instance, List<String> aliases) { }
    private static final class Registry {
        final Map<String, Owned> definitions = new ConcurrentHashMap<>();
        boolean observing;
        final Map<String, byte[]> rawMethods = new HashMap<>();
    }

    public SpringAddedBeanReloader(PlatformContext platform) { this.platform = platform; }

    public synchronized boolean reloadBeanMethods(Class<?> type, Set<String> added, byte[] bytes) {
        if (bytes == null) return true;
        var plan = AddedBeanAdapter.inspect(bytes, added);
        try {
            PreparedRawConfigs raw = prepareRaw(type, added, bytes);
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
                    if (removeOwned(factory, entry.getKey(), entry.getValue())) {
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
                        if (method.lazy() || method.prototype()) {
                            ReloadEffects.note("added bean " + method.name()
                                    + " registered (" + (method.lazy() ? "lazy" : "prototype") + ", created on demand)");
                            StatusReporter.detail("Added @Bean registered: " + method.name()
                                    + " (" + (method.lazy() ? "lazy" : "prototype") + ")");
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
        if (bytes == null) return new PreparedRawConfigs(List.of());
        var plan = AddedBeanAdapter.inspect(bytes, added);
        if (!plan.full()) return new PreparedRawConfigs(List.of());
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

    private static void requireStateless(Class<?> type, byte[] bytes) {
        if (type.getSuperclass() != Object.class || type.getInterfaces().length != 0)
            throw new IllegalStateException("raw configuration inheritance/lifecycle interfaces require a restart");
        for (var field : type.getDeclaredFields())
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) && !field.getName().startsWith(com.onurkat.reclazz.bootstrap.InjectedNames.PREFIX))
                throw new IllegalStateException("raw configuration instance fields require a restart");
        if (type.getDeclaredConstructors().length != 1 || type.getDeclaredConstructors()[0].getParameterCount() != 0)
            throw new IllegalStateException("raw configuration constructor parameters require a restart");
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node, org.objectweb.asm.ClassReader.SKIP_FRAMES);
        if (!"java/lang/Object".equals(node.superName) || !node.interfaces.isEmpty())
            throw new IllegalStateException("raw configuration inheritance/lifecycle interfaces require a restart");
        for (var field : node.fields)
            if ((field.access & org.objectweb.asm.Opcodes.ACC_STATIC) == 0 && !field.name.startsWith(com.onurkat.reclazz.bootstrap.InjectedNames.PREFIX))
                throw new IllegalStateException("raw configuration instance fields require a restart");
        int constructors = 0;
        for (var method : node.methods) {
            if (method.name.equals("<init>")) {
                constructors++;
                if (!method.desc.equals("()V")) throw new IllegalStateException("raw configuration constructor parameters require a restart");
            }
            if (method.visibleAnnotations != null) for (var annotation : method.visibleAnnotations)
                if (annotation.desc.equals("Ljakarta/annotation/PostConstruct;") || annotation.desc.equals("Ljakarta/annotation/PreDestroy;")
                        || annotation.desc.equals("Ljavax/annotation/PostConstruct;") || annotation.desc.equals("Ljavax/annotation/PreDestroy;"))
                    throw new IllegalStateException("raw configuration lifecycle callbacks require a restart");
        }
        if (constructors != 1) throw new IllegalStateException("raw configuration requires exactly one no-arg constructor");
        for (var method : type.getDeclaredMethods()) for (var annotation : method.getDeclaredAnnotations())
            if (Set.of("jakarta.annotation.PostConstruct", "jakarta.annotation.PreDestroy", "javax.annotation.PostConstruct", "javax.annotation.PreDestroy")
                    .contains(annotation.annotationType().getName()))
                throw new IllegalStateException("raw configuration lifecycle callbacks require a restart");
    }

    private void preflightName(Class<?> type, Object factory, AddedBeanAdapter.Factory method) throws Exception {
        Registry registry = owned.get(factory);
        Owned previous = registry == null ? null : registry.definitions.get(method.name());
        boolean ours = previous != null && previous.owner().get() == type && stillOwned(factory, method.name(), previous);
        Map<?, ?> bindings = method.aliases().isEmpty() ? Map.of() : aliasBindings(factory);
        for (String name : method.names()) {
            boolean occupied = Boolean.TRUE.equals(call(factory, "containsBean", name))
                    || Boolean.TRUE.equals(call(factory, "containsBeanDefinition", name)) || Boolean.TRUE.equals(call(factory, "isAlias", name));
            if (occupied && !(ours && (name.equals(method.name()) || previous.aliases().contains(name) && method.name().equals(bindings.get(name)))))
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
            if (method.lazy() || method.prototype() || failed.get(scope).contains(method.name())) continue;
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
        for (String candidate : method.names()) {
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
        Class<?> definitionInterface = Class.forName("org.springframework.beans.factory.config.BeanDefinition", false, spring);
        Object definition = definitionType.getConstructor(Class.class).newInstance(resultType);
        definitionType.getMethod("setInstanceSupplier", Supplier.class).invoke(definition, checked);
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
        SpringAddedFactoryValues.attach(factory, definition, type, configName, method.method().name,
                full, savedParameters.get(), checked);
        factory.getClass().getMethod("registerBeanDefinition", String.class, definitionInterface).invoke(factory, name, definition);
        Owned registration = new Owned(new WeakReference<>(type), new WeakReference<>(definition),
                new AtomicReference<>(new WeakReference<>(null)), method.aliases());
        registrations.put(name, registration);
        try {
            for (String alias : method.aliases())
                factory.getClass().getMethod("registerAlias", String.class, String.class).invoke(factory, name, alias);
        } catch (Throwable failure) {
            discardFailed(factory, name, registration, registrations, failure);
            throw failure;
        }
    }

    private static void initialize(Object factory, String name, Map<String, Owned> registrations) throws Throwable {
        Owned registration = registrations.get(name);
        if (registration == null) throw new IllegalStateException("added definition was removed before initialization: " + name);
        try {
            if (!stillOwned(factory, name, registration))
                throw new IllegalStateException("added registration was replaced before initialization: " + name);
            Object product = call(factory, "getBean", name);
            registration.instance().set(new WeakReference<>(product));
            ReloadEffects.note("added bean " + name + " registered");
            StatusReporter.detail("Added @Bean registered: " + name);
        } catch (Throwable failure) {
            // Only our definition is removed; callbacks and dependent destruction
            // are live application work and cannot be rolled back here.
            discardFailed(factory, name, registration, registrations, failure);
            throw failure;
        }
    }

    private static void discardFailed(Object factory, String name, Owned registration,
                                      Map<String, Owned> registrations, Throwable failure) {
        try {
            removeOwned(factory, name, registration);
            registrations.remove(name, registration);
        } catch (Throwable cleanup) {
            // Keep the registration and report cleanup without hiding the original failure.
            failure.addSuppressed(cleanup);
        }
    }

    private static boolean removeOwned(Object factory, String name, Owned registration) throws Exception {
        if (!stillOwned(factory, name, registration)) return false;
        Map<?, ?> bindings = registration.aliases().isEmpty() ? Map.of() : aliasBindings(factory);
        call(factory, "removeBeanDefinition", name);
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

    private static boolean stillOwned(Object factory, String name, Owned registration) throws Exception {
        if (!Boolean.TRUE.equals(call(factory, "containsBeanDefinition", name))
                || call(factory, "getBeanDefinition", name) != registration.definition().get()) return false;
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
