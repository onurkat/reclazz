/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.StatusTestClient;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Boot root + Tomcat MVC child, native transaction/cache advice and actual class saves. */
public final class PlainSingletonGainsProxyAcceptance {
    private PlainSingletonGainsProxyAcceptance(){}
    public static void run(Path dir,String kind)throws Exception {
        String version=(String)Class.forName("org.springframework.boot.SpringBootVersion").getMethod("getVersion").invoke(null);
        boolean tx=kind.equals("transaction");
        var builder=WatchedApp.in(dir).classpath(frameworkClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=200,verbose=true,autoCompile=false,portFile="+dir.resolve("agent.port"))
                .jvmArgs("-Dprobe.dir="+dir,"-Dprobe.tx="+tx)
                .with("App",WRAPPER).with("Runner",APP).with("Work",work(false,false))
                .with("ColdHolder","package app;public class ColdHolder {static app.Work value=make();static app.Work make(){app.Runner.coldInitializations++;return new app.Work();}}");
        WatchedApp app=builder.start();
        try(app) {
            app.awaitOrFail("] Watching 1 director","watcher before Boot application classes");Files.writeString(dir.resolve("load"),"go");
            app.awaitOrFail("R29_READY|"+version,"actual Boot and warmed MVC handler ready");
            command(dir,"RELOAD pause request=r29-pause","RELOAD_STATE r29-pause pause ");
            app.rewrite("Work",work(tx,!tx));
            command(dir,"SCAN\nRELOAD resume request=r29-resume","RELOAD_STATE r29-resume resume ");
            receipt(dir,app);
            Files.writeString(dir.resolve("observe"),"go");app.awaitOrFail("R29_DONE|","fresh proxy and HTTP observations");
            var checks=app.output().stream().filter(l->l.startsWith("R29_CHECK|")).toList();
            assertEquals(12,checks.size(),"all native behavior checks must execute: "+app.tail());
            assertFalse(checks.stream().anyMatch(l->l.contains("|FAIL|")),String.join("\n",checks));
            assertTrue(checks.contains("R29_CHECK|nativeProxy|PASS|true"),app.tail());
            assertTrue(checks.contains("R29_CHECK|httpThroughFreshConstructorConsumer|PASS|true"),app.tail());
            assertTrue(checks.contains("R29_CHECK|nativeAdviceEffect|PASS|true"),app.tail());
        }finally {
            Path evidence=Path.of(System.getenv().getOrDefault("RECLAZZ_PROXY_EVIDENCE","build/proxy-evidence"),"boot-"+version+"-"+kind);
            Files.createDirectories(evidence);Files.write(evidence.resolve("child.log"),app.output());
        }
    }
    private static String work(boolean transaction,boolean cache){return """
        package app;public class Work {
        private int calls;
        TX public boolean transaction(){return org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();}
        CACHE public int cached(){return ++calls;}
        public int calls(){return calls;}}
        """.replace("TX",transaction?"@org.springframework.transaction.annotation.Transactional":"").replace("CACHE",cache?"@org.springframework.cache.annotation.Cacheable(cacheNames=\"numbers\")":"");}
    private static String frameworkClasspath(){
        var entries=new ArrayList<String>();for(String entry:System.getProperty("java.class.path").split(File.pathSeparator)) {
            String n=Path.of(entry).getFileName().toString();
            if(n.endsWith(".jar")&&(n.startsWith("spring-")||n.startsWith("jackson-")||n.startsWith("tomcat-")||n.startsWith("commons-logging-")
                    ||n.startsWith("micrometer-")||n.startsWith("jspecify-")||n.startsWith("jakarta.")||n.startsWith("javax.")||n.startsWith("snakeyaml-")
                    ||n.startsWith("jboss-logging-")||n.startsWith("hibernate-validator-")||n.startsWith("classmate-")||n.startsWith("expressly-")||n.startsWith("el-api-")||n.startsWith("validation-api-")))entries.add(entry);
        }return String.join(File.pathSeparator,entries);
    }
    private static String command(Path dir,String command,String prefix)throws Exception {
        var json=new ObjectMapper();try(Socket socket=StatusTestClient.connect(dir.resolve("agent.port"))) {
            socket.setSoTimeout(15000);var input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));socket.getOutputStream().write((command+"\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
            for(;;){String line=input.readLine();assertNotNull(line);String message=json.readTree(line).path("message").asText();if(message.startsWith(prefix))return message.substring(prefix.length());}
        }
    }
    private static void receipt(Path dir,WatchedApp app)throws Exception {
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(app.classesDir().resolve("app/Work.class"))));
        var json=new ObjectMapper();long end=System.nanoTime()+30_000_000_000L;JsonNode result=null;
        while(System.nanoTime()<end) {
            String token="r29-"+System.nanoTime();result=json.readTree(command(dir,"VERIFY "+token+" app.Work "+hash,"VERIFY_RESULT "+token+" "));
            if(List.of("applied","failed","unverified").contains(result.path("status").asText())){assertEquals("applied",result.path("status").asText(),result+"\n"+app.tail());assertEquals(hash,result.path("observedSha256").asText());return;}Thread.sleep(50);
        }fail("No terminal R29 receipt: "+result+"\n"+app.tail());
    }
    private static final String WRAPPER="""
        package app;public class App {public static void main(String[] args)throws Exception {
        var dir=java.nio.file.Path.of(System.getProperty("probe.dir"));while(!java.nio.file.Files.exists(dir.resolve("load")))Thread.sleep(20);
        Class.forName("app.Runner").getMethod("main",String[].class).invoke(null,(Object)args);}}
        """;
    private static final String APP="""
        package app;
        import java.nio.file.*;import java.util.*;import java.net.*;import java.net.http.*;
        import org.springframework.boot.*;import org.springframework.context.annotation.*;import org.springframework.cache.*;
        import org.springframework.cache.annotation.EnableCaching;import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
        import org.springframework.transaction.*;import org.springframework.transaction.annotation.EnableTransactionManagement;import org.springframework.transaction.support.*;
        import org.springframework.web.bind.annotation.*;import org.springframework.web.servlet.config.annotation.EnableWebMvc;
        import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;import org.springframework.web.servlet.DispatcherServlet;
        public class Runner {
          public static int coldInitializations;static int checks;static final java.util.concurrent.atomic.AtomicInteger begins=new java.util.concurrent.atomic.AtomicInteger(),commits=new java.util.concurrent.atomic.AtomicInteger();
          public static class Holder {public final Work work;Holder(Work work){this.work=work;}}
          @Configuration(proxyBeanMethods=false) @EnableTransactionManagement(proxyTargetClass=true) @EnableCaching(proxyTargetClass=true)
          public static class NativeConfig {
            @Bean Work work(){return new Work();}@Bean Holder holder(Work w){return new Holder(w);}
            @Bean org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource unrelatedSource(){return new org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource();}
            @Bean CacheManager cacheManager(){return new ConcurrentMapCacheManager("numbers");}
            @Bean PlatformTransactionManager transactionManager(){return new AbstractPlatformTransactionManager(){protected Object doGetTransaction(){return new Object();}protected void doBegin(Object t,TransactionDefinition d){begins.incrementAndGet();}protected void doCommit(DefaultTransactionStatus s){commits.incrementAndGet();}protected void doRollback(DefaultTransactionStatus s){}};}
          }
          @Configuration(proxyBeanMethods=false) @EnableWebMvc public static class WebConfig {@Bean Endpoint endpoint(Work w,Holder h){return new Endpoint(w,h);}}
          @RestController public static class Endpoint {
            public final Work work;public final Holder holder;Endpoint(Work w,Holder h){work=w;holder=h;}
            @GetMapping(value="/probe",produces="text/plain")public String probe(){boolean tx=work.transaction();int a=work.cached(),b=holder.work.cached();return "proxy="+org.springframework.aop.support.AopUtils.isAopProxy(work)+";tx="+tx+";same="+(work==holder.work)+";a="+a+";b="+b+";calls="+work.calls();}
          }
          static String request(int port)throws Exception {var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/probe")).timeout(java.time.Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new AssertionError("HTTP "+response.statusCode()+" "+response.body());return response.body();}
          static void check(String name,boolean ok){checks++;System.out.println("R29_CHECK|"+name+"|"+(ok?"PASS":"FAIL")+"|"+ok);System.out.flush();}
          public static void main(String[] args)throws Exception {
            Path dir=Path.of(System.getProperty("probe.dir"));boolean tx=Boolean.getBoolean("probe.tx");
            var boot=new SpringApplication(NativeConfig.class);boot.setWebApplicationType(WebApplicationType.NONE);boot.setDefaultProperties(Map.of("spring.main.banner-mode","off","logging.level.root","WARN"));var root=boot.run();
            var tomcat=new org.apache.catalina.startup.Tomcat();tomcat.setBaseDir(Files.createDirectories(dir.resolve("tomcat")).toString());tomcat.setPort(0);tomcat.getConnector().setProperty("address","127.0.0.1");
            var context=tomcat.addContext("",dir.toString());context.setParentClassLoader(Runner.class.getClassLoader());var web=new AnnotationConfigWebApplicationContext();web.setParent(root);web.setClassLoader(Runner.class.getClassLoader());web.register(WebConfig.class);
            var servlet=org.apache.catalina.startup.Tomcat.addServlet(context,"mvc",new DispatcherServlet(web));servlet.setLoadOnStartup(1);context.addServletMappingDecoded("/","mvc");tomcat.start();int port=tomcat.getConnector().getLocalPort();
            Work old=root.getBean(Work.class);Holder oldHolder=root.getBean(Holder.class);Endpoint oldEndpoint=web.getBean(Endpoint.class);Class.forName("app.ColdHolder",false,Runner.class.getClassLoader());
            String baseline=request(port);request(port);if(org.springframework.aop.support.AopUtils.isAopProxy(old)||!baseline.contains("proxy=false;tx=false;same=true;a=1;b=2"))throw new AssertionError("plain baseline "+baseline);
            System.out.println("R29_READY|"+SpringBootVersion.getVersion()+"|"+baseline);System.out.flush();while(!Files.exists(dir.resolve("observe")))Thread.sleep(20);
            Work fresh=root.getBean(Work.class);Holder holder=root.getBean(Holder.class);Endpoint endpoint=web.getBean(Endpoint.class);String result=request(port);System.out.println("R29_HTTP|"+result);
            check("nativeProxy",org.springframework.aop.support.AopUtils.isAopProxy(fresh));check("rootRecreated",fresh!=old);check("constructorConsumerRecreated",holder!=oldHolder);check("controllerRecreated",endpoint!=oldEndpoint);
            check("constructorUsesNewProxy",holder.work==fresh);check("controllerUsesNewProxy",endpoint.work==fresh&&endpoint.holder==holder);
            check("httpThroughFreshConstructorConsumer",result.contains("proxy=true;tx="+tx+";same=true"));
            check("nativeAdviceEffect",tx?begins.get()>0&&commits.get()==begins.get():result.contains("a=1;b=1"));
            check("stateResetsThroughNativeCreation",result.contains(tx?"a=1;b=2;calls=2":"a=1;b=1;calls=1"));
            check("coldStaticHolderNotInitialized",coldInitializations==0);check("oldConsumerStateNotMutated",oldHolder.work==old&&oldEndpoint.work==old&&oldEndpoint.holder==oldHolder);
            check("noRestartConcern",Class.forName("com.onurkat.reclazz.ui.RestartLedger").getMethod("digest").invoke(null).equals(List.of("Nothing from this session needs a restart.")));
            System.out.println("R29_DONE|"+checks);System.out.flush();web.close();root.close();tomcat.stop();tomcat.destroy();
          }
        }
        """;
}
