/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.FieldStore;
import com.onurkat.reclazz.bootstrap.ReflectionBridge;
import com.onurkat.reclazz.transform.KeepBaseline;
import com.onurkat.reclazz.util.ClassLookup;
import com.onurkat.reclazz.util.Reflect;
import com.onurkat.reclazz.ui.Failures;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import static com.onurkat.reclazz.spring.SpringConstructorExtension.require;

/** Constructor changes must be admitted before a same-save caller can reach the new owner body. */
public final class SpringConstructorSave {
    private SpringConstructorSave() { }
    // A supplier-backed owner can be edited again. Retain its original physical proof and
    // the admitted logical constructor, without retaining contexts or singleton instances.
    private record Receipt(KeepBaseline.Fingerprint physical, Map<String,Long> logical, Map<String,String> metadata) { }
    private static final ClassValue<java.util.concurrent.atomic.AtomicReference<Receipt>> receipts = new ClassValue<>() {
        @Override protected java.util.concurrent.atomic.AtomicReference<Receipt> computeValue(Class<?> type) {
            return new java.util.concurrent.atomic.AtomicReference<>();
        }
    };
    private static final ThreadLocal<Set<Object>> prepared = new ThreadLocal<>();
    static boolean consume(Object instance) {
        var set = prepared.get();
        if (set == null || !set.remove(instance)) return false;
        if (set.isEmpty()) prepared.remove();
        return true;
    }
    public static void endSave() { prepared.remove(); }

    private record Candidate(Object factory, String name, Object incoming, Class<?> type) { }
    private record Binding(SpringConstructorExtension.Slot slot, Class<?> dependency, Candidate candidate) { }
    private record Owner(Object factory, String name, Class<?> type, Object old, Object raw, Object merged,
                         ConcurrentMap<String,Object> singletons, Constructor<?> ctor, List<Binding> bindings) { }

    public static Map<String,String> apply(Map<String,byte[]> bytes, ClassLookup.LoadedClasses loaded, Supplier<List<Object>> contextSource) {
        List<Object> contexts = null;
        List<Owner> owners = new ArrayList<>();
        List<Candidate> incoming = new ArrayList<>();
        List<Candidate> registered = new ArrayList<>();
        List<Replacement> staged = new ArrayList<>();
        String subject = "";
        try {
            for (var entry : bytes.entrySet()) {
                Class<?> type = loaded.find(entry.getKey());
                if (type == null || type.isRecord()) continue;
                var baseline = KeepBaseline.get(type);
                var previous = receipts.get(type).get();
                if (previous == null) {
                    if (baseline != null && !signatureChanged(baseline.constructors().keySet(), entry.getValue())) continue;
                    if (!SpringNewBeanRegistrar.hasStereotype(type, type.getClassLoader())) continue;
                    if (baseline == null) {
                        Set<String> physical = new HashSet<>();
                        for (var ctor : ReflectionBridge.physicalDeclaredConstructors(type))
                            physical.add(Type.getConstructorDescriptor(ctor));
                        if (!signatureChanged(physical, entry.getValue())) continue;
                    }
                } else if (!changedSince(previous, entry.getValue())) continue;
                if (contexts == null) contexts = contextSource.get();
                if (contexts.isEmpty()) return Map.of();
                subject = type.getName();
                var seen = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Object context : contexts) {
                    Object factory = SpringBeans.getBeanFactory(context);
                    if (factory == null || !seen.add(factory)) continue;
                    for (String name : names(factory, type)) {
                        require(baseline != null && KeepBaseline.witnessedLoad(type), "no witnessed physical constructor");
                        if (previous != null) {
                            var next = KeepBaseline.fingerprint(entry.getValue());
                            require(!next.constructors().keySet().equals(previous.logical.keySet()),
                                    "changing previously admitted constructor logic or annotations");
                        }
                        owners.add(prepare(context, factory, name, type, entry.getValue(), previous == null ? baseline : previous.physical, bytes, incoming));
                    }
                }
            }
            if (owners.isEmpty()) return Map.of();
            // Registration happens only after every owner and every argument has an unambiguous name.
            for (Candidate candidate : incoming) {
                require(!contains(candidate.factory, candidate.name), "incoming bean name already in use: " + candidate.name);
                var definition = spring(candidate.factory, "beans.factory.config.BeanDefinition");
                candidate.factory.getClass().getMethod("registerBeanDefinition", String.class, definition)
                        .invoke(candidate.factory, candidate.name, candidate.incoming);
                registered.add(candidate);
            }
            for (var candidate : registered) bean(candidate.factory, candidate.name);
            for (Owner owner : owners) {
                subject = owner.type.getName();
                var replacement = new Replacement(owner);
                replacement.stage();
                staged.add(replacement);
            }
            for (Replacement replacement : staged) replacement.publish();
            var replacements = new IdentityHashMap<Object,Object>();
            for (Replacement r : staged) replacements.put(r.owner.old, r.next);
            SpringBeanReloader.healStaleReferences(contexts, replacements);
            prepared.set(Collections.newSetFromMap(new IdentityHashMap<>()));
            for (Replacement r : staged) {
                prepared.get().add(r.next);
                var type = r.owner.type;
                var next = KeepBaseline.fingerprint(bytes.get(type.getName()));
                var previous = receipts.get(type).get();
                receipts.get(type).set(new Receipt(previous == null ? KeepBaseline.get(type) : previous.physical,
                        next.constructors(), constructorMetadata(next)));
                for (String reason : com.onurkat.reclazz.ui.RestartLedger.reasonsFor(r.owner.type.getName()))
                    if (reason.startsWith("constructor dependency change on ")) com.onurkat.reclazz.ui.RestartLedger.resolve(r.owner.type.getName(), reason);
            }
            return Map.of();
        } catch (Exception | LinkageError failure) {
            for (int i = staged.size() - 1; i >= 0; i--) {
                try { staged.get(i).restore(); } catch (Exception recovery) { failure.addSuppressed(recovery); }
            }
            for (int i = registered.size() - 1; i >= 0; i--) {
                Candidate c = registered.get(i);
                try {
                    if (named(c.factory, "getBeanDefinition", c.name) == c.incoming)
                        named(c.factory, "removeBeanDefinition", c.name);
                } catch (Exception recovery) { failure.addSuppressed(recovery); }
            }
            String reason = "constructor dependency change on " + subject + " requires restart: " + Failures.describe(failure);
            com.onurkat.reclazz.ui.RestartLedger.noteOnce(subject, reason);
            var refused = new LinkedHashMap<String,String>();
            bytes.keySet().forEach(name -> refused.put(name, reason));
            return refused;
        }
    }

    private static Map<String,String> constructorMetadata(KeepBaseline.Fingerprint fingerprint) {
        Map<String,String> metadata = new LinkedHashMap<>();
        fingerprint.memberAnnotations().forEach((key,value) -> {
            if (key.startsWith("<init>:")) metadata.put(key,value);
        });
        return metadata;
    }
    private static boolean changedSince(Receipt receipt, byte[] bytes) {
        var next = KeepBaseline.fingerprint(bytes);
        return !receipt.logical.equals(next.constructors()) || !receipt.metadata.equals(constructorMetadata(next));
    }
    private static boolean signatureChanged(Set<String> previous, byte[] bytes) {
        Set<String> descriptors = new HashSet<>();
        new ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            @Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (name.equals("<init>")) descriptors.add(desc);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return !descriptors.equals(previous);
    }

    @SuppressWarnings("unchecked")
    private static Owner prepare(Object context, Object factory, String name, Class<?> type, byte[] rawBytes,
                                 KeepBaseline.Fingerprint baseline, Map<String,byte[]> batch, List<Candidate> incoming) throws Exception {
        Object old = named(factory, "getSingleton", name);
        require(old != null && old.getClass() == type, "owner is cold, scoped or proxied: " + name);
        Object raw = named(factory, "getBeanDefinition", name), merged = named(factory, "getMergedBeanDefinition", name);
        Object supplier = raw.getClass().getMethod("getInstanceSupplier").invoke(raw);
        require((supplier == null || supplier instanceof Creation)
                && SpringConstructorDependencies.plain(raw, supplier) && SpringConstructorDependencies.plain(merged, supplier), "custom bean creation policy: " + name);
        require(!(boolean)merged.getClass().getMethod("hasPropertyValues").invoke(merged)
                && merged.getClass().getMethod("getInitMethodName").invoke(merged) == null
                && merged.getClass().getMethod("getDestroyMethodName").invoke(merged) == null
                && !spring(factory, "beans.factory.InitializingBean").isAssignableFrom(type), "configured owner lifecycle: " + name);
        require(baseline.lifecycle().isEmpty() && !AutoCloseable.class.isAssignableFrom(type)
                && !spring(factory,"beans.factory.DisposableBean").isAssignableFrom(type), "lifecycle-owned bean: " + name);
        Constructor<?>[] constructors = ReflectionBridge.physicalDeclaredConstructors(type);
        require(constructors.length == 1 && baseline.constructors().size() == 1, "multiple or unknown physical constructors");
        Constructor<?> ctor = constructors[0];
        ctor.setAccessible(true);
        var source = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(rawBytes).accept(source, ClassReader.SKIP_FRAMES);
        var slots = SpringConstructorExtension.assignments(source, ctor, baseline);
        if (supplier instanceof Creation last) require(slots.containsAll(last.bindings.stream().map(Binding::slot).toList()),
                "removing or changing a previously admitted dependency");
        List<Binding> bindings = new ArrayList<>();
        for (var slot : slots) {
            Class<?> dep = Class.forName(Type.getType(slot.descriptor()).getClassName(), false, type.getClassLoader());
            List<Candidate> candidates = new ArrayList<>();
            for (Object level = factory; level != null; level = level.getClass().getMethod("getParentBeanFactory").invoke(level)) {
                for (String candidate : names(level, dep)) {
                    if ((boolean)named(level, "containsBeanDefinition", candidate)) {
                        Object definition = named(level, "getBeanDefinition", candidate);
                        if (!((boolean)definition.getClass().getMethod("isAutowireCandidate").invoke(definition))) continue;
                    }
                    candidates.add(new Candidate(level, candidate, null, dep));
                }
            }
            for (var item : batch.entrySet()) {
                if (!couldImplement(item.getKey(), dep, batch, type.getClassLoader(), new HashSet<>())) continue;
                Class<?> actual = Class.forName(item.getKey(), false, type.getClassLoader());
                if (Modifier.isAbstract(actual.getModifiers()) || !dep.isAssignableFrom(actual)) continue;
                var stereo = SpringNewBeanRegistrar.resolveStereotype(actual, factory, factory.getClass().getClassLoader());
                if (stereo == null) continue;
                if (contains(factory, stereo.beanName())) {
                    require(candidates.stream().anyMatch(c -> c.factory == factory && c.name.equals(stereo.beanName())
                            && existingType(factory, c.name) == actual), "incoming bean name collision: " + stereo.beanName());
                    continue;
                }
                if (SpringComponentConditions.shouldSkip(stereo.definition(), context, factory, type.getClassLoader(),
                        factory.getClass().getClassLoader())) continue;
                require(!stereo.controller() && (boolean)stereo.definition().getClass().getMethod("isSingleton").invoke(stereo.definition()),
                        "incoming dependency has a scoped or controller lifecycle: " + stereo.beanName());
                var proposed = new Candidate(factory, stereo.beanName(), stereo.definition(), actual);
                candidates.add(proposed);
            }
            require(candidates.stream().map(Candidate::factory).distinct().count() <= 1, "competing context levels: " + labels(candidates));
            Object descriptor = SpringDependencyCandidate.descriptor(type, dep, slot.qualifier(), factory.getClass().getClassLoader());
            var matched = new ArrayList<Candidate>();
            for (Candidate candidate : candidates) {
                if (SpringDependencyCandidate.matches(candidate.factory, candidate.name, candidate.incoming, descriptor))
                    matched.add(candidate);
            }
            require(matched.size() == 1, "dependency " + dep.getName() + " candidates " + labels(candidates));
            Candidate chosen = matched.get(0);
            if (chosen.incoming != null) {
                Candidate previous = incoming.stream().filter(c -> c.factory == factory && c.name.equals(chosen.name)).findFirst().orElse(null);
                require(previous == null || previous.type == chosen.type, "competing incoming definitions: " + chosen.name);
                if (previous == null) incoming.add(chosen);
            }
            bindings.add(new Binding(slot, dep, chosen));
        }
        Object map = Reflect.readField(factory, "singletonObjects");
        require(map instanceof ConcurrentMap<?,?>, "singleton registry unavailable");
        return new Owner(factory, name, type, old, raw, merged, (ConcurrentMap<String,Object>)map, ctor, bindings);
    }

    private static Class<?> existingType(Object factory, String name) {
        try { return (Class<?>)factory.getClass().getMethod("getType", String.class, boolean.class).invoke(factory, name, false); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private static boolean couldImplement(String name, Class<?> type, Map<String,byte[]> batch, ClassLoader loader, Set<String> seen) throws Exception {
        if (name.equals(type.getName())) return true;
        if (!seen.add(name)) return false;
        byte[] bytes = batch.get(name);
        if (bytes == null) return type.isAssignableFrom(Class.forName(name, false, loader));
        var reader = new ClassReader(bytes);
        for (String iface : reader.getInterfaces()) if (couldImplement(iface.replace('/','.'),type,batch,loader,seen)) return true;
        return reader.getSuperName() != null && couldImplement(reader.getSuperName().replace('/','.'),type,batch,loader,seen);
    }
    private static List<String> labels(List<Candidate> candidates) { return candidates.stream().map(Candidate::name).toList(); }
    private static String[] names(Object factory, Class<?> type) throws Exception {
        return (String[])factory.getClass().getMethod("getBeanNamesForType",Class.class,boolean.class,boolean.class).invoke(factory,type,true,false);
    }
    private static boolean contains(Object factory, String name) throws Exception { return (boolean)named(factory,"containsLocalBean",name); }
    private static Object named(Object target, String method, String name) throws Exception {
        return target.getClass().getMethod(method,String.class).invoke(target,name);
    }
    private static Object bean(Object factory, String name) throws Exception { return named(factory,"getBean",name); }
    private static Class<?> spring(Object factory, String suffix) throws Exception {
        return Class.forName("org.springframework." + suffix, false, factory.getClass().getClassLoader());
    }

    private record Creation(Object factory, String name, Class<?> type, Constructor<?> ctor,
                            List<Binding> bindings) implements Supplier<Object> {
        Object construct(Set<String> dependencies) throws Exception {
            Object[] args = new Object[ctor.getParameterCount()];
            Class<?> parameter = spring(factory,"core.MethodParameter");
            Class<?> descriptor = spring(factory,"beans.factory.config.DependencyDescriptor");
            var resolve = factory.getClass().getMethod("resolveDependency",descriptor,String.class,Set.class,spring(factory,"beans.TypeConverter"));
            for (int i = 0; i < args.length; i++) {
                Object p = parameter.getConstructor(Constructor.class,int.class).newInstance(ctor,i);
                Object d = descriptor.getConstructor(parameter,boolean.class).newInstance(p,true);
                args[i] = resolve.invoke(factory,d,name,dependencies,null);
            }
            Object instance = ctor.newInstance(args);
            for (Binding binding : bindings) {
                Object value = bean(binding.candidate.factory,binding.candidate.name);
                require(binding.dependency.isInstance(value), "bean type changed: " + binding.candidate.name);
                store(instance, binding, value);
                dependencies.add(binding.candidate.name);
            }
            return instance;
        }

        void store(Object instance, Binding binding, Object value) {
            String internal = Type.getInternalName(type);
            var slot = binding.slot;
            FieldStore.putExtField(instance,value,internal,slot.field(),slot.descriptor());
            require(value != null && FieldStore.getExtField(instance,internal,slot.field(),slot.descriptor()) == value,
                    "added dependency field unavailable: " + slot.field());
        }
        void register(Set<String> dependencies) throws Exception {
            var method = factory.getClass().getMethod("registerDependentBean",String.class,String.class);
            for (String dep : dependencies) method.invoke(factory,dep,name);
        }
        @Override public Object get() {
            try {
                Set<String> dependencies = new LinkedHashSet<>();
                Object result = construct(dependencies);
                register(dependencies);
                return result;
            } catch (Exception failure) { throw new IllegalStateException("cannot construct " + name, failure); }
        }
    }

    private static final class Replacement {
        final Owner owner;
        final Creation creation;
        final Set<String> edges = new LinkedHashSet<>();
        Object next, previousSupplier;
        boolean installed, swapped;

        Replacement(Owner owner) {
            this.owner = owner;
            creation = new Creation(owner.factory, owner.name, owner.type, owner.ctor, List.copyOf(owner.bindings));
        }
        void stage() throws Exception {
            Object instance = creation.construct(edges);
            owner.factory.getClass().getMethod("autowireBean",Object.class).invoke(owner.factory,instance);
            next = owner.factory.getClass().getMethod("initializeBean",Object.class,String.class).invoke(owner.factory,instance,owner.name);
            require(next == instance, "initialization wrapped the detached owner");
        }
        void supplier(Object definition, Supplier<?> value) throws Exception {
            definition.getClass().getMethod("setInstanceSupplier",Supplier.class).invoke(definition,value);
        }
        void publish() throws Exception {
            require(owner.singletons.get(owner.name) == owner.old, "owner was replaced during admission");
            previousSupplier = owner.raw.getClass().getMethod("getInstanceSupplier").invoke(owner.raw);
            installed = true;
            supplier(owner.raw,creation);
            supplier(owner.merged,creation);
            swapped = owner.singletons.replace(owner.name,owner.old,next);
            require(swapped, "owner changed during publication");
            creation.register(edges);
        }
        void restore() throws Exception {
            if (swapped) owner.singletons.replace(owner.name,next,owner.old);
            if (installed) { supplier(owner.raw,(Supplier<?>)previousSupplier); supplier(owner.merged,(Supplier<?>)previousSupplier); }
        }
    }
}
