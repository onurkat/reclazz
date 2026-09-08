/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ComputedValueConstructorReloadTest {
    @TempDir Path tmp;

    @Test
    void computedConstructorUpdatesRejectsAndRecoversWithoutRestart() throws Exception {
        Path properties = Files.createDirectories(tmp.resolve("classes")).resolve("application.properties");
        Files.writeString(properties, content("5", "5", "old"));
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath())
                .jvmArgs("-Dprobe.dir=" + tmp).with("App", APP).start()) {
            app.awaitOrFail("CFG_READY=5000:20:old", "Spring must evaluate the initial expressions");
            app.awaitOrFail("] Watching 1 director", "watcher did not start");
            Files.writeString(properties, content("8", "4", "new"));
            app.awaitOrFail("Applied 3 property changes", "valid candidate was not applied");
            probe(app, 1, "8000:25:new:8:4:new:0:2:healed");

            Files.writeString(properties, content("10", "0", "rejected"));
            app.awaitOrFail("Rejected: the running configuration is unchanged", "division by zero was not rejected");
            probe(app, 2, "8000:25:new:8:4:new:0:2:healed");

            Files.writeString(properties, content("10", "5", "recovered"));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline && app.output().stream()
                    .filter(s -> s.contains("Applied 3 property changes")).count() < 2) Thread.sleep(25);
            assertEquals(2, app.output().stream().filter(s -> s.contains("Applied 3 property changes")).count(), app.tail());
            probe(app, 3, "10000:20:recovered:10:5:recovered:0:3:healed");

            Files.writeString(properties, content("T(app.App).sideEffect()", "5", "unsupported"));
            app.awaitOrFail("Uncheckable: the running configuration is unchanged", "unsupported expression was not held");
            probe(app, 4, "10000:20:recovered:10:5:recovered:0:3:healed");
        }
    }

    private static String content(String seconds, String divisor, String label) {
        return "cfg.seconds=" + seconds + "\ncfg.divisor=" + divisor + "\ncfg.label=" + label + "\n";
    }

    private void probe(WatchedApp app, int stage, String expected) throws Exception {
        Files.createFile(tmp.resolve("probe" + stage));
        app.awaitOrFail("PROBE" + stage + "=", "probe missing");
        String actual = app.latest("PROBE" + stage + "=");
        System.out.println("[computed-constructor] " + actual);
        assertEquals("PROBE" + stage + "=" + expected, actual, app.tail());
    }

    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            import org.springframework.beans.factory.annotation.Value;
            @Configuration(proxyBeanMethods = false)
            @PropertySource("classpath:application.properties")
            public class App {
                public static class Client {
                    final long millis;
                    final int rate;
                    @Value("${cfg.label}") String label;
                    static int constructions;
                    public Client(@Value("#{${cfg.seconds} * 1000L}") long millis,
                                  @Value("#{100 / ${cfg.divisor}}") int rate) {
                        constructions++;
                        this.millis = millis; this.rate = rate;
                    }
                }
                public static class Holder {
                    Client client;
                    Holder(Client client) { this.client = client; }
                }
                public static int calls;
                public static int sideEffect() { calls++; return 9; }
                public static void main(String[] args) throws Exception {
                    try (var context = new AnnotationConfigApplicationContext(App.class, Client.class)) {
                        Client original = context.getBean(Client.class);
                        Holder holder = new Holder(original);
                        context.getBeanFactory().registerSingleton("holder", holder);
                        System.out.println("CFG_READY=" + original.millis + ":" + original.rate + ":" + original.label);
                        for (int i = 1; i <= 4; i++) {
                            Path probe = Path.of(System.getProperty("probe.dir"), "probe" + i);
                            while (!Files.exists(probe)) Thread.sleep(20);
                            Client bean = context.getBean(Client.class);
                            System.out.println("PROBE" + i + "=" + bean.millis + ":" + bean.rate + ":" + bean.label
                                + ":" + context.getEnvironment().getProperty("cfg.seconds")
                                + ":" + context.getEnvironment().getProperty("cfg.divisor")
                                + ":" + context.getEnvironment().getProperty("cfg.label")
                                + ":" + calls + ":" + Client.constructions + ":"
                                + (holder.client == bean && bean != original ? "healed" : "stale"));
                        }
                    }
                }
            }
            """;
}
