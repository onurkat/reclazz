/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class KeepSingletonStateReloadTest {
    @TempDir Path tmp;

    @Test
    void bodyEditKeepsCounterAndMap() throws Exception {
        exercise(false);
    }

    @Test
    void controllerBodyEditKeepsStateAndMappings() throws Exception {
        exercise(true);
    }


    String annotationNamespace() { return "javax.annotation"; }

    @Test void addedMethodAndLiftedFieldKeepInstance() throws Exception { variant("field", false); }
    @Test void constructorChangeStillRecreates() throws Exception { variant("constructor", true); }
    @Test void addedAutowiredFieldStillRecreates() throws Exception { variant("injection", true); }
    @Test void addedPostConstructStillRecreates() throws Exception { variant("lifecycle", true); }
    @Test void postConstructBodyEditRecreates() throws Exception { variant("postBody", true); }
    @Test void autowiredSetterBodyEditRecreates() throws Exception { variant("setterBody", true); }
    @Test void xmlInitMethodBodyEditRecreates() throws Exception { variant("xmlInit", true); }
    @Test void xmlPropertySetterBodyEditRecreates() throws Exception { variant("xmlProperty", true); }
    @Test void businessMethodEditOnCallbackBeanKeeps() throws Exception { variant("businessCallback", false); }
    @Test void nativeBodyEditKeepsState() throws Exception { variant("native", false); }
    @Test void cglibProxiedBodyEditKeepsTarget() throws Exception { variant("proxy", false); }

    private void variant(String mode, boolean recreated) throws Exception {
        var client = HttpClient.newHttpClient();
        String appSource = APP.replace("public static void main", metadata(mode) + "\npublic static void main");
        try (var app = WatchedApp.in(tmp).classpath(ValidationConstraintReloadTest.nativeClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true" + (mode.equals("native") ? ",structuralReload=false" : ""))
                .jvmArgs("-Dprobe.dir=" + tmp).with("App", appSource)
                .with("Stateful", variantSource(mode, false)).with("Endpoint", endpoint(false)).start()) {
            app.awaitOrFail("PORT=", "servlet missing"); app.awaitOrFail("] Watching ", "watcher missing");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            String[] first = request(client, port).split(":");
            long before = reloads(app);
            app.rewrite("Stateful", variantSource(mode, true));
            awaitReloads(app,before+1);
            String[] next=request(client, port).split(":");
            assertEquals("v2", next[0], app.tail());
            assertEquals(recreated ? "1" : "2",next[1],app.tail());
            assertEquals(recreated ? "7" : first[2],next[2],app.tail());
            if(recreated)assertNotEquals(first[3],next[3],app.tail());
            else assertEquals(first[3],next[3],app.tail());
            assertTrue(app.output().stream().anyMatch(line->line.contains(recreated ? "bean stateful re-created (" : "bean stateful kept, state preserved")),app.tail());
            if(mode.equals("field"))assertEquals("[]",next[4],app.tail());
            if(mode.equals("proxy")) {
                before=reloads(app);
                app.rewrite("Stateful",variantSource(mode,true).replace("public String next()", "public String added() { return \"new\"; } public String next()"));
                awaitReloads(app,before+1);
                String[] added=request(client,port).split(":");
                assertNotEquals(next[3],added[3],app.tail());
                assertTrue(app.output().stream().anyMatch(line->line.contains("CGLIB public method added")),app.tail());
            }
        }
    }

    private String variantSource(String mode,boolean changed) {
        String field="", callback="", constructor="", extra="", result="";
        boolean existingCallback=java.util.Set.of("postBody","businessCallback").contains(mode);
        if(existingCallback||mode.equals("lifecycle")&&changed)
            callback="@"+annotationNamespace()+".PostConstruct public void init() { limit = "+(changed&&!mode.equals("businessCallback")?7:3)+"; }";
        if(mode.equals("setterBody"))callback="@org.springframework.beans.factory.annotation.Autowired public void wire(org.springframework.context.ApplicationContext c) { limit = "+(changed?7:3)+"; }";
        if(mode.equals("xmlInit"))callback="public void init() { limit = "+(changed?7:3)+"; }";
        if(mode.equals("xmlProperty"))callback="public void setLimit(int value) { limit = value + "+(changed?4:0)+"; }";
        if(mode.equals("injection")&&changed) {
            field="@org.springframework.beans.factory.annotation.Autowired org.springframework.context.ApplicationContext context;";
            result="if(context == null) throw new AssertionError(\"injection missing\"); limit=7;";
        }
        if(mode.equals("constructor"))constructor="public Stateful() { limit = "+(changed?7:3)+"; }";
        if(mode.equals("field")&&changed) { field="java.util.List<String> tags = new java.util.ArrayList<>();";extra="public String added() { return tags.toString(); }"; }
        return "package app; @org.springframework.stereotype.Service public class Stateful { int count; int limit; "+field+constructor+callback+extra+
                " public String next() { "+result+" return \""+(changed?"v2":"v1")+":\" + (++count) + \":\" + limit + \":\" + System.identityHashCode(this)"+
                (mode.equals("field")&&changed?" + \":\" + added()":"")+"; } }";
    }

    private static String metadata(String mode) {
        if(mode.equals("xmlInit")||mode.equals("xmlProperty"))return """
                @Bean public static org.springframework.beans.factory.config.BeanFactoryPostProcessor metadata() {
                    return factory -> {
                        var definition = factory.getBeanDefinition("stateful");
                        %s
                    };
                }
                """.formatted(mode.equals("xmlInit")?"definition.setInitMethodName(\"init\");":"definition.getPropertyValues().add(\"limit\",3);");
        if(mode.equals("proxy"))return """
                @Bean public static org.springframework.beans.factory.config.BeanPostProcessor proxy() {
                    return new org.springframework.beans.factory.config.BeanPostProcessor() {
                        public Object postProcessAfterInitialization(Object bean, String name) {
                            if (!name.equals("stateful")) return bean;
                            var proxy = new org.springframework.aop.framework.ProxyFactory(bean);
                            proxy.setProxyTargetClass(true);
                            proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> invocation.proceed());
                            return proxy.getProxy();
                        }
                    };
                }
                """;
        return "";
    }

    private static void awaitReloads(WatchedApp app,long count) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        while(reloads(app)<count&&System.nanoTime()<until)Thread.sleep(20);
        assertEquals(count,reloads(app),app.tail());
    }


    @Test void serviceBodyEditKeepsServiceAndDependent() throws Exception { dependent(false); }
    @Test void mixedBatchHealsOnlyTheRecreatedBean() throws Exception { dependent(true); }

    private void dependent(boolean mixed) throws Exception {
        var client=HttpClient.newHttpClient();
        String dependent="""
                package app;
                @org.springframework.web.bind.annotation.RestController public class Endpoint {
                    int count;
                    @org.springframework.beans.factory.annotation.Autowired Stateful stateful;
                    @org.springframework.web.bind.annotation.GetMapping("/state") public String state() {
                        return stateful.next() + ":" + (++count) + ":" + System.identityHashCode(this);
                    }
                }
                """;
        String b="package app; @org.springframework.stereotype.Service public class StatefulB { int count; int limit; public StatefulB(){limit=3;} public String next(){return (++count)+\":\"+limit+\":\"+System.identityHashCode(this);} }";
        String dependentB=dependent.replace("Endpoint","EndpointB").replace("Stateful","StatefulB").replace("/state","/b");
        try(var app=WatchedApp.in(tmp).classpath(ValidationConstraintReloadTest.nativeClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=250,verbose=true")
                .jvmArgs("-Dprobe.dir="+tmp).with("App",APP).with("Stateful",stateful("v1",false))
                .with("Endpoint",dependent).with("StatefulB",b).with("EndpointB",dependentB).start()) {
            app.awaitOrFail("PORT=","servlet missing");app.awaitOrFail("] Watching ","watcher missing");
            int port=Integer.parseInt(app.latest("PORT=").substring(5));
            String[] aBefore=request(client,port).split(":"), bBefore=request(client,port,"/b").split(":");
            long before=reloads(app);
            if(mixed)app.rewriteAll(java.util.Map.of("Stateful",stateful("v2",false),"StatefulB",b.replace("limit=3","limit=7")));
            else app.rewrite("Stateful",stateful("v2",false));
            awaitReloads(app,before+1);
            if(mixed)assertTrue(app.awaits("Reloaded app.StatefulB ",45),app.tail());
            String[] aAfter=request(client,port).split(":"), bAfter=request(client,port,"/b").split(":");
            assertEquals("v2",aAfter[0],app.tail());assertEquals("2",aAfter[1],app.tail());
            assertEquals(aBefore[3],aAfter[3],"service identity");
            assertEquals("2",aAfter[4],"dependent counter");assertEquals(aBefore[5],aAfter[5],"dependent identity");
            if(mixed) { assertEquals("7",bAfter[1],app.tail());assertNotEquals(bBefore[2],bAfter[2],"dependent must see new B"); }
            else assertEquals(bBefore[2],bAfter[2]);
        }
    }

    @Test void addedFieldThenBodyThenConstructorUsesEachSuccessfulBaseline() throws Exception {
        var client=HttpClient.newHttpClient();
        String initial=variantSource("field",false);
        try(var app=WatchedApp.in(tmp).classpath(ValidationConstraintReloadTest.nativeClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true").jvmArgs("-Dprobe.dir="+tmp)
                .with("App",APP).with("Stateful",initial).with("Endpoint",endpoint(false)).start()) {
            app.awaitOrFail("PORT=","servlet missing");app.awaitOrFail("] Watching ","watcher missing");
            int port=Integer.parseInt(app.latest("PORT=").substring(5));
            String[] first=request(client,port).split(":");
            String field=variantSource("field",true);
            String body=field.replace("v2:","v3:");
            int count=1;
            for(String source:java.util.List.of(field,body)) {
                long before=reloads(app);app.rewrite("Stateful",source);awaitReloads(app,before+1);
                String[] answer=request(client,port).split(":");
                assertEquals(Integer.toString(++count),answer[1],app.tail());assertEquals(first[3],answer[3]);
                assertEquals("[]",answer[4]);
            }
            long before=reloads(app);
            app.rewrite("Stateful",body.replace("int limit;","int limit; public Stateful(){limit=7;}"));awaitReloads(app,before+1);
            String[] recreated=request(client,port).split(":");
            assertEquals("1",recreated[1]);assertEquals("7",recreated[2]);assertNotEquals(first[3],recreated[3]);
        }
    }

    @Test void failedNativeReloadDoesNotAdvanceBaseline() throws Exception {
        var client=HttpClient.newHttpClient();String original=stateful("v1",false);
        try(var app=WatchedApp.in(tmp).classpath(ValidationConstraintReloadTest.nativeClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true,structuralReload=false")
                .jvmArgs("-Dprobe.dir="+tmp).with("App",APP).with("Stateful",original).with("Endpoint",endpoint(false)).start()) {
            app.awaitOrFail("PORT=","servlet missing");app.awaitOrFail("] Watching ","watcher missing");
            int port=Integer.parseInt(app.latest("PORT=").substring(5));String[] first=request(client,port).split(":");
            app.rewrite("Stateful",original.replace("private int count;","private int count; private int added;"));
            assertTrue(app.awaits("Hot-swap failed for",45),app.tail());
            long before=reloads(app);app.rewrite("Stateful",stateful("v2",false));awaitReloads(app,before+1);
            String[] answer=request(client,port).split(":");
            assertEquals("v2",answer[0]);assertEquals("2",answer[1],app.tail());assertEquals(first[3],answer[3]);
            assertTrue(app.output().stream().anyMatch(line->line.contains("bean stateful kept, state preserved")),app.tail());
        }
    }

    @Test void preloadedBeanWithoutBaselineRecreates() throws Exception {
        // The first agent defines the application bean before Reclazz registers its transformer.
        Path source=tmp.resolve("Preloader.java"), classes=java.nio.file.Files.createDirectories(tmp.resolve("preloader"));
        java.nio.file.Files.writeString(source,"public class Preloader { public static void premain(String args, java.lang.instrument.Instrumentation inst) throws Exception { Class.forName(\"app.Stateful\"); } }");
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",classes.toString(),source.toString()));
        var manifest=new java.util.jar.Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");manifest.getMainAttributes().putValue("Premain-Class","Preloader");
        Path jar=tmp.resolve("preloader.jar");
        try(var out=new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar),manifest)) {
            out.putNextEntry(new java.util.jar.JarEntry("Preloader.class"));out.write(java.nio.file.Files.readAllBytes(classes.resolve("Preloader.class")));out.closeEntry();
        }
        var client=HttpClient.newHttpClient();
        try(var app=WatchedApp.in(tmp).classpath(ValidationConstraintReloadTest.nativeClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true,structuralReload=false")
                .jvmArgs("-javaagent:"+jar,"-Dprobe.dir="+tmp).with("App",APP).with("Stateful",stateful("v1",false)).with("Endpoint",endpoint(false)).start()) {
            app.awaitOrFail("PORT=","servlet missing");app.awaitOrFail("] Watching ","watcher missing");
            int port=Integer.parseInt(app.latest("PORT=").substring(5));String[] first=request(client,port).split(":");
            long before=reloads(app);app.rewrite("Stateful",stateful("v2",false));awaitReloads(app,before+1);
            String[] answer=request(client,port).split(":");
            assertEquals("v2",answer[0]);assertEquals("1",answer[1],app.tail());assertNotEquals(first[3],answer[3]);
            assertTrue(app.output().stream().anyMatch(line->line.contains("bean stateful re-created (no baseline")),app.tail());
        }
    }

    private void exercise(boolean controller) throws Exception {
        var client = HttpClient.newHttpClient();
        try (var app = WatchedApp.in(tmp)
                .classpath(ValidationConstraintReloadTest.nativeClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .jvmArgs("-Dprobe.dir=" + tmp)
                .with("App", APP)
                .with("Stateful", stateful("v1", controller))
                .with("Endpoint", endpoint(controller))
                .start()) {
            app.awaitOrFail("PORT=", "native servlet did not start");
            app.awaitOrFail("] Watching ", "watcher missing");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            String identity = null;
            for (int n = 1; n <= 3; n++) {
                String[] answer = request(client, port).split(":");
                assertEquals("v1", answer[0]);
                assertEquals(Integer.toString(n), answer[1]);
                if (identity == null) identity = answer[3];
                assertEquals(identity, answer[3]);
            }
            for (int save = 2; save <= 4; save++) {
                long before = reloads(app);
                app.rewrite("Stateful", stateful("v" + save, controller));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
                while (reloads(app) == before && System.nanoTime() < until) Thread.sleep(20);
                assertEquals(before + 1, reloads(app), app.tail());
                String[] answer = request(client, port).split(":");
                assertEquals("v" + save, answer[0], app.tail());
                assertEquals(Integer.toString(save + 2), answer[1],
                        "singleton count must continue after a completed reload: " + app.tail());
                assertEquals("warm", answer[2], "map entry survives");
                assertEquals(identity, answer[3], "the same singleton serves new code");
            }
            if(controller) {
                long before=reloads(app);
                app.rewrite("Stateful",stateful("v5",true).replace("public String next()", "public String next()")
                        .replace("private int count;","@GetMapping(\"/added\") public String added() { return \"added:\" + count; } private int count;"));
                awaitReloads(app,before+1);
                assertEquals("added:6",request(client,port,"/added"),app.tail());
                String[] answer=request(client,port).split(":");
                assertEquals("7",answer[1],app.tail());assertEquals(identity,answer[3]);
            }
        }
    }

    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Stateful ")
                || s.contains("Structural reload: app.Stateful ")).count();
    }

    private static String request(HttpClient client, int port) throws Exception {
        return request(client,port,"/state");
    }
    private static String request(HttpClient client, int port, String path) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private static String stateful(String prefix, boolean controller) {
        return """
                package app;
                import org.springframework.stereotype.Service;
                import org.springframework.web.bind.annotation.*;
                %s public class Stateful {
                    private int count;
                    private final java.util.Map<String,String> cache = new java.util.HashMap<>();
                    %s public String next() {
                        count++;
                        if (count == 1) cache.put("key", "warm");
                        return "%s:" + count + ":" + cache.get("key") + ":" + System.identityHashCode(this);
                    }
                }
                """.formatted(controller ? "@RestController" : "@Service",
                        controller ? "@GetMapping(\"/state\")" : "", prefix);
    }

    private static String endpoint(boolean controller) {
        return controller ? "package app; public class Endpoint {}" : """
                package app;
                import org.springframework.web.bind.annotation.*;
                @RestController public class Endpoint {
                    @org.springframework.beans.factory.annotation.Autowired Stateful stateful;
                    @GetMapping("/state") public String state() { return stateful.next(); }
                }
                """;
    }

    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            import org.springframework.web.servlet.config.annotation.EnableWebMvc;
            import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
            import org.springframework.web.servlet.DispatcherServlet;
            @Configuration @EnableWebMvc @ComponentScan("app")
            public class App {
                public static void main(String[] args) throws Exception {
                    Path base=Files.createDirectories(Path.of(System.getProperty("probe.dir"),"tomcat"));
                    var tomcat=new org.apache.catalina.startup.Tomcat(); tomcat.setBaseDir(base.toString()); tomcat.setPort(0);
                    tomcat.getConnector().setProperty("address","127.0.0.1");
                    var context=tomcat.addContext("",base.toString()); context.setParentClassLoader(App.class.getClassLoader());
                    var spring=new AnnotationConfigWebApplicationContext(); spring.setClassLoader(App.class.getClassLoader()); spring.register(App.class);
                    var servlet=org.apache.catalina.startup.Tomcat.addServlet(context,"mvc",new DispatcherServlet(spring));
                    servlet.setAsyncSupported(true); servlet.setLoadOnStartup(1); context.addServletMappingDecoded("/","mvc");
                    tomcat.start(); System.out.println("PORT="+tomcat.getConnector().getLocalPort());
                    Runtime.getRuntime().addShutdownHook(new Thread(()->{try { tomcat.stop(); tomcat.destroy(); }catch(Exception ignored){} }));
                    Thread.sleep(120000);
                }
            }
            """;
}
