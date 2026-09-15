/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class AddedCallbackOperationsReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void callbacksUseNativeTransactionsAndCachesAcrossSaves(boolean child) throws Exception {
        String h2 = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> Path.of(p).getFileName().toString().startsWith("h2-"))
                .collect(Collectors.joining(File.pathSeparator));
        assertFalse(h2.isEmpty());
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath() + File.pathSeparator + h2);
        if (child) builder.childClassLoader();
        try (var app = builder.jvmArgs("-Dprobe.dir=" + tmp).with("App", APP)
                .with("Service", service(0)).with("Receipt", "package app; public record Receipt(int id) { }").start()) {
            app.awaitOrFail("READY", "context did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "child loader missing");
            for (int stage = 1; stage <= 4; stage++) {
                app.rewrite("Service", service(stage));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                while (reloads(app) < stage && System.nanoTime() < until) Thread.sleep(25);
                assertEquals(stage, reloads(app), app.tail());
                Files.createFile(tmp.resolve("probe" + stage));
                app.awaitOrFail("R" + stage + "=", "callback probe failed");
                String expected = stage == 3 ? "removed tasks=0 rows=0 receipts=0 old=true"
                        : "rows=" + (stage == 2 ? 4 : 2) + " tx=" + (stage != 2)
                        + " cache=" + (stage == 2 ? "retained" : "evicted")
                        + " lookups=" + (stage == 2 ? 2 : 1) + " receipts=2 self=false old=true";
                assertEquals("R" + stage + "=" + expected, app.latest("R" + stage + "="), app.tail());
                System.out.println("[callback-operations] child=" + child + " " + app.latest("R" + stage + "="));
            }
        }
    }

    private long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Service ")
                || s.contains("Structural reload: app.Service ")).count();
    }
    private static String service(int version) {
        boolean added = version != 0 && version != 3;
        boolean advised = version == 1 || version == 4;
        String callbacks = !added ? "" : """
                @org.springframework.scheduling.annotation.Scheduled(initialDelay=3600000, fixedDelay=3600000)
                %s
                public void tick() throws Exception {
                    App.tx = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
                    jdbc.update("insert into entries values (?)", App.fail ? 2 : 1);
                    if (App.fail) throw new Exception("rollback");
                }
                @org.springframework.context.event.EventListener(condition="#p0 > 0")
                @org.springframework.core.annotation.Order(7)
                %s
                public void write(Integer id) throws Exception {
                    App.tx = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
                    jdbc.update("insert into entries values (?)", id);
                    if (id == 12) throw new Exception("rollback");
                }
                @org.springframework.context.event.EventListener
                %s
                public Receipt lookup(String key) { return new Receipt(++App.lookups); }
                @org.springframework.transaction.annotation.Transactional
                public boolean active() {
                    return org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
                }
                """.formatted(advised ? "@org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)\n"
                        + "@org.springframework.cache.annotation.CacheEvict(cacheNames=\"values\", allEntries=true)" : "",
                        advised ? "@org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)" : "",
                        advised ? "@org.springframework.cache.annotation.Cacheable(cacheNames=\"events\", key=\"#p0\")" : "");
        return """
                package app;
                @org.springframework.stereotype.Service
                public class Service {
                    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
                    public Service(org.springframework.jdbc.core.JdbcTemplate jdbc) { this.jdbc = jdbc; }
                    @org.springframework.transaction.annotation.Transactional
                    public boolean original() {
                        return org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
                    }
                    public int version() { return %d; }
                    public boolean self() { return %s; }
                    %s
                }
                """.formatted(version, added ? "active()" : "false", callbacks);
    }
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            import org.springframework.transaction.annotation.EnableTransactionManagement;
            import org.springframework.cache.annotation.EnableCaching;
            import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
            import org.springframework.jdbc.core.JdbcTemplate;
            import org.springframework.jdbc.datasource.DataSourceTransactionManager;
            import org.springframework.scheduling.annotation.*;
            @Configuration(proxyBeanMethods=false)
            @ComponentScan("app")
            @EnableTransactionManagement(proxyTargetClass=true, order=1)
            @EnableCaching(proxyTargetClass=true, order=0)
            @EnableScheduling
            public class App {
                public static boolean fail, tx;
                public static int lookups, receipts;
                @Bean public org.h2.jdbcx.JdbcDataSource dataSource() {
                    var ds = new org.h2.jdbcx.JdbcDataSource(); ds.setURL("jdbc:h2:mem:callbacks;DB_CLOSE_DELAY=-1"); return ds;
                }
                @Bean public JdbcTemplate jdbc(org.h2.jdbcx.JdbcDataSource ds) { return new JdbcTemplate(ds); }
                @Bean public org.springframework.transaction.PlatformTransactionManager transactionManager(org.h2.jdbcx.JdbcDataSource ds) {
                    return new DataSourceTransactionManager(ds);
                }
                @Bean public org.springframework.cache.CacheManager cacheManager() { return new ConcurrentMapCacheManager("values", "events"); }
                @org.springframework.context.event.EventListener public void receipt(Receipt value) { receipts++; }
                public static void main(String[] args) throws Exception {
                    try (var context = new AnnotationConfigApplicationContext(App.class)) {
                        var jdbc = context.getBean(JdbcTemplate.class);
                        jdbc.execute("create table entries (id int)");
                        Service old = context.getBean(Service.class);
                        if (!(old instanceof org.springframework.aop.framework.Advised))
                            throw new AssertionError("fixture requires an existing proxy");
                        // Deliberately leave original() cold until after the first reload.
                        System.out.println("READY");
                        for (int stage=1; stage<=4; stage++) {
                            while (!Files.exists(Path.of(System.getProperty("probe.dir"), "probe" + stage))) Thread.sleep(10);
                            jdbc.update("delete from entries"); fail=false; tx=false; lookups=0; receipts=0;
                            var manager = context.getBean(org.springframework.cache.CacheManager.class);
                            var values = manager.getCache("values"); values.clear(); values.put("keep", "value");
                            manager.getCache("events").clear();
                            var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
                            if (stage==3) {
                                context.publishEvent(11); context.publishEvent("key");
                                System.out.println("R3=removed tasks=" + tasks.size() + " rows=" + rows(jdbc)
                                    + " receipts=" + receipts + " old=" + old.original());
                                continue;
                            }
                            if (tasks.size()!=1) throw new AssertionError("expected one native scheduled task, got " + tasks.size());
                            // Invoke Spring's registered Runnable deterministically. The timer has
                            // a one-hour initial delay; no direct call to the added method is used.
                            Runnable task = tasks.iterator().next().getTask().getRunnable();
                            task.run();
                            String cache = values.get("keep")==null ? "evicted" : "retained";
                            values.put("keep", "after-success");
                            fail=true; expectRollback(task); fail=false;
                            if (values.get("keep")==null) throw new AssertionError("failed callback evicted the cache");
                            context.publishEvent(-1); // condition must reject before invocation
                            context.publishEvent(11);
                            expectRollback(() -> context.publishEvent(12));
                            context.publishEvent("key"); context.publishEvent("key");
                            System.out.println("R" + stage + "=rows=" + rows(jdbc) + " tx=" + tx + " cache=" + cache
                                + " lookups=" + lookups + " receipts=" + receipts
                                + " self=" + context.getBean(Service.class).self() + " old=" + old.original());
                        }
                    }
                }
                private static int rows(JdbcTemplate jdbc) { return jdbc.queryForObject("select count(*) from entries", Integer.class); }
                private static void expectRollback(Runnable action) {
                    try { action.run(); } catch (Throwable failure) {
                        while (failure.getCause()!=null) failure=failure.getCause();
                        if (!"rollback".equals(failure.getMessage())) throw new AssertionError(failure);
                        return;
                    }
                    throw new AssertionError("expected checked rollback exception");
                }
            }
            """;
}
