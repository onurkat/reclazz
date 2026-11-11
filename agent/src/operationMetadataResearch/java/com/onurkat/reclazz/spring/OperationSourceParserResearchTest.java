/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.springframework.cache.annotation.*;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.util.ReflectionUtils;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Research only: actual redefine and Spring parsers; no modeled annotation result. */
class OperationSourceParserResearchTest {
    public static class Owner {
        @Cacheable(cacheNames="owned", condition="true") @Transactional(readOnly=true)
        public String read() { return "value"; }
    }
    @AfterEach void restore() throws Exception { redefine(true); clearAnnotations(); }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void freshMethodAfterClearReadsNewAnnotation(boolean tx) throws Exception {
        Method held=Owner.class.getMethod("read");
        assertTrue(parse(tx,held));
        redefine(false); clearAnnotations();
        Method fresh=Owner.class.getMethod("read");
        assertFalse(direct(tx,fresh));
        assertTrue(direct(tx,held),"held Method really retains old JDK annotation");
        assertFalse(parse(tx,fresh),"fresh parse control");
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void newEpochPublicationMustNotCarryOldEpochAnnotationPoison(boolean tx) throws Exception {
        Method held=Owner.class.getMethod("read");
        assertTrue(parse(tx,held)); // populate the held Method's actual JDK annotation cache
        Object source=tx?new AnnotationTransactionAttributeSource():new AnnotationCacheOperationSource();
        PublishMap map=new PublishMap(); installMap(source,map);
        ExecutorService workers=Executors.newFixedThreadPool(2);
        CountDownLatch oldReady=new CountDownLatch(1),allowOld=new CountDownLatch(1);
        try {
            // A token entered and normalized BEFORE redefine, so it holds this old Method.
            Future<Boolean> old=workers.submit(()->{oldReady.countDown();await(allowOld);return parse(tx,held);});
            await(oldReady); redefine(false); clearAnnotations();
            Method fresh=Owner.class.getMethod("read"); assertFalse(direct(tx,fresh));
            allowOld.countDown(); assertTrue(old.get(10,TimeUnit.SECONDS));
            // Old token is now drained, but it has populated real Spring annotation caches.
            // A new-epoch token uses a genuinely fresh Method and still reads that poison.
            Future<Boolean> newer=workers.submit(()->{
                map.pausedThread=Thread.currentThread();return lookup(tx,source,fresh);
            });
            await(map.publishing);
            assertTrue(map.pendingOld,"new epoch parsed stale metadata from real Spring annotation caches");
            assertTrue(old.isDone(),"older token drained");
            // Stronger than v3's initial refresh: clear annotation AND operation caches again.
            // This repairs the currently visible answer, but does not drain the new epoch token.
            clearAnnotations(); map.clear();
            assertFalse(lookup(tx,source,Owner.class.getMethod("read")),"final verification is fresh");
            map.resume.countDown(); assertTrue(newer.get(10,TimeUnit.SECONDS));
            assertFalse(lookup(tx,source,Owner.class.getMethod("read")),
                    "a post-epoch computation inherited old annotation poison and published after older-token drain");
        } finally {
            allowOld.countDown();map.resume.countDown();workers.shutdownNow();
            assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));
        }
    }
    static boolean direct(boolean tx,Method method) {
        return tx?method.getAnnotation(Transactional.class).readOnly():method.getAnnotation(Cacheable.class).condition().equals("true");
    }
    static boolean parse(boolean tx,Method method) {
        return tx?new SpringTransactionAnnotationParser().parseTransactionAnnotation(method).isReadOnly()
                :new SpringCacheAnnotationParser().parseCacheAnnotations(method).iterator().next().getCondition().equals("true");
    }
    static boolean lookup(boolean tx,Object source,Method method) {
        return tx?((AnnotationTransactionAttributeSource)source).getTransactionAttribute(method,Owner.class).isReadOnly()
                :((AnnotationCacheOperationSource)source).getCacheOperations(method,Owner.class).iterator().next().getCondition().equals("true");
    }
    static void clearAnnotations() { AnnotationUtils.clearCache();ReflectionUtils.clearCache(); }
    static void await(CountDownLatch latch) {
        try {assertTrue(latch.await(10,TimeUnit.SECONDS),"latch timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    private static final class PublishMap extends ConcurrentHashMap<Object,Object> {
        volatile Thread pausedThread;volatile boolean pendingOld;
        final CountDownLatch publishing=new CountDownLatch(1),resume=new CountDownLatch(1);
        @Override public Object put(Object key,Object value) {
            if(Thread.currentThread()==pausedThread) {
                pendingOld=value instanceof TransactionAttribute attr?attr.isReadOnly()
                        :((CacheOperation)((Collection<?>)value).iterator().next()).getCondition().equals("true");
                publishing.countDown();await(resume);
            }
            return super.put(key,value);
        }
    }
    static void installMap(Object source,Map<Object,Object> map) throws Exception {
        for(Class<?> c=source.getClass();c!=null;c=c.getSuperclass()) {
            for(String name:List.of("attributeCache","operationCache")) {
                try {Field f=c.getDeclaredField(name);f.setAccessible(true);f.set(source,map);return;}
                catch(NoSuchFieldException ignored) { }
            }
        }
        fail("Spring source cache not found");
    }
    static void redefine(boolean old) throws Exception {
        var inst=ByteBuddyAgent.install();
        var transformer=new ClassFileTransformer(){
            @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,
                    java.security.ProtectionDomain domain,byte[] bytes){
                if(type!=Owner.class)return null;
                var node=new ClassNode();new ClassReader(bytes).accept(node,0);
                for(var method:node.methods) if(method.name.equals("read")) {
                    for(var annotation:method.visibleAnnotations) {
                        String key=annotation.desc.endsWith("/Cacheable;")?"condition":"readOnly";
                        for(int i=0;i<annotation.values.size();i+=2) if(annotation.values.get(i).equals(key))
                            annotation.values.set(i+1,key.equals("condition")?Boolean.toString(old):old);
                    }
                }
                var writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();
            }
        };
        inst.addTransformer(transformer,true);
        try {inst.retransformClasses(Owner.class);} finally {inst.removeTransformer(transformer);}
    }
}
