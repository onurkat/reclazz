/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AddedMvcBindingReloadTest {
    @TempDir Path tmp;
    @Test void localBindingAndModelMethodsFollowFiveSavesOnOldAndNewEndpoints() throws Exception { exercise(false); }
    @Test void adviceBindingAndModelMethodsFollowFiveSavesAndRespectSelectors() throws Exception { exercise(true); }

    @Test void localBindingAndModelMethodsWorkAcrossChildLoaderSaves() throws Exception { exercise(false, true); }
    @Test void adviceBindingAndModelMethodsWorkAcrossChildLoaderSaves() throws Exception { exercise(true, true); }

    private void exercise(boolean advice) throws Exception { exercise(advice, false); }

    private void exercise(boolean advice, boolean childLoader) throws Exception {
        String servlet = Path.of(javax.servlet.Servlet.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath() + File.pathSeparator + servlet);
        if (childLoader) builder.childClassLoader();
        try (var app = builder
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Api", api(advice, 0)).with("Advice", advice(0)).with("Other", OTHER)
                .with("Editor", EDITOR).with("Global", GLOBAL).with("App", APP).start()) {
            app.awaitOrFail("PORT=", "server did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            if (childLoader) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "fixture did not cross a module boundary");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            var client = HttpClient.newHttpClient();
            check(client, port, "/value?amount=7", "21:null:global:existing");
            for (int stage = 1; stage <= 5; stage++) {
                String name = advice ? "Advice" : "Api";
                if (advice && stage == 1) app.rewriteAll(java.util.Map.of("Advice", advice(stage), "Api", api(true, stage)));
                else app.rewrite(name, advice ? advice(stage) : api(false, stage));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                int expected = stage;
                while (reloads(app, name) < expected && System.nanoTime() < until) Thread.sleep(25);
                assertEquals(expected, reloads(app, name), app.tail());
                if (advice && stage == 1) {
                    while (reloads(app, "Api") < 1 && System.nanoTime() < until) Thread.sleep(25);
                    assertEquals(1, reloads(app, "Api"), app.tail());
                }
                boolean active = stage != 3 && stage != 5;
                String tag = active ? (advice ? "advice" : "local") + stage + ":global" : "null";
                String result = (active ? 7 * (stage + 1) : 21) + ":" + tag + ":global:existing";
                check(client, port, "/value?amount=7", result);
                check(client, port, "/added?amount=7", result);
                check(client, port, "/plain?other=7", "7:" + tag + ":global:existing");
                check(client, port, "/other?amount=7", "21:null:global");
            }
            assertFalse(app.output().stream().anyMatch(s -> s.contains("@InitBinder is found by scanning")
                    || s.contains("@ModelAttribute is found by scanning")), app.tail());
            System.out.println("[added-mvc-binding] " + (advice ? "advice" : "local")
                    + " add/edit/remove/private-restore/unannotate=passed old/new endpoints, named binder, selector, existing model=preserved");
        }
    }
    private static long reloads(WatchedApp app, String name) {
        return app.output().stream().filter(s -> s.contains("Reloaded app." + name + " ")
                || s.contains("Structural reload: app." + name + " ")).count();
    }
    private static void check(HttpClient client, int port, String path, String body) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(body, response.body());
    }
    private static String methods(int stage) {
        if (stage == 0 || stage == 3) return "";
        return """
                %s
                %s void bind(org.springframework.web.bind.WebDataBinder binder) {
                    binder.registerCustomEditor(Integer.class, new Editor(%d));
                }
                %s
                %s String tag(@ModelAttribute("base") String base) { return prefix + "%d:" + base; }
                """.formatted(stage == 5 ? "" : "@InitBinder(\"amount\")", stage == 4 ? "private" : "public", stage + 1,
                stage == 5 ? "" : "@ModelAttribute(\"tag\")", stage == 4 ? "private" : "public", stage);
    }
    private static String api(boolean advice, int stage) {
        return """
                package app;
                import org.springframework.web.bind.annotation.*;
                @RestController public class Api {
                    private final String prefix = new String("local");
                    @ModelAttribute("base") public String base() { return "local-skipped"; }
                    @ModelAttribute("existing") public String existing() { return "existing"; }
                    @GetMapping("/value") public String value(@RequestParam("amount") Integer amount, org.springframework.ui.Model model) {
                        return amount + ":" + model.getAttribute("tag") + ":" + model.getAttribute("base") + ":" + model.getAttribute("existing");
                    }
                    @GetMapping("/plain") public String plain(@RequestParam("other") Integer amount, org.springframework.ui.Model model) {
                        return value(amount, model);
                    }
                    %s
                    %s
                }
                """.formatted(advice ? "" : methods(stage), stage == 0 ? "" : """
                    @GetMapping("/added") public String addedEndpoint(@RequestParam("amount") Integer amount, org.springframework.ui.Model model) {
                        return value(amount, model);
                    }
                    """);
    }
    private static String advice(int stage) {
        return """
                package app;
                import org.springframework.web.bind.annotation.*;
                @ControllerAdvice(assignableTypes=Api.class)
                @org.springframework.core.annotation.Order(-10)
                public class Advice {
                    private final String prefix = new String("advice");
                    public String marker() { return "ready"; }
                    %s
                }
                """.formatted(methods(stage));
    }
    private static final String EDITOR = """
            package app;
            public class Editor extends org.springframework.beans.propertyeditors.CustomNumberEditor {
                private final int factor;
                public Editor(int factor) { super(Integer.class, false); this.factor = factor; }
                @Override public void setAsText(String text) { setValue(Integer.valueOf(text) * factor); }
            }
            """;
    private static final String GLOBAL = """
            package app;
            @org.springframework.web.bind.annotation.ControllerAdvice
            @org.springframework.core.annotation.Order(-20)
            public class Global {
                @org.springframework.web.bind.annotation.InitBinder("amount")
                public void bind(org.springframework.web.bind.WebDataBinder binder) {
                    binder.registerCustomEditor(Integer.class, new Editor(3));
                }
                @org.springframework.web.bind.annotation.ModelAttribute("base") public String base() { return "global"; }
            }
            """;
    private static final String OTHER = """
            package app;
            import org.springframework.web.bind.annotation.*;
            @RestController public class Other {
                @GetMapping("/other") public String value(@RequestParam("amount") Integer amount, org.springframework.ui.Model model) {
                    return amount + ":" + model.getAttribute("tag") + ":" + model.getAttribute("base");
                }
            }
            """;
    private static final String APP = """
            package app;
            @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
            @org.springframework.web.servlet.config.annotation.EnableWebMvc
            @org.springframework.context.annotation.ComponentScan("app")
            public class App {
                public static void main(String[] args) throws Exception {
                    var context = new org.springframework.web.context.support.AnnotationConfigWebApplicationContext();
                    context.setServletContext(new org.springframework.mock.web.MockServletContext());
                    context.register(App.class); context.refresh();
                    var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).build();
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                    server.createContext("/", exchange -> {
                        int status; byte[] body;
                        try {
                            var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                    exchange.getRequestURI().toString())).andReturn().getResponse();
                            status = response.getStatus(); body = response.getContentAsByteArray();
                        } catch (Throwable failure) {
                            status = 500; body = failure.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        }
                        exchange.sendResponseHeaders(status, body.length);
                        exchange.getResponseBody().write(body); exchange.close();
                    });
                    server.start(); System.out.println("PORT=" + server.getAddress().getPort());
                }
            }
            """;
}
