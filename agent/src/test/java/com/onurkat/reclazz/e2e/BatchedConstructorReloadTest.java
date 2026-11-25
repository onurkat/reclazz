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
    @Test void constructorRefusalReportsUnverifiedButStillRunsFrameworkWork() throws Exception { exercise(false, true, "constructorRefusal"); }
    @Test void deferredWorkDoesNotLeakIntoNextBatchAfterException() throws Exception { exercise(false, true, "frameworkFailure"); }

    @Test void unchangedConstructorRefusalStaysAppliedWithoutAConstructorWarning() throws Exception { exercise(false,true,"unchangedConstructorRefusal"); }

    @Test void annotationRefusalIsNotHiddenByAnUnchangedConstructor() throws Exception { exercise(false,true,"annotationRefusal"); }

    @Test void refusedStaticInitializerEditHasItsOwnWarningAndUnverifiedReceipt() throws Exception {
        Path port=dir.resolve("agent.port");
        try(var app=WatchedApp.in(dir).classpath(WatchedApp.springClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=300,verbose=true,portFile="+port+",autoCompile=false")
                .jvmArgs("-Dprobe.dir="+dir,"-Dprobe.mode=naturalStaticRefusal")
                .with("Service",staticService(1,false)).with("Noise",noise("noise-one",false))
                .with("Controller","package app; public class Controller {@org.springframework.beans.factory.annotation.Autowired public Service service;}")
                .with("App",APP).start()) {
            try {
                app.awaitOrFail("BEFORE=hello:1","initial static value");app.awaitWatcherReady();scan(port);
                control(port,"pause");save(app,false,Map.of("Service",staticService(2,true),"Noise",noise("noise-two",false)));scan(port);control(port,"resume");
                awaitReceipt(port,app,"Service","unverified");awaitReceipt(port,app,"Noise","unverified");
                String observation=observe(app,1);assertTrue(observation.contains("current:hello:1,direct:hello:1"),observation);
                assertTrue(observation.contains("noise:noise-two"),observation);
                assertTrue(app.output().stream().anyMatch(line->line.contains("redefine refusal for app.Service") && line.contains("Nest")),app.tail());
                assertTrue(app.output().stream().anyMatch(line->line.contains("Static initializer not refreshed for app.Service")),app.tail());
                assertFalse(app.output().stream().anyMatch(line->line.contains("Constructor bodies not refreshed for app.Service")),app.tail());
                app.awaitOrFail("LEDGER1=","static-initializer restart concern");assertTrue(app.latest("LEDGER1=").contains("static initializer"),app.tail());
                save(app,false,Map.of("Noise",noise("noise-three",false)));awaitReceipt(port,app,"Noise","applied");
                assertTrue(observe(app,2).contains("noise:noise-three"),app.tail());
            } finally {
                Path evidence=Path.of("build","constructor-receipt-evidence","naturalStaticRefusal");Files.createDirectories(evidence);Files.write(evidence.resolve("child.log"),app.output());
            }
        }
    }
    private static String staticService(int rate,boolean nested) {
        return service("hello").replace("public class Service {","public class Service { public static final java.util.Map<String,Integer> RATES=java.util.Map.of(\"rate\","+rate+");"+(nested?"static class Added {}":""))
                .replace("return greeting;","return greeting+\":\"+RATES.get(\"rate\");");
    }
    @Test void successfulConstructorAdmissionUpdatesTheBaselineBeforeAnUnchangedRefusal() throws Exception {exercise(false,true,"successThenRefusal");}

    @Test void successfulInitializerAdmissionUpdatesTheBaselineBeforeAnUnchangedRefusal() throws Exception {
        Path port=dir.resolve("agent.port");
        try(var app=WatchedApp.in(dir).classpath(WatchedApp.springClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=300,verbose=true,resetStatics=true,portFile="+port+",autoCompile=false")
                .jvmArgs("-Dprobe.dir="+dir,"-Dprobe.mode=initializerSuccessThenRefusal")
                .with("Service",scalarStaticService(1,false)).with("Noise",noise("noise-one",false))
                .with("Controller","package app; public class Controller {@org.springframework.beans.factory.annotation.Autowired public Service service;}")
                .with("App",APP).start()) {
            try {
                app.awaitOrFail("BEFORE=hello:1","initial scalar value");app.awaitWatcherReady();scan(port);
                save(app,false,Map.of("Service",scalarStaticService(2,false)));awaitReceipt(port,app,"Service","applied");
                assertTrue(observe(app,1).contains("current:hello:2,direct:hello:2"),app.tail());
                assertTrue(app.output().stream().anyMatch(line->line.contains("Reset static app.Service.RATE")),app.tail());
                assertFalse(app.output().stream().anyMatch(line->line.contains("falling back to class redefinition")),app.tail());
                save(app,false,Map.of("Service",scalarStaticService(2,true)));awaitReceipt(port,app,"Service","applied");
                assertTrue(observe(app,2).contains("current:hello:2-method,direct:hello:2-method"),app.tail());
                assertTrue(app.output().stream().anyMatch(line->line.contains("redefine refusal for app.Service") && line.contains("test refusal after native success")),app.tail());
                assertFalse(app.output().stream().anyMatch(line->line.contains("Static initializer not refreshed for app.Service")),app.tail());
                app.awaitOrFail("LEDGER2=","absence of stale initializer concern");assertFalse(app.latest("LEDGER2=").contains("static initializer"),app.tail());
                long events=reloadLines(app,"Service").size();save(app,false,Map.of("Noise",noise("noise-three",false)));awaitReceipt(port,app,"Noise","applied");
                assertTrue(observe(app,3).contains("noise:noise-three"),app.tail());assertEquals(events,reloadLines(app,"Service").size());
            } finally {
                Path evidence=Path.of("build","constructor-receipt-evidence","initializerSuccessThenRefusal");Files.createDirectories(evidence);Files.write(evidence.resolve("child.log"),app.output());
            }
        }
    }
    private static String scalarStaticService(int rate,boolean methodEdit) {
        return service("hello").replace("public class Service {","public class Service { public static int RATE="+rate+";")
                .replace("return greeting;","return greeting+\":\"+RATE"+(methodEdit?"+\"-method\"":"")+";");
    }

    @Test void pinnedFallbackKeepsRawAdmissionProofForTheNextRefusal() throws Exception {
        Path port=dir.resolve("agent.port");
        try(var app=WatchedApp.in(dir).classpath(WatchedApp.springClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=300,verbose=true,portFile="+port+",autoCompile=false")
                .jvmArgs("-Dprobe.dir="+dir)
                .with("BaseA","package app; public class BaseA {public int onlyA(){return 1;}}")
                .with("BaseB","package app; public class BaseB {public int onlyB(){return 2;}}")
                .with("Service",pinnedService("BaseA","hello",false)).with("App",PINNED_APP).start()) {
            try {
                app.awaitOrFail("BEFORE=hello","raw definition ready");app.awaitWatcherReady();scan(port);
                save(app,false,Map.of("Service",pinnedService("BaseB","constructed",false)));awaitReceipt(port,app,"Service","unverified");
                Files.writeString(dir.resolve("observe1"),"");app.awaitOrFail("DIRECT1=constructed","pinned transformed fallback admitted the constructor");
                app.awaitOrFail("PIN1=1","the entangled body actually retained its previous superclass call");
                assertTrue(app.output().stream().anyMatch(line->line.contains("test pinned companion failure")&&line.contains("falling back to class redefinition")),app.tail());
                int start=app.output().size();
                save(app,false,Map.of("Service",pinnedService("BaseA","constructed",true)));awaitReceipt(port,app,"Service","applied");
                Files.writeString(dir.resolve("observe2"),"");app.awaitOrFail("DIRECT2=constructed-method","unchanged constructors survive the next native refusal");
                assertFalse(app.output().subList(start,app.output().size()).stream().anyMatch(line->line.contains("Constructor bodies not refreshed")||line.contains("Static initializer not refreshed")),app.tail());
            } finally {
                Path evidence=Path.of("build","constructor-receipt-evidence","pinnedFallback");Files.createDirectories(evidence);Files.write(evidence.resolve("child.log"),app.output());
            }
        }
    }
    private static String pinnedService(String parent,String greeting,boolean methodEdit) {
        return "package app; public class Service extends "+parent+" { private String greeting=\""+greeting+"\"; public String getGreeting(){return greeting"+(methodEdit?"+\"-method\"":"")+";} public int entangled(){return super."+(parent.equals("BaseA")?"onlyA":"onlyB")+"();}}";
    }

    private void exercise(boolean auto, boolean batch, String mode) throws Exception {
        Path port = dir.resolve("agent.port");
        var builder = WatchedApp.in(dir).classpath(WatchedApp.springClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=300,verbose=true,portFile=" + port + ",autoCompile=" + auto)
                .jvmArgs("-Dprobe.dir=" + dir, "-Dprobe.mode=" + mode)
                .with("Service", service("hello"))
                .with("Noise", noise("noise-one", mode.equals("effects")))
                .with("Controller", "package app; public class Controller { @org.springframework.beans.factory.annotation.Autowired public Service service; }")
                .with("App", APP);
        if (auto) builder.mavenLayout();
        try (var app = builder.start()) {
            try {
            app.awaitOrFail("BEFORE=hello", "baseline singleton");
            app.awaitWatcherReady();
            scan(port);
            int start = app.output().size();
            if (batch) control(port, "pause");
            String nextService=mode.equals("unchangedConstructorRefusal")?service("hello").replace("return greeting;","return greeting+\"-method\";")
                    :mode.equals("annotationRefusal")?service("hello").replace("@org.springframework","@Deprecated @org.springframework"):service("constructed");
            save(app, auto, batch ? Map.of("Service", nextService, "Noise", noise("noise-two", mode.equals("effects")))
                    : Map.of("Service", nextService));
            if (batch) {
                scan(port);
                control(port, "resume");
            }
            if (auto) assertTrue(app.awaits("Compiled Service.java", 20), app.tail());
            else if (batch) assertTrue(app.awaits("2 class files changed together", 20), app.tail());
            String status = mode.equals("nativeFailure") ? "failed" : mode.equals("frameworkFailure") || mode.equals("constructorRefusal") || mode.equals("annotationRefusal") ? "unverified" : "applied";
            awaitReceipt(port, app, "Service", status);
            if (batch) awaitReceipt(port, app, "Noise", mode.equals("frameworkFailure") || mode.equals("nativeFailure") || mode.equals("constructorRefusal") || mode.equals("annotationRefusal") ? "unverified" : "applied");
            String observation = observe(app, 1);
            if (mode.equals("nativeFailure")) {
                assertTrue(observation.contains("current:hello,direct:hello"), observation);
                assertTrue(observation.contains("sameOriginal:true"), observation);
                assertTrue(observation.contains("noise:noise-two"), observation);
            } else if (mode.equals("constructorRefusal")) {
                assertTrue(observation.contains("current:hello,direct:hello"), observation);
                assertTrue(observation.contains("sameOriginal:false"), observation);
                assertTrue(observation.contains("noise:noise-two"), observation);
                assertTrue(app.output().stream().anyMatch(line->line.contains("Spring bean refreshed: service")),app.tail());
                app.awaitOrFail("LEDGER1=","restart note is observable");
                assertTrue(app.latest("LEDGER1=").contains("a constructor the JVM would not redefine"),app.tail());
                assertTrue(app.output().stream().anyMatch(line->line.contains("Constructor bodies not refreshed for app.Service")),app.tail());
            } else if(mode.equals("annotationRefusal")) {
                assertTrue(observation.contains("current:hello,direct:hello"),observation);
                app.awaitOrFail("LEDGER1=","annotation refusal restart concern is observable");
                assertTrue(app.latest("LEDGER1=").contains("annotation metadata"),app.tail());
                assertFalse(app.output().stream().anyMatch(line->line.contains("Constructor bodies not refreshed for app.Service")),app.tail());
            } else if(mode.equals("unchangedConstructorRefusal")) {
                assertTrue(observation.contains("current:hello-method,direct:hello-method"),observation);
                assertTrue(observation.contains("sameOriginal:true"),observation);
                assertTrue(observation.contains("noise:noise-two"),observation);
                app.awaitOrFail("LEDGER1=","absence of constructor concern is observable");
                assertFalse(app.latest("LEDGER1=").contains("a constructor the JVM would not redefine"),app.tail());
                assertFalse(app.output().stream().anyMatch(line->line.contains("Constructor bodies not refreshed for app.Service")),app.tail());
            } else {
                assertTrue(observation.contains("current:constructed,direct:constructed"), observation);
                if (!mode.equals("frameworkFailure")) assertTrue(observation.contains("registeredController:constructed"), observation);
                if (mode.equals("effects")) {
                    assertEvent(app, start, "Service", "service", "noise");
                    assertEvent(app, start, "Noise", "noise", "service");
                }
            }
            int nextStage=2;
            if(mode.equals("successThenRefusal")) {
                assertFalse(app.output().stream().anyMatch(line->line.contains("falling back to class redefinition")),app.tail());
                save(app,false,Map.of("Service",service("constructed").replace("return greeting;","return greeting+\"-method\";")));
                awaitReceipt(port,app,"Service","applied");
                assertTrue(observe(app,2).contains("current:constructed-method,direct:constructed-method"),app.tail());
                assertFalse(app.output().stream().anyMatch(line->line.contains("Constructor bodies not refreshed for app.Service")),app.tail());
                app.awaitOrFail("LEDGER2=","no stale constructor restart concern");assertFalse(app.latest("LEDGER2=").contains("a constructor the JVM would not redefine"),app.tail());
                nextStage=3;
            }
            if(mode.equals("constructorRefusal")) {
                save(app,false,Map.of("Service",service("constructed").replace("return greeting;","return greeting+\"-method\";")));
                awaitReceipt(port,app,"Service","unverified");
                assertTrue(observe(app,2).contains("current:hello-method,direct:hello-method"),app.tail());
                assertEquals(2,app.output().stream().filter(line->line.contains("Constructor bodies not refreshed for app.Service")).count(),app.tail());
                nextStage=3;
            }
            if (mode.equals("frameworkFailure") || mode.equals("constructorRefusal") || mode.equals("unchangedConstructorRefusal") || mode.equals("annotationRefusal") || mode.equals("successThenRefusal") || mode.equals("initializerSuccessThenRefusal")) {
                long serviceEvents = reloadLines(app, "Service").size();
                long serviceRefreshes = app.output().stream().filter(s -> s.contains("Spring bean refreshed: service")).count();
                save(app, false, Map.of("Noise", noise("noise-three", mode.equals("effects"))));
                awaitReceipt(port, app, "Noise", "applied");
                assertTrue(observe(app, nextStage).contains("noise:noise-three"), app.tail());
                assertEquals(serviceEvents, reloadLines(app, "Service").size(), "old service work replayed into next batch");
                assertEquals(serviceRefreshes, app.output().stream().filter(s -> s.contains("Spring bean refreshed: service")).count(), app.tail());
            }
            } finally {
                Path evidence=Path.of(System.getProperty("reclazz.constructorEvidence",Path.of("build","constructor-receipt-evidence").toString())).resolve(mode+"-auto"+auto+"-batch"+batch);
                Files.createDirectories(evidence);Files.write(evidence.resolve("child.log"),app.output());
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
    private static String noise(String value, boolean recreationEffects) {
        if (!recreationEffects) return "package app;\n@org.springframework.stereotype.Service public class Noise { public String value() { return \"" + value + "\"; } }";
        // This receipt-isolation scenario needs two genuine recreation owners.
        // A body-only sibling is now deliberately retained by the keep policy.
        return "package app;\n@org.springframework.stereotype.Service public class Noise { private String value=\"" + value
                + "\"; public String value() { return value; } }";
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
                if (mode.equals("nativeFailure") || mode.equals("constructorRefusal") || mode.equals("unchangedConstructorRefusal") || mode.equals("annotationRefusal") || mode.equals("successThenRefusal") || mode.equals("initializerSuccessThenRefusal")) {
                    Field f = agent.getDeclaredField("structuralReloader"); f.setAccessible(true); Object reloader = f.get(null);
                    Field inst = reloader.getClass().getDeclaredField("instrumentation"); inst.setAccessible(true);
                    Instrumentation real = (Instrumentation) inst.get(reloader);
                    var serviceAttempts=new java.util.concurrent.atomic.AtomicInteger();
                    Instrumentation proxy = (Instrumentation) Proxy.newProxyInstance(App.class.getClassLoader(), new Class<?>[]{Instrumentation.class}, (p,m,a) -> {
                        if (m.getName().equals("redefineClasses")) {
                            for (ClassDefinition definition : (ClassDefinition[]) a[0]) if (definition.getDefinitionClass().getName().equals("app.Service")) {
                                if(mode.equals("successThenRefusal") || mode.equals("initializerSuccessThenRefusal")) {
                                    if(serviceAttempts.incrementAndGet()>1)throw new UnsupportedOperationException("test refusal after native success");
                                } else if (mode.equals("constructorRefusal") || mode.equals("unchangedConstructorRefusal") || mode.equals("annotationRefusal")) throw new UnsupportedOperationException("test constructor refusal");
                                else throw new ClassFormatError("test native terminal failure");
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
                    for (int stage=1; stage<=3; stage++) {
                        while (!Files.exists(Path.of(System.getProperty("probe.dir"), "observe" + stage))) Thread.sleep(10);
                        Service after = context.getBean(Service.class);
                        System.out.println("OBSERVE" + stage + "=current:" + after.getGreeting() + ",direct:" + new Service().getGreeting()
                            + ",sameOriginal:" + (after == before) + ",registeredController:" + context.getBean(Controller.class).service.getGreeting()
                            + ",noise:" + context.getBean(Noise.class).value());
                        System.out.println("LEDGER"+stage+"="+Class.forName("com.onurkat.reclazz.ui.RestartLedger").getMethod("digest").invoke(null));
                    }
                }
            }
        }
        """;
    private static final String PINNED_APP="""
        package app;
        import java.lang.instrument.*;
        import java.lang.reflect.*;
        import java.nio.file.*;
        public class App {
            private static final class FailOnceVersions extends java.util.concurrent.ConcurrentHashMap<String,Integer> {
                private boolean fail=true;
                @Override public Integer merge(String key,Integer value,java.util.function.BiFunction<? super Integer,? super Integer,? extends Integer> function) {
                    if(key.equals("app/Service")&&fail){fail=false;throw new IllegalStateException("test pinned companion failure");}
                    return super.merge(key,value,function);
                }
            }
            public static void main(String[] args)throws Exception {
                Service warm=new Service();
                Class<?> agent=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent");
                Field field=agent.getDeclaredField("structuralReloader");field.setAccessible(true);Object reloader=field.get(null);
                Field versions=reloader.getClass().getDeclaredField("versionCounters");versions.setAccessible(true);
                var failing=new FailOnceVersions();failing.putAll((java.util.Map<String,Integer>)versions.get(reloader));versions.set(reloader,failing);
                Field inst=reloader.getClass().getDeclaredField("instrumentation");inst.setAccessible(true);Instrumentation real=(Instrumentation)inst.get(reloader);
                var attempts=new java.util.concurrent.atomic.AtomicInteger();
                Instrumentation proxy=(Instrumentation)Proxy.newProxyInstance(App.class.getClassLoader(),new Class<?>[]{Instrumentation.class},(p,m,a)->{
                    if(m.getName().equals("redefineClasses"))for(ClassDefinition definition:(ClassDefinition[])a[0])
                        if(definition.getDefinitionClass()==Service.class && attempts.incrementAndGet()>1)throw new UnsupportedOperationException("test refusal after pinned fallback");
                    try{return m.invoke(real,a);}catch(InvocationTargetException failure){throw failure.getCause();}
                });
                inst.set(reloader,proxy);System.out.println("BEFORE="+warm.getGreeting());
                for(int stage=1;stage<=2;stage++){
                    while(!Files.exists(Path.of(System.getProperty("probe.dir"),"observe"+stage)))Thread.sleep(10);
                    System.out.println("DIRECT"+stage+"="+new Service().getGreeting());
                    System.out.println("PIN"+stage+"="+new Service().entangled());
                }
            }
        }
        """;
}
