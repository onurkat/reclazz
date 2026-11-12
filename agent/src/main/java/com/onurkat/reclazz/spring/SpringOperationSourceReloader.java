/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.ui.RestartLedger;

import java.lang.reflect.Field;
import java.util.Map;
import com.onurkat.reclazz.util.Reflect;

/** Bounded best-effort operation repair. A concurrent request can publish after either pass. */
public class SpringOperationSourceReloader {

    /** The types whose beans carry an {@code attributeCache} worth clearing. */
    private static final String[] SOURCE_TYPES = {
            "org.springframework.transaction.interceptor.TransactionAttributeSource",
            "org.springframework.cache.interceptor.CacheOperationSource",
    };

    private final PlatformContext platformContext;
    private final java.util.Map<Class<?>, Pending> pending = new java.util.WeakHashMap<>();
    // Clearing a source for B also removes A's entries. Remember edited classes
    // weakly so a later batch repopulates those entries too, before an old proxy does.
    private final java.util.Map<Class<?>, Pending> affected = new java.util.WeakHashMap<>();
    private final java.util.Map<Class<?>, java.util.List<java.lang.ref.WeakReference<Object>>> ownerContexts = new java.util.WeakHashMap<>();
    private final java.util.Map<Class<?>, java.util.Set<String>> unsupportedReported = new java.util.WeakHashMap<>();
    private record LedgerConcern(String kind, String reason, boolean unsupported) { }
    private final java.util.Map<Class<?>, java.util.Set<LedgerConcern>> ledgerConcerns = new java.util.WeakHashMap<>();
    private final java.util.Set<Class<?>> reloaded = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    // Accessed only on the serialized repair path, never from the heartbeat callback.
    private final java.util.Map<Class<?>, java.util.Set<String>> passConcerns = new java.util.IdentityHashMap<>();
    private final java.util.Map<Class<?>, java.util.Set<String>> passMatches = new java.util.IdentityHashMap<>();
    private record Pending(boolean inspect, boolean reportUnsupported, boolean companion, boolean previousComposed) { }
    private java.util.function.BiFunction<Runnable,Long,Runnable> timer;
    private volatile java.util.function.Consumer<Runnable> admission;
    private Runnable cancel;
    private volatile long generation;
    private int batchDepth;
    private volatile boolean closed;

    public SpringOperationSourceReloader(PlatformContext platformContext) { this.platformContext = platformContext; }

    /** Timer returns a cancellation action; it only enqueues work onto the reload queue. */
    public synchronized void setScheduler(java.util.function.BiFunction<Runnable,Long,Runnable> timer,
                                          java.util.function.Consumer<Runnable> admission) {
        this.timer=timer; this.admission=admission;
    }
    public synchronized void beginBatch() { if(!closed)batchDepth++; }
    public synchronized void endBatch() {
        if(batchDepth==0)return;
        if(--batchDepth==0) { repair(); schedule(); }
    }
    public synchronized void close() {
        closed=true;generation++;
        if(cancel!=null)cancel.run();cancel=null;
        pending.clear();affected.clear();ownerContexts.clear();unsupportedReported.clear();ledgerConcerns.clear();reloaded.clear();timer=null;admission=null;
    }
    public int reloadOperationSources(Class<?> owner,boolean annotationsChanged) {
        return reloadOperationSources(owner,annotationsChanged,null,null);
    }
    public synchronized int reloadOperationSources(Class<?> owner,boolean annotationsChanged,
                                                   java.util.Set<String> previous,byte[] bytecode) {
        return reloadOperationSources(owner,annotationsChanged,previous,bytecode,false);
    }
    public synchronized int reloadOperationSources(Class<?> owner,boolean annotationsChanged,
                                                   java.util.Set<String> previous,byte[] bytecode,boolean companion) {
        if(owner==null||closed)return 0;
        boolean relevant=previous==null; boolean known=previous!=null;
        boolean previousComposed=false;
        try {
            java.util.Set<String> current = bytecode==null ? descriptors(owner)
                    : com.onurkat.reclazz.transform.AnnotationSignatures.operationDescriptors(bytecode);
            previousComposed=OperationMetadataExpectation.hasComposed(previous,owner.getClassLoader());
            relevant |= OperationMetadataExpectation.relevant(current,owner.getClassLoader())
                    ||OperationMetadataExpectation.relevant(previous,owner.getClassLoader());
        }catch(Throwable failure){ relevant=true; }
        reloaded.add(owner);
        pending.putAll(affected);
        Pending before=pending.get(owner);
        pending.put(owner,new Pending(relevant||(before!=null&&before.inspect()),known,companion,previousComposed));
        if(pending.get(owner).inspect())affected.put(owner,pending.get(owner));
        generation++;if(cancel!=null)cancel.run();cancel=null;
        if(batchDepth>0)return 0;
        int changed=repair();schedule();return changed;
    }
    private static java.util.Set<String> descriptors(Class<?> owner) {
        java.util.Set<String> out=new java.util.HashSet<>();
        for(java.lang.annotation.Annotation a:owner.getDeclaredAnnotations())out.add("L"+a.annotationType().getName().replace('.','/')+";");
        for(java.lang.reflect.Method m:owner.getDeclaredMethods())for(java.lang.annotation.Annotation a:m.getDeclaredAnnotations())
            out.add("L"+a.annotationType().getName().replace('.','/')+";");
        return out;
    }
    private void schedule() {
        pending.entrySet().removeIf(e->!e.getValue().inspect());
        if(pending.isEmpty()||closed)return;
        long ticket=generation;
        if(timer==null||admission==null) {
            for(Class<?> c:java.util.List.copyOf(pending.keySet()))diagnostic(c,"cache/transaction","delayed repair unavailable",false);
            pending.clear();return;
        }
        java.util.List<String> affectedNames=pending.keySet().stream().map(Class::getName).toList();
        try {
            cancel=timer.apply(()->{
                // This is the shared heartbeat thread: never wait for a repair's monitor.
                if(closed||ticket!=generation)return;
                java.util.function.Consumer<Runnable> queue=admission;
                if(queue==null)return;
                try { queue.accept(()->delayed(ticket)); }
                catch(Throwable failure) {
                    if(!closed&&ticket==generation)for(String name:affectedNames) {
                        StatusReporter.warn(name+": cache/transaction metadata may still be stale; save again or restart (repair queue unavailable)");
                        RestartLedger.note(name,ledgerReason("cache/transaction","repair queue unavailable"));
                    }
                }
            },250L);
        }catch(Throwable failure) {
            for(Class<?> c:java.util.List.copyOf(pending.keySet()))diagnostic(c,"cache/transaction","delayed repair unavailable",false);
            pending.clear();
        }
    }
    private synchronized void delayed(long ticket) {
        if(closed||ticket!=generation)return;
        try { repair(); } finally { pending.clear();cancel=null; }
    }
    private int repair() {
        passConcerns.clear();passMatches.clear();
        try {
            int result=repairPass();
            for(var match:passMatches.entrySet()) {
                Class<?> owner=match.getKey();
                java.util.Set<String> blocked=passConcerns.getOrDefault(owner,java.util.Set.of());
                java.util.Set<LedgerConcern> concerns=ledgerConcerns.get(owner);
                if(concerns!=null)concerns.removeIf(concern->{
                    boolean verified=concern.kind().equals("cache/transaction")
                            ? blocked.isEmpty() : match.getValue().contains(concern.kind())
                                &&!blocked.contains(concern.kind())&&!blocked.contains("cache/transaction");
                    if(!verified||(concern.unsupported()&&!reloaded.contains(owner)))return false;
                    RestartLedger.resolve(owner.getName(),ledgerReason(concern.kind(),concern.reason()));
                    return true;
                });
                if(blocked.isEmpty())RestartLedger.resolve(owner.getName(),ledgerReason("cache/transaction","repair queue unavailable"));
            }
            return result;
        } finally { passConcerns.clear();passMatches.clear();reloaded.clear(); }
    }
    private int repairPass() {
        java.util.List<Class<?>> classes=new java.util.ArrayList<>(pending.keySet());
        if(classes.isEmpty()||closed)return 0;
        java.util.List<Object> liveContexts=new java.util.ArrayList<>();
        for(Object context:platformContext.getAllApplicationContexts()) {
            try {
                java.lang.reflect.Method active=Reflect.findMethod(context.getClass(),"isActive");
                if(active==null||Boolean.TRUE.equals(active.invoke(context)))liveContexts.add(context);
            } catch(Throwable failure) {
                for(Class<?> c:classes)if(pending.get(c).inspect())diagnostic(c,"cache/transaction","context inspection failed",false);
            }
        }
        java.util.Map<Class<?>,OperationMetadataInspection.Proxies> allMethods=new java.util.IdentityHashMap<>();
        for(java.util.Iterator<Class<?>> iterator=classes.iterator();iterator.hasNext();) {
            Class<?> c=iterator.next();
            java.util.List<OperationMetadataInspection.Lookup> lookups=new java.util.ArrayList<>();String unsupported=null;
            java.util.List<java.lang.ref.WeakReference<Object>> owners=new java.util.ArrayList<>();
            for(Object context:liveContexts) {
                OperationMetadataInspection.Proxies inspected=OperationMetadataInspection.methods(context,c);
                lookups.addAll(inspected.lookups());
                if(inspected.ownsClass())owners.add(new java.lang.ref.WeakReference<>(context));
                if(inspected.unsupported()!=null)unsupported=inspected.unsupported();
            }
            if(!owners.isEmpty())ownerContexts.put(c,java.util.List.copyOf(owners));
            else if(ownerContexts.containsKey(c)) {
                // A shared root source may retain this class after its child closes.
                // Clear that source below, but do not make the retired entry live again.
                iterator.remove();pending.remove(c);affected.remove(c);ownerContexts.remove(c);continue;
            }
            allMethods.put(c,new OperationMetadataInspection.Proxies(java.util.List.copyOf(lookups),unsupported,!owners.isEmpty()));
        }
        java.util.Set<ClassLoader> clearedLoaders=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for(Class<?> c:classes) {
            Pending state=pending.get(c);
            if(state.inspect()&&state.reportUnsupported()&&state.companion())diagnostic(c,"cache/transaction","companion operation metadata",true);
            if(state.inspect()&&state.reportUnsupported()&&state.previousComposed())diagnostic(c,"cache/transaction","previous composed metadata",true);
            if(clearedLoaders.add(c.getClassLoader())) {
                String failure=clearSpringAnnotationCaches(c);
                if(failure!=null)for(Class<?> affectedClass:classes)
                    if(affectedClass.getClassLoader()==c.getClassLoader()&&pending.get(affectedClass).inspect())
                        diagnostic(affectedClass,"cache/transaction",failure,false);
            }
        }
        int cleared=0;
        java.util.Set<Object> visited=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for(Object context:liveContexts) {
            try {
                Object factory=SpringBeans.getBeanFactory(context);
                if(factory==null)throw new IllegalStateException("singleton factory unavailable");
                for(String name:(String[])OperationMetadataInspection.call(factory,"getSingletonNames")) {
                    Object source=factory.getClass().getMethod("getSingleton",String.class).invoke(factory,name);
                    if(source==null||visited.contains(source))continue;
                    for(String kind:SOURCE_TYPES) {
                        Class<?> api;
                        try {api=Class.forName(kind,false,context.getClass().getClassLoader());}
                        catch(ClassNotFoundException absent){continue;}
                        if(!api.isInstance(source))continue;
                        visited.add(source);boolean tx=kind.contains("transaction");String label=tx?"transaction":"cache";
                        java.lang.reflect.Method ask=api.getMethod(tx?"getTransactionAttribute":"getCacheOperations",java.lang.reflect.Method.class,Class.class);
                        try {
                            Map<?,?> cache=metadataMap(source);
                            if(cache==null)throw new UnsupportedOperationException("metadata map unavailable");
                            if(!cache.isEmpty())cleared++;cache.clear();
                            for(Class<?> c:classes) {
                                OperationMetadataInspection.Proxies proxies=allMethods.get(c);
                                for(OperationMetadataInspection.Lookup lookup:proxies.lookups()) {
                                    java.lang.reflect.Method fresh=c.getMethod(lookup.method().getName(),lookup.method().getParameterTypes());
                                    ask.invoke(source,fresh,lookup.target());
                                }
                            }
                            for(Class<?> c:classes)inspect(source,ask,allMethods.get(c),c,tx,label);
                        }catch(Throwable failure) {
                            for(Class<?> c:classes)if(pending.get(c).inspect())diagnostic(c,label,"repair failed: "+failure.getClass().getSimpleName(),false);
                        }
                    }
                }
            }catch(Throwable failure) {
                for(Class<?> c:classes)if(pending.get(c).inspect())diagnostic(c,"cache/transaction","context repair failed: "+failure.getClass().getSimpleName(),false);
            }
        }
        return cleared;
    }
    private void inspect(Object source,java.lang.reflect.Method ask,OperationMetadataInspection.Proxies proxies,Class<?> owner,boolean tx,String kind) throws Exception {
        Pending state=pending.get(owner);if(!state.inspect())return;
        boolean matched=proxies.unsupported()==null;
        if(proxies.unsupported()!=null&&state.reportUnsupported())diagnostic(owner,kind,proxies.unsupported(),true);
        String standard=tx?"org.springframework.transaction.annotation.AnnotationTransactionAttributeSource":"org.springframework.cache.annotation.AnnotationCacheOperationSource";
        if(!source.getClass().getName().equals(standard)) {
            passConcerns.computeIfAbsent(owner,k->new java.util.HashSet<>()).add(kind);
            if(state.reportUnsupported())diagnostic(owner,kind,"custom operation source",true);
            return;
        }
        String configuration=OperationMetadataInspection.sourceConfiguration(source,tx);
        if(configuration!=null) {
            passConcerns.computeIfAbsent(owner,k->new java.util.HashSet<>()).add(kind);
            if(state.reportUnsupported())diagnostic(owner,kind,configuration,true);
            return;
        }
        boolean inspected=false;
        for(OperationMetadataInspection.Lookup lookup:proxies.lookups()) {
            java.lang.reflect.Method fresh=owner.getDeclaredMethod(lookup.method().getName(),lookup.method().getParameterTypes());
            OperationMetadataExpectation.Expected expected=OperationMetadataExpectation.read(owner,fresh,tx);
            if(expected.unsupported()!=null) {
                matched=false;
                if(state.reportUnsupported())diagnostic(owner,kind,expected.unsupported(),true);
                continue;
            }
            java.util.List<Map<String,Object>> actual;
            try {actual=OperationMetadataInspection.answer(ask.invoke(source,lookup.method(),lookup.target()),tx);}
            catch(UnsupportedOperationException unsupported) {
                matched=false;
                if(state.reportUnsupported())diagnostic(owner,kind,unsupported.getMessage(),true);continue;
            }
            inspected=true;
            if(!expected.operations().equals(actual)) {
                matched=false;diagnostic(owner,kind,"annotation/source mismatch at "+fresh.getName(),false);
            }
        }
        if(matched&&inspected)passMatches.computeIfAbsent(owner,k->new java.util.HashSet<>()).add(kind);
        else passConcerns.computeIfAbsent(owner,k->new java.util.HashSet<>()).add(kind);
        StatusReporter.detail(kind+" metadata refreshed (best effort) for "+owner.getName());
    }
    private void diagnostic(Class<?> owner,String kind,String reason,boolean unsupported) {
        String message=owner.getName()+": "+kind+" metadata may still be stale; save again or restart ("+reason+")";
        passConcerns.computeIfAbsent(owner,k->new java.util.HashSet<>()).add(kind);
        if(unsupported&&!unsupportedReported.computeIfAbsent(owner,k->new java.util.HashSet<>()).add(kind+":"+reason))StatusReporter.detail(message);
        else {
            StatusReporter.warn(message);
            RestartLedger.note(owner.getName(),ledgerReason(kind,reason));
            ledgerConcerns.computeIfAbsent(owner,k->new java.util.HashSet<>()).add(new LedgerConcern(kind,reason,unsupported));
        }
    }
    private static String ledgerReason(String kind,String reason) {
        return kind+" metadata may still be stale ("+reason+")";
    }
    private static Map<?,?> metadataMap(Object source) throws Exception {
        for(Class<?> c=source.getClass();c!=null&&c!=Object.class;c=c.getSuperclass()) {
            Field found=null;
            for(String name:new String[]{"attributeCache","operationCache"}) {found=mapField(c,name);if(found!=null)break;}
            if(found==null)for(Field f:c.getDeclaredFields())if(Map.class.isAssignableFrom(f.getType())&&f.getName().endsWith("Cache")) {
                if(found!=null)throw new UnsupportedOperationException("ambiguous metadata maps");found=f;
            }
            if(found!=null){found.setAccessible(true);return (Map<?,?>)found.get(source);}
        }
        return null;
    }

    /**
     * {@code AnnotationUtils.clearCache()} and
     * {@code ReflectionUtils.clearCache()}, resolved against the reloaded
     * class's own Spring. Both are public statics Spring ships for exactly
     * this, both repopulate on the next lookup, and a Spring that removed
     * them makes this a no-op.
     */
    static String clearSpringAnnotationCaches(Class<?> reloadedClass) {
        ClassLoader loader = reloadedClass.getClassLoader();
        if (loader == null) return null;
        for (String utility : new String[] {
                "org.springframework.core.annotation.AnnotationUtils",
                "org.springframework.util.ReflectionUtils"}) {
            try {
                Class.forName(utility, false, loader)
                        .getMethod("clearCache").invoke(null);
            } catch (ClassNotFoundException absent) {
                // No Spring utilities on this loader.
            } catch (Throwable failure) {
                return "annotation cache repair failed: "+failure.getClass().getSimpleName();
            }
        }
        return null;
    }

    /**
     * Finds the metadata cache up the class hierarchy and clears it.
     *
     * <p>The transaction source calls its map {@code attributeCache}; the
     * cache source called it that too until Spring Framework 6.1 renamed its
     * copy to {@code operationCache}, which was measured the hard way: the
     * transaction half of this cleared and the cache half silently did not,
     * while one success line claimed both. So both names are tried, and when
     * neither is there, any single Map-typed field whose name ends in
     * {@code Cache} is taken as the rename it will be next time. A Spring
     * that moves further away makes this a no-op, never an error.
     */
    static boolean clearAttributeCache(Object source) {
        return clearMetadataCache(source, "attributeCache", "operationCache");
    }

    /**
     * The same finder, told which names to try.
     *
     * <p>Spring Security keeps the same kind of map under a third name again
     * ({@code cachedAttributes}), and its method-security refresh needs
     * exactly this walk: up the hierarchy, by name, with the shape fallback
     * for the next rename. Passing the names in is what keeps one finder
     * instead of two that drift apart.
     */
    static boolean clearMetadataCache(Object source, String... names) {
        if (source == null) return false;
        for (Class<?> c = source.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Field field = null;
            for (String name : names) {
                if (field == null) field = mapField(c, name);
            }
            if (field == null) {
                Field byShape = null;
                for (Field candidate : c.getDeclaredFields()) {
                    if (!Map.class.isAssignableFrom(candidate.getType())) continue;
                    if (!candidate.getName().endsWith("Cache")) continue;
                    if (byShape != null) { byShape = null; break; }   // ambiguous: touch nothing
                    byShape = candidate;
                }
                field = byShape;
            }
            if (field == null) continue;                              // keep walking up
            try {
                field.setAccessible(true);
                Map<?, ?> cache = (Map<?, ?>) field.get(source);
                if (cache == null || cache.isEmpty()) return false;
                cache.clear();
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    private static Field mapField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            return Map.class.isAssignableFrom(field.getType()) ? field : null;
        } catch (NoSuchFieldException absent) {
            return null;
        }
    }
}
