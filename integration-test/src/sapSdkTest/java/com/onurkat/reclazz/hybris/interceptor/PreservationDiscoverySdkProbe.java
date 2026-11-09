/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris.interceptor;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.spring.SpringBeanReloader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.beans.factory.support.RootBeanDefinition;
import java.util.*;

/** Native singleton discovery boundaries, including ordinary owners without SDK visibility. */
public class PreservationDiscoverySdkProbe {
    static String nextValue = "hello";
    public static class Service { final String value = nextValue; }
    static final ClassLoader visible = PreservationDiscoverySdkProbe.class.getClassLoader();
    static final ClassLoader blind = new ClassLoader(visible) {
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("de.hybris.platform.servicelayer.interceptor.")) throw new ClassNotFoundException(name);
            return super.loadClass(name, resolve);
        }
    };
    static GenericApplicationContext ordinary() {
        var c = new GenericApplicationContext();
        c.registerBeanDefinition("service", new RootBeanDefinition(Service.class)); c.refresh();
        c.setClassLoader(blind); c.getBeanFactory().setBeanClassLoader(blind);
        return c;
    }
    static PlatformContext platform(List<GenericApplicationContext> owners) {
        return (PlatformContext) java.lang.reflect.Proxy.newProxyInstance(visible, new Class<?>[]{PlatformContext.class},
                (p,m,a) -> switch(m.getName()) { case "getApplicationContext" -> owners.get(0); case "getAllApplicationContexts" -> owners; default -> null; });
    }
    static SpringBeanReloader reloader(List<GenericApplicationContext> owners) {
        var beans = new SpringBeanReloader(platform(owners));
        beans.setPreservedSingletons(InterceptorSingletons::preserved); return beans;
    }
    static void preservationWithoutNativeSdkDoesNotBlockOrdinaryRefresh() throws Exception {
        for (boolean batch : List.of(false, true)) {
            nextValue = "hello";
            try (var c = ordinary()) {
                Object before = c.getBean("service"); nextValue = "constructed";
                check(InterceptorSingletons.preserved(c).isEmpty(), "ordinary owner must have nothing to preserve");
                var beans = reloader(List.of(c)); if (batch) beans.beginBatch();
                beans.refreshBean(Service.class.getName(), Service.class); if (batch) beans.endBatch();
                check(before != c.getBean("service") && c.getBean("service",Service.class).value.equals("constructed"), "SDK-absent ordinary service stayed stale");
            }
        }
    }
    static void nativeLoaderBoundary(boolean bothBlind) throws Exception {
        for (boolean batch : List.of(false, true)) try (var c = RegistryPreservationSdkProbe.context(true)) {
            c.setClassLoader(blind); c.getBeanFactory().setBeanClassLoader(bothBlind ? blind : visible);
            check(InterceptorSingletons.preserved(c).equals(Set.of("nativeRegistry", "mapping", "unrelated")), "native discovery set differs");
            Object registry=c.getBean("interceptorRegistry"), mapping=c.getBean("mapping"), target=c.getBean("target"), holder=c.getBean("ordinary");
            var pending=new InterceptorReloader().prepareInContext(InterceptorRegistrySdkProbe.Target.class.getName(),c,visible);
            var beans=reloader(List.of(c)); if(batch)beans.beginBatch();
            beans.refreshBean(InterceptorRegistrySdkProbe.Target.class.getName(),InterceptorRegistrySdkProbe.Target.class);
            check(pending.complete(),"native completion failed"); if(batch)beans.endBatch();
            RegistryPreservationSdkProbe.verify(c,registry,mapping,target,holder);
        }
    }
    static void ordinaryRefreshContinuesAcrossMixedOwners() throws Exception {
        for (boolean batch : List.of(false,true)) {
            nextValue="hello";
            try(var first=ordinary();var second=RegistryPreservationSdkProbe.context(false)) {
                second.registerBeanDefinition("service",new RootBeanDefinition(Service.class));
                Object before1=first.getBean("service"),before2=second.getBean("service"),registry=second.getBean("interceptorRegistry");
                nextValue="constructed";
                var beans=reloader(List.of(first,second));if(batch)beans.beginBatch();
                beans.refreshBean(Service.class.getName(),Service.class);if(batch)beans.endBatch();
                check(before1!=first.getBean("service") && before2!=second.getBean("service"),"one ordinary owner blocked a later native owner");
                check(first.getBean("service",Service.class).value.equals("constructed") && second.getBean("service",Service.class).value.equals("constructed"),"mixed-owner constructor values stale");
                check(second.getBean("interceptorRegistry")==registry,"mixed owner native registry replaced");
            }
        }
    }
    static void mappingOnlyContextAndRegistryAliasArePreserved() throws Exception {
        try(var c=new GenericApplicationContext();var parent=RegistryPreservationSdkProbe.context(true)) {
            c.setParent(parent);
            c.registerBeanDefinition("target",new RootBeanDefinition(InterceptorRegistrySdkProbe.Target.class));
            InterceptorRegistrySdkProbe.mapping(c,"mapping","target",7);c.registerAlias("mapping","alias");c.refresh();
            c.setClassLoader(blind);c.getBeanFactory().setBeanClassLoader(blind);
            check(InterceptorSingletons.preserved(c).equals(Set.of("mapping")),"mapping-only owner borrowed parent registry or lost its mapping");
            check(InterceptorSingletons.preserved(parent).contains("nativeRegistry"),"registry alias was not canonicalized");
        }
    }
    static void nonNativeBeanNamedInterceptorRegistryIsNotPreserved() throws Exception {
        try(var c=ordinary()) {
            c.getBeanFactory().registerSingleton("interceptorRegistry",new Object());
            check(InterceptorSingletons.preserved(c).isEmpty(),"non-native name collision preserved");
        }
    }
    public static void main(String[] args) throws Exception {
        Map<String,Checked> cases=new LinkedHashMap<>();
        cases.put("preservationWithoutNativeSdkDoesNotBlockOrdinaryRefresh",PreservationDiscoverySdkProbe::preservationWithoutNativeSdkDoesNotBlockOrdinaryRefresh);
        cases.put("contextResourceLoaderMismatchPreservesNativeSingletons",()->nativeLoaderBoundary(false));
        cases.put("initializedNativeObjectsSurviveNarrowContextAndFactoryLoaders",()->nativeLoaderBoundary(true));
        cases.put("ordinaryRefreshContinuesAcrossMixedOwners",PreservationDiscoverySdkProbe::ordinaryRefreshContinuesAcrossMixedOwners);
        cases.put("mappingOnlyContextAndRegistryAliasArePreserved",PreservationDiscoverySdkProbe::mappingOnlyContextAndRegistryAliasArePreserved);
        cases.put("nonNativeBeanNamedInterceptorRegistryIsNotPreserved",PreservationDiscoverySdkProbe::nonNativeBeanNamedInterceptorRegistryIsNotPreserved);
        int failures=0;
        for(var entry:cases.entrySet())try{entry.getValue().run();System.out.println("PASS: "+entry.getKey());}
        catch(Throwable failure){failures++;System.out.println("FAIL: "+entry.getKey()+": "+failure);}
        System.out.println("PRESERVATION_DISCOVERY: "+cases.size()+" cases, "+failures+" failures");
        check(failures==0,"preservation discovery regressions failed");
    }
    @FunctionalInterface interface Checked {void run() throws Exception;}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
