/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.AbstractResourceBasedMessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

/** Native text and MessageFormat results, not mock cache-reset counters. */
class NativeMessageSourceReloadTest {
    @TempDir Path tmp;
    private static final Locale TURKISH = Locale.forLanguageTag("tr");

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void nativeCacheKeepsOldLookupUntilExplicitReset(boolean reloadable) throws Exception {
        Files.writeString(tmp.resolve("messages_tr.properties"), messages("Before"));
        try (var loader = new URLClassLoader(new java.net.URL[]{tmp.toUri().toURL()}, null)) {
            AbstractResourceBasedMessageSource source;
            if (reloadable) {
                var nativeSource = new ReloadableResourceBundleMessageSource();
                nativeSource.setBasename(tmp.resolve("messages").toUri().toString());
                source = nativeSource;
            } else {
                var nativeSource = new ResourceBundleMessageSource();
                nativeSource.setBasename("messages"); nativeSource.setBundleClassLoader(loader);
                source = nativeSource;
            }
            source.setDefaultEncoding("UTF-8"); source.setFallbackToSystemLocale(false); source.setCacheMillis(-1);
            assertEquals("Before Ada", source.getMessage("greeting", new Object[]{"Ada"}, TURKISH));
            Files.writeString(tmp.resolve("messages_tr.properties"), messages("Yeni"));
            assertEquals("Before Ada", source.getMessage("greeting", new Object[]{"Ada"}, TURKISH),
                    "native save alone must stay cached");
            var reset = com.onurkat.reclazz.spring.SpringMessageSourceReloader.class.getDeclaredMethod("clear", Object.class);
            reset.setAccessible(true);
            assertEquals(Boolean.TRUE, reset.invoke(null, source));
            assertEquals("Yeni Ada", source.getMessage("greeting", new Object[]{"Ada"}, TURKISH));
        }
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void cachedNativeMessagesFollowRepeatedWatchedLocaleSaves(boolean reloadable, boolean child) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path file = classes.resolve("messages_tr.properties");
        Files.writeString(file, messages("Before"));
        Files.writeString(classes.resolve("messages.properties"), "fallback=Fallback\n");
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath()).with("App", app(reloadable))
                .jvmArgs("-Dprobe.dir=" + tmp);
        if (child) builder.childClassLoader();
        try (var running = builder.start()) {
            running.awaitOrFail("READY=Before Ada:Fallback", "native message lookup did not warm the caches");
            running.awaitWatcherReady();
            if (child) running.awaitOrFail("APP_IN_CHILD_MODULE=true", "child application loader missing");
            for (int n = 1; n <= 3; n++) {
                String label = n == 1 ? "Yeni" : n == 2 ? "İyi günler" : "Again";
                Files.writeString(file, messages(label));
                long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (System.nanoTime() < until && resets(running) < n) Thread.sleep(25);
                assertEquals(n, resets(running), running.tail());
                Files.createFile(tmp.resolve("probe" + n));
                running.awaitOrFail("PROBE" + n + "=", "native lookup probe missing");
                String actual = running.latest("PROBE" + n + "=");
                assertEquals("PROBE" + n + "=" + label + " Ada:Fallback", actual, running.tail());
                System.out.println("[native-message] " + actual);
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void configuredBundleRefreshesBothOfItsConsumers(boolean alsoImported) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path bundle = Files.createDirectories(classes.resolve("i18n")).resolve("checkout.properties");
        Path config = classes.resolve("application.properties");
        Files.writeString(bundle, "checkout.text=before\n");
        String settings = "spring.messages.basename=i18n/checkout\n"
                + (alsoImported ? "spring.config.import=classpath:i18n/checkout.properties\n" : "");
        Files.writeString(config, settings + "other.value=one\n");
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100").with("App", BOOT).start()) {
            String property = alsoImported ? "before" : "absent";
            app.awaitOrFail("READ=before:" + property + ":one:0", "cache was not warmed");
            app.awaitWatcherReady();
            Files.writeString(config, settings + "other.value=two\n");
            app.awaitOrFail("READ=before:" + property + ":two:0", "unrelated Boot configuration did not update");
            Files.writeString(bundle, "checkout.text=after\n");
            app.awaitOrFail("READ=after:" + (alsoImported ? "after" : "absent") + ":two:0",
                    "translation, Environment or lazy singleton state differs from startup");
        }
    }

    private static final String BOOT = """
            package app;
            import org.springframework.context.annotation.*;
            import org.springframework.context.support.*;
            @Configuration(proxyBeanMethods=false)
            @Import(org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration.class)
            public class App {
                private static int lazyStarts;
                @Bean @Lazy public ResourceBundleMessageSource unusedSource() {
                    lazyStarts++;
                    return new ResourceBundleMessageSource();
                }
                public static void main(String[] args) throws Exception {
                    try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(App.class)
                            .web(org.springframework.boot.WebApplicationType.NONE).run()) {
                        String last = "";
                        while (true) {
                            String row = context.getMessage("checkout.text", null, java.util.Locale.ROOT)
                                    + ":" + context.getEnvironment().getProperty("checkout.text", "absent")
                                    + ":" + context.getEnvironment().getProperty("other.value") + ":" + lazyStarts;
                            if (!row.equals(last)) System.out.println("READ=" + row);
                            last = row;
                            Thread.sleep(40);
                        }
                    }
                }
            }
            """;

    private static long resets(WatchedApp running) {
        return running.output().stream().filter(v -> v.contains("messages_tr.properties re-read:")).count();
    }
    private static String messages(String label) { return "greeting=" + label + " {0}\n"; }
    private static String app(boolean reloadable) {
        String source = reloadable ? """
                var source = new ReloadableResourceBundleMessageSource();
                source.setBasename(dir.resolve("classes/messages").toUri().toString());
                """ : """
                var source = new ResourceBundleMessageSource();
                source.setBasename("messages"); source.setBundleClassLoader(App.class.getClassLoader());
                """;
        return """
                package app;
                import java.nio.file.*;
                import org.springframework.context.support.*;
                public class App {
                    public static void main(String[] args) throws Exception {
                        Path dir = Path.of(System.getProperty("probe.dir"));
                        %s
                        source.setDefaultEncoding("UTF-8"); source.setFallbackToSystemLocale(false); source.setCacheMillis(-1);
                        try (var context = new GenericApplicationContext()) {
                            context.getBeanFactory().registerSingleton("messageSource", source);
                            context.refresh();
                            var locale = java.util.Locale.forLanguageTag("tr");
                            java.util.concurrent.Callable<String> lookup = () -> context.getMessage("greeting", new Object[]{"Ada"}, locale)
                                + ":" + context.getMessage("fallback", null, locale);
                            System.out.println("READY=" + lookup.call());
                            for (int n = 1; n <= 3; n++) {
                                while (!Files.exists(dir.resolve("probe" + n))) Thread.sleep(20);
                                System.out.println("PROBE" + n + "=" + lookup.call());
                            }
                        }
                    }
                }
                """.formatted(source);
    }
}
