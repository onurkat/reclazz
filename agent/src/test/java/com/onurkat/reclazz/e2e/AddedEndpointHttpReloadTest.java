/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real servlet requests, including original routes and unchanged context identity. */
class AddedEndpointHttpReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest(name = "getAndPostEndpointsFollowFiveSavesWithoutRestart [{index}] {arguments}") @ValueSource(booleans = {false, true})
    void getAndPostEndpointsFollowFiveSavesWithoutRestart(boolean child) throws Exception {
        var builder = WatchedApp.in(tmp).classpath(ValidationConstraintReloadTest.nativeClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .jvmArgs("-Dprobe.dir=" + tmp).with("App", APP).with("Controller", controller(0));
        if (child) builder.childClassLoader();
        var client = HttpClient.newHttpClient();
        try (var app = builder.start()) {
            app.awaitOrFail("PORT=", "native servlet did not start");
            app.awaitOrFail("] Watching ", "watcher missing");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "child loader missing");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            String nonce = request(client, port, "/nonce", null).body();
            assertEquals(200, request(client, port, "/nonce", null).statusCode());
            check(client, port, "/added?name=x", null, 404, null);
            check(client, port, "/submit", "x", 404, null);
            for (int stage = 1; stage <= 5; stage++) {
                app.rewrite("Controller", controller(stage));
                int expected = stage;
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                while (reloads(app) < expected && System.nanoTime() < until) Thread.sleep(20);
                assertEquals(expected, reloads(app), app.tail());
                boolean active = stage != 3 && stage != 5;
                long settled = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                while (true) {
                    try {
                        check(client, port, "/added?name=x", null, active ? 200 : 404, active ? "get" + stage + ":x" : null);
                        check(client, port, "/submit", "x", active ? 200 : 404, active ? "post" + stage + ":x" : null);
                        check(client, port, "/submit", null, active ? 405 : 404, null);
                        check(client, port, "/existing", null, 200, "existing");
                        check(client, port, "/nonce", null, 200, nonce);
                        break;
                    } catch (AssertionError notSettled) {
                        if (System.nanoTime() >= settled) throw notSettled;
                        Thread.sleep(25);
                    }
                }
            }
        }
    }
    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Controller ")
                || s.contains("Structural reload: app.Controller ")).count();
    }
    private static HttpResponse<String> request(HttpClient client, int port, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
                .timeout(Duration.ofSeconds(10));
        if (body == null) request.GET();
        else request.header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void check(HttpClient client, int port, String path, String body, int status, String expected) throws Exception {
        var response = request(client, port, path, body);
        assertEquals(status, response.statusCode(), response.body());
        if (expected != null) assertEquals(expected, response.body());
    }
    private static String controller(int stage) {
        String methods = stage == 0 || stage == 3 ? "" : ("""
                %s public String added(@RequestParam("name") String name) { return "get%d:" + name; }
                %s public String submit(@RequestBody String value) { return "post%d:" + value; }
                """).formatted(stage == 5 ? "" : "@GetMapping(\"/added\")", stage,
                        stage == 5 ? "" : "@PostMapping(\"/submit\")", stage);
        return """
                package app;
                import org.springframework.web.bind.annotation.*;
                @RestController @RequestMapping("/api") public class Controller {
                    @org.springframework.beans.factory.annotation.Autowired org.springframework.context.ApplicationContext context;
                    @GetMapping("/nonce") public String nonce() { return context.getBean("nonce", String.class); }
                    @GetMapping("/existing") public String existing() { return "existing"; }
                    %s
                }
                """.formatted(methods);
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
