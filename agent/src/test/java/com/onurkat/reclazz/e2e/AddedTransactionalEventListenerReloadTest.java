/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AddedTransactionalEventListenerReloadTest {
    @TempDir Path tmp;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void transactionPhasesAndQueuedEventsSurviveEditsRemovalAndRestoration(boolean child) throws Exception {
        String h2=Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .filter(s->Path.of(s).getFileName().toString().startsWith("h2-")).findFirst().orElseThrow();
        var builder=WatchedApp.in(tmp).classpath(WatchedApp.springClasspath()+java.io.File.pathSeparator+h2);
        if(child) builder.childClassLoader();
        try(var app=builder.jvmArgs("-Dtest.dir="+tmp).with("App",APP).with("Tape",TAPE).with("Listeners",listeners(0)).start()) {
            app.awaitOrFail("READY", "context not ready"); app.awaitOrFail("] Watching ","watcher not ready");
            reload(app,1); command("matrix1");
            expect(app,"MATRIX1=",matrix(1,1,3));
            command("hold2"); app.awaitOrFail("QUEUED2", "transaction did not queue callbacks");
            reload(app,2); command("release2");
            expect(app,"HELD2=","[before2:ok-new, after2:ok-new, complete2:ok-new]:2:5");
            command("hold3"); app.awaitOrFail("QUEUED3", "transaction did not queue callbacks");
            reload(app,3); command("release3"); expect(app,"HELD3=","[]:3:7");
            reload(app,4); command("matrix4"); expect(app,"MATRIX4=",matrix(4,4,10));
        }
    }
    private static String matrix(int version,int rows,int normal) {
        return "[fallback"+version+":outside, before"+version+":ok-commit, after"+version+":ok-commit, complete"
                +version+":ok-commit, rollback"+version+":ok-rollback, complete"+version+":ok-rollback]:"+rows+":"+normal;
    }
    private void command(String name) throws Exception { Files.createFile(tmp.resolve(name)); }
    private void expect(WatchedApp app,String prefix,String expected) throws Exception {
        app.awaitOrFail(prefix,"probe missing"); String line=app.latest(prefix);
        assertEquals(prefix+expected,line.substring(line.indexOf(prefix)),app.tail()); System.out.println("[tx-event] "+line);
    }
    private void reload(WatchedApp app,int version) throws Exception {
        app.rewrite("Listeners",listeners(version)); long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(System.nanoTime()<end && reloads(app)<version) Thread.sleep(25);
        assertEquals(version,reloads(app),app.tail());
    }
    private long reloads(WatchedApp app) { return app.output().stream().filter(s->s.contains("Reloaded app.Listeners")||s.contains("Structural reload: app.Listeners")).count(); }
    private static String listeners(int version) {
        StringBuilder body=new StringBuilder("package app; @org.springframework.stereotype.Service public class Listeners { public int version(){return "+version+";}")
                .append("@org.springframework.context.event.EventListener public void ordinary(String message) { Tape.normal++; }");
        if(version!=0 && version!=3) {
            int order=0;
            for(String phase:List.of("BEFORE_COMMIT","AFTER_COMMIT","AFTER_ROLLBACK","AFTER_COMPLETION")) {
                String label=switch(phase) { case "BEFORE_COMMIT"->"before"; case "AFTER_COMMIT"->"after"; case "AFTER_ROLLBACK"->"rollback"; default->"complete"; };
                body.append("@org.springframework.transaction.event.TransactionalEventListener(phase=org.springframework.transaction.event.TransactionPhase.")
                    .append(phase).append(", condition=\"#a0.startsWith('ok')\") @org.springframework.core.annotation.Order(").append(++order)
                    .append(") private void ").append(label).append("(String message) { Tape.add(\"").append(label).append(version).append(":\"+message); }");
            }
            body.append("@org.springframework.transaction.event.TransactionalEventListener(fallbackExecution=true,condition=\"#a0 == 'outside'\") ")
                .append("private void fallback(String message) { Tape.add(\"fallback").append(version).append(":\"+message); }");
        }
        return body.append('}').toString();
    }
    private static final String TAPE="""
        package app;
        public class Tape {
            public static final java.util.List<String> calls=new java.util.ArrayList<>(); public static int normal;
            public static void add(String value) { calls.add(value); }
        }
        """;
    private static final String APP="""
        package app;
        @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
        @org.springframework.transaction.annotation.EnableTransactionManagement
        public class App {
            public static void main(String[] args) throws Exception {
                var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext();
                context.register(App.class,Listeners.class); context.refresh();
                var data=new org.h2.jdbcx.JdbcDataSource(); data.setURL("jdbc:h2:mem:events;DB_CLOSE_DELAY=-1");
                var jdbc=new org.springframework.jdbc.core.JdbcTemplate(data); jdbc.execute("create table events (id int)");
                var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(data));
                System.out.println("READY"); matrix(context,tx,jdbc,1);
                hold(context,tx,jdbc,2); hold(context,tx,jdbc,3); matrix(context,tx,jdbc,4); context.close();
            }
            static void waitFor(String command) {
                var file=java.nio.file.Path.of(System.getProperty("test.dir"),command);
                try { while(!java.nio.file.Files.exists(file)) Thread.sleep(25); } catch(Exception e) { throw new RuntimeException(e); }
            }
            static void matrix(org.springframework.context.ConfigurableApplicationContext context,
                    org.springframework.transaction.support.TransactionTemplate tx,org.springframework.jdbc.core.JdbcTemplate jdbc,int stage) {
                waitFor("matrix"+stage); Tape.calls.clear(); context.publishEvent("outside");
                tx.execute(status->{ jdbc.update("insert into events values (1)"); int before=Tape.calls.size(); context.publishEvent("ok-commit");
                    if(Tape.calls.size()!=before) throw new AssertionError("transaction callback ran early"); return null; });
                tx.execute(status->{ jdbc.update("insert into events values (2)"); int before=Tape.calls.size(); context.publishEvent("ok-rollback");
                    if(Tape.calls.size()!=before) throw new AssertionError("transaction callback ran early"); status.setRollbackOnly(); return null; });
                System.out.println("MATRIX"+stage+"="+Tape.calls+":"+jdbc.queryForObject("select count(*) from events",Integer.class)+":"+Tape.normal);
            }
            static void hold(org.springframework.context.ConfigurableApplicationContext context,
                    org.springframework.transaction.support.TransactionTemplate tx,org.springframework.jdbc.core.JdbcTemplate jdbc,int stage) {
                waitFor("hold"+stage); Tape.calls.clear();
                tx.execute(status->{ jdbc.update("insert into events values (3)"); context.publishEvent("ok-old");
                    System.out.println("QUEUED"+stage); waitFor("release"+stage); context.publishEvent("ok-new"); return null; });
                System.out.println("HELD"+stage+"="+Tape.calls+":"+jdbc.queryForObject("select count(*) from events",Integer.class)+":"+Tape.normal);
            }
        }
        """;
}
