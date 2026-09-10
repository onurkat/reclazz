/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.util.Reflect;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.*;
import org.springframework.transaction.event.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.h2.jdbcx.JdbcDataSource;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AddedTransactionalEventListenerTest {
    public static class Handler {
        final List<String> calls=new ArrayList<>();
        public void before(String message) { calls.add("before:"+message); }
        private void after(String message) { calls.add("after:"+message); }
        public void rollback(String message) { calls.add("rollback:"+message); }
        public void complete(String message) { calls.add("complete:"+message); }
        public void ordinary(String message) { calls.add("ordinary:"+message); }
        public void fail(String message) { calls.add("fail:"+message); throw new IllegalStateException("listener refused"); }
        public String result(String message) { return message; }
        public static void staticMethod(String message) { }
        public <T> void generic(T message) { }
    }
    public static class Native {
        final List<String> calls=new ArrayList<>();
        @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT) @org.springframework.core.annotation.Order(10)
        public void before(String message) { calls.add("before:"+message); }
        @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT) @org.springframework.core.annotation.Order(20)
        public void after(String message) { calls.add("after:"+message); }
        @TransactionalEventListener(phase=TransactionPhase.AFTER_ROLLBACK) @org.springframework.core.annotation.Order(30)
        public void rollback(String message) { calls.add("rollback:"+message); }
        @TransactionalEventListener(phase=TransactionPhase.AFTER_COMPLETION) @org.springframework.core.annotation.Order(40)
        public void complete(String message) { calls.add("complete:"+message); }
    }
    public static class CustomFactory extends TransactionalEventListenerFactory { }
    public static class RefusingMulticaster extends SimpleApplicationEventMulticaster {
        boolean refuse;
        @Override public void removeApplicationListener(org.springframework.context.ApplicationListener<?> listener) {
            if(refuse && listener.getClass().isHidden()) throw new IllegalStateException("remove refused");
            super.removeApplicationListener(listener);
        }
    }
    @AfterEach void clear() { RestartLedger.clear(); }

    @Test void allPhasesMatchNativeSpringAndRealCommitRollbackRows() throws Exception {
        try(var scope=new Scope(true)) {
            Native nativeBean=new Native(); scope.context.getBeanFactory().registerSingleton("native",nativeBean);
            // Register reflected native methods through Spring's processor, independently of the adapter.
            Object processor=scope.context.getBean(EventListenerMethodProcessor.class);
            var process=Reflect.findMethod(processor.getClass(),"processBean",String.class,Class.class);
            process.invoke(processor,"native",Native.class);
            assertTrue(scope.reload(phases()));
            scope.context.publishEvent("outside"); assertTrue(scope.handler().calls.isEmpty());
            scope.tx.execute(status->{ scope.jdbc.update("insert into events values (1)"); scope.context.publishEvent("commit");
                assertTrue(scope.handler().calls.isEmpty()); assertTrue(nativeBean.calls.isEmpty()); return null; });
            assertEquals(List.of("before:commit","after:commit","complete:commit"),scope.handler().calls);
            scope.tx.execute(status->{ scope.jdbc.update("insert into events values (2)"); scope.context.publishEvent("rollback"); status.setRollbackOnly(); return null; });
            assertEquals(nativeBean.calls,scope.handler().calls); assertEquals(1,scope.rows());
            assertEquals(List.of("before:commit","after:commit","complete:commit","rollback:rollback","complete:rollback"),scope.handler().calls);
        }
    }

    @Test void fallbackConditionSavedNamesAndTypeFilteringUseSpring() throws Exception {
        try(var scope=new Scope(true)) {
            scope.context.getBeanFactory().registerSingleton("allowed","yes");
            byte[] bytes=one("after",TransactionPhase.AFTER_COMMIT,true,"#message == @allowed");
            assertTrue(scope.reload(bytes));
            scope.context.publishEvent(123); scope.context.publishEvent("no"); scope.context.publishEvent("yes");
            assertEquals(List.of("after:yes"),scope.handler().calls);
            scope.tx.execute(s->{scope.context.publishEvent("yes");return null;});
            assertEquals(List.of("after:yes","after:yes"),scope.handler().calls);
        }
    }

    @Test void ordinaryAddedListenersWorkAlongsideTheStandardTransactionalFactory() throws Exception {
        try(var scope=new Scope(true)) {
            ClassNode source=read(one("after",TransactionPhase.AFTER_COMMIT,false,""));
            method(source,"ordinary").visibleAnnotations=new ArrayList<>(List.of(new AnnotationNode(AddedEventListenerAdapter.EVENT)));
            assertTrue(scope.reload(write(source)));
            scope.tx.execute(s->{scope.context.publishEvent("inside");assertEquals(List.of("ordinary:inside"),scope.handler().calls);return null;});
            assertEquals(List.of("ordinary:inside","after:inside"),scope.handler().calls);
        }
    }

    @Test void replacingAPhaseWhileTransactionIsOpenCancelsItsOldQueuedCallback() throws Exception {
        try(var scope=new Scope(true)) {
            assertTrue(scope.reload(one("after",TransactionPhase.AFTER_COMMIT,false,"")));
            scope.tx.execute(s->{scope.context.publishEvent("old");
                assertTrue(scope.reload(one("after",TransactionPhase.BEFORE_COMMIT,false,"")));
                scope.context.publishEvent("new"); assertTrue(scope.handler().calls.isEmpty());return null;});
            assertEquals(List.of("after:new"),scope.handler().calls);
        }
    }

    @Test void removalSkipsPendingConditionEvaluationBeforeCommit() throws Exception {
        try(var scope=new Scope(true)) {
            assertTrue(scope.reload(one("after",TransactionPhase.BEFORE_COMMIT,false,"@missing.accept(#a0)")));
            byte[] removed=original();
            scope.tx.execute(s->{scope.jdbc.update("insert into events values (1)");scope.context.publishEvent("old");
                assertTrue(scope.reload(removed));return null;});
            assertEquals(1,scope.rows()); assertTrue(scope.handler().calls.isEmpty());
        }
    }

    @Test void failedMulticasterRemovalRetiresEveryBeanAndCanBeRetried() throws Exception {
        var multicaster=new RefusingMulticaster();
        try(var scope=new Scope(true,c->c.getBeanFactory().registerSingleton("applicationEventMulticaster",multicaster))) {
            Handler other=new Handler(); scope.context.getBeanFactory().registerSingleton("other",other);
            assertTrue(scope.reload(one("after",TransactionPhase.AFTER_COMMIT,false,"")));
            byte[] removed=original();
            scope.tx.execute(s->{scope.context.publishEvent("old");multicaster.refuse=true;scope.reload(removed);return null;});
            assertTrue(scope.handler().calls.isEmpty()); assertTrue(other.calls.isEmpty());
            multicaster.refuse=false; assertTrue(scope.reload(one("after",TransactionPhase.AFTER_COMMIT,false,"")));
            scope.tx.execute(s->{scope.context.publishEvent("new");return null;});
            assertEquals(List.of("after:new"),scope.handler().calls); assertEquals(List.of("after:new"),other.calls);
        }
    }

    @Test void beforeCommitExceptionRollsBackTheActualDatabase() throws Exception {
        try(var scope=new Scope(true)) {
            assertTrue(scope.reload(one("fail",TransactionPhase.BEFORE_COMMIT,false,"")));
            var failure=assertThrows(IllegalStateException.class,()->scope.tx.execute(s->{
                scope.jdbc.update("insert into events values (1)");scope.context.publishEvent("failure");return null;}));
            assertEquals("listener refused",failure.getMessage());assertEquals(0,scope.rows());
        }
    }

    @Test void callbackResolvesCurrentSingletonAndDoesNotRecreateAnAbsentOne() throws Exception {
        try(var scope=new Scope(true)) {
            assertTrue(scope.reload(one("after",TransactionPhase.AFTER_COMMIT,false,"")));
            Handler old=scope.handler(),next=new Handler();
            scope.tx.execute(s->{scope.context.publishEvent("next");scope.context.getDefaultListableBeanFactory().destroySingleton("handler");
                scope.context.getBeanFactory().registerSingleton("handler",next);return null;});
            assertTrue(old.calls.isEmpty());assertEquals(List.of("after:next"),next.calls);
            scope.tx.execute(s->{scope.context.publishEvent("absent");scope.context.getDefaultListableBeanFactory().destroySingleton("handler");return null;});
            assertNull(scope.context.getBeanFactory().getSingleton("handler"));assertEquals(1,next.calls.size());
        }
    }

    @Test void afterCommitFailureCannotUndoCommittedDataOrPreventOtherCompletionListeners() throws Exception {
        try(var scope=new Scope(true)) {
            ClassNode source=read(one("fail",TransactionPhase.AFTER_COMMIT,false,""));
            method(source,"complete").visibleAnnotations=new ArrayList<>(List.of(annotation(TransactionPhase.AFTER_COMPLETION,false,"")));
            assertTrue(scope.reload(write(source)));
            scope.tx.execute(s->{scope.jdbc.update("insert into events values (1)");scope.context.publishEvent("committed");return null;});
            assertEquals(1,scope.rows());
            assertEquals(List.of("complete:committed","fail:committed"),scope.handler().calls.stream().sorted().toList());
        }
    }

    @Test void closedContextSkipsQueuedCallbacksAndTheirConditions() throws Exception {
        try(var scope=new Scope(true)) {
            assertTrue(scope.reload(one("after",TransactionPhase.BEFORE_COMMIT,false,"@missing.accept(#a0)")));
            Handler old=scope.handler();
            scope.tx.execute(s->{scope.jdbc.update("insert into events values (1)");scope.context.publishEvent("old");scope.context.close();return null;});
            assertTrue(old.calls.isEmpty());assertEquals(1,scope.rows());
        }
    }

    @Test void missingOrCustomTransactionalFactoryIsNotBypassed() throws Exception {
        try(var scope=new Scope(false)) {
            scope.reload(one("after",TransactionPhase.AFTER_COMMIT,true,""));scope.context.publishEvent("no");
            assertTrue(scope.handler().calls.isEmpty());assertTrue(RestartLedger.digest().stream().anyMatch(s->s.contains("standard transactional")));
        }
        try(var scope=new Scope(true,c->c.registerBean("custom",CustomFactory.class))) {
            scope.reload(one("after",TransactionPhase.AFTER_COMMIT,true,""));scope.context.publishEvent("no");
            assertTrue(scope.handler().calls.isEmpty());assertTrue(RestartLedger.digest().stream().anyMatch(s->s.contains("custom event listener factories")));
        }
    }

    @Test void wrongFactoryOrderingIsRefusedInsteadOfUsingImmediateDelivery() throws Exception {
        try(var scope=new Scope(true)) {
            var processor=scope.context.getBean(EventListenerMethodProcessor.class);
            List<?> factories=(List<?>)Reflect.readField(processor,"eventListenerFactories");Collections.reverse(factories);
            scope.reload(one("after",TransactionPhase.AFTER_COMMIT,true,""));scope.context.publishEvent("no");
            assertTrue(scope.handler().calls.isEmpty());assertTrue(RestartLedger.digest().stream().anyMatch(s->s.contains("must precede")));
        }
    }

    @Test void unsupportedDeclarationsStayNamed() throws Exception {
        for(String name:List.of("result","staticMethod","generic")) {
            byte[] bytes=one(name,TransactionPhase.AFTER_COMMIT,false,"");
            var plan=AddedEventListenerAdapter.inspect(bytes,added(bytes));
            assertTrue(plan.methods().isEmpty(),name);assertFalse(plan.refused().isEmpty(),name);
        }
        ClassNode source=read(one("after",TransactionPhase.AFTER_COMMIT,false,""));
        method(source,"after").visibleAnnotations.add(new AnnotationNode("Lorg/springframework/transaction/annotation/Transactional;"));
        byte[] bytes=write(source);assertFalse(AddedEventListenerAdapter.inspect(bytes,added(bytes)).refused().isEmpty());
    }

    @Test void repeatedSavesDoNotGrowContextRegistryOrGlobalExpressionCache() throws Exception {
        try(var scope=new Scope(true)) {
            int registry=scope.context.getApplicationListeners().size();
            Object evaluator=Reflect.readField(scope.context.getBean(EventListenerMethodProcessor.class),"evaluator");
            Map<?,?> cache=(Map<?,?>)Reflect.readField(evaluator,"conditionCache"); int before=cache.size();
            for(int i=0;i<4;i++) {
                assertTrue(scope.reload(one("after",TransactionPhase.AFTER_COMMIT,false,"#message == 'yes'")));
                scope.tx.execute(s->{scope.context.publishEvent("yes");return null;});
            }
            assertEquals(4,scope.handler().calls.size());assertEquals(registry,scope.context.getApplicationListeners().size());assertEquals(before,cache.size());
        }
    }

    private static final class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();
        final TransactionTemplate tx; final JdbcTemplate jdbc; final SpringEventReloader reloader;
        Scope(boolean transactional) throws Exception { this(transactional,c->{ }); }
        Scope(boolean transactional,java.util.function.Consumer<AnnotationConfigApplicationContext> setup) throws Exception {
            LookupCapture.store(Handler.class,MethodHandles.privateLookupIn(Handler.class,MethodHandles.lookup()));
            context.getBeanFactory().registerSingleton("handler",new Handler());
            if(transactional) context.registerBean("transactionalFactory",TransactionalEventListenerFactory.class);
            setup.accept(context);context.refresh();
            var data=new JdbcDataSource();data.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
            jdbc=new JdbcTemplate(data);jdbc.execute("create table events (id int)");tx=new TransactionTemplate(new DataSourceTransactionManager(data));
            PlatformContext platform=(PlatformContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                    (p,m,a)->m.getName().equals("getAllApplicationContexts")?List.of(context):null);
            reloader=new SpringEventReloader(platform);
        }
        Handler handler() { return context.getBean("handler",Handler.class); }
        int rows() { return jdbc.queryForObject("select count(*) from events",Integer.class); }
        boolean reload(byte[] bytes) { return reloader.reloadEventListeners(Handler.class,added(bytes),bytes); }
        public void close() { context.close();jdbc.execute("shutdown"); }
    }
    private static byte[] phases() throws Exception {
        ClassNode source=read(original());int order=0;
        for(String name:List.of("before","after","rollback","complete")) {
            TransactionPhase phase=switch(name) {case "before"->TransactionPhase.BEFORE_COMMIT;case "after"->TransactionPhase.AFTER_COMMIT;
                case "rollback"->TransactionPhase.AFTER_ROLLBACK;default->TransactionPhase.AFTER_COMPLETION;};
            var method=method(source,name);method.visibleAnnotations=new ArrayList<>(List.of(annotation(phase,false,"")));
            var sort=new AnnotationNode("Lorg/springframework/core/annotation/Order;");sort.values=new ArrayList<>(List.of("value",++order*10));method.visibleAnnotations.add(sort);
        }
        return write(source);
    }
    private static byte[] one(String name,TransactionPhase phase,boolean fallback,String condition) {
        try { ClassNode source=read(original());method(source,name).visibleAnnotations=new ArrayList<>(List.of(annotation(phase,fallback,condition)));return write(source); }
        catch(Exception failure) { throw new IllegalStateException(failure); }
    }
    private static AnnotationNode annotation(TransactionPhase phase,boolean fallback,String condition) {
        var annotation=new AnnotationNode(AddedEventListenerAdapter.TRANSACTIONAL);
        annotation.values=new ArrayList<>(List.of("phase",new String[]{"Lorg/springframework/transaction/event/TransactionPhase;",phase.name()},
                "fallbackExecution",fallback,"condition",condition));return annotation;
    }
    private static Set<String> added(byte[] bytes) {
        Set<String> keys=new HashSet<>();for(var m:read(bytes).methods)keys.add(m.name+":"+m.desc);return keys;
    }
    private static byte[] original() throws Exception {
        try(var in=Handler.class.getResourceAsStream("/"+Handler.class.getName().replace('.','/')+".class")) {return Objects.requireNonNull(in).readAllBytes();}
    }
    private static ClassNode read(byte[] bytes) {var node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
    private static MethodNode method(ClassNode source,String name) {return source.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static byte[] write(ClassNode node) {var writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();}
}
