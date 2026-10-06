/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.platform.SpringXmlResources;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class XmlFactoryBeanProductDiagnosticsTest {
    @TempDir Path tmp;
    public record Product(String label) { }
    public static class Factory implements FactoryBean<Product> {
        static int constructions, objects, types, singletonQueries, writes, coldInitializations;
        String label;
        boolean productSingleton = true;
        public Factory() { constructions++; }
        public void setLabel(String value) { writes++; label = value; }
        public void setProductSingleton(boolean value) { productSingleton = value; }
        public Product getObject() { objects++; return new Product(label); }
        public Class<?> getObjectType() { types++; return Product.class; }
        public boolean isSingleton() { singletonQueries++; return productSingleton; }
    }
    public static class ColdFactory extends Factory { static { coldInitializations++; } }
    public static class Settings {
        String label;
        public void setLabel(String value) { label = value; }
    }
    @BeforeEach void reset() { ApplicationContextHolder.clear(); RestartLedger.clear();
        Factory.constructions = Factory.objects = Factory.types = Factory.singletonQueries = Factory.writes = 0; }
    @AfterEach void clear() { ApplicationContextHolder.clear(); RestartLedger.clear(); }

    @ParameterizedTest @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void warmedAndUnwarmedProductsRefuseBeforeMutatingFactory(boolean warm, boolean singleton) throws Exception {
        try (var s = new Scope(false, singleton, Factory.class, "")) {
            Factory held = (Factory) s.context.getBean("&factory");
            Product product = warm ? s.context.getBean("factory", Product.class) : null;
            int[] counts = counts(); Object raw = s.raw(), merged = s.merged();
            s.reload("after", "other", false); s.warning(); s.preserved(raw, merged);
            assertArrayEquals(counts, counts(), "classification must never query/create product or call factory setters");
            assertSame(held, s.context.getBean("&factory")); assertEquals("before", held.label);
            Product current = s.context.getBean("factory", Product.class);
            assertEquals("before", current.label());
            if (warm && singleton) assertSame(product, current);
            if (warm && !singleton) assertNotSame(product, current);
            assertEquals("other", s.settings.label);
            assertFalse(s.events.stream().anyMatch(v -> v.contains("Spring property updated: factory.")), s.events.toString());
        }
    }
    @Test void lazyFactoryStaysUncreatedAndItsClassIsNotInitialized() throws Exception {
        int initialized = Factory.coldInitializations;
        try (var s = new Scope(true, true, ColdFactory.class, "")) {
            int[] counts = counts(); Object raw = s.raw(), merged = s.merged();
            s.reload("after", "other", false); s.warning(); s.preserved(raw, merged);
            assertFalse(s.context.getBeanFactory().containsSingleton("factory"));
            assertArrayEquals(counts, counts()); assertEquals(initialized, Factory.coldInitializations);
            assertEquals("before", s.context.getBean("factory", Product.class).label());
        }
    }
    @Test void inheritedFactoryTypeAlsoRequiresRestart() throws Exception {
        try (var s = new Scope(true, true, Factory.class, "parent")) {
            Object raw = s.raw(), merged = s.merged();
            s.reload("after", "other", false); s.warning(); s.preserved(raw, merged);
            assertFalse(s.context.getBeanFactory().containsSingleton("factory"));
            assertEquals("before", s.context.getBean("factory", Product.class).label());
        }
    }
    @Test void repeatedMixedEditsAndRevertKeepCachedProductAndDefinition() throws Exception {
        try (var s = new Scope(false, true, Factory.class, "")) {
            Product product = s.context.getBean("factory", Product.class);
            Factory held = (Factory) s.context.getBean("&factory");
            Object raw = s.raw(), merged = s.merged(); int[] counts = counts();
            for (int i = 0; i < 3; i++) {
                s.reload("after" + i, "other" + i, false); s.warning(); s.preserved(raw, merged);
                assertEquals("other" + i, s.settings.label);
                assertSame(product, s.context.getBean("factory")); assertSame(held, s.context.getBean("&factory"));
                // getBean itself may query isSingleton; the reload must not.
                counts = counts();
            }
            s.reload("before", "final", false); s.noWarning(); s.preserved(raw, merged);
            assertArrayEquals(counts, counts()); assertSame(product, s.context.getBean("factory"));
        }
    }
    @Test void unchangedFactoryIsQuietWhileOrdinarySiblingUpdates() throws Exception {
        try (var s = new Scope(false, true, Factory.class, "")) {
            Object raw = s.raw(), merged = s.merged(); int[] counts = counts();
            s.reload("before", "other", false); s.noWarning(); s.preserved(raw, merged);
            assertArrayEquals(counts, counts()); assertEquals("other", s.settings.label);
        }
    }
    @Test void propertyRemovalRefusesOtherFactoryPropertiesToo() throws Exception {
        try (var s = new Scope(false, true, Factory.class, "")) {
            Object raw = s.raw(), merged = s.merged(); int[] counts = counts();
            s.reload("ignored", "other", true); s.warning(); s.preserved(raw, merged);
            assertArrayEquals(counts, counts());
        }
    }
    @Test void newFactoryDefinitionKeepsNativeCreation() throws Exception {
        try (var s = new Scope(false, true, Factory.class, "")) {
            s.extra = "<bean id='added' class='" + Factory.class.getName() + "'><property name='label' value='new'/></bean>";
            s.reload("before", "other", false); s.noWarning();
            assertEquals("new", s.context.getBean("added", Product.class).label());
        }
    }
    @Test void prototypeFactoryStillUsesExistingScopeRefusal() throws Exception {
        try (var s = new Scope(true, true, Factory.class, "prototype")) {
            int[] counts = counts(); s.reload("after", "other", false);
            assertTrue(s.events.stream().anyMatch(v -> v.contains("Restart required") && v.contains("scope=prototype")), s.events.toString());
            int[] after = counts();
            assertEquals(counts[0], after[0]); assertEquals(counts[1], after[1]); assertEquals(counts[4], after[4]);
            assertEquals("before", s.context.getBean("factory", Product.class).label());
        }
    }
    public static class UnverifiableFactory {
        public Object getSingleton(String name) { return null; }
        public Object getMergedBeanDefinition(String name) { throw new IllegalStateException("metadata unavailable"); }
    }
    @Test void unavailableTypeIsRefusedWithoutAssumingOrdinaryBean() {
        var diff = new BeanDefinitionDiff();
        assertTrue(XmlFactoryBeanPropertyGuard.classify(new UnverifiableFactory(), "unknown", new Object(), new Object(), diff));
        assertEquals(1, diff.unsafe.size()); assertTrue(diff.unsafe.get(0).reason.contains("cannot verify FactoryBean"));
        assertTrue(diff.propertyChanges.isEmpty()); assertTrue(diff.recreated.isEmpty());
    }
    @Test void ordinaryBootstrapLoadedClassDoesNotNeedSpringInItsOwnLoader() {
        try (var context = new GenericApplicationContext()) {
            context.registerBean("ordinary", Object.class); context.refresh();
            Object definition = context.getBeanFactory().getBeanDefinition("ordinary");
            var diff = new BeanDefinitionDiff();
            assertFalse(XmlFactoryBeanPropertyGuard.classify(context.getBeanFactory(), "ordinary", definition, definition, diff));
            assertFalse(diff.hasChanges());
        }
    }
    private static int[] counts() { return new int[]{Factory.constructions, Factory.objects, Factory.types, Factory.singletonQueries, Factory.writes}; }
    private final class Scope implements AutoCloseable {
        final Path xml;
        final boolean lazy, singleton;
        final Class<?> type;
        final String mode;
        final GenericApplicationContext context;
        final Settings settings;
        final Object stableFactory, stableProduct;
        final SpringXmlReloader reloader;
        final List<String> events = new ArrayList<>();
        String extra = "";
        Scope(boolean lazy, boolean singleton, Class<?> type, String mode) throws Exception {
            this.lazy = lazy; this.singleton = singleton; this.type = type; this.mode = mode;
            xml = Files.createTempFile(tmp, "factory-", ".xml"); Files.writeString(xml, document("before", "before", false));
            context = new GenericApplicationContext(); var reader = new XmlBeanDefinitionReader(context); reader.setValidating(false);
            var resource = new FileSystemResource(xml); reader.loadBeanDefinitions(resource); SpringXmlResources.record(reader, resource);
            context.refresh(); settings = context.getBean("settings", Settings.class);
            stableFactory = context.getBean("&stable"); stableProduct = context.getBean("stable");
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PlatformContext.class},
                    (proxy, method, args) -> { if (method.getName().equals("getApplicationContext")) return context;
                        throw new UnsupportedOperationException(method.getName()); });
            reloader = new SpringXmlReloader(platform);
        }
        String document(String label, String other, boolean removed) {
            return "<beans xmlns='http://www.springframework.org/schema/beans'>"
                    + (mode.equals("parent") ? "<bean id='template' abstract='true' class='" + type.getName() + "'/>" : "")
                    + "<bean id='factory' " + (mode.equals("parent") ? "parent='template'" : "class='" + type.getName() + "'")
                    + " lazy-init='" + lazy + "'" + (mode.equals("prototype") ? " scope='prototype'" : "") + ">"
                    + (removed ? "" : "<property name='label' value='" + label + "'/>")
                    + "<property name='productSingleton' value='" + (removed ? !singleton : singleton) + "'/></bean>"
                    + "<bean id='stable' class='" + Factory.class.getName() + "'><property name='label' value='stable'/></bean>"
                    + "<bean id='settings' class='" + Settings.class.getName() + "'><property name='label' value='" + other + "'/></bean>"
                    + extra + "</beans>";
        }
        AbstractBeanDefinition raw() { return (AbstractBeanDefinition) context.getBeanFactory().getBeanDefinition("factory"); }
        Object merged() { return context.getBeanFactory().getMergedBeanDefinition("factory"); }
        void reload(String label, String other, boolean removed) throws Exception {
            Files.writeString(xml, document(label, other, removed)); events.clear(); RestartLedger.clear();
            StatusReporter.StatusListener listener = (level, message) -> events.add(level + ":" + message);
            StatusReporter.addListener(listener); try { reloader.reload(xml); } finally { StatusReporter.removeListener(listener); }
            assertSame(settings, context.getBean("settings"));
            // Read the singleton cache directly; do not introduce product queries into preflight-count assertions.
            assertSame(stableFactory, context.getBeanFactory().getSingleton("stable"));
        }
        void preserved(Object raw, Object merged) {
            assertSame(raw, raw()); assertSame(merged, merged());
            assertEquals("before", ((TypedStringValue) raw().getPropertyValues().get("label")).getValue());
            assertEquals("before", ((TypedStringValue) ((AbstractBeanDefinition) merged()).getPropertyValues().get("label")).getValue());
        }
        void warning() {
            assertTrue(events.stream().anyMatch(v -> v.contains("Restart required") && v.contains("factory") && v.contains("FactoryBean")), events.toString());
            assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains("factory") && v.contains("FactoryBean")), RestartLedger.digest().toString());
            assertTrue(events.stream().anyMatch(v -> v.contains("1 change applied, 1 require restart")), events.toString());
        }
        void noWarning() { assertFalse(events.stream().anyMatch(v -> v.contains("Restart required")), events.toString()); }
        public void close() { assertSame(stableProduct, context.getBean("stable")); context.close(); }
    }
}
