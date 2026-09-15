/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.AddedOperationBridge;
import com.onurkat.reclazz.bootstrap.InjectedNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.AliasFor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.lang.annotation.*;
import java.lang.invoke.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComposedTransactionOperationTest {
    @org.junit.jupiter.api.AfterEach void clearLedger() { com.onurkat.reclazz.ui.RestartLedger.clear(); }
    @Target({ElementType.TYPE,ElementType.METHOD,ElementType.ANNOTATION_TYPE}) @Retention(RetentionPolicy.RUNTIME)
    @Transactional(rollbackFor=Exception.class)
    public @interface Work {
        @AliasFor(annotation=Transactional.class,attribute="readOnly") boolean readOnly() default false;
        @AliasFor(annotation=Transactional.class,attribute="transactionManager") String manager() default "transactionManager";
        @AliasFor(annotation=Transactional.class,attribute="propagation") Propagation propagation() default Propagation.REQUIRED;
    }
    @Target({ElementType.TYPE,ElementType.METHOD}) @Retention(RetentionPolicy.RUNTIME) @Work(readOnly=true)
    public @interface ReadWork {
        @AliasFor(annotation=Work.class,attribute="readOnly") boolean readOnly() default true;
    }
    @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
    @Work @org.springframework.scheduling.annotation.Async
    public @interface Mixed { }
    @Target(ElementType.ANNOTATION_TYPE) @Retention(RetentionPolicy.RUNTIME)
    public @interface Unrelated { }
    @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @Work @Unrelated
    public @interface Marked { }
    @Work(readOnly=true) public static class ClassDefault { public String write(int id,boolean fail) { return null; } }
    @Work(readOnly=true) public static class MethodOverride {
        @ReadWork(readOnly=false) public String write(int id,boolean fail) { return null; }
    }
    public static class Nested { @ReadWork public String write(int id,boolean fail) { return null; } }
    public static class Write { @Work public String write(int id,boolean fail) { return null; } }
    public static class NewTx { @Work(propagation=Propagation.REQUIRES_NEW) public String write(int id,boolean fail) { return null; } }
    public static class Missing { @Work(manager="missing") public String write(int id,boolean fail) { return null; } }
    public static class Unsafe { @Mixed public String write(int id,boolean fail) { return null; } }
    public static class Unknown { @Marked public String write(int id,boolean fail) { return null; } }
    @Target({ElementType.METHOD,ElementType.ANNOTATION_TYPE}) @Retention(RetentionPolicy.RUNTIME) @Work @CycleB
    public @interface CycleA { }
    @Target(ElementType.ANNOTATION_TYPE) @Retention(RetentionPolicy.RUNTIME) @CycleA
    public @interface CycleB { }
    public static class Cyclic { @CycleA public String write(int id,boolean fail) { return null; } }
    public static class Callback { @Work @org.springframework.context.event.EventListener public void event(String value) { } }
    public static class FutureTx { @Work public java.util.concurrent.CompletableFuture<String> future() { return null; } }
    public static class Plain { public String write(int id,boolean fail) { return null; } }
    public static class Empty { }
    public static class Owner {
        final JdbcTemplate jdbc; int calls;
        Owner(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    }
    @ParameterizedTest @ValueSource(classes={Write.class,Nested.class,ClassDefault.class,MethodOverride.class,NewTx.class})
    void savedMetadataMatchesNativeMergedTransactionAttributes(Class<?> metadata) throws Exception {
        var plan=AddedOperationMetadata.create(Owner.class,bytes(metadata),lookup(),false);
        assertTrue(plan.operations(),"composed transaction must activate the adapter");
        var entry=plan.entries().stream().filter(e->e.method().getName().equals("write")).findFirst().orElseThrow();
        assertTrue(entry.transaction()); assertNull(entry.reason());
        var source=new AnnotationTransactionAttributeSource();
        var nativeAttr=source.getTransactionAttribute(metadata.getMethod("write",int.class,boolean.class),metadata);
        var saved=source.getTransactionAttribute(entry.method(),Owner.class);
        assertNotNull(nativeAttr); assertNotNull(saved);
        assertEquals(nativeAttr.isReadOnly(),saved.isReadOnly());
        assertEquals(nativeAttr.getPropagationBehavior(),saved.getPropagationBehavior());
        assertEquals(nativeAttr.getQualifier(),saved.getQualifier());
        assertEquals(nativeAttr.rollbackOn(new Exception()),saved.rollbackOn(new Exception()));
        assertTrue(saved.rollbackOn(new Exception()));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void heldPlainAndCglibCallsCommitRollbackAndFollowAnnotationChanges(boolean proxy) throws Throwable {
        try(var s=new Scope(proxy)) {
            var call=site();
            s.publish(Write.class);
            assertEquals("true:false",call.dynamicInvoker().invokeWithArguments(s.bean,1,false));
            assertThrows(Exception.class,()->call.dynamicInvoker().invokeWithArguments(s.bean,2,true));
            assertEquals(1,s.rows());
            s.publish(Nested.class);
            assertEquals("true:true",call.dynamicInvoker().invokeWithArguments(s.bean,3,false));
            s.publish(MethodOverride.class);
            assertEquals("true:false",call.dynamicInvoker().invokeWithArguments(s.bean,4,false));
            s.publish(Plain.class);
            assertEquals("false:false",call.dynamicInvoker().invokeWithArguments(s.bean,5,false));
            s.publish(Write.class);
            assertThrows(Exception.class,()->call.dynamicInvoker().invokeWithArguments(s.bean,6,true));
            assertEquals(4,s.rows()); assertEquals(6,s.target.calls);
            s.publish(Empty.class);
            assertTrue(assertThrows(IllegalStateException.class,()->call.dynamicInvoker().invokeWithArguments(s.bean,7,false))
                    .getMessage().contains("removed"));
            assertEquals(6,s.target.calls);
            s.publish(Write.class);
            assertEquals("true:false",call.dynamicInvoker().invokeWithArguments(s.bean,8,false));
            assertEquals(5,s.rows());
        }
    }
    @Test void aliasPropagationCommitsInsideAnOuterRollback() throws Throwable {
        try(var s=new Scope(false)) {
            s.publish(NewTx.class); var call=site();
            var tx=new TransactionTemplate(s.context.getBean("transactionManager",org.springframework.transaction.PlatformTransactionManager.class));
            tx.execute(status->{
                try { assertEquals("true:false",call.dynamicInvoker().invokeWithArguments(s.bean,1,false)); }
                catch(Throwable failure) { throw new AssertionError(failure); }
                status.setRollbackOnly(); return null;
            });
            assertEquals(1,s.rows());
        }
    }
    @Test void managerAliasFailureOccursBeforeBody() throws Exception {
        try(var s=new Scope(false)) {
            s.publish(Missing.class); var call=site();
            assertThrows(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,
                    ()->call.dynamicInvoker().invokeWithArguments(s.bean,1,false));
            assertEquals(0,s.target.calls); assertEquals(0,s.rows());
        }
    }
    @ParameterizedTest @ValueSource(classes={Unsafe.class,Unknown.class,Cyclic.class})
    void mixedCompositionNeverFallsThroughToAnUnadvisedBody(Class<?> metadata) throws Exception {
        try(var s=new Scope(false)) {
            s.publish(metadata); var call=site();
            assertTrue(assertThrows(IllegalStateException.class,()->call.dynamicInvoker().invokeWithArguments(s.bean,1,false))
                    .getMessage().contains("unsupported method annotation"));
            assertEquals(0,s.target.calls); assertEquals(0,s.rows());
        }
    }
    @ParameterizedTest @ValueSource(classes={Callback.class,FutureTx.class})
    void compositionDoesNotExpandCallbackOrFuturePolicies(Class<?> metadata) throws Exception {
        var plan=AddedOperationMetadata.create(Owner.class,bytes(metadata),lookup(),false);
        assertTrue(plan.operations()); assertEquals(1,plan.entries().size());
        assertTrue(plan.entries().get(0).transaction());
        assertNotNull(plan.entries().get(0).reason());
    }
    public static String write(Owner owner,int id,boolean fail) throws Exception {
        owner.calls++; owner.jdbc.update("insert into entries values (?)",id);
        if(fail)throw new Exception("checked failure");
        return TransactionSynchronizationManager.isActualTransactionActive()+":"+TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }
    private static MethodHandles.Lookup lookup() throws IllegalAccessException { return MethodHandles.privateLookupIn(Owner.class,MethodHandles.lookup()); }
    private static CallSite site() throws ReflectiveOperationException {
        var type=MethodType.methodType(String.class,Owner.class,int.class,boolean.class);
        var direct=MethodHandles.lookup().findStatic(ComposedTransactionOperationTest.class,"write",type);
        String desc=type.dropParameterTypes(0,1).toMethodDescriptorString();
        return AddedOperationBridge.externalCall(Owner.class,InjectedNames.siteKey("write",InjectedNames.descHash(desc)),new ConstantCallSite(direct));
    }
    private static byte[] bytes(Class<?> metadata) throws Exception {
        byte[] original;
        try(var stream=metadata.getResourceAsStream("ComposedTransactionOperationTest$"+metadata.getSimpleName()+".class")) { original=stream.readAllBytes(); }
        var node=new ClassNode(); new ClassReader(original).accept(node,0); node.name=Type.getInternalName(Owner.class);
        var writer=new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }
    private static class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext(SpringAddedOperationsTest.Config.class);
        final JdbcTemplate jdbc=context.getBean(JdbcTemplate.class);
        final java.sql.Connection anchor;
        final Owner target; final Object bean;
        Scope(boolean proxy) throws Exception {
            anchor=context.getBean(org.h2.jdbcx.JdbcDataSource.class).getConnection(); jdbc.execute("create table entries(id int)");
            target=new Owner(jdbc);
            if(proxy) { var factory=new ProxyFactory(target); factory.setProxyTargetClass(true);
                for(var advisor:context.getBeansOfType(Advisor.class).values())factory.addAdvisor(advisor); bean=factory.getProxy(); }
            else bean=target;
            context.getBeanFactory().registerSingleton("owner",bean);
        }
        void publish(Class<?> metadata) throws Exception { SpringAddedOperations.publish(Owner.class,bytes(metadata),lookup(),List.of(context)); }
        int rows() { return jdbc.queryForObject("select count(*) from entries",Integer.class); }
        public void close() throws Exception { context.close(); anchor.close(); }
    }
}
