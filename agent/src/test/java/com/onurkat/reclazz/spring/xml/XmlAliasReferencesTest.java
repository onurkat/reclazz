/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.RuntimeBeanNameReference;
import org.springframework.beans.factory.support.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.URLClassLoader;
import java.nio.file.*;
import javax.tools.ToolProvider;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class XmlAliasReferencesTest {
    static class Field { @Autowired Object x; }
    static class Qualified { @Autowired(required=false) @Qualifier("x") Object other; }
    static class Literal { @Value("#{beanFactory.containsBean('x') ? @x : @a}") Object fallback; }
    static class Computed { @Value("#{beanFactory.containsBean(T(java.lang.Character).toString(120)) ? @x : @a}") Object fallback; }
    static class Parent { @javax.annotation.Resource Object x; }
    static class Child extends Parent { }
    public static class Product implements org.springframework.beans.factory.FactoryBean<Object> {
        int calls;
        public Object getObject() { calls++; return new Computed(); }
        public Class<?> getObjectType() { return Computed.class; }
    }

    @Test void aCachedFactoryProductIsInspectedWithoutCreatingAnUnrequestedProduct() throws Exception {
        var f = new DefaultListableBeanFactory(); var producer = new Product();
        f.registerSingleton("producer", producer);
        XmlAliasReferences.check(f, "x"); assertEquals(0, producer.calls);
        assertInstanceOf(Computed.class, f.getBean("producer"));
        assertThrows(IllegalStateException.class, () -> XmlAliasReferences.check(f, "x"));
        assertEquals(1, producer.calls);
    }

    static class ByName { public void setX(Object ignored) {} }

    @Test void containerManagedNamesAndComputedLookupsStayPending() throws Exception {
        var f = new DefaultListableBeanFactory();
        for (Object bean : List.of(new Field(), new Qualified(), new Literal(), new Computed())) {
            f.registerSingleton("consumer", bean);
            var failure = assertThrows(IllegalStateException.class, () -> XmlAliasReferences.check(f, "x"), bean.getClass().getName());
            assertTrue(failure.getMessage().contains("consumer"));
            f.destroySingleton("consumer");
        }
    }

    @Test void discoveredConstructorParameterIsSensitive(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("Consumer.java");
        Files.writeString(source, "public class Consumer { public Consumer(Object x) {} }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-parameters", "-d", directory.toString(), source.toString()));
        try (var loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()}, getClass().getClassLoader())) {
            Object consumer = loader.loadClass("Consumer").getConstructor(Object.class).newInstance(new Object());
            var f = new DefaultListableBeanFactory(); f.registerSingleton("consumer", consumer);
            assertThrows(IllegalStateException.class, () -> XmlAliasReferences.check(f, "x"));
            assertDoesNotThrow(() -> XmlAliasReferences.check(f, "unused"));
        }
    }

    @Test void inheritedResourceImplicitNameIsSensitive() {
        var f = new DefaultListableBeanFactory();
        f.registerSingleton("consumer", new Child());
        assertThrows(IllegalStateException.class, () -> XmlAliasReferences.check(f, "x"));
    }

    @Test void nestedReferencesAndByNamePropertiesAndCustomResolversStayPending() throws Exception {
        var f = new DefaultListableBeanFactory();
        var d = new RootBeanDefinition(ByName.class); d.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_NAME);
        f.registerBeanDefinition("consumer", d);
        assertThrows(IllegalStateException.class, () -> XmlAliasReferences.check(f, "x"));
        f.removeBeanDefinition("consumer");
        var m = new RootBeanDefinition(ByName.class);
        m.getConstructorArgumentValues().addIndexedArgumentValue(0, List.of(new RuntimeBeanNameReference("x")));
        f.registerBeanDefinition("nested", m);
        assertThrows(IllegalStateException.class, () -> XmlAliasReferences.check(f, "x"));
        f.removeBeanDefinition("nested"); f.setAutowireCandidateResolver(new SimpleAutowireCandidateResolver() {});
        assertThrows(IllegalStateException.class, () -> XmlAliasReferences.check(f, "unused"));
    }

    @Test void templatePartsKeepNamedAndComputedBeanLookupsPendingWithoutEvaluatingThem() throws Exception {
        for (String expression : List.of("#{&x}", "#{@'x'}", "#{x.value}", "#{[name]}", "#{#root}",
                "#{#this}", "#{other.applicationContext}", "#{__beanFactory}", "#{beanFactory}",
                "prefix#{42}#{other.getBeanNamesForType(T(java.lang.String))}", "#{bad expression!}")) {
            var factory = new DefaultListableBeanFactory();
            var definition = new RootBeanDefinition(ByName.class);
            definition.getPropertyValues().add("x", expression);
            factory.registerBeanDefinition("consumer", definition);
            assertThrows(IllegalStateException.class, () -> XmlAliasReferences.check(factory, "x"), expression);
        }
    }

    @Test void stockIntegrationPropertiesOnlyLooksUpTheGlobalPropertiesBean() throws Exception {
        String stock = "T(org.springframework.integration.context.IntegrationContextUtils)"
                + ".getIntegrationProperties(beanFactory).toProperties().getProperty('spring.integration.channels.autoCreate')";
        for (String expression : List.of("#{" + stock + "}",
                "#{" + stock.replace("channels.autoCreate", "taskScheduler.poolSize") + "}",
                "#{" + stock.replace("channels.autoCreate", "readOnly.headers") + "}", "#{@integrationGlobalProperties['spring.integration.channels.autoCreate']}")) {
            var f = withExpression(expression);
            assertDoesNotThrow(() -> XmlAliasReferences.check(f, "newName"));
            assertThrows(IllegalStateException.class, () -> XmlAliasReferences.check(f, "integrationGlobalProperties"));
        }
        for (String expression : List.of("#{beanFactory.getBean('newName')}",
                "#{" + stock.replace("'spring.integration.channels.autoCreate'", "other.key") + "}",
                "#{" + stock.replace("IntegrationContextUtils", "DifferentClass") + "}",
                "#{" + stock.replace("beanFactory", "some.beanFactory") + "}",
                "#{" + stock + ".getBean('x')}")) {
            assertThrows(IllegalStateException.class, () -> XmlAliasReferences.check(withExpression(expression), "x"), expression);
        }
    }

    private static DefaultListableBeanFactory withExpression(String expression) {
        var factory = new DefaultListableBeanFactory();
        var definition = new RootBeanDefinition(ByName.class);
        definition.getPropertyValues().add("x", expression);
        factory.registerBeanDefinition("channelInitializer", definition);
        return factory;
    }

    @Test void plainPlaceholdersAndLiteralExpressionsAllowNewNames() throws Exception {
        var f = new DefaultListableBeanFactory(); var d = new RootBeanDefinition(ByName.class);
        d.getPropertyValues().add("x", List.of("${ordinary.key}", "#{42}", "#{'a literal'}"));
        f.registerBeanDefinition("ordinary", d); XmlAliasReferences.check(f, "free");
    }
}
