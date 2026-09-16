/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class NewEntityPreparedSchemaReloadTest {
    @TempDir Path tmp;
    @ParameterizedTest @CsvSource({"false,false,false","true,false,false","false,true,false","true,true,false","false,true,true","true,true,true"})
    void newEntityUsesPreparedSchemaAndFailedValidationCanRetry(boolean child, boolean retry, boolean auto) throws Exception {
        run(child,retry,auto,null);
    }
    @ParameterizedTest @CsvSource({"field,false","annotation,false","annotation,true"})
    void changedPendingMappingMustBeRestoredBeforeRetry(String changed,boolean auto) throws Exception {
        run(false,true,auto,changed);
    }
    private void run(boolean child,boolean retry,boolean auto,String changed) throws Exception {
        var prefixes=List.of("spring-","hibernate-core-","hibernate-commons-annotations-","jakarta.persistence-api-",
                "jakarta.transaction-api-","jboss-logging-","jandex-","classmate-","byte-buddy-","jakarta.xml.bind-api-",
                "jaxb-runtime-","jaxb-core-","txw2-","istack-commons-runtime-","jakarta.activation-api-","angus-activation-",
                "antlr4-runtime-","h2-");
        String cp=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p->prefixes.stream().anyMatch(Path.of(p).getFileName().toString()::startsWith))
                .collect(Collectors.joining(File.pathSeparator));
        var builder=WatchedApp.in(tmp).classpath(cp).agentArgs("startupDelaySec=1,debounceMs=100,jpaRefresh=true,autoCompile="+auto)
                .jvmArgs("-Dprobe.dir="+tmp,"-Dprepare="+!retry).with("App",APP).with("Existing",EXISTING);
        if(child)builder.childClassLoader();
        if(auto)builder.mavenLayout();
        try(var app=builder.start()) {
            app.awaitOrFail("READY", "JPA application failed to start"); app.awaitOrFail("] Watching ","watcher missing");
            save(app,auto,added(1)); awaitReload(app,1);
            int saves=1;
            probe(app,0,retry?"false:true:kept":"true:false:kept");
            if(retry) {
                assertTrue(app.output().stream().anyMatch(s->s.contains("validation failed")),app.tail());
                Files.createFile(tmp.resolve("prepare")); app.awaitOrFail("PREPARED", "schema preparation failed");
                if(changed!=null) {
                    String altered=changed.equals("field")?added(2).replace("public String label;","public String label; public String extra;")
                            :added(2).replace("public String label;","@jakarta.persistence.Column(name=\"other_label\") public String label;");
                    save(app,auto,altered); awaitReload(app,++saves);
                    app.awaitOrFail("pending entity metadata changed", "unsafe mapping retry was not refused");
                    probe(app,1,"false:true:kept");
                }
                save(app,auto,added(3)); awaitReload(app,++saves);
                probe(app,2,"true:false:kept");
            }
            Files.createFile(tmp.resolve("write"));
            app.awaitOrFail("ROUNDTRIP=", "old injected JPA client could not use new entity");
            assertEquals("ROUNDTRIP=new:kept",app.latest("ROUNDTRIP="),app.tail());
            long rebuilds=app.output().stream().filter(s->s.contains("rebuilt persistence unit")).count();
            save(app,auto,added(4)); awaitReload(app,++saves);
            assertEquals(rebuilds,app.output().stream().filter(s->s.contains("rebuilt persistence unit")).count(),app.tail());
            System.out.println("[prepared-jpa] child="+child+" retry="+retry+" auto="+auto+" changed="+changed+" "+app.latest("ROUNDTRIP="));
        }
    }
    private void save(WatchedApp app,boolean auto,String source) throws Exception {
        if(auto)Files.writeString(tmp.resolve("src/main/java/app/Added.java"),source);
        else app.rewrite("Added",source);
    }
    private void probe(WatchedApp app,int index,String expected) throws Exception {
        Files.createFile(tmp.resolve("probe"+index)); app.awaitOrFail("P"+index+"=","probe failed");
        assertEquals("P"+index+"="+expected,app.latest("P"+index+"="),app.tail());
    }
    private static void awaitReload(WatchedApp app,int count) throws Exception {
        long until=System.nanoTime()+20_000_000_000L;
        while(System.nanoTime()<until&&app.output().stream().filter(s->s.contains("Reloaded app.Added")||s.contains("Structural reload: app.Added")).count()<count)Thread.sleep(25);
        assertEquals(count,app.output().stream().filter(s->s.contains("Reloaded app.Added")||s.contains("Structural reload: app.Added")).count(),app.tail());
    }
    private static final String EXISTING="""
            package app;
            @jakarta.persistence.Entity @jakarta.persistence.Table(name="existing_record")
            public class Existing { @jakarta.persistence.Id public long id; public String label; public Existing(){} }
            """;
    private static String added(int version) { return """
            package app;
            @jakarta.persistence.Entity @jakarta.persistence.Table(name="added_record")
            public class Added { @jakarta.persistence.Id public long id; public String label; public Added(){} public int marker(){return %d;} }
            """.formatted(version); }
    private static final String APP="""
            package app;
            import java.nio.file.*;
            import java.sql.*;
            import java.util.*;
            import jakarta.persistence.*;
            import org.springframework.context.support.GenericApplicationContext;
            import org.springframework.jdbc.datasource.DriverManagerDataSource;
            import org.springframework.orm.jpa.*;
            import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
            import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
            public class App {
                static final String URL="jdbc:h2:mem:prepared;DB_CLOSE_DELAY=-1";
                static void sql(String text)throws Exception{try(var c=DriverManager.getConnection(URL,"sa","");var s=c.createStatement()){s.execute(text);}}
                static void prepare()throws Exception{sql("create table added_record(id bigint primary key,label varchar(255))");}
                public static void main(String[] args)throws Exception{
                    Path dir=Path.of(System.getProperty("probe.dir"));
                    sql("create table existing_record(id bigint primary key,label varchar(255))");sql("insert into existing_record values(1,'kept')");
                    if(Boolean.getBoolean("prepare"))prepare();
                    try(var context=new GenericApplicationContext()){
                        context.setClassLoader(App.class.getClassLoader());
                        var factory=new LocalContainerEntityManagerFactoryBean();
                        factory.setDataSource(new DriverManagerDataSource(URL,"sa",""));
                        factory.setManagedTypes(PersistenceManagedTypes.of(Existing.class.getName()));
                        factory.setPersistenceUnitName("prepared");factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
                        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","validate","hibernate.archive.autodetection","none"));
                        context.registerBean("emf",LocalContainerEntityManagerFactoryBean.class,()->factory);context.refresh();
                        var client=context.getBean(EntityManagerFactory.class);
                        var shared=SharedEntityManagerCreator.createSharedEntityManager(client);
                        var original=factory.getNativeEntityManagerFactory();
                        System.out.println("READY");
                        for(int tick=0;tick<12000;tick++){
                            if(Files.deleteIfExists(dir.resolve("prepare"))){prepare();System.out.println("PREPARED");}
                            for(int i=0;i<3;i++)if(Files.deleteIfExists(dir.resolve("probe"+i))){
                                boolean mapped=client.getMetamodel().getEntities().stream().anyMatch(e->e.getJavaType().getName().equals("app.Added"));
                                System.out.println("P"+i+"="+mapped+":"+(original==factory.getNativeEntityManagerFactory())+":"+shared.find(Existing.class,1L).label);
                            }
                            if(Files.deleteIfExists(dir.resolve("write"))){
                                Class<?> type=Class.forName("app.Added");Object added=type.getConstructor().newInstance();
                                type.getField("id").set(added,2L);type.getField("label").set(added,"new");
                                try(var em=client.createEntityManager()){em.getTransaction().begin();em.persist(added);em.getTransaction().commit();}
                                System.out.println("ROUNDTRIP="+type.getField("label").get(shared.find(type,2L))+":"+shared.find(Existing.class,1L).label);
                            }
                            Thread.sleep(10);
                        }
                    }
                }
            }
            """;
}
