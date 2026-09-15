/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.*;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class TransactionProxyValueTest {
    public interface Api { long setting(); void save(boolean fail); }
    public static class Service implements Api, InitializingBean, DisposableBean {
        static int built, initialized, destroyed;
        final JdbcTemplate jdbc;
        final long millis;
        public Service(JdbcTemplate jdbc, @Value("#{${cfg.seconds:5} * 1000L}") long millis) {
            this.jdbc=jdbc; this.millis=millis; built++;
        }
        @Override public long setting() { return millis; }
        @Override @Transactional public void save(boolean fail) {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new AssertionError("transaction missing");
            jdbc.update("insert into entries(amount) values (?)", millis);
            if (fail) throw new IllegalArgumentException("rollback");
        }
        @Override public void afterPropertiesSet() { initialized++; }
        @Override public void destroy() { destroyed++; }
    }
    public static class Holder {
        Api service;
        @Value("${cfg.label}") String label="old";
        Holder(Api service) { this.service=service; }
    }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement
    static class Jdk { }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement(proxyTargetClass=true)
    static class Cglib { }
    @Configuration(proxyBeanMethods=false)
    static class Infrastructure {
        @Bean org.h2.jdbcx.JdbcDataSource dataSource() {
            var ds=new org.h2.jdbcx.JdbcDataSource(); ds.setURL("jdbc:h2:mem:tx_values_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1"); return ds;
        }
        @Bean JdbcTemplate jdbc(org.h2.jdbcx.JdbcDataSource ds) { return new JdbcTemplate(ds); }
        @Bean org.springframework.transaction.PlatformTransactionManager transactionManager(org.h2.jdbcx.JdbcDataSource ds) {
            return new DataSourceTransactionManager(ds);
        }
    }
    @Configuration
    static class Factory {
        static boolean rejectEight;
        @Bean({"service","serviceAlias"}) Api service(JdbcTemplate jdbc, @Value("#{${cfg.seconds:5} * 1000L}") long millis) {
            if (rejectEight && millis==8000) throw new IllegalArgumentException("factory refuses eight");
            return new Service(jdbc,millis);
        }
    }
    static final class Fixture implements AutoCloseable {
        final AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();
        final JdbcTemplate jdbc;
        final Holder holder;
        Fixture(boolean cglib, boolean factory) {
            Service.built=Service.initialized=Service.destroyed=0; Factory.rejectEight=false;
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("original",Map.of("cfg.seconds","5","cfg.label","old")));
            context.register(cglib?Cglib.class:Jdk.class,Infrastructure.class);
            if (factory) context.register(Factory.class); else context.registerBean("service",Service.class);
            context.refresh();
            jdbc=context.getBean(JdbcTemplate.class); jdbc.execute("create table entries(amount bigint)");
            holder=new Holder(bean()); context.getBeanFactory().registerSingleton("holder",holder);
        }
        Api bean() { return context.getBean("service",Api.class); }
        PropertyChangeOutcome apply(String seconds) {
            return new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.seconds",seconds,"cfg.label","new"));
        }
        int rows() { return jdbc.queryForObject("select count(*) from entries",Integer.class); }
        @Override public void close() { context.close(); }
    }

    @ParameterizedTest @CsvSource({"false,false","true,false","false,true","true,true"})
    void settingsRecreateNativeTransactionProxyAndKeepCommitRollback(boolean cglib, boolean factory) {
        try (var f=new Fixture(cglib,factory)) {
            Api original=f.bean();
            assertEquals(!cglib,Proxy.isProxyClass(original.getClass()));
            assertTrue(original instanceof Advised);
            original.save(false); assertThrows(IllegalArgumentException.class,()->original.save(true)); assertEquals(1,f.rows());
            var result=f.apply("8");
            assertEquals(PropertyChangeOutcome.State.APPLIED,result.state(),result.findings().toString());
            Api current=f.bean(); assertNotSame(original,current); assertSame(current,f.holder.service);
            assertEquals(!cglib,Proxy.isProxyClass(current.getClass()));
            assertEquals(8000,current.setting()); assertEquals("new",f.holder.label);
            assertEquals(2,Service.built); assertEquals(2,Service.initialized); assertEquals(1,Service.destroyed);
            assertEquals(List.of("service"),result.rebuilt());
            if (factory) assertSame(current,f.context.getBean("serviceAlias"));
            f.holder.service.save(false); assertThrows(IllegalArgumentException.class,()->f.holder.service.save(true));
            assertEquals(2,f.rows()); assertEquals(13000L,f.jdbc.queryForObject("select sum(amount) from entries",Long.class));
            assertEquals(PropertyChangeOutcome.State.APPLIED,f.apply("9").state());
            assertEquals(9000,f.holder.service.setting()); assertEquals(3,Service.built); assertEquals(2,Service.destroyed);
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void badCandidateCannotChangeAnyLiveValueOrRunLifecycle(boolean factory) {
        try (var f=new Fixture(false,factory)) {
            Api original=f.bean();
            AtomicBoolean boundary=new AtomicBoolean(),accepted=new AtomicBoolean();
            var bad=new SpringPropertyRebinder(List.of(f.context)).apply(Map.of("cfg.seconds","1 / 0","cfg.label","bad"),
                    work->{boundary.set(true);work.run();},()->accepted.set(true));
            assertEquals(PropertyChangeOutcome.State.REJECTED,bad.state(),bad.findings().toString());
            assertFalse(boundary.get()); assertFalse(accepted.get());
            assertSame(original,f.bean()); assertSame(original,f.holder.service); assertEquals("old",f.holder.label);
            assertEquals("5",f.context.getEnvironment().getProperty("cfg.seconds"));
            assertEquals(1,Service.built); assertEquals(0,Service.destroyed); assertEquals(0,f.rows());
            var unsupported=f.apply("T(java.lang.System).nanoTime()");
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE,unsupported.state(),unsupported.findings().toString());
            assertSame(original,f.bean()); assertEquals(0,Service.destroyed);
            assertEquals(PropertyChangeOutcome.State.APPLIED,f.apply("8").state());
        }
    }
    @Test void checkOnlyAndUnrelatedKeysDoNotReplaceTheProxy() {
        try (var f=new Fixture(true,false)) {
            Api original=f.bean();
            var check=new PropertyChangeCheck().check(List.of(f.context),Map.of("cfg.seconds","8"));
            assertEquals(PropertyChangeOutcome.State.APPLIED,check.state(),check.findings().toString());
            assertEquals(1,Service.built); assertEquals(0,Service.destroyed); assertSame(original,f.bean());
            var result=new SpringPropertyRebinder(List.of(f.context)).apply(Map.of("cfg.label","new"));
            assertEquals(PropertyChangeOutcome.State.APPLIED,result.state()); assertTrue(result.rebuilt().isEmpty());
            assertSame(original,f.bean()); assertEquals("new",f.holder.label);
        }
    }
    @ParameterizedTest @ValueSource(strings={"extra-advice","frozen","nested","custom-source"})
    void unsupportedProxyPoliciesHoldTheCandidate(String shape) throws Exception {
        try (var f=new Fixture(false,true)) {
            Api original=f.bean(); Advised proxy=(Advised) original;
            Object target=proxy.getTargetSource().getTarget();
            switch(shape) {
                case "extra-advice" -> proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) call->call.proceed());
                case "frozen" -> {
                    Object handler=Proxy.getInvocationHandler(original);
                    var field=handler.getClass().getDeclaredField("advised"); field.setAccessible(true);
                    ((AdvisedSupport)field.get(handler)).setFrozen(true);
                }
                case "nested" -> {
                    var nested=new ProxyFactory(target); nested.addAdvisor(proxy.getAdvisors()[0]);
                    proxy.setTargetSource(new org.springframework.aop.target.SingletonTargetSource(nested.getProxy()));
                }
                case "custom-source" -> proxy.setTargetSource(new org.springframework.aop.TargetSource() {
                    public Class<?> getTargetClass() { return Service.class; }
                    public boolean isStatic() { return true; }
                    public Object getTarget() { return target; }
                    public void releaseTarget(Object bean) { }
                });
                default -> throw new AssertionError(shape);
            }
            var result=f.apply("8");
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE,result.state(),shape+result.findings());
            assertSame(original,f.bean()); assertEquals(1,Service.built); assertEquals(0,Service.destroyed);
            assertEquals("5",f.context.getEnvironment().getProperty("cfg.seconds")); assertEquals("old",f.holder.label);
        }
    }
    @Test void liveFactoryFailureIsPartialAndARecoveryLookupStillGetsTransactionAdvice() {
        try (var f=new Fixture(true,true)) {
            Factory.rejectEight=true;
            AtomicBoolean accepted=new AtomicBoolean();
            var result=new SpringPropertyRebinder(List.of(f.context)).apply(Map.of("cfg.seconds","8"),Runnable::run,()->accepted.set(true));
            assertEquals(PropertyChangeOutcome.State.PARTIAL,result.state(),result.findings().toString());
            assertFalse(accepted.get()); assertEquals(1,Service.destroyed);
            assertEquals("8",f.context.getEnvironment().getProperty("cfg.seconds"));
            assertFalse(f.context.getBeanFactory().containsSingleton("service"));
            assertEquals(PropertyChangeOutcome.State.APPLIED,f.apply("9").state());
            Api recovered=f.bean(); assertEquals(9000,recovered.setting());
            recovered.save(false); assertThrows(IllegalArgumentException.class,()->recovered.save(true)); assertEquals(1,f.rows());
        }
    }
}
