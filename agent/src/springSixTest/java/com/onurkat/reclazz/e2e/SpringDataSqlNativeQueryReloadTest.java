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

class SpringDataSqlNativeQueryReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void currentTrackedDependentAndRetainedManualHolderMatchNative(boolean child, boolean annotated) throws Exception {
        String cp=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p->p.endsWith(".jar")&&!Path.of(p).getFileName().toString().startsWith("agent-"))
                .collect(Collectors.joining(File.pathSeparator));
        var b=WatchedApp.in(tmp).classpath(cp).agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .jvmArgs("-Dprobe.dir="+tmp).with("App",APP).with("Row",ROW).with("Records",records(0,annotated));
        if(child)b.childClassLoader();
        try(var app=b.start()) {
            app.awaitOrFail("READY="+expected(0),"native query startup failed");
            app.awaitOrFail("] Watching ","watcher missing");
            assertEquals(expected(0),nativeFresh(app.classesDir(),cp,0));
            for(int stage=1;stage<=6;stage++) {
                app.rewrite("Records",records(stage,annotated));
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                while(reloads(app)<stage&&System.nanoTime()<until)Thread.sleep(25);
                assertEquals(stage,reloads(app),app.tail());
                assertEquals(expected(stage),nativeFresh(app.classesDir(),cp,stage),"fresh agentless SQL baseline");
                Files.createFile(tmp.resolve("probe"+stage));
                app.awaitOrFail("R"+stage+"=","SQL observation missing");
                assertEquals("R"+stage+"="+expected(stage),app.latest("R"+stage+"="),app.tail());
                System.out.println("[sql-native] child="+child+" annotated="+annotated+" "+app.latest("R"+stage+"="));
            }
        }
    }
    private String nativeFresh(Path classes,String cp,int stage)throws Exception {
        Path log=tmp.resolve("native-"+stage+".log");
        Process p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
                classes+File.pathSeparator+cp,"app.App","native",""+stage)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(p.waitFor(40,TimeUnit.SECONDS),"native SQL timeout");String data=Files.readString(log);
            assertEquals(0,p.exitValue(),data);
            String value=data.lines().filter(x->x.startsWith("NATIVE=")).findFirst().orElseThrow().substring(7);
            System.out.println("[native SQL] stage="+stage+" "+value);return value;
        } finally {if(p.isAlive())p.destroyForcibly();}
    }
    private static long reloads(WatchedApp app){return app.output().stream().filter(x->x.contains("Reloaded app.Records ")||x.contains("Structural reload: app.Records ")).count();}
    private static String expected(int v) {
        String value=switch(v){case 1->"after";case 2->"AFTER";case 3->"SQL_ERROR:42122";case 5->"after:fixed";default->"before";};
        return v+":active="+value+":manual="+value+":stable=anchor:other=anchor:rows=3";
    }
    private static String records(int v,boolean annotated) {
        String sql=switch(v){
            case 1->"select label from proof_row where id = 2";
            case 2->"select upper(label) from proof_row where id = 2";
            case 3->"select absent_column from proof_row where id = 2";
            case 5->"select concat(label, ':fixed') from proof_row where id = 2";
            default->"select label from proof_row where id = 1";
        };
        return "package app; "+(annotated?"@org.springframework.stereotype.Repository ":"")+"public interface Records extends org.springframework.data.jpa.repository.JpaRepository<Row,Long> { @org.springframework.data.jpa.repository.Query(value=\""+sql+"\",nativeQuery=true) String label(); }";
    }
    private static final String ROW="""
        package app;
        @jakarta.persistence.Entity(name="ProofRow") @jakarta.persistence.Table(name="proof_row")
        public class Row { @jakarta.persistence.Id public long id; public String label; }
        """;
    private static final String APP="""
        package app;
        import java.nio.file.*;
        import org.springframework.context.support.GenericApplicationContext;
        import org.springframework.orm.jpa.*;
        import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
        import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
        import org.springframework.jdbc.datasource.DriverManagerDataSource;
        import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
        import jakarta.persistence.EntityManagerFactory;
        public class App {
            static boolean nativeControl;
            public interface StableRecords extends org.springframework.data.jpa.repository.JpaRepository<Row,Long> {
                @org.springframework.data.jpa.repository.Query(value="select label from proof_row where id = 3",nativeQuery=true) String anchor();
            }
            public static class Holder { public final Records records; public Holder(Records r){records=r;} }
            public static class Manual { public Records records; }
            static GenericApplicationContext context(String db,boolean edited) {
                var c=new GenericApplicationContext();
                c.registerBean("entityManagerFactory",LocalContainerEntityManagerFactoryBean.class,()->{
                    var f=new LocalContainerEntityManagerFactoryBean();
                    f.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:"+db+";DB_CLOSE_DELAY=-1","sa",""));
                    f.setManagedTypes(PersistenceManagedTypes.of(Row.class.getName()));
                    f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
                    f.setJpaPropertyMap(java.util.Map.of("hibernate.hbm2ddl.auto","create-drop","hibernate.archive.autodetection","none"));
                    return f;
                });
                c.registerBean("transactionManager",JpaTransactionManager.class,()->new JpaTransactionManager(c.getBean(EntityManagerFactory.class)));
                if(edited){
                    c.registerBean("records",JpaRepositoryFactoryBean.class,()->factory(c,Records.class));
                    c.registerBean("holder",Holder.class,()->new Holder(c.getBean(Records.class)));
                    c.registerBean("manual",Manual.class);
                }
                c.registerBean(edited?"stable":"records",JpaRepositoryFactoryBean.class,()->factory(c,StableRecords.class));
                c.refresh();
                try(var em=c.getBean(EntityManagerFactory.class).createEntityManager()) {
                    em.getTransaction().begin();
                    for(int id=1;id<=3;id++){var r=new Row();r.id=id;r.label=id==1?"before":id==2?"after":"anchor";em.persist(r);}
                    em.getTransaction().commit();
                }
                return c;
            }
            static <T extends org.springframework.data.repository.Repository<Row,Long>> JpaRepositoryFactoryBean<T,Row,Long> factory(GenericApplicationContext c,Class<T> t) {
                var f=new JpaRepositoryFactoryBean<T,Row,Long>(t);
                f.setEntityManager(SharedEntityManagerCreator.createSharedEntityManager(c.getBean(EntityManagerFactory.class)));
                f.setEntityPathResolver(c.getBeanProvider(org.springframework.data.querydsl.EntityPathResolver.class));return f;
            }
            static String query(Records r) {
                try{return r.label();}catch(RuntimeException x){
                    for(Throwable t=x;t!=null;t=t.getCause())if(t instanceof java.sql.SQLException s)return "SQL_ERROR:"+s.getErrorCode();
                    throw x;
                }
            }
            static void same(Object a,Object b,String what){if(a!=b)throw new AssertionError("recreated "+what);}
            static String observe(GenericApplicationContext c,GenericApplicationContext other,Holder held,Manual manual,EntityManagerFactory emf,EntityManagerFactory otherEmf,StableRecords stable,StableRecords otherStable,int stage) {
                Holder current=c.getBean(Holder.class);
                if(!nativeControl&&stage>0&&current==held)throw new AssertionError("tracked holder not recreated");
                same(current.records,c.getBean(Records.class),"current injected product identity");same(manual,c.getBean(Manual.class),"manual holder");
                same(emf,c.getBean(EntityManagerFactory.class),"EMF");same(otherEmf,other.getBean(EntityManagerFactory.class),"other EMF");
                same(stable,c.getBean(StableRecords.class),"unrelated repository");same(otherStable,other.getBean(StableRecords.class),"other context repository");
                if(!emf.isOpen()||!otherEmf.isOpen())throw new AssertionError("EMF closed");
                return stage+":active="+query(current.records)+":manual="+query(manual.records)+":stable="+stable.anchor()+":other="+otherStable.anchor()+":rows="+stable.count();
            }
            public static void main(String[] args)throws Exception {
                nativeControl=args.length>0;
                try(var c=context("watched",true);var other=context("other",false)) {
                    var held=c.getBean(Holder.class);var manual=c.getBean(Manual.class);manual.records=held.records;
                    var emf=c.getBean(EntityManagerFactory.class);var otherEmf=other.getBean(EntityManagerFactory.class);
                    var stable=c.getBean(StableRecords.class);var otherStable=other.getBean(StableRecords.class);
                    if(args.length>0){System.out.println("NATIVE="+observe(c,other,held,manual,emf,otherEmf,stable,otherStable,Integer.parseInt(args[1])));return;}
                    System.out.println("READY="+observe(c,other,held,manual,emf,otherEmf,stable,otherStable,0));
                    for(int stage=1;stage<=6;stage++) {
                        while(!Files.exists(Path.of(System.getProperty("probe.dir"),"probe"+stage)))Thread.sleep(20);
                        System.out.println("R"+stage+"="+observe(c,other,held,manual,emf,otherEmf,stable,otherStable,stage));
                    }
                }
            }
        }
        """;
}
