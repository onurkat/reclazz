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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AspectPointcutReloadTest {
    @TempDir Path tmp;

    @Test void factoryAspectSavesUpdateAlreadyInjectedJdkAndCglibProxies() throws Exception { exercise(false); }
    @Test void componentAspectSavesUpdateAlreadyInjectedJdkAndCglibProxies() throws Exception { exercise(true); }

    private void exercise(boolean component) throws Exception {
        String aspectj = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> Path.of(p).getFileName().toString().startsWith("aspectjweaver-"))
                .collect(Collectors.joining(File.pathSeparator));
        assertFalse(aspectj.isEmpty());
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath() + File.pathSeparator + aspectj)
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("App", APP).with("Service", SERVICE).with("Counter", COUNTER)
                .with("Holder", HOLDER).with("Edited", aspect("first", 0, component)).start()) {
            app.awaitOrFail("PORT=", "application did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            var client = HttpClient.newHttpClient();
            for (String kind : new String[]{"jdk", "cglib"}) {
                assertEquals("v0[first:1]", get(client, port, kind + "/first"));
                assertEquals("second:2", get(client, port, kind + "/second"));
            }
            String[] matches = {"second", "*", "noSuchMethod", "first"};
            int calls = 2;
            for (int stage = 1; stage <= matches.length; stage++) {
                app.rewrite("Edited", aspect(matches[stage - 1], stage, component));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                while (reloads(app) < stage && System.nanoTime() < deadline) Thread.sleep(25);
                assertEquals(stage, reloads(app), app.tail());
                for (String kind : new String[]{"jdk", "cglib"}) {
                    String first = "first:" + (calls + 1), second = "second:" + (calls + 2);
                    if (stage == 2 || stage == 4) first = "v" + stage + "[" + first + "]";
                    if (stage <= 2) second = "v" + stage + "[" + second + "]";
                    String actualFirst = get(client, port, kind + "/first");
                    String actualSecond = get(client, port, kind + "/second");
                    System.out.println("[aop-http] stage=" + stage + " " + kind + " " + actualFirst + " | " + actualSecond);
                    assertEquals(first, actualFirst, app.tail());
                    assertEquals(second, actualSecond, app.tail());
                    assertEquals("true", get(client, port, kind + "/identity"));
                    assertEquals(stage == 3 ? "1" : "2", get(client, port, kind + "/advisors"));
                    assertEquals("target failure", get(client, port, kind + "/fail"));
                }
                calls += 2;
            }
            assertFalse(app.output().stream().anyMatch(s -> s.contains("Reloaded app.Counter")
                    || s.contains("Reloaded app.Holder")), app.tail());
        }
    }

    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Edited") || s.contains("Structural reload: app.Edited")).count();
    }

    private static String get(HttpClient client, int port, String path) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/" + path))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private static String aspect(String match, int version, boolean component) {
        return """
                package app;
                @org.aspectj.lang.annotation.Aspect
                %s
                public class Edited {
                    @org.aspectj.lang.annotation.Around("execution(* app.Counter.%s(..))")
                    public Object advice(org.aspectj.lang.ProceedingJoinPoint call) throws Throwable {
                        return "v%d[" + call.proceed() + "]";
                    }
                }
                """.formatted(component ? "@org.springframework.stereotype.Component" : "", match, version);
    }
    private static final String SERVICE = """
            package app;
            public interface Service { String first(); String second(); String fail(); }
            """;
    private static final String COUNTER = """
            package app;
            public class Counter implements Service {
                private int calls;
                public String first() { return "first:" + ++calls; }
                public String second() { return "second:" + ++calls; }
                public String fail() { throw new IllegalStateException("target failure"); }
            }
            """;
    private static final String HOLDER = """
            package app;
            public class Holder {
                public final Service service;
                public Holder(Service service) { this.service = service; }
            }
            """;
    private static final String APP = """
            package app;
            import org.springframework.context.support.GenericApplicationContext;
            import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator;
            import org.springframework.aop.framework.Advised;
            public class App {
                static GenericApplicationContext context(boolean cglib) {
                    var context = new GenericApplicationContext();
                    context.registerBean("autoProxyCreator", AnnotationAwareAspectJAutoProxyCreator.class, () -> {
                        var creator = new AnnotationAwareAspectJAutoProxyCreator();
                        creator.setProxyTargetClass(cglib); return creator;
                    });
                    context.registerBean("edited", Edited.class);
                    context.registerBean("service", Counter.class);
                    context.registerBean("holder", Holder.class, () -> new Holder(context.getBean("service", Service.class)));
                    context.refresh(); return context;
                }
                public static void main(String[] args) throws Exception {
                    var jdk = context(false); var cglib = context(true);
                    Holder oldJdk = jdk.getBean(Holder.class), oldCglib = cglib.getBean(Holder.class);
                    Object jdkTarget = ((Advised) oldJdk.service).getTargetSource().getTarget();
                    Object cglibTarget = ((Advised) oldCglib.service).getTargetSource().getTarget();
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                    server.createContext("/", exchange -> {
                        String path = exchange.getRequestURI().getPath();
                        boolean isJdk = path.startsWith("/jdk/");
                        Holder old = isJdk ? oldJdk : oldCglib;
                        var context = isJdk ? jdk : cglib;
                        String result; int status = 200;
                        try {
                            if (path.endsWith("/identity")) result = Boolean.toString(old == context.getBean(Holder.class)
                                && old.service == context.getBean("service")
                                && ((Advised) old.service).getTargetSource().getTarget() == (isJdk ? jdkTarget : cglibTarget));
                            else if (path.endsWith("/advisors")) result = "" + ((Advised) old.service).getAdvisors().length;
                            else if (path.endsWith("/first")) result = old.service.first();
                            else if (path.endsWith("/second")) result = old.service.second();
                            else {
                                try { result = old.service.fail(); }
                                catch (IllegalStateException expected) { result = expected.getMessage(); }
                            }
                        } catch (Throwable failure) { failure.printStackTrace(); result = failure.toString(); status = 500; }
                        byte[] bytes = result.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(status, bytes.length);
                        exchange.getResponseBody().write(bytes); exchange.close();
                    });
                    server.start(); System.out.println("PORT=" + server.getAddress().getPort());
                    Thread.sleep(120000);
                }
            }
            """;
}
