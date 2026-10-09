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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Observes live Spring objects; a reload log alone cannot establish recreation or injection. */
class OrdinarySpringReloadEvidenceTest {
    @TempDir Path tmp;

    @ParameterizedTest(name = "editedSingletonRecreatesStateAndReachesHeldCallers [{index}] {arguments}")
    @ValueSource(booleans = {false, true})
    void editedSingletonRecreatesStateAndReachesHeldCallers(boolean child) throws Exception {
        exercise(child, false);
    }

    @ParameterizedTest(name = "addedFieldInjectionAnnotationsReachRecreatedSingleton [{index}] {arguments}")
    @ValueSource(booleans = {false, true})
    void addedFieldInjectionAnnotationsReachRecreatedSingleton(boolean child) throws Exception {
        exercise(child, true);
    }

    private void exercise(boolean child, boolean injection) throws Exception {
        String annotations = Path.of(javax.annotation.Resource.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath() + File.pathSeparator + annotations)
                .agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dprobe.dir=" + tmp).with("App", APP).with("Service", service(false))
                .with("Holder", "package app; public class Holder { @org.springframework.beans.factory.annotation.Autowired public Service service; }")
                .with("Manual", "package app; public class Manual { public Service service; }")
                .with("Unrelated", "package app; public class Unrelated { public int count; }");
        if (child) builder.childClassLoader();
        try (var app = builder.start()) {
            app.awaitOrFail("BEFORE=1:41:null:0:null", "baseline state/injection differs");
            app.awaitOrFail("] Watching ", "watcher missing");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "child loader missing");
            app.rewrite("Service", service(injection).replace("return 1;", "return 2;"));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (System.nanoTime() < until && app.output().stream().noneMatch(s -> s.contains("Reloaded app.Service")
                    || s.contains("Structural reload: app.Service"))) Thread.sleep(25);
            Files.createFile(tmp.resolve("probe"));
            app.awaitOrFail("AFTER=", "live observation missing");
            String expected = "AFTER=2:0:true:true:true:true:29:41:"
                    + (injection ? "injected:42:injected" : "null:0:null");
            assertEquals(expected, app.latest("AFTER="), app.tail());
            System.out.println("[ordinary-spring] child=" + child + " injection=" + injection + " " + app.latest("AFTER="));
        }
    }

    private static String service(boolean annotations) {
        return """
                package app;
                @org.springframework.stereotype.Service
                public class Service {
                    public int state;
                    %s public String token;
                    %s public int amount;
                    %s public String resource;
                    public int version() { return 1; }
                }
                """.formatted(annotations ? "@org.springframework.beans.factory.annotation.Autowired" : "",
                annotations ? "@org.springframework.beans.factory.annotation.Value(\"${fixture.amount}\")" : "",
                annotations ? "@javax.annotation.Resource(name=\"token\")" : "");
    }

    private static final String APP = """
            package app;
            import java.nio.file.*;
            import java.util.Map;
            import org.springframework.context.annotation.AnnotationConfigApplicationContext;
            import org.springframework.core.env.MapPropertySource;
            public class App {
                public static void main(String[] args) throws Exception {
                    try (var context = new AnnotationConfigApplicationContext()) {
                        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of("fixture.amount", "42")));
                        context.register(Service.class, Holder.class, Unrelated.class);
                        context.registerBean("token", String.class, () -> "injected");
                        context.refresh();
                        Service before = context.getBean(Service.class); before.state = 41;
                        Manual held = new Manual(); held.service = before;
                        context.getBeanFactory().registerSingleton("manual", held);
                        Unrelated unrelated = context.getBean(Unrelated.class); unrelated.count = 29;
                        System.out.println("BEFORE=" + before.version() + ":" + before.state + ":" + before.token + ":" + before.amount + ":" + before.resource);
                        while (!Files.exists(Path.of(System.getProperty("probe.dir"), "probe"))) Thread.sleep(20);
                        Service after = context.getBean(Service.class);
                        System.out.println("AFTER=" + held.service.version() + ":" + after.state + ":" + (before != after)
                            + ":" + (held == context.getBean("manual")) + ":" + (held.service == after)
                            + ":" + (context.getBean(Holder.class).service == after) + ":" + context.getBean(Unrelated.class).count
                            + ":" + before.state + ":" + after.token + ":" + after.amount + ":" + after.resource);
                        if (unrelated != context.getBean(Unrelated.class)) throw new AssertionError("unrelated singleton recreated");
                    }
                }
            }
            """;
}
