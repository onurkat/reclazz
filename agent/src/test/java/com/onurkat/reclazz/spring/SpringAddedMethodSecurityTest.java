/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.AddedOperationBridge;
import com.onurkat.reclazz.bootstrap.InjectedNames;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.*;
import org.springframework.security.access.intercept.aopalliance.MethodSecurityInterceptor;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.cache.annotation.*;
import org.springframework.transaction.annotation.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpringAddedMethodSecurityTest {
    public static class Owner {
        int calls;
        @PreAuthorize("hasAuthority('WRITE')") public String original(String name) { return name; }
        public int calls() { return calls; }
    }
    public static class Before {
        @PreAuthorize("hasAuthority('WRITE') and #name == authentication.name and this.calls() >= 0")
        public String work(String name) { return null; }
    }
    public static class Changed {
        @PreAuthorize("hasAuthority('ADMIN')") public String work(String name) { return null; }
    }
    public static class After {
        @PostAuthorize("returnObject == authentication.name") public String work(String name) { return null; }
    }
    @PreAuthorize("hasAuthority('ADMIN')") public static class ClassPolicy {
        @PreAuthorize("hasAuthority('WRITE')") public String work(String name) { return null; }
    }
    @PreAuthorize("hasAuthority('ADMIN')") public static class ClassDefault { public String work(String name) { return null; } }
    public static class Plain { public String work(String name) { return null; } }
    public static class Empty { }
    public static class Async {
        @org.springframework.scheduling.annotation.Async @PreAuthorize("denyAll()")
        public String work(String name) { return null; }
    }
    public static class Callback {
        @org.springframework.context.event.EventListener @PreAuthorize("denyAll()")
        public String work(String name) { return null; }
    }
    public static class FutureResult {
        @PreAuthorize("denyAll()") public java.util.concurrent.CompletableFuture<String> work(String name) { return null; }
    }
    public static class Filter {
        @PreFilter("false") public String work(String name) { return null; }
    }
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @PreAuthorize("denyAll()") public @interface Restricted { }
    public static class Composed { @Restricted public String work(String name) { return null; } }
    public static class Combined {
        @PreAuthorize("hasAuthority('WRITE')") @Transactional(rollbackFor=Exception.class)
        @Cacheable(cacheNames="secured", key="#p0") public String work(String name) { return null; }
    }
    @Configuration(proxyBeanMethods=false)
    @EnableGlobalMethodSecurity(prePostEnabled=true, proxyTargetClass=true, order=0)
    static class Config { @Bean Owner owner() { return new Owner(); } }
    @Configuration(proxyBeanMethods=false)
    @EnableGlobalMethodSecurity(prePostEnabled=true, proxyTargetClass=true)
    static class SecurityOnly { }
    @Configuration(proxyBeanMethods=false)
    @EnableGlobalMethodSecurity(securedEnabled=true, proxyTargetClass=true)
    static class Disabled { @Bean Owner owner() { return new Owner(); } }
    @Configuration(proxyBeanMethods=false)
    @EnableCaching(proxyTargetClass=true, order=2) @EnableTransactionManagement(proxyTargetClass=true, order=1)
    static class Operations {
        @Bean org.h2.jdbcx.JdbcDataSource dataSource() {
            var ds=new org.h2.jdbcx.JdbcDataSource(); ds.setURL("jdbc:h2:mem:security_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1"); return ds;
        }
        @Bean org.springframework.jdbc.core.JdbcTemplate jdbc(org.h2.jdbcx.JdbcDataSource ds) { return new org.springframework.jdbc.core.JdbcTemplate(ds); }
        @Bean org.springframework.transaction.PlatformTransactionManager transactionManager(org.h2.jdbcx.JdbcDataSource ds) {
            return new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);
        }
        @Bean org.springframework.cache.CacheManager cacheManager() { return new org.springframework.cache.concurrent.ConcurrentMapCacheManager("secured"); }
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); com.onurkat.reclazz.ui.RestartLedger.clear(); }
    static void login(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("alice", "unused", AuthorityUtils.createAuthorityList(authority)));
    }
    @Test void nativeInterceptorAcceptsHiddenMethodWithActualTargetAndArguments() throws Throwable {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            Owner bean=context.getBean(Owner.class);
            Owner target=(Owner)((Advised)bean).getTargetSource().getTarget();
            MethodSecurityInterceptor interceptor=context.getBean(MethodSecurityInterceptor.class);
            // A native probe independent of Reclazz operation activation/routing.
            Method method=hidden(Before.class).getDeclaredMethod("work",String.class);
            var invocation=new org.aopalliance.intercept.MethodInvocation() {
                public Method getMethod() { return method; }
                public Object[] getArguments() { return new Object[]{"alice"}; }
                public Object getThis() { return target; }
                public AccessibleObject getStaticPart() { return method; }
                public Object proceed() { return work(target,"alice"); }
            };
            assertThrows(AuthenticationCredentialsNotFoundException.class,()->interceptor.invoke(invocation));
            login("READ"); assertThrows(AccessDeniedException.class,()->interceptor.invoke(invocation));
            assertEquals(0,target.calls);
            login("WRITE"); assertEquals("alice",interceptor.invoke(invocation)); assertEquals(1,target.calls);
            System.out.println("NATIVE_SECURITY="+interceptor.getClass().getName()+",source="+interceptor.getSecurityMetadataSource().getClass().getName());
        }
    }
    @Test void addedPreAuthorizationDeniesBeforeBodyOnHeldProxyAndTarget() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            scope.publish(Before.class);
            assertThrows(AuthenticationCredentialsNotFoundException.class,()->scope.invoke("alice"));
            login("READ"); assertThrows(AccessDeniedException.class,()->scope.invoke("alice"));
            login("WRITE"); assertThrows(AccessDeniedException.class,()->scope.invoke("bob"));
            assertEquals(0,scope.target.calls);
            assertEquals("alice",scope.invoke("alice"));
            assertEquals("alice",scope.site.dynamicInvoker().invokeWithArguments(scope.target,"alice"));
            assertEquals(2,scope.target.calls);
            assertEquals("alice",scope.bean.original("alice"));
        }
    }
    @Test void plainRegisteredSingletonCanGainItsFirstProtectedOperation() throws Throwable {
        try(var scope=new Scope(SecurityOnly.class)) {
            assertFalse(scope.bean instanceof Advised); scope.publish(Before.class);
            login("READ"); assertThrows(AccessDeniedException.class,()->scope.invoke("alice"));
            login("WRITE"); assertEquals("alice",scope.invoke("alice")); assertEquals(1,scope.target.calls);
        }
    }
    @Test void absentAutoProxyCreatorNeverPermitsAnnotatedBody() throws Throwable {
        try(var context=new org.springframework.context.support.GenericApplicationContext()) {
            context.refresh(); Owner owner=new Owner(); context.getBeanFactory().registerSingleton("owner",owner);
            SpringAddedOperations.publish(Owner.class,bytes(Before.class),MethodHandles.privateLookupIn(Owner.class,MethodHandles.lookup()),List.of(context));
            var site=site("work",null); login("WRITE");
            assertThrows(IllegalStateException.class,()->site.dynamicInvoker().invokeWithArguments(owner,"alice"));
            assertEquals(0,owner.calls);
        }
    }
    @Test void customExpressionHandlerRefusesBeforeBody() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            Object voter=scope.context.getBean(MethodSecurityInterceptor.class).getAccessDecisionManager();
            var voters=((org.springframework.security.access.vote.AffirmativeBased)voter).getDecisionVoters();
            var field=voters.get(0).getClass().getDeclaredField("preAdvice"); field.setAccessible(true);
            var advice=(org.springframework.security.access.expression.method.ExpressionBasedPreInvocationAdvice)field.get(voters.get(0));
            advice.setExpressionHandler(new org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler() { });
            scope.publish(Before.class); login("WRITE");
            assertThrows(IllegalStateException.class,()->scope.invoke("alice")); assertEquals(0,scope.target.calls);
        }
    }
    @Test void savedPolicyAnnotationRemovalAndMethodRemovalFollowHeldCallSite() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            login("WRITE"); scope.publish(Before.class); assertEquals("alice",scope.invoke("alice"));
            scope.publish(Changed.class); assertThrows(AccessDeniedException.class,()->scope.invoke("alice"));
            login("ADMIN"); assertEquals("alice",scope.invoke("alice"));
            scope.publish(Plain.class); login("READ"); assertEquals("public",scope.invoke("public"));
            scope.publish(Empty.class); assertTrue(assertThrows(IllegalStateException.class,()->scope.invoke("alice")).getMessage().contains("removed"));
            scope.publish(Before.class); assertThrows(AccessDeniedException.class,()->scope.invoke("alice"));
            login("WRITE"); assertEquals("alice",scope.invoke("alice")); assertEquals(4,scope.target.calls);
        }
    }
    @Test void postAuthorizationChecksReturnValueAfterBody() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            scope.publish(After.class); login("READ");
            assertEquals("alice",scope.invoke("alice"));
            assertThrows(AccessDeniedException.class,()->scope.invoke("bob")); assertEquals(2,scope.target.calls);
        }
    }
    @Test void nativeClassPolicyAndMethodPrecedence() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            login("WRITE"); scope.publish(ClassDefault.class); assertThrows(AccessDeniedException.class,()->scope.invoke("alice"));
            scope.publish(ClassPolicy.class); assertEquals("alice",scope.invoke("alice"));
            assertEquals(1,scope.target.calls);
        }
    }
    @Test void securityPrecedesCachedResultsAndTransactionsUseNativeCommitRollback() throws Throwable {
        try(var scope=new Scope(Config.class,Operations.class)) {
            var jdbc=scope.context.getBean(org.springframework.jdbc.core.JdbcTemplate.class); jdbc.execute("create table entries(name varchar)");
            scope.publish(Combined.class);
            scope.site=site("write",scope.context);
            login("WRITE"); assertEquals("alice",scope.invoke("alice")); assertEquals("alice",scope.invoke("alice"));
            login("READ"); assertThrows(AccessDeniedException.class,()->scope.invoke("alice"));
            assertThrows(AccessDeniedException.class,()->scope.invoke("bob"));
            login("WRITE"); assertEquals("rollback",assertThrows(Exception.class,()->scope.invoke("fail")).getMessage());
            assertEquals(1,jdbc.queryForObject("select count(*) from entries",Integer.class)); assertEquals(2,scope.target.calls);
        }
    }
    @Test void missingAndDisabledInfrastructureRefuseBeforeBody() throws Throwable {
        for(Class<?> config:new Class<?>[]{Disabled.class,Operations.class}) try(var scope=new Scope(config)) {
            scope.publish(Before.class); login("WRITE");
            assertThrows(IllegalStateException.class,()->scope.invoke("alice")); assertEquals(0,scope.target.calls);
        }
    }
    @Test void unsupportedAsyncCallbackFilteringAndComposedSecurityRefuseBeforeBody() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            login("WRITE");
            for(Class<?> metadata:List.of(Async.class,Callback.class,Filter.class,Composed.class)) {
                scope.publish(metadata); assertThrows(IllegalStateException.class,()->scope.invoke("alice"),metadata.getName());
                assertEquals(0,scope.target.calls);
            }
        }
    }
    @Test void unknownReceiverIsRefusedBeforeBody() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            scope.publish(Before.class); login("WRITE"); Owner stranger=new Owner();
            assertThrows(IllegalStateException.class,()->scope.site.dynamicInvoker().invokeWithArguments(stranger,"alice"));
            assertEquals(0,stranger.calls);
        }
    }
    @Test void customMetadataSourceCannotTurnAnnotatedOperationPublic() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            scope.context.getBean(MethodSecurityInterceptor.class).setSecurityMetadataSource(new org.springframework.security.access.method.AbstractMethodSecurityMetadataSource() {
                public Collection<org.springframework.security.access.ConfigAttribute> getAttributes(Method m,Class<?> t) { return List.of(); }
                public Collection<org.springframework.security.access.ConfigAttribute> getAllConfigAttributes() { return List.of(); }
            });
            scope.publish(Before.class); login("WRITE");
            assertThrows(IllegalStateException.class,()->scope.invoke("alice")); assertEquals(0,scope.target.calls);
        }
    }
    @Test void customDecisionManagerIsNotSilentlyTreatedAsStandardSecurity() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            scope.context.getBean(MethodSecurityInterceptor.class).setAccessDecisionManager(new org.springframework.security.access.AccessDecisionManager() {
                public void decide(org.springframework.security.core.Authentication a,Object o,Collection<org.springframework.security.access.ConfigAttribute> c) { }
                public boolean supports(org.springframework.security.access.ConfigAttribute c) { return true; }
                public boolean supports(Class<?> c) { return true; }
            });
            scope.publish(Before.class); login("WRITE");
            assertThrows(IllegalStateException.class,()->scope.invoke("alice")); assertEquals(0,scope.target.calls);
        }
    }
    @PreAuthorize("denyAll()") public static class ClassCallbacks {
        @org.springframework.context.event.EventListener private void event(String value) { }
        @org.springframework.scheduling.annotation.Scheduled(fixedRate=10) private void scheduled() { }
    }
    @Restricted public static class ComposedClassCallbacks {
        @org.springframework.context.event.EventListener private void event(String value) { }
        @org.springframework.scheduling.annotation.Scheduled(fixedRate=10) private void scheduled() { }
    }
    @Test void privateCallbacksCannotBypassDirectOrComposedClassSecurity() throws Exception {
        for(Class<?> metadata:List.of(ClassCallbacks.class,ComposedClassCallbacks.class)) {
            var added=Set.of("event:(Ljava/lang/String;)V","scheduled:()V");
            var events=AddedEventListenerAdapter.inspect(bytes(metadata),added);
            var tasks=AddedScheduledAdapter.inspect(bytes(metadata),added);
            assertTrue(events.methods().isEmpty()); assertEquals(1,events.refused().size());
            assertTrue(tasks.methods().isEmpty()); assertEquals(1,tasks.refused().size());
        }
    }
    @Test void unsupportedSecurityAloneActivatesARefusalWithoutPreviousOperations() throws Exception {
        for(Class<?> metadata:List.of(Filter.class,Composed.class,Async.class,Callback.class,FutureResult.class)) {
            var plan=AddedOperationMetadata.create(Owner.class,bytes(metadata),MethodHandles.privateLookupIn(Owner.class,MethodHandles.lookup()),false);
            assertTrue(plan.operations(),metadata.getName()); assertEquals(1,plan.entries().size());
            assertTrue(plan.entries().get(0).security()); assertNotNull(plan.entries().get(0).reason());
        }
    }
    @Test void retiredMetadataCacheEntriesAreRemovedWithoutClearingOriginalMethod() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            login("WRITE"); scope.bean.original("alice"); scope.publish(Before.class); scope.invoke("alice");
            Object source=scope.context.getBean(MethodSecurityInterceptor.class).getSecurityMetadataSource();
            Field f=source.getClass().getDeclaredField("attributeCache"); f.setAccessible(true); Map<?,?> cache=(Map<?,?>)f.get(source);
            var before=new HashSet<>(cache.keySet());
            scope.publish(Changed.class);
            assertTrue(cache.size()<before.size(),"superseded hidden Method retained");
            assertTrue(cache.keySet().stream().anyMatch(k->k.toString().contains("original")),"unrelated native metadata removed");
        }
    }
    static String work(Owner target,String name) { target.calls++; return name; }
    static String write(AnnotationConfigApplicationContext context,Owner target,String name) throws Exception {
        target.calls++; assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        context.getBean(org.springframework.jdbc.core.JdbcTemplate.class).update("insert into entries values (?)",name);
        if(name.equals("fail"))throw new Exception("rollback"); return name;
    }
    static byte[] bytes(Class<?> metadata) throws Exception {
        byte[] bytes; try(var stream=metadata.getResourceAsStream("SpringAddedMethodSecurityTest$"+metadata.getSimpleName()+".class")) { bytes=stream.readAllBytes(); }
        var node=new ClassNode(); new ClassReader(bytes).accept(node,0); node.name=org.objectweb.asm.Type.getInternalName(Owner.class);
        node.methods.stream().filter(m->m.name.equals("work")).forEach(m->m.parameters=List.of(new ParameterNode("name",0)));
        var writer=new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }
    static Class<?> hidden(Class<?> metadata) throws Exception {
        var node=new ClassNode(); new ClassReader(bytes(metadata)).accept(node,0);
        node.methods.removeIf(m->m.name.startsWith("<"));
        var writer=new ClassWriter(0); node.accept(writer);
        return MethodHandles.privateLookupIn(Owner.class,MethodHandles.lookup()).defineHiddenClass(writer.toByteArray(),false).lookupClass();
    }
    static CallSite site(String body,AnnotationConfigApplicationContext context) throws Exception {
        var type=MethodType.methodType(String.class,Owner.class,String.class);
        MethodHandle direct=body.equals("write")?MethodHandles.lookup().findStatic(SpringAddedMethodSecurityTest.class,"write",type.insertParameterTypes(0,AnnotationConfigApplicationContext.class)).bindTo(context)
                :MethodHandles.lookup().findStatic(SpringAddedMethodSecurityTest.class,"work",type);
        return AddedOperationBridge.externalCall(Owner.class,InjectedNames.siteKey("work",InjectedNames.descHash("(Ljava/lang/String;)Ljava/lang/String;")),new ConstantCallSite(direct));
    }
    static class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context;
        final Owner bean,target;
        CallSite site;
        Scope(Class<?>... configs) throws Exception {
            context=new AnnotationConfigApplicationContext(configs);
            if(context.getBeansOfType(Owner.class).isEmpty())context.getBeanFactory().registerSingleton("owner",new Owner());
            bean=context.getBean(Owner.class); target=bean instanceof Advised a?(Owner)a.getTargetSource().getTarget():bean;
            site=site("work",context);
        }
        void publish(Class<?> metadata) throws Exception { SpringAddedOperations.publish(Owner.class,bytes(metadata),MethodHandles.privateLookupIn(Owner.class,MethodHandles.lookup()),List.of(context)); }
        Object invoke(String name) throws Throwable { return site.dynamicInvoker().invokeWithArguments(bean,name); }
        public void close() { context.close(); }
    }
}
