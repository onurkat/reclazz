/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.AddedOperationBridge;
import com.onurkat.reclazz.bootstrap.InjectedNames;
import com.onurkat.reclazz.spring.SpringAddedAsyncEventTest.Owner;
import com.onurkat.reclazz.spring.SpringAddedAsyncEventTest.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.lang.invoke.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SpringAddedAsyncServiceTest {
    public static class VoidService { @Async public void receive(String value) { } }
    public static class Completable { @Async("two") public CompletableFuture<String> compute(String value) { return null; } }
    public static class FutureService { @Async("two") public Future<String> compute(String value) { return null; } }
    public static class TxService { @Async("two") @Transactional(rollbackFor=Exception.class) public CompletableFuture<String> compute(String value) { return null; } }
    public static class CachedService {
        @Async("two") @org.springframework.cache.annotation.Cacheable(cacheNames="values",key="#p0")
        public CompletableFuture<String> compute(String value) { return null; }
    }
    public static class SyncService { public CompletableFuture<String> compute(String value) { return null; } }
    public static class Nested {
        @Async("two") public CompletableFuture<List<String>> computeList(List<String> value) { return null; }
    }
    public static class BadResult { @Async public String compute(String value) { return null; } }
    public static class StageResult { @Async public CompletionStage<String> compute(String value) { return null; } }
    public static class CustomFuture extends CompletableFuture<String> { }
    public static class CustomResult { @Async public CustomFuture compute(String value) { return null; } }
    public static class MethodVariable { @Async public <T> CompletableFuture<T> compute(String value) { return null; } }
    public static class Wildcard { @Async public CompletableFuture<? extends CharSequence> compute(String value) { return null; } }
    public static class ClassVariable<T> { @Async public CompletableFuture<T> compute(String value) { return null; } }
    public static class MissingExecutor { @Async("missing") public CompletableFuture<String> compute(String value) { return null; } }
    public static class Empty { }
    public static class Native {
        final Owner target = new Owner();
        @Async("two") public CompletableFuture<String> compute(String value) throws Exception {
            return SpringAddedAsyncServiceTest.compute(target,value);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void standaloneVoidUsesTheDefaultExecutorAndUncaughtHandler(boolean proxy) throws Throwable {
        try(var s=new Scope(proxy,false)) {
            publish(VoidService.class,s);s.invoke("fail");assertTrue(s.target.calls.isEmpty());s.one.runNext();
            assertEquals(List.of("fail:one:false"),s.target.calls);
            assertEquals(List.of("receive:fail:failure"),s.errors);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void futuresReturnImmediatelyAndPreserveNativeResultAndErrorBehavior(boolean proxy) throws Throwable {
        try(var s=new Scope(proxy,false)) {
            Native original=new Native();
            Native nativeProxy=(Native)s.processor.postProcessAfterInitialization(original,"native");
            for(String input:List.of("ok","throw","exceptional","null")) {
                publish(Completable.class,s);
                var actual=(CompletableFuture<?>)invoke(s,CompletableFuture.class,input);
                var expected=nativeProxy.compute(input);
                assertFalse(actual.isDone());assertFalse(expected.isDone());
                s.two.runNext();s.two.runNext();
                assertEquals(outcome(expected),outcome(actual));
            }
            assertEquals(original.target.calls,s.target.calls);
            assertTrue(s.errors.isEmpty(),"Future failures belong on the returned future");
            publish(FutureService.class,s);
            var result=(Future<?>)invoke(s,Future.class,"future");assertFalse(result.isDone());s.two.runNext();
            assertEquals("future:two",result.get(2,TimeUnit.SECONDS));
        }
    }

    @Test void cancellationBeforeExecutionMatchesTheNativeOuterFuture() throws Throwable {
        try(var s=new Scope(false,false)) {
            for(Class<?> resultType:List.of(Future.class,CompletableFuture.class)) {
                publish(resultType==Future.class?FutureService.class:Completable.class,s);
                var result=(Future<?>)invoke(s,resultType,"cancelled");assertTrue(result.cancel(true));
                s.two.runNext();assertTrue(result.isCancelled());
                assertThrows(CancellationException.class,()->result.get(2,TimeUnit.SECONDS));
            }
            assertTrue(s.target.calls.isEmpty());assertTrue(s.errors.isEmpty());
        }
    }

    @Test void checkedThrowRollsBackButAnExceptionalReturnedFutureDoesNotRetroactivelyRollback() throws Throwable {
        try(var s=new Scope(true,true)) {
            publish(TxService.class,s);
            for(String input:List.of("ok","throw","exceptional")) {
                var result=(Future<?>)invoke(s,CompletableFuture.class,input);
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());s.two.runNext();
                if(input.equals("ok"))assertEquals("ok:two",result.get(2,TimeUnit.SECONDS));
                else assertInstanceOf(Exception.class,assertThrows(ExecutionException.class,()->result.get(2,TimeUnit.SECONDS)).getCause());
            }
            assertEquals(List.of("ok:two:true","throw:two:true","exceptional:two:true"),s.target.calls);
            assertEquals(List.of("ok","exceptional"),s.target.jdbc.queryForList("select id from entries",String.class));
            assertTrue(s.errors.isEmpty());
        }
    }

    @Test void nativeCacheStoresTheInnerFutureAndEachCallGetsItsOwnOuterFuture() throws Throwable {
        try(var s=new Scope(true,true)) {
            publish(CachedService.class,s);
            Future<?> first=(Future<?>)invoke(s,CompletableFuture.class,"cached");s.two.runNext();
            Future<?> second=(Future<?>)invoke(s,CompletableFuture.class,"cached");s.two.runNext();
            assertNotSame(first,second);assertEquals(first.get(),second.get());assertEquals(1,s.target.calls.size());
            Object stored=s.context.getBean(org.springframework.cache.CacheManager.class).getCache("values").get("cached").get();
            assertInstanceOf(CompletableFuture.class,stored);assertNotSame(first,stored);
        }
    }

    @Test void concreteNestedGenericMetadataIsPreservedForNativeConsumers() throws Throwable {
        try(var s=new Scope(false,false)) {
            byte[] bytes=bytes(Nested.class);
            var plan=AddedOperationMetadata.create(Owner.class,bytes,lookup(),false);
            var method=plan.entries().get(0).method();assertNull(plan.entries().get(0).reason());
            assertEquals("java.util.concurrent.CompletableFuture<java.util.List<java.lang.String>>",method.getGenericReturnType().getTypeName());
            assertEquals("java.util.List<java.lang.String>",method.getGenericParameterTypes()[0].getTypeName());
            publish(Nested.class,s);
            MethodHandle body=MethodHandles.lookup().findStatic(getClass(),"computeList",MethodType.methodType(CompletableFuture.class,Owner.class,List.class));
            var future=(Future<?>)site("computeList",body).invokeWithArguments(s.bean,List.of("a","b"));
            s.two.runNext();assertEquals(List.of("a","b","two"),future.get(2,TimeUnit.SECONDS));
        }
    }

    @Test void invalidReturnTypesAndUnresolvedGenericMetadataAreRefusedBeforeSubmission() throws Throwable {
        try(var s=new Scope(false,false)) {
            for(Class<?> metadata:List.of(BadResult.class,StageResult.class,CustomResult.class,MethodVariable.class,Wildcard.class,ClassVariable.class)) {
                var covered=publish(metadata,s);assertTrue(covered.isEmpty(),metadata.getSimpleName());
                Class<?> returns=metadata.getDeclaredMethod("compute",String.class).getReturnType();
                assertThrows(IllegalStateException.class,()->invoke(s,returns,"bad"),metadata.getSimpleName());
                assertTrue(s.one.queue.isEmpty());assertTrue(s.two.queue.isEmpty());assertTrue(s.target.calls.isEmpty());
            }
            publish(MissingExecutor.class,s);
            assertThrows(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,()->invoke(s,CompletableFuture.class,"missing"));
            assertTrue(s.target.calls.isEmpty());
        }
    }

    @Test void annotationRemovalMethodRemovalAndRestorePreserveTheHeldCallSite() throws Throwable {
        try(var s=new Scope(false,false)) {
            var call=site("compute",body(CompletableFuture.class));
            publish(Completable.class,s);Future<?> queued=(Future<?>)call.invokeWithArguments(s.bean,"queued");
            publish(SyncService.class,s);Future<?> sync=(Future<?>)call.invokeWithArguments(s.bean,"sync");
            assertTrue(sync.isDone());assertEquals("sync:"+Thread.currentThread().getName(),sync.get());
            publish(Empty.class,s);assertThrows(IllegalStateException.class,()->call.invokeWithArguments(s.bean,"removed"));
            s.two.runNext();assertEquals("queued:two",queued.get());
            publish(Completable.class,s);Future<?> restored=(Future<?>)call.invokeWithArguments(s.bean,"restored");
            s.two.runNext();assertEquals("restored:two",restored.get());
        }
    }

    static CompletableFuture<String> compute(Owner owner,String value) throws Exception {
        owner.calls.add(value+":"+Thread.currentThread().getName()+":"+TransactionSynchronizationManager.isActualTransactionActive());
        if(owner.jdbc!=null)owner.jdbc.update("insert into entries values (?)",value);
        if(value.equals("throw"))throw new Exception("thrown");
        if(value.equals("exceptional"))return CompletableFuture.failedFuture(new Exception("returned"));
        if(value.equals("null"))return null;
        return CompletableFuture.completedFuture(value+":"+Thread.currentThread().getName());
    }
    static CompletableFuture<List<String>> computeList(Owner owner,List<String> values) {
        var copy=new ArrayList<>(values);copy.add(Thread.currentThread().getName());return CompletableFuture.completedFuture(copy);
    }
    private static String outcome(Future<?> future) throws Exception {
        try{return "value:"+future.get(2,TimeUnit.SECONDS);}catch(ExecutionException failure){return "error:"+failure.getCause().getMessage();}
    }
    private static MethodHandles.Lookup lookup() throws Exception {return MethodHandles.privateLookupIn(Owner.class,MethodHandles.lookup());}
    private static MethodHandle body(Class<?> result) throws Exception {
        return MethodHandles.lookup().findStatic(SpringAddedAsyncServiceTest.class,"compute",MethodType.methodType(CompletableFuture.class,Owner.class,String.class))
                .asType(MethodType.methodType(result,Owner.class,String.class));
    }
    private static MethodHandle site(String name,MethodHandle body) {
        String desc=body.type().dropParameterTypes(0,1).toMethodDescriptorString();
        return AddedOperationBridge.externalCall(Owner.class,InjectedNames.siteKey(name,InjectedNames.descHash(desc)),new ConstantCallSite(body)).dynamicInvoker();
    }
    private static Object invoke(Scope s,Class<?> result,String value) throws Throwable {return site("compute",body(result)).invokeWithArguments(s.bean,value);}
    private static byte[] bytes(Class<?> metadata) throws Exception {
        var node=new ClassNode();
        try(var in=metadata.getResourceAsStream("/"+metadata.getName().replace('.','/')+".class")){new ClassReader(in.readAllBytes()).accept(node,0);}
        node.name=Type.getInternalName(Owner.class);var writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();
    }
    private static Set<String> publish(Class<?> metadata,Scope scope) throws Exception {
        return SpringAddedOperations.publish(Owner.class,bytes(metadata),lookup(),List.of(scope.context));
    }
}
