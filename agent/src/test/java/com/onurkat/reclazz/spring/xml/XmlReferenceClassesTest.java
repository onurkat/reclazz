/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import static org.junit.jupiter.api.Assertions.assertEquals;

class XmlReferenceClassesTest {
    @TempDir Path directory;
    final DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

    Set<String> missing(String xml) throws Exception {
        var path = directory.resolve("case-spring.xml");
        Files.writeString(path, XmlDefinitionTestScope.document(xml));
        return XmlReferenceClasses.inspect(path, getClass().getClassLoader(), factory).missing();
    }

    void external(String name, String type) {
        var definition = new RootBeanDefinition();
        definition.setBeanClassName(type);
        factory.registerBeanDefinition(name, definition);
    }

    @Test void collectsListRefBean() throws Exception {
        external("step", "example.Step");
        assertEquals(Set.of("example.Step"), missing("<bean><property name='steps'><list><ref bean='step'/></list></property></bean>"));
    }

    @Test void mapValueRef() throws Exception {
        external("key","example.Key");
        external("value","example.Value");
        assertEquals(Set.of("example.Key","example.Value"), missing("<bean><property name='entries'><map><entry key-ref='key' value-ref='value'/></map></property></bean>"));
    }

    @Test void constructorArgRef() throws Exception {
        external("arg","example.Arg");
        external("id","example.Id");
        assertEquals(Set.of("example.Arg","example.Id"), missing("<bean><constructor-arg ref='arg'/><constructor-arg><idref bean='id'/></constructor-arg></bean>"));
    }

    @Test void innerBeanClass() throws Exception {
        assertEquals(Set.of("example.Inner"), missing("<bean><constructor-arg><bean class='example.Inner'/></constructor-arg></bean>"));
    }

    @Test void existingClassesNotWaited() throws Exception {
        factory.addEmbeddedValueResolver(value -> value.replace("${known}", "java.lang.String"));
        assertEquals(int.class, org.springframework.util.ClassUtils.resolvePrimitiveClassName("int"));
        assertEquals(Set.of(), missing("<bean class='int' abstract='true'/><bean class='java.lang.Object'/><bean class='${known}'/><bean class='${dynamic}'/>"));
    }
}
