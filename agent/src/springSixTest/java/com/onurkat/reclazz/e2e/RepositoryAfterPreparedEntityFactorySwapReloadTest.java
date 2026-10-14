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
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryAfterPreparedEntityFactorySwapReloadTest {
    @TempDir Path tmp;
    @ParameterizedTest @CsvSource({"false,prepared","true,prepared","false,retry","true,retry"})
    void heldRepositoryReadsAndWritesAfterPreparedSwapAndValidationRetry(boolean child,String mode)throws Exception {run(child,mode);}
    @ParameterizedTest @CsvSource({"false,optout","true,optout","false,ambiguous","true,ambiguous"})
    void optOutAndTwoUnitsNeverSwapHeldRepositoryInfrastructure(boolean child,String mode)throws Exception {run(child,mode);}

    private void run(boolean child,String mode)throws Exception {
        String cp=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p->p.endsWith(".jar")&&!Path.of(p).getFileName().toString().startsWith("agent-"))
                .collect(Collectors.joining(File.pathSeparator));
        var b=WatchedApp.in(tmp).classpath(cp).agentArgs("startupDelaySec=1,debounceMs=100,verbose=true,jpaRefresh="+!mode.equals("optout"))
                .jvmArgs("-Dprobe.dir="+tmp,"-Dmode="+mode).with("App",APP).with("Existing",EXISTING).with("Records",RECORDS);
        if(child)b.childClassLoader();
        try(var app=b.start()) {
            app.awaitOrFail("READY="+data(false),"repository startup/warm query failed");app.awaitOrFail("] Watching ","watcher missing");
            assertEquals(data(false),nativeFresh(app.classesDir(),cp,mode,false,0));
            app.rewrite("Added",added(1));awaitReload(app,1);
            boolean first=mode.equals("prepared");
            assertEquals(data(first),nativeFresh(app.classesDir(),cp,mode,first,1));
            probe(app,1,first,mode);
            int saves=1;
            if(mode.equals("retry")) {
                assertTrue(app.output().stream().anyMatch(x->x.contains("validation failed")),app.tail());
                Files.createFile(tmp.resolve("prepare"));app.awaitOrFail("PREPARED","synthetic schema preparation failed");
                app.rewrite("Added",added(2));awaitReload(app,++saves);
                assertEquals(data(true),nativeFresh(app.classesDir(),cp,mode,true,2));probe(app,2,true,mode);
            }
            boolean mapped=mode.equals("prepared")||mode.equals("retry");
            long rebuilds=app.output().stream().filter(x->x.contains("rebuilt persistence unit")).count();
            assertEquals(mapped?1:0,rebuilds,app.tail());
            app.rewrite("Added",added(3));awaitReload(app,++saves);
            assertEquals(data(mapped),nativeFresh(app.classesDir(),cp,mode,mapped,3));probe(app,3,mapped,mode);
            assertEquals(rebuilds,app.output().stream().filter(x->x.contains("rebuilt persistence unit")).count(),"body-only saved mapped entity must not rebuild again");
            if(mode.equals("optout"))assertTrue(app.output().stream().anyMatch(x->x.contains("jpaRefresh=true")),app.tail());
            if(mode.equals("ambiguous"))assertTrue(app.output().stream().anyMatch(x->x.contains("not exactly one persistence unit")),app.tail());
        }
    }
    private void probe(WatchedApp app,int stage,boolean mapped,String mode)throws Exception {
        Files.createFile(tmp.resolve("probe"+stage));app.awaitOrFail("R"+stage+"=","repository probe missing");
        String expected="R"+stage+"="+data(mapped)+"|nativeSame="+!mapped+":oldOpen="+!mapped+":otherOpen=true";
        assertEquals(expected,app.latest("R"+stage+"="),app.tail());
        System.out.println("[repo-emf] mode="+mode+" "+app.latest("R"+stage+"="));
    }
    private String nativeFresh(Path classes,String cp,String mode,boolean mapped,int stage)throws Exception {
        Path log=tmp.resolve("native-"+stage+".log");
        Process p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",classes+File.pathSeparator+cp,"app.App","native",mode,""+mapped)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(p.waitFor(40,TimeUnit.SECONDS),"fresh native timeout");String text=Files.readString(log);assertEquals(0,p.exitValue(),text);
            String result=text.lines().filter(x->x.startsWith("NATIVE=")).findFirst().orElseThrow().substring(7);
            System.out.println("[native repo-emf] mode="+mode+" mapped="+mapped+" "+result);return result;
        }finally{if(p.isAlive())p.destroyForcibly();}
    }
    private static String data(boolean mapped){String label=mapped?"written":"kept";return "find="+label+":jpql="+label+":count=1:added="+(mapped?"new":"UNMAPPED")+":anchor=anchor";}
    private static void awaitReload(WatchedApp app,int count)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(System.nanoTime()<until&&app.output().stream().filter(x->x.contains("Reloaded app.Added ")||x.contains("Structural reload: app.Added ")).count()<count)Thread.sleep(25);
        assertEquals(count,app.output().stream().filter(x->x.contains("Reloaded app.Added ")||x.contains("Structural reload: app.Added ")).count(),app.tail());
    }
    private static final String EXISTING="""
        package app;
        @jakarta.persistence.Entity(name="ProofExisting") @jakarta.persistence.Table(name="existing_record")
        public class Existing { @jakarta.persistence.Id public long id; public String label; public Existing(){} }
        """;
    private static String added(int v){return """
        package app;
        @jakarta.persistence.Entity(name="ProofAdded") @jakarta.persistence.Table(name="added_record")
        public class Added { @jakarta.persistence.Id public long id; public String label; public Added(){} public int marker(){return %d;} }
        """.formatted(v);}
    private static final String RECORDS="""
        package app;
        public interface Records extends org.springframework.data.jpa.repository.JpaRepository<Existing,Long>, App.NewRows {
            @org.springframework.data.jpa.repository.Query("select r.label from ProofExisting r where r.id=1") String existingLabel();
        }
        """;
    private static final String APP="""
        package app;
        import java.nio.file.*;
        import java.sql.*;
        import java.util.*;
        import jakarta.persistence.*;
        import org.springframework.context.support.GenericApplicationContext;
        import org.springframework.jdbc.datasource.DriverManagerDataSource;
        import org.springframework.orm.jpa.*;
        import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
        import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
        import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
        public class App {
            static final String URL="jdbc:h2:mem:prepared_repo;DB_CLOSE_DELAY=-1";
            public interface NewRows {String addedLabel();}
            public static class Fragment implements NewRows {
                final EntityManager shared;Fragment(EntityManager em){shared=em;}
                public String addedLabel(){return shared.createQuery("select r.label from ProofAdded r where r.id=2",String.class).getSingleResult();}
            }
            public static class Holder {public final Records records;Holder(Records records){this.records=records;}}
            static void sql(String url,String text)throws Exception{try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){s.execute(text);}}
            static void prepare()throws Exception{sql(URL,"create table added_record(id bigint primary key,label varchar(255))");sql(URL,"insert into added_record values(2,'new')");}
            static LocalContainerEntityManagerFactoryBean factory(String url,String unit,boolean mapped)throws Exception {
                sql(url,"create table existing_record(id bigint primary key,label varchar(255))");sql(url,"insert into existing_record values(1,'kept')");
                var f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(new DriverManagerDataSource(url,"sa",""));
                f.setManagedTypes(mapped?PersistenceManagedTypes.of(Existing.class.getName(),"app.Added"):PersistenceManagedTypes.of(Existing.class.getName()));
                f.setPersistenceUnitName(unit);f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
                f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","validate","hibernate.archive.autodetection","none"));return f;
            }
            static void same(Object first,Object second,String name){if(first!=second)throw new AssertionError("replaced "+name);}
            static String readNew(Records records){
                try{return records.addedLabel();}catch(RuntimeException failure){
                    for(Throwable t=failure;t!=null;t=t.getCause())if(t.getClass().getName().equals("org.hibernate.query.sqm.UnknownEntityException"))return "UNMAPPED";
                    throw failure;
                }
            }
            static String observe(GenericApplicationContext c,GenericApplicationContext other,Records held,Holder holder,EntityManagerFactory client,EntityManager shared,LocalContainerEntityManagerFactoryBean f,Object anchor,EntityManagerFactory otherNative,boolean mapped) {
                same(held,c.getBean(Records.class),"repository");same(holder,c.getBean(Holder.class),"holder");same(held,holder.records,"injected repository");same(client,c.getBean(EntityManagerFactory.class),"injected EMF client");same(anchor,other.getBean("anchor"),"unrelated context anchor");
                if(!client.isOpen()||(otherNative!=null&&!otherNative.isOpen()))throw new AssertionError("live unrelated/client factory closed");
                if(mapped){var row=new Existing();row.id=1;row.label="written";held.saveAndFlush(row);}
                String found=held.findById(1L).orElseThrow().label;
                same(shared,((Fragment)c.getBean("fragment")).shared,"original shared entity manager");
                if(!found.equals(shared.find(Existing.class,1L).label))throw new AssertionError("shared/repository disagree");
                return "find="+found+":jpql="+held.existingLabel()+":count="+held.count()+":added="+readNew(held)+":anchor="+anchor;
            }
            public static void main(String[] args)throws Exception {
                boolean nativeMode=args.length>0;String mode=nativeMode?args[1]:System.getProperty("mode");boolean nativeMapped=nativeMode&&Boolean.parseBoolean(args[2]);
                if(!mode.equals("retry")||nativeMapped)prepare();
                try(var c=new GenericApplicationContext();var other=new GenericApplicationContext()) {
                    c.setClassLoader(App.class.getClassLoader());other.setClassLoader(App.class.getClassLoader());
                    var f=factory(URL,"prepared-repo",nativeMapped);c.registerBean("emf",LocalContainerEntityManagerFactoryBean.class,()->f);
                    c.registerBean("transactionManager",JpaTransactionManager.class,()->new JpaTransactionManager(c.getBean(EntityManagerFactory.class)));
                    c.registerBean("fragment",Fragment.class,()->new Fragment(SharedEntityManagerCreator.createSharedEntityManager(c.getBean(EntityManagerFactory.class))));
                    c.registerBean("records",JpaRepositoryFactoryBean.class,()->{
                        var b=new JpaRepositoryFactoryBean<Records,Existing,Long>(Records.class);b.setEntityManager(c.getBean(Fragment.class).shared);b.setCustomImplementation(c.getBean(Fragment.class));b.setEntityPathResolver(c.getBeanProvider(org.springframework.data.querydsl.EntityPathResolver.class));return b;
                    });
                    c.registerBean("holder",Holder.class,()->new Holder(c.getBean(Records.class)));c.refresh();
                    other.registerBean("anchor",String.class,()->"anchor");
                    LocalContainerEntityManagerFactoryBean second=null;
                    if(mode.equals("ambiguous")){second=factory("jdbc:h2:mem:other_repo;DB_CLOSE_DELAY=-1","other",false);var captured=second;other.registerBean("emf",LocalContainerEntityManagerFactoryBean.class,()->captured);}
                    other.refresh();EntityManagerFactory otherNative=second==null?null:second.getNativeEntityManagerFactory();
                    var held=c.getBean(Records.class);var holder=c.getBean(Holder.class);var client=c.getBean(EntityManagerFactory.class);var shared=c.getBean(Fragment.class).shared;var original=f.getNativeEntityManagerFactory();var anchor=other.getBean("anchor");
                    if(nativeMode){System.out.println("NATIVE="+observe(c,other,held,holder,client,shared,f,anchor,otherNative,nativeMapped));return;}
                    System.out.println("READY="+observe(c,other,held,holder,client,shared,f,anchor,otherNative,false));
                    Object installed=null;Path dir=Path.of(System.getProperty("probe.dir"));
                    for(int tick=0;tick<16000;tick++){
                        if(Files.deleteIfExists(dir.resolve("prepare"))){prepare();System.out.println("PREPARED");}
                        for(int stage=1;stage<=3;stage++)if(Files.deleteIfExists(dir.resolve("probe"+stage))){
                            boolean mapped=client.getMetamodel().getEntities().stream().anyMatch(e->e.getName().equals("ProofAdded"));
                            try {
                                if(mapped&&installed!=null&&installed!=f.getNativeEntityManagerFactory())throw new AssertionError("mapped body-only save rebuilt again");
                                if(mapped)installed=f.getNativeEntityManagerFactory();
                                String value=observe(c,other,held,holder,client,shared,f,anchor,otherNative,mapped);
                                System.out.println("R"+stage+"="+value+"|nativeSame="+(original==f.getNativeEntityManagerFactory())+":oldOpen="+original.isOpen()+":otherOpen="+(otherNative==null||otherNative.isOpen()));
                            }catch(Throwable failure){System.out.println("R"+stage+"=FAIL:"+failure.getClass().getName()+":"+failure.getMessage());throw failure;}
                        }
                        Thread.sleep(10);
                    }
                }
            }
        }
        """;
}
