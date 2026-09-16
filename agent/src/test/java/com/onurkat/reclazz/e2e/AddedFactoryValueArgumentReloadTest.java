/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AddedFactoryValueArgumentReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void addedFactoryValuesFollowPropertyEdits(boolean child, boolean full) throws Exception {
        Path properties = Files.createDirectories(tmp.resolve("classes")).resolve("application.properties");
        Files.writeString(properties, content("5", "5", "old"));
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath()).jvmArgs("-Dprobe.dir=" + tmp)
                .with("App", APP).with("Config", config(0, full)).with("Client", CLIENT);
        if (child) builder.childClassLoader();
        try (var app = builder.start()) {
            app.awaitOrFail("READY", "Spring did not start");
            app.awaitOrFail("] Watching 1 director", "watcher did not start");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "child loader missing");
            app.rewrite("Config", config(1, full));
            awaitReloads(app, 1);
            Files.createFile(tmp.resolve("capture"));
            app.awaitOrFail("CAPTURED=5000:20:old", "added factory did not create the initial product");

            Files.writeString(properties, content("8", "4", "new"));
            awaitApplied(app, 1);
            probe(app, 1, "8000:25:new:8:4:new:0:2:1:true:true:false");
            Files.writeString(properties, content("10", "0", "rejected"));
            app.awaitOrFail("Rejected: the running configuration is unchanged", "division by zero was not held");
            probe(app, 2, "8000:25:new:8:4:new:0:2:1:true:true:false");
            Files.writeString(properties, content("10", "5", "recovered"));
            awaitApplied(app, 2);
            probe(app, 3, "10000:20:recovered:10:5:recovered:0:3:2:true:true:false");
            Files.writeString(properties, content("T(app.App).sideEffect()", "5", "unsupported"));
            app.awaitOrFail("Uncheckable: the running configuration is unchanged", "unsafe expression was not held");
            probe(app, 4, "10000:20:recovered:10:5:recovered:0:3:2:true:true:false");

            // Restore the on-disk accepted candidate before a new method save.
            Files.writeString(properties, content("10", "5", "recovered"));
            app.rewrite("Config", config(2, full));
            awaitReloads(app, 2);
            Files.createFile(tmp.resolve("recapture"));
            app.awaitOrFail("RECAPTURED=6000", "new saved Value dependency was not installed");
            Files.writeString(properties, content("10", "5", "final") + "cfg.next=9\n");
            awaitApplied(app, 3);
            probe(app, 5, "9000:20:final:10:5:final:0:5:4:true:true:false");
            Files.createFile(tmp.resolve("close"));
            app.awaitOrFail("CLOSED=5", "latest recreated product was not destroyed exactly once");
        }
    }

    private static void awaitReloads(WatchedApp app, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && reloads(app) < count) Thread.sleep(25);
        assertEquals(count, reloads(app), app.tail());
    }
    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Config") || s.contains("Structural reload: app.Config")).count();
    }
    private static void awaitApplied(WatchedApp app, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && applied(app) < count) Thread.sleep(25);
        assertEquals(count, applied(app), app.tail());
    }
    private static long applied(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Applied ") && s.contains(" property change")).count();
    }
    private static String content(String seconds, String divisor, String label) {
        return "cfg.seconds=" + seconds + "\ncfg.divisor=" + divisor + "\ncfg.label=" + label + "\n";
    }
    private void probe(WatchedApp app, int stage, String expected) throws Exception {
        Files.createFile(tmp.resolve("probe" + stage));
        app.awaitOrFail("PROBE" + stage + "=", "probe missing");
        String actual = app.latest("PROBE" + stage + "=");
        System.out.println("[added-factory-value] " + actual);
        assertEquals("PROBE" + stage + "=" + expected, actual, app.tail());
    }
    private static String config(int version, boolean full) {
        String method = version == 0 ? "" : """
                @Bean({"client", "alias"})
                public Client client(@Value("#{${cfg.%s} * 1000L}") long millis,
                                     @Value("#{100 / ${cfg.divisor}}") int rate,
                                     @Value("${cfg.label}") String label) {
                    return new Client(millis, rate, label);
                }
                """.formatted(version == 1 ? "seconds" : "next:6");
        return """
                package app;
                import org.springframework.context.annotation.*;
                import org.springframework.beans.factory.annotation.Value;
                @Configuration(proxyBeanMethods=%s)
                public class Config { %s }
                """.formatted(full, method);
    }
    private static final String CLIENT = """
            package app;
            public class Client implements AutoCloseable {
                final long millis; final int rate; final String label;
                static int made, closed;
                public Client(long millis, int rate, String label) {
                    made++; this.millis=millis; this.rate=rate; this.label=label;
                }
                public void close() { closed++; }
            }
            """;
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import java.util.Arrays;
            import org.springframework.context.annotation.*;
            @Configuration @PropertySource("classpath:application.properties")
            public class App {
                public static class Holder { Client client; Holder(Client client) { this.client=client; } }
                public static int calls;
                public static int sideEffect() { calls++; return 9; }
                public static void main(String[] args) throws Exception {
                    Path dir=Path.of(System.getProperty("probe.dir"));
                    try (var c=new AnnotationConfigApplicationContext(App.class, Config.class)) {
                        System.out.println("READY");
                        while (!Files.exists(dir.resolve("capture"))) Thread.sleep(20);
                        Client original=c.getBean(Client.class);
                        Holder holder=new Holder(original); c.getBeanFactory().registerSingleton("holder", holder);
                        System.out.println("CAPTURED="+original.millis+":"+original.rate+":"+original.label);
                        for (int stage=1; stage<=5; stage++) {
                            if (stage==5) {
                                while (!Files.exists(dir.resolve("recapture"))) Thread.sleep(20);
                                holder.client=c.getBean(Client.class);
                                System.out.println("RECAPTURED="+holder.client.millis);
                            }
                            while (!Files.exists(dir.resolve("probe"+stage))) Thread.sleep(20);
                            Client current=c.getBean(Client.class);
                            System.out.println("PROBE"+stage+"="+current.millis+":"+current.rate+":"+current.label
                                +":"+c.getEnvironment().getProperty("cfg.seconds")
                                +":"+c.getEnvironment().getProperty("cfg.divisor")
                                +":"+c.getEnvironment().getProperty("cfg.label")
                                +":"+calls+":"+Client.made+":"+Client.closed
                                +":"+(holder.client==current && current!=original)
                                +":"+(c.getBean("alias")==current)
                                +":"+Arrays.stream(Config.class.getDeclaredMethods()).anyMatch(m->m.getName().equals("client")));
                        }
                        while (!Files.exists(dir.resolve("close"))) Thread.sleep(20);
                    }
                    System.out.println("CLOSED="+Client.closed);
                }
            }
            """;
}
