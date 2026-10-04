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
import org.springframework.beans.Mergeable;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class XmlCollectionMergeGuardTest {
    @TempDir Path tmp;
    public static class Chain {
        List<?> stages;
        Map<?, ?> entries;
        String label;
        public void setStages(List<?> value) { stages = value; }
        public void setEntries(Map<?, ?> value) { entries = value; }
        public void setLabel(String value) { label = value; }
    }

    @BeforeEach void reset() { ApplicationContextHolder.clear(); RestartLedger.clear(); }
    @AfterEach void clear() { ApplicationContextHolder.clear(); RestartLedger.clear(); }

    @Test void mergedListEditPreservesParentAndDefinition() throws Exception {
        try (var s = new Scope(parent() + child("true", "old", "before", false) + independent("old"))) {
            Chain bean = s.bean(); Object raw = s.raw("stages");
            s.reload(parent() + child("true", "new", "after", false) + independent("new"));
            assertSame(bean, s.bean());
            assertEquals(List.of("base", "old"), bean.stages);
            assertEquals("before", bean.label, "affected bean is not partially updated");
            assertSame(raw, s.raw("stages"));
            assertEquals("new", s.context.getBean("other", Chain.class).label);
            s.assertRefused();
        }
    }

    @Test void mergedMapEditPreservesParentOverridesAndDefinition() throws Exception {
        try (var s = new Scope(parent() + child("true", "old", "before", false))) {
            Object raw = s.raw("entries");
            s.reload(parent() + child("true", "old", "before", false)
                    .replace("key='shared' value='old'", "key='shared' value='new'"));
            assertEquals(Map.of("base", "kept", "shared", "old"), s.bean().entries);
            assertSame(raw, s.raw("entries"));
            s.assertRefused();
        }
    }

    @Test void mergeFlagOnlyBothDirectionsRequireRestart() throws Exception {
        for (boolean initial : List.of(true, false)) {
            try (var s = new Scope(parent() + child("" + initial, "old", "before", false))) {
                Object raw = s.raw("stages"); List<?> before = s.bean().stages;
                s.reload(parent() + child("" + !initial, "old", "before", false));
                assertSame(before, s.bean().stages);
                assertSame(raw, s.raw("stages"));
                assertEquals(initial, ((Mergeable) raw).isMergeEnabled());
                s.assertRefused();
            }
        }
    }

    @Test void nestedMergeFlagChangeRequiresRestart() throws Exception {
        String template = "<bean id='chain' class='%s'><property name='stages'><list>"
                + "<list merge='%s'><value>nested</value></list></list></property></bean>";
        try (var s = new Scope(template.formatted(Chain.class.getName(), "false"))) {
            Object raw = s.raw("stages"); List<?> before = s.bean().stages;
            s.reload(template.formatted(Chain.class.getName(), "true"));
            assertSame(raw, s.raw("stages")); assertSame(before, s.bean().stages);
            s.assertRefused();
        }
    }

    @Test void removedMergedPropertyWithOtherEditRequiresRestart() throws Exception {
        try (var s = new Scope(parent() + child("true", "old", "before", false))) {
            Object raw = s.raw("stages");
            s.reload(parent() + "<bean id='chain' parent='base'><property name='label' value='after'/></bean>");
            assertSame(raw, s.raw("stages")); assertEquals("before", s.bean().label);
            s.assertRefused();
        }
    }

    @Test void unchangedMergedPropertyAllowsScalarEdit() throws Exception {
        try (var s = new Scope(parent() + child("true", "old", "before", false))) {
            List<?> before = s.bean().stages;
            s.reload(parent() + child("true", "old", "after", false));
            assertEquals("after", s.bean().label); assertSame(before, s.bean().stages);
            assertFalse(s.events.stream().anyMatch(v -> v.contains("merged collection")), s.events.toString());
        }
    }

    @Test void ordinaryCollectionReplacementStillApplies() throws Exception {
        try (var s = new Scope(parent() + child("false", "old", "before", false))) {
            s.reload(parent() + child("false", "new", "after", false));
            assertEquals(List.of("new"), s.bean().stages);
            assertEquals(Map.of("shared", "new"), s.bean().entries);
            assertEquals("after", s.bean().label);
            assertFalse(s.events.stream().anyMatch(v -> v.contains("merged collection")));
        }
    }

    @Test void repeatedRejectedSavesPreserveOrderAndReferences() throws Exception {
        try (var s = new Scope(parent() + child("true", "old", "before", false))) {
            Chain bean = s.bean(); List<?> list = bean.stages; Map<?, ?> map = bean.entries;
            for (String next : List.of("new", "new", "later")) {
                s.reload(parent() + child("true", next, "before", false));
                assertSame(bean, s.bean()); assertSame(list, bean.stages); assertSame(map, bean.entries);
                assertEquals(List.of("base", "old"), bean.stages); s.assertRefused();
            }
        }
    }

    @Test void lazyMergedBeanDefinitionIsNotMutated() throws Exception {
        try (var s = new Scope(parent() + child("true", "old", "before", true))) {
            assertFalse(s.context.getBeanFactory().containsSingleton("chain"));
            Object raw = s.raw("stages");
            s.reload(parent() + child("true", "new", "after", true));
            assertFalse(s.context.getBeanFactory().containsSingleton("chain"));
            assertSame(raw, s.raw("stages"));
            assertEquals(List.of("base", "old"), s.bean().stages); s.assertRefused();
        }
    }

    @Test void separateResourceParentIsPreserved() throws Exception {
        Path base = tmp.resolve("base.xml"); Files.writeString(base, wrap(parent()));
        try (var s = new Scope(child("true", "old", "before", false), base)) {
            Object definition = s.context.getBeanFactory().getBeanDefinition("base");
            s.reload(child("true", "new", "after", false));
            assertSame(definition, s.context.getBeanFactory().getBeanDefinition("base"));
            assertEquals(List.of("base", "old"), s.bean().stages); s.assertRefused();
        }
    }

    @Test void newMergedBeanUsesNativeCreation() throws Exception {
        try (var s = new Scope(parent())) {
            s.reload(parent() + child("true", "new", "after", false));
            assertEquals(List.of("base", "new"), s.bean().stages);
            assertEquals(Map.of("base", "kept", "shared", "new"), s.bean().entries);
            assertFalse(s.events.stream().anyMatch(v -> v.contains("merged collection")));
        }
    }

    private static String parent() {
        return "<bean id='base' class='" + Chain.class.getName() + "' abstract='true'>"
                + "<property name='stages'><list><value>base</value></list></property>"
                + "<property name='entries'><map><entry key='base' value='kept'/>"
                + "<entry key='shared' value='parent'/></map></property></bean>";
    }
    private static String child(String merge, String value, String label, boolean lazy) {
        return "<bean id='chain' parent='base' lazy-init='" + lazy + "'>"
                + "<property name='stages'><list merge='" + merge + "'><value>" + value + "</value></list></property>"
                + "<property name='entries'><map merge='" + merge + "'><entry key='shared' value='" + value + "'/></map></property>"
                + "<property name='label' value='" + label + "'/></bean>";
    }
    private static String independent(String label) {
        return "<bean id='other' class='" + Chain.class.getName() + "'><property name='label' value='" + label + "'/></bean>";
    }
    private static String wrap(String body) { return "<beans xmlns='http://www.springframework.org/schema/beans'>" + body + "</beans>"; }

    private final class Scope implements AutoCloseable {
        final GenericApplicationContext context = new GenericApplicationContext();
        final Path xml;
        final List<String> events = new ArrayList<>();
        final SpringXmlReloader reloader;
        Scope(String body, Path... earlier) throws Exception {
            xml = Files.createTempFile(tmp, "merge-", ".xml");
            Files.writeString(xml, wrap(body));
            var reader = new XmlBeanDefinitionReader(context); reader.setValidating(false);
            for (Path path : earlier) reader.loadBeanDefinitions(new FileSystemResource(path.toFile()));
            reader.loadBeanDefinitions(new FileSystemResource(xml.toFile())); context.refresh();
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PlatformContext.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getApplicationContext" -> context;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            reloader = new SpringXmlReloader(platform);
        }
        Chain bean() { return context.getBean("chain", Chain.class); }
        Object raw(String property) { return context.getBeanFactory().getBeanDefinition("chain").getPropertyValues().get(property); }
        void reload(String body) throws Exception {
            Files.writeString(xml, wrap(body)); events.clear();
            StatusReporter.StatusListener listener = (level, message) -> events.add(level + ":" + message);
            StatusReporter.addListener(listener);
            try { reloader.reload(xml); } finally { StatusReporter.removeListener(listener); }
        }
        void assertRefused() {
            assertTrue(events.stream().anyMatch(v -> v.contains("merged collection") && v.contains("restart")), events.toString());
            assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains("merged collection")));
        }
        public void close() { context.close(); }
    }
}
