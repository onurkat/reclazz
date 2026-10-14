/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.File;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Desired behavior: warm original methods, held proxies, native fresh-JVM controls. */
class ExistingOperationMetadataReloadTest {
    @TempDir Path tmp;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void originalTransactionRulesFollowSavesAndRealCheckedRollback(boolean child) throws Exception { exercise("tx",child); }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void originalCacheRulesFollowSavesAndRealKeysAndBodyCounts(boolean child) throws Exception { exercise("cache",child); }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void bodyOnlySaveEvictsTheSameWarmedKey(boolean child) throws Exception { exercise("eviction",child); }

    private void exercise(String kind,boolean child) throws Exception {
        String h2=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p->Path.of(p).getFileName().toString().startsWith("h2-")).collect(Collectors.joining(File.pathSeparator));
        assertFalse(h2.isEmpty());
        String cp=WatchedApp.springClasspath()+File.pathSeparator+h2;
        var b=WatchedApp.in(tmp).classpath(cp).jvmArgs("-Dprobe.dir="+tmp,"-Dprobe.kind="+kind)
                .with("App",APP).with("Store",store(kind,0));
        if(child)b.childClassLoader();
        try(var app=b.start()) {
            String initial=expected(kind,0);
            app.awaitOrFail("READY="+initial,"native warm original method failed");
            app.awaitOrFail("] Watching ","watcher missing");
            if(child)app.awaitOrFail("APP_IN_CHILD_MODULE=true","child loader missing");
            assertEquals(initial,nativeFresh(app.classesDir(),cp,kind,0));
            for(int stage=1;stage<=4;stage++) {
                app.rewrite("Store",store(kind,stage));
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
                while(reloads(app)<stage&&System.nanoTime()<until)Thread.sleep(25);
                assertEquals(stage,reloads(app),app.tail());
                // This is a new JVM WITHOUT -javaagent, not a fixture-side metadata clear.
                assertEquals(expected(kind,stage),nativeFresh(app.classesDir(),cp,kind,stage),"fresh native baseline");
                Files.createFile(tmp.resolve("probe"+stage));
                app.awaitOrFail("R"+stage+"=","held original-method observation missing");
                assertEquals("R"+stage+"="+expected(kind,stage),app.latest("R"+stage+"="),app.tail());
                System.out.println("[original-operation] "+kind+" child="+child+" "+app.latest("R"+stage+"="));
            }
        }
    }
    private String nativeFresh(Path classes,String cp,String kind,int stage) throws Exception {
        Path output=tmp.resolve("native-"+stage+".log");
        Process p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Dprobe.kind="+kind,"-cp",classes+File.pathSeparator+cp,"app.App","native",""+stage)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(p.waitFor(30,TimeUnit.SECONDS),"native JVM timeout");
            String s=Files.readString(output);assertEquals(0,p.exitValue(),s);
            return s.lines().filter(x->x.startsWith("NATIVE=")).findFirst().orElseThrow(()->new AssertionError(s)).substring(7);
        } finally {if(p.isAlive())p.destroyForcibly();}
    }
    private static String expected(String kind,int stage) {
        if(kind.equals("tx"))return stage+":"+(stage!=3)+":"+(stage==1)+":"+((stage==1||stage==4)?1:2)+":control=true:false";
        boolean cached=kind.equals("eviction") || (stage!=2&&stage!=3);
        return stage+":equal="+cached+":calls="+(cached?1:2)+":entry="+cached+":control=11";
    }
    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s->s.contains("Reloaded app.Store ")||s.contains("Structural reload: app.Store ")).count();
    }
    private static String store(String kind,int v) {
        String operation;
        if(kind.equals("tx")) {
            String annotation=v==3?"":"@org.springframework.transaction.annotation.Transactional(readOnly="+(v==1)
                    +((v==1||v==4)?", rollbackFor=Exception.class":"")+")";
            operation=annotation+"\npublic String operation(int id,boolean fail) throws Exception { boolean active=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(); boolean readOnly=org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly(); jdbc.update(\"insert into entries values (?)\",id); if(fail)throw new Exception(\"checked\"); return \""+v+":\"+active+\":\"+readOnly; }";
        } else {
            int metadataVersion=kind.equals("eviction")?0:v;
            String annotation=metadataVersion==3?"":"@org.springframework.cache.annotation.Cacheable(cacheNames=\""+(metadataVersion==1?"changed":"values")+"\", key=\""+(metadataVersion==1?"'next/' + #p0":"#p0")+"\", condition=\""+(metadataVersion==2?"false":"true")+"\")";
            operation=annotation+"\npublic String operation(String key) { calls++; return \""+v+":\"+key+\":\"+calls; }";
        }
        operation += kind.equals("tx") ? "\npublic String operation(String key) { return \"unused\"; }" : "\npublic String operation(int id,boolean fail) throws Exception { return \"unused\"; }";
        return """
                package app;
                public class Store {
                    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
                    private int calls;
                    public Store(org.springframework.jdbc.core.JdbcTemplate jdbc) { this.jdbc=jdbc; }
                    public int version() { return %d; }
                    public int calls() { return calls; }
                    @org.springframework.transaction.annotation.Transactional
                    public String txControl() { return ""+org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()+":"+org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly(); }
                    @org.springframework.cache.annotation.Cacheable("control") public int cacheControl() { return 11; }
                    %s
                }
                """.formatted(v,operation);
    }
    private static final String APP="""
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            @Configuration(proxyBeanMethods=false)
            @org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass=true)
            @org.springframework.cache.annotation.EnableCaching(proxyTargetClass=true)
            public class App {
                @Bean public org.h2.jdbcx.JdbcDataSource dataSource() { var d=new org.h2.jdbcx.JdbcDataSource();d.setURL("jdbc:h2:mem:original_operations;DB_CLOSE_DELAY=-1");return d; }
                @Bean public org.springframework.jdbc.core.JdbcTemplate jdbc(org.h2.jdbcx.JdbcDataSource d) { return new org.springframework.jdbc.core.JdbcTemplate(d); }
                @Bean public org.springframework.transaction.PlatformTransactionManager transactionManager(org.h2.jdbcx.JdbcDataSource d) { return new org.springframework.jdbc.datasource.DataSourceTransactionManager(d); }
                @Bean public org.springframework.cache.CacheManager cacheManager() { return new org.springframework.cache.concurrent.ConcurrentMapCacheManager("values","changed","control"); }
                static int creations, coldCreations;
                @Bean public Store store(org.springframework.jdbc.core.JdbcTemplate jdbc) { creations++; return new Store(jdbc); }
                @Bean @Lazy public Object coldProduct() { coldCreations++; return new Object(); }
                static String observe(org.springframework.context.ApplicationContext c,Store held,int stage) throws Exception {
                    if(held!=c.getBean(Store.class)||creations!=1||coldCreations!=0)throw new AssertionError("helper reload recreated or initialized a product");
                    if(System.getProperty("probe.kind").equals("tx")) {
                        var jdbc=c.getBean(org.springframework.jdbc.core.JdbcTemplate.class);jdbc.update("delete from entries");
                        String result=held.operation(1,false);
                        try { held.operation(2,true);throw new AssertionError("expected checked exception"); }
                        catch(Exception e) { if(!"checked".equals(e.getMessage()))throw e; }
                        return result+":"+jdbc.queryForObject("select count(*) from entries",Integer.class)+":control="+held.txControl();
                    }
                    boolean eviction=System.getProperty("probe.kind").equals("eviction");
                    int before=held.calls();String key=eviction?"held":"k"+stage;
                    String first=held.operation(key),second=held.operation(key);
                    String region=stage==1&&!eviction?"changed":"values",entryKey=stage==1&&!eviction?"next/"+key:key;
                    var entry=c.getBean(org.springframework.cache.CacheManager.class).getCache(region).get(entryKey);
                    if(!first.startsWith(stage+":"+key+":")||!second.startsWith(stage+":"+key+":"))throw new AssertionError("stale body: "+first+" "+second);
                    return held.version()+":equal="+first.equals(second)+":calls="+(held.calls()-before)+":entry="+(entry!=null&&second.equals(entry.get()))+":control="+held.cacheControl();
                }
                public static void main(String[] args) throws Exception {
                    try(var c=new AnnotationConfigApplicationContext(App.class)) {
                        c.getBean(org.springframework.jdbc.core.JdbcTemplate.class).execute("create table entries(id int)");
                        Store held=c.getBean(Store.class);
                        if(!(held instanceof org.springframework.aop.framework.Advised))throw new AssertionError("native proxy missing");
                        if(args.length>0) { System.out.println("NATIVE="+observe(c,held,Integer.parseInt(args[1])));return; }
                        System.out.println("READY="+observe(c,held,0));
                        for(int stage=1;stage<=4;stage++) {
                            while(!Files.exists(Path.of(System.getProperty("probe.dir"),"probe"+stage)))Thread.sleep(20);
                            System.out.println("R"+stage+"="+observe(c,held,stage));
                        }
                    }
                }
            }
            """;
}
