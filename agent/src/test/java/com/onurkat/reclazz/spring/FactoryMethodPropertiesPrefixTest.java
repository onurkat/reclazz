/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R37: Boot's own dataSource bean carries {@code @ConfigurationProperties("spring.datasource.hikari")} on its factory
 * method. Read from the class alone it bound "the root", so every property save rebound it and validated it as a
 * removal target, which a HikariDataSource fails ("Uncheckable").
 */
class FactoryMethodPropertiesPrefixTest {
    public static class Pool {
        private int size;
        public int getSize() { return size; }
        public void setSize(int value) { size = value; }
        public boolean isClosed() { return false; }
    }

    @ConfigurationProperties("own")
    public static class Own { }

    @Configuration(proxyBeanMethods = false)
    static class Config {
        @Bean @ConfigurationProperties("spring.datasource.hikari") Pool pool() { return new Pool(); }
        @Bean Own own() { return new Own(); }
    }

    @Test void factoryMethodPrefixIsReadAndUnrelatedKeysLeaveTheBeanAlone() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            Object pool = context.getBean("pool");
            assertEquals("", SpringPropertyRebinder.prefixOf(pool, ConfigurationProperties.class), "the class alone says nothing");
            assertEquals("spring.datasource.hikari",
                    SpringPropertyRebinder.prefixOf(context, "pool", pool, ConfigurationProperties.class));
            assertEquals("own", SpringPropertyRebinder.prefixOf(context, "own", context.getBean("own"), ConfigurationProperties.class));
            assertFalse(SpringPropertyRebinder.affects(Set.of("spring.datasource.url"), "spring.datasource.hikari"));
        }
    }
}
