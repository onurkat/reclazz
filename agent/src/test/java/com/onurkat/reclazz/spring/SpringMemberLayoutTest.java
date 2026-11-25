/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.Configuration;
import static org.junit.jupiter.api.Assertions.*;

class SpringMemberLayoutTest {
    static class WrongDisposables extends DefaultListableBeanFactory { int disposableBeans; }
    static class WrongDefinitions extends DefaultListableBeanFactory { int beanDefinitionMap; }
    static class WrongInvalidation { int clearMergedBeanDefinition(String name) { return 1; } }
    static class WrongAttribute { int getAttribute(String name) { return 1; } }

    private static void refuses(SpringMemberLayout probe, Class<?> type, String member) {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> probe.require(type));
        assertTrue(failure.getMessage().contains(member), failure::getMessage);
    }

    @Test void constructorCacheRequiresNativeInvalidation() {
        SpringMemberLayout.CONSTRUCTOR.require(DefaultListableBeanFactory.class);
        refuses(SpringMemberLayout.CONSTRUCTOR, WrongInvalidation.class, "clearMergedBeanDefinition");
    }

    @Test void proxyRecreationRequiresItsDisposableBeanMap() {
        SpringMemberLayout.PROXY.require(DefaultListableBeanFactory.class);
        refuses(SpringMemberLayout.PROXY, WrongDisposables.class, "disposableBeans");
    }

    @Test void xmlPublicationRequiresItsDefinitionMap() {
        SpringMemberLayout.XML.require(DefaultListableBeanFactory.class);
        refuses(SpringMemberLayout.XML, WrongDefinitions.class, "beanDefinitionMap");
    }

    @Configuration static class Owner { }

    @Test void rawConfigurationChecksTheActualDefinitionAfterMemberDiscovery() throws Exception {
        SpringMemberLayout.RAW_CONFIGURATION.require(AnnotatedGenericBeanDefinition.class);
        refuses(SpringMemberLayout.RAW_CONFIGURATION, WrongAttribute.class, "getAttribute");
        Class<?> utils = Class.forName("org.springframework.context.annotation.ConfigurationClassUtils");
        var keyField = utils.getDeclaredField("CONFIGURATION_CLASS_ATTRIBUTE");
        keyField.setAccessible(true);
        var application = new AnnotatedGenericBeanDefinition(Owner.class);
        application.setAttribute((String) keyField.get(null), "lite");
        assertTrue(SpringConfigurationCalls.rawShape(Owner.class, application));
        var infrastructure = new AnnotatedGenericBeanDefinition(Owner.class);
        infrastructure.setAttribute((String) keyField.get(null), "lite");
        infrastructure.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        assertFalse(SpringConfigurationCalls.rawShape(Owner.class, infrastructure));
    }
}
