/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Both real watcher entry paths, including terminal native and framework failures. */
class BatchedConstructorReloadTest {
    @TempDir Path dir;
    private final ObjectMapper json = new ObjectMapper();
    private int serial;

    @Test void autoCompileBatchedConstructorEditRecreatesSingletonWithUpdatedConstructor() throws Exception { exercise(true, true, "normal"); }
    @Test void classBatchConstructorEditRecreatesSingletonWithUpdatedConstructor() throws Exception { exercise(false, true, "normal"); }
    @Test void singleClassConstructorEditRecreatesSingleton() throws Exception { exercise(false, false, "normal"); }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void eachReloadEventCarriesOnlyItsOwnEffectsAfterFrameworkWork(boolean auto) throws Exception { exercise(auto, true, "effects"); }
    @Test void nativeFailureSkipsThatClassFrameworkWorkWhileSiblingCompletes() throws Exception { exercise(false, true, "nativeFailure"); }
    @Test void benignConstructorRefusalStillRunsFrameworkWork() throws Exception { exercise(false, true, "benignRefusal"); }
    @Test void deferredWorkDoesNotLeakIntoNextBatchAfterException() throws Exception { exercise(false, true, "frameworkFailure"); }

    private void exercise(boolean auto, boolean batch, String mode) throws Exception {
        Path port = dir.resolve("agent.port");
        var builder = WatchedApp.in(dir).classpath(WatchedApp.springClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=300,verbose=true,portFile=" + port + ",autoCompile=" + auto)
                .jvmArgs("-Dprobe.dir=" + dir, "-Dprobe.mode=" + mode)
                .with("Service", service("hello"))
                .with("Noise", noise("noise-one"))
                .with("Controller", "package app; public class Controller { @org.springframework.beans.factory.annotation.Autowired public Service service; }")
                .with("App", APP);
        if (auto) builder.mavenLayout();
        try (var app = builder.start()) {
            app.awaitOrFail("BEFORE=hello", "baseline singleton");
            app.awaitOrFail("] Watching 1 director", "watcher ready");
            scan(port);
            int start = app.output().size();
            if (batch) control(port, "pause");
            save(app, auto, batch ? Map.of("Service", service("constructed"), "Noise", noise("noise-two"))
                    : Map.of("Service", service("constructed")));
            if (batch) {
                scan(port);
                control(port, "resume");
            }
            if (auto) assertTrue(app.awaits("Compiled Service.java", 20), app.tail());
            else if (batch) assertTrue(app.awaits("2 class files changed together", 20), app.tail());
            String status = mode.equals("nativeFailure") ? "failed" : mode.equals("frameworkFailure") ? "unverified" : "applied";
            awaitReceipt(port, app, "Service", status);
            if (batch) awaitReceipt(port, app, "Noise", mode.equals("frameworkFailure") || mode.equals("nativeFailure") ? "unverified" : "applied");
            String observation = observe(app, 1);
            if (mode.equals("nativeFailure")) {
                assertTrue(observation.contains("current:hello,direct:hello"), observation);
                assertTrue(observation.contains("sameOriginal:true"), observation);
                assertTrue(observation.contains("noise:noise-two"), observation);
            } else if (mode.equals("benignRefusal")) {
                assertTrue(observation.contains("current:hello,direct:hello"), observation);
                assertTrue(observation.contains("sameOriginal:false"), observation);
            } else {
                assertTrue(observation.contains("current:constructed,direct:constructed"), observation);
                if (!mode.equals("frameworkFailure")) assertTrue(observation.contains("registeredController:constructed"), observation);
                if (mode.equals("effects")) {
                    assertEvent(app, start, "Service", "service", "noise");
                    assertEvent(app, start, "Noise", "noise", "service");
                }
            }
            if (mode.equals("frameworkFailure")) {
                long serviceEvents = reloadLines(app, "Service").size();
                long serviceRefreshes = app.output().stream().filter(s -> s.contains("Spring bean refreshed: service")).count();
                save(app, false, Map.of("Noise", noise("noise-three")));
                awaitReceipt(port, app, "Noise", "applied");
                assertTrue(observe(app, 2).contains("noise:noise-three"), app.tail());
                assertEquals(serviceEvents, reloadLines(app, "Service").size(), "old service work replayed into next batch");
                assertEquals(serviceRefreshes, app.output().stream().filter(s -> s.contains("Spring bean refreshed: service")).count(), app.tail());
            }
        }
    }

    private void control(Path port, String action) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(Files.readString(port).trim()))) {
            socket.setSoTimeout(5000);
            var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String token = "hold" + (++serial), prefix = "RELOAD_STATE " + token + " " + action + " ";
            socket.getOutputStream().write(("RELOAD " + action + " request=" + token + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            while (true) {
                String line = in.readLine(); assertNotNull(line);
                if (json.readTree(line).path("message").asText().startsWith(prefix)) return;
            }
        }
    }
    private void scan(Path port) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(Files.readString(port).trim()))) {
            socket.setSoTimeout(15000);
            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String token = "scanned" + (++serial), prefix = "RELOAD_STATE " + token + " status ";
            // Same connection: status is answered only after scanNow's delivery barrier returns.
            socket.getOutputStream().write(("SCAN\nRELOAD status request=" + token + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            while (true) {
                String line = input.readLine(); assertNotNull(line);
                if (json.readTree(line).path("message").asText().startsWith(prefix)) return;
            }
        }
    }

    private static List<String> reloadLines(WatchedApp app, String type) {
        return app.output().stream().filter(s -> s.contains("Reloaded app." + type + " ") || s.contains("Reloaded app." + type + ":")
                || s.contains("Structural reload: app." + type + " ")).toList();
    }
    private static void assertEvent(WatchedApp app, int start, String type, String own, String sibling) {
        List<String> lines = app.output();
        String event = reloadLines(app, type).get(0);
        assertTrue(event.contains("bean " + own + " re-created"), event);
        assertFalse(event.contains("bean " + sibling + " re-created"), event);
        int eventIndex = lines.indexOf(event);
        assertTrue(lines.subList(start, eventIndex).stream().anyMatch(s -> s.contains("Spring bean refreshed: " + own)), app.tail());
    }
    private void save(WatchedApp app, boolean auto, Map<String,String> changes) throws Exception {
        if (auto) {
            for (var e : changes.entrySet()) Files.writeString(dir.resolve("src/main/java/app/" + e.getKey() + ".java"), e.getValue());
        } else app.rewriteAll(changes);
    }
    private String observe(WatchedApp app, int stage) throws Exception {
        Files.createFile(dir.resolve("observe" + stage));
        app.awaitOrFail("OBSERVE" + stage + "=", "live singleton observation");
        return app.latest("OBSERVE" + stage + "=");
    }
    private void awaitReceipt(Path port, WatchedApp app, String type, String expected) throws Exception {
        Path bytes = app.classesDir().resolve("app/" + type + ".class");
        // On AutoCompile the COMPILE event, not an arbitrary delay, establishes these bytes exist.
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(bytes)));
        try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(Files.readString(port).trim()))) {
            socket.setSoTimeout(3000);
            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            JsonNode receipt = null;
            while (System.nanoTime() < end) {
                String token = "batch" + (++serial), prefix = "VERIFY_RESULT " + token + " ";
                socket.getOutputStream().write(("VERIFY " + token + " app." + type + " " + hash + "\n").getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                String message;
                do { String line = input.readLine(); assertNotNull(line, app.tail()); message = json.readTree(line).path("message").asText(); }
                while (!message.startsWith(prefix) && System.nanoTime() < end);
                assertTrue(message.startsWith(prefix), app.tail());
                receipt = json.readTree(message.substring(prefix.length()));
                if (List.of("applied", "unverified", "failed").contains(receipt.path("status").asText())) break;
                Thread.sleep(50);
            }
            assertNotNull(receipt);
            assertEquals(expected, receipt.path("status").asText(), receipt + "\n" + app.tail());
            assertEquals(hash, receipt.path("observedSha256").asText());
            assertFalse(receipt.path("completedAt").asText().isBlank());
        }
    }
    private static String service(String value) {
        return "package app;\n@org.springframework.stereotype.Service public class Service { private String greeting=\"" + value
                + "\"; public String getGreeting() { return greeting; } }";
    }
    private static String noise(String value) {
        return "package app;\n@org.springframework.stereotype.Service public class Noise { public String value() { return \"" + value + "\"; } }";
    }
    private static final String APP = """
        package app;
        import java.nio.file.*;
        import java.lang.reflect.*;
        import java.lang.instrument.*;
        import org.springframework.context.annotation.AnnotationConfigApplicationContext;
        public class App {
            static boolean thrown;
            public static void main(String[] args) throws Exception {
                String mode = System.getProperty("probe.mode");
                Class<?> agent = Class.forName("com.onurkat.reclazz.agent.ReclazzAgent");
                if (mode.equals("nativeFailure") || mode.equals("benignRefusal")) {
                    Field f = agent.getDeclaredField("structuralReloader"); f.setAccessible(true); Object reloader = f.get(null);
                    Field inst = reloader.getClass().getDeclaredField("instrumentation"); inst.setAccessible(true);
                    Instrumentation real = (Instrumentation) inst.get(reloader);
                    Instrumentation proxy = (Instrumentation) Proxy.newProxyInstance(App.class.getClassLoader(), new Class<?>[]{Instrumentation.class}, (p,m,a) -> {
                        if (m.getName().equals("redefineClasses")) {
                            for (ClassDefinition definition : (ClassDefinition[]) a[0]) if (definition.getDefinitionClass().getName().equals("app.Service")) {
                                if (mode.equals("benignRefusal")) throw new UnsupportedOperationException("test constructor refusal");
                                throw new ClassFormatError("test native terminal failure");
                            }
                        }
                        try { return m.invoke(real,a); } catch (InvocationTargetException e) { throw e.getCause(); }
                    });
                    inst.set(reloader, proxy);
                }
                if (mode.equals("frameworkFailure")) {
                    Class<?> reporter = Class.forName("com.onurkat.reclazz.ui.StatusReporter");
                    Class<?> listener = Class.forName("com.onurkat.reclazz.ui.StatusReporter$StatusListener");
                    Object observer = Proxy.newProxyInstance(App.class.getClassLoader(), new Class<?>[]{listener}, (p,m,a) -> {
                        if (m.getName().equals("onEvent") && !thrown && String.valueOf(a[1]).equals("Spring bean refreshed: service")) {
                            thrown = true; throw new AssertionError("test Phase B failure");
                        }
                        if (m.getName().equals("equals")) return p == a[0];
                        if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                        return null;
                    });
                    reporter.getMethod("addListener", listener).invoke(null, observer);
                }
                try (var context = new AnnotationConfigApplicationContext(Service.class, Noise.class, Controller.class)) {
                    Service before = context.getBean(Service.class);
                    System.out.println("BEFORE=" + before.getGreeting());
                    for (int stage=1; stage<=2; stage++) {
                        while (!Files.exists(Path.of(System.getProperty("probe.dir"), "observe" + stage))) Thread.sleep(10);
                        Service after = context.getBean(Service.class);
                        System.out.println("OBSERVE" + stage + "=current:" + after.getGreeting() + ",direct:" + new Service().getGreeting()
                            + ",sameOriginal:" + (after == before) + ",registeredController:" + context.getBean(Controller.class).service.getGreeting()
                            + ",noise:" + context.getBean(Noise.class).value());
                    }
                }
            }
        }
        """;
}
