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

class AddedAsyncScheduledReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest @ValueSource(booleans={false,true})
    void asyncSchedulesKeepAcceptedWorkAndRefreshFutureTicks(boolean child) throws Exception {
        String h2=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p->Path.of(p).getFileName().toString().startsWith("h2-"))
                .collect(Collectors.joining(File.pathSeparator));
        assertFalse(h2.isEmpty());
        var builder=WatchedApp.in(tmp).classpath(WatchedApp.springClasspath()+File.pathSeparator+h2);
        if(child)builder.childClassLoader();
        try(var app=builder.jvmArgs("-Dprobe.dir="+tmp).with("App",APP).with("Service",service(0)).start()) {
            app.awaitOrFail("READY","context did not start"); app.awaitOrFail("] Watching ","watcher did not start");
            if(child)app.awaitOrFail("APP_IN_CHILD_MODULE=true","child loader missing");
            String[] expected={"queued=1 calls=0", "captured=true rerouted=true original=true",
                    "refused=true empty=true", "rollback=true handler=true queued=1", "sync=true queued=1",
                    "removed=true captured=true original=true", "restored=true closed=true"};
            for(int stage=1;stage<=7;stage++) {
                app.rewrite("Service",service(stage));
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
                while(reloads(app)<stage && System.nanoTime()<until)Thread.sleep(25);
                assertEquals(stage,reloads(app),app.tail());
                Files.createFile(tmp.resolve("probe"+stage));
                app.awaitOrFail("R"+stage+"=","async probe failed");
                assertEquals("R"+stage+"="+expected[stage-1]+" reflected=false",app.latest("R"+stage+"="),app.tail());
                assertTrue(app.output().stream().noneMatch(line -> line.contains("tick() was added")), app.tail());
                System.out.println("[async-scheduled] child="+child+" "+app.latest("R"+stage+"="));
            }
        }
    }
    private long reloads(WatchedApp app) {
        return app.output().stream().filter(s->s.contains("Reloaded app.Service ")||s.contains("Structural reload: app.Service ")).count();
    }
    private static String service(int version) {
        String callback=version==0||version==6?"":"""
                @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000, initialDelay=3600000)
                %s
                @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
                public void tick() throws Exception {
                    boolean tx=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
                    App.calls.add("v%d:"+Thread.currentThread().getName()+":"+tx+":"+identity());
                    jdbc.update("insert into entries values (?)", "tick");
                    if(App.fail) throw new Exception("failure");
                }
                """.formatted(version==5 ? "" : "@org.springframework.scheduling.annotation.Async(\""
                        + (version==1?"one":version==3?"missing":"two") + "\")",version);
        return """
                package app;
                @org.springframework.stereotype.Service
                public class Service {
                    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
                    public Service(org.springframework.jdbc.core.JdbcTemplate jdbc) { this.jdbc=jdbc; }
                    public int identity() { return System.identityHashCode(this); }
                    public int version() { return %d; }
                    @org.springframework.scheduling.annotation.Async("one")
                    public void originalAsync() { App.original.add(Thread.currentThread().getName()); }
                    @org.springframework.transaction.annotation.Transactional
                    public boolean originalTx() { return org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(); }
                    %s
                }
                """.formatted(version,callback);
    }
    private static final String APP="""
            package app;
            import java.nio.file.*;
            import java.util.*;
            import java.util.concurrent.*;
            import org.springframework.context.annotation.*;
            import org.springframework.scheduling.annotation.*;
            import org.springframework.transaction.annotation.EnableTransactionManagement;
            import org.springframework.jdbc.core.JdbcTemplate;
            import org.springframework.jdbc.datasource.DataSourceTransactionManager;
            @Configuration(proxyBeanMethods=false) @ComponentScan("app")
            @EnableAsync(proxyTargetClass=true) @EnableScheduling @EnableTransactionManagement(proxyTargetClass=true)
            public class App implements AsyncConfigurer {
                static final List<String> calls=new CopyOnWriteArrayList<>(),errors=new CopyOnWriteArrayList<>(),original=new CopyOnWriteArrayList<>();
                static boolean fail;
                static final QueueExecutor ONE=new QueueExecutor("one"),TWO=new QueueExecutor("two");
                @Bean public java.util.concurrent.Executor one() { return ONE; }
                @Bean public java.util.concurrent.Executor two() { return TWO; }
                public java.util.concurrent.Executor getAsyncExecutor() { return ONE; }
                public org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
                    return (failure,method,args)->errors.add(method.getName()+":"+args.length+":"+failure.getMessage());
                }
                @Bean public org.h2.jdbcx.JdbcDataSource dataSource() {
                    var ds=new org.h2.jdbcx.JdbcDataSource();ds.setURL("jdbc:h2:mem:async_schedules;DB_CLOSE_DELAY=-1");return ds;
                }
                @Bean public JdbcTemplate jdbc(org.h2.jdbcx.JdbcDataSource ds) { return new JdbcTemplate(ds); }
                @Bean public org.springframework.transaction.PlatformTransactionManager transactionManager(org.h2.jdbcx.JdbcDataSource ds) {
                    return new DataSourceTransactionManager(ds);
                }
                static class QueueExecutor implements java.util.concurrent.Executor {
                    final BlockingQueue<Runnable> queue=new LinkedBlockingQueue<>();final String name;
                    QueueExecutor(String name) { this.name=name; }
                    public void execute(Runnable action) { queue.add(action); }
                    void runNext() throws Exception {
                        var task=queue.poll(3,TimeUnit.SECONDS);if(task==null)throw new AssertionError("missing async submission");
                        var finished=new FutureTask<Void>(task,null);new Thread(finished,name).start();finished.get(5,TimeUnit.SECONDS);
                    }
                }
                static void fire(ScheduledAnnotationBeanPostProcessor scheduler) throws Exception {
                    Runnable tick=scheduler.getScheduledTasks().iterator().next().getTask().getRunnable();
                    var completion=new FutureTask<Void>(tick,null);
                    new Thread(completion,"scheduler-probe").start();completion.get(5,TimeUnit.SECONDS);
                }
                public static void main(String[] args) throws Exception {
                    try(var context=new AnnotationConfigApplicationContext(App.class)) {
                        var jdbc=context.getBean(JdbcTemplate.class);jdbc.execute("create table entries(id varchar(30))");
                        Service old=context.getBean(Service.class);
                        if(!(old instanceof org.springframework.aop.framework.Advised))throw new AssertionError("native proxy absent");
                        int admitted=0,last=0;System.out.println("READY");
                        for(int stage=1;stage<=7;stage++) {
                            while(!Files.exists(Path.of(System.getProperty("probe.dir"),"probe"+stage)))Thread.sleep(10);
                            Service current=context.getBean(Service.class);String result;
                            var scheduler=context.getBean(ScheduledAnnotationBeanPostProcessor.class);
                            if(stage!=6 && scheduler.getScheduledTasks().size()!=1)throw new AssertionError("missing or duplicate schedule");
                            if(stage==1) {
                                admitted=current.identity();fire(scheduler);
                                result="queued="+ONE.queue.size()+" calls="+calls.size();
                            } else if(stage==2) {
                                ONE.runNext();boolean captured=calls.equals(List.of("v1:one:true:"+admitted));
                                fire(scheduler);TWO.runNext();
                                boolean rerouted=calls.size()==2&&calls.get(1).equals("v2:two:true:"+current.identity());
                                old.originalAsync();ONE.runNext();
                                result="captured="+captured+" rerouted="+rerouted+" original="+(original.equals(List.of("one"))&&old.originalTx());
                            } else if(stage==3) {
                                boolean refused=false;try{fire(scheduler);}catch(Exception failure){refused=true;}
                                result="refused="+refused+" empty="+(ONE.queue.isEmpty()&&TWO.queue.isEmpty()&&calls.size()==2);
                            } else if(stage==4) {
                                int before=jdbc.queryForObject("select count(*) from entries",Integer.class);
                                fail=true;fire(scheduler);TWO.runNext();fail=false;
                                boolean rollback=before==jdbc.queryForObject("select count(*) from entries",Integer.class);
                                last=current.identity();fire(scheduler);
                                result="rollback="+rollback+" handler="+errors.equals(List.of("tick:0:failure"))+" queued="+TWO.queue.size();
                            } else if(stage==5) {
                                fire(scheduler);
                                result="sync="+(calls.size()==4&&calls.get(3).equals("v5:scheduler-probe:true:"+current.identity()))+" queued="+TWO.queue.size();
                            } else if(stage==6) {
                                boolean removed=scheduler.getScheduledTasks().isEmpty()&&TWO.queue.size()==1;
                                TWO.runNext();
                                boolean captured=calls.size()==5&&calls.get(4).equals("v4:two:true:"+last);
                                old.originalAsync();ONE.runNext();
                                result="removed="+removed+" captured="+captured+" original="+(original.equals(List.of("one","one"))&&old.originalTx());
                            } else {
                                fire(scheduler);TWO.runNext();
                                boolean restored=calls.size()==6&&calls.get(5).equals("v7:two:true:"+current.identity());
                                context.close();
                                result="restored="+restored+" closed="+scheduler.getScheduledTasks().isEmpty();
                            }
                            boolean reflected=Arrays.stream(Service.class.getDeclaredMethods()).anyMatch(m->m.getName().equals("tick"));
                            System.out.println("R"+stage+"="+result+" reflected="+reflected);
                        }
                    }
                }
            }
            """;
}
