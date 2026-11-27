/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.ui.RestartLedger;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JpaPreparedSchemaTest {
    @Entity(name="PreparedExisting") @Table(name="existing_record")
    public static class Existing { @Id public long id; public String label; public Existing() {} }
    @Entity(name="PreparedAdded") @Table(name="added_record")
    public static class Added { @Id public long id; public String label; public Added() {} }

    @BeforeEach void setup() { RestartLedger.clear(); ApplicationContextHolder.clear(); JpaMappingRefresh.configure(() -> true, () -> false); }
    @AfterEach void cleanup() { RestartLedger.clear(); ApplicationContextHolder.clear(); JpaMappingRefresh.configure(null, null); }

    @Test void heldSessionAndDelayedAcquireKeepTheirMappedRowAfterRebuild() throws Exception {
        try (var fixture = new Fixture("validate")) {
            fixture.sql("create table added_record(id bigint primary key, label varchar(255))");
            var previous = fixture.factory.getNativeEntityManagerFactory();
            try (var held = previous.createEntityManager()) {
                var resolved = new java.util.concurrent.CountDownLatch(1);
                var resume = new java.util.concurrent.CountDownLatch(1);
                var delayed = new java.util.concurrent.FutureTask<String>(() -> {
                    var captured = fixture.factory.getNativeEntityManagerFactory();
                    resolved.countDown();
                    if (!resume.await(20, java.util.concurrent.TimeUnit.SECONDS))
                        throw new AssertionError("rebuild did not finish");
                    try (var late = captured.createEntityManager()) { return late.find(Existing.class, 1L).label; }
                });
                Thread thread = new Thread(delayed, "delayed-persistence-request");
                thread.setDaemon(true);
                thread.start();
                try {
                    assertTrue(resolved.await(20, java.util.concurrent.TimeUnit.SECONDS));
                    JpaMappingRefresh.applyForNewEntity(Added.class.getName(), Added.class);
                } finally {
                    resume.countDown();
                }
                assertNotSame(previous, fixture.factory.getNativeEntityManagerFactory());
                assertEquals("kept", delayed.get(20, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals("kept", held.find(Existing.class, 1L).label);
                assertNotNull(fixture.client.getMetamodel().entity(Added.class));
            }
        }
    }

    @Test void preparedTableMapsThroughTheAlreadyInjectedClientAndSharedEntityManager() throws Exception {
        try (var f = new Fixture("validate")) {
            f.sql("create table added_record(id bigint primary key, label varchar(255))");
            var old = f.factory.getNativeEntityManagerFactory();
            var shared = SharedEntityManagerCreator.createSharedEntityManager(f.client);
            JpaMappingRefresh.applyForNewEntity(Added.class.getName(), Added.class);
            assertNotSame(old, f.factory.getNativeEntityManagerFactory(), "validate must admit a prepared schema");
            assertTrue(old.isOpen());
            Added record = new Added(); record.id=2; record.label="new";
            try (var em = f.client.createEntityManager()) {
                em.getTransaction().begin(); em.persist(record); em.getTransaction().commit();
            }
            assertEquals("new", shared.find(Added.class, 2L).label);
            assertEquals("kept", shared.find(Existing.class, 1L).label);
            var installed = f.factory.getNativeEntityManagerFactory();
            JpaMappingRefresh.applyForNewEntity(Added.class.getName(), Added.class);
            assertSame(installed, f.factory.getNativeEntityManagerFactory(), "already mapped is not a new entity");
        }
    }

    @ParameterizedTest @ValueSource(strings={"missing-table","missing-column","wrong-type"})
    void validationFailureKeepsLiveFactoryAndManagedNamesThenCanRetry(String problem) throws Exception {
        try (var f = new Fixture("validate")) {
            if (problem.equals("missing-column")) f.sql("create table added_record(id bigint primary key)");
            if (problem.equals("wrong-type")) f.sql("create table added_record(id bigint primary key, label bigint)");
            var old = f.factory.getNativeEntityManagerFactory();
            var managed = List.copyOf(f.factory.getPersistenceUnitInfo().getManagedClassNames());
            List<String> messages = new ArrayList<>();
            StatusReporter.StatusListener listener = (level, message) -> messages.add(message);
            StatusReporter.addListener(listener);
            try { JpaMappingRefresh.applyForNewEntity(Added.class.getName(), Added.class); }
            finally { StatusReporter.removeListener(listener); }
            assertSame(old, f.factory.getNativeEntityManagerFactory()); assertTrue(old.isOpen());
            assertEquals(managed, f.factory.getPersistenceUnitInfo().getManagedClassNames());
            assertEquals("kept", SharedEntityManagerCreator.createSharedEntityManager(f.client).find(Existing.class,1L).label);
            assertTrue(messages.stream().anyMatch(s -> s.contains("validation failed")), messages.toString());
            assertTrue(RestartLedger.digest().stream().anyMatch(m->m.contains("prepared-schema mapping is not installed")));
            RestartLedger.note(Added.class.getName(),"separate structural concern");
            f.sql("drop table if exists added_record");
            f.sql("create table added_record(id bigint primary key, label varchar(255))");
            JpaMappingRefresh.applyForNewEntity(Added.class.getName(), Added.class);
            assertNotSame(old, f.factory.getNativeEntityManagerFactory());
            assertNotNull(f.client.getMetamodel().entity(Added.class));
            assertFalse(RestartLedger.digest().stream().anyMatch(m->m.contains("prepared-schema mapping is not installed")));
            assertTrue(RestartLedger.digest().stream().anyMatch(m->m.contains("separate structural concern")));
        }
    }

    @Test void existingUpdateModeStillCreatesAndMapsTheTable() throws Exception {
        try (var f = new Fixture("update")) {
            JpaMappingRefresh.applyForNewEntity(Added.class.getName(), Added.class);
            assertNotNull(f.client.getMetamodel().entity(Added.class));
            f.sql("insert into added_record values(2, 'new')");
            assertEquals("new", SharedEntityManagerCreator.createSharedEntityManager(f.client).find(Added.class,2L).label);
        }
    }

    @ParameterizedTest @ValueSource(strings={
            "jakarta.persistence.schema-generation.database.action",
            "javax.persistence.schema-generation.database.action",
            "jakarta.persistence.schema-generation.scripts.action",
            "javax.persistence.schema-generation.scripts.action",
            "jakarta.persistence.schema-generation.database.action.orm",
            "hibernate.hbm2ddl.auto.orm", "hibernate.schema_management_tool",
            "hibernate.hbm2ddl.schema_filter_provider"})
    void schemaOverridesCannotTurnValidationIntoDdlOrSkipIt(String key) throws Exception {
        try (var f = new Fixture("validate")) {
            f.sql("create table added_record(id bigint primary key, label varchar(255))");
            var old = f.factory.getNativeEntityManagerFactory();
            for (boolean unit : new boolean[]{false,true}) {
                Map target = unit ? f.factory.getPersistenceUnitInfo().getProperties() : f.factory.getJpaPropertyMap();
                target.put(key,"none"); // Even JPA action=none can suppress Hibernate validate.
                try {
                    assertNotNull(JpaPreparedSchemaRefresh.policyProblem(f.factory));
                    JpaMappingRefresh.applyForNewEntity(Added.class.getName(),Added.class);
                    assertSame(old,f.factory.getNativeEntityManagerFactory()); assertTrue(old.isOpen());
                    assertFalse(f.factory.getPersistenceUnitInfo().getManagedClassNames().contains(Added.class.getName()));
                } finally { target.remove(key); }
            }
            assertNull(JpaPreparedSchemaRefresh.policyProblem(f.factory));
        }
    }

    @Test void changingTheCandidateActionOrProviderRequiresRestart() throws Exception {
        try (var f = new Fixture("validate")) {
            var old=f.factory.getNativeEntityManagerFactory();
            f.factory.getJpaPropertyMap().put("hibernate.hbm2ddl.auto","create");
            JpaMappingRefresh.applyForNewEntity(Added.class.getName(),Added.class);
            assertSame(old,f.factory.getNativeEntityManagerFactory());
            assertEquals("kept",SharedEntityManagerCreator.createSharedEntityManager(f.client).find(Existing.class,1L).label);
            f.factory.getJpaPropertyMap().put("hibernate.hbm2ddl.auto","validate");
            f.factory.setPersistenceProvider(new org.hibernate.jpa.HibernatePersistenceProvider() {});
            JpaMappingRefresh.applyForNewEntity(Added.class.getName(),Added.class);
            assertSame(old,f.factory.getNativeEntityManagerFactory());
        }
    }

    @Test void absentOptInAndAmbiguousUnitsKeepTheirIdentityAndData() throws Exception {
        try (var first = new Fixture("validate")) {
            first.sql("create table added_record(id bigint primary key, label varchar(255))");
            var old=first.factory.getNativeEntityManagerFactory();
            JpaMappingRefresh.configure(()->false,()->false);
            JpaMappingRefresh.applyForNewEntity(Added.class.getName(),Added.class);
            assertSame(old,first.factory.getNativeEntityManagerFactory());
            JpaMappingRefresh.configure(()->true,()->false);
            try (var second = new Fixture("validate")) {
                var other=second.factory.getNativeEntityManagerFactory();
                JpaMappingRefresh.applyForNewEntity(Added.class.getName(),Added.class);
                assertSame(old,first.factory.getNativeEntityManagerFactory());
                assertSame(other,second.factory.getNativeEntityManagerFactory());
                assertTrue(old.isOpen()); assertTrue(other.isOpen());
            }
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "hibernate.session_factory_name,shared", "hibernate.session_factory_name_is_jndi,true",
            "hibernate.jndi.url,shared", "hibernate.jmx.enabled,true", "hibernate.cache.use_query_cache,true",
            "hibernate.cache.region.factory_class,jcache", "hibernate.stats.factory,custom",
            "hibernate.session_factory_observer,custom", "spring.jpa.hibernate.ddl-auto,create",
            "jakarta.persistence.schema-generation.database.action,drop-and-create",
            "javax.persistence.schema-generation.database.action,drop"})
    void unsafeOverrideHoldsBeforeChangingManagedNamesOrMappedRows(String key, String value) throws Exception {
        try (var f = new Fixture("update")) {
            var old = f.factory.getNativeEntityManagerFactory();
            var before = List.copyOf(f.factory.getPersistenceUnitInfo().getManagedClassNames());
            f.factory.getJpaPropertyMap().put(key,value);
            try (var held = old.createEntityManager()) {
                JpaMappingRefresh.applyForNewEntity(Added.class.getName(),Added.class);
                assertSame(old,f.factory.getNativeEntityManagerFactory());
                assertEquals(before,f.factory.getPersistenceUnitInfo().getManagedClassNames());
                assertEquals("kept",held.find(Existing.class,1L).label);
                assertTrue(RestartLedger.digest().stream().anyMatch(note -> note.contains(key) && note.contains("restart")),
                        RestartLedger.digest().toString());
            }
        }
    }

    @Test void existingEntityRebuildKeepsDelayedAndOpenRequestsWorking() throws Exception {
        try (var f = new Fixture("update")) {
            JpaMappingRefresh.configure(() -> true, () -> true);
            var old = f.factory.getNativeEntityManagerFactory();
            try (var held = old.createEntityManager()) {
                var result = JpaMappingRefresh.apply(Existing.class.getName(), Existing.class,
                        new JpaEntityChange.Change(List.of(), List.of("label")));
                assertTrue(result.refreshed(), result.appendix());
                assertNotSame(old, f.factory.getNativeEntityManagerFactory());
                try (var delayed = old.createEntityManager(); var fresh = f.client.createEntityManager()) {
                    assertEquals("kept", held.find(Existing.class,1L).label);
                    assertEquals("kept", delayed.find(Existing.class,1L).label);
                    assertEquals("kept", fresh.find(Existing.class,1L).label);
                }
            }
        }
    }

    @Test void noCachingRegionFactorySpellingsAllowRetentionWhileARealRegionFactoryHolds() {
        String key = "hibernate.cache.region.factory_class";
        Class<?> none = org.hibernate.cache.internal.NoCachingRegionFactory.class;
        for (Object value : List.of("none", none.getName(), none, org.hibernate.cache.internal.NoCachingRegionFactory.INSTANCE)) {
            assertNull(JpaFactoryRetention.settingProblem(Map.of(key, value)), String.valueOf(value));
        }
        assertTrue(JpaFactoryRetention.settingProblem(Map.of(key,"jcache")).contains(key));
    }

    @Test void concurrentRebuildAttemptsRetainAtMostFiveFactoriesUntilContextClose() throws Exception {
        var previous = new java.util.concurrent.CopyOnWriteArrayList<EntityManagerFactory>();
        List<Integer> closed = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (var f = new Fixture("update")) {
            var field = org.springframework.orm.jpa.AbstractEntityManagerFactoryBean.class.getDeclaredField("nativeEntityManagerFactory");
            field.setAccessible(true);
            var create = LocalContainerEntityManagerFactoryBean.class.getDeclaredMethod("createNativeEntityManagerFactory");
            create.setAccessible(true);
            var workers = java.util.concurrent.Executors.newFixedThreadPool(6);
            var start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
            try {
                for (int i = 0; i < 6; i++) results.add(workers.submit(() -> {
                    start.await();
                    try (var reservation = JpaFactoryRetention.reserve(f.factory, field)) {
                        var old = (EntityManagerFactory) field.get(f.factory);
                        var fresh = (EntityManagerFactory) create.invoke(f.factory);
                        reservation.install(f.factory, field, fresh);
                        previous.add(old);
                        return true;
                    } catch (JpaFactoryRetention.Hold full) {
                        assertTrue(full.getMessage().contains("5 persistence rebuilds"), full.getMessage());
                        return false;
                    }
                }));
                start.countDown();
                int accepted = 0;
                for (var result : results) if (result.get(30, java.util.concurrent.TimeUnit.SECONDS)) accepted++;
                assertEquals(5, accepted);
                for (int i = 0; i < previous.size(); i++) {
                    int generation = i;
                    var old = previous.get(i);
                    ((org.hibernate.internal.SessionFactoryImpl) old).addObserver(new org.hibernate.SessionFactoryObserver() {
                        @Override public void sessionFactoryClosed(org.hibernate.SessionFactory factory) { closed.add(generation); }
                    });
                    try (var em = old.createEntityManager()) { assertEquals("kept", em.find(Existing.class, 1L).label); }
                }
            } finally { workers.shutdownNow(); }
        }
        assertEquals(List.of(4, 3, 2, 1, 0), closed);
        assertTrue(previous.stream().noneMatch(EntityManagerFactory::isOpen));
    }

    @Test void candidateMissingTheEntityClosesWithoutReplacingTheLiveFactory() throws Exception {
        try (var first = new Fixture("validate"); var candidate = new Fixture("validate")) {
            var old=first.factory.getNativeEntityManagerFactory();
            var fresh=candidate.factory.getNativeEntityManagerFactory();
            Holder holder=new Holder(old);
            var field=Holder.class.getDeclaredField("factory");
            var nativeField = org.springframework.orm.jpa.AbstractEntityManagerFactoryBean.class.getDeclaredField("nativeEntityManagerFactory");
            nativeField.setAccessible(true);
            try (var reservation = JpaFactoryRetention.reserve(first.factory, nativeField)) {
                assertFalse(JpaPreparedSchemaRefresh.install(Added.class,holder,field,fresh,reservation));
            }
            assertSame(old,holder.factory); assertTrue(old.isOpen()); assertFalse(fresh.isOpen());
            assertEquals("kept",SharedEntityManagerCreator.createSharedEntityManager(first.client).find(Existing.class,1L).label);
        }
    }

    @Test void retainedFactoriesCloseAtContextShutdownAndContinueAfterOneCloseFails() throws Exception {
        var f = new Fixture("update");
        var old = f.factory.getNativeEntityManagerFactory();
        List<String> messages = new ArrayList<>();
        StatusReporter.StatusListener listener = (level,message) -> messages.add(message);
        StatusReporter.addListener(listener);
        try (f) {
            JpaMappingRefresh.applyForNewEntity(Added.class.getName(),Added.class);
            var fresh = f.factory.getNativeEntityManagerFactory();
            var nativeField = org.springframework.orm.jpa.AbstractEntityManagerFactoryBean.class.getDeclaredField("nativeEntityManagerFactory");
            nativeField.setAccessible(true);
            Holder holder = new Holder(new FailingClose());
            try (var reservation = JpaFactoryRetention.reserve(f.factory, nativeField)) {
                assertTrue(JpaPreparedSchemaRefresh.install(Added.class,holder,Holder.class.getDeclaredField("factory"),fresh,reservation));
            }
            assertTrue(old.isOpen());
            assertTrue(messages.stream().noneMatch(m -> m.contains("Closing a retained persistence factory failed")));
        } finally { StatusReporter.removeListener(listener); }
        assertFalse(old.isOpen());
        assertEquals(1, messages.stream().filter(m -> m.contains("Closing a retained persistence factory failed")).count());
    }
    public static final class FailingClose { public void close() { throw new IllegalStateException("close failed"); } }
    static final class Holder { Object factory; Holder(Object value) { factory=value; } }

    static final class Fixture implements AutoCloseable {
        final String url="jdbc:h2:mem:prepared_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        final GenericApplicationContext context=new GenericApplicationContext();
        final LocalContainerEntityManagerFactoryBean factory=new LocalContainerEntityManagerFactoryBean();
        final EntityManagerFactory client;
        Fixture(String action) throws Exception {
            sql("create table existing_record(id bigint primary key, label varchar(255))");
            sql("insert into existing_record values(1,'kept')");
            factory.setDataSource(new DriverManagerDataSource(url,"sa",""));
            factory.setManagedTypes(PersistenceManagedTypes.of(Existing.class.getName()));
            factory.setPersistenceUnitName("prepared");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto",action,"hibernate.archive.autodetection","none"));
            context.registerBean("emf",LocalContainerEntityManagerFactoryBean.class,()->factory);
            context.refresh(); ApplicationContextHolder.register(context);
            client=context.getBean(EntityManagerFactory.class);
        }
        void sql(String sql) throws Exception {
            try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){s.execute(sql);}
        }
        @Override public void close() throws Exception { context.close(); sql("shutdown"); }
    }
}
