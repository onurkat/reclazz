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
            running.awaitOrFail("] Watching ", "watcher missing");
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
