/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.*;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AddedBeanAliasesTest {
    public static class Product implements AutoCloseable {
        int closed;
        Runnable onClose;
        public void close() { closed++; if (onClose != null) onClose.run(); }
    }
    public record Consumer(Product product) { }
    @Configuration(proxyBeanMethods = false)
    public static class Config {
        int calls;
        boolean fail;
        public Consumer client(@Qualifier("legacy") Product product) { return new Consumer(product); }
        private Product product() {
            calls++;
            if (fail) throw new IllegalStateException("test factory failure");
            return new Product();
        }
        public Product second() { calls++; return new Product(); }
    }

    @AfterEach void clear() { RestartLedger.clear(); }

    @ParameterizedTest
    @ValueSource(strings = {"name", "value", "both"})
    void aliasesResolveTheSameSingletonAndInjectByAlias(String attribute) throws Exception {
        try (Scope scope = new Scope()) {
            ClassNode source = beans("main", "legacy", "older");
            if (!attribute.equals("name")) {
                AnnotationNode bean = method(source, "product").visibleAnnotations.get(0);
                if (attribute.equals("value")) bean.values.set(0, "value");
                else bean.values.addAll(List.of("value", List.of("main", "legacy", "older")));
            }
            method(source, "client").visibleAnnotations = new ArrayList<>(List.of(bean("name", List.of("client"))));
            scope.ok(source);
            Object product = scope.context.getBean("main");
            assertSame(product, scope.context.getBean("legacy"));
            assertSame(product, scope.context.getBean("older"));
            assertSame(product, scope.context.getBean(Consumer.class).product());
            assertFalse(scope.context.containsBean("product"), "method name is not an implicit alias");
            assertEquals(1, scope.context.getBean(Config.class).calls);
            assertEquals(1, scope.context.getBeanNamesForType(Product.class).length);
        }
    }

    @Test
    void editsRenamesAndRemovalCleanOnlyOldAliasesAndCloseOnce() throws Exception {
        try (Scope scope = new Scope()) {
            scope.ok(beans("main", "legacy", "older"));
            Product first = scope.context.getBean("main", Product.class);
            scope.ok(beans("main", "current"));
            assertEquals(1, first.closed);
            scope.absent("legacy", "older");
            Product second = scope.context.getBean("current", Product.class);
            scope.ok(beans("current", "main")); // alias becomes canonical, old canonical becomes alias
            assertEquals(1, second.closed);
            assertTrue(scope.factory.isAlias("main"));
            assertFalse(scope.factory.isAlias("current"));
            assertSame(scope.context.getBean("main"), scope.context.getBean("current"));
            Product third = scope.context.getBean("main", Product.class);
            scope.ok(original());
            scope.absent("main", "current");
            assertEquals(1, third.closed);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"definition", "singleton", "alias", "dangling", "parent", "parentAlias"})
    void aliasCollisionsDoNotInstallAnyPartOfTheFactory(String kind) throws Exception {
        try (Scope scope = new Scope(); var parent = new AnnotationConfigApplicationContext()) {
            Product existing = new Product();
            switch (kind) {
                case "definition" -> scope.context.registerBean("taken", Product.class, () -> existing);
                case "singleton" -> scope.factory.registerSingleton("taken", existing);
                case "alias" -> { scope.factory.registerSingleton("external", existing); scope.factory.registerAlias("external", "taken"); }
                case "dangling" -> scope.factory.registerAlias("missing", "taken");
                case "parent" -> { parent.getBeanFactory().registerSingleton("taken", existing); parent.refresh(); scope.context.setParent(parent); }
                case "parentAlias" -> { parent.getBeanFactory().registerSingleton("external", existing); parent.getBeanFactory().registerAlias("external", "taken"); parent.refresh(); scope.context.setParent(parent); }
            }
            assertFalse(scope.reload(beans("main", "free", "taken")));
            scope.absent("main", "free");
            assertEquals(0, scope.context.getBean(Config.class).calls);
            if (kind.equals("dangling")) assertEquals("missing", scope.factory.canonicalName("taken"));
            else assertSame(existing, scope.context.getBean("taken"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"aliasAlias", "aliasCanonical", "canonicalAlias"})
    void overlappingNamesInOneSaveRefuseBothFactories(String kind) throws Exception {
        try (Scope scope = new Scope()) {
            ClassNode source = kind.equals("canonicalAlias") ? beans("shared", "firstAlias") : beans("main", "shared");
            List<String> second = kind.equals("aliasCanonical") ? List.of("shared", "other") : List.of("secondMain", "shared");
            method(source, "second").visibleAnnotations = new ArrayList<>(List.of(bean("name", second)));
            assertFalse(scope.reload(source));
            scope.absent("main", "shared", "firstAlias", "secondMain", "other");
            assertEquals(0, scope.context.getBean(Config.class).calls);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "&other", "main"})
    void invalidOrRepeatedAliasRefusesTheWholeFactory(String alias) throws Exception {
        try (Scope scope = new Scope()) {
            assertFalse(scope.reload(beans("main", alias)));
            scope.absent("main");
            assertEquals(0, scope.context.getBean(Config.class).calls);
        }
    }

    @Test
    void retargetedAliasesArePreservedAndRemovedAliasesAreNotRecreated() throws Exception {
        try (Scope scope = new Scope()) {
            scope.ok(beans("main", "legacy", "older"));
            Object external = new Object();
            scope.factory.registerSingleton("external", external);
            scope.factory.registerAlias("external", "legacy");
            scope.factory.removeAlias("older");
            scope.ok(original());
            scope.absent("main", "older");
            assertSame(external, scope.context.getBean("legacy"));
        }
    }

    @Test
    void anExternalAliasChainToTheSameRootIsNotMistakenForOurDirectBinding() throws Exception {
        try (Scope scope = new Scope()) {
            scope.ok(beans("main", "legacy"));
            scope.factory.registerAlias("main", "bridge");
            scope.factory.registerAlias("bridge", "legacy");
            scope.ok(original());
            scope.absent("main");
            assertTrue(scope.factory.isAlias("bridge"));
            assertTrue(scope.factory.isAlias("legacy"));
            // Rebinding the preserved bridge demonstrates that legacy still targets it.
            Object external = new Object();
            scope.factory.registerSingleton("external", external);
            scope.factory.registerAlias("external", "bridge");
            assertSame(external, scope.context.getBean("legacy"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void externalCanonicalReplacementKeepsItsAliases(boolean definition) throws Exception {
        try (Scope scope = new Scope()) {
            scope.ok(beans("main", "legacy"));
            Product external = new Product();
            if (definition) scope.context.registerBean("main", Product.class, () -> external);
            else { scope.factory.destroySingleton("main"); scope.factory.registerSingleton("main", external); }
            assertSame(external, scope.context.getBean("legacy"));
            scope.ok(original());
            assertSame(external, scope.context.getBean("main"));
            assertSame(external, scope.context.getBean("legacy"));
            assertEquals(0, external.closed);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"factory", "init"})
    void initializationFailureRemovesAliasesAndTheNextSaveRecovers(String failure) throws Exception {
        try (Scope scope = new Scope()) {
            scope.context.getBean(Config.class).fail = failure.equals("factory");
            ClassNode source = beans("main", "legacy");
            if (failure.equals("init")) method(source, "product").visibleAnnotations.get(0).values.addAll(List.of("initMethod", "doesNotExist"));
            assertFalse(scope.reload(source));
            scope.absent("main", "legacy");
            scope.context.getBean(Config.class).fail = false;
            scope.ok(beans("main", "legacy"));
            assertSame(scope.context.getBean("main"), scope.context.getBean("legacy"));
        }
    }

    @Test
    void aliasesRetargetedDuringDestructionArePreserved() throws Exception {
        try (Scope scope = new Scope()) {
            scope.ok(beans("main", "legacy"));
            Object external = new Object();
            scope.factory.registerSingleton("external", external);
            Product product = scope.context.getBean("main", Product.class);
            product.onClose = () -> scope.factory.registerAlias("external", "legacy");
            scope.ok(original());
            assertEquals(1, product.closed);
            assertSame(external, scope.context.getBean("legacy"));
        }
    }

    @Test
    void aCanonicalReplacementDuringDestructionKeepsItsAliases() throws Exception {
        try (Scope scope = new Scope()) {
            scope.ok(beans("main", "legacy"));
            Product product = scope.context.getBean("main", Product.class);
            Product external = new Product();
            product.onClose = () -> scope.context.registerBean("main", Product.class, () -> external);
            scope.ok(original());
            assertSame(external, scope.context.getBean("legacy"));
            assertEquals(1, product.closed);
            assertEquals(0, external.closed);
        }
    }

    @Test
    void springRecreationThroughAnAliasRemainsOwned() throws Exception {
        try (Scope scope = new Scope()) {
            scope.ok(beans("main", "legacy"));
            Product first = scope.context.getBean("legacy", Product.class);
            scope.factory.destroySingleton("main");
            Product recreated = scope.context.getBean("legacy", Product.class);
            assertNotSame(first, recreated);
            assertEquals(1, first.closed);
            scope.ok(original());
            scope.absent("main", "legacy");
            assertEquals(1, recreated.closed);
        }
    }

    public static class UnknownAliasLayoutFactory extends DefaultListableBeanFactory {
        // Simulate an incompatible private storage layout for the ownership reader.
        private final Object aliasMap = new Object();
    }
    @Test
    void unreadableAliasOwnershipRefusesBeforeRegistrationButSingleNamesStillWork() throws Exception {
        try (Scope scope = new Scope(new UnknownAliasLayoutFactory())) {
            assertFalse(scope.reload(beans("main", "legacy")));
            scope.absent("main", "legacy");
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains("cannot inspect direct alias bindings")));
            assertEquals(0, scope.context.getBean(Config.class).calls);
            scope.ok(beans("main"));
            assertNotNull(scope.context.getBean("main"));
        }
    }

    public static class FailingAliasFactory extends DefaultListableBeanFactory {
        boolean fail = true;
        @Override public void registerAlias(String name, String alias) {
            super.registerAlias(name, alias);
            if (fail && alias.equals("broken")) throw new IllegalStateException("test alias registration failure");
        }
    }
    @Test
    void partialAliasRegistrationFailureLeavesNoOwnedNames() throws Exception {
        FailingAliasFactory factory = new FailingAliasFactory();
        try (Scope scope = new Scope(factory)) {
            assertFalse(scope.reload(beans("main", "legacy", "broken")));
            scope.absent("main", "legacy", "broken");
            assertEquals(0, scope.context.getBean(Config.class).calls);
            factory.fail = false;
            scope.ok(beans("main", "legacy", "broken"));
            assertSame(scope.context.getBean("main"), scope.context.getBean("broken"));
        }
    }

    @Test
    void aliasesStayInsideTheirContexts() throws Exception {
        try (Scope one = new Scope(); Scope two = new Scope()) {
            one.ok(beans("main", "legacy"));
            two.ok(beans("main", "legacy"));
            assertNotSame(one.context.getBean("legacy"), two.context.getBean("legacy"));
            one.ok(original());
            one.absent("legacy");
            assertSame(two.context.getBean("main"), two.context.getBean("legacy"));
        }
    }

    private static final class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context;
        final DefaultListableBeanFactory factory;
        final SpringAddedBeanReloader reloader;
        Scope() throws Exception { this(new DefaultListableBeanFactory()); }
        Scope(DefaultListableBeanFactory factory) throws Exception {
            this.factory = factory;
            context = new AnnotationConfigApplicationContext(factory);
            LookupCapture.store(Config.class, MethodHandles.privateLookupIn(Config.class, MethodHandles.lookup()));
            context.registerBean("config", Config.class);
            context.refresh();
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PlatformContext.class}, (p, m, a) -> m.getName().equals("getAllApplicationContexts") ? List.of(context) : null);
            reloader = new SpringAddedBeanReloader(platform);
        }
        boolean reload(ClassNode source) {
            RestartLedger.clear();
            Set<String> added = new HashSet<>();
            for (var method : source.methods) added.add(method.name + ":" + method.desc);
            ClassWriter writer = new ClassWriter(0);
            source.accept(writer);
            return reloader.reloadBeanMethods(Config.class, added, writer.toByteArray());
        }
        void ok(ClassNode source) { assertTrue(reload(source), RestartLedger.digest().toString()); }
        void absent(String... names) {
            for (String name : names) {
                assertFalse(context.containsBean(name), name);
                assertFalse(factory.containsBeanDefinition(name), name);
                assertFalse(factory.isAlias(name), "dangling alias: " + name);
            }
        }
        @Override public void close() { context.close(); }
    }
    private static ClassNode beans(String... names) throws Exception {
        ClassNode source = original();
        method(source, "product").visibleAnnotations = new ArrayList<>(List.of(bean("name", List.of(names))));
        return source;
    }
    private static AnnotationNode bean(Object... values) {
        var bean = new AnnotationNode(AddedBeanAdapter.BEAN);
        bean.values = new ArrayList<>(Arrays.asList(values));
        return bean;
    }
    private static MethodNode method(ClassNode source, String name) {
        return source.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static ClassNode original() throws Exception {
        try (var in = Config.class.getResourceAsStream("/" + Config.class.getName().replace('.', '/') + ".class")) {
            assertNotNull(in);
            ClassNode source = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(source, 0);
            return source;
        }
    }
}
