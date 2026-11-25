/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.annotation.ScannedGenericBeanDefinition;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class XmlScannedInfrastructureTest {
    private static final Path FILE = Path.of("extension-spring.xml");

    @Test void equalScannedClassIgnoresRuntimeLiteMarkerButKeepsSetterAndSupplierChanges() throws Exception {
        var f = new DefaultListableBeanFactory();
        var p = new DefaultListableBeanFactory();
        var reader = new SimpleMetadataReaderFactory(getClass().getClassLoader()).getMetadataReader(getClass().getName());
        var before = new ScannedGenericBeanDefinition(reader);
        before.setResource(reader.getResource());
        before.setAttribute("org.springframework.context.annotation.ConfigurationClassPostProcessor.configurationClass", "lite");
        var next = new ScannedGenericBeanDefinition(reader);
        next.setResource(reader.getResource());
        assertTrue(XmlScannedInfrastructure.unchanged(f, p, "listener", before, next, FILE));
        next.getPropertyValues().add("target", "new");
        assertFalse(XmlScannedInfrastructure.unchanged(f, p, "listener", before, next, FILE));
        next.getPropertyValues().removePropertyValue("target");
        next.setInstanceSupplier(Object::new);
        assertFalse(XmlScannedInfrastructure.unchanged(f, p, "listener", before, next, FILE));
    }

    @Test void namespaceDefaultCannotReplaceExternallyDeclaredProcessorButAnEditedProcessorIsChecked() {
        String name = AnnotationConfigUtils.COMMON_ANNOTATION_PROCESSOR_BEAN_NAME;
        var f = new DefaultListableBeanFactory();
        var p = new DefaultListableBeanFactory();
        AnnotationConfigUtils.registerAnnotationConfigProcessors(p);
        var next = p.getBeanDefinition(name);
        var before = new GenericBeanDefinition(next);
        before.setResource(new ClassPathResource("core-spring.xml"));
        before.getPropertyValues().add("fallbackToDefaultTypeMatch", false);
        f.registerBeanDefinition(name, before);
        assertTrue(XmlScannedInfrastructure.unchanged(f, p, name, before, next, FILE));
        AnnotationConfigUtils.registerAnnotationConfigProcessors(f);
        assertSame(before, f.getBeanDefinition(name));
        next.getPropertyValues().add("fallbackToDefaultTypeMatch", true);
        assertFalse(XmlScannedInfrastructure.unchanged(f, p, name, before, next, FILE));
    }
}
