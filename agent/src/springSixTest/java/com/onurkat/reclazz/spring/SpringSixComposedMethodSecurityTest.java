/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanDefinition;
import static com.onurkat.reclazz.spring.SpringAddedMethodSecurityTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SpringSixComposedMethodSecurityTest extends ComposedMethodSecurityTest {
    @Override Class<?> configuration() { return SpringSixAddedMethodSecurityTest.Config.class; }
    @Override Class<?> plainConfiguration() { return SpringSixAddedMethodSecurityTest.PlainConfig.class; }
    @Override Class<?> disabledConfiguration() { return SpringSixAddedMethodSecurityTest.Disabled.class; }
    @Override void configureOrder(Scope scope) {
        ((org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor)scope.context.getBean("org.springframework.transaction.config.internalTransactionAdvisor")).setOrder(300);
        ((org.springframework.cache.interceptor.BeanFactoryCacheOperationSourceAdvisor)scope.context.getBean("org.springframework.cache.config.internalCacheAdvisor")).setOrder(400);
    }
    @ParameterizedTest @ValueSource(strings={"pre","post"})
    void eachComposedPolicyNeedsItsAdvisorEvenWhenTheOtherMatches(String missing) throws Throwable {
        try(var scope=new Scope(plainConfiguration())) {
            scope.context.getBeanFactory().getBeanDefinition(missing+"AuthorizeAuthorizationAdvisor").setRole(BeanDefinition.ROLE_APPLICATION);
            scope.publish(missing.equals("pre")?Post.class:Pre.class); login("WRITE"); assertEquals("alice",scope.invoke("alice"));
            scope.publish(Both.class);
            assertTrue(assertThrows(IllegalStateException.class,()->scope.invoke("alice")).getMessage().contains("matching"));
            assertEquals(1,scope.target.calls);
        }
    }
}
