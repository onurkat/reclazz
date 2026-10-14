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
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class HttpSecurityPolicyReloadTest {
    @TempDir Path tmp;
    String namespace(){return "javax";}
    String matcher(){return "antMatcher";}
    String rules(){return "authorizeRequests";}
    String paths(){return "antMatchers";}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void realOrderedServletRulesAndIgnoredRouteFollowFourSaves(boolean child)throws Exception {
        String cp=ValidationConstraintReloadTest.nativeClasspath();
        var builder=WatchedApp.in(tmp).classpath(cp).agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .jvmArgs("-Dprobe.dir="+tmp).with("App",appSource()).with("SecurityConfig",config(0));
        if(child)builder.childClassLoader();
        try(var app=builder.start()) {
            app.awaitOrFail("PORT=","servlet startup failed");app.awaitOrFail("] Watching ","watcher missing");
            int port=Integer.parseInt(app.latest("PORT=").substring(5));
            String nonce=observe(port,0);nativeFresh(app.classesDir(),cp,0);
            for(int stage=1;stage<=4;stage++) {
                app.rewrite("SecurityConfig",config(stage));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
                while(reloads(app)<stage&&System.nanoTime()<until)Thread.sleep(25);
                assertEquals(stage,reloads(app),app.tail());nativeFresh(app.classesDir(),cp,stage);
                assertEquals(nonce,observe(port,stage),"context identity changed");
                System.out.println("[HTTP policy] child="+child+" stage="+stage+" allowed="+(stage%2==1));
            }
        }
    }
    private static long reloads(WatchedApp app){return app.output().stream().filter(x->x.contains("Reloaded app.SecurityConfig ")||x.contains("Structural reload: app.SecurityConfig ")).count();}
    private String observe(int port,int stage)throws Exception {
        var client=HttpClient.newHttpClient();String before=get(client,port,"/metrics").body();String[] b=before.split(":");
        for(String path:new String[]{"/gate/work","/gate/free"}) {
            var r=get(client,port,path);assertEquals(stage%2==1?200:403,r.statusCode(),path+" "+r.body());
        }
        assertEquals(200,get(client,port,"/ignored").statusCode(),"ignoring chain lost");
        assertEquals(403,get(client,port,"/other").statusCode(),"unrelated deny rule lost");
        String[] a=get(client,port,"/metrics").body().split(":");
        assertEquals(Integer.parseInt(b[1])+(stage%2==1?2:0),Integer.parseInt(a[1]),"denied gate must not execute body");
        assertEquals(Integer.parseInt(b[2])+1,Integer.parseInt(a[2]),"ignored route body");
        assertEquals(b[3],a[3],"denied unrelated route must not execute body");assertEquals(b[0],a[0]);return a[0];
    }
    private static HttpResponse<String> get(HttpClient c,int port,String path)throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private void nativeFresh(Path classes,String cp,int stage)throws Exception {
        Path dir=Files.createDirectories(tmp.resolve("native-"+stage));Path log=dir.resolve("stdout.log");
        Process p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Dprobe.dir="+dir,"-cp",classes+File.pathSeparator+cp,"app.App").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);String port=null;
            while(port==null&&p.isAlive()&&System.nanoTime()<until){Thread.sleep(25);port=Files.readString(log).lines().filter(x->x.startsWith("PORT=")).findFirst().orElse(null);}
            assertNotNull(port,Files.readString(log));observe(Integer.parseInt(port.substring(5)),stage);System.out.println("[native HTTP] stage="+stage+" passed");
        } finally {p.destroyForcibly();assertTrue(p.waitFor(10,TimeUnit.SECONDS));}
    }
    String config(int stage) {
        return """
                package app;
                import org.springframework.context.annotation.*;
                import org.springframework.core.annotation.Order;
                import org.springframework.security.config.annotation.web.configuration.*;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                import org.springframework.security.web.SecurityFilterChain;
                @Configuration(proxyBeanMethods=false) @EnableWebSecurity
                public class SecurityConfig {
                    @Bean @Order(1) public SecurityFilterChain gate(HttpSecurity http)throws Exception {
                        http.%s("/gate/**").%s(a->a.anyRequest().%s())
                            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->res.sendError(403)))
                            .csrf(c->c.disable());return http.build();
                    }
                    @Bean @Order(2) public SecurityFilterChain fallback(HttpSecurity http)throws Exception {
                        http.%s(a->a.%s("/metrics","/gate/**").permitAll().anyRequest().denyAll())
                            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->res.sendError(403)))
                            .csrf(c->c.disable());return http.build();
                    }
                    @Bean public WebSecurityCustomizer ignored(){return web->web.ignoring().%s("/ignored");}
                }
                """.formatted(matcher(),rules(),stage%2==1?"permitAll":"denyAll",rules(),paths(),paths());
    }
    private String appSource(){return APP.replace("javax.servlet",namespace()+".servlet");}
    private static final String APP="""
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
            @Configuration(proxyBeanMethods=false) @Import(SecurityConfig.class)
            public class App {
                static final String NONCE=java.util.UUID.randomUUID().toString();
                static final java.util.concurrent.atomic.AtomicInteger GATE=new java.util.concurrent.atomic.AtomicInteger(),IGNORED=new java.util.concurrent.atomic.AtomicInteger(),OTHER=new java.util.concurrent.atomic.AtomicInteger();
                public static void main(String[] args)throws Exception {
                    Path base=Files.createDirectories(Path.of(System.getProperty("probe.dir"),"tomcat"));
                    var tomcat=new org.apache.catalina.startup.Tomcat();tomcat.setBaseDir(base.toString());tomcat.setPort(0);tomcat.getConnector().setProperty("address","127.0.0.1");
                    var ctx=tomcat.addContext("",base.toString());ctx.setParentClassLoader(App.class.getClassLoader());
                    var spring=new AnnotationConfigWebApplicationContext();spring.setClassLoader(App.class.getClassLoader());spring.setServletContext(ctx.getServletContext());spring.register(App.class);spring.refresh();
                    var def=new org.apache.tomcat.util.descriptor.web.FilterDef();def.setFilterName("security");def.setFilter(new org.springframework.web.filter.DelegatingFilterProxy("springSecurityFilterChain",spring));ctx.addFilterDef(def);
                    var map=new org.apache.tomcat.util.descriptor.web.FilterMap();map.setFilterName("security");map.addURLPattern("/*");ctx.addFilterMap(map);
                    var servlet=org.apache.catalina.startup.Tomcat.addServlet(ctx,"probe",new javax.servlet.http.HttpServlet(){
                        @Override protected void doGet(javax.servlet.http.HttpServletRequest req,javax.servlet.http.HttpServletResponse res)throws java.io.IOException {
                            String path=req.getRequestURI();res.setContentType("text/plain");
                            if(path.equals("/metrics")){res.getWriter().write(NONCE+":"+GATE.get()+":"+IGNORED.get()+":"+OTHER.get());return;}
                            if(path.startsWith("/gate/"))GATE.incrementAndGet();else if(path.equals("/ignored"))IGNORED.incrementAndGet();else OTHER.incrementAndGet();res.getWriter().write(path);
                        }
                    });servlet.setLoadOnStartup(1);ctx.addServletMappingDecoded("/","probe");tomcat.start();System.out.println("PORT="+tomcat.getConnector().getLocalPort());
                    Runtime.getRuntime().addShutdownHook(new Thread(()->{try{tomcat.stop();tomcat.destroy();spring.close();}catch(Exception ignored){}}));Thread.sleep(120000);
                }
            }
            """;
}
