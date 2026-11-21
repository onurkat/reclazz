/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpringRecordCachesTest {
    @ConfigurationProperties("record-probe") @ConstructorBinding
    public record Settings(String name) { public Settings{if(name==null)throw new IllegalArgumentException("required name");} }
    public static final class Holder implements org.springframework.context.SmartLifecycle,org.springframework.beans.factory.DisposableBean {
        public final Settings settings;private volatile boolean running;int destroyed;
        Holder(Settings settings){this.settings=settings;}
        public void start(){running=true;}public void stop(){running=false;}public void stop(Runnable callback){stop();callback.run();}
        public boolean isRunning(){return running;}public boolean isAutoStartup(){return true;}public int getPhase(){return 0;}
        public void destroy(){destroyed++;running=false;}
    }
    @Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(Settings.class)
    static class Config { @Bean Holder holder(Settings settings) { return new Holder(settings); } }
    public static final class Endpoint { public final Holder holder; Endpoint(Holder holder) { this.holder = holder; } }
    private AnnotationConfigApplicationContext context(Map<String,Object> values) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("record-probe", values));
        context.register(Config.class); context.refresh(); return context;
    }
    @Test void preflightDoesNotMutateThenRecreatesExactOwnerAndHealsRealFinalHolder() throws Exception {
        Map<String,Object> values = new HashMap<>(Map.of("record-probe.name", "before"));
        try (var context = context(values)) {
            Settings old = context.getBean(Settings.class); Holder holder = context.getBean(Holder.class);
            Endpoint endpoint = new Endpoint(holder); context.getBeanFactory().registerSingleton("existingEndpoint", endpoint);
            var prepared = SpringRecordCaches.prepare(List.of(context), List.of(Settings.class));
            assertTrue(prepared.owns(Settings.class)); assertSame(old, context.getBean(Settings.class));
            assertSame(old, holder.settings); values.put("record-probe.name", "after");
            assertTrue(prepared.apply().isEmpty()); Settings current = context.getBean(Settings.class);
            assertNotSame(old, current); assertEquals("after", current.name());
            Holder replacement = context.getBean(Holder.class);
            assertSame(holder, replacement); assertSame(current, replacement.settings);
            assertSame(endpoint, context.getBean("existingEndpoint")); assertSame(replacement, endpoint.holder);
        }
    }
    @Test void failingBindLeavesOldValueAndDisposableLifecycleDependentsAlive()throws Exception {
        Map<String,Object> values=new HashMap<>(Map.of("record-probe.name","before"));
        try(var context=context(values)) {
            Settings before=context.getBean(Settings.class);Holder holder=context.getBean(Holder.class);
            assertTrue(holder.isRunning());assertEquals(0,holder.destroyed);
            var prepared=SpringRecordCaches.prepare(List.of(context),List.of(Settings.class));values.remove("record-probe.name");
            var failures=prepared.apply();assertFalse(failures.isEmpty());
            assertSame(before,context.getBeanFactory().getSingleton(context.getBeanNamesForType(Settings.class)[0]));
            assertSame(holder,context.getBeanFactory().getSingleton("holder"));assertSame(before,holder.settings);
            assertTrue(holder.isRunning(),"failed bind cannot stop dependent lifecycle");assertEquals(0,holder.destroyed);
        }
    }
    @Test void successfulBindKeepsExistingLifecycleDependentRunning()throws Exception {
        Map<String,Object> values=new HashMap<>(Map.of("record-probe.name","before"));
        try(var context=context(values)) {
            Holder holder=context.getBean(Holder.class);assertTrue(holder.isRunning());
            var prepared=SpringRecordCaches.prepare(List.of(context),List.of(Settings.class));values.put("record-probe.name","after");
            assertTrue(prepared.apply().isEmpty());assertSame(holder,context.getBean(Holder.class));
            assertSame(context.getBean(Settings.class),holder.settings);assertEquals("after",holder.settings.name());
            assertTrue(holder.isRunning());assertEquals(0,holder.destroyed);
        }
    }
    @Test void changedOwnerAfterPreflightIsReportedAndNotDestroyed() throws Exception {
        try (var context = context(new HashMap<>(Map.of("record-probe.name", "before")))) {
            var prepared = SpringRecordCaches.prepare(List.of(context), List.of(Settings.class));
            String name = context.getBeanNamesForType(Settings.class)[0];
            context.getDefaultListableBeanFactory().destroySingleton(name); Settings changed = context.getBean(Settings.class);
            var failures = prepared.apply(); assertEquals(1, failures.size());
            assertTrue(failures.values().iterator().next().contains("owner changed")); assertSame(changed, context.getBean(Settings.class));
        }
    }
    @Test void ordinaryRecordHasNoConfigurationOwner() throws Exception {
        record Plain(String name) { }
        try (var context = context(new HashMap<>(Map.of("record-probe.name", "before")))) {
            Settings old = context.getBean(Settings.class);
            var prepared = SpringRecordCaches.prepare(List.of(context), List.of(Plain.class));
            assertFalse(prepared.owns(Plain.class)); assertTrue(prepared.apply().isEmpty()); assertSame(old, context.getBean(Settings.class));
        }
    }
    @Test void preflightNeverInitializesUnknownLazyFactoryBean()throws Exception {
        var created=new java.util.concurrent.atomic.AtomicInteger();
        try(var context=context(new HashMap<>(Map.of("record-probe.name","before")))) {
            var definition=new org.springframework.beans.factory.support.RootBeanDefinition(org.springframework.beans.factory.FactoryBean.class);
            definition.setLazyInit(true);definition.setInstanceSupplier(()->{created.incrementAndGet();return new org.springframework.beans.factory.FactoryBean<Object>() {
                public Object getObject(){return new Settings("factory");}
                public Class<?> getObjectType(){return Settings.class;}
            };});context.registerBeanDefinition("lazyUnknownFactory",definition);
            assertEquals(0,created.get());SpringRecordCaches.prepare(List.of(context),List.of(Settings.class));
            assertEquals(0,created.get(),"read-only preflight cannot ask a lazy FactoryBean for its type");
        }
    }
    @Test void preflightNeverCreatesAnUninitializedBindingInfrastructure()throws Exception {
        try(var context=context(new HashMap<>(Map.of("record-probe.name","before")))) {
            Class<?> owner=Class.forName("org.springframework.boot.context.properties.ConfigurationPropertiesBinder");
            var get=owner.getDeclaredMethod("get",org.springframework.beans.factory.BeanFactory.class);get.setAccessible(true);
            Object bindingOwner=get.invoke(null,context.getBeanFactory());var field=owner.getDeclaredField("binder");field.setAccessible(true);
            Object original=field.get(bindingOwner);assertNotNull(original);field.set(bindingOwner,null);
            try {
                Exception rejected=null;
                try{SpringRecordCaches.prepare(List.of(context),List.of(Settings.class));}catch(Exception failure){rejected=failure;}
                assertNull(field.get(bindingOwner),"read-only preflight must not lazily create a Binder");
                assertInstanceOf(IllegalStateException.class,rejected);
            }finally{field.set(bindingOwner,original);}
        }
    }
    @Test void absentBeanFactoryIsARealPreflightFailure() {
        assertThrows(NoSuchMethodException.class, () -> SpringRecordCaches.prepare(List.of(new Object()), List.of(Settings.class)));
    }
}
