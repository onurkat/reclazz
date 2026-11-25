/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

public class RecreatedBeanDependentSeesNewInstanceTest {
    @TempDir Path tmp;
    @Test void constructorEditReachesDependentController() throws Exception { exercise("service"); }
    @Test void controllerConstructorEditReachesRequests() throws Exception { exercise("controller"); }
    @Test void transitiveDependencyEditReachesController() throws Exception { exercise("transitive"); }

    private void exercise(String mode) throws Exception {
        String service="package app; @org.springframework.stereotype.Service public class Stateful { int value; public Stateful(){value=3;} public int value(){return value;} }";
        String middle="package app; @org.springframework.stereotype.Service public class Middle { @org.springframework.beans.factory.annotation.Autowired Stateful stateful; public int value(){return stateful.value();} }";
        String endpoint=ENDPOINT.replace("SOURCE_TYPE",mode.equals("transitive")?"Middle":"Stateful")
                .replace("VALUE",mode.equals("controller")?"limit":"source.value()");
        var client=HttpClient.newHttpClient();
        try(var app=WatchedApp.in(tmp).classpath(ValidationConstraintReloadTest.nativeClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true").jvmArgs("-Dprobe.dir="+tmp)
                .with("App",APP).with("Stateful",service).with("Middle",middle)
                .with("Endpoint",endpoint).with("Unaffected",UNAFFECTED).start()) {
            app.awaitOrFail("PORT=","servlet missing");app.awaitWatcherReady();
            int port=Integer.parseInt(app.latest("PORT=").substring(5));
            assertEquals("3",request(client,port,"/state"));
            String[] unaffectedBefore=request(client,port,"/unaffected").split(":");
            assertEquals("1",unaffectedBefore[0]);
            String edited=mode.equals("controller")?"Endpoint":"Stateful";
            app.rewrite(edited,mode.equals("controller")?endpoint.replace("limit=3","limit=7"):service.replace("value=3","value=7"));
            assertTrue(app.awaits("Reloaded app."+edited+" ",45),app.tail());
            assertEquals("7",request(client,port,"/state"),app.tail());
            String[] unaffectedAfter=request(client,port,"/unaffected").split(":");
            assertEquals("2",unaffectedAfter[0],"unaffected counter survives");
            assertEquals(unaffectedBefore[1],unaffectedAfter[1],"unaffected controller identity survives");
            assertTrue(app.output().stream().anyMatch(line->line.contains("bean "+(mode.equals("controller")?"endpoint":"stateful")+" re-created")),app.tail());
            if(org.springframework.core.SpringVersion.getVersion().startsWith("7.")&&!mode.equals("controller"))
                assertTrue(app.output().stream().anyMatch(line->line.contains("1 controller handler re-resolved")),app.tail());
        }
    }
    private static String request(HttpClient client,int port,String path) throws Exception {
        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());return response.body();
    }
    private static final String ENDPOINT="""
            package app;
            @org.springframework.web.bind.annotation.RestController public class Endpoint {
                int limit; public Endpoint(){limit=3;}
                @org.springframework.beans.factory.annotation.Autowired SOURCE_TYPE source;
                @org.springframework.web.bind.annotation.GetMapping("/state") public String state(){return Integer.toString(VALUE);}
            }
            """;
    private static final String UNAFFECTED="""
            package app;
            @org.springframework.web.bind.annotation.RestController public class Unaffected {
                int count;
                @org.springframework.web.bind.annotation.GetMapping("/unaffected") public String state(){return (++count)+":"+System.identityHashCode(this);}
            }
            """;
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
