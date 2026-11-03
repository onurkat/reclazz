/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An unchanged file parsed by the agent's sandbox reader must give definitions
 * natively equal to the live reader's. Several guards decide "unchanged" by
 * that equality; once the alias observer left the parsed resource as the
 * source of every property value, each unchanged SAP list directive in an
 * edited file was reported as needing a restart.
 */
class SandboxParseEqualityTest {
    @TempDir Path tmp;

    public static class Target {
        public void setLabel(String label) { }
        public void setItems(java.util.List<Object> items) { }
    }

    @Test void anUnchangedFileParsesToEqualDefinitionsInTheSandbox() throws Exception {
        Path xml = Files.writeString(tmp.resolve("beans.xml"), """
                <beans xmlns="http://www.springframework.org/schema/beans"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       xsi:schemaLocation="http://www.springframework.org/schema/beans
                         http://www.springframework.org/schema/beans/spring-beans.xsd">
                  <bean id="other" class="%1$s"/>
                  <bean id="target" name="targetAlias" class="%1$s" depends-on="other">
                    <constructor-arg index="0" value="ignored"/>
                    <property name="label" value="before"/>
                    <property name="items">
                      <list merge="false"><ref bean="other"/><value>text</value></list>
                    </property>
                    <meta key="origin" value="xml"/>
                  </bean>
                  <alias name="target" alias="secondAlias"/>
                </beans>
                """.formatted(Target.class.getName()));

        DefaultListableBeanFactory live = new DefaultListableBeanFactory();
        XmlBeanDefinitionReader reader = new XmlBeanDefinitionReader(live);
        reader.setValidating(false);
        reader.loadBeanDefinitions(new FileSystemResource(xml.toFile()));

        DefaultListableBeanFactory sandbox = (DefaultListableBeanFactory)
                SpringReflection.newTempBeanFactory(getClass().getClassLoader());
        assertTrue(SpringReflection.loadXml(sandbox, xml.toFile(), getClass().getClassLoader()));

        for (String name : live.getBeanDefinitionNames()) {
            assertEquals(live.getBeanDefinition(name), sandbox.getBeanDefinition(name),
                    "unchanged definition " + name + " must stay natively equal");
        }
        for (String name : sandbox.getBeanDefinitionNames()) {
            BeanDefinition parsed = sandbox.getBeanDefinition(name);
            assertNull(parsed.getSource(), "sandbox definitions carry no source, like the live reader's");
        }
        assertArrayEquals(live.getAliases("target"), sandbox.getAliases("target"));
    }
}
