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

    @Test void preparedTableMapsThroughTheAlreadyInjectedClientAndSharedEntityManager() throws Exception {
        try (var f = new Fixture("validate")) {
            f.sql("create table added_record(id bigint primary key, label varchar(255))");
            var old = f.factory.getNativeEntityManagerFactory();
            var shared = SharedEntityManagerCreator.createSharedEntityManager(f.client);
            JpaMappingRefresh.applyForNewEntity(Added.class.getName(), Added.class);
            assertNotSame(old, f.factory.getNativeEntityManagerFactory(), "validate must admit a prepared schema");
            assertFalse(old.isOpen());
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

    @Test void candidateMissingTheEntityClosesWithoutReplacingTheLiveFactory() throws Exception {
        try (var first = new Fixture("validate"); var candidate = new Fixture("validate")) {
            var old=first.factory.getNativeEntityManagerFactory();
            var fresh=candidate.factory.getNativeEntityManagerFactory();
            Holder holder=new Holder(old);
            var field=Holder.class.getDeclaredField("factory");
            assertFalse(JpaPreparedSchemaRefresh.install(Added.class,holder,field,fresh));
            assertSame(old,holder.factory); assertTrue(old.isOpen()); assertFalse(fresh.isOpen());
            assertEquals("kept",SharedEntityManagerCreator.createSharedEntityManager(first.client).find(Existing.class,1L).label);
        }
    }

    @Test void closeFailureAfterSwapIsReportedWithoutClaimingTheOldFactoryWasRetained() throws Exception {
        try (var f = new Fixture("update")) {
            JpaMappingRefresh.applyForNewEntity(Added.class.getName(),Added.class);
            var fresh=f.factory.getNativeEntityManagerFactory();
            Holder holder=new Holder(new FailingClose());
            List<String> messages=new ArrayList<>();
            StatusReporter.StatusListener listener=(level,message)->messages.add(message);
            StatusReporter.addListener(listener);
            try { assertTrue(JpaPreparedSchemaRefresh.install(Added.class,holder,Holder.class.getDeclaredField("factory"),fresh)); }
            finally { StatusReporter.removeListener(listener); }
            assertSame(fresh,holder.factory); assertTrue(fresh.isOpen());
            assertTrue(messages.stream().anyMatch(m->m.contains("mapping installed")&&m.contains("retiring")),messages.toString());
        }
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
