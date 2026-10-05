/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A prototype-scoped action in the shape SAP Commerce business processes use: an
 * abstract base with a template method that calls an abstract step, and a concrete
 * subclass the process engine resolves fresh at each transition through
 * {@code getBean}. Editing the step body must take effect on the next resolution
 * with no restart.
 *
 * <p>This holds for free: the companion trampoline applies the new body to the
 * loaded class, and a prototype yields a fresh instance of that class on the next
 * lookup, so the running code is current. The orchestrator must not try to refresh
 * the non-singleton (SpringBeanReloader leaves prototypes alone), and nothing may
 * report a restart. The test pins that contract so it cannot silently regress.
 */
class PrototypeActionReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void prototypeActionBodyEditsTakeEffectOnNextResolution(boolean child) throws Exception {
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dtest.dir=" + tmp)
                .with("App", APP).with("Engine", ENGINE)
                .with("AbstractAction", abstractAction()).with("OrderAction", orderAction(1));
        if (child) builder.childClassLoader();
        try (var app = builder.start()) {
            app.awaitOrFail("READY", "Spring did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            probe(app, 0, 1);
            reload(app, orderAction(2), 1);
            probe(app, 1, 2);
            reload(app, orderAction(3), 2);
            probe(app, 2, 3);
            reload(app, orderAction(4), 3);
            probe(app, 3, 4);
            assertFalse(app.output().stream().anyMatch(s -> s.contains("restart")
                    && s.contains("OrderAction")), app.tail());
        }
    }

    private void reload(WatchedApp app, String source, int expected) throws Exception {
        app.rewrite("OrderAction", source);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && reloads(app) < expected) Thread.sleep(25);
        assertEquals(expected, reloads(app), app.tail());
    }

    private long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.OrderAction")
                || s.contains("Structural reload: app.OrderAction")).count();
    }

    private void probe(WatchedApp app, int stage, int value) throws Exception {
        Files.createFile(tmp.resolve("probe" + stage));
        app.awaitOrFail("PROBE" + stage + "=", "action probe did not complete");
        String actual = app.latest("PROBE" + stage + "=");
        System.out.println("[prototype-action] " + actual);
        // engine result follows the edit; fresh=true proves the bean is a prototype.
        assertEquals("PROBE" + stage + "=" + value + ":fresh", actual, app.tail());
    }

    /** The template-method base, the AbstractProceduralAction shape. */
    private static String abstractAction() {
        return """
                package app;
                public abstract class AbstractAction {
                    public int execute() { return step(); }
                    protected abstract int step();
                }
                """;
    }

    /** A prototype-scoped concrete action; its step body is what the reload edits. */
    private static String orderAction(int value) {
        return """
                package app;
                import org.springframework.stereotype.Component;
                import org.springframework.context.annotation.Scope;
                @Component
                @Scope(scopeName = "prototype")
                public class OrderAction extends AbstractAction {
                    protected int step() { return %d; }
                }
                """.formatted(value);
    }

    /** A singleton that resolves the action fresh per step, like the process engine. */
    private static final String ENGINE = """
            package app;
            import org.springframework.stereotype.Component;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.context.ApplicationContext;
            @Component
            public class Engine {
                @Autowired ApplicationContext context;
                int runStep() { return context.getBean(OrderAction.class).execute(); }
                boolean prototype() { return context.getBean(OrderAction.class) != context.getBean(OrderAction.class); }
            }
            """;

    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            @Configuration @ComponentScan("app")
            public class App {
                public static void main(String[] args) throws Exception {
                    try (var context = new AnnotationConfigApplicationContext(App.class)) {
                        Path dir = Path.of(System.getProperty("test.dir"));
                        Engine engine = context.getBean(Engine.class);
                        System.out.println("READY");
                        for (int stage = 0; stage < 4; stage++) {
                            while (!Files.exists(dir.resolve("probe" + stage))) Thread.sleep(10);
                            System.out.println("PROBE" + stage + "=" + engine.runStep()
                                + ":" + (engine.prototype() ? "fresh" : "shared"));
                        }
                    }
                }
            }
            """;
}
