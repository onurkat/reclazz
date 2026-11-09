/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.*;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedDefinitionPropertyEditTest {
    @TempDir Path dir;
    private final List<GenericApplicationContext> contexts = new ArrayList<>();
    public static class Bean {
        String value;
        Object target;
        boolean fail;
        public void setValue(String value) {
            if (fail) throw new IllegalArgumentException("synthetic owner setter failure");
            this.value = value;
        }
        public void setTarget(Object target) { this.target = target; }
    }
    public static class Consumer {
        String capturedValue;
        public void setProbe(Bean probe) { capturedValue = probe.value; }
    }
    @AfterEach void close() {
        contexts.forEach(GenericApplicationContext::close);
        ApplicationContextHolder.clear();
        RestartLedger.clear();
    }

    @Test void sharedDefinitionPropertyEditAppliesInEveryOwner() throws Exception {
        try (var s = new Scope(true, false, false)) { s.edit("new", "b"); s.assertValues("new", "b"); }
    }
    @Test void distinctDefinitionsStillApplyPerOwner() throws Exception {
        try (var s = new Scope(false, false, false)) { s.edit("new", "b"); s.assertValues("new", "b"); }
    }
    @Test void bothOwnerOrdersApply() throws Exception {
        try (var s = new Scope(true, false, true)) { s.edit("new", "b"); s.assertValues("new", "b"); }
    }
    @Test void fallbackDispatchAppliesSharedDefinitions() throws Exception {
        try (var s = new Scope(true, false, false)) {
            s.write("new", "b");
            Path fallback = dir.resolve("unobserved-spring.xml");
            Files.copy(s.xml, fallback);
            s.reloader.reload(fallback); s.assertValues("new", "b");
        }
    }
    @Test void sharedDefinitionOneOwnerFailsIsNotMirroredAndRetries() throws Exception {
        for (boolean reverse : List.of(false, true)) {
            try (var s = new Scope(true, false, reverse)) {
                Bean failed = s.bean(s.second);
                failed.fail = true;
                s.edit("new", "a");
                assertEquals("new", s.bean(s.first).value);
                assertEquals("old", failed.value);
                assertEquals("old", rawValue(s.first), "shared raw value must not hide failed owner");
                assertTrue(RestartLedger.digest().stream().anyMatch(x -> x.contains("property apply failed")));
                failed.fail = false;
                s.edit("new", "a"); s.assertValues("new", "a");
                assertEquals("new", rawValue(s.first));
                assertEquals(1, s.registrations.get(s.first).size());
                assertEquals(1, s.registrations.get(s.second).size());
            }
            ApplicationContextHolder.clear(); RestartLedger.clear();
        }
    }
    @Test void repeatEditAndRevertDoNotDuplicate() throws Exception {
        try (var s = new Scope(true, false, false)) {
            s.edit("new", "b"); s.assertValues("new", "b");
            s.edit("new", "b"); s.assertValues("new", "b");
            s.edit("old", "a"); s.assertValues("old", "a");
            for (var owner : List.of(s.first, s.second)) {
                assertEquals(1, s.registrations.get(owner).size());
                assertEquals(2, s.hookCalls.get(owner));
            }
        }
    }
    @Test void lazyOwnerIsNotCreatedForClassification() throws Exception {
        try (var s = new Scope(true, true, false)) {
            var merged = s.second.getBeanFactory().getMergedBeanDefinition("probe");
            s.edit("new", "b");
            assertFalse(s.second.getBeanFactory().containsSingleton("probe"));
            assertNotSame(merged, s.second.getBeanFactory().getMergedBeanDefinition("probe"));
            s.assertValues("new", "b");
        }
    }
    @Test void lazyPropertyEditPrecedesNewConsumerCreation() throws Exception {
        try (var s = new Scope(true, true, false)) {
            assertFalse(s.first.getBeanFactory().containsSingleton("probe"));
            assertFalse(s.second.getBeanFactory().containsSingleton("probe"));
            s.write("new", "b");
            Files.writeString(s.xml, Files.readString(s.xml).replace("</beans>",
                    "<bean id='consumer' class='" + Consumer.class.getName() + "'>"
                            + "<property name='probe' ref='probe'/></bean></beans>"));
            s.reloader.reloadLoaded(s.xml);
            for (var owner : List.of(s.first, s.second))
                assertEquals("new", owner.getBean("consumer", Consumer.class).capturedValue,
                        "native creation during this save must see the updated lazy definition");
            s.assertValues("new", "b");
        }
    }
    private static Object rawValue(GenericApplicationContext owner) {
        Object raw = owner.getBeanFactory().getBeanDefinition("probe").getPropertyValues().get("value");
        return raw instanceof org.springframework.beans.factory.config.TypedStringValue typed ? typed.getValue() : raw;
    }
    private final class Scope implements AutoCloseable {
        final Path xml = Files.createTempFile(dir, "shared-", "-spring.xml");
        final GenericApplicationContext first, second;
        final boolean lazy;
        final Map<Object, Set<Object>> registrations = new IdentityHashMap<>();
        final Map<Object, Integer> hookCalls = new IdentityHashMap<>();
        final SpringXmlReloader reloader;
        Scope(boolean shared, boolean lazy, boolean reverse) throws Exception {
            this.lazy = lazy;
            write("old", "a");
            first = load(null, lazy); second = load(shared ? first : null, lazy);
            for (var owner : reverse ? List.of(second, first) : List.of(first, second)) ApplicationContextHolder.register(owner);
            PlatformContext platform = (PlatformContext) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PlatformContext.class}, (p,m,a) -> m.getName().equals("getApplicationContext") ? first : null);
            XmlBeanLifecycle hook = (owner, name, previous) -> {
                hookCalls.merge(owner, 1, Integer::sum);
                return (current, applied) -> {
                    if (current != null && applied) registrations.computeIfAbsent(owner, ignored ->
                            Collections.newSetFromMap(new IdentityHashMap<>())).add(current);
                };
            };
            reloader = new SpringXmlReloader(platform, paths -> {}, hook);
        }
        GenericApplicationContext load(GenericApplicationContext share, boolean lazy) {
            var c = new GenericApplicationContext(); contexts.add(c);
            var reader = new XmlBeanDefinitionReader(c); reader.setValidating(false);
            var resource = new FileSystemResource(xml); reader.loadBeanDefinitions(resource);
            SpringXmlResources.record(reader, resource);
            c.getBeanFactory().getBeanDefinition("probe").setLazyInit(lazy);
            if (share != null) c.registerBeanDefinition("probe", share.getBeanFactory().getBeanDefinition("probe"));
            c.refresh(); return c;
        }
        Bean bean(GenericApplicationContext c) { return c.getBean("probe", Bean.class); }
        void write(String value, String target) throws Exception {
            Files.writeString(xml, "<beans xmlns='http://www.springframework.org/schema/beans'>"
                    + "<bean id='a' class='java.lang.Object'/><bean id='b' class='java.lang.Object'/>"
                    + "<bean id='probe' class='" + Bean.class.getName() + "' lazy-init='" + lazy + "'>"
                    + "<property name='value' value='" + value + "'/><property name='target' ref='" + target + "'/>"
                    + "</bean></beans>");
        }
        void edit(String value, String target) throws Exception { write(value, target); reloader.reloadLoaded(xml); }
        void assertValues(String value, String target) {
            for (var owner : List.of(first, second)) {
                assertEquals(value, bean(owner).value);
                assertSame(owner.getBean(target), bean(owner).target, "ref must resolve in its own owner");
            }
        }
        public void close() { first.close(); second.close(); }
    }
}
