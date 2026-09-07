/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class PropertyChangeIsAllOrNothingTest {
    @TempDir Path tmp;

    @Test void rejectedSaveKeepsAllValuesAndFixRetriesBothKeys() throws Exception {
        Path properties = Files.createDirectories(tmp.resolve("classes")).resolve("application.properties");
        Files.writeString(properties, "svc.url=old\nsvc.timeout=10\n");
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath())
                .jvmArgs("-Dprobe.dir=" + tmp).with("App", APP).with("Settings", SETTINGS).start()) {
            app.awaitOrFail("CFG_READY", "Spring startup failed");
            app.awaitOrFail("] Watching 1 director", "watcher did not start");
            Files.writeString(properties, "svc.url=new\nsvc.timeout=abc\n");
            app.awaitOrFail("Rejected: the running configuration is unchanged", "invalid file was not rejected");
            Files.createFile(tmp.resolve("probe1"));
            app.awaitOrFail("PROBE1=", "first probe missing");
            assertTrue(app.output().contains("PROBE1=old:10:old:10:old:10"), app.tail());
            Files.writeString(properties, "svc.url=new\nsvc.timeout=20\n");
            app.awaitOrFail("Applied 2 property changes", "held keys were not applied together");
            Files.createFile(tmp.resolve("probe2"));
            app.awaitOrFail("PROBE2=", "second probe missing");
            assertTrue(app.output().contains("PROBE2=new:20:new:20:new:20"), app.tail());
            System.out.println("[property-change] rejected live values changed=0/6; fixed keys applied=2/2");
        }
    }

    private static final String SETTINGS = """
            package app;
            @org.springframework.boot.context.properties.ConfigurationProperties("svc")
            public class Settings {
                private String url;
                private int timeout;
                public String getUrl() { return url; }
                public void setUrl(String value) { url = value; }
                public int getTimeout() { return timeout; }
                public void setTimeout(int value) { timeout = value; }
            }
            """;
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            import org.springframework.beans.factory.annotation.Value;
            @Configuration(proxyBeanMethods = false)
            @org.springframework.boot.context.properties.EnableConfigurationProperties(Settings.class)
            @PropertySource("classpath:application.properties")
            public class App {
                @Value("${svc.url}") String url;
                @Value("${svc.timeout}") int timeout;
                public static void main(String[] args) throws Exception {
                    var context = new AnnotationConfigApplicationContext(App.class);
                    System.out.println("CFG_READY");
                    for (int i = 1; i <= 2; i++) {
                        Path probe = Path.of(System.getProperty("probe.dir"), "probe" + i);
                        while (!Files.exists(probe)) Thread.sleep(30);
                        Settings settings = context.getBean(Settings.class);
                        App reader = context.getBean(App.class);
                        System.out.println("PROBE" + i + "=" + settings.getUrl() + ":" + settings.getTimeout()
                                + ":" + reader.url + ":" + reader.timeout + ":"
                                + context.getEnvironment().getProperty("svc.url") + ":"
                                + context.getEnvironment().getProperty("svc.timeout"));
                    }
                    while (true) Thread.sleep(1000);
                }
            }
            """;
}
