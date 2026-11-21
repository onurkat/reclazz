/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Refreshes retained Jackson snapshots without retaining application objects or loaders. */
public final class RecordCodecs {
    private static final Object lock = new Object();
    private static final ReferenceQueue<Object> collected = new ReferenceQueue<>();
    private static final java.util.concurrent.ConcurrentHashMap<Entry,Entry> entries = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> copying = new ThreadLocal<>();
    private static final ClassValue<AtomicLong> epochs = new ClassValue<>() {
        @Override protected AtomicLong computeValue(Class<?> type) { return new AtomicLong(); }
    };
    private static volatile long generation;
    private static volatile java.util.function.BiConsumer<String,String> failureReporter;
    private static final AtomicLong failureIds=new AtomicLong();
    public static void reporter(java.util.function.BiConsumer<String,String> reporter){failureReporter=reporter;}
    private static void report(String subject,String reason){
        try{var reporter=failureReporter;if(reporter!=null)reporter.accept(subject,reason);}catch(Throwable ignored){/* Preserve Jackson's own result. */}
    }

    private RecordCodecs() { }

    private static final class Entry extends WeakReference<Object> {
        final int identity;
        volatile long seen;
        volatile long failedGeneration = -1;
        volatile String failedReason;
        volatile String failureSubject;
        volatile boolean initialized, repaired;
        volatile Dependencies dependencies;
        Entry(Object target) { super(target, collected); identity = System.identityHashCode(target); seen = -1; }
        @Override public int hashCode(){return identity;}
        @Override public boolean equals(Object other){return this==other||other instanceof Entry entry&&get()!=null&&get()==entry.get();}
    }
    private record Lookup(Object target) {
        @Override public int hashCode(){return System.identityHashCode(target);}
        @Override public boolean equals(Object other){return other instanceof Entry entry&&target==entry.get();}
    }
    private record Version(WeakReference<Class<?>> type, long epoch) { }
    private record Dependencies(List<Version> versions, boolean open) {
        boolean changed() {
            for (Version version : versions) {
                Class<?> type = version.type.get();
                if (type == null || epochs.get(type).get() != version.epoch) return true;
            }
            return false;
        }
    }
    private static final ClassValue<CachedGraph> classGraphs = new ClassValue<>() {
        @Override protected CachedGraph computeValue(Class<?> type){return new CachedGraph(type);}
    };
    private static final class CachedGraph {
        final WeakReference<Class<?>> owner;
        volatile Dependencies value;
        CachedGraph(Class<?> type){owner=new WeakReference<>(type);}
        Dependencies get() {
            Dependencies found=value;
            if(found!=null&&!found.changed())return found;
            synchronized(this){
                found=value;if(found!=null&&!found.changed())return found;
                Graph graph=new Graph();Class<?> type=owner.get();if(type!=null)graph.type(type);
                return value=graph.freeze();
            }
        }
    }
    private static final class Graph {
        final Set<Class<?>> classes = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Object> javaTypes = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean open;
        void javaType(Object type) throws ReflectiveOperationException {
            if (type == null || !javaTypes.add(type)) return;
            Class<?> raw = (Class<?>) type.getClass().getMethod("getRawClass").invoke(type);
            int count = (int) type.getClass().getMethod("containedTypeCount").invoke(type);
            for (int i = 0; i < count; i++) javaType(type.getClass().getMethod("containedType", int.class).invoke(type, i));
            for (String name : List.of("getKeyType", "getContentType", "getReferencedType"))
                javaType(type.getClass().getMethod(name).invoke(type));
            boolean container = raw.isArray() || Collection.class.isAssignableFrom(raw)
                    || Map.class.isAssignableFrom(raw) || Optional.class.isAssignableFrom(raw);
            if (container) {
                classes.add(raw);
                if (raw.isArray()) type(raw.getComponentType());
                else if (count == 0) open = true;
            } else merge(classGraphs.get(raw).get());
        }
        void merge(Dependencies dependencies){
            open |= dependencies.open();
            for(Version version:dependencies.versions()){Class<?> type=version.type().get();if(type!=null)classes.add(type);}
        }
        void type(Type value) {
            if (value instanceof ParameterizedType p) {
                for (Type argument : p.getActualTypeArguments()) type(argument);
                if (p.getRawType() instanceof Class<?> raw && (Collection.class.isAssignableFrom(raw)
                        || Map.class.isAssignableFrom(raw) || Optional.class.isAssignableFrom(raw))) classes.add(raw);
                else type(p.getRawType());
            } else if (value instanceof GenericArrayType a) type(a.getGenericComponentType());
            else if (value instanceof Class<?> raw) {
                if (!classes.add(raw)) return;
                if (raw.isArray()) { type(raw.getComponentType()); return; }
                if (raw == Object.class || raw.isInterface() || Modifier.isAbstract(raw.getModifiers())) {
                    if (!raw.isPrimitive() && !raw.getName().startsWith("java.lang.")) open = true;
                    if (raw == Object.class) open = true;
                }
                if (raw.isPrimitive() || raw.isEnum() || raw.getName().startsWith("java.")) return;
                for (Class<?> current = raw; current != null && current != Object.class; current = current.getSuperclass()) {
                    for (Field field : current.getDeclaredFields())
                        if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) type(field.getGenericType());
                }
                for (Method method : raw.getMethods()) {
                    if (method.getDeclaringClass() != Object.class && !Modifier.isStatic(method.getModifiers())
                            && !method.isSynthetic() && method.getParameterCount() == 0
                            && (method.getName().startsWith("get") || method.getName().startsWith("is")))
                        type(method.getGenericReturnType());
                }
            } else open = true;
        }
        Dependencies freeze() {
            return new Dependencies(classes.stream().map(type -> new Version(new WeakReference<>(type), epochs.get(type).get())).toList(), open);
        }
    }
    public static void track(Object target) {
        if (target == null || Boolean.TRUE.equals(copying.get())) return;
        entry(target).seen = generation;
    }
    private static Entry entry(Object target) {
        Entry found=entries.get(new Lookup(target));if(found!=null)return found;
        drain();Entry fresh=new Entry(target);Entry existing=entries.putIfAbsent(fresh,fresh);
        return existing==null?fresh:existing;
    }
    private static void drain() {
        Entry dead;while((dead=(Entry)collected.poll())!=null){entries.remove(dead);if(dead.failureSubject!=null)report(dead.failureSubject,null);}
    }
    static int liveCount(){drain();return entries.size();}
    private static List<Entry> live(){drain();return new ArrayList<>(entries.values());}
    private static final ClassValue<Field> slotFields = new ClassValue<>() {
        @Override protected Field computeValue(Class<?> type) {
            String name="_prefetch";
            for(Class<?> current=type;current!=null;current=current.getSuperclass()) {
                if(current.getName().equals("com.fasterxml.jackson.databind.MappingIterator")||current.getName().equals("tools.jackson.databind.MappingIterator")){name="_deserializer";break;}
                if(current.getName().equals("com.fasterxml.jackson.databind.ObjectReader")||current.getName().equals("tools.jackson.databind.ObjectReader")){name="_rootDeserializer";break;}
            }
            try{return field(type,name);}catch(NoSuchFieldException failure){throw new IllegalStateException(failure);}
        }
    };
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { return current.getDeclaredField(name); } catch (NoSuchFieldException absent) { }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
    private static Object read(Object target, String name) throws ReflectiveOperationException {
        return UnsafeAccess.getObject(target, field(target.getClass(), name));
    }
    private static boolean kind(Object target, String suffix) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals("com.fasterxml.jackson.databind." + suffix)
                    || type.getName().equals("tools.jackson.databind." + suffix)) return true;
        return false;
    }
    private static boolean modern(Object target) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().startsWith("tools.jackson.databind.")) return true;
            if (type.getName().startsWith("com.fasterxml.jackson.databind.")) return false;
        }
        return false;
    }
    private static String slot(Object target) {
        return kind(target, "MappingIterator") ? "_deserializer" : kind(target, "ObjectReader") ? "_rootDeserializer" : "_prefetch";
    }
    private static Object root(Object target) throws ReflectiveOperationException {
        if (kind(target, "MappingIterator")) return read(target, "_type");
        if (kind(target, "ObjectReader")) return target.getClass().getMethod("getValueType").invoke(target);
        if (!kind(target, "ObjectWriter")) throw new IllegalArgumentException("not a Jackson codec");
        Object prefetch = read(target, "_prefetch");
        return prefetch == null ? null : read(prefetch, "rootType");
    }
    private static Dependencies dependencies(Object type) throws ReflectiveOperationException {
        Graph graph = new Graph();
        try { graph.javaType(type); }
        catch (RuntimeException | LinkageError incompleteBeanMetadata) { graph.open = true; }
        return graph.freeze();
    }
    /** A real broker operation, not a VM flag, before admitting a native record mutation. */
    public static List<String> preflight() {
            List<String> failures = new ArrayList<>();
            try {
                Probe probe = new Probe(); Object value = new Object(); Field field = Probe.class.getDeclaredField("value");
                UnsafeAccess.putObject(probe, field, value); UnsafeAccess.storeFence();
                if (UnsafeAccess.getObject(probe, field) != value) throw new IllegalStateException("memory broker readback");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                failures.add("Jackson snapshot memory broker: " + failure.getClass().getSimpleName());
            }
            for (Entry entry : live()) {
                Object target = entry.get(); if (target == null) continue;
                try {
                    Object type = root(target); field(target.getClass(), slot(target));
                    if (type != null) {
                        if (!entry.initialized) synchronized(entry) { if(!entry.initialized){entry.dependencies = dependencies(type); entry.initialized = true;} }
                        if (kind(target, "MappingIterator")) {
                            Object context = read(target, "_context");
                            method(context, "findRootValueDeserializer", ".JavaType");
                        } else {
                            target.getClass().getMethod("forType", Class.class);
                            method(target, "forType", ".JavaType"); target.getClass().getMethod("getConfig");
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    failures.add(target.getClass().getName() + ": " + failure.getClass().getSimpleName());
                }
            }
            return List.copyOf(failures);
    }
    @FunctionalInterface public interface CacheAction { void apply() throws ReflectiveOperationException; }
    public record CacheReset(String surface, CacheAction action) { }

    /** Held codecs can outlive mapper registration (e.g. attachment); preflight their actual shared caches too. */
    public static List<CacheReset> prepareCaches() throws ReflectiveOperationException {
            List<CacheReset> actions = new ArrayList<>();
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Entry entry : live()) {
                Object target = entry.get(); if (target == null) continue;
                if (kind(target, "MappingIterator") && root(target) == null) continue; // Jackson's shared empty iterator has no context/cache.
                String surface = "retained Jackson caches " + target.getClass().getName();
                if (kind(target, "ObjectWriter")) {
                    boolean modern = modern(target);
                    Object owner = read(target, modern ? "_serializationContexts" : "_serializerProvider");
                    Object cache = read(owner, modern ? "_cache" : "_serializerCache");
                    if (seen.add(cache)) {
                        Method flush = cache.getClass().getMethod("flush");
                        Object snapshot = read(cache, "_readOnlyMap");
                        if (!(snapshot instanceof java.util.concurrent.atomic.AtomicReference<?> reference))
                            throw new IllegalStateException("retained serializer snapshot is not an AtomicReference");
                        actions.add(new CacheReset(surface, () -> {
                            synchronized (cache) { flush.invoke(cache); reference.set(null); }
                        }));
                    }
                } else {
                    boolean reader = kind(target, "ObjectReader");
                    boolean modernReader = reader && modern(target);
                    Object owner = read(target, modernReader ? "_contexts" : "_context");
                    Object cache = read(owner, "_cache");
                    if (seen.add(cache)) {
                        Method flush = cache.getClass().getMethod("flushCachedDeserializers");
                        actions.add(new CacheReset(surface, () -> flush.invoke(cache)));
                    }
                    if (reader) {
                        Object roots = read(target, "_rootDeserializers");
                        if (!(roots instanceof Map<?, ?> map)) throw new IllegalStateException("retained root cache is not a Map");
                        if (seen.add(roots)) actions.add(new CacheReset(surface, map::clear));
                    }
                }
            }
            return List.copyOf(actions);
    }

    private static final class Probe { private Object value; }
    private static Method method(Object target, String name, String parameterSuffix) throws NoSuchMethodException {
        for (Method method : target.getClass().getMethods())
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].getName().endsWith(parameterSuffix)) return method;
        throw new NoSuchMethodException(name + parameterSuffix);
    }
    private static void replace(Object target, Object type) throws ReflectiveOperationException {
        copying.set(true);
        try {
            Object replacement;
            if (kind(target, "MappingIterator")) {
                Object context = read(target, "_context");
                replacement = method(context, "findRootValueDeserializer", ".JavaType").invoke(context, type);
            } else {
                Object intermediate = target.getClass().getMethod("forType", Class.class).invoke(target, Object.class);
                Object fresh = method(target, "forType", ".JavaType").invoke(intermediate, type);
                if (target.getClass().getMethod("getConfig").invoke(target)
                        != fresh.getClass().getMethod("getConfig").invoke(fresh))
                    throw new IllegalStateException("Jackson snapshot configuration identity changed");
                replacement = read(fresh, slot(target));
            }
            UnsafeAccess.putObject(target, field(target.getClass(), slot(target)), replacement);
            UnsafeAccess.storeFence();
        } finally { copying.remove(); }
    }
    private static void update(Object target, Entry entry, long next) throws ReflectiveOperationException {
        if(entry.failedGeneration==next||(entry.initialized&&entry.seen>=next))return;
        Object type=root(target);
        if(type==null){entry.seen=next;entry.initialized=true;return;}
        if(!entry.initialized) {
            Dependencies dependencies=dependencies(type);
            boolean previouslyChanged=dependencies.versions().stream().anyMatch(version->version.epoch()>0);
            if(entry.seen<next&&previouslyChanged){replace(target,type);entry.repaired=true;}
            entry.dependencies=dependencies;entry.initialized=true;
        }else if(entry.dependencies.changed()) {
            replace(target,type);entry.repaired=true;entry.dependencies=dependencies(type);
        }
        entry.failedReason=null;entry.failedGeneration=-1;entry.seen=next;
        String recovered=entry.failureSubject;entry.failureSubject=null;if(recovered!=null)report(recovered,null);
    }
    /** No graph discovery, snapshot rebuilding or application callback holds the global epoch monitor. */
    public static List<String> refresh(Collection<Class<?>> changed) {
        long next;
        synchronized(lock){for(Class<?> type:changed)epochs.get(type).incrementAndGet();next=++generation;}
        List<String> failures=new ArrayList<>();
        for(Entry entry:live()) {
            Object target=entry.get();if(target==null)continue;
            synchronized(entry) {
                if(entry.failedGeneration==next){
                    failures.add(target.getClass().getName()+": "+entry.failedReason);continue;
                }
                try{update(target,entry,next);}
                catch(ReflectiveOperationException|RuntimeException|LinkageError failure){
                    entry.failedReason=failure.getClass().getSimpleName();entry.failedGeneration=next;entry.seen=next;
                    failures.add(target.getClass().getName()+": "+entry.failedReason);
                }
            }
        }
        return List.copyOf(failures);
    }
    /** A failed generation uses Jackson's raw behavior; its refresh concern is reported once, without retry storms. */
    public static Object current(Object target,Object raw) {
        long observedGeneration=generation;
        if(observedGeneration==0)return raw;
        if(target==null||Boolean.TRUE.equals(copying.get()))return raw;
        Entry entry=entry(target);
        if(entry.failedGeneration==observedGeneration)return raw;
        try {
            if(entry.seen!=observedGeneration||!entry.initialized) {
                synchronized(entry){
                    try{update(target,entry,observedGeneration);}
                    catch(ReflectiveOperationException|RuntimeException|LinkageError failure){return failedUse(target,entry,observedGeneration,raw,failure);}
                }
            }
            if(entry.failedGeneration==observedGeneration)return raw;
            return entry.repaired?UnsafeAccess.getObject(target,slotFields.get(target.getClass())):raw;
        }catch(RuntimeException|LinkageError failure){
            return failedUse(target,entry,observedGeneration,raw,failure);
        }
    }
    private static Object failedUse(Object target,Entry entry,long generation,Object raw,Throwable failure) {
        synchronized(entry){
            if(entry.failedGeneration!=generation){
                entry.failedReason=failure.getClass().getSimpleName();entry.failedGeneration=generation;entry.seen=generation;
                if(entry.failureSubject==null)entry.failureSubject=target.getClass().getName()+" / codec "+failureIds.incrementAndGet();
                report(entry.failureSubject,"retained Jackson snapshot repair did not finish: "+failure.getClass().getSimpleName()+"; this codec uses raw Jackson behavior until a later successful repair or restart");
            }
        }
        return raw;
    }
}
