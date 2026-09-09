/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.aopalliance.intercept.MethodInvocation;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/** Proves Spring's invocation SPI accepts saved metadata with a different actual target class. */
class AddedOperationMetadataTest {
    public static class Owner { int calls; }
    public static class Metadata {
        @Transactional(rollbackFor = Exception.class)
        public int write(boolean fail) { throw new AssertionError("metadata body must never run"); }
        @Cacheable(cacheNames = "values", key = "#p0")
        public int value(String key) { throw new AssertionError("metadata body must never run"); }
    }

    @Test void separateMetadataDrivesRealCommitAndRollback() throws Throwable {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:metadata_" + java.util.UUID.randomUUID());
        try (var anchor = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("create table entries (id int)");
            var interceptor = new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                    new AnnotationTransactionAttributeSource());
            interceptor.afterPropertiesSet();
            var owner = new Owner();
            Method metadata = Metadata.class.getMethod("write", boolean.class);
            assertEquals(1, interceptor.invoke(invocation(owner, metadata, new Object[]{false}, () -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                return jdbc.update("insert into entries values (1)");
            })));
            var failure = new Exception("rollback this row");
            assertSame(failure, assertThrows(Exception.class, () -> interceptor.invoke(
                    invocation(owner, metadata, new Object[]{true}, () -> {
                        jdbc.update("insert into entries values (2)"); throw failure;
                    }))));
            assertEquals(1, jdbc.queryForObject("select count(*) from entries", Integer.class));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        }
    }

    @Test void separateMetadataDrivesActualCacheHitsWithArguments() throws Throwable {
        var interceptor = new CacheInterceptor();
        interceptor.setCacheOperationSource(new AnnotationCacheOperationSource());
        interceptor.setCacheManager(new ConcurrentMapCacheManager("values"));
        interceptor.afterPropertiesSet();
        interceptor.afterSingletonsInstantiated();
        var owner = new Owner();
        Method metadata = Metadata.class.getMethod("value", String.class);
        assertEquals(1, interceptor.invoke(invocation(owner, metadata, new Object[]{"a"}, () -> ++owner.calls)));
        assertEquals(1, interceptor.invoke(invocation(owner, metadata, new Object[]{"a"}, () -> ++owner.calls)));
        assertEquals(2, interceptor.invoke(invocation(owner, metadata, new Object[]{"b"}, () -> ++owner.calls)));
        assertEquals(2, owner.calls);
    }

    interface Body { Object run() throws Throwable; }
    private static MethodInvocation invocation(Object owner, Method metadata, Object[] args, Body body) {
        return new MethodInvocation() {
            public Method getMethod() { return metadata; }
            public Object[] getArguments() { return args; }
            public Object proceed() throws Throwable { return body.run(); }
            public Object getThis() { return owner; }
            public AccessibleObject getStaticPart() { return metadata; }
        };
    }
}
