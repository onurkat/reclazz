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
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class TransactionProxyValueReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest @ValueSource(booleans={false,true})
    void watchedSettingsRecreateBothTransactionProductsAndRetainRollback(boolean child) throws Exception {
        String h2=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p->Path.of(p).getFileName().toString().startsWith("h2-"))
                .collect(Collectors.joining(File.pathSeparator));
        assertFalse(h2.isEmpty());
        Path properties=Files.createDirectories(tmp.resolve("classes")).resolve("application.properties");
        Files.writeString(properties,"cfg.seconds=5\n");
        var builder=WatchedApp.in(tmp).classpath(WatchedApp.springClasspath()+File.pathSeparator+h2)
                .jvmArgs("-Dprobe.dir="+tmp).with("App",APP);
        if(child)builder.childClassLoader();
        try(var app=builder.start()) {
            app.awaitOrFail("READY=5000:5000:2:10000","initial native transactions failed");
            app.awaitOrFail("] Watching 1 director","watcher did not start");
            if(child)app.awaitOrFail("APP_IN_CHILD_MODULE=true","child loader missing");
            Files.writeString(properties,"cfg.seconds=8\n");
            awaitApplied(app,1);
            probe(app,1,"8000:8000:4:26000:4:2:healed");
            Files.writeString(properties,"cfg.seconds=1 / 0\n");
            app.awaitOrFail("Rejected: the running configuration is unchanged","invalid candidate was not rejected");
            probe(app,2,"8000:8000:6:42000:4:2:healed");
            Files.writeString(properties,"cfg.seconds=10\n");
            awaitApplied(app,2);
            probe(app,3,"10000:10000:8:62000:6:4:healed");
        }
    }
    private static void awaitApplied(WatchedApp app,int count) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline&&app.output().stream().filter(s->s.contains("Applied 1 property change")).count()<count)
            Thread.sleep(25);
        assertEquals(count,app.output().stream().filter(s->s.contains("Applied 1 property change")).count(),app.tail());
    }
    private void probe(WatchedApp app,int stage,String expected) throws Exception {
        Files.createFile(tmp.resolve("probe"+stage));
        app.awaitOrFail("PROBE"+stage+"=","transaction probe missing");
        String actual=app.latest("PROBE"+stage+"=");
        System.out.println("[tx-proxy-value] "+actual);
        assertEquals("PROBE"+stage+"="+expected,actual,app.tail());
    }
    private static final String APP="""
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            import org.springframework.beans.factory.*;
            import org.springframework.beans.factory.annotation.Value;
            import org.springframework.jdbc.core.JdbcTemplate;
            import org.springframework.jdbc.datasource.DataSourceTransactionManager;
            import org.springframework.transaction.annotation.*;
            import org.springframework.transaction.support.TransactionSynchronizationManager;
            @Configuration
            @EnableTransactionManagement
            @PropertySource("classpath:application.properties")
            public class App {
                public interface Api { long setting(); void save(boolean fail); }
                public static class Service implements Api, DisposableBean {
                    static int built, destroyed;
                    final JdbcTemplate jdbc;
                    final long millis;
                    public Service(JdbcTemplate jdbc,@Value("#{${cfg.seconds} * 1000L}") long millis) {
                        this.jdbc=jdbc; this.millis=millis; built++;
                    }
                    public long setting() { return millis; }
                    @Transactional public void save(boolean fail) {
                        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new AssertionError("transaction missing");
                        jdbc.update("insert into entries(amount) values (?)",millis);
                        if(fail)throw new IllegalArgumentException("rollback");
                    }
                    public void destroy() { destroyed++; }
                }
                @Bean public org.h2.jdbcx.JdbcDataSource dataSource() {
                    var ds=new org.h2.jdbcx.JdbcDataSource(); ds.setURL("jdbc:h2:mem:tx_properties;DB_CLOSE_DELAY=-1"); return ds;
                }
                @Bean public JdbcTemplate jdbc(org.h2.jdbcx.JdbcDataSource ds) { return new JdbcTemplate(ds); }
                @Bean public org.springframework.transaction.PlatformTransactionManager transactionManager(org.h2.jdbcx.JdbcDataSource ds) {
                    return new DataSourceTransactionManager(ds);
                }
                @Bean public Api produced(JdbcTemplate jdbc,@Value("#{${cfg.seconds} * 1000L}") long millis) {
                    return new Service(jdbc,millis);
                }
                public static class Holder {
                    Api constructed, produced;
                    Holder(Api c,Api p) { constructed=c; produced=p; }
                }
                static void exercise(Api bean) {
                    bean.save(false);
                    try { bean.save(true); throw new AssertionError("expected rollback exception"); }
                    catch(IllegalArgumentException expected) { }
                }
                static String result(Holder h,JdbcTemplate jdbc) {
                    exercise(h.constructed); exercise(h.produced);
                    return h.constructed.setting()+":"+h.produced.setting()+":"
                        +jdbc.queryForObject("select count(*) from entries",Integer.class)+":"
                        +jdbc.queryForObject("select sum(amount) from entries",Long.class);
                }
                public static void main(String[] args) throws Exception {
                    try(var context=new AnnotationConfigApplicationContext()) {
                        context.register(App.class); context.registerBean("constructed",Service.class); context.refresh();
                        JdbcTemplate jdbc=context.getBean(JdbcTemplate.class); jdbc.execute("create table entries(amount bigint)");
                        Api originalC=context.getBean("constructed",Api.class),originalP=context.getBean("produced",Api.class);
                        Holder holder=new Holder(originalC,originalP); context.getBeanFactory().registerSingleton("holder",holder);
                        System.out.println("READY="+result(holder,jdbc));
                        for(int i=1;i<=3;i++) {
                            Path probe=Path.of(System.getProperty("probe.dir"),"probe"+i);
                            while(!Files.exists(probe))Thread.sleep(20);
                            boolean healed=holder.constructed==context.getBean("constructed")&&holder.produced==context.getBean("produced")
                                &&holder.constructed!=originalC&&holder.produced!=originalP;
                            System.out.println("PROBE"+i+"="+result(holder,jdbc)+":"+Service.built+":"+Service.destroyed+":"+(healed?"healed":"stale"));
                        }
                    }
                }
            }
            """;
}
