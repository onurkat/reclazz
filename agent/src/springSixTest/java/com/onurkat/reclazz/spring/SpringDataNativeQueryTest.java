/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import jakarta.persistence.*;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import java.lang.instrument.ClassDefinition;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SpringDataNativeQueryTest {
    @Entity(name="QueryRecord") @Table(name="query_record")
    public static class Record { @Id public long id; public String label; }
    public interface Records extends JpaRepository<Record, Long> {
        @Query("select r.label from QueryRecord r where r.id = 1") String selectedLabel();
    }
    @org.springframework.stereotype.Repository
    public interface AnnotatedRecords extends JpaRepository<Record, Long> {}
    public static class Holder { public Records records; }
    public static class TrackedHolder { final Records records; TrackedHolder(Records r) { records=r; } }

    @Test void changedNativeQueryReachesExistingManuallyWiredHolderAndTrackedDependent() throws Exception {
        var instrumentation = ByteBuddyAgent.install();
        byte[] original;
        try (var in = Records.class.getResourceAsStream("/" + Records.class.getName().replace('.', '/') + ".class")) {
            original = in.readAllBytes();
        }
        try (var ctx = context()) {
            var before = ctx.getBean(Records.class); var holder = ctx.getBean(Holder.class); holder.records = before;
            assertEquals("before", before.selectedLabel());
            for (int id : new int[]{2, 1, 2}) {
                var writer = new ClassWriter(0);
                new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e) {
                        return new MethodVisitor(Opcodes.ASM9, super.visitMethod(a,n,d,s,e)) {
                            @Override public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                                var delegate = super.visitAnnotation(desc,visible);
                                if (!desc.equals("Lorg/springframework/data/jpa/repository/Query;")) return delegate;
                                return new AnnotationVisitor(Opcodes.ASM9,delegate) {
                                    @Override public void visit(String name,Object value) {
                                        super.visit(name, name.equals("value") ? "select r.label from QueryRecord r where r.id = " + id : value);
                                    }
                                };
                            }
                        };
                    }
                },0);
                byte[] changed = writer.toByteArray();
                var previous = holder.records; var previousResult = previous.selectedLabel();
                instrumentation.redefineClasses(new ClassDefinition(Records.class, changed));
                assertEquals(previousResult, previous.selectedLabel(), "negative witness: existing native query is cached");
                new SpringReloadOrchestrator(SpringDataRefreshTest.platform(ctx)).onClassReloaded(
                        Records.class.getName(),Records.class,false,true,false,Set.of(),changed);
                String expected = id == 1 ? "before" : "after";
                assertNotSame(previous,ctx.getBean(Records.class));
                assertSame(holder,ctx.getBean(Holder.class));
                assertSame(ctx.getBean(Records.class),holder.records);
                assertEquals(expected,holder.records.selectedLabel());
                assertEquals(expected,ctx.getBean(TrackedHolder.class).records.selectedLabel());
            }
        } finally { instrumentation.redefineClasses(new ClassDefinition(Records.class,original)); }
    }

    @Test void annotatedRepositoryDoesNotRecreateTwice() {
        try (var ctx = context()) {
            var before = ctx.getBean(AnnotatedRecords.class);
            int count = factories;
            new SpringReloadOrchestrator(SpringDataRefreshTest.platform(ctx)).onClassReloaded(
                    AnnotatedRecords.class.getName(),AnnotatedRecords.class,false);
            assertNotSame(before,ctx.getBean(AnnotatedRecords.class));
            assertEquals(1,factories-count);
            assertEquals(2,ctx.getBean(AnnotatedRecords.class).count());
        }
    }

    private static int factories;
    private static Class<?> lookupDuringCreation;
    private static int lookupResult = -1;

    @Test void typeLookupDuringFactoryRecreationDoesNotPoisonFutureLookup() {
        try (var ctx = context()) {
            ctx.getBean(Records.class);
            lookupDuringCreation = Records.class; lookupResult = -1;
            try {
                assertTrue(new SpringDataReloader(SpringDataRefreshTest.platform(ctx)).reloadRepository(Records.class));
                assertEquals(0, lookupResult, "negative witness: lookup while factory is creating can see no product type");
                assertNotNull(ctx.getBean(Records.class), "a transient type lookup must not remain cached after recreation");
            } finally { lookupDuringCreation = null; }
        }
    }
    private static GenericApplicationContext context() {
        var ctx = new GenericApplicationContext();
        ctx.registerBean("entityManagerFactory",LocalContainerEntityManagerFactoryBean.class,() -> {
            var f = new LocalContainerEntityManagerFactoryBean();
            f.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:q" + System.nanoTime() + ";DB_CLOSE_DELAY=-1","sa",""));
            f.setManagedTypes(PersistenceManagedTypes.of(Record.class.getName()));
            f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create-drop","hibernate.archive.autodetection","none"));
            return f;
        });
        ctx.registerBean("transactionManager",JpaTransactionManager.class,() -> new JpaTransactionManager(ctx.getBean(EntityManagerFactory.class)));
        ctx.registerBean("records",JpaRepositoryFactoryBean.class,() -> factory(ctx,Records.class));
        ctx.registerBean("annotated",JpaRepositoryFactoryBean.class,() -> factory(ctx,AnnotatedRecords.class));
        ctx.registerBean("holder",Holder.class);
        ctx.registerBean("tracked",TrackedHolder.class,() -> new TrackedHolder(ctx.getBean(Records.class)));
        ctx.refresh();
        try (var em=ctx.getBean(EntityManagerFactory.class).createEntityManager()) {
            em.getTransaction().begin();
            for (int id=1;id<=2;id++) { var r=new Record();r.id=id;r.label=id==1?"before":"after";em.persist(r); }
            em.getTransaction().commit();
        }
        return ctx;
    }
    private static <T extends org.springframework.data.repository.Repository<Record,Long>> JpaRepositoryFactoryBean<T,Record,Long>
    factory(GenericApplicationContext ctx,Class<T> type) {
        factories++;
        if (type == lookupDuringCreation) lookupResult = ctx.getBeanNamesForType(type).length;
        var f=new JpaRepositoryFactoryBean<T,Record,Long>(type);
        f.setEntityManager(SharedEntityManagerCreator.createSharedEntityManager(ctx.getBean(EntityManagerFactory.class)));
        f.setEntityPathResolver(ctx.getBeanProvider(org.springframework.data.querydsl.EntityPathResolver.class));
        return f;
    }
}
