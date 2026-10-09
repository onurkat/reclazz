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
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Native servlet and provider caches are warmed before each annotation edit. */
class ValidationConstraintReloadTest {
    @TempDir Path tmp;
    String validationNamespace() { return "javax.validation"; }

    static String nativeClasspath() {
        String extra = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> {
                    String n = Path.of(p).getFileName().toString();
                    return n.startsWith("tomcat-embed-core-") || n.startsWith("hibernate-validator-")
                            || n.startsWith("validation-api-") || n.startsWith("jakarta.validation-api-")
                            || n.startsWith("jakarta.el-") || n.startsWith("jakarta.el-api-")
                            || n.startsWith("expressly-") || n.startsWith("jboss-logging-")
                            || n.startsWith("classmate-") || n.startsWith("jackson-")
                            || n.startsWith("javax.annotation-api-") || n.startsWith("jakarta.annotation-api-")
                            || n.startsWith("micrometer-");
                }).collect(Collectors.joining(File.pathSeparator));
        return WatchedApp.springClasspath() + File.pathSeparator + extra;
    }

    @ParameterizedTest(name = "warmedNativeConstraintsFollowSixSavesBeforeControllerSideEffects [{index}] {arguments}")
    @ValueSource(booleans = {false, true})
    void warmedNativeConstraintsFollowSixSavesBeforeControllerSideEffects(boolean child) throws Exception {
        var builder = WatchedApp.in(tmp).classpath(nativeClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .jvmArgs("-Dprobe.dir=" + tmp).with("App", APP)
                .with("Controller", CONTROLLER.replace("javax.validation", validationNamespace()))
                .with("Form", form(0)).with("Control", CONTROL.replace("javax.validation", validationNamespace()));
        if (child) builder.childClassLoader();
        var client = HttpClient.newHttpClient();
        try (var app = builder.start()) {
            app.awaitOrFail("PORT=", "native servlet did not start");
            app.awaitOrFail("] Watching ", "watcher missing");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "child loader missing");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            String nonce = get(client, port, "/nonce");
            checkPost(client, port, "", 1, 200);
            assertEquals("", get(client, port, "/held"), "held validator warmed before the annotation exists");
            int calls = 1;
            for (int stage = 1; stage <= 6; stage++) {
                app.rewrite("Form", form(stage));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                while (reloads(app) < stage && System.nanoTime() < until) Thread.sleep(20);
                assertEquals(stage, reloads(app), app.tail());
                assertEquals(Integer.toString(stage), get(client, port, "/version"));
                boolean constrained = stage != 3 && stage != 6;
                checkPost(client, port, "", 1, constrained ? 400 : 200);
                if (!constrained) calls++;
                checkPost(client, port, "abc", 1, stage == 2 || stage == 5 ? 400 : 200);
                if (stage != 2 && stage != 5) calls++;
                checkPost(client, port, "abcdefg", 1, 200); calls++;
                checkPost(client, port, "abcdefg", 0, 400);
                assertEquals(constrained ? "title" : "", get(client, port, "/held"), app.tail());
                assertEquals(Integer.toString(calls), get(client, port, "/calls"), "rejected body must never execute");
                assertEquals(nonce, get(client, port, "/nonce"), "server/context was restarted");
                var malformed = post(client, port, "/submit", "{not-json");
                assertEquals(400, malformed.statusCode(), malformed.body());
                assertEquals(Integer.toString(calls), get(client, port, "/calls"));
                assertEquals(400, post(client, port, "/control", "{\"quantity\":0}").statusCode());
                assertEquals(200, post(client, port, "/control", "{\"quantity\":1}").statusCode());
            }
            System.out.println("[native-validation] child=" + child + " namespace=" + validationNamespace()
                    + " six saves, MVC rejection before body, held validator, control and nonce passed");
        }
    }

    private long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Form ") || s.contains("Structural reload: app.Form ")).count();
    }
    private static String get(HttpClient client, int port, String path) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body()); return response.body();
    }
    private static HttpResponse<String> post(HttpClient client, int port, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void checkPost(HttpClient client, int port, String title, int quantity, int expected) throws Exception {
        var result = post(client, port, "/submit", "{\"title\":\"" + title + "\",\"quantity\":" + quantity + "}");
        assertEquals(expected, result.statusCode(), result.body());
        if (expected == 200) assertEquals("accepted:" + title, result.body());
    }
    private String form(int stage) {
        int min = stage == 2 ? 5 : stage == 4 ? 2 : stage == 5 ? 6 : 3;
        String constraints = stage == 0 || stage == 3 || stage == 6 ? "" : "@javax.validation.constraints.NotBlank @javax.validation.constraints.Size(min=" + min + ")";
        return ("package app; public class Form { " + constraints + " public String title; "
                + "@javax.validation.constraints.Min(1) public int quantity; public static int version() { return " + stage + "; } }")
                .replace("javax.validation", validationNamespace());
    }
    private static final String CONTROL = "package app; public class Control { @javax.validation.constraints.Min(1) public int quantity; }";
    private static final String CONTROLLER = """
            package app;
            import org.springframework.web.bind.annotation.*;
            @RestController public class Controller {
                private final javax.validation.Validator held;
                private final Form old = new Form();
                private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
                private final String nonce = java.util.UUID.randomUUID().toString();
                public Controller(org.springframework.validation.beanvalidation.LocalValidatorFactoryBean factory) {
                    held = factory.getValidator(); old.title = ""; old.quantity = 1;
                }
                @PostMapping("/submit") public String submit(@javax.validation.Valid @RequestBody Form body) {
                    calls.incrementAndGet(); return "accepted:" + body.title;
                }
                @PostMapping("/control") public String control(@javax.validation.Valid @RequestBody Control body) { return "control"; }
                @GetMapping("/held") public String held() {
                    return held.validate(old).stream().map(v -> v.getPropertyPath().toString()).distinct().sorted()
                        .collect(java.util.stream.Collectors.joining(","));
                }
                @GetMapping("/calls") public String calls() { return "" + calls.get(); }
                @GetMapping("/nonce") public String nonce() { return nonce; }
                @GetMapping("/version") public String version() { return "" + Form.version(); }
            }
            """;
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            import org.springframework.web.servlet.config.annotation.*;
            import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
            import org.springframework.web.servlet.DispatcherServlet;
            @Configuration @EnableWebMvc @ComponentScan("app")
            public class App implements WebMvcConfigurer {
                @Bean public org.springframework.validation.beanvalidation.LocalValidatorFactoryBean validator() {
                    return new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
                }
                @Override public org.springframework.validation.Validator getValidator() { return validator(); }
                public static void main(String[] args) throws Exception {
                    Path base = Files.createDirectories(Path.of(System.getProperty("probe.dir"), "tomcat"));
                    var tomcat = new org.apache.catalina.startup.Tomcat(); tomcat.setBaseDir(base.toString()); tomcat.setPort(0);
                    tomcat.getConnector().setProperty("address", "127.0.0.1");
                    var context = tomcat.addContext("", base.toString()); context.setParentClassLoader(App.class.getClassLoader());
                    var spring = new AnnotationConfigWebApplicationContext(); spring.setClassLoader(App.class.getClassLoader()); spring.register(App.class);
                    var servlet = org.apache.catalina.startup.Tomcat.addServlet(context, "mvc", new DispatcherServlet(spring));
                    servlet.setLoadOnStartup(1); context.addServletMappingDecoded("/", "mvc");
                    tomcat.start(); System.out.println("PORT=" + tomcat.getConnector().getLocalPort());
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { tomcat.stop(); tomcat.destroy(); } catch (Exception ignored) { } }));
                    Thread.sleep(180000);
                }
            }
            """;
}
