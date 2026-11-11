/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.context.support.GenericApplicationContext;
import com.onurkat.reclazz.platform.PlatformContext;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.onurkat.reclazz.spring.OperationSourceParserResearchTest.*;
import com.onurkat.reclazz.spring.OperationSourceParserResearchTest.Owner;

/** Research only: Route A, actual CGLIB retained Method, JVM redefine and Spring parsers. */
public class OperationSourceRouteAResearchTest {
    public static class Control { public String read() { return "control"; } }
    @AfterEach void restore() throws Exception { redefine(true); refreshRetained(); clearAnnotations(); }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void actualProxyMethodCanBeRefreshed(boolean tx) throws Exception {
        try (Scope s=new Scope(tx)) {
            assertTrue(parse(tx,s.held)); redefine(false); clearAnnotations();
            assertTrue(direct(tx,s.held)); assertFalse(direct(tx,Owner.class.getMethod("read")));
            assertTrue(refreshRetained()>0); clearAnnotations();
            assertFalse(direct(tx,s.held)); assertFalse(parse(tx,s.held));
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void sourceRefreshPreventsDeferredOldParseFromPoisoningNewEpoch(boolean tx) throws Exception {
        try (Scope s=new Scope(tx)) {
            assertTrue(parse(tx,s.held));
            CountDownLatch ready=new CountDownLatch(1),resume=new CountDownLatch(1);
            Future<Boolean> old=s.workers.submit(()->{ready.countDown();await(resume);return parse(tx,s.held);});
            try {
                await(ready); s.refresh(); resume.countDown(); assertFalse(old.get(10,TimeUnit.SECONDS));
                // New epoch still goes through the real operation source and publication.
                s.map.pause=true;
                Future<Boolean> newer=s.workers.submit(()->{s.map.thread=Thread.currentThread();return s.old(Owner.class.getMethod("read"));});
                await(s.map.ready); assertFalse(s.map.pendingOld); assertTrue(old.isDone());
                clearAnnotations();s.map.clear(); assertFalse(s.old(Owner.class.getMethod("read")));
                s.map.resume.countDown();assertFalse(newer.get(10,TimeUnit.SECONDS));assertFalse(s.old(s.held));
            } finally { resume.countDown(); }
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void withoutConcurrentRequestFreshMetadataIsRepopulated(boolean tx) throws Exception {
        try (Scope s=new Scope(tx)) { assertTrue(s.old(s.held));s.refresh();assertEquals(1,s.reload(Owner.class));assertFalse(s.old(s.held)); }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void requestBetweenClearAndRepopulateSeesRefreshedMethod(boolean tx) throws Exception {
        try (Scope s=new Scope(tx)) {
            assertTrue(s.old(s.held));s.refresh();s.map.clear();
            assertFalse(s.old(s.held),"interleaved request uses the repaired retained Method");
            assertFalse(s.old(Owner.class.getMethod("read")));
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void laterClassClearDoesNotResurrectRetainedOldAnnotations(boolean tx) throws Exception {
        try (Scope s=new Scope(tx)) {
            assertTrue(s.old(s.held));s.refresh();assertEquals(1,s.reload(Owner.class));assertFalse(s.old(s.held));
            assertEquals(1,s.reload(Control.class));assertFalse(s.old(s.held));
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void alreadyParsedOldResultMustNotPublishAfterRefresh(boolean tx) throws Exception { lateWriter(tx,true); }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void completedComputeMustNotPublishAfterRefresh(boolean tx) throws Exception { lateWriter(tx,false); }
    private void lateWriter(boolean tx,boolean insideCompute) throws Exception {
        try (Scope s=new Scope(tx)) {
            s.gate.pause=insideCompute;s.map.pause=!insideCompute;
            Future<Boolean> old=s.workers.submit(()->{
                s.gate.thread=Thread.currentThread();s.map.thread=Thread.currentThread();return s.old(s.held);
            });
            await(insideCompute?s.gate.ready:s.map.ready);
            s.refresh();s.map.clear();assertFalse(direct(tx,s.held),"source itself repaired");
            assertFalse(s.old(Owner.class.getMethod("read")),"visible metadata freshly verified");
            s.gate.resume.countDown();s.map.resume.countDown();assertTrue(old.get(10,TimeUnit.SECONDS));
            assertFalse(s.old(s.held),"Route A cannot revoke a result already computed before the Method refresh");
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void combinedRefreshAndOldTokenDrainMustRejectInProgressAnnotationPublication(boolean tx) throws Exception {
        try (Scope s=new Scope(tx)) {
            Class<?> scanner=Class.forName("org.springframework.core.annotation.AnnotationsScanner");
            CountDownLatch captured=new CountDownLatch(1),publishAnnotations=new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Thread> oldThread=new java.util.concurrent.atomic.AtomicReference<>();
            annotationCapture=(value,key)-> {
                if(Thread.currentThread()==oldThread.get()&&key instanceof Method m
                        &&m.getDeclaringClass()==Owner.class&&m.getName().equals("read")) {
                    assertTrue(Arrays.stream(value).anyMatch(annotation->
                        annotation instanceof org.springframework.transaction.annotation.Transactional t&&t.readOnly()));
                    captured.countDown();await(publishAnnotations);
                }
            };
            var inst=ByteBuddyAgent.install();
            var transformer=new java.lang.instrument.ClassFileTransformer() {
                @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,
                        java.security.ProtectionDomain domain,byte[] bytes) {
                    if(type!=scanner)return null;
                    var node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(bytes).accept(node,0);
                    int hooks=0;
                    for(var method:node.methods) if(method.name.equals("getDeclaredAnnotations")) {
                        for(var instruction:method.instructions.toArray())
                            if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                                    &&call.owner.equals("java/lang/reflect/AnnotatedElement")
                                    &&call.name.equals("getDeclaredAnnotations")&&call.desc.equals("()[Ljava/lang/annotation/Annotation;")) {
                                var added=new org.objectweb.asm.tree.InsnList();
                                added.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.DUP));
                                added.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD,0));
                                added.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC,
                                    "com/onurkat/reclazz/spring/OperationSourceRouteAResearchTest","afterAnnotationRead",
                                    "([Ljava/lang/annotation/Annotation;Ljava/lang/reflect/AnnotatedElement;)V",false));
                                method.instructions.insert(instruction,added);hooks++;
                            }
                    }
                    assertEquals(1,hooks,"verified scanner annotation-read site");
                    var writer=new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
                    node.accept(writer);return writer.toByteArray();
                }
            };
            inst.addTransformer(transformer,true);inst.retransformClasses(scanner);
            // Research token model: registered before compute, live through lookup's terminal put.
            java.util.concurrent.atomic.AtomicInteger oldTokens=new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger newTokens=new java.util.concurrent.atomic.AtomicInteger();
            try {
                clearAnnotations();s.gate.pause=true;
                Future<Boolean> old=s.workers.submit(()->{
                    oldTokens.incrementAndGet();oldThread.set(Thread.currentThread());s.gate.thread=Thread.currentThread();
                    try{return s.old(s.held);}finally{oldTokens.decrementAndGet();}
                });
                await(captured);
                s.refresh(); // bytes -> declared cache -> Spring cache clear
                assertFalse(direct(tx,s.held),"retained Method repaired before epoch transition");
                s.map.clear(); // epoch transition; future registrations below belong to new epoch
                assertFalse(s.old(Owner.class.getMethod("read")),"initial reloaded-class repopulation is fresh");
                assertEquals(1,oldTokens.get());
                publishAnnotations.countDown();await(s.gate.ready);
                // Old parser published its already captured Annotation[] after the refresh.
                s.map.pause=true;
                Future<Boolean> newer=s.workers.submit(()->{
                    newTokens.incrementAndGet();s.map.thread=Thread.currentThread();
                    try{return proxyLookup(tx,s,Owner.class.getMethod("read"));}finally{newTokens.decrementAndGet();}
                });
                await(s.map.ready);assertTrue(s.map.pendingOld,"new epoch inherits real scanner poison");
                s.gate.resume.countDown();assertTrue(old.get(10,TimeUnit.SECONDS));
                assertEquals(0,oldTokens.get(),"old token drained after actual operation publication");
                assertEquals(1,newTokens.get(),"new epoch publication is still in flight");
                clearAnnotations();s.map.clear();
                assertFalse(proxyLookup(tx,s,Owner.class.getMethod("read")),"post-drain repair verifies fresh answer");
                s.map.resume.countDown();assertTrue(newer.get(10,TimeUnit.SECONDS));assertEquals(0,newTokens.get());
                assertFalse(proxyLookup(tx,s,s.held),"combined Route A plus old-token drain still permits transitive late publication");
            } finally {
                publishAnnotations.countDown();s.gate.resume.countDown();s.map.resume.countDown();
                s.workers.shutdown();assertTrue(s.workers.awaitTermination(10,TimeUnit.SECONDS));
                inst.removeTransformer(transformer);inst.retransformClasses(scanner);annotationCapture=null;clearAnnotations();
            }
        }
    }
    private static boolean proxyLookup(boolean tx,Scope s,Method method) {
        // Spring's distinct targetClass key: initial reloaded-owner repopulation does not fill this miss.
        return tx?((AnnotationTransactionAttributeSource)s.source).getTransactionAttribute(method,s.proxy.getClass()).isReadOnly()
            :((AnnotationCacheOperationSource)s.source).getCacheOperations(method,s.proxy.getClass()).iterator().next().getCondition().equals("true");
    }
    private static volatile java.util.function.BiConsumer<java.lang.annotation.Annotation[],AnnotatedElement> annotationCapture;
    public static void afterAnnotationRead(java.lang.annotation.Annotation[] value,AnnotatedElement key) {
        var hook=annotationCapture;if(hook!=null)hook.accept(value,key);
    }
    private static boolean oldValue(Object result) {
        return result instanceof TransactionAttribute a?a.isReadOnly():result instanceof Collection<?> c&&!c.isEmpty()
                &&((CacheOperation)c.iterator().next()).getCondition().equals("true");
    }
    static final class Gate {
        volatile boolean pause;volatile Thread thread;
        final CountDownLatch ready=new CountDownLatch(1),resume=new CountDownLatch(1);
        void afterParse(Object result) { if(pause&&Thread.currentThread()==thread&&oldValue(result)){ready.countDown();await(resume);} }
    }
    static final class Publication extends ConcurrentHashMap<Object,Object> {
        volatile boolean pause,pendingOld;volatile Thread thread;
        final CountDownLatch ready=new CountDownLatch(1),resume=new CountDownLatch(1);
        @Override public Object put(Object key,Object value) {
            if(pause&&Thread.currentThread()==thread){pendingOld=oldValue(value);ready.countDown();await(resume);}
            return super.put(key,value);
        }
    }
    static final class Scope implements AutoCloseable {
        final boolean tx;final Method held;final Object proxy,source;
        final Gate gate=new Gate();final Publication map=new Publication();
        final ExecutorService workers=Executors.newFixedThreadPool(2);
        final GenericApplicationContext context=new GenericApplicationContext();
        final SpringOperationSourceReloader reloader;
        Scope(boolean tx) throws Exception {
            this.tx=tx;
            var factory=new ProxyFactory(new Owner());factory.setProxyTargetClass(true);proxy=factory.getProxy();
            refreshRetained();clearAnnotations();
            Method found=null;
            for(Field f:proxy.getClass().getDeclaredFields()) if(Modifier.isStatic(f.getModifiers())&&f.getType()==Method.class){
                f.setAccessible(true);Method m=(Method)f.get(null);
                if(m!=null&&m.getDeclaringClass()==Owner.class&&m.getName().equals("read"))found=m;
            }
            assertNotNull(found,"actual CGLIB retained read Method");held=found;
            source=tx?new AnnotationTransactionAttributeSource(){
                @Override protected TransactionAttribute findTransactionAttribute(Method m){var result=super.findTransactionAttribute(m);gate.afterParse(result);return result;}
            }:new AnnotationCacheOperationSource(){
                @Override protected Collection<CacheOperation> findCacheOperations(Method m){var result=super.findCacheOperations(m);gate.afterParse(result);return result;}
            };
            installMap(source,map);context.getBeanFactory().registerSingleton("operationSource",source);context.refresh();
            PlatformContext platform=(PlatformContext)Proxy.newProxyInstance(PlatformContext.class.getClassLoader(),new Class<?>[]{PlatformContext.class},(p,m,a)->{
                if(m.getName().equals("getAllApplicationContexts"))return List.of(context);throw new UnsupportedOperationException(m.getName());
            });reloader=new SpringOperationSourceReloader(platform);
        }
        void refresh() throws Exception { redefine(false);assertTrue(refreshRetained()>0);clearAnnotations(); }
        int reload(Class<?> type){return reloader.reloadOperationSources(type,true);}
        boolean old(Method method){return lookup(tx,source,method);}
        public void close() throws Exception {gate.resume.countDown();map.resume.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));context.close();}
    }
    private static int refreshRetained() throws Exception {
        var inst=ByteBuddyAgent.install();Module own=OperationSourceRouteAResearchTest.class.getModule();
        // Research-only extra opening: java.lang.reflect alone still leaves
        // Method fields filtered by Class.getDeclaredFields. Never shipped.
        inst.redefineModule(Object.class.getModule(),Set.of(),Map.of(),Map.of("java.lang",Set.of(own),"java.lang.reflect",Set.of(own)),Set.of(),Map.of());
        Method raw=Class.class.getDeclaredMethod("getDeclaredFields0",boolean.class);raw.setAccessible(true);
        Map<String,Field> fields=new HashMap<>();
        for(Field f:(Field[])raw.invoke(Method.class,false)){f.setAccessible(true);fields.put(f.getName(),f);}
        Field declared=Executable.class.getDeclaredField("declaredAnnotations");declared.setAccessible(true);
        int feature=Runtime.version().feature();
        assertTrue(feature==17||feature==21,"research adapter only verifies installed JDK17/21 layouts");
        for(String name:List.of("annotations","parameterAnnotations","annotationDefault")) {
            Field field=fields.get(name);
            assertNotNull(field,"verified annotation field: "+name);
            assertEquals(byte[].class,field.getType());
            // Installed SAPMachine javap evidence: private on17, private final on21.
            assertEquals(Modifier.PRIVATE|(feature==21?Modifier.FINAL:0),field.getModifiers());
        }
        assertEquals(Method.class,fields.get("root").getType());
        int count=0;
        for(Class<?> type:inst.getAllLoadedClasses()) if(type!=Owner.class&&Owner.class.isAssignableFrom(type)) {
            for(Field f:type.getDeclaredFields()) if(Modifier.isStatic(f.getModifiers())&&f.getType()==Method.class) {
                f.setAccessible(true);Method held=(Method)f.get(null);
                if(held==null||held.getDeclaringClass()!=Owner.class)continue;
                Method fresh=Owner.class.getDeclaredMethod(held.getName(),held.getParameterTypes());
                for(String name:List.of("annotations","parameterAnnotations","annotationDefault"))fields.get(name).set(held,fields.get(name).get(fresh));
                fields.get("root").set(held,null);declared.set(held,null);count++;
            }
        }
        return count;
    }
}
