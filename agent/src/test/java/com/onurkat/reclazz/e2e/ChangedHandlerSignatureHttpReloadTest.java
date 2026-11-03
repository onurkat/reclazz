/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A handler the application started with gets a different parameter list and
 * keeps its mapping. The request has to reach the new method; the one it
 * replaced is gone from the source.
 */
class ChangedHandlerSignatureHttpReloadTest {
    @TempDir Path tmp;

    @Test
    void aStartupHandlerWithANewParameterListServesTheNewBody() throws Exception {
        var client = HttpClient.newHttpClient();
        try (var app = WatchedApp.in(tmp).classpath(ValidationConstraintReloadTest.nativeClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .jvmArgs("-Dprobe.dir=" + tmp).with("App", APP).with("Controller", controller(0)).start()) {
            app.awaitOrFail("PORT=", "native servlet did not start");
            app.awaitOrFail("] Watching ", "watcher missing");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            assertEquals("v0", get(client, port, "/probe?mode=x"));

            for (int stage = 1; stage <= 3; stage++) {
                app.rewrite("Controller", controller(stage));
                String expected = stage == 1 ? "v1:x" : stage == 2 ? "v2:x:7" : "v3";
                long settled = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                String body = null;
                while (System.nanoTime() < settled) {
                    body = get(client, port, "/probe?mode=x&n=7");
                    if (expected.equals(body)) break;
                    Thread.sleep(50);
                }
                assertEquals(expected, body, "stage " + stage + "\n" + app.tail());
                assertEquals("other", get(client, port, "/other"));
            }
        }
    }

    private static String get(HttpClient client, int port, String path) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 ? response.body() : response.statusCode() + ":" + response.body();
    }

    /** 0: no parameters; 1: one; 2: two; 3: back to none. */
    private static String controller(int stage) {
        String handler = switch (stage) {
            case 0 -> "public String probe() { return \"v0\"; }";
            case 1 -> "public String probe(@RequestParam(\"mode\") String mode) { return \"v1:\" + mode; }";
            case 2 -> "public String probe(@RequestParam(\"mode\") String mode, @RequestParam(\"n\") int n) { return \"v2:\" + mode + \":\" + n; }";
            default -> "public String probe() { return \"v3\"; }";
        };
        return """
                package app;
                import org.springframework.web.bind.annotation.*;
                @RestController @RequestMapping("/api") public class Controller {
                    @RequestMapping(value = "/probe", method = RequestMethod.GET) %s
                    @GetMapping("/other") public String other() { return "other"; }
                }
                """.formatted(handler);
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
                    servlet.setLoadOnStartup(1); context.addServletMappingDecoded("/","mvc");
                    tomcat.start(); System.out.println("PORT="+tomcat.getConnector().getLocalPort());
                    Runtime.getRuntime().addShutdownHook(new Thread(()->{try { tomcat.stop(); tomcat.destroy(); }catch(Exception ignored){} }));
                    Thread.sleep(120000);
                }
            }
            """;
}
