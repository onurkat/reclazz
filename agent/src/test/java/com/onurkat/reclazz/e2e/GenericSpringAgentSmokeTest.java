/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end smoke test for the GENERIC (non-Hybris) path, the one the
 * README leads with ("works with any Spring Boot application out of the
 * box"). It launches a real child JVM with {@code -javaagent}, runs a small
 * Spring application in it, edits a bean class on disk and asserts the
 * running app serves the new behaviour. Without it, a change made for the
 * Hybris path could silently break the generic one and nothing would notice
 * until a user reported it.
 *
 * <p>On the shared harness, like the other end-to-end tests; it used to
 * carry its own copy of the process launch, the output reader, the fixture
 * compiler and the Spring classpath filter. Skipped when the agent jar has
 * not been built.
 */
class GenericSpringAgentSmokeTest {

    private static final long RELOAD_TIMEOUT_SEC = 45;

    @TempDir
    Path tmp;

    @Test
    void editingABeanClassHotReloadsItInARunningSpringApp() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .classpath(WatchedApp.springClasspath())
                .with("App", appSource())
                .with("Greeter", greeterSource("v1"))
                .start()) {

            app.awaitOrFail("APP_STARTED", "app did not start under the agent");
            app.awaitOrFail("GREET=v1", "app did not serve v1");

            // Deliberately NOT waiting for the watcher to settle: editing
            // right after startup is what a developer does, and it used to
            // fall into the baseline race (the edit was recorded as the
            // baseline while the JVM still ran the old class, so it never
            // reloaded). See BaselineRaceTest.
            app.rewrite("Greeter", greeterSource("v2"));

            assertTrue(app.awaits("GREET=v2", RELOAD_TIMEOUT_SEC),
                    () -> "hot reload did not reach the running app:\n" + app.tail());
        }
    }

    private static String appSource() {
        return """
                package app;
                import org.springframework.context.annotation.AnnotationConfigApplicationContext;
                import org.springframework.context.annotation.ComponentScan;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                @ComponentScan("app")
                public class App {
                    public static void main(String[] args) throws Exception {
                        var ctx = new AnnotationConfigApplicationContext(App.class);
                        System.out.println("APP_STARTED");
                        while (true) {
                            Thread.sleep(500);
                            System.out.println("GREET=" + ctx.getBean(Greeter.class).greet());
                        }
                    }
                }
                """;
    }

    private static String greeterSource(String value) {
        return """
                package app;
                import org.springframework.stereotype.Service;

                @Service
                public class Greeter {
                    public String greet() { return "%s"; }
                }
                """.formatted(value);
    }
}
