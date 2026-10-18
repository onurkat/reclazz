/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.support.LookupOverride;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** The same real Spring constructor contract runs on isolated Spring 5 and 6 graphs. */
public abstract class ConstructorDependencyContract {
    public static class Helper { static int next = 10; final int seed = next; }
    public static class Owner {
        static int creations;
        final Helper helper; final int captured;
        public Owner(Helper helper) { creations++; this.helper = helper; captured = helper.seed; }
    }
    public static class Unrelated { }

    static AnnotationConfigApplicationContext context() {
        Helper.next = 10;
        return new AnnotationConfigApplicationContext(Helper.class, Owner.class, Unrelated.class);
    }
    static SpringBeanReloader reloader(Object... contexts) {
        PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(
                PlatformContext.class.getClassLoader(), new Class<?>[]{PlatformContext.class},
                (p,m,a) -> m.getName().equals("getAllApplicationContexts") ? List.of(contexts) : null);
        return new SpringBeanReloader(platform);
    }
    static String name(AnnotationConfigApplicationContext c, Class<?> type) {
        return c.getBeanNamesForType(type)[0];
    }
    static void edge(AnnotationConfigApplicationContext c) {
        var f = c.getDefaultListableBeanFactory();
        assertTrue(Arrays.asList(f.getDependentBeans(name(c, Helper.class))).contains(name(c, Owner.class)),
                "native dependency edge must survive owner reconstruction");
        assertTrue(Arrays.asList(f.getDependenciesForBean(name(c, Owner.class))).contains(name(c, Helper.class)));
    }
    @Test void nativeCachedRecreationLosesEdge() {
        try (var c = context()) {
            edge(c);
            c.getDefaultListableBeanFactory().destroySingleton(name(c, Owner.class));
            assertEquals(10, c.getBean(Owner.class).captured);
            assertArrayEquals(new String[0], c.getDefaultListableBeanFactory().getDependentBeans(name(c, Helper.class)),
                    "native cache replay control; no agent repair is invoked");
        }
    }
    @Test void repeatedOwnerRecreationKeepsNativeDependencyEdges() {
        try (var c = context()) {
            Object unrelated = c.getBean(Unrelated.class);
            Object unrelatedDefinition = c.getBeanFactory().getMergedBeanDefinition(name(c, Unrelated.class));
            Owner previous = c.getBean(Owner.class);
            for (int save = 0; save < 3; save++) {
                reloader(c).refreshBean(Owner.class.getName(), Owner.class);
                Owner fresh = c.getBean(Owner.class);
                assertNotSame(previous, fresh);
                assertEquals(10, fresh.captured);
                assertSame(c.getBean(Helper.class), fresh.helper);
                edge(c);
                assertSame(unrelated, c.getBean(Unrelated.class));
                assertSame(unrelatedDefinition, c.getBeanFactory().getMergedBeanDefinition(name(c, Unrelated.class)),
                        "unrelated merged metadata must not be flushed");
                previous = fresh;
            }
        }
    }
    @Test void repeatedDependencyRecreationUsesCurrentConstructorValue() {
        try (var c = context()) {
            Object unrelated = c.getBean(Unrelated.class);
            for (int seed : new int[]{20, 30, 40}) {
                // The direct owner reload warms its constructor just like an added callback save.
                reloader(c).refreshBean(Owner.class.getName(), Owner.class);
                Helper.next = seed;
                c.getDefaultListableBeanFactory().destroySingleton(name(c, Helper.class));
                assertNull(c.getDefaultListableBeanFactory().getSingleton(name(c, Owner.class)),
                        "native helper destruction must retire its registered dependent owner");
                Owner fresh = c.getBean(Owner.class);
                assertEquals(seed, fresh.captured);
                assertSame(c.getBean(Helper.class), fresh.helper);
                assertSame(unrelated, c.getBean(Unrelated.class));
            }
        }
    }
    @Test void supportedPreflightDoesNotChangeAnyLiveState() throws Exception {
        try (var c = context()) {
            var f = c.getDefaultListableBeanFactory();
            String n = name(c, Owner.class);
            Object old = f.getSingleton(n), definition = f.getMergedBeanDefinition(n);
            var prepared = SpringConstructorDependencies.prepare(List.of(c), c, n);
            assertNotNull(prepared);
            assertSame(old, f.getSingleton(n));
            assertSame(definition, f.getMergedBeanDefinition(n));
            edge(c);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"supplier", "factoryMethod", "factoryBean", "arguments",
            "methodOverride", "prototype", "parentDefinition", "definitionQualifier"})
    void unsupportedDefinitionPreflightLeavesLiveOwnerUntouched(String policy) {
        try (var c = context()) {
            var f = c.getDefaultListableBeanFactory(); String n = name(c, Owner.class);
            Object old = f.getSingleton(n), merged = f.getMergedBeanDefinition(n);
            var d = (AbstractBeanDefinition) c.getBeanDefinition(n);
            switch (policy) {
                case "supplier" -> d.setInstanceSupplier(() -> old);
                case "factoryMethod" -> d.setFactoryMethodName("create");
                case "factoryBean" -> d.setFactoryBeanName("creator");
                case "arguments" -> d.getConstructorArgumentValues().addIndexedArgumentValue(0, new Helper());
                case "methodOverride" -> d.getMethodOverrides().addOverride(new LookupOverride("lookup", "helper"));
                case "prototype" -> d.setScope("prototype");
                case "parentDefinition" -> d.setParentName("base");
                case "definitionQualifier" -> d.addQualifier(new AutowireCandidateQualifier("custom"));
                default -> fail(policy);
            }
            assertNull(SpringConstructorDependencies.prepare(List.of(c), c, n));
            assertSame(old, f.getSingleton(n));
            assertSame(merged, f.getMergedBeanDefinition(n));
            edge(c);
        }
    }
    @Test void separateSameNameContextsHaveLocalReadOnlyCachePreparation() {
        try (var a = context(); var b = context()) {
            Object left = a.getBean(Owner.class), right = b.getBean(Owner.class);
            assertEquals(name(a, Owner.class), name(b, Owner.class));
            for (var c : List.of(a,b)) {
                assertNotNull(SpringConstructorDependencies.prepare(List.of(a,b), c, name(c, Owner.class)));
                edge(c);
            }
            assertSame(left, a.getBean(Owner.class)); assertSame(right, b.getBean(Owner.class));
        }
    }

    public static class Held { Owner owner; Held(Owner owner) { this.owner = owner; } }
    public static class OtherHelper { }
    public static class OtherOwner {
        static int creations;
        final OtherHelper helper;
        public OtherOwner(OtherHelper helper) { creations++; this.helper = helper; }
    }
    public static class SecondOwner { public SecondOwner(Helper helper) { } }
    public static class Transitive { public Transitive(Owner owner) { } }
    public static class CallbackOwner implements AutoCloseable {
        static int closes;
        public CallbackOwner(Helper helper) { }
        public void close() { closes++; }
    }
    public static class AddedAnnotationMetadata {
        @org.springframework.beans.factory.annotation.Autowired public Helper injected;
        @org.springframework.context.event.EventListener public void event(Object value) { }
    }

    @ParameterizedTest @ValueSource(strings={"method","field"})
    void addedAnnotatedMetadataRefusesAnAlreadyPreparedClosure(String member) throws Exception {
        try(var c=context()) {
            var f=c.getDefaultListableBeanFactory();String h=name(c,Helper.class),o=name(c,Owner.class);
            Object helper=f.getSingleton(h),owner=f.getSingleton(o),merged=f.getMergedBeanDefinition(o);
            var plan=SpringConstructorDependencies.prepareOrdinary(List.of(c),c,h);assertNotNull(plan);
            var methods=member.equals("method") ? List.of(AddedAnnotationMetadata.class.getDeclaredMethod("event",Object.class)) : List.<java.lang.reflect.Method>of();
            var fields=member.equals("field") ? List.of(AddedAnnotationMetadata.class.getDeclaredField("injected")) : List.<java.lang.reflect.Field>of();
            com.onurkat.reclazz.bootstrap.ReflectionBridge.replaceClassState(Owner.class,methods,fields);
            assertThrows(Exception.class,()->plan.recreate(List.of(c),new java.util.IdentityHashMap<>()));
            assertSame(helper,f.getSingleton(h));assertSame(owner,f.getSingleton(o));
            assertSame(merged,f.getMergedBeanDefinition(o));edge(c);
        } finally {
            com.onurkat.reclazz.bootstrap.ReflectionBridge.replaceClassState(Owner.class,List.of(),List.of());
            com.onurkat.reclazz.ui.RestartLedger.clear();
        }
    }

    @Test void lifecycleOwnedDependentRefusesWithoutCallingClose() {
        try (var c=new AnnotationConfigApplicationContext(Helper.class,CallbackOwner.class)) {
            String h=name(c,Helper.class),o=name(c,CallbackOwner.class);
            Object helper=c.getBeanFactory().getSingleton(h),owner=c.getBeanFactory().getSingleton(o);
            int before=CallbackOwner.closes;
            assertThrows(IllegalStateException.class,()->SpringConstructorDependencies.prepareOrdinary(List.of(c),c,h));
            assertEquals(before,CallbackOwner.closes);
            assertSame(helper,c.getBeanFactory().getSingleton(h)); assertSame(owner,c.getBeanFactory().getSingleton(o));
        } finally { com.onurkat.reclazz.ui.RestartLedger.clear(); }
    }

    @Test void preflightDoesNotCreateLazyConstructorOwner() throws Exception {
        try (var c=new AnnotationConfigApplicationContext()) {
            c.register(Helper.class);
            var definition=new RootBeanDefinition(Owner.class); definition.setLazyInit(true);
            c.registerBeanDefinition("lazyOwner",definition); c.refresh(); int before=Owner.creations;
            assertNull(SpringConstructorDependencies.prepareOrdinary(List.of(c),c,name(c,Helper.class)));
            assertNull(SpringConstructorDependencies.prepare(List.of(c),c,"lazyOwner"));
            assertNull(c.getBeanFactory().getSingleton("lazyOwner")); assertEquals(before,Owner.creations);
        }
    }

    @Test void parentLeafRefreshIgnoresLocalShadowSiblingAndDuplicateCapture() {
        Helper.next = 10;
        try (var parent = new AnnotationConfigApplicationContext(Helper.class);
             var child = new AnnotationConfigApplicationContext();
             var shadow = new AnnotationConfigApplicationContext();
             var sibling = new AnnotationConfigApplicationContext()) {
            child.setParent(parent); child.register(Owner.class); child.refresh();
            String helperName = name(parent, Helper.class), ownerName = name(child, Owner.class);
            for (var c : List.of(shadow, sibling)) {
                if (c == shadow) c.setParent(parent);
                c.registerBeanDefinition(helperName, new RootBeanDefinition(OtherHelper.class));
                c.registerBeanDefinition(ownerName, new RootBeanDefinition(OtherOwner.class)); c.refresh();
            }
            var held = new Held(child.getBean(Owner.class)); child.getBeanFactory().registerSingleton("held", held);
            Object shadowOwner = shadow.getBean(ownerName), siblingOwner = sibling.getBean(ownerName);
            Object shadowMetadata = shadow.getBeanFactory().getMergedBeanDefinition(ownerName);
            Object siblingMetadata = sibling.getBeanFactory().getMergedBeanDefinition(ownerName);
            int otherBirths = OtherOwner.creations;
            for (int seed : new int[]{20,30,40}) {
                Helper.next = seed;
                reloader(parent, child, shadow, sibling, child).refreshBean(Helper.class.getName(), Helper.class);
                assertEquals(seed, held.owner.captured);
                assertSame(child.getBeanFactory().getSingleton(ownerName), held.owner);
                assertTrue(Arrays.asList(child.getBeanFactory().getDependentBeans(helperName)).contains(ownerName));
                assertSame(shadowOwner, shadow.getBeanFactory().getSingleton(ownerName));
                assertSame(siblingOwner, sibling.getBeanFactory().getSingleton(ownerName));
                assertSame(shadowMetadata, shadow.getBeanFactory().getMergedBeanDefinition(ownerName));
                assertSame(siblingMetadata, sibling.getBeanFactory().getMergedBeanDefinition(ownerName));
                assertEquals(otherBirths, OtherOwner.creations);
            }
        } finally { Helper.next = 10; }
    }

    @Test void duplicateDirectOwnerCaptureRecreatesOnlyOnce() {
        try (var c = context()) {
            int before = Owner.creations;
            reloader(c,c).refreshBean(Owner.class.getName(), Owner.class);
            assertEquals(before + 1, Owner.creations); edge(c);
        }
    }

    @ParameterizedTest @ValueSource(strings={"supplier","factoryMethod","arguments","parentDefinition",
            "definitionQualifier","alias","extraDependent","transitive"})
    void unsupportedIndirectClosureRefusesBeforeRetirement(String policy) throws Exception {
        try (var c = context()) {
            var f = c.getDefaultListableBeanFactory(); String h = name(c, Helper.class), o = name(c, Owner.class);
            Object helper = f.getSingleton(h), owner = f.getSingleton(o), merged = f.getMergedBeanDefinition(o);
            var d = (AbstractBeanDefinition)c.getBeanDefinition(o);
            switch (policy) {
                case "supplier" -> d.setInstanceSupplier(() -> owner);
                case "factoryMethod" -> d.setFactoryMethodName("create");
                case "arguments" -> d.getConstructorArgumentValues().addIndexedArgumentValue(0, helper);
                case "parentDefinition" -> d.setParentName("base");
                case "definitionQualifier" -> d.addQualifier(new AutowireCandidateQualifier("custom"));
                case "alias" -> f.registerAlias(h, "alternateHelper");
                case "extraDependent" -> { c.register(SecondOwner.class); c.getBean(SecondOwner.class); }
                case "transitive" -> { c.register(Transitive.class); c.getBean(Transitive.class); }
                default -> fail(policy);
            }
            assertThrows(IllegalStateException.class, () -> SpringConstructorDependencies.prepareOrdinary(List.of(c),c,h));
            assertSame(helper, f.getSingleton(h)); assertSame(owner, f.getSingleton(o));
            assertSame(merged, f.getMergedBeanDefinition(o)); edge(c);
        } finally { com.onurkat.reclazz.ui.RestartLedger.clear(); }
    }

    @ParameterizedTest @ValueSource(strings={"definition","instance","context"})
    void ordinaryPlanRevalidatesBeforeInvalidation(String change) throws Exception {
        try (var c = context()) {
            var f=c.getDefaultListableBeanFactory(); String h=name(c,Helper.class),o=name(c,Owner.class);
            var plan=SpringConstructorDependencies.prepareOrdinary(List.of(c),c,h); assertNotNull(plan);
            if (change.equals("definition")) ((AbstractBeanDefinition)c.getBeanDefinition(o)).setInstanceSupplier(() -> new Owner(new Helper()));
            else if (change.equals("instance")) { f.destroySingleton(o); c.getBean(o); }
            else c.close();
            Object owner=f.getSingleton(o), helper=f.getSingleton(h);
            Object merged=change.equals("context") ? null : f.getMergedBeanDefinition(o);
            assertThrows(Exception.class, () -> plan.recreate(List.of(c),new java.util.IdentityHashMap<>()));
            assertSame(owner,f.getSingleton(o)); assertSame(helper,f.getSingleton(h));
            if (merged!=null) assertSame(merged,f.getMergedBeanDefinition(o));
        } finally { com.onurkat.reclazz.ui.RestartLedger.clear(); }
    }

    @ParameterizedTest @ValueSource(strings={"owner-first","helper-first"})
    void ordinaryBatchRebuildsLeafOnceInEitherOrder(String order) {
        try (var c=context()) {
            var held=new Held(c.getBean(Owner.class)); c.getBeanFactory().registerSingleton("held",held);
            int before=Owner.creations; var r=reloader(c);
            r.beginBatch(); Helper.next=20;
            if (order.equals("owner-first")) {
                r.refreshBean(Owner.class.getName(),Owner.class); r.refreshBean(Helper.class.getName(),Helper.class);
            } else {
                r.refreshBean(Helper.class.getName(),Helper.class); r.refreshBean(Owner.class.getName(),Owner.class);
            }
            assertEquals(before,Owner.creations,"plain reconstruction is deferred until the complete batch");
            r.endBatch(); assertEquals(before+1,Owner.creations); assertEquals(20,held.owner.captured);
            assertSame(c.getBeanFactory().getSingleton(name(c,Owner.class)),held.owner); edge(c);
        } finally { Helper.next=10; }
    }

    public static class FailingHelper {
        static boolean fail;
        public FailingHelper() { if(fail) throw new IllegalStateException("synthetic constructor failure"); }
    }
    public static class FailingOwner {
        static boolean fail;
        public FailingOwner(FailingHelper helper) { if(fail) throw new IllegalStateException("synthetic leaf failure"); }
    }
    @ParameterizedTest @ValueSource(strings={"helper","owner"})
    void nativeCreationFailureIsRecordedAsPartialRestart(String failing) throws Exception {
        com.onurkat.reclazz.ui.RestartLedger.clear(); FailingHelper.fail=false; FailingOwner.fail=false;
        try(var c=new AnnotationConfigApplicationContext(FailingHelper.class,FailingOwner.class)) {
            String h=name(c,FailingHelper.class),o=name(c,FailingOwner.class);
            Object oldHelper=c.getBeanFactory().getSingleton(h);
            var plan=SpringConstructorDependencies.prepareOrdinary(List.of(c),c,h); assertNotNull(plan);
            if(failing.equals("helper")) FailingHelper.fail=true; else FailingOwner.fail=true;
            assertThrows(Exception.class,() -> plan.recreate(List.of(c),new java.util.IdentityHashMap<>()));
            assertNull(c.getBeanFactory().getSingleton(o));
            if(failing.equals("helper")) assertNull(c.getBeanFactory().getSingleton(h));
            else assertNotSame(oldHelper,c.getBeanFactory().getSingleton(h));
            assertTrue(com.onurkat.reclazz.ui.RestartLedger.digest().stream().anyMatch(s->s.contains("partially reconstructed")));
        } finally { FailingHelper.fail=false; FailingOwner.fail=false; com.onurkat.reclazz.ui.RestartLedger.clear(); }
    }
    @Test void proxyAppearingDuringRecreationRequiresPartialRestart() throws Exception {
        com.onurkat.reclazz.ui.RestartLedger.clear();
        try(var c=context()) {
            var proxy=new java.util.concurrent.atomic.AtomicBoolean();
            c.getBeanFactory().addBeanPostProcessor(new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean,String n) {
                    return proxy.get() && bean instanceof Owner ? new ProxyFactory(bean).getProxy() : bean;
                }
            });
            String h=name(c,Helper.class);var plan=SpringConstructorDependencies.prepareOrdinary(List.of(c),c,h);
            assertNotNull(plan);proxy.set(true);
            assertThrows(Exception.class,()->plan.recreate(List.of(c),new java.util.IdentityHashMap<>()));
            assertTrue(com.onurkat.reclazz.ui.RestartLedger.digest().stream().anyMatch(s->s.contains("partially reconstructed")));
        } finally { com.onurkat.reclazz.ui.RestartLedger.clear(); }
    }
    @Test void parentContextDoesNotReceiveCacheRepair() {
        try (var parent = context(); var child = new AnnotationConfigApplicationContext()) {
            child.setParent(parent); child.register(Helper.class, Owner.class); child.refresh();
            Object original = child.getBean(Owner.class);
            assertNull(SpringConstructorDependencies.prepare(List.of(child), child, name(child, Owner.class)));
            assertSame(original, child.getBean(Owner.class)); edge(child); edge(parent);
        }
    }
    @Test void multilevelOwnerDoesNotReceiveCachePreparation() {
        try(var parent=new AnnotationConfigApplicationContext(Helper.class);
            var middle=new AnnotationConfigApplicationContext();var child=new AnnotationConfigApplicationContext()) {
            middle.setParent(parent);middle.refresh();child.setParent(middle);child.register(Owner.class);child.refresh();
            var contexts=List.<Object>of(parent,middle,child);String h=name(parent,Helper.class),o=name(child,Owner.class);
            Object helper=parent.getBeanFactory().getSingleton(h),owner=child.getBeanFactory().getSingleton(o);
            assertNull(SpringConstructorDependencies.prepare(contexts,child,o));
            assertThrows(IllegalStateException.class,()->SpringConstructorDependencies.prepareOrdinary(contexts,parent,h));
            assertSame(helper,parent.getBeanFactory().getSingleton(h));assertSame(owner,child.getBeanFactory().getSingleton(o));
        } finally { com.onurkat.reclazz.ui.RestartLedger.clear(); }
    }
    @Test void parentQualifierIsOutsideTheNewCacheScope() {
        try(var parent=new AnnotationConfigApplicationContext();var child=new AnnotationConfigApplicationContext()) {
            parent.registerBeanDefinition("blue",new RootBeanDefinition(Helper.class));parent.refresh();
            child.setParent(parent);child.register(QualifiedOwner.class);child.refresh();
            String o=name(child,QualifiedOwner.class);Object owner=child.getBeanFactory().getSingleton(o);
            assertNull(SpringConstructorDependencies.prepare(List.of(parent,child),child,o));
            assertSame(owner,child.getBeanFactory().getSingleton(o));
        }
    }
    @Test void proxyDoesNotReceiveCacheRepair() {
        try (var c = new AnnotationConfigApplicationContext()) {
            c.getBeanFactory().addBeanPostProcessor(new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String n) {
                    return bean instanceof Owner ? new ProxyFactory(bean).getProxy() : bean;
                }
            });
            c.register(Helper.class, Owner.class); c.refresh();
            String n = name(c, Owner.class); Object original = c.getBean(n);
            assertNotEquals(Owner.class, original.getClass());
            assertNull(SpringConstructorDependencies.prepare(List.of(c), c, n));
            assertSame(original, c.getBean(n)); edge(c);
        }
    }
    public static class CustomDefinition extends RootBeanDefinition {
        public CustomDefinition() { super(Owner.class); }
    }
    @Test void customDefinitionDoesNotReceiveCacheRepair() {
        try (var c = new AnnotationConfigApplicationContext()) {
            c.register(Helper.class); c.registerBeanDefinition("owner", new CustomDefinition()); c.refresh();
            Object old = c.getBean("owner");
            assertNull(SpringConstructorDependencies.prepare(List.of(c), c, "owner"));
            assertSame(old, c.getBean("owner"));
        }
    }
    @Test void manualSingletonDoesNotReceiveCacheRepair() {
        try (var c = context()) {
            Owner manual = new Owner(c.getBean(Helper.class));
            c.getBeanFactory().registerSingleton("manual", manual);
            assertNull(SpringConstructorDependencies.prepare(List.of(c), c, "manual"));
            assertSame(manual, c.getBean("manual"));
        }
    }
    @Test void changedOwnerAfterPreparationIsRejectedBeforeInvalidation() throws Exception {
        try (var c = context()) {
            String n = name(c, Owner.class); var f = c.getDefaultListableBeanFactory();
            var prepared = SpringConstructorDependencies.prepare(List.of(c), c, n); assertNotNull(prepared);
            f.destroySingleton(n); Object fresh = c.getBean(n), merged = f.getMergedBeanDefinition(n);
            assertThrows(IllegalStateException.class, prepared::apply);
            assertSame(fresh, f.getSingleton(n)); assertSame(merged, f.getMergedBeanDefinition(n));
        }
    }
    @Test void changedDefinitionAfterPreparationIsRejectedBeforeInvalidation() throws Exception {
        try (var c = context()) {
            String n = name(c, Owner.class); var f = c.getDefaultListableBeanFactory();
            Object original = c.getBean(n), merged = f.getMergedBeanDefinition(n);
            var prepared = SpringConstructorDependencies.prepare(List.of(c), c, n); assertNotNull(prepared);
            ((AbstractBeanDefinition) c.getBeanDefinition(n)).setInstanceSupplier(() -> original);
            assertThrows(IllegalStateException.class, prepared::apply);
            assertSame(original, f.getSingleton(n)); assertSame(merged, f.getMergedBeanDefinition(n)); edge(c);
        }
    }
    public static class MultipleConstructors {
        public MultipleConstructors() { }
        @org.springframework.beans.factory.annotation.Autowired
        public MultipleConstructors(Helper helper) { }
    }
    @Test void multipleConstructorsDoNotReceiveCacheRepair() {
        try (var c = new AnnotationConfigApplicationContext(Helper.class, MultipleConstructors.class)) {
            String n = name(c, MultipleConstructors.class); Object original = c.getBean(n);
            assertNull(SpringConstructorDependencies.prepare(List.of(c), c, n));
            assertSame(original, c.getBean(n));
            assertArrayEquals(new String[]{n}, c.getBeanFactory().getDependentBeans(name(c, Helper.class)));
        }
    }
    public static class Creator implements org.springframework.beans.factory.FactoryBean<Owner> {
        private final Owner product;
        public Creator(Helper helper) { product = new Owner(helper); }
        @Override public Owner getObject() { return product; }
        @Override public Class<?> getObjectType() { return Owner.class; }
    }
    @Test void factoryBeanDoesNotReceiveCacheRepair() {
        try (var c = new AnnotationConfigApplicationContext()) {
            c.register(Helper.class); c.registerBeanDefinition("creator", new RootBeanDefinition(Creator.class)); c.refresh();
            Object product = c.getBean("creator"), creator = c.getBean("&creator");
            assertNull(SpringConstructorDependencies.prepare(List.of(c), c, "creator"));
            assertSame(product, c.getBean("creator")); assertSame(creator, c.getBean("&creator"));
        }
    }
    public static class QualifiedOwner {
        final Helper helper;
        public QualifiedOwner(@Qualifier("blue") Helper h) { helper = h; }
    }
    @Test void stableQualifierUsesNativeSelection() {
        try (var c = new AnnotationConfigApplicationContext()) {
            c.registerBeanDefinition("blue", new RootBeanDefinition(Helper.class));
            c.registerBeanDefinition("red", new RootBeanDefinition(Helper.class));
            c.register(QualifiedOwner.class); c.refresh(); String n = name(c, QualifiedOwner.class);
            for (int save = 0; save < 3; save++) {
                reloader(c).refreshBean(QualifiedOwner.class.getName(), QualifiedOwner.class);
                assertSame(c.getBean("blue"), c.getBean(QualifiedOwner.class).helper);
                assertArrayEquals(new String[]{n}, c.getBeanFactory().getDependentBeans("blue"));
                assertArrayEquals(new String[0], c.getBeanFactory().getDependentBeans("red"));
            }
        }
    }
    @Test void nativeCandidateRemovalDoesNotRestoreStaleEdges() {
        try (var c = new AnnotationConfigApplicationContext()) {
            var blue = new RootBeanDefinition(Helper.class); blue.setPrimary(true);
            c.registerBeanDefinition("blue", blue);
            c.registerBeanDefinition("red", new RootBeanDefinition(Helper.class));
            c.register(Owner.class); c.refresh();
            reloader(c).refreshBean(Owner.class.getName(), Owner.class);
            String n = name(c, Owner.class);
            c.removeBeanDefinition("blue");
            assertNull(c.getBeanFactory().getSingleton(n));
            c.getBean(n);
            reloader(c).refreshBean(Owner.class.getName(), Owner.class);
            assertSame(c.getBean("red"), c.getBean(Owner.class).helper);
            assertArrayEquals(new String[0], c.getBeanFactory().getDependentBeans("blue"));
            assertArrayEquals(new String[]{n}, c.getBeanFactory().getDependentBeans("red"));
            assertArrayEquals(new String[]{"red"}, c.getBeanFactory().getDependenciesForBean(n));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"constructor", "qualifier"})
    @SuppressWarnings("unchecked")
    void actualConstructorAnnotationEditRefusesCacheRepair(String edit) throws Exception {
        var instrumentation = net.bytebuddy.agent.ByteBuddyAgent.install();
        try (var c = context()) {
            String n = name(c, Owner.class); Object old = c.getBean(n);
            assertNotNull(SpringConstructorDependencies.prepare(List.of(c), c, n));
            // Follow the existing SpringAopReloaderTest pattern: transform after JaCoCo,
            // keeping its injected members through both the edit and restoration.
            var transformer = new java.lang.instrument.ClassFileTransformer() {
                @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                        java.security.ProtectionDomain domain, byte[] bytes) {
                    if (type != Owner.class) return null;
                    var node = new org.objectweb.asm.tree.ClassNode();
                    new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
                    var constructor = node.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
                    if (edit.equals("constructor")) {
                        constructor.visibleAnnotations = new java.util.ArrayList<>(List.of(
                                new org.objectweb.asm.tree.AnnotationNode("Lorg/springframework/beans/factory/annotation/Autowired;")));
                    } else {
                        var qualifier = new org.objectweb.asm.tree.AnnotationNode("Lorg/springframework/beans/factory/annotation/Qualifier;");
                        qualifier.values = new java.util.ArrayList<>(List.of("value", "missing"));
                        constructor.visibleParameterAnnotations = new List[]{new java.util.ArrayList<>(List.of(qualifier))};
                    }
                    var writer = new org.objectweb.asm.ClassWriter(0); node.accept(writer);
                    return writer.toByteArray();
                }
            };
            instrumentation.addTransformer(transformer, true);
            try {
                instrumentation.retransformClasses(Owner.class);
                var live = Owner.class.getDeclaredConstructors()[0];
                if (edit.equals("constructor")) assertNotNull(live.getAnnotation(org.springframework.beans.factory.annotation.Autowired.class));
                else assertEquals("missing", live.getParameters()[0].getAnnotation(Qualifier.class).value());
                assertNull(SpringConstructorDependencies.prepare(List.of(c), c, n));
                assertSame(old, c.getBeanFactory().getSingleton(n)); edge(c);
            } finally {
                instrumentation.removeTransformer(transformer);
                instrumentation.retransformClasses(Owner.class);
            }
        }
    }

}
