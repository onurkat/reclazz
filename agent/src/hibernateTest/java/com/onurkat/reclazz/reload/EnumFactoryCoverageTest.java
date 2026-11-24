/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.internal.SessionFactoryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EnumFactoryCoverageTest {
    enum State { NEW, PAID }

    @Entity(name = "NamedOrder") public static class NamedOrder {
        @Id public long id;
        @Enumerated(EnumType.STRING) public State state;
    }

    @Entity(name = "OrdinalOrder") public static class OrdinalOrder {
        @Id public long id;
        @Enumerated(EnumType.ORDINAL) public State state;
    }

    static SessionFactory factory(Class<?> entity) {
        return configuration().addAnnotatedClass(entity).buildSessionFactory();
    }

    static Configuration configuration() {
        return new Configuration()
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:" + UUID.randomUUID())
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("jakarta.persistence.validation.mode", "none")
                .setProperty("hibernate.cache.use_second_level_cache", "false");
    }

    @Test
    void removingARegistrationDoesNotCloseAnUnmanagedOrdinalFactory() {
        try (SessionFactory named = factory(NamedOrder.class);
             SessionFactory ordinal = factory(OrdinalOrder.class);
             var context = new GenericApplicationContext()) {
            context.getBeanFactory().registerSingleton("namedFactory", named);
            context.refresh();
            ApplicationContextHolder.register(context);
            try {
                var registry = SessionFactoryRegistry.INSTANCE;
                var hidden = ordinal.unwrap(SessionFactoryImplementor.class);
                var visible = named.unwrap(SessionFactoryImplementor.class);
                assertSame(hidden, registry.getSessionFactory(hidden.getUuid()));
                registry.removeSessionFactory(hidden.getUuid(), null, false, null);
                assertNull(registry.getSessionFactory(hidden.getUuid()));
                assertSame(visible, registry.getSessionFactory(visible.getUuid()));
                assertEquals(1, context.getBeansOfType(SessionFactory.class).size());
                assertTrue(hidden.isOpen());
                var decision = EnumConstantAppender.ordinalPersistence(State.class, net.bytebuddy.agent.ByteBuddyAgent.install());
                assertTrue(decision != null && decision.contains("before lifecycle tracking"), decision);
                try (var session = hidden.openSession()) {
                    var tx = session.beginTransaction();
                    var row = new OrdinalOrder();
                    row.id = 1;
                    row.state = State.PAID;
                    session.persist(row);
                    tx.commit();
                    Number stored = (Number) session.createNativeQuery(
                            "select state from OrdinalOrder where id = 1", Object.class).getSingleResult();
                    assertEquals(1, stored.intValue());
                }
            } finally {
                ApplicationContextHolder.clear();
            }
        }
    }
}
