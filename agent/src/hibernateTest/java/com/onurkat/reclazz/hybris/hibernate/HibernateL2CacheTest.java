package com.onurkat.reclazz.hybris.hibernate;

import jakarta.persistence.*;
import org.hibernate.SessionFactory;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import com.onurkat.reclazz.platform.ApplicationContextHolder;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Requires the isolated Hibernate/JCache test classpath; never runs against Commerce data. */
class HibernateL2CacheTest {
    @Entity(name = "CacheProbe") @Table(name = "cache_probe")
    @Cacheable @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
    public static class Record {
        @Id public long id;
        public String label;
        public Record() {}
    }
    static class Dao {}

    @Test void daoReloadEvictsRealEntityAndQueryCaches() throws Exception {
        String url = "jdbc:h2:mem:l2_" + UUID.randomUUID();
        try (SessionFactory factory = factory(url, true);
             var context = new GenericApplicationContext()) {
            seed(factory);
            context.getBeanFactory().registerSingleton("customOrmFactory", factory);
            context.refresh();
            ApplicationContextHolder.register(context);
            try {
                assertEquals("old", read(factory));
                assertEquals("old", read(factory));
                assertTrue(factory.getCache().containsEntity(Record.class, 1L), "a real L2 entry must exist");
                assertTrue(factory.getStatistics().getSecondLevelCacheHitCount() > 0);
                assertEquals("old", query(factory));
                assertEquals("old", query(factory));
                assertTrue(factory.getStatistics().getQueryCacheHitCount() > 0);
                updateDirectly(url);
                assertEquals("old", read(factory), "external SQL deliberately leaves the L2 entry stale");
                assertEquals("old", query(factory), "external SQL deliberately leaves the query region stale");
                new HibernateCacheInvalidator().invalidateCache(Dao.class.getName());
                assertEquals("new", read(factory));
                assertEquals("new", query(factory));
            } finally { ApplicationContextHolder.clear(); }
        }
    }

    @Test void aliasesAndRepeatedContextsEvictAFactoryOnlyOnce() throws Exception {
        String url = "jdbc:h2:mem:l2_" + UUID.randomUUID();
        try (SessionFactory factory = factory(url, true);
             var context = new GenericApplicationContext()) {
            seed(factory);
            read(factory);
            context.getBeanFactory().registerSingleton("one", factory);
            context.getBeanFactory().registerSingleton("two", factory);
            context.refresh();
            assertEquals(1, new HibernateCacheInvalidator().invalidateCache(Dao.class.getName(), List.of(context, context)));
            assertFalse(factory.getCache().containsEntity(Record.class, 1L));
        }
    }

    @Test void disabledL2IsNotReportedAsAnEviction() {
        try (SessionFactory factory = factory("jdbc:h2:mem:l2_" + UUID.randomUUID(), false);
             var context = new GenericApplicationContext()) {
            context.getBeanFactory().registerSingleton("orm", factory);
            context.refresh();
            assertEquals(0, new HibernateCacheInvalidator().invalidateCache(Dao.class.getName(), List.of(context)));
        }
    }

    private static SessionFactory factory(String url, boolean cache) {
        var configuration = new Configuration().addAnnotatedClass(Record.class)
                .setProperty("hibernate.connection.url", url)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.generate_statistics", "true")
                .setProperty("jakarta.persistence.validation.mode", "none")
                .setProperty("hibernate.cache.use_second_level_cache", Boolean.toString(cache))
                .setProperty("hibernate.cache.use_query_cache", Boolean.toString(cache));
        if (cache) configuration.setProperty("hibernate.cache.region.factory_class", "jcache");
        return configuration.buildSessionFactory();
    }

    private static void seed(SessionFactory factory) {
        try (var session = factory.openSession()) {
            var transaction = session.beginTransaction();
            Record record = new Record(); record.id = 1L; record.label = "old";
            session.persist(record);
            transaction.commit();
        }
    }
    private static String read(SessionFactory factory) {
        try (var session = factory.openSession()) { return session.get(Record.class, 1L).label; }
    }
    private static String query(SessionFactory factory) {
        try (var session = factory.openSession()) {
            return session.createQuery("select p.label from CacheProbe p where p.id = 1", String.class)
                    .setCacheable(true).uniqueResult();
        }
    }
    private static void updateDirectly(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate("update cache_probe set label='new' where id=1"));
        }
    }
}
