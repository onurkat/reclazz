/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.AddedOperationBridge;
import com.onurkat.reclazz.bootstrap.InjectedNames;
import com.onurkat.reclazz.util.Reflect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.invoke.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SpringAddedAsyncScheduledTest {
    public static class Owner { final List<String> calls = new CopyOnWriteArrayList<>(); boolean fail; }
    public static class Default { @Async @Scheduled(fixedDelay=1000) public void tick() { } }
    public static class Qualified { @Async("two") @Scheduled(fixedDelay=1000) public void tick() { } }
    public static class Repeated { @Async @Scheduled(fixedDelay=1000) @Scheduled(fixedRate=2000) public void tick() { } }
    public static class Missing { @Async("missing") @Scheduled(fixedDelay=1000) public void tick() { } }
    public static class Wrong { @Async("wrong") @Scheduled(fixedDelay=1000) public void tick() { } }
    public static class Reject { @Async("reject") @Scheduled(fixedDelay=1000) public void tick() { } }
    public static class Sync { @Scheduled(fixedDelay=1000) public void tick() { } }
    public static class Empty { }
    @Async public static class ClassAsync { @Scheduled(fixedDelay=1000) private void tick() { } }
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @Async public @interface Background { }
    @Background public static class ComposedClassAsync { @Scheduled(fixedDelay=1000) private void tick() { } }
    @Async public static class AsyncParent { }
    public static class InheritedAsync extends AsyncParent { @Scheduled(fixedDelay=1000) public void tick() { } }

    @Test void inheritedClassAsyncIsRefusedWhenNativeSpringWouldApplyIt() throws Exception {
        var pointcut = new AsyncAnnotationAdvisor().getPointcut();
        assertTrue(pointcut.getClassFilter().matches(InheritedAsync.class));
        assertTrue(pointcut.getMethodMatcher().matches(InheritedAsync.class.getMethod("tick"), InheritedAsync.class));
        var plan = AddedScheduledAdapter.inspect(bytes(InheritedAsync.class), Set.of("tick:()V"));
        assertTrue(plan.methods().isEmpty(), "inherited async must not become a synchronous scheduled task");
        assertTrue(plan.refused().get(0).contains("class-level async"));
    }


    @Test void directAndComposedClassAsyncNeverBecomeSynchronousPrivateTasks() throws Exception {
        for (Class<?> metadata : List.of(ClassAsync.class, ComposedClassAsync.class)) {
            var plan = AddedScheduledAdapter.inspect(bytes(metadata), Set.of("tick:()V"));
            assertTrue(plan.methods().isEmpty());
            assertTrue(plan.refused().get(0).contains("class-level async"));
        }
    }

    public static class Transaction {
        @Async("two") @Scheduled(fixedDelay=1000) @Transactional(rollbackFor=Exception.class)
        @org.springframework.cache.annotation.CacheEvict(cacheNames="values", allEntries=true)
        public void tick() { }
    }
    public static class Control {
        final List<String> calls = new CopyOnWriteArrayList<>();
        @Async("two") @Scheduled(fixedDelay=1000) public void tick() { calls.add(Thread.currentThread().getName()); }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void defaultQualifiedAndRepeatedSchedulesUseNativeAsyncInterceptors(boolean proxy) throws Throwable {
        try (var s = new Scope(proxy)) {
            s.publish(Default.class); s.invoke();
            assertTrue(s.target.calls.isEmpty()); assertEquals(1, s.one.queue.size());
            s.one.runNext(); assertEquals(List.of("one:false"), s.target.calls);
            s.publish(Qualified.class); s.invoke(); s.two.runNext();
            Control nativeTarget = new Control();
            Control nativeProxy = (Control) s.processor.postProcessAfterInitialization(nativeTarget, "control");
            new org.springframework.scheduling.support.ScheduledMethodRunnable(nativeProxy,
                    Control.class.getMethod("tick")).run();
            assertTrue(nativeTarget.calls.isEmpty()); s.two.runNext();
            assertEquals(List.of("two"), nativeTarget.calls);
            s.publish(Repeated.class); s.invoke(); s.one.runNext();
            assertEquals(List.of("one:false", "two:false", "one:false"), s.target.calls);
        }
    }

    @Test void transactionAndCacheAdviceExecuteOnWorkerAndFailureUsesNativeHandler() throws Throwable {
        try (var s = new Scope(true)) {
            var cache = s.context.getBean(org.springframework.cache.CacheManager.class).getCache("values");
            cache.put("keep", "before");
            s.publish(Transaction.class); s.invoke();
            assertNotNull(cache.get("keep")); assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            s.two.runNext(); assertNull(cache.get("keep"));
            s.target.fail = true; cache.put("keep", "after"); s.invoke(); s.two.runNext();
            assertEquals("after", cache.get("keep", String.class));
            assertEquals(List.of("two:true", "two:true"), s.target.calls);
            assertEquals(List.of("tick:0:failure"), s.errors);
        }
    }

    @Test void invalidExecutorsRefuseBeforeBodyAndCorrectionRecovers() throws Throwable {
        try (var s = new Scope(false)) {
            for (Class<?> invalid : List.of(Missing.class, Wrong.class, Reject.class)) {
                s.publish(invalid); assertThrows(Exception.class, s::invoke);
                assertTrue(s.one.queue.isEmpty()); assertTrue(s.two.queue.isEmpty());
                assertTrue(s.target.calls.isEmpty()); assertTrue(s.errors.isEmpty());
            }
            s.publish(Default.class); s.invoke(); s.one.runNext();
            assertEquals(List.of("one:false"), s.target.calls);
        }
    }

    @Test void syncRemovalAndRestorationPreserveAlreadySubmittedWork() throws Throwable {
        try (var s = new Scope(true)) {
            s.publish(Default.class); s.invoke();
            s.publish(Sync.class); s.invoke(); assertEquals(1, s.target.calls.size());
            assertEquals(Thread.currentThread().getName() + ":false", s.target.calls.get(0));
            s.publish(Empty.class); assertThrows(IllegalStateException.class, s::invoke);
            s.one.runNext(); assertEquals("one:false", s.target.calls.get(1));
            s.publish(Qualified.class); s.invoke(); s.two.runNext();
            assertEquals(3, s.target.calls.size());
            s.context.close(); assertThrows(IllegalStateException.class, s::invoke);
        }
    }

    @Test void missingAsyncInfrastructureNeverRunsSynchronously() throws Throwable {
        try (var s = new Scope(false)) {
            s.publish(Default.class);
            s.context.getDefaultListableBeanFactory().destroySingleton("async");
            assertThrows(IllegalStateException.class, s::invoke);
            assertTrue(s.target.calls.isEmpty()); assertTrue(s.one.queue.isEmpty());
        }
    }

    static void tick(Owner target) throws Exception {
        target.calls.add(Thread.currentThread().getName() + ":" + TransactionSynchronizationManager.isActualTransactionActive());
        if (target.fail) throw new Exception("failure");
    }
    static byte[] bytes(Class<?> metadata) throws Exception {
        var node = new ClassNode();
        try (var in = metadata.getResourceAsStream("/" + metadata.getName().replace('.', '/') + ".class")) {
            new ClassReader(in.readAllBytes()).accept(node, 0);
        }
        node.name = Type.getInternalName(Owner.class);
        var writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }
    static final class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final SpringAddedAsyncEventTest.Manual one = new SpringAddedAsyncEventTest.Manual("one"),
                two = new SpringAddedAsyncEventTest.Manual("two");
        final List<String> errors = new CopyOnWriteArrayList<>();
        final AsyncAnnotationBeanPostProcessor processor = new AsyncAnnotationBeanPostProcessor();
        final Owner target = new Owner(); final Object bean; final MethodHandle invoke;
        Scope(boolean proxy) throws Exception {
            processor.setExecutor(one); processor.setProxyTargetClass(true);
            processor.setExceptionHandler((failure, method, args) -> errors.add(method.getName() + ":" + args.length + ":" + failure.getMessage()));
            context.registerBean("async", AsyncAnnotationBeanPostProcessor.class, () -> processor);
            context.registerBean("two", Executor.class, () -> two);
            context.registerBean("wrong", String.class, () -> "not an executor");
            context.registerBean("reject", Executor.class, () -> task -> { throw new RejectedExecutionException("full"); });
            context.register(SpringAddedAsyncEventTest.TxConfig.class); context.refresh();
            if (proxy) {
                var pf = new ProxyFactory(target); pf.setProxyTargetClass(true);
                pf.addAdvisor((Advisor) Reflect.readField(processor, "advisor"));
                for (var advisor : context.getBeansOfType(Advisor.class).values()) pf.addAdvisor(advisor);
                bean = pf.getProxy();
            } else bean = target;
            context.getBeanFactory().registerSingleton("owner", bean);
            MethodHandle body = MethodHandles.lookup().findStatic(SpringAddedAsyncScheduledTest.class, "tick",
                    MethodType.methodType(void.class, Owner.class));
            invoke = AddedOperationBridge.externalCall(Owner.class, InjectedNames.siteKey("tick", InjectedNames.descHash("()V")),
                    new ConstantCallSite(body)).dynamicInvoker();
        }
        void publish(Class<?> metadata) throws Exception {
            byte[] saved = bytes(metadata);
            if (metadata != Empty.class) {
                var plan = AddedScheduledAdapter.inspect(saved, Set.of("tick:()V"));
                assertEquals(1, plan.methods().size(), plan.refused().toString());
            }
            SpringAddedOperations.publish(Owner.class, saved, MethodHandles.privateLookupIn(Owner.class, MethodHandles.lookup()), List.of(context));
        }
        void invoke() throws Throwable { invoke.invokeWithArguments(bean); }
        public void close() { context.close(); AddedOperationBridge.publish(Owner.class, Map.of()); }
    }
}
