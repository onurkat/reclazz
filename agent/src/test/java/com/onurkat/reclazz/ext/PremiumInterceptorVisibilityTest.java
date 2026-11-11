/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.ext;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.*;
import com.onurkat.reclazz.hybris.SapPremiumModuleFactory;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PremiumInterceptorVisibilityTest {
    private static final String TARGET = PremiumInterceptorVisibilityTest.class.getName();
    private final List<String> messages = new ArrayList<>();
    private final StatusReporter.StatusListener listener = (level, text) -> messages.add(level + ":" + text);
    @BeforeEach void start() { RestartLedger.clear(); StatusReporter.addListener(listener); }
    @AfterEach void stop() { StatusReporter.removeListener(listener); RestartLedger.clear(); }
    private PremiumModule module(PlatformContext.Platform kind) {
        var platform = (PlatformContext) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PlatformContext.class}, (p, method, args) -> {
                    if (method.getName().equals("getPlatformId")) return kind;
                    throw new UnsupportedOperationException(method.getName());
                });
        return new SapPremiumModuleFactory().create(new PremiumContext() {
            public PlatformContext platform() { return platform; }
            public AgentConfig config() { return AgentConfig.parse(null); }
            public PropertyFileSnapshots propertySnapshots() { return new PropertyFileSnapshots(); }
            public java.lang.instrument.Instrumentation instrumentation() { return null; }
            public int applyLoggerLevels(Map<String,String> values, Collection<String> keys) { throw new AssertionError(); }
            public boolean requestRunningSystemUpdate() { throw new AssertionError(); }
        });
    }
    public static class Factory {
        public boolean containsBeanDefinition(String name) { return true; }
    }
    public static class Context {
        final Factory factory; final ClassLoader loader; final boolean failLookup;
        int probes, lookups;
        Context(Factory factory, ClassLoader loader, boolean failLookup) {
            this.factory=factory; this.loader=loader; this.failLookup=failLookup;
        }
        public Factory getBeanFactory() { return factory; }
        public ClassLoader getClassLoader() { probes++; return loader; }
        public String[] getBeanNamesForType(Class<?> type) throws ClassNotFoundException {
            lookups++;
            if (failLookup) throw new ClassNotFoundException("owning lookup failure");
            return new String[0];
        }
    }
    private Context context(boolean invisible, boolean fail) {
        ClassLoader parent=getClass().getClassLoader();
        return new Context(new Factory(), invisible ? new ClassLoader(parent) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(TARGET)) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        } : parent, fail);
    }
    @Test void invisibleOnlyIsNotRestartConcern() {
        Context c=context(true,false);
        assertFalse(module(PlatformContext.Platform.HYBRIS).prepareInterceptor(TARGET,List.of(c)).complete());
        assertEquals(1,c.probes); assertEquals(0,c.lookups);
        assertEquals(0,RestartLedger.size()); assertTrue(messages.isEmpty(),messages.toString());
    }
    @Test void visibleWithoutLocalBeanRemainsFalseAndQuiet() {
        Context c=context(false,false);
        assertFalse(module(PlatformContext.Platform.HYBRIS).prepareInterceptor(TARGET,List.of(c)).complete());
        assertEquals(1,c.lookups); assertEquals(0,RestartLedger.size()); assertTrue(messages.isEmpty());
    }
    @Test void nestedClassNotFoundFromOwningLookupStaysLoudInBothOrders() {
        for(boolean reversed:List.of(false,true)) {
            RestartLedger.clear(); messages.clear();
            Context hidden=context(true,false),owner=context(false,true);
            assertFalse(module(PlatformContext.Platform.HYBRIS).prepareInterceptor(TARGET,
                    reversed?List.of(owner,hidden):List.of(hidden,owner)).complete());
            assertEquals(1,owner.lookups); assertEquals(0,hidden.lookups);
            assertEquals(1,RestartLedger.size());
            assertEquals(1,messages.size(),messages.toString());
            assertTrue(messages.get(0).contains("owning lookup failure"));
        }
    }
    @Test void noClassDefFoundErrorIsNotMistakenForNonOwner() {
        Context c=new Context(new Factory(),new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if(name.equals(TARGET)) throw new NoClassDefFoundError("missing-dependency");
                return super.loadClass(name,resolve);
            }
        },false);
        assertThrows(NoClassDefFoundError.class,()->module(PlatformContext.Platform.HYBRIS).prepareInterceptor(TARGET,List.of(c)));
        assertEquals(1,c.probes); assertEquals(0,c.lookups);
    }
    @Test void duplicateFactoryIsProbedOnce() {
        Context c=context(true,false);
        assertFalse(module(PlatformContext.Platform.HYBRIS).prepareInterceptor(TARGET,List.of(c,c)).complete());
        assertEquals(1,c.probes); assertEquals(0,RestartLedger.size()); assertTrue(messages.isEmpty());
    }
    @Test void nonSapKeepsNull() {
        Context c=context(false,true);
        assertNull(module(PlatformContext.Platform.GENERIC).prepareInterceptor(TARGET,List.of(c)));
        assertEquals(0,c.probes); assertTrue(messages.isEmpty());
    }
}
