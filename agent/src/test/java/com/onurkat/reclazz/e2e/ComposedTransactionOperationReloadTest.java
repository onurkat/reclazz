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

class ComposedTransactionOperationReloadTest {
    @TempDir Path tmp;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void composedTransactionsFollowSavesThroughHeldProxy(boolean child) throws Exception {
        String h2=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p->Path.of(p).getFileName().toString().startsWith("h2-"))
                .collect(Collectors.joining(File.pathSeparator));
        assertFalse(h2.isEmpty());
        var builder=WatchedApp.in(tmp).classpath(WatchedApp.springClasspath()+File.pathSeparator+h2)
                .jvmArgs("-Dprobe.dir="+tmp).with("App",APP).with("Work",WORK).with("ReadWork",READ_WORK)
                .with("Store",store(0)).with("Probe",probe(false));
        if(child)builder.childClassLoader();
        try(var app=builder.start()) {
            app.awaitOrFail("READY=true","native transaction proxy missing");
            app.awaitOrFail("] Watching ","watcher missing");
            if(child)app.awaitOrFail("APP_IN_CHILD_MODULE=true","child loader missing");
            String[] expected={"1:true:false:1:2:true:false","2:true:true:2:4:true:false",
                    "3:false:false:4:6:true:false","removed:4:6:true:false","5:true:false:5:8:true:false"};
            for(int stage=1;stage<=5;stage++) {
                if(stage==1)app.rewriteAll(Map.of("Store",store(stage),"Probe",probe(true)));
                else app.rewrite("Store",store(stage));
                await(app,"Store",stage); if(stage==1)await(app,"Probe",1);
                Files.createFile(tmp.resolve("probe"+stage));
                app.awaitOrFail("R"+stage+"=","composed transaction probe failed");
                assertEquals("R"+stage+"="+expected[stage-1],app.latest("R"+stage+"="),app.tail());
                System.out.println("[composed-tx] child="+child+" "+app.latest("R"+stage+"="));
            }
        }
    }
    private static void await(WatchedApp app,String name,int count) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(reloads(app,name)<count&&System.nanoTime()<until)Thread.sleep(25);
        assertEquals(count,reloads(app,name),app.tail());
    }
    private static long reloads(WatchedApp app,String name) {
        return app.output().stream().filter(s->s.contains("Reloaded app."+name+" ")||s.contains("Structural reload: app."+name+" ")).count();
    }
    private static String probe(boolean added) {
        return "package app; public class Probe { public static String call(Store store,int id,boolean fail) throws Exception { return "
                +(added?"store.write(id,fail)":"\"ready\"")+"; } }";
    }
    private static String store(int version) {
        String method=(version==0||version==4)?"":"""
                %s
                public String write(int id,boolean fail) throws Exception {
                    calls++; jdbc.update("insert into entries values (?)",id);
                    if(fail)throw new Exception("checked rollback");
                    return "%d:"+org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                        +":"+org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly();
                }
                """.formatted(version==2?"@ReadWork":version==3?"":"@Work",version);
        return """
                package app;
                public class Store {
                    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
                    private int calls;
                    public Store(org.springframework.jdbc.core.JdbcTemplate jdbc) { this.jdbc=jdbc; }
                    @org.springframework.transaction.annotation.Transactional public int original() { return 1; }
                    public int calls() { return calls; }
                    %s
                }
                """.formatted(method);
    }
    private static final String WORK="""
            package app;
            import java.lang.annotation.*;
            import org.springframework.transaction.annotation.Transactional;
            import org.springframework.core.annotation.AliasFor;
            @Target({ElementType.TYPE,ElementType.METHOD,ElementType.ANNOTATION_TYPE}) @Retention(RetentionPolicy.RUNTIME)
            @Transactional(rollbackFor=Exception.class)
            public @interface Work {
                @AliasFor(annotation=Transactional.class,attribute="readOnly") boolean readOnly() default false;
                @AliasFor(annotation=Transactional.class,attribute="transactionManager") String manager() default "transactionManager";
            }
            """;
    private static final String READ_WORK="""
            package app;
            import java.lang.annotation.*;
            @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @Work(readOnly=true)
            public @interface ReadWork { }
            """;
    private static final String APP="""
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            import org.springframework.jdbc.core.JdbcTemplate;
            import org.springframework.transaction.annotation.EnableTransactionManagement;
            @Configuration(proxyBeanMethods=false) @EnableTransactionManagement(proxyTargetClass=true)
            public class App {
                @Bean public org.h2.jdbcx.JdbcDataSource dataSource() {
                    var ds=new org.h2.jdbcx.JdbcDataSource(); ds.setURL("jdbc:h2:mem:composed;DB_CLOSE_DELAY=-1"); return ds;
                }
                @Bean public JdbcTemplate jdbc(org.h2.jdbcx.JdbcDataSource ds) { return new JdbcTemplate(ds); }
                @Bean public org.springframework.transaction.PlatformTransactionManager transactionManager(org.h2.jdbcx.JdbcDataSource ds) {
                    return new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);
                }
                @Bean public Store store(JdbcTemplate jdbc) { return new Store(jdbc); }
                public static void main(String[] args) throws Exception {
                    try(var context=new AnnotationConfigApplicationContext(App.class)) {
                        JdbcTemplate jdbc=context.getBean(JdbcTemplate.class); jdbc.execute("create table entries(id int)");
                        Store held=context.getBean(Store.class);
                        System.out.println("READY="+(held instanceof org.springframework.aop.framework.Advised));
                        for(int i=1;i<=5;i++) {
                            while(!Files.exists(Path.of(System.getProperty("probe.dir"),"probe"+i)))Thread.sleep(20);
                            String result;
                            if(i==4) {
                                try { Probe.call(held,i,false); throw new AssertionError("removed body ran"); }
                                catch(IllegalStateException expected) {
                                    if(!expected.getMessage().contains("operation method was removed"))throw expected;
                                    result="removed";
                                }
                            } else {
                                result=Probe.call(held,i,false);
                                try { Probe.call(held,i,true); throw new AssertionError("missing exception"); }
                                catch(Exception expected) { if(!"checked rollback".equals(expected.getMessage()))throw expected; }
                            }
                            boolean reflected=java.util.Arrays.stream(Store.class.getDeclaredMethods()).anyMatch(m->m.getName().equals("write"));
                            System.out.println("R"+i+"="+result+":"+jdbc.queryForObject("select count(*) from entries",Integer.class)
                                +":"+held.calls()+":"+(held==context.getBean(Store.class))+":"+reflected);
                        }
                    }
                }
            }
            """;
}
