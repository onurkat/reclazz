/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.ui.StatusReporter;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MvcResolvedHandlerCacheTest {
    public static class Factory {
        final Map<String,Object> singletons = new HashMap<>();
        public String canonicalName(String name) { return name; }
        public boolean containsSingleton(String name) { return singletons.containsKey(name); }
        public Object getSingleton(String name, boolean early) {
            assertFalse(early, "never trigger early singleton creation"); return singletons.get(name);
        }
    }
    public static class Owner {
        private final Object bean;
        Owner(Object bean) { this.bean = bean; }
        public Object getBean() { return bean; }
        public Class<?> getBeanType() { return Object.class; }
    }
    public static class Handler extends Owner {
        volatile Owner resolvedBeanHandlerMethod;
        Handler(Object cached) { super("endpoint"); resolvedBeanHandlerMethod = new Owner(cached); }
    }
    public static class FinalHandler extends Owner {
        final Owner resolvedBeanHandlerMethod = new Owner(new Object());
        FinalHandler() { super("endpoint"); }
    }
    public static class Registry { final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock(); }
    public static class Mapping {
        final Registry mappingRegistry = new Registry();
        final Map<String,Object> methods = new LinkedHashMap<>();
        public Map<String,Object> getHandlerMethods() { return methods; }
    }
    private static int clear(Factory factory, Object handler) {
        Mapping mapping = new Mapping();mapping.methods.put("/probe",handler);
        return MvcResolvedHandlerCache.invalidate(factory,mapping,null,true);
    }
    public static class MissingLockMapping {
        final Map<String, Object> methods = new LinkedHashMap<>();
        public Map<String, Object> getHandlerMethods() { return methods; }
    }
    @Test void missingLockRescansEvenAnEmptyCacheWithAPossiblePendingStore() {
        var mapping = new MissingLockMapping(); var handler = new Handler(null);
        handler.resolvedBeanHandlerMethod = null; mapping.methods.put("/probe", handler);
        var rescanned = new ArrayList<String>();
        var fallback = new SpringMvcReloader(null) {
            @Override boolean rescan(Object target, String name, Class<?> type) {
                assertSame(mapping, target); assertEquals(Object.class, type); rescanned.add(name); return true;
            }
        };
        assertEquals(0, MvcResolvedHandlerCache.invalidate(new Factory(), mapping, fallback, true));
        assertEquals(List.of("endpoint"), rescanned);
    }
    @Test void missingLockFallsBackForAResolvedStaleHandler() {
        var mapping = new MissingLockMapping(); var handler = new Handler(new Object());
        mapping.methods.put("/probe", handler); var rescanned = new ArrayList<String>();
        var fallback = new SpringMvcReloader(null) {
            @Override boolean rescan(Object target, String name, Class<?> type) { rescanned.add(name); return true; }
        };
        assertEquals(0, MvcResolvedHandlerCache.invalidate(new Factory(), mapping, fallback, true));
        assertEquals(List.of("endpoint"), rescanned);
        assertNotNull(handler.resolvedBeanHandlerMethod, "fallback must not perform an unlocked null write");
    }
    @Test void missingCacheFieldIsNoOp() { assertEquals(0,clear(new Factory(),new Owner("endpoint"))); }
    @Test void identicalSingletonRetainsCache() {
        Object bean=new Object();var factory=new Factory();factory.singletons.put("endpoint",bean);
        var handler=new Handler(bean);Object before=handler.resolvedBeanHandlerMethod;
        assertEquals(0,clear(factory,handler));assertSame(before,handler.resolvedBeanHandlerMethod);
    }
    @Test void destroyedSingletonClearsCache() {
        var handler=new Handler(new Object());assertEquals(1,clear(new Factory(),handler));assertNull(handler.resolvedBeanHandlerMethod);
    }
    @Test void replacedSingletonClearsCache() {
        var factory=new Factory();factory.singletons.put("endpoint",new Object());var handler=new Handler(new Object());
        assertEquals(1,clear(factory,handler));assertNull(handler.resolvedBeanHandlerMethod);
    }
    @Test void unwritableCacheWarnsWithoutThrowing() {
        var messages=new ArrayList<String>();StatusReporter.StatusListener listener=(level,message)->messages.add(level+":"+message);
        StatusReporter.addListener(listener);
        try {assertEquals(0,clear(new Factory(),new FinalHandler()));}
        finally {StatusReporter.removeListener(listener);}
        assertTrue(messages.stream().anyMatch(s->s.contains("endpoint")&&s.contains("restart or save the controller")),messages.toString());
    }
}
