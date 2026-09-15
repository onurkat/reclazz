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

class AddedAsyncServiceReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest @ValueSource(booleans={false,true})
    void heldCallsGetNativeFuturesAcrossExecutorChangesRemovalAndRestore(boolean child) throws Exception {
        String h2=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p->Path.of(p).getFileName().toString().startsWith("h2-"))
                .collect(Collectors.joining(File.pathSeparator));
        assertFalse(h2.isEmpty());
        var builder=WatchedApp.in(tmp).classpath(WatchedApp.springClasspath()+File.pathSeparator+h2);
        if(child)builder.childClassLoader();
        try(var app=builder.jvmArgs("-Dprobe.dir="+tmp).with("App",APP).with("Service",service(0)).with("Probe",probe(false)).start()) {
            app.awaitOrFail("READY","context did not start");app.awaitOrFail("] Watching ","watcher did not start");
            if(child)app.awaitOrFail("APP_IN_CHILD_MODULE=true","child loader missing");
            String[] expected={"pending=true calls=0","captured=true old=true current=true failed=true cancelled=true",
                    "sync=true","removed=true","restored=true self=true original=true"};
            for(int stage=1;stage<=5;stage++) {
                if(stage==1)app.rewriteAll(Map.of("Service",service(stage),"Probe",probe(true)));
                else app.rewrite("Service",service(stage));
                await(app,"Service",stage);if(stage==1)await(app,"Probe",1);
                Files.createFile(tmp.resolve("probe"+stage));app.awaitOrFail("R"+stage+"=","async service probe failed");
                assertEquals("R"+stage+"="+expected[stage-1]+" reflected=false",app.latest("R"+stage+"="),app.tail());
                assertTrue(app.output().stream().noneMatch(line->line.contains("compute() was added")),app.tail());
                System.out.println("[async-service] child="+child+" "+app.latest("R"+stage+"="));
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
        return "package app; public class Probe { public static java.util.concurrent.CompletableFuture<String> call(Service service,int value) throws Exception { return "
                +(added?"service.compute(value)":"java.util.concurrent.CompletableFuture.completedFuture(\"ready\")")+"; } }";
    }
    private static String service(int version) {
        boolean present=version!=0&&version!=4;
        String added=!present?"":"""
                %s
                public java.util.concurrent.CompletableFuture<String> compute(int value) throws Exception {
                    boolean tx=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
                    App.calls++;
                    jdbc.update("insert into entries values (?)",value);
                    if(value<0)throw new Exception("failure");
                    return java.util.concurrent.CompletableFuture.completedFuture("v%d:"+value+":"+Thread.currentThread().getName()+":"+tx+":"+identity());
                }
                """.formatted(version==3?"":"@org.springframework.scheduling.annotation.Async(\""+(version==1?"one":"two")+"\")\n"
                        +"@org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)",version);
        return """
                package app;
                @org.springframework.stereotype.Service
                public class Service {
                    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
                    public Service(org.springframework.jdbc.core.JdbcTemplate jdbc){this.jdbc=jdbc;}
                    public int version(){return %d;}
                    public int identity(){return System.identityHashCode(this);}
                    public java.util.concurrent.CompletableFuture<String> self() throws Exception{return %s;}
                    @org.springframework.scheduling.annotation.Async("one") public void original(){App.original.add(Thread.currentThread().getName());}
                    %s
                }
                """.formatted(version,present?"compute(9)":"java.util.concurrent.CompletableFuture.completedFuture(\"empty\")",added);
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
            @EnableAsync(proxyTargetClass=true) @EnableTransactionManagement(proxyTargetClass=true)
            public class App implements AsyncConfigurer {
                public static volatile int calls;
                static final List<String> errors=new CopyOnWriteArrayList<>(),original=new CopyOnWriteArrayList<>();
                static final QueueExecutor ONE=new QueueExecutor("one"),TWO=new QueueExecutor("two");
                @Bean public Executor one(){return ONE;}
                @Bean public Executor two(){return TWO;}
                public Executor getAsyncExecutor(){return ONE;}
                public org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler(){return(f,m,a)->errors.add(f.toString());}
                @Bean public org.h2.jdbcx.JdbcDataSource dataSource(){var ds=new org.h2.jdbcx.JdbcDataSource();ds.setURL("jdbc:h2:mem:async_service;DB_CLOSE_DELAY=-1");return ds;}
                @Bean public JdbcTemplate jdbc(org.h2.jdbcx.JdbcDataSource ds){return new JdbcTemplate(ds);}
                @Bean public org.springframework.transaction.PlatformTransactionManager transactionManager(org.h2.jdbcx.JdbcDataSource ds){return new DataSourceTransactionManager(ds);}
                static class QueueExecutor implements Executor {
                    final BlockingQueue<Runnable> queue=new LinkedBlockingQueue<>();final String name;
                    QueueExecutor(String name){this.name=name;}
                    public void execute(Runnable action){queue.add(action);}
                    void runNext() throws Exception {
                        Runnable action=queue.poll(3,TimeUnit.SECONDS);if(action==null)throw new AssertionError("no async submission");
                        var done=new FutureTask<Void>(action,null);new Thread(done,name).start();done.get(5,TimeUnit.SECONDS);
                    }
                }
                public static void main(String[] args) throws Exception {
                    try(var context=new AnnotationConfigApplicationContext(App.class)) {
                        var jdbc=context.getBean(JdbcTemplate.class);jdbc.execute("create table entries(id int)");
                        Service old=context.getBean(Service.class);int oldId=old.identity();
                        if(!(old instanceof org.springframework.aop.framework.Advised))throw new AssertionError("proxy missing");
                        CompletableFuture<String> admitted=null;System.out.println("READY");
                        for(int stage=1;stage<=5;stage++) {
                            while(!Files.exists(Path.of(System.getProperty("probe.dir"),"probe"+stage)))Thread.sleep(10);
                            Service current=context.getBean(Service.class);String result;
                            if(stage==1){
                                admitted=Probe.call(old,1);result="pending="+(!admitted.isDone()&&ONE.queue.size()==1)+" calls="+calls;
                            }else if(stage==2){
                                ONE.runNext();boolean captured=admitted.get(2,TimeUnit.SECONDS).equals("v1:1:one:true:"+oldId);
                                var a=Probe.call(old,2);TWO.runNext();boolean oldOk=a.get().equals("v2:2:two:true:"+oldId);
                                var b=Probe.call(current,3);TWO.runNext();boolean currentOk=b.get().equals("v2:3:two:true:"+current.identity());
                                int before=rows(jdbc);var bad=Probe.call(old,-1);TWO.runNext();boolean failed=false;
                                try{bad.get(2,TimeUnit.SECONDS);}catch(ExecutionException ex){failed="failure".equals(ex.getCause().getMessage())&&rows(jdbc)==before&&errors.isEmpty();}
                                int count=calls;var cancelled=Probe.call(old,4);cancelled.cancel(true);TWO.runNext();
                                result="captured="+captured+" old="+oldOk+" current="+currentOk+" failed="+failed+" cancelled="+(cancelled.isCancelled()&&calls==count);
                            }else if(stage==3){
                                var sync=Probe.call(old,3);result="sync="+(sync.isDone()&&sync.get().equals("v3:3:"+Thread.currentThread().getName()+":false:"+oldId)&&TWO.queue.isEmpty());
                            }else if(stage==4){
                                boolean removed=false;try{Probe.call(old,4);}catch(IllegalStateException ex){removed=ex.getMessage().contains("removed");}
                                result="removed="+removed;
                            }else{
                                var restored=Probe.call(old,5);TWO.runNext();boolean restoredOk=restored.get().equals("v5:5:two:true:"+oldId);
                                var self=current.self();boolean selfOk=self.isDone()&&self.get().equals("v5:9:"+Thread.currentThread().getName()+":false:"+current.identity())&&TWO.queue.isEmpty();
                                old.original();ONE.runNext();result="restored="+restoredOk+" self="+selfOk+" original="+original.equals(List.of("one"));
                            }
                            boolean reflected=Arrays.stream(Service.class.getDeclaredMethods()).anyMatch(m->m.getName().equals("compute"));
                            System.out.println("R"+stage+"="+result+" reflected="+reflected);
                        }
                    }
                }
                private static int rows(JdbcTemplate jdbc){return jdbc.queryForObject("select count(*) from entries",Integer.class);}
            }
            """;
}
