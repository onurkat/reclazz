/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AddedScopedBeanHttpTest {
    @TempDir Path temp;

    private static final String PRODUCT = """
            package app;
            public class Product implements Tag, org.springframework.beans.factory.DisposableBean {
                private static final java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
                public static final java.util.Map<String,Integer> closed = new java.util.concurrent.ConcurrentHashMap<>();
                private final String key;
                public Product(String version) { key=version+"-"+seq.incrementAndGet(); }
                public String key() { return key; }
                public void destroy() { closed.merge(key,1,Integer::sum); }
            }
            """;

    private static final String SERVER = """
            package app;
            import org.springframework.context.annotation.*;
            import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
            import org.springframework.web.servlet.DispatcherServlet;
            import org.springframework.web.servlet.config.annotation.EnableWebMvc;
            @Configuration @EnableWebMvc @Import({Factories.class, Endpoint.class})
            public class App {
                @Bean public static org.springframework.beans.factory.config.BeanFactoryPostProcessor scopes() {
                    return factory -> factory.registerScope("audit",new org.springframework.context.support.SimpleThreadScope());
                }
                @Bean(name={"scopedTarget.collision","foreignAlias"}) public String foreign() {return "original";}
                public static void main(String[] args) throws Exception {
                    var dir=java.nio.file.Files.createDirectories(java.nio.file.Path.of(System.getProperty("probe.dir"),"tomcat"));
                    var tomcat=new org.apache.catalina.startup.Tomcat();
                    tomcat.setBaseDir(dir.toString()); tomcat.setPort(0);
                    tomcat.getConnector().setProperty("address","127.0.0.1");
                    var web=tomcat.addContext("",dir.toString()); web.setParentClassLoader(App.class.getClassLoader());
                    var spring=new AnnotationConfigWebApplicationContext();
                    spring.setClassLoader(App.class.getClassLoader()); spring.register(App.class);
                    var servlet=org.apache.catalina.startup.Tomcat.addServlet(web,"mvc",new DispatcherServlet(spring));
                    servlet.setLoadOnStartup(1); web.addServletMappingDecoded("/","mvc");
                    tomcat.start();
                    System.out.println("PORT="+tomcat.getConnector().getLocalPort());
                    Thread.sleep(180000);
                }
            }
            """;
    private static final String ENDPOINT = """
            package app;
            @org.springframework.web.bind.annotation.RestController
            public class Endpoint {
                @org.springframework.beans.factory.annotation.Autowired org.springframework.context.ApplicationContext context;
                @org.springframework.web.bind.annotation.GetMapping("/")
                public String check(jakarta.servlet.http.HttpServletRequest request) {
                    String name=request.getParameter("name");
                    if(name.equals("invalidate")) {
                        var session=request.getSession(false); if (session!=null) session.invalidate();
                        return "ok";
                    }
                    if(name.equals("closed")) return Product.closed.getOrDefault(request.getParameter("key"),0).toString();
                    if(name.equals("foreignAlias")) return context.getBean(name,String.class);
                    if(!context.containsBean(name)) return "absent";
                    Tag bean=context.getBean(name,Tag.class);
                    String kind=org.springframework.aop.support.AopUtils.isJdkDynamicProxy(bean)?"jdk":
                            org.springframework.aop.support.AopUtils.isCglibProxy(bean)?"class":"plain";
                    String first=bean.key();
                    if(!first.equals(context.getBean(name,Tag.class).key())) throw new AssertionError("two targets in the same scope");
                    return kind+":"+first;
                }
            }
            """;

    private static String factories(String version, boolean invalid) {
        String definitions=version.isEmpty()?"":"""
                @Bean @Scope(value="request",proxyMode=ScopedProxyMode.TARGET_CLASS)
                public Product requestBean() { return new Product("%1$s"); }
                @Bean @Scope(value="session",proxyMode=ScopedProxyMode.INTERFACES)
                public Tag sessionBean() { return new Product("%1$s"); }
                @Bean @Scope("application") public Product applicationBean() { return new Product("%1$s"); }
                @Bean @Scope("audit") public Product auditBean() { return new Product("%1$s"); }
                @Bean @Scope("request") public Product plainRequest() { return new Product("%1$s"); }
                @Bean @Scope("session") public Product plainSession() { return new Product("%1$s"); }
                @Bean @Scope(value="audit",proxyMode=ScopedProxyMode.TARGET_CLASS)
                public Product auditProxy() { return new Product("%1$s"); }
                """.formatted(version);
        if(invalid) definitions+="""
                @Bean @Scope("unknown-scope") public Product unknown() { return new Product("unknown"); }
                @Bean @Scope(value="request",proxyMode=ScopedProxyMode.TARGET_CLASS)
                public Product collision() { return new Product("collision"); }
                """;
        return "package app; import org.springframework.context.annotation.*;"
                +"@Configuration(proxyBeanMethods=false) public class Factories { "+definitions+" }";
    }

    private WatchedApp launch(boolean nativeRun) throws Exception {
        Path root=temp.resolve(nativeRun?"native":"live");
        var builder=WatchedApp.in(root).classpath(ValidationConstraintReloadTest.nativeClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100").jvmArgs("-Dprobe.dir="+root)
                .with("Tag","package app; public interface Tag { String key(); }")
                .with("Product",PRODUCT).with("Factories",factories(nativeRun?"v1":"",false))
                .with("Endpoint",ENDPOINT).with("App",SERVER);
        if(nativeRun) builder.withoutAgent();
        return builder.start();
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    }

    private static String get(HttpClient client,int port,String query) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/?name="+query))
                .timeout(Duration.ofSeconds(10)).GET().build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response::body);
        return response.body();
    }

    private static void save(WatchedApp app,String version,boolean invalid) throws Exception {
        long before=reloads(app);
        app.rewrite("Factories",factories(version,invalid));
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while (reloads(app)==before && System.nanoTime()<limit) Thread.sleep(25);
        assertTrue(reloads(app)>before,app::tail);
    }

    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(line->line.contains("Reloaded app.Factories") || line.contains("Structural reload: app.Factories (")).count();
    }

    private static void closed(HttpClient client,int port,String product) throws Exception {
        String key=product.substring(product.indexOf(':')+1);
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        String count;
        while ((count=get(client,port,"closed&key="+key)).equals("0") && System.nanoTime()<limit) Thread.sleep(15);
        assertEquals("1",count,product);
    }

    @Test void scopedTargetsFollowRequestsSessionsAndFactoryEdits() throws Exception {
        for(boolean nativeRun:new boolean[]{true,false}) {
            try(var app=launch(nativeRun)) {
                app.awaitOrFail("PORT=","servlet ready");
                int port=Integer.parseInt(app.latest("PORT=").substring(5));
                var first=client();var second=client();
                assertEquals("original",get(first,port,"foreignAlias"));
                if(!nativeRun) {
                    app.awaitOrFail("] Watching ","watcher ready");
                    assertEquals("absent",get(first,port,"requestBean"));
                    save(app,"v1",false);
                }
                String request=get(first,port,"requestBean");
                assertTrue(request.startsWith("class:v1-"),request);
                closed(first,port,request);
                assertNotEquals(request,get(first,port,"requestBean"));
                String session=get(first,port,"sessionBean");
                assertTrue(session.startsWith("jdk:v1-"),session);
                String application=get(first,port,"applicationBean");
                assertTrue(application.startsWith("plain:v1-"),application);
                assertEquals(application,get(second,port,"applicationBean"));
                String custom=get(first,port,"auditBean");
                assertTrue(custom.startsWith("plain:v1-"),custom);
                assertTrue(get(first,port,"auditProxy").startsWith("class:v1-"));
                String plainRequest=get(first,port,"plainRequest");
                assertTrue(plainRequest.startsWith("plain:v1-"));
                closed(first,port,plainRequest);
                String plainSession=get(first,port,"plainSession");
                assertTrue(plainSession.startsWith("plain:v1-"));
                if(!nativeRun) save(app,"v2",false);
                assertEquals(plainSession,get(first,port,"plainSession"));
                assertEquals(session,get(first,port,"sessionBean"));
                String newer=get(second,port,"sessionBean");
                assertTrue(newer.startsWith(nativeRun?"jdk:v1-":"jdk:v2-"),newer);
                assertNotEquals(newer,session);
                assertEquals("ok",get(first,port,"invalidate"));
                closed(first,port,session);
                closed(first,port,plainSession);
                if(!nativeRun) {
                    save(app,"",false);
                    assertEquals("absent",get(second,port,"sessionBean"));
                }
                assertEquals("ok",get(second,port,"invalidate"));
                closed(second,port,newer);
                closed(first,port,session);
                closed(first,port,plainSession);
                if(!nativeRun) {
                    save(app,"",true);
                    app.awaitOrFail("scope 'unknown-scope' is not registered","specific scope refusal");
                    app.awaitOrFail("scopedTarget.collision' is already in use","specific derived name collision");
                    assertEquals("absent",get(first,port,"collision"));
                    assertEquals("absent",get(first,port,"unknown"));
                    assertEquals("original",get(first,port,"foreignAlias"));
                }
            }
        }
    }
}
