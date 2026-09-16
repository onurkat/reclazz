/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class JakartaAsyncMvcRequestBoundaryReloadTest {
    @TempDir Path tmp;
    @ParameterizedTest @CsvSource({"callable,false","callable,true","deferred,false","deferred,true"})
    void heldAsyncResponseKeepsItsCodeUntilNativeCompletion(String kind,boolean child) throws Exception {
        run(kind,child,true);
    }
    @Test void immediateModeStillReloadsWhileTheResponseIsPending() throws Exception { run("callable",false,false); }
    @Test void timedOutCallableThatIgnoresCancellationStillOwnsItsCode() throws Exception { run("timeout",false,true); }

    private void run(String kind,boolean child,boolean boundary) throws Exception {
        String server=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p->{String n=Path.of(p).getFileName().toString();return n.startsWith("tomcat-embed-core-")||n.startsWith("jakarta.annotation-api-")||n.startsWith("micrometer-");})
                .collect(Collectors.joining(File.pathSeparator));
        assertTrue(server.contains("tomcat-embed-core-"));
        var builder=WatchedApp.in(tmp).classpath(WatchedApp.springClasspath()+File.pathSeparator+server)
                .agentArgs("startupDelaySec=1,debounceMs=100"+(boundary?",reloadBoundary=request":""))
                .jvmArgs("-Dprobe.dir="+tmp).with("App",APP).with("Controller",CONTROLLER).with("Rules",rules(1));
        if(child)builder.childClassLoader();
        try(var app=builder.start();var client=HttpClient.newHttpClient()) {
            app.awaitOrFail("PORT=","Tomcat did not start"); app.awaitOrFail("] Watching ","watcher missing");
            int port=Integer.parseInt(app.latest("PORT=").substring(5));
            assertEquals("1:1",get(client,port,"fast").body());
            CompletableFuture<HttpResponse<String>> held=client.sendAsync(request(port,kind),HttpResponse.BodyHandlers.ofString());
            app.awaitOrFail("HELD=1","async work did not start");
            if(kind.equals("timeout")) assertEquals(503,held.get(15,TimeUnit.SECONDS).statusCode());
            app.rewrite("Rules",rules(2));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<until&&!app.output().stream().anyMatch(s->s.contains("Reloaded app.Rules")||s.contains("Request boundary deferred")))Thread.sleep(20);
            assertTrue(app.output().stream().anyMatch(s->s.contains("Reloaded app.Rules")||s.contains("Request boundary deferred")),app.tail());
            if(boundary) assertEquals("1:1",get(client,port,"fast").body(),app.tail());
            Files.createFile(tmp.resolve("release"));
            app.awaitOrFail("WORKER=","worker did not finish");
            assertEquals(boundary?"WORKER=1:1":"WORKER=1:2",app.latest("WORKER="),app.tail());
            if(!kind.equals("timeout")) {
                var response=held.get(15,TimeUnit.SECONDS); assertEquals(200,response.statusCode(),response.body());
                assertEquals(boundary?"1:1":"1:2",response.body(),app.tail());
            }
            app.awaitOrFail("Reloaded app.Rules","queued edit did not apply after completion");
            assertEquals("2:2",get(client,port,"fast").body(),app.tail());
            assertEquals("instant",get(client,port,"instant").body());
            System.out.println("[jakarta-async-mvc] "+kind+" child="+child+" boundary="+boundary+" "+app.latest("WORKER="));
        }
    }
    private static HttpRequest request(int port,String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/"+path)).timeout(Duration.ofSeconds(35)).GET().build();
    }
    private static HttpResponse<String> get(HttpClient client,int port,String path) throws Exception {
        return client.send(request(port,path),HttpResponse.BodyHandlers.ofString());
    }
    private static String rules(int version) {
        return "package app; public class Rules { public static int first() { return "+version+"; } public static int second() { return "+version+"; } }";
    }
    private static final String APP="""
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
    private static final String CONTROLLER="""
            package app;
            import java.nio.file.*;
            import java.util.concurrent.Callable;
            import org.springframework.web.bind.annotation.*;
            import org.springframework.web.context.request.async.*;
            @RestController public class Controller {
                @GetMapping("/fast") public String fast() { return Rules.first()+":"+Rules.second(); }
                private String work(int first) {
                    System.out.println("HELD="+first);
                    while(!Files.exists(Path.of(System.getProperty("probe.dir"),"release"))) {
                        try { Thread.sleep(10); }catch(InterruptedException ignored) { }
                    }
                    String result=first+":"+Rules.second(); System.out.println("WORKER="+result); return result;
                }
                @GetMapping("/callable") public Callable<String> callable() { return ()->work(Rules.first()); }
                @GetMapping("/timeout") public WebAsyncTask<String> timeout() { return new WebAsyncTask<>(500L,()->work(Rules.first())); }
                @GetMapping("/deferred") public DeferredResult<String> deferred() {
                    int first=Rules.first(); var result=new DeferredResult<String>(30000L);
                    new Thread(()->result.setResult(work(first))).start(); return result;
                }
                @GetMapping("/instant") public DeferredResult<String> instant() {
                    var result=new DeferredResult<String>(); result.setResult("instant"); return result;
                }
            }
            """;
}
