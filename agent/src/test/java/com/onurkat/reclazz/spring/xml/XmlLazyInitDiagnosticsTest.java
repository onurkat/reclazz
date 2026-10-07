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
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class XmlLazyInitDiagnosticsTest {
    @TempDir Path tmp;
    public static class Target {
        static int created;
        String label;
        public Target() { created++; }
        public void setLabel(String value) { label = value; }
    }
    public static class Other {
        String label;
        public void setLabel(String value) { label = value; }
    }
    public static class UnavailableLazyMetadata extends org.springframework.beans.factory.support.RootBeanDefinition {
        @Override public boolean isLazyInit() { throw new IllegalStateException("lazy metadata unavailable"); }
    }
    @BeforeEach void reset() { ApplicationContextHolder.clear(); RestartLedger.clear(); Target.created = 0; }
    @AfterEach void clear() { ApplicationContextHolder.clear(); RestartLedger.clear(); }

    @ParameterizedTest
    @CsvSource({"false,true,false", "true,false,false", "true,false,true", "~,true,false", "true,~,false", "true,~,true"})
    void effectiveFlagChangesPreserveColdAndWarmBeansAndDefinitions(String before, String after, boolean warm) throws Exception {
        try (var s = new Scope(before, false, warm)) {
            Object raw = s.raw(), merged = s.merged();
            boolean instantiated = s.factory().containsSingleton("target");
            Target held = instantiated ? s.context.getBean("target", Target.class) : null;
            int created = Target.created;
            s.reload(after, false, "rejected", "after");
            s.warning(); s.preserved(raw, merged, before.equals("true"), "before");
            assertEquals(instantiated, s.factory().containsSingleton("target"));
            assertEquals(created, Target.created, "preflight must not instantiate a cold target");
            if (held != null) { assertSame(held, s.context.getBean("target")); assertEquals("before", held.label); }
            assertEquals("after", s.other.label);
            assertFalse(s.events.stream().anyMatch(v -> v.contains("Spring property updated: target.")), s.events.toString());
            // Explicit native creation after refusal still uses the original definition.
            assertEquals("before", s.context.getBean("target", Target.class).label);
        }
    }

    @ParameterizedTest
    @CsvSource({"~,false,false", "false,~,false", "~,default,false", "default,~,false", "~,true,true", "true,~,true", "default,true,true"})
    void nativeDefaultEquivalenceAllowsPropertyChanges(String before, String after, boolean rootLazy) throws Exception {
        try (var s = new Scope(before, rootLazy, false)) {
            assertEquals(rootLazy, s.raw().isLazyInit());
            int created = Target.created;
            s.reload(after, rootLazy, "after", "after"); s.quiet();
            assertEquals(rootLazy, s.raw().isLazyInit());
            assertEquals(created, Target.created);
            assertEquals("after", s.context.getBean("target", Target.class).label);
        }
    }

    @ParameterizedTest @CsvSource({"false,true", "true,false"})
    void changedRootDefaultUsesNativeParsedEffectiveFlag(boolean before, boolean after) throws Exception {
        try (var s = new Scope("~", before, false)) {
            Object raw = s.raw(), merged = s.merged(); int created = Target.created;
            s.reload("default", after, "rejected", "after"); s.warning();
            s.preserved(raw, merged, before, "before");
            assertEquals(created, Target.created); assertEquals("after", s.other.label);
        }
    }

    @Test void metadataOnlyRepeatNoOpAndRevertRemainBounded() throws Exception {
        try (var s = new Scope("true", false, false)) {
            Object raw = s.raw(), merged = s.merged();
            s.reload("true", false, "before", "before"); s.quiet(); s.preserved(raw, merged, true, "before");
            for (int n = 0; n < 2; n++) {
                s.reload("false", false, "before", "other" + n); s.warning();
                s.preserved(raw, merged, true, "before"); assertEquals(0, Target.created);
                assertTrue(s.events.stream().anyMatch(v -> v.contains("1 change applied, 1 require restart")), s.events.toString());
            }
            s.reload("true", false, "corrected", "final"); s.quiet();
            assertEquals(0, Target.created); assertFalse(s.factory().containsSingleton("target"));
            assertEquals("corrected", s.context.getBean("target", Target.class).label);
        }
    }

    @Test void bothOwningContextsAreGuardedWhileAnUnrelatedContextStaysUntouched() throws Exception {
        try (var s = new Scope("true", false, false); var second = load(s.xml); var unrelated = new GenericApplicationContext()) {
            unrelated.registerBean("target", Target.class, () -> { var v = new Target(); v.label = "isolated"; return v; });
            unrelated.refresh(); Target isolated = unrelated.getBean("target", Target.class);
            ApplicationContextHolder.register(second); ApplicationContextHolder.register(unrelated);
            Object raw = second.getBeanFactory().getBeanDefinition("target"), merged = second.getBeanFactory().getMergedBeanDefinition("target");
            int created = Target.created;
            s.reload("false", false, "rejected", "after"); s.warning();
            assertFalse(s.factory().containsSingleton("target")); assertFalse(second.getBeanFactory().containsSingleton("target"));
            assertSame(raw, second.getBeanFactory().getBeanDefinition("target")); assertSame(merged, second.getBeanFactory().getMergedBeanDefinition("target"));
            assertTrue(second.getBeanFactory().getBeanDefinition("target").isLazyInit());
            assertEquals("after", second.getBean("other", Other.class).label);
            assertSame(isolated, unrelated.getBean("target")); assertEquals("isolated", isolated.label); assertEquals(created, Target.created);
        }
    }

    @Test void lazyFlagJoinsOtherFieldsInOneFindingAndNewDefinitionsKeepTheirRoute() {
        var before = new org.springframework.beans.factory.support.RootBeanDefinition(Target.class);
        var after = new org.springframework.beans.factory.support.RootBeanDefinition(Target.class);
        after.setLazyInit(true); after.setPrimary(true);
        var diff = new BeanDefinitionDiff();
        assertTrue(XmlDefinitionMetadataGuard.classify("target", before, after, diff));
        assertEquals(1, diff.unsafe.size()); assertTrue(diff.unsafe.get(0).reason.contains("lazy-init"));
        assertTrue(diff.unsafe.get(0).reason.contains("primary")); assertFalse(before.isLazyInit());
        var added = new BeanDefinitionDiff(); assertFalse(XmlDefinitionMetadataGuard.classify("added", null, after, added));
        assertTrue(added.unsafe.isEmpty()); assertEquals(0, Target.created);
    }

    @Test void unavailableLazyMetadataFailsClosedBeforeAnyChanges() {
        var before = new org.springframework.beans.factory.support.RootBeanDefinition(Target.class);
        var diff = new BeanDefinitionDiff();
        assertTrue(XmlDefinitionMetadataGuard.classify("target", before, new UnavailableLazyMetadata(), diff));
        assertEquals(1, diff.unsafe.size()); assertTrue(diff.unsafe.get(0).reason.contains("cannot verify definition metadata"));
        assertTrue(diff.propertyChanges.isEmpty()); assertTrue(diff.recreated.isEmpty()); assertEquals(0, Target.created);
    }

    private static String lazy(String value) { return value.equals("~") ? "" : " lazy-init='" + value + "'"; }
    private static GenericApplicationContext load(Path xml) {
        var context = new GenericApplicationContext(); var reader = new XmlBeanDefinitionReader(context);
        reader.setValidating(false); var resource = new FileSystemResource(xml); reader.loadBeanDefinitions(resource);
        SpringXmlResources.record(reader, resource); context.refresh(); return context;
    }
    private final class Scope implements AutoCloseable {
        final Path xml; final GenericApplicationContext context; final SpringXmlReloader reloader;
        final Other other; final List<String> events = new ArrayList<>();
        Scope(String initial, boolean rootLazy, boolean warm) throws Exception {
            xml = Files.createTempFile(tmp, "lazy-", ".xml"); Files.writeString(xml, document(initial, rootLazy, "before", "before"));
            context = load(xml); other = context.getBean("other", Other.class);
            if (warm) context.getBean("target");
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PlatformContext.class},
                    (p, m, a) -> { if (m.getName().equals("getApplicationContext")) return context; throw new UnsupportedOperationException(m.getName()); });
            reloader = new SpringXmlReloader(platform);
        }
        String document(String value, boolean rootLazy, String label, String sibling) {
            return "<beans xmlns='http://www.springframework.org/schema/beans' default-lazy-init='" + rootLazy + "'>"
                    + "<bean id='target' class='" + Target.class.getName() + "'" + lazy(value) + "><property name='label' value='" + label + "'/></bean>"
                    + "<bean id='other' class='" + Other.class.getName() + "' lazy-init='false'><property name='label' value='" + sibling + "'/></bean></beans>";
        }
        org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory() { return context.getBeanFactory(); }
        AbstractBeanDefinition raw() { return (AbstractBeanDefinition) factory().getBeanDefinition("target"); }
        Object merged() { return factory().getMergedBeanDefinition("target"); }
        void reload(String value, boolean rootLazy, String label, String sibling) throws Exception {
            Files.writeString(xml, document(value, rootLazy, label, sibling)); events.clear(); RestartLedger.clear();
            StatusReporter.StatusListener listener = (level, message) -> events.add(level + ":" + message);
            StatusReporter.addListener(listener); try { reloader.reload(xml); } finally { StatusReporter.removeListener(listener); }
            assertSame(other, context.getBean("other"));
        }
        void warning() {
            assertTrue(events.stream().anyMatch(v -> v.contains("Restart required") && v.contains("target") && v.contains("lazy-init")), events.toString());
            assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains("target") && v.contains("lazy-init")), RestartLedger.digest().toString());
        }
        void quiet() { assertFalse(events.stream().anyMatch(v -> v.contains("Restart required")), events.toString()); }
        void preserved(Object raw, Object merged, boolean flag, String label) {
            assertSame(raw, raw()); assertSame(merged, merged()); assertEquals(flag, raw().isLazyInit());
            assertEquals(flag, ((AbstractBeanDefinition) merged).isLazyInit());
            assertEquals(label, ((org.springframework.beans.factory.config.TypedStringValue) raw().getPropertyValues().get("label")).getValue());
            assertEquals(label, ((org.springframework.beans.factory.config.TypedStringValue) ((AbstractBeanDefinition) merged).getPropertyValues().get("label")).getValue());
        }
        public void close() { context.close(); }
    }
}
