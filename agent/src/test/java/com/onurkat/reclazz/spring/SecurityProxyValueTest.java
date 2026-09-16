/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.*;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.onurkat.reclazz.spring.TransactionProxyValueTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Same native recreation contract runs against both security generations. */
class SecurityProxyValueTest {
    public static class SecuredService extends Service {
        int reads;
        public SecuredService(JdbcTemplate jdbc,@Value("#{${cfg.seconds:5} * 1000L}") long millis) { super(jdbc,millis); }
        @Override @PreAuthorize("hasAuthority('READ')") @PostAuthorize("returnObject > 0")
        public long setting() { reads++; return super.setting(); }
        @Override @PreAuthorize("hasAuthority('WRITE')") @Transactional
        public void save(boolean fail) { super.save(fail); }
    }
    @Configuration(proxyBeanMethods=false) @EnableGlobalMethodSecurity(prePostEnabled=true)
    static class SecurityJdk { }
    @Configuration(proxyBeanMethods=false) @EnableGlobalMethodSecurity(prePostEnabled=true,proxyTargetClass=true)
    static class SecurityCglib { }
    @Configuration
    static class SecuredFactory {
        static boolean rejectEight;
        @Bean({"service","serviceAlias"}) Api service(JdbcTemplate jdbc,@Value("#{${cfg.seconds:5} * 1000L}") long millis) {
            if(rejectEight && millis==8000) throw new IllegalArgumentException("factory refuses eight");
            return new SecuredService(jdbc,millis);
        }
    }
    Class<?> securityConfiguration(boolean cglib) { return cglib?SecurityCglib.class:SecurityJdk.class; }
    void customizeSecurity(Advised proxy) throws Exception {
        Object advisor=Arrays.stream(proxy.getAdvisors()).filter(SpringSecurityAdvice::isAdvisor).findFirst().orElseThrow();
        var interceptor=(org.springframework.security.access.intercept.aopalliance.MethodSecurityInterceptor)SpringSecurityAdvice.interceptor(advisor);
        interceptor.setSecurityMetadataSource(new org.springframework.security.access.method.AbstractMethodSecurityMetadataSource() {
            public Collection<org.springframework.security.access.ConfigAttribute> getAttributes(java.lang.reflect.Method m,Class<?> t) { return List.of(); }
            public Collection<org.springframework.security.access.ConfigAttribute> getAllConfigAttributes() { return List.of(); }
        });
    }
    public static class FilteredService extends SecuredService {
        public FilteredService(JdbcTemplate jdbc,@Value("#{${cfg.seconds:5} * 1000L}") long millis) { super(jdbc,millis); }
        @PreFilter("false") public List<String> filter(List<String> values) { return values; }
    }
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @PreAuthorize("hasAuthority('READ')") @PostAuthorize("returnObject > 0")
    public @interface ReadSetting { }
    public static class MarkerService extends SecuredService {
        public MarkerService(JdbcTemplate jdbc,@Value("#{${cfg.seconds:5} * 1000L}") long millis) { super(jdbc,millis); }
        @Override @ReadSetting public long setting() { return super.setting(); }
    }
    void makeSecurityCold(Advised proxy,AtomicBoolean initialized) throws Exception {
        Object advisor=Arrays.stream(proxy.getAdvisors()).filter(SpringSecurityAdvice::isAdvisor).findFirst().orElseThrow();
        com.onurkat.reclazz.util.Reflect.findField(advisor.getClass(),"interceptor").set(advisor,null);
        ((org.springframework.beans.factory.BeanFactoryAware)advisor).setBeanFactory(
                (org.springframework.beans.factory.BeanFactory)Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{org.springframework.beans.factory.BeanFactory.class},(p,m,a)->{
                            initialized.set(true); throw new AssertionError("candidate initialized security advice");
                        }));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); com.onurkat.reclazz.ui.RestartLedger.clear(); }
    static void login(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("alice","unused",AuthorityUtils.createAuthorityList(roles)));
    }
    final class Fixture implements AutoCloseable {
        final AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();
        final JdbcTemplate jdbc; final Holder holder;
        Fixture(boolean cglib,boolean factory,boolean transactions) {
            Service.built=Service.initialized=Service.destroyed=0; SecuredFactory.rejectEight=false;
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("original",Map.of("cfg.seconds","5","cfg.label","old")));
            context.register(securityConfiguration(cglib),Infrastructure.class);
            if(transactions) context.register(cglib?Cglib.class:Jdk.class);
            if(factory) context.register(SecuredFactory.class); else context.registerBean("service",SecuredService.class);
            context.refresh(); jdbc=context.getBean(JdbcTemplate.class); jdbc.execute("create table entries(amount bigint)");
            holder=new Holder(bean()); context.getBeanFactory().registerSingleton("holder",holder);
        }
        Api bean() { return context.getBean("service",Api.class); }
        SecuredService target() throws Exception { return (SecuredService)((Advised)bean()).getTargetSource().getTarget(); }
        PropertyChangeOutcome apply(String seconds) { return new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.seconds",seconds,"cfg.label","new")); }
        int rows() { return jdbc.queryForObject("select count(*) from entries",Integer.class); }
        public void close() { context.close(); }
    }
    static void deniedAndAllowed(Fixture f,long value,boolean transactions) throws Exception {
        int before=f.target().reads;
        SecurityContextHolder.clearContext(); assertThrows(AuthenticationCredentialsNotFoundException.class,()->f.holder.service.setting());
        login("NONE"); assertThrows(AccessDeniedException.class,()->f.holder.service.setting());
        assertEquals(before,f.target().reads);
        if(transactions) {
            assertThrows(AccessDeniedException.class,()->f.holder.service.save(false));
            login("READ","WRITE"); int rows=f.rows();
            f.holder.service.save(false); assertThrows(IllegalArgumentException.class,()->f.holder.service.save(true));
            assertEquals(rows+1,f.rows());
        }
        login("READ"); assertEquals(value,f.holder.service.setting()); assertEquals(before+1,f.target().reads);
    }
    @Test void nativeControlUsesAuthorizationAndTransactionRollback() throws Exception {
        try(var f=new Fixture(false,true,true)) { deniedAndAllowed(f,5000,true); assertEquals(1,f.rows()); }
    }
    @ParameterizedTest @CsvSource({"false,false,false","false,true,false","true,false,false","true,true,false",
            "false,false,true","false,true,true","true,false,true","true,true,true"})
    void nativeSecuritySurvivesValueRecreation(boolean cglib,boolean factory,boolean transactions) throws Exception {
        try(var f=new Fixture(cglib,factory,transactions)) {
            Api original=f.bean(); deniedAndAllowed(f,5000,transactions);
            var result=f.apply("8"); assertEquals(PropertyChangeOutcome.State.APPLIED,result.state(),result.findings().toString());
            Api current=f.bean(); assertNotSame(original,current); assertSame(current,f.holder.service);
            assertEquals(!cglib,Proxy.isProxyClass(current.getClass()));
            assertEquals(2,Service.built); assertEquals(2,Service.initialized); assertEquals(1,Service.destroyed);
            assertEquals("new",f.holder.label); assertEquals(List.of("service"),result.rebuilt());
            if(factory) assertSame(current,f.context.getBean("serviceAlias"));
            deniedAndAllowed(f,8000,transactions);
            assertEquals(PropertyChangeOutcome.State.APPLIED,f.apply("9").state()); deniedAndAllowed(f,9000,transactions);
            assertEquals(3,Service.built); assertEquals(2,Service.destroyed);
            // Native post-authorization still executes after the newly created body.
            assertEquals(PropertyChangeOutcome.State.APPLIED,f.apply("-1").state()); login("READ");
            assertThrows(AccessDeniedException.class,()->f.bean().setting()); assertEquals(1,f.target().reads);
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void invalidCandidateHoldsEnvironmentLifecycleHolderAndAdvice(boolean factory) throws Exception {
        try(var f=new Fixture(false,factory,true)) {
            Api original=f.bean(); deniedAndAllowed(f,5000,true);
            AtomicBoolean boundary=new AtomicBoolean(),accepted=new AtomicBoolean();
            var bad=new SpringPropertyRebinder(List.of(f.context)).apply(Map.of("cfg.seconds","1 / 0","cfg.label","bad"),
                    work->{boundary.set(true);work.run();},()->accepted.set(true));
            assertEquals(PropertyChangeOutcome.State.REJECTED,bad.state(),bad.findings().toString());
            assertFalse(boundary.get()); assertFalse(accepted.get()); assertSame(original,f.bean()); assertSame(original,f.holder.service);
            assertEquals("5",f.context.getEnvironment().getProperty("cfg.seconds")); assertEquals("old",f.holder.label);
            assertEquals(1,Service.built); assertEquals(0,Service.destroyed); deniedAndAllowed(f,5000,true);
            assertEquals(PropertyChangeOutcome.State.APPLIED,f.apply("8").state()); deniedAndAllowed(f,8000,true);
        }
    }
    @Test void checkOnlyAndUnrelatedKeysHaveNoCreationOrBusinessSideEffects() throws Exception {
        try(var f=new Fixture(true,true,true)) {
            Api original=f.bean(); deniedAndAllowed(f,5000,true); int reads=f.target().reads,rows=f.rows();
            var check=new PropertyChangeCheck().check(List.of(f.context),Map.of("cfg.seconds","8"));
            assertEquals(PropertyChangeOutcome.State.APPLIED,check.state(),check.findings().toString());
            assertSame(original,f.bean()); assertEquals(1,Service.built); assertEquals(0,Service.destroyed);
            assertEquals(reads,f.target().reads); assertEquals(rows,f.rows());
            var result=new SpringPropertyRebinder(List.of(f.context)).apply(Map.of("cfg.label","new"));
            assertEquals(PropertyChangeOutcome.State.APPLIED,result.state()); assertTrue(result.rebuilt().isEmpty());
            assertSame(original,f.bean()); assertEquals("new",f.holder.label);
        }
    }
    @Test void extraAdviceHoldsEveryValueWithoutLifecycle() throws Exception {
        try(var f=new Fixture(false,true,true)) {
            Api original=f.bean(); deniedAndAllowed(f,5000,true);
            ((Advised)original).addAdvice((org.aopalliance.intercept.MethodInterceptor) call->call.proceed());
            var result=f.apply("8"); assertEquals(PropertyChangeOutcome.State.UNCHECKABLE,result.state());
            assertSame(original,f.bean()); assertEquals(1,Service.built); assertEquals(0,Service.destroyed);
            assertEquals("5",f.context.getEnvironment().getProperty("cfg.seconds")); assertEquals("old",f.holder.label);
        }
    }
    @Test void uninitializedSecurityIsHeldWithoutCallingItsFactory() throws Exception {
        try(var f=new Fixture(false,true,true)) {
            Api original=f.bean(); deniedAndAllowed(f,5000,true);
            AtomicBoolean initialized=new AtomicBoolean(); makeSecurityCold((Advised)original,initialized);
            var result=f.apply("8"); assertEquals(PropertyChangeOutcome.State.UNCHECKABLE,result.state());
            assertFalse(initialized.get()); assertSame(original,f.bean()); assertEquals(1,Service.built); assertEquals(0,Service.destroyed);
            assertEquals("5",f.context.getEnvironment().getProperty("cfg.seconds")); assertEquals("old",f.holder.label);
        }
    }
    @Test void customSecurityCannotTurnCandidateCheckingIntoAuthorizationBypass() throws Exception {
        try(var f=new Fixture(false,true,true)) {
            Api original=f.bean(); deniedAndAllowed(f,5000,true); customizeSecurity((Advised)original);
            var result=f.apply("8"); assertEquals(PropertyChangeOutcome.State.UNCHECKABLE,result.state());
            assertSame(original,f.bean()); assertEquals(0,Service.destroyed); assertEquals("5",f.context.getEnvironment().getProperty("cfg.seconds"));
        }
    }
    @Test void filteringMetadataHoldsTheCandidateEvenOnStandardInfrastructure() throws Exception {
        try(var f=new Fixture(false,false,false)) {
            f.context.registerBean("filtered",FilteredService.class); f.context.getBean("filtered");
            var result=f.apply("8"); assertEquals(PropertyChangeOutcome.State.UNCHECKABLE,result.state());
            assertEquals(0,Service.destroyed); assertEquals("5",f.context.getEnvironment().getProperty("cfg.seconds"));
        }
    }
    @Test void fixedMarkersProtectTheRecreatedTarget() throws Exception {
        try(var f=new Fixture(true,false,false)) {
            f.context.registerBean("marked",MarkerService.class);
            Api original=f.context.getBean("marked",Api.class);
            login("NONE"); assertThrows(AccessDeniedException.class,original::setting);
            login("READ"); assertEquals(5000,original.setting());
            var result=f.apply("8"); assertEquals(PropertyChangeOutcome.State.APPLIED,result.state(),result.findings().toString());
            Api current=f.context.getBean("marked",Api.class); assertNotSame(original,current);
            login("NONE"); assertThrows(AccessDeniedException.class,current::setting);
            login("READ"); assertEquals(8000,current.setting());
        }
    }
    @Test void removingSecurityCannotFallBackToTransactionOnlyEligibility() throws Exception {
        try(var f=new Fixture(false,true,true)) {
            Api original=f.bean(); deniedAndAllowed(f,5000,true); Advised proxy=(Advised)original;
            for(var advisor:proxy.getAdvisors()) if(SpringSecurityAdvice.isAdvisor(advisor)||SpringModernSecurityAdvice.isAdvisor(advisor)) proxy.removeAdvisor(advisor);
            var result=f.apply("8"); assertEquals(PropertyChangeOutcome.State.UNCHECKABLE,result.state());
            assertSame(original,f.bean()); assertEquals(0,Service.destroyed); assertEquals("5",f.context.getEnvironment().getProperty("cfg.seconds"));
        }
    }
    @Test void liveFactoryFailureStaysPartialAndRecoveryKeepsAuthorization() throws Exception {
        try(var f=new Fixture(true,true,true)) {
            deniedAndAllowed(f,5000,true); SecuredFactory.rejectEight=true;
            var result=f.apply("8"); assertEquals(PropertyChangeOutcome.State.PARTIAL,result.state(),result.findings().toString());
            assertEquals(1,Service.destroyed); assertEquals("8",f.context.getEnvironment().getProperty("cfg.seconds"));
            assertFalse(f.context.getBeanFactory().containsSingleton("service"));
            SecuredFactory.rejectEight=false; Api recovered=f.bean();
            login("NONE"); assertThrows(AccessDeniedException.class,recovered::setting);
            login("READ","WRITE"); assertEquals(8000,recovered.setting()); recovered.save(false);
            assertThrows(IllegalArgumentException.class,()->recovered.save(true)); assertEquals(2,f.rows());
        }
    }
}
