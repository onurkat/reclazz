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
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.invoke.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SpringAddedAsyncEventTest {
    public static class Owner { final List<String> calls = new CopyOnWriteArrayList<>(); JdbcTemplate jdbc; }
    public static class Default { @Async @EventListener public void receive(String event) { } }
    public static class Qualified { @Async("two") @EventListener public void receive(String event) { } }
    public static class Missing { @Async("missing") @EventListener public void receive(String event) { } }
    public static class Wrong { @Async("wrong") @EventListener public void receive(String event) { } }
    public static class Sync { @EventListener public void receive(String event) { } }
    public static class Empty { }
    public static class Transaction {
        @Async("two") @EventListener @Transactional(rollbackFor=Exception.class)
        @org.springframework.cache.annotation.CacheEvict(cacheNames="values", allEntries=true)
        public void receive(String event) { }
    }
    public static class Control {
        final List<String> calls = new CopyOnWriteArrayList<>();
        @Async("two") public void receive(String value) { calls.add(value + ":" + Thread.currentThread().getName()); }
    }
    public static class CustomProcessor extends AsyncAnnotationBeanPostProcessor { }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement(proxyTargetClass=true)
    @org.springframework.cache.annotation.EnableCaching(proxyTargetClass=true)
    static class TxConfig {
        @Bean org.springframework.cache.CacheManager cacheManager() {
            return new org.springframework.cache.concurrent.ConcurrentMapCacheManager("values");
        }
        @Bean org.h2.jdbcx.JdbcDataSource dataSource() {
            var ds = new org.h2.jdbcx.JdbcDataSource();
            ds.setURL("jdbc:h2:mem:async_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"); return ds;
        }
        @Bean JdbcTemplate jdbc(org.h2.jdbcx.JdbcDataSource ds) { return new JdbcTemplate(ds); }
        @Bean org.springframework.transaction.PlatformTransactionManager transactionManager(org.h2.jdbcx.JdbcDataSource ds) {
            return new DataSourceTransactionManager(ds);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void defaultAndQualifiedExecutorsMatchNativeSpringOnTheOriginalTarget(boolean proxy) throws Throwable {
        try (var s = new Scope(proxy, false)) {
            s.publish(Default.class); s.invoke("default");
            assertTrue(s.target.calls.isEmpty()); assertEquals(1, s.one.queue.size());
            s.one.runNext(); assertEquals(List.of("default:one:false"), s.target.calls);
            s.publish(Qualified.class); s.invoke("qualified");
            assertEquals(1, s.two.queue.size()); s.two.runNext();
            Control nativeTarget = new Control();
            Control nativeProxy = (Control) s.processor.postProcessAfterInitialization(nativeTarget, "control");
            nativeProxy.receive("native"); assertTrue(nativeTarget.calls.isEmpty());
            s.two.runNext();
            assertEquals(List.of("native:two"), nativeTarget.calls);
            assertEquals(List.of("default:one:false", "qualified:two:false"), s.target.calls);
            assertTrue(s.errors.isEmpty());
        }
    }

    @Test void nativeNamedDefaultExecutorIsUsedWithoutAnExplicitConfigurer() throws Throwable {
        try (var s = new Scope(false, false, false)) {
            s.publish(Default.class); s.invoke("default"); s.one.runNext();
            assertEquals(List.of("default:one:false"), s.target.calls);
            assertTrue(s.two.queue.isEmpty());
        }
    }

    @Test void repeatedAsyncRefreshDoesNotInstallTheSameAdvisorTwice() throws Exception {
        try (var s = new Scope(false, false)) {
            Control target = new Control();
            Control bean = (Control) s.processor.postProcessAfterInitialization(target, "control");
            s.context.getBeanFactory().registerSingleton("control", bean);
            var platform = (com.onurkat.reclazz.platform.PlatformContext) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{com.onurkat.reclazz.platform.PlatformContext.class},
                    (proxy, method, args) -> method.getName().equals("getAllApplicationContexts") ? List.of(s.context) : null);
            var reloader = new SpringAsyncReloader(platform);
            for (int i = 0; i < 3; i++) {
                reloader.reloadAsyncMethods(Control.class);
                assertEquals(1, ((org.springframework.aop.framework.Advised) bean).getAdvisors().length);
                bean.receive("event" + i); s.two.runNext();
                assertEquals(i + 1, target.calls.size()); assertTrue(s.two.queue.isEmpty());
            }
        }
    }

    @Test void nativeUncaughtHandlerReceivesFailureMethodNameAndArguments() throws Throwable {
        try (var s = new Scope(false, false)) {
            s.publish(Default.class); s.invoke("fail"); s.one.runNext();
            assertEquals(List.of("receive:fail:failure"), s.errors);
            assertEquals(List.of("fail:one:false"), s.target.calls);
        }
    }

    @Test void transactionBeginsOnWorkerAndCheckedFailureRollsBack() throws Throwable {
        try (var s = new Scope(true, true)) {
            var cache = s.context.getBean(org.springframework.cache.CacheManager.class).getCache("values");
            cache.put("keep", "before");
            s.publish(Transaction.class); s.invoke("ok");
            assertNotNull(cache.get("keep"));
            assertEquals(0, s.target.jdbc.queryForObject("select count(*) from entries", Integer.class));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            s.two.runNext(); assertNull(cache.get("keep"));
            cache.put("keep", "after");
            s.invoke("fail"); s.two.runNext(); assertEquals("after", cache.get("keep", String.class));
            assertEquals(List.of("ok:two:true", "fail:two:true"), s.target.calls);
            assertEquals(1, s.target.jdbc.queryForObject("select count(*) from entries", Integer.class));
            assertEquals(List.of("receive:fail:failure"), s.errors);
        }
    }

    @Test void missingAndWrongExecutorsDoNotSubmitAndCorrectedSaveRecovers() throws Throwable {
        try (var s = new Scope(false, false)) {
            for (Class<?> invalid : List.of(Missing.class, Wrong.class)) {
                s.publish(invalid); assertThrows(Throwable.class, () -> s.invoke("bad"));
                assertTrue(s.one.queue.isEmpty()); assertTrue(s.two.queue.isEmpty()); assertTrue(s.target.calls.isEmpty());
            }
            s.publish(Qualified.class); s.invoke("fixed"); s.two.runNext();
            assertEquals(List.of("fixed:two:false"), s.target.calls);
        }
    }

    @Test void syncRemovalRestorationAndContextCloseDoNotLeakSubmissions() throws Throwable {
        try (var s = new Scope(false, false)) {
            s.publish(Default.class); s.invoke("queued");
            s.publish(Sync.class); s.invoke("sync"); assertEquals(1, s.target.calls.size());
            s.publish(Empty.class); assertThrows(IllegalStateException.class, () -> s.invoke("removed"));
            s.one.runNext(); assertEquals("queued:one:false", s.target.calls.get(1));
            s.publish(Default.class); s.invoke("restored"); s.one.runNext();
            assertEquals(3, s.target.calls.size());
            s.context.close(); assertThrows(IllegalStateException.class, () -> s.invoke("closed"));
            assertTrue(s.one.queue.isEmpty());
        }
    }

    @Test void contextsUseTheirOwnExecutorAndHandler() throws Throwable {
        try (var a = new Scope(false, false); var b = new Scope(false, false)) {
            publish(Default.class, List.of(a.context,b.context));
            a.invoke("a"); b.invoke("fail");
            assertEquals(1,a.one.queue.size()); assertEquals(1,b.one.queue.size());
            a.one.runNext(); b.one.runNext();
            assertEquals(List.of("a:one:false"), a.target.calls);
            assertTrue(a.errors.isEmpty()); assertEquals(List.of("receive:fail:failure"),b.errors);
        }
    }

    @Test void absentDuplicateCustomAndReorderedInfrastructureRefuseBeforeBody() throws Throwable {
        try (var s = new Scope(false, false)) {
            s.publish(Default.class);
            s.processor.setBeforeExistingAdvisors(false);
            assertThrows(IllegalStateException.class, () -> s.invoke("order"));
            s.processor.setBeforeExistingAdvisors(true);
            s.context.getBeanFactory().registerSingleton("duplicate", new AsyncAnnotationBeanPostProcessor());
            assertThrows(IllegalStateException.class, () -> s.invoke("duplicate"));
            s.context.getDefaultListableBeanFactory().destroySingleton("duplicate");
            s.context.getDefaultListableBeanFactory().destroySingleton("async");
            assertThrows(IllegalStateException.class, () -> s.invoke("absent"));
            s.context.getBeanFactory().registerSingleton("async", new CustomProcessor());
            assertThrows(IllegalStateException.class, () -> s.invoke("custom"));
            assertTrue(s.target.calls.isEmpty()); assertTrue(s.one.queue.isEmpty());
        }
    }


    @Test void customAnnotationForeignAndDuplicateAsyncAdvisorsAreNotSilentlyBypassed() throws Throwable {
        try (var s = new Scope(true, false)) {
            s.publish(Default.class);
            var proxy = (org.springframework.aop.framework.Advised) s.bean;
            var original = proxy.getAdvisors()[0];
            proxy.addAdvisor(original);
            assertThrows(IllegalStateException.class, () -> s.invoke("duplicate"));
            proxy.removeAdvisor(1);
            proxy.removeAdvisor(0);
            proxy.addAdvisor(new AsyncAnnotationAdvisor());
            assertThrows(IllegalStateException.class, () -> s.invoke("foreign"));
            proxy.removeAdvisor(0); proxy.addAdvisor(original);
            ((AsyncAnnotationAdvisor) original).setAsyncAnnotationType(Deprecated.class);
            assertThrows(IllegalStateException.class, () -> s.invoke("custom-annotation"));
            assertTrue(s.one.queue.isEmpty()); assertTrue(s.target.calls.isEmpty());
        }
    }

    @Test void refreshedMetadataDoesNotAccumulateInTheNativeExecutorCache() throws Throwable {
        try (var s = new Scope(false, false)) {
            var advisor = (AsyncAnnotationAdvisor) Reflect.readField(s.processor, "advisor");
            var cache = (Map<?, ?>) Reflect.readField(advisor.getAdvice(), "executors");
            Control nativeTarget = new Control();
            ((Control) s.processor.postProcessAfterInitialization(nativeTarget, "control")).receive("native");
            s.two.runNext();
            int baseline = cache.size(); assertEquals(1, baseline);
            for (int i = 0; i < 4; i++) {
                s.publish(Qualified.class); s.invoke("event" + i); s.two.runNext();
                assertEquals(baseline + 1, cache.size());
            }
            s.publish(Empty.class); assertEquals(baseline, cache.size());
            assertTrue(cache.keySet().stream().anyMatch(m -> ((java.lang.reflect.Method) m).getDeclaringClass() == Control.class));
        }
    }

    @Test void rejectedSubmissionDoesNotRunTheBodyOrInvokeTheUncaughtHandler() throws Throwable {
        try (var s = new Scope(false, false)) {
            s.context.getBeanFactory().registerSingleton("reject", (Executor) task -> {
                throw new RejectedExecutionException("full");
            });
            var node = new ClassNode(); new ClassReader(bytes(Qualified.class)).accept(node, 0);
            node.methods.stream().filter(m -> m.name.equals("receive")).findFirst().orElseThrow()
                    .visibleAnnotations.stream().filter(a -> a.desc.equals(AddedOperationMetadata.ASYNC))
                    .findFirst().orElseThrow().values = new ArrayList<>(List.of("value", "reject"));
            var writer = new ClassWriter(0); node.accept(writer);
            SpringAddedOperations.publish(Owner.class, writer.toByteArray(),
                    MethodHandles.privateLookupIn(Owner.class, MethodHandles.lookup()), List.of(s.context));
            assertThrows(RejectedExecutionException.class, () -> s.invoke("rejected"));
            assertTrue(s.target.calls.isEmpty()); assertTrue(s.errors.isEmpty());
        }
    }

    static void receive(Owner target, String event) throws Exception {
        target.calls.add(event + ":" + Thread.currentThread().getName() + ":"
                + TransactionSynchronizationManager.isActualTransactionActive());
        if (target.jdbc != null) target.jdbc.update("insert into entries values (?)", event);
        if (event.equals("fail")) throw new Exception("failure");
    }
    static byte[] bytes(Class<?> metadata) throws Exception {
        var source = new ClassNode();
        try(var in=metadata.getResourceAsStream("SpringAddedAsyncEventTest$"+metadata.getSimpleName()+".class")) {
            new ClassReader(in.readAllBytes()).accept(source, 0);
        }
        source.name=Type.getInternalName(Owner.class);
        var writer=new ClassWriter(0); source.accept(writer); return writer.toByteArray();
    }
    static void publish(Class<?> metadata, List<Object> contexts) throws Exception {
        SpringAddedOperations.publish(Owner.class,bytes(metadata),MethodHandles.privateLookupIn(Owner.class,MethodHandles.lookup()),contexts);
    }
    static final class Manual implements Executor {
        final BlockingQueue<Runnable> queue=new LinkedBlockingQueue<>(); final String name;
        Manual(String name) { this.name=name; }
        public void execute(Runnable action) { queue.add(action); }
        void runNext() throws Exception {
            Runnable task=queue.poll(2,TimeUnit.SECONDS); assertNotNull(task,"no async submission");
            var completion=new FutureTask<Void>(task,null);
            new Thread(completion,name).start(); completion.get(5,TimeUnit.SECONDS);
        }
    }
    static final class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();
        final Manual one=new Manual("one"),two=new Manual("two");
        final List<String> errors=new CopyOnWriteArrayList<>();
        final AsyncAnnotationBeanPostProcessor processor=new AsyncAnnotationBeanPostProcessor();
        final Owner target=new Owner(); final Object bean; final MethodHandle invoke;
        Scope(boolean proxy,boolean tx) throws Exception { this(proxy, tx, true); }
        Scope(boolean proxy,boolean tx,boolean configured) throws Exception {
            if (configured) processor.setExecutor(one);
            else context.registerBean("taskExecutor", Manual.class, () -> one);
            processor.setProxyTargetClass(true);
            processor.setExceptionHandler((failure,method,args)->errors.add(method.getName()+":"+args[0]+":"+failure.getMessage()));
            context.registerBean("async",AsyncAnnotationBeanPostProcessor.class,()->processor);
            context.registerBean("two",Manual.class,()->two);
            context.registerBean("wrong",String.class,()->"not an executor");
            if(tx) context.register(TxConfig.class);
            context.refresh();
            if(tx) { target.jdbc=context.getBean(JdbcTemplate.class); target.jdbc.execute("create table entries(id varchar(30))"); }
            if(proxy) {
                var pf=new ProxyFactory(target);pf.setProxyTargetClass(true);
                pf.addAdvisor((Advisor)Reflect.readField(processor,"advisor"));
                for(var advisor:context.getBeansOfType(Advisor.class).values())pf.addAdvisor(advisor);
                bean=pf.getProxy();
            } else bean=target;
            context.getBeanFactory().registerSingleton("owner",bean);
            MethodHandle body=MethodHandles.lookup().findStatic(SpringAddedAsyncEventTest.class,"receive",
                    MethodType.methodType(void.class,Owner.class,String.class));
            invoke=AddedOperationBridge.externalCall(Owner.class,InjectedNames.siteKey("receive",
                    InjectedNames.descHash("(Ljava/lang/String;)V")),new ConstantCallSite(body)).dynamicInvoker();
        }
        void publish(Class<?> metadata) throws Exception { SpringAddedAsyncEventTest.publish(metadata,List.of(context)); }
        void invoke(String event) throws Throwable { invoke.invokeWithArguments(bean,event); }
        public void close() { context.close(); AddedOperationBridge.publish(Owner.class,Map.of()); }
    }
}
