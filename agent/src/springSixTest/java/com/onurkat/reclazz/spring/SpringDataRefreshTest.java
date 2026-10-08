/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.ReloadEffects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.repository.Repository;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SpringDataRefreshTest {
    interface Repo extends Repository<String, Long> { int generation(); }
    interface Base extends Repo {}
    interface DeepBase extends Base {}
    interface Deep extends DeepBase {}
    static class Product implements Deep {
        static int created;
        final int generation = ++created;
        public int generation() { return generation; }
    }
    public static class Factory implements FactoryBean<Deep> {
        static int objects;
        public Deep getObject() { objects++; return new Product(); }
        public Class<?> getObjectType() { return Deep.class; }
        public boolean isSingleton() { return true; }
    }
    public static class BrokenFactory extends Factory {
        static boolean broken;
        @Override public Deep getObject() {
            if (broken) throw new IllegalStateException("deliberate product failure");
            return super.getObject();
        }
    }
    public static class Holder { public Repo repo; }
    @org.springframework.stereotype.Repository
    public static class Dao { static int created; public final int generation = ++created; }
    public static class DaoHolder { public Dao dao; }
    @Retention(RetentionPolicy.RUNTIME) @interface NotARepository {}
    @NotARepository public static class Ordinary {}

    @Test void ordinaryRepositoryDaoIsRecreatedOnlyOnceAndHolderUsesLiveBean() {
        try (var ctx = new GenericApplicationContext()) {
            ctx.registerBean("dao", Dao.class); ctx.registerBean("holder", DaoHolder.class); ctx.refresh();
            var holder = ctx.getBean(DaoHolder.class); holder.dao = ctx.getBean(Dao.class);
            int before = Dao.created;
            new SpringReloadOrchestrator(platform(ctx)).onClassReloaded(Dao.class.getName(), Dao.class, false);
            assertEquals(1, Dao.created - before);
            assertSame(ctx.getBean(Dao.class), holder.dao);
        }
    }

    @Test void unrelatedRepositoryAnnotationDoesNotTriggerDataRefresh() {
        try (var ctx = new GenericApplicationContext()) {
            ctx.registerBean("ordinary", Ordinary.class); ctx.refresh(); var before = ctx.getBean(Ordinary.class);
            assertFalse(new SpringDataReloader(platform(ctx)).reloadRepository(Ordinary.class));
            assertSame(before, ctx.getBean(Ordinary.class));
        }
    }

    @Test void deepRepositoryHierarchyRefreshesItsProductAndHolder() {
        try (var ctx = context("one")) {
            var before = ctx.getBean(Deep.class); var holder = ctx.getBean(Holder.class); holder.repo = before;
            assertTrue(new SpringDataReloader(platform(ctx)).reloadRepository(Deep.class));
            assertNotSame(before, ctx.getBean(Deep.class));
            assertSame(ctx.getBean(Deep.class), holder.repo);
        }
    }

    @Test void allMatchingNamesAndContextsAreRefreshedWithoutCrossWiring() {
        try (var first = context("one", "two"); var second = context("three")) {
            var one = (Repo) first.getBean("one"); var two = (Repo) first.getBean("two"); var three = (Repo) second.getBean("three");
            first.getBean(Holder.class).repo = one; second.getBean(Holder.class).repo = three;
            assertTrue(new SpringDataReloader(platform(first, second)).reloadRepository(Deep.class));
            assertNotSame(one, first.getBean("one")); assertNotSame(two, first.getBean("two"));
            assertNotSame(three, second.getBean("three"));
            assertSame(first.getBean("one"), first.getBean(Holder.class).repo);
            assertSame(second.getBean("three"), second.getBean(Holder.class).repo);
            assertNotSame(first.getBean(Holder.class).repo, second.getBean(Holder.class).repo);
        }
    }

    @Test void coldFactoryProductIsNotCreatedDuringRefresh() {
        try (var ctx = context("one")) {
            int objects = Factory.objects;
            assertTrue(ctx.getBeanFactory().containsSingleton("one"));
            assertFalse(new SpringDataReloader(platform(ctx)).reloadRepository(Repo.class));
            assertEquals(objects, Factory.objects);
            assertNotNull(ctx.getBean(Deep.class));
            assertEquals(objects + 1, Factory.objects);
        }
    }

    @Test void completelyLazyFactoryRemainsUninitialized() {
        try (var ctx = new GenericApplicationContext()) {
            ctx.registerBean("one", Factory.class, bd -> bd.setLazyInit(true)); ctx.refresh();
            assertFalse(ctx.getBeanFactory().containsSingleton("one"));
            int objects = Factory.objects;
            assertFalse(new SpringDataReloader(platform(ctx)).reloadRepository(Repo.class));
            assertFalse(ctx.getBeanFactory().containsSingleton("one")); assertEquals(objects, Factory.objects);
        }
    }

    @Test void failedProductCreationNeverClaimsSuccess() {
        try (var ctx = new GenericApplicationContext()) {
            ctx.registerBean("one", BrokenFactory.class); ctx.refresh(); ctx.getBean("one");
            BrokenFactory.broken = true; ReloadEffects.begin();
            try {
                assertFalse(new SpringDataReloader(platform(ctx)).reloadRepository(Repo.class));
                assertFalse(ReloadEffects.end().contains("repository re-created"));
            } finally { BrokenFactory.broken = false; ReloadEffects.end(); }
        }
    }

    @Test void missingRepositoryNeverClaimsSuccess() {
        try (var ctx = new GenericApplicationContext()) {
            ctx.refresh(); ReloadEffects.begin();
            try { assertFalse(new SpringDataReloader(platform(ctx)).reloadRepository(Repo.class));
                assertEquals("", ReloadEffects.end()); }
            finally { ReloadEffects.end(); }
        }
    }

    private static GenericApplicationContext context(String... names) {
        var ctx = new GenericApplicationContext();
        for (String name : names) ctx.registerBean(name, Factory.class);
        ctx.registerBean("holder", Holder.class); ctx.refresh(); return ctx;
    }

    static PlatformContext platform(Object... contexts) {
        return (PlatformContext) Proxy.newProxyInstance(PlatformContext.class.getClassLoader(), new Class<?>[]{PlatformContext.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getAllApplicationContexts" -> List.of(contexts);
                    case "getApplicationContext" -> contexts.length == 0 ? null : contexts[0];
                    case "getPlatformId" -> PlatformContext.Platform.GENERIC;
                    case "getClassOutputDirs", "getSourceDirs", "getResourceDirs" -> Map.of();
                    case "resolveClasspath" -> "";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }
}
