/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class XmlInnerBeanValueComparisonTest {
    @TempDir Path tmp;
    public static class Stage {
        String value;
        public Stage() { }
        public Stage(String value) { this.value = value; }
        public void setValue(String value) { this.value = value; }
    }
    public static class ResourceStage extends Stage implements InitializingBean, DisposableBean {
        static int created, initialized, destroyed;
        public ResourceStage() { created++; }
        public void afterPropertiesSet() { initialized++; }
        public void destroy() { destroyed++; }
    }
    public static class CallbackStage extends Stage {
        static int initialized, destroyed;
        public void start() { initialized++; }
        public void stop() { destroyed++; }
    }
    public static class Owner {
        Object stage;
        List<?> stages;
        Map<?,?> entries;
        String label;
        public void setStage(Object value) { stage = value; }
        public void setStages(List<?> value) { stages = value; }
        public void setEntries(Map<?,?> value) { entries = value; }
        public void setLabel(String value) { label = value; }
        Object selected(String shape) {
            return switch (shape) { case "direct" -> stage; case "list" -> stages.get(0); default -> entries.get("key"); };
        }
    }
    @BeforeEach void reset() {
        ApplicationContextHolder.clear(); RestartLedger.clear();
        ResourceStage.created = ResourceStage.initialized = ResourceStage.destroyed = 0;
        CallbackStage.initialized = CallbackStage.destroyed = 0;
    }
    @AfterEach void clear() { ApplicationContextHolder.clear(); RestartLedger.clear(); }

    @ParameterizedTest @ValueSource(strings = {"direct", "list", "map"})
    void namedInnerEditsAreDetectedAndRepeatedSameSaveIsNoOp(String shape) throws Exception {
        try (var s = new Scope(property(shape, inner(Stage.class, "inner", "old", "")), false)) {
            Owner owner = s.bean(); Object before = owner.selected(shape);
            s.reload(property(shape, inner(Stage.class, "inner", "new", "")), "after", "after");
            assertSame(owner, s.bean()); assertNotSame(before, owner.selected(shape));
            assertEquals("new", ((Stage) owner.selected(shape)).value); s.noWarning();
            Object applied = owner.selected(shape), raw = s.raw(propertyName(shape));
            s.reload(property(shape, inner(Stage.class, "inner", "new", "")), "after", "after");
            assertSame(applied, owner.selected(shape)); assertSame(raw, s.raw(propertyName(shape))); s.noWarning();
        }
    }
    @ParameterizedTest @ValueSource(strings = {"direct", "list", "map"})
    void anonymousResourceNoOpPreservesIdentityAndLifecycleCounts(String shape) throws Exception {
        Scope s = new Scope(property(shape, inner(ResourceStage.class, null, "old", "")), false);
        try {
            Owner owner = s.bean(); Object stage = owner.selected(shape), raw = s.raw(propertyName(shape));
            for (int i = 0; i < 3; i++) {
                s.reload(property(shape, inner(ResourceStage.class, null, "old", "")), "label" + i, "other" + i);
                assertSame(owner, s.bean()); assertSame(stage, owner.selected(shape)); assertSame(raw, s.raw(propertyName(shape)));
                assertEquals(1, ResourceStage.created); assertEquals(1, ResourceStage.initialized); assertEquals(0, ResourceStage.destroyed);
                assertEquals("label" + i, owner.label); assertEquals("other" + i, s.other.label); s.noWarning();
            }
        } finally { s.close(); }
        assertEquals(1, ResourceStage.destroyed, "only the original resource is closed by Spring");
    }
    @ParameterizedTest @ValueSource(strings = {"direct", "list", "map"})
    void simpleAnonymousEditsStillApply(String shape) throws Exception {
        try (var s = new Scope(property(shape, inner(Stage.class, null, "old", "")), false)) {
            s.reload(property(shape, inner(Stage.class, null, "new", "")), "after", "after");
            assertEquals("new", ((Stage) s.bean().selected(shape)).value); s.noWarning();
        }
    }
    @ParameterizedTest @ValueSource(strings = {"direct", "list", "map"})
    void changedResourceCompositionRefusesWholeOwnerBeforeAnyMutation(String shape) throws Exception {
        Scope s = new Scope(property(shape, inner(ResourceStage.class, "resource", "old", "")), false);
        try {
            Owner owner = s.bean(); Object stage = owner.selected(shape), raw = s.raw(propertyName(shape));
            for (int i = 0; i < 2; i++) {
                s.reload(property(shape, inner(ResourceStage.class, "resource", "new", "")), "rejected", "other" + i);
                assertSame(owner, s.bean()); assertSame(stage, owner.selected(shape)); assertSame(raw, s.raw(propertyName(shape)));
                assertEquals("old", ((Stage) stage).value); assertEquals("before", owner.label);
                assertEquals("other" + i, s.other.label); assertEquals(1, ResourceStage.created);
                assertEquals(1, ResourceStage.initialized); assertEquals(0, ResourceStage.destroyed); s.warning();
            }
        } finally { s.close(); }
        assertEquals(1, ResourceStage.destroyed);
    }
    @Test void explicitLifecycleChangeRefusesOldAndNewResourceTypes() throws Exception {
        try (var s = new Scope(property("direct", inner(CallbackStage.class, null, "old", "init-method='start' destroy-method='stop'")), false)) {
            Object before = s.bean().stage;
            s.reload(property("direct", inner(Stage.class, null, "new", "")), "rejected", "after");
            assertSame(before, s.bean().stage); assertEquals("before", s.bean().label);
            assertEquals(1, CallbackStage.initialized); assertEquals(0, CallbackStage.destroyed); s.warning();
        }
        assertEquals(1, CallbackStage.destroyed);
        try (var s = new Scope(property("direct", inner(Stage.class, null, "old", "")), false)) {
            Object before = s.bean().stage;
            s.reload(property("direct", inner(CallbackStage.class, null, "new", "init-method='start' destroy-method='stop'")), "rejected", "after");
            assertSame(before, s.bean().stage); assertEquals(1, CallbackStage.initialized); s.warning();
        }
    }
    @Test void lazyResourceEditKeepsRawMetadataAndDoesNotInstantiate() throws Exception {
        try (var s = new Scope(property("direct", inner(ResourceStage.class, null, "old", "")), true)) {
            Object raw = s.raw("stage");
            s.reload(property("direct", inner(ResourceStage.class, null, "new", "")), "rejected", "after");
            assertSame(raw, s.raw("stage")); assertFalse(s.context.getBeanFactory().containsSingleton("owner"));
            assertEquals(0, ResourceStage.created); s.warning();
            assertEquals("old", ((Stage) s.bean().stage).value);
        }
        assertEquals(1, ResourceStage.destroyed);
    }
    @Test void changedOuterCollectionWithUnchangedResourceStillRefusesReconstruction() throws Exception {
        String resource = inner(ResourceStage.class, null, "old", "");
        try (var s = new Scope("<property name='stages'><list>" + resource + "<value>old</value></list></property>", false)) {
            Object before = s.bean().stages;
            s.reload("<property name='stages'><list>" + resource + "<value>new</value></list></property>", "rejected", "after");
            assertSame(before, s.bean().stages); assertEquals(1, ResourceStage.created); s.warning();
        }
    }
    @Test void innerConstructorValuesAreComparedAndPlainNativeConstructionStillWorks() throws Exception {
        String format = "<property name='stage'><bean id='inner' class='" + Stage.class.getName() + "'><constructor-arg value='%s'/></bean></property>";
        try (var s = new Scope(format.formatted("old"), false)) {
            s.reload(format.formatted("new"), "after", "after");
            assertEquals("new", ((Stage) s.bean().stage).value); s.noWarning();
        }
    }
    @Test void unsupportedInnerScopeIsRefused() throws Exception {
        try (var s = new Scope(property("direct", inner(Stage.class, "inner", "old", "")), false)) {
            Object before = s.bean().stage;
            s.reload(property("direct", inner(Stage.class, "inner", "new", "scope='prototype'")), "rejected", "after");
            assertSame(before, s.bean().stage); assertEquals("before", s.bean().label); s.warning();
        }
    }
    @Test void sameNameLocalToParentReferenceIsNotMissed() throws Exception {
        try (var parent = new GenericApplicationContext()) {
            parent.registerBean("choice", Stage.class, () -> new Stage("parent")); parent.refresh();
            try (var s = new Scope("<property name='stage'><ref bean='choice'/></property>", false, parent)) {
                assertEquals("local", ((Stage) s.bean().stage).value);
                s.reload("<property name='stage'><ref parent='choice'/></property>", "after", "after");
                assertSame(parent.getBean("choice"), s.bean().stage); s.noWarning();
                s.reload("<property name='stage'><ref bean='choice'/></property>", "after", "after");
                assertSame(s.context.getBean("choice"), s.bean().stage); s.noWarning();
            }
        }
    }
    @Test void deeplyNestedValueRefusesBeforeUpdatingSiblingProperty() throws Exception {
        String deep = "<value>inside</value>";
        for (int i = 0; i < 36; i++) deep = "<list>" + deep + "</list>";
        try (var s = new Scope("<property name='stage'><value>before</value></property>", false)) {
            s.reload("<property name='stage'>" + deep + "</property>", "rejected", "after");
            assertEquals("before", s.bean().stage); assertEquals("before", s.bean().label); s.warning();
        }
    }
    @Test void mergedContainerIgnoresGeneratedInnerNamesButChangedContentsStillWarn() throws Exception {
        String base = "<bean id='base' abstract='true' class='" + Owner.class.getName() + "'><property name='stages'><list><value>base</value></list></property></bean>";
        String format = base + "<bean id='derived' parent='base'><property name='stages'><list merge='true'>"
                + inner(Stage.class, null, "%s", "") + "</list></property></bean>";
        try (var s = new Scope("", false, format.formatted("old"))) {
            Owner derived = s.context.getBean("derived", Owner.class); Object original = derived.stages;
            s.extra = format.formatted("old"); s.reload("", "after", "after");
            assertSame(original, derived.stages);
            assertFalse(s.events.stream().anyMatch(v -> v.contains("merged collection")), s.events.toString());
            s.extra = format.formatted("new"); s.reload("", "after", "after");
            assertSame(original, derived.stages);
            assertTrue(s.events.stream().anyMatch(v -> v.contains("merged collection")), s.events.toString());
        }
    }
    @Test void emptyMergedCollectionTypeMetadataCannotReplaceInheritedValues() throws Exception {
        String base = "<bean id='base' abstract='true' class='" + Owner.class.getName()
                + "'><property name='stages'><list><value>base</value></list></property></bean>";
        String format = base + "<bean id='derived' parent='base'><property name='stages'>"
                + "<list merge='true' value-type='%s'/></property></bean>";
        try (var s = new Scope("", false, format.formatted("java.lang.String"))) {
            Owner derived = s.context.getBean("derived", Owner.class); Object original = derived.stages;
            assertEquals(List.of("base"), original);
            s.extra = format.formatted("java.lang.Object"); s.reload("", "after", "after");
            assertSame(original, derived.stages); assertEquals(List.of("base"), derived.stages);
            assertTrue(s.events.stream().anyMatch(v -> v.contains("merged collection")), s.events.toString());
        }
    }
    @Test void typedResolutionAndSourceAndHolderNamesDoNotCauseFalseChanges() throws Exception {
        var old = new org.springframework.beans.factory.support.GenericBeanDefinition();
        old.setBeanClass(Stage.class); old.setSource("old source");
        old.getPropertyValues().add("value", new org.springframework.beans.factory.config.TypedStringValue("same", String.class));
        var next = new org.springframework.beans.factory.support.GenericBeanDefinition();
        next.setBeanClassName(Stage.class.getName()); next.setSource("new source");
        next.getPropertyValues().add("value", new org.springframework.beans.factory.config.TypedStringValue("same", String.class.getName()));
        var comparator = new XmlRawValueComparator(getClass().getClassLoader());
        assertTrue(comparator.same(new org.springframework.beans.factory.config.BeanDefinitionHolder(old, "generated1"),
                new org.springframework.beans.factory.config.BeanDefinitionHolder(next, "generated2"), getClass().getClassLoader()));
    }
    @Test void unknownRawTypesDoNotFallBackToStringAndCyclesAndWidthAreBounded() throws Exception {
        var comparator = new XmlRawValueComparator(getClass().getClassLoader());
        Object unknown = new Object() { public String toString() { throw new AssertionError("must not use toString"); } };
        assertThrows(IllegalArgumentException.class, () -> comparator.semantic(unknown));
        List<Object> cycle = new ArrayList<>(); cycle.add(cycle);
        assertThrows(IllegalArgumentException.class, () -> comparator.semantic(cycle));
        assertThrows(IllegalArgumentException.class, () -> comparator.semantic(Collections.nCopies(5000, "value")));
    }
    private static String propertyName(String shape) { return switch (shape) { case "direct" -> "stage"; case "list" -> "stages"; default -> "entries"; }; }
    private static String property(String shape, String inner) {
        String value = switch (shape) { case "direct" -> inner; case "list" -> "<list>" + inner + "</list>"; default -> "<map><entry key='key'>" + inner + "</entry></map>"; };
        return "<property name='" + propertyName(shape) + "'>" + value + "</property>";
    }
    private static String inner(Class<?> type, String id, String value, String attrs) {
        return "<bean " + (id == null ? "" : "id='" + id + "' ") + "class='" + type.getName() + "' " + attrs + "><property name='value' value='" + value + "'/></bean>";
    }
    private final class Scope implements AutoCloseable {
        final Path xml;
        final boolean lazy;
        final GenericApplicationContext context;
        final SpringXmlReloader reloader;
        final Owner other;
        final List<String> events = new ArrayList<>();
        String extra;
        Scope(String properties, boolean lazy, GenericApplicationContext... parents) throws Exception {
            this(properties, lazy, "", parents);
        }
        Scope(String properties, boolean lazy, String extra, GenericApplicationContext... parents) throws Exception {
            this.extra = extra;
            this.lazy = lazy; context = new GenericApplicationContext();
            if (parents.length != 0) context.setParent(parents[0]);
            xml = Files.createTempFile(tmp, "inner-", ".xml"); Files.writeString(xml, document(properties, "before", "before"));
            var reader = new XmlBeanDefinitionReader(context); reader.setValidating(false);
            reader.loadBeanDefinitions(new FileSystemResource(xml.toFile())); context.refresh();
            other = context.getBean("other", Owner.class);
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PlatformContext.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getApplicationContext" -> context;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            reloader = new SpringXmlReloader(platform);
        }
        String document(String properties, String label, String otherLabel) {
            return "<beans xmlns='http://www.springframework.org/schema/beans'>"
                    + "<bean id='choice' class='" + Stage.class.getName() + "'><property name='value' value='local'/></bean>"
                    + "<bean id='owner' class='" + Owner.class.getName() + "' lazy-init='" + lazy + "'>"
                    + "<property name='label' value='" + label + "'/>" + properties + "</bean>"
                    + "<bean id='other' class='" + Owner.class.getName() + "'><property name='label' value='" + otherLabel + "'/></bean>" + extra + "</beans>";
        }
        Owner bean() { return context.getBean("owner", Owner.class); }
        Object raw(String property) { return context.getBeanFactory().getBeanDefinition("owner").getPropertyValues().get(property); }
        void reload(String properties, String label, String otherLabel) throws Exception {
            Files.writeString(xml, document(properties, label, otherLabel)); events.clear(); RestartLedger.clear();
            StatusReporter.StatusListener listener = (level, message) -> events.add(level + ":" + message);
            StatusReporter.addListener(listener);
            try { reloader.reload(xml); } finally { StatusReporter.removeListener(listener); }
            assertSame(other, context.getBean("other"));
        }
        void warning() {
            assertTrue(events.stream().anyMatch(v -> v.contains("Restart required") && v.contains("owner")), events.toString());
            assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains("owner")), RestartLedger.digest().toString());
        }
        void noWarning() { assertFalse(events.stream().anyMatch(v -> v.contains("Restart required")), events.toString()); }
        public void close() { context.close(); }
    }
}
