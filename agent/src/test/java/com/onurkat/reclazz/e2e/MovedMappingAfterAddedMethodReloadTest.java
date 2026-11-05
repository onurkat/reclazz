/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * An existing handler's path changes after an earlier save added a method to the
 * same controller: the request mapping moves to the new path.
 */
class MovedMappingAfterAddedMethodReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest(name = "pathEditMovesTheMapping [addedFirst={0}, autoCompile={1}]")
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void pathEditMovesTheMapping(boolean addedFirst, boolean autoCompile) throws Exception {
        var client = HttpClient.newHttpClient();
        var builder = WatchedApp.in(tmp).classpath(ValidationConstraintReloadTest.nativeClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true" + (autoCompile ? ",autoCompile=true" : ""))
                .jvmArgs("-Dprobe.dir=" + tmp).with("App", APP)
                .with("Controller", controller("/ping", false));
        // autoCompile: the agent compiles the saved source itself, in one reload batch.
        if (autoCompile) builder.mavenLayout();
        try (var app = builder.start()) {
            app.awaitOrFail("PORT=", "native servlet did not start");
            app.awaitOrFail("] Watching ", "watcher missing");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            int saves = 0;
            if (addedFirst) {
                save(app, autoCompile, controller("/ping", true));
                awaitReloads(app, ++saves);
                settle(client, port, "/added", 200);
            }
            save(app, autoCompile, controller("/pong", addedFirst));
            awaitReloads(app, ++saves);
            settle(client, port, "/pong", 200);
            // A later reload of the same class would re-scan with the annotations the first one deferred.
            assertEquals(saves, reloads(app), app.tail());
            assertEquals(404, request(client, port, "/ping").statusCode());
            assertEquals("pong", request(client, port, "/pong").body());
        }
    }

    private void save(WatchedApp app, boolean autoCompile, String source) throws Exception {
        if (!autoCompile) {
            app.rewrite("Controller", source);
            return;
        }
        // A polling watcher compares modification times in whole seconds; a
        // save inside the second it last looked would not be seen.
        Thread.sleep(1100);
        Files.writeString(tmp.resolve("src/main/java/app/Controller.java"), source);
    }

    private static void awaitReloads(WatchedApp app, int expected) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (reloads(app) < expected && System.nanoTime() < until) Thread.sleep(20);
        assertEquals(expected, reloads(app), app.tail());
    }

    private static void settle(HttpClient client, int port, String path, int status) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        int last;
        while ((last = request(client, port, path).statusCode()) != status && System.nanoTime() < until) Thread.sleep(25);
        assertEquals(status, last, path);
    }

    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Controller ")
                || s.contains("Structural reload: app.Controller ")).count();
    }

    private static HttpResponse<String> request(HttpClient client, int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String controller(String pingPath, boolean added) {
        return """
                package app;
                import org.springframework.web.bind.annotation.*;
                @RestController @RequestMapping("/api") public class Controller {
                    @RequestMapping(value = "%s", method = RequestMethod.GET) public String ping() { return "pong"; }
                    @GetMapping("/existing") public String existing() { return "existing"; }
                    %s
                }
                """.formatted(pingPath, added ? "@GetMapping(\"/added\") public String added() { return \"added\"; }" : "");
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
                @Bean public String nonce() { return java.util.UUID.randomUUID().toString(); }
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
