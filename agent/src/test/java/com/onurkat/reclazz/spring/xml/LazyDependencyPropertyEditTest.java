/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.platform.SpringXmlResources;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LazyDependencyPropertyEditTest {
    @TempDir Path dir;
    public static class Settings {
        String value;
        boolean fail;
        Runnable onSet = () -> {};
        public void setValue(String value) {
            onSet.run();
            if (fail) throw new IllegalArgumentException("synthetic setter failure");
            this.value = value;
        }
    }
    public static class Consumer {
        String captured;
        public void setTarget(Settings target) { captured = target.value; }
    }
    @BeforeEach @AfterEach void clear() { ApplicationContextHolder.clear(); RestartLedger.clear(); }

    @Test void existingConsumerToEditedLazyDependencySeesNewData() throws Exception {
        try (var s = new Scope(1, false, false, false, false, false)) { s.edit(); s.assertNew(); }
    }

    @ParameterizedTest(name="shared={0}, reverseOwners={1}, consumerFirst={2}, mixed={3}")
    @CsvSource({
        "false,false,false,false", "false,true,false,false", "true,false,false,false", "true,true,false,false",
        "false,false,true,false", "false,true,true,false", "true,false,true,false", "true,true,true,false",
        "false,false,false,true", "false,true,false,true", "true,false,false,true", "true,true,false,true",
        "false,false,true,true", "false,true,true,true", "true,false,true,true", "true,true,true,true"
    })
    void existingConsumersUseEditedDependencyInEveryOwner(boolean shared, boolean reverse, boolean consumerFirst,
                                                         boolean mixed) throws Exception {
        try (var s = new Scope(2, shared, reverse, consumerFirst, mixed, false)) { s.edit(); s.assertNew(); }
    }

    @Test void primedCacheConsumerBeforeDependencySeesNewDataInEveryOwner() throws Exception {
        try (var s = new Scope(2, true, false, true, false, false)) { s.edit(); s.assertNew(); }
    }
    @Test void primedCacheDependencyBeforeConsumerSeesNewDataInEveryOwner() throws Exception {
        try (var s = new Scope(2, true, true, false, false, false)) { s.edit(); s.assertNew(); }
    }
    @Test void unchangedSaveAfterLazyEditStaysCorrect() throws Exception {
        try (var s = new Scope(2, true, false, false, false, false)) {
            s.edit(); s.reloader.reloadLoaded(s.xml); s.assertNew();
        }
    }
    @Test void fallbackDispatchResolvesEditedLazyDependency() throws Exception {
        try (var s = new Scope(2, true, true, true, false, false)) {
            s.write("new", "lazy");
            Path fallback = dir.resolve("fallback-spring.xml"); Files.copy(s.xml, fallback);
            s.reloader.reload(fallback); s.assertNew();
        }
    }
    @Test void unusedLazyBeanIsNotCreated() throws Exception {
        try (var s = new Scope(2, true, false, true, false, false)) {
            s.write("new", "other"); s.reloader.reloadLoaded(s.xml);
            for (var c : s.owners) {
                assertFalse(c.getBeanFactory().containsSingleton("lazy"));
                assertEquals("new", raw(c.getBeanFactory().getMergedBeanDefinition("lazy").getPropertyValues().get("value")));
            }
        }
    }
    @Test void failedOwnerRollsBackEveryMergedCacheAndRetries() throws Exception {
        try (var s = new Scope(2, true, false, false, false, true)) {
            Settings failed = s.owners.get(1).getBean("lazy", Settings.class);
            for (var c : s.owners) c.getBean("lazy", Settings.class).onSet = () ->
                    c.getBeanFactory().getMergedBeanDefinition("lazy");
            failed.fail = true;
            s.write("new", "other"); s.reloader.reloadLoaded(s.xml);
            for (var c : s.owners) {
                assertEquals("old", raw(c.getBeanFactory().getBeanDefinition("lazy").getPropertyValues().get("value")));
                assertEquals("old", raw(c.getBeanFactory().getMergedBeanDefinition("lazy").getPropertyValues().get("value")),
                        "rollback must invalidate caches populated during apply in every owner");
            }
            assertTrue(RestartLedger.digest().stream().anyMatch(x -> x.contains("property apply failed")));
            failed.fail = false; s.edit(); s.assertNew();
        }
    }
    @Test void successfulInitializedPropertyEditKeepsMergedIdentity() throws Exception {
        try (var s = new Scope(1, false, false, false, false, true)) {
            var c = s.owners.get(0);
            Object before = c.getBeanFactory().getMergedBeanDefinition("lazy");
            s.write("new", "other"); s.reloader.reloadLoaded(s.xml);
            assertEquals("new", c.getBean("lazy", Settings.class).value);
            assertSame(before, c.getBeanFactory().getMergedBeanDefinition("lazy"),
                    "successful ordinary edits retain the initialized bean's merged definition identity");
        }
    }

    private static Object raw(Object v) { return v instanceof TypedStringValue t ? t.getValue() : v; }
    private final class Scope implements AutoCloseable {
        final Path xml;
        final List<GenericApplicationContext> owners = new ArrayList<>();
        final boolean consumerFirst;
        final SpringXmlReloader reloader;
        Scope(int count, boolean shared, boolean reverse, boolean consumerFirst, boolean mixed, boolean initializeAll)
                throws Exception {
            this.consumerFirst = consumerFirst;
            xml = Files.createTempFile(dir, "lazy-", "-spring.xml"); write("old", "other");
            for (int i=0;i<count;i++) {
                var c = new GenericApplicationContext(); owners.add(c);
                var reader = new XmlBeanDefinitionReader(c); reader.setValidating(false);
                var resource = new FileSystemResource(xml); reader.loadBeanDefinitions(resource);
                SpringXmlResources.record(reader, resource);
                if (shared && i>0) c.registerBeanDefinition("lazy", owners.get(0).getBeanFactory().getBeanDefinition("lazy"));
                c.refresh();
                if (initializeAll || (mixed && i==0)) c.getBean("lazy");
                c.getBeanFactory().getMergedBeanDefinition("lazy");
            }
            var order = new ArrayList<>(owners); if(reverse) Collections.reverse(order);
            order.forEach(ApplicationContextHolder::register);
            PlatformContext platform = (PlatformContext) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PlatformContext.class}, (p,m,a) -> m.getName().equals("getApplicationContext") ? owners.get(0) : null);
            reloader = new SpringXmlReloader(platform);
        }
        void write(String value, String ref) throws Exception {
            String lazy = "<bean id='lazy' class='"+Settings.class.getName()+"' lazy-init='true'><property name='value' value='"+value+"'/></bean>";
            String consumer = "<bean id='consumer' class='"+Consumer.class.getName()+"'><property name='target' ref='"+ref+"'/></bean>";
            Files.writeString(xml, "<beans xmlns='http://www.springframework.org/schema/beans'>"
                    +"<bean id='other' class='"+Settings.class.getName()+"'><property name='value' value='other'/></bean>"
                    +(consumerFirst ? consumer+lazy : lazy+consumer)+"</beans>");
        }
        void edit() throws Exception { write("new", "lazy"); reloader.reloadLoaded(xml); }
        void assertNew() {
            for (var c : owners) {
                assertEquals("new", c.getBean("consumer", Consumer.class).captured, "consumer captured stale dependency");
                assertEquals("new", c.getBean("lazy", Settings.class).value);
            }
        }
        public void close() { owners.forEach(GenericApplicationContext::close); }
    }
}
