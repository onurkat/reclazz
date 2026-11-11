/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.*;
import org.springframework.transaction.annotation.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import com.onurkat.reclazz.ui.StatusReporter;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

class OperationMetadataSchedulingTest {
    static class A { @Cacheable(value="items",condition="false") public String read(){return "a";} }
    static class B { @Cacheable(value="items",condition="false") public String read(){return "b";} }
    static class Tx { @Transactional(readOnly=false) public void read(){} }
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) @Transactional @interface Composed{}
    static class Meta { @Composed public void read(){} }
    static class Rig implements AutoCloseable {
        final AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();
        final AnnotationCacheOperationSource cache=new AnnotationCacheOperationSource();
        final AnnotationTransactionAttributeSource tx=new AnnotationTransactionAttributeSource();
        final SpringOperationSourceReloader reloader;
        final List<Runnable> timers=new ArrayList<>(), queue=new ArrayList<>();
        final List<String> warnings=new ArrayList<>(),events=new ArrayList<>();
        final StatusReporter.StatusListener listener=(level,message)->{events.add(message);if(level.equals("WARN"))warnings.add(message);};
        Rig(){
            context.registerBean("cache",AnnotationCacheOperationSource.class,()->cache);
            context.registerBean("tx",AnnotationTransactionAttributeSource.class,()->tx);context.refresh();
            reloader=new SpringOperationSourceReloader(new OperationMetadataRepairTest.OneContext(context));
            reloader.setScheduler((work,delay)->{assertEquals(250L,delay);timers.add(work);return ()->{};},queue::add);
            StatusReporter.addListener(listener);
        }
        void reload(Class<?> c){reloader.reloadOperationSources(c,true,Set.of(),null);}
        void fire(){timers.get(timers.size()-1).run();assertFalse(queue.isEmpty());queue.remove(0).run();}
        public void close(){reloader.close();StatusReporter.removeListener(listener);context.close();}
    }
    static Map<Object,Object> map(Object source) throws Exception {
        for(Class<?> c=source.getClass();c!=null;c=c.getSuperclass())for(String name:List.of("attributeCache","operationCache"))try{
            Field f=c.getDeclaredField(name);f.setAccessible(true);return (Map<Object,Object>)f.get(source);
        }catch(NoSuchFieldException absent){}
        throw new AssertionError("source map missing");
    }
    static void replaceMap(Object source,Map<Object,Object> replacement)throws Exception{
        for(Class<?> c=source.getClass();c!=null;c=c.getSuperclass())for(String name:List.of("attributeCache","operationCache"))try{
            Field f=c.getDeclaredField(name);f.setAccessible(true);f.set(source,replacement);return;
        }catch(NoSuchFieldException absent){}
        throw new AssertionError("source map missing");
    }
    @Test void firstSaveCacheAnnotationRemovalStillRepairsAndChecks()throws Exception{firstRemoval(false);}
    @Test void firstSaveTransactionAnnotationRemovalStillRepairsAndChecks()throws Exception{firstRemoval(true);}
    private void firstRemoval(boolean transaction)throws Exception {
        Class<?> owner=transaction?Tx.class:A.class;
        try(Rig r=new Rig()) {
            Object source=transaction?r.tx:r.cache;
            Method ask=source.getClass().getMethod(transaction?"getTransactionAttribute":"getCacheOperations",Method.class,Class.class);
            assertNotNull(ask.invoke(source,owner.getMethod("read"),owner));
            var inst=net.bytebuddy.agent.ByteBuddyAgent.install();
            var transformer=new java.lang.instrument.ClassFileTransformer(){
                @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,java.security.ProtectionDomain domain,byte[] bytes){
                    if(type!=owner)return null;
                    var node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(bytes).accept(node,0);
                    for(var method:node.methods)if(method.name.equals("read"))method.visibleAnnotations=null;
                    var writer=new org.objectweb.asm.ClassWriter(0);node.accept(writer);return writer.toByteArray();
                }
            };
            inst.addTransformer(transformer,true);
            try {
                inst.retransformClasses(owner);assertEquals(0,owner.getMethod("read").getDeclaredAnnotations().length);
                assertNotNull(ask.invoke(source,owner.getMethod("read"),owner));
                r.reloader.reloadOperationSources(owner,true,Set.of(transaction?"Lorg/springframework/transaction/annotation/Transactional;":"Lorg/springframework/cache/annotation/Cacheable;"),null);
                r.fire();assertNull(ask.invoke(source,owner.getMethod("read"),owner));assertTrue(r.warnings.isEmpty(),r.warnings.toString());
            }finally{inst.removeTransformer(transformer);inst.retransformClasses(owner);org.springframework.core.annotation.AnnotationUtils.clearCache();}
        }
    }
    static class Plain { @Deprecated public void read(){} }
    @Test void unrelatedAnnotationEditDoesNotInspectOrWarn(){
        try(Rig r=new Rig()){r.reload(Plain.class);assertTrue(r.timers.isEmpty());assertTrue(r.warnings.isEmpty());}
    }
    @Test void actualCglibRetainedMethodsAreEnumeratedAndRepaired()throws Exception {
        try(Rig r=new Rig()) {
            var proxyFactory=new org.springframework.aop.framework.ProxyFactory(new A());proxyFactory.setProxyTargetClass(true);
            Object proxy=proxyFactory.getProxy();r.context.getBeanFactory().registerSingleton("owner",proxy);
            var inspected=OperationMetadataInspection.methods(r.context,A.class);
            assertNull(inspected.unsupported(),inspected::unsupported);
            assertTrue(inspected.lookups().stream().anyMatch(k->k.target()==proxy.getClass()),"real CGLIB retained methods must be checked with proxy key");
            r.reload(A.class);r.fire();assertTrue(r.warnings.isEmpty(),r.warnings.toString());
        }
    }
    @Test void laterSaveRepopulatesEarlierAffectedClass()throws Exception {
        try(Rig r=new Rig()){r.reload(A.class);r.fire();assertEquals(1,map(r.cache).size());r.reload(B.class);assertEquals(2,map(r.cache).size());r.fire();assertEquals(2,map(r.cache).size());}
    }
    @Test void companionInspectionWarnsOnceAndUnknownBaselineSuppressesUnsupported() {
        try(Rig r=new Rig()) {
            r.reloader.reloadOperationSources(A.class,true,Set.of(),null,true);r.fire();
            r.reloader.reloadOperationSources(A.class,true,Set.of(),null,true);r.fire();
            assertEquals(1,r.warnings.stream().filter(s->s.contains("companion operation metadata")).count());
        }
        try(Rig r=new Rig()){r.reloader.reloadOperationSources(A.class,true,null,null,true);r.fire();assertTrue(r.warnings.isEmpty());}
    }
    @Test void previousComposedAnnotationRemovalWarnsOnce() {
        try(Rig r=new Rig()) {
            String descriptor="L"+Composed.class.getName().replace('.','/')+";";
            r.reloader.reloadOperationSources(A.class,true,Set.of(descriptor),null);r.fire();
            r.reloader.reloadOperationSources(A.class,true,Set.of(descriptor),null);r.fire();
            assertEquals(1,r.warnings.stream().filter(s->s.contains("previous composed metadata")).count());
        }
    }
    @Test void sameNameDifferentLoadersRemainDistinct()throws Exception {
        byte[] bytes;
        try(var in=A.class.getResourceAsStream("/"+A.class.getName().replace('.','/')+".class")){bytes=in.readAllBytes();}
        class CopyLoader extends ClassLoader {
            CopyLoader(){super(A.class.getClassLoader());}
            Class<?> define(byte[] code){return defineClass(A.class.getName(),code,0,code.length);}
        }
        Class<?> first=new CopyLoader().define(bytes),second=new CopyLoader().define(bytes);
        assertNotSame(first,second);assertEquals(first.getName(),second.getName());
        try(Rig r=new Rig()){r.reloader.beginBatch();r.reload(first);r.reload(second);r.reloader.endBatch();assertEquals(2,map(r.cache).size());r.fire();assertEquals(2,map(r.cache).size());assertTrue(r.warnings.isEmpty(),r.warnings.toString());}
    }
    @Test void actualRetainedOldMethodRepopulatesWithFreshMethodForEveryTargetKey()throws Exception {
        try(Rig r=new Rig()) {
            var factory=new org.springframework.aop.framework.ProxyFactory(new A());factory.setProxyTargetClass(true);
            Object proxy=factory.getProxy();r.context.getBeanFactory().registerSingleton("owner",proxy);
            Method held=OperationMetadataInspection.methods(r.context,A.class).lookups().stream().filter(k->k.target()==proxy.getClass()).findFirst().orElseThrow().method();
            assertEquals("false",held.getAnnotation(Cacheable.class).condition());
            r.cache.getCacheOperations(held,proxy.getClass());
            var inst=net.bytebuddy.agent.ByteBuddyAgent.install();
            var transformer=new java.lang.instrument.ClassFileTransformer(){
                @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,java.security.ProtectionDomain domain,byte[] bytes){
                    if(type!=A.class)return null;
                    var node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(bytes).accept(node,0);
                    for(var method:node.methods)if(method.name.equals("read"))for(var annotation:method.visibleAnnotations)
                        if(annotation.desc.equals("Lorg/springframework/cache/annotation/Cacheable;"))for(int i=0;i<annotation.values.size();i+=2)
                            if(annotation.values.get(i).equals("condition"))annotation.values.set(i+1,"true");
                    var writer=new org.objectweb.asm.ClassWriter(0);node.accept(writer);return writer.toByteArray();
                }
            };
            inst.addTransformer(transformer,true);
            try {
                inst.retransformClasses(A.class);
                assertEquals("true",A.class.getMethod("read").getAnnotation(Cacheable.class).condition());
                assertEquals("false",held.getAnnotation(Cacheable.class).condition(),"actual retained Method is stale");
                r.reload(A.class);r.fire();
                assertEquals("true",r.cache.getCacheOperations(held,proxy.getClass()).iterator().next().getCondition());
                assertEquals("false",held.getAnnotation(Cacheable.class).condition(),"repair must not mutate Method internals");
                assertTrue(r.warnings.isEmpty(),r.warnings.toString());
            }finally{inst.removeTransformer(transformer);inst.retransformClasses(A.class);org.springframework.core.annotation.AnnotationUtils.clearCache();}
        }
    }
    @Test void sharedSourceInspectsProxyMethodsFromEveryLiveContext()throws Exception {
        try(Rig r=new Rig();var child=new AnnotationConfigApplicationContext()) {
            child.setParent(r.context);
            var factory=new org.springframework.aop.framework.ProxyFactory(new A());factory.setProxyTargetClass(true);
            Object proxy=factory.getProxy();child.registerBean("owner",A.class,()->(A)proxy);child.refresh();
            var repair=new SpringOperationSourceReloader(new OperationMetadataRepairTest.OneContext(List.of(r.context,child)));
            repair.setScheduler((work,delay)->{r.timers.add(work);return ()->{};},r.queue::add);
            try {repair.reloadOperationSources(A.class,true,Set.of(),null);r.fire();assertEquals(2,map(r.cache).size());assertTrue(r.warnings.isEmpty(),r.warnings.toString());}
            finally {repair.close();}
        }
    }
    @Test void heartbeatAdmissionDoesNotWaitForRepairMonitor()throws Exception {
        try(Rig r=new Rig()) {
            r.reload(A.class);
            var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                synchronized(r.reloader) {
                    worker.submit(r.timers.get(r.timers.size()-1)).get(10,java.util.concurrent.TimeUnit.SECONDS);
                    assertEquals(1,r.queue.size(),"timer should admit without waiting for the metadata monitor");
                }
                r.queue.remove(0).run();
            }finally{worker.shutdownNow();assertTrue(worker.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
        }
    }
    @Test void disposedChildOwnerIsNotRepopulatedIntoSharedRootSource()throws Exception {
        try(Rig r=new Rig();var child=new AnnotationConfigApplicationContext()) {
            child.setParent(r.context);child.registerBean("owner",A.class,A::new);child.refresh();
            var repair=new SpringOperationSourceReloader(new OperationMetadataRepairTest.OneContext(List.of(r.context,child)));
            repair.setScheduler((work,delay)->{r.timers.add(work);return ()->{};},r.queue::add);
            try {
                repair.reloadOperationSources(A.class,true,Set.of(),null);assertEquals(1,map(r.cache).size());
                child.close();r.fire();assertTrue(map(r.cache).isEmpty(),"retired child class must not be held alive by repeated root-source repopulation");
                repair.reloadOperationSources(B.class,true,Set.of(),null);r.fire();assertEquals(1,map(r.cache).size());
                assertTrue(r.warnings.isEmpty(),r.warnings.toString());
            }finally{repair.close();}
        }
    }
    @Test void repairWithoutConcurrentWriterAndMatchingSamplesStayQuiet()throws Exception{
        try(Rig r=new Rig()){r.reload(A.class);r.fire();assertTrue(r.warnings.isEmpty(),r.warnings.toString());assertEquals("false",r.cache.getCacheOperations(A.class.getMethod("read"),A.class).iterator().next().getCondition());}
    }
    @Test void delayedRepairSeesInterveningStalePublication()throws Exception{
        try(Rig r=new Rig()){
            r.reload(A.class);Map<Object,Object> map=map(r.cache);Object key=map.keySet().iterator().next();
            var builder=new org.springframework.cache.interceptor.CacheableOperation.Builder();builder.setCacheNames("items");builder.setCondition("true");builder.setUnless("");
            map.put(key,List.of(builder.build()));
            r.timers.get(r.timers.size()-1).run();
            assertEquals("true",r.cache.getCacheOperations(A.class.getMethod("read"),A.class).iterator().next().getCondition(),"timer must only admit work");
            r.queue.remove(0).run();
            assertEquals("false",r.cache.getCacheOperations(A.class.getMethod("read"),A.class).iterator().next().getCondition());
        }
    }
    @Test void repeatedReloadCoalescesAndStaleTimerAlreadyEnqueuedIsIgnored()throws Exception{
        try(Rig r=new Rig()){
            r.reload(A.class);r.timers.get(0).run();assertEquals(1,r.queue.size());r.reload(B.class);
            r.queue.remove(0).run();r.fire();
            assertNotNull(r.cache.getCacheOperations(A.class.getMethod("read"),A.class));
            assertNotNull(r.cache.getCacheOperations(B.class.getMethod("read"),B.class));assertTrue(r.warnings.isEmpty());
        }
    }
    @Test void multiClassBatchClearsOnceAndRepopulatesAll()throws Exception{
        try(Rig r=new Rig()){
            class Counting extends ConcurrentHashMap<Object,Object>{int clears;@Override public void clear(){clears++;super.clear();}}
            Counting map=new Counting();replaceMap(r.cache,map);r.reloader.beginBatch();r.reload(A.class);r.reload(B.class);assertEquals(0,map.clears);
            r.reloader.endBatch();assertEquals(1,map.clears);assertEquals(2,map.size());r.fire();assertEquals(2,map.clears);assertEquals(2,map.size());
        }
    }
    @Test void equalCachedAnswersDoNotProveFreshnessAndRetainedFreshMismatchWarns()throws Exception{
        try(Rig r=new Rig()){
            r.cache.getCacheOperations(A.class.getMethod("read"),A.class);Map<Object,Object> old=map(r.cache);Object key=old.keySet().iterator().next();
            var builder=new org.springframework.cache.interceptor.CacheableOperation.Builder();builder.setCacheNames("items");builder.setCondition("true");builder.setUnless("");
            Map<Object,Object> poison=new ConcurrentHashMap<>(old){@Override public void clear(){}};poison.put(key,List.of(builder.build()));replaceMap(r.cache,poison);
            r.reload(A.class);assertTrue(r.warnings.stream().anyMatch(s->s.contains("annotation/source mismatch")),r.warnings.toString());
            assertEquals(r.cache.getCacheOperations(A.class.getMethod("read"),A.class),r.cache.getCacheOperations(A.class.getMethod("read"),A.class));
        }
    }
    @Test void writerAfterFinalCheckpointNeverProducesSuccessClaim()throws Exception{
        try(Rig r=new Rig()){
            r.reload(A.class);r.fire();Map<Object,Object> map=map(r.cache);Object key=map.keySet().iterator().next();
            var builder=new org.springframework.cache.interceptor.CacheableOperation.Builder();builder.setCacheNames("items");builder.setCondition("true");builder.setUnless("");map.put(key,List.of(builder.build()));
            assertEquals("true",r.cache.getCacheOperations(A.class.getMethod("read"),A.class).iterator().next().getCondition());
            assertFalse(r.events.stream().anyMatch(s->s.contains("metadata re-read")));
        }
    }
    @Test void repeatedUnsupportedReloadWarnsOncePerSession(){
        try(Rig r=new Rig()){
            r.reload(Meta.class);r.fire();r.reload(Meta.class);r.fire();
            assertEquals(1,r.warnings.stream().filter(s->s.contains("composed metadata")).count(),r.warnings.toString());
        }
    }
    @Test void unknownBaselineRepairsAndWarnsOnlyOnMismatch(){
        try(Rig r=new Rig()){r.reloader.reloadOperationSources(Meta.class,true);r.fire();assertTrue(r.warnings.isEmpty(),r.warnings.toString());}
    }
    @Test void stoppedSchedulerAndDisposedContextDoNotRunRepair(){
        try(Rig r=new Rig()){r.reload(A.class);r.context.close();r.fire();assertTrue(r.warnings.isEmpty());r.reloader.close();r.timers.forEach(Runnable::run);assertTrue(r.queue.isEmpty());}
    }
    @Test void absentSchedulerWarns(){
        try(Rig r=new Rig()){r.reloader.setScheduler(null,null);r.reload(A.class);assertTrue(r.warnings.stream().anyMatch(s->s.contains("delayed repair unavailable")));}
    }
    @Test void cacheAndTransactionDiagnosticsMatchIndependentOracle(){
        try(Rig r=new Rig()){r.reload(Tx.class);r.fire();assertTrue(r.warnings.isEmpty(),r.warnings.toString());}
    }
}
