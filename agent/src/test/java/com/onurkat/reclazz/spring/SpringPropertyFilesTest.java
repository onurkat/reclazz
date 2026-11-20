/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.*;
import org.springframework.boot.env.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpringPropertyFilesTest {
    @TempDir Path tmp;
    @Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(Settings.class)
    static class Config { @Value("${svc.timeout:5}") int timeout; }
    @Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(ReadOnlySettings.class)
    static class ReadOnlyConfig { }
    @ConfigurationProperties("svc")
    public static class ReadOnlySettings {
        private final List<String> items = new ArrayList<>();
        public List<String> getItems() { return items; }
    }
    @ConfigurationProperties("svc")
    public static class Settings {
        static Settings reject;
        private int timeout = 5;
        public int getTimeout() { return timeout; }
        public void setTimeout(int value) { if (this == reject) throw new IllegalStateException("live-setter-failed"); timeout = value; }
    }

    @Test void fileReplacementPreservesPrecedenceAndEmptyFileOwnership() throws Exception {
        Path file = file("application.properties", "svc.timeout=10\n");
        try (var context = context(file, "dev")) {
            var reload = new SpringPropertyFiles();
            var sources = context.getEnvironment().getPropertySources();
            sources.addFirst(new MapPropertySource("commandLineArgs", Map.of("svc.timeout", "99")));
            sources.addLast(new MapPropertySource("defaults", Map.of("svc.timeout", "7")));
            Files.writeString(file, "svc.timeout=20\n");
            applied(reload, file, context); assertValues(context, 99);
            Files.writeString(file, ""); applied(reload, file, context); assertValues(context, 99);
            sources.remove("commandLineArgs");
            Files.writeString(file, "svc.timeout=30\n"); applied(reload, file, context); assertValues(context, 30);
            Files.writeString(file, ""); applied(reload, file, context); assertValues(context, 7);
            assertEquals(1, java.util.stream.StreamSupport.stream(sources.spliterator(), false)
                    .filter(s -> s.getName().startsWith("fixture")).count());
        }
    }

    @Test void activeProfileDocumentsKeepTheirOrderAndTopologyChangesAreHeld() throws Exception {
        Path file = file("application.yaml", documents("10", "20", "30"));
        try (var context = context(file, "dev")) {
            var reload = new SpringPropertyFiles();
            Files.writeString(file, documents("11", "21", "invalid-but-inactive"));
            applied(reload, file, context); assertValues(context, 21);
            Files.writeString(file, documents("11", "22", "31").replace("on-profile: dev", "on-profile: other"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, reload.apply(file, List.of(context), Runnable::run).outcome().state());
            assertTrue(com.onurkat.reclazz.ui.RestartLedger.digest().stream()
                    .anyMatch(line -> line.contains("Active configuration document topology changed")));
            assertValues(context, 21);
            Files.writeString(file, documents("12", "23", "33"));
            applied(reload, file, context); assertValues(context, 23);
        }
    }

    @Test void rejectionInOneContextHoldsAllContextsAndFixRetries() throws Exception {
        Path file = file("application.yml", documents("10", "20", "30"));
        try (var first = context(file, "other"); var second = context(file, "dev")) {
            var reload = new SpringPropertyFiles();
            Files.writeString(file, documents("11", "bad", "30"));
            assertEquals(PropertyChangeOutcome.State.REJECTED,
                    reload.apply(file, List.of(first, second), Runnable::run).outcome().state());
            assertValues(first, 10); assertValues(second, 20);
            Files.writeString(file, documents("12", "22", "30"));
            assertEquals(PropertyChangeOutcome.State.APPLIED,
                    reload.apply(file, List.of(first, second), Runnable::run).outcome().state());
            assertValues(first, 12); assertValues(second, 22);
        }
    }

    @Test void boundaryUsesOneReadAndANonRunningBoundaryDoesNotAccept() throws Exception {
        Path file = file("application.properties", "svc.timeout=10\n");
        try (var context = context(file, "dev")) {
            var reload = new SpringPropertyFiles();
            Files.writeString(file, "svc.timeout=20\n");
            assertEquals(PropertyChangeOutcome.State.NOT_RUN, reload.apply(file, List.of(context), ignored -> { }).outcome().state());
            assertValues(context, 10);
            var result = reload.apply(file, List.of(context), action -> {
                try { Files.writeString(file, "svc.timeout=30\n"); } catch (Exception e) { throw new RuntimeException(e); }
                action.run();
            });
            assertEquals(PropertyChangeOutcome.State.APPLIED, result.outcome().state()); assertValues(context, 20);
            applied(reload, file, context); assertValues(context, 30);
        }
    }

    @Test void partialSetterFailureRemainsPendingOnAnIdenticalSave() throws Exception {
        Path file = file("application.properties", "svc.timeout=10\n");
        try (var context = context(file, "dev")) {
            var reload = new SpringPropertyFiles(); Settings settings = context.getBean(Settings.class);
            Settings.reject = settings;
            Files.writeString(file, "svc.timeout=20\n");
            assertEquals(PropertyChangeOutcome.State.PARTIAL, reload.apply(file, List.of(context), Runnable::run).outcome().state());
            assertEquals(10, settings.getTimeout()); assertEquals("20", context.getEnvironment().getProperty("svc.timeout"));
            Settings.reject = null;
            applied(reload, file, context); assertValues(context, 20); assertSame(settings, context.getBean(Settings.class));
        } finally { Settings.reject = null; }
    }

    @Test void inactiveOrUnownedFilesNeverBecomeAnOverride() throws Exception {
        Path file = file("application.properties", "svc.timeout=10\n");
        Path other = file("application-prod.properties", "svc.timeout=99\n");
        try (var context = context(file, "dev")) {
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE,
                    new SpringPropertyFiles().apply(other, List.of(context), Runnable::run).outcome().state());
            assertValues(context, 10);
        }
    }

    @Test void unownedSourceMessageNamesLoadedCopy() throws Exception {
        Path output = Files.createDirectories(tmp.resolve("build/resources/main")).resolve("application.properties");
        Path source = Files.createDirectories(tmp.resolve("src/main/resources")).resolve("application.properties");
        Files.writeString(output, "svc.timeout=10\n");
        Files.writeString(source, "svc.timeout=99\n");
        List<String> warnings = new ArrayList<>();
        StatusReporter.StatusListener listener = (level, message) -> warnings.add(message);
        StatusReporter.addListener(listener);
        try (var context = context(output, "dev")) {
            context.getEnvironment().getPropertySources().addLast(new MapPropertySource("defaults", Map.of("svc.timeout", "5")));
            var reload = new SpringPropertyFiles();
            // Native origins identify the loaded file before any ownership cache exists.
            for (int attempt = 0; attempt < 2; attempt++) {
                warnings.clear();
                var result = reload.apply(source, List.of(context), action -> fail("Unowned file entered boundary"));
                assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, result.outcome().state());
                String reason = result.outcome().findings().get(0);
                assertTrue(reason.contains(source.toRealPath() + " is not the file this application loaded"), reason);
                assertTrue(reason.contains("loaded files with this name: [" + output.toRealPath() + "]"), reason);
                assertTrue(reason.contains("nothing was applied from this file"), reason);
                assertTrue(warnings.stream().anyMatch(message -> message.contains(reason)), warnings.toString());
                assertTrue(result.effective().isEmpty()); assertTrue(result.changed().isEmpty());
                assertValues(context, attempt == 0 ? 10 : 5);
                if (attempt == 0) {
                    // Replacement loses native origins; empty files keep verified cached ownership.
                    Files.writeString(output, ""); applied(reload, output, context);
                }
            }
            context.getEnvironment().getPropertySources().replace("fixture", new MapPropertySource("fixture", Map.of("svc.timeout", "88")));
            var stale = reload.apply(source, List.of(context), action -> fail("Unowned file entered boundary"));
            // Without any Boot source, the existing legacy fallback is retained.
            assertNull(stale);
            assertEquals("88", context.getEnvironment().getProperty("svc.timeout"));
        } finally { StatusReporter.removeListener(listener); }
    }

    @Test void unownedSourceMessageWithoutLoadedCopy() throws Exception {
        Path loaded = file("different.properties", "svc.timeout=10\n");
        Path source = file("application.properties", "svc.timeout=99\n");
        List<String> warnings = new ArrayList<>();
        StatusReporter.StatusListener listener = (level, message) -> warnings.add(message);
        StatusReporter.addListener(listener);
        try (var context = context(loaded, "dev")) {
            var result = new SpringPropertyFiles().apply(source, List.of(context), action -> fail("Unowned file entered boundary"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, result.outcome().state());
            String reason = result.outcome().findings().get(0);
            assertTrue(reason.contains(source.toRealPath() + " is not the file this application loaded"), reason);
            assertTrue(reason.contains("loaded files with this name: []"), reason);
            assertFalse(reason.contains(loaded.toRealPath().toString()), reason);
            assertTrue(reason.contains("nothing was applied from this file"), reason);
            assertTrue(warnings.stream().anyMatch(message -> message.contains(reason)), warnings.toString());
            assertTrue(result.effective().isEmpty()); assertTrue(result.changed().isEmpty());
            assertValues(context, 10);
        } finally { StatusReporter.removeListener(listener); }
    }

    static class BootEnvironment extends StandardEnvironment {
        @Override protected ConfigurablePropertyResolver createPropertyResolver(MutablePropertySources sources) {
            return org.springframework.boot.context.properties.source.ConfigurationPropertySources.createPropertyResolver(sources);
        }
    }

    @Test void boot27NativeResolverAllowsRemovalAndRestoration() throws Exception {
        Path file = file("application.properties", "svc.timeout=10\n");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(new BootEnvironment());
            var sources = context.getEnvironment().getPropertySources();
            new PropertiesPropertySourceLoader().load("fixture", new FileSystemResource(file)).forEach(sources::addFirst);
            sources.addLast(new MapPropertySource("defaults", Map.of("svc.timeout", "5")));
            org.springframework.boot.context.properties.source.ConfigurationPropertySources.attach(context.getEnvironment());
            context.register(Config.class); context.refresh();
            Object resolver = PropertyChangeCheck.environmentResolver(getClass().getClassLoader(), context.getEnvironment());
            assertEquals("org.springframework.boot.context.properties.source.ConfigurationPropertySourcesPropertyResolver", resolver.getClass().getName());
            assertEquals("2.7.18", resolver.getClass().getPackage().getImplementationVersion());
            Settings held = context.getBean(Settings.class);
            var reload = new SpringPropertyFiles();
            assertValues(context, 10);
            for (boolean delete : List.of(false, true)) {
                if (delete) Files.delete(file); else Files.writeString(file, "marker=ready\n");
                var result = reload.apply(file, List.of(context), Runnable::run);
                assertEquals(PropertyChangeOutcome.State.APPLIED, result.outcome().state(), result.outcome().findings().toString());
                assertEquals(1, result.outcome().valueFields());
                assertValues(context, 5); assertSame(held, context.getBean(Settings.class));
                Files.writeString(file, "svc.timeout=30\n"); applied(reload, file, context);
                assertValues(context, 30); assertSame(held, context.getBean(Settings.class));
            }
        }
    }

    @Test void externalSourceReplacementIsNotOverwritten() throws Exception {
        Path file = file("application.properties", "svc.timeout=10\n");
        try (var context = context(file, "dev")) {
            var reload = new SpringPropertyFiles(); applied(reload, file, context);
            context.getEnvironment().getPropertySources().replace("fixture", new MapPropertySource("fixture", Map.of("svc.timeout", "88")));
            Files.writeString(file, "svc.timeout=20\n");
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, reload.apply(file, List.of(context), Runnable::run).outcome().state());
            assertEquals("88", context.getEnvironment().getProperty("svc.timeout"));
        }
    }

    @Test void malformedYamlAndImportsDoNotChangeLiveSources() throws Exception {
        Path file = file("application.yaml", "svc:\n  timeout: 10\n");
        try (var context = context(file, "dev")) {
            var reload = new SpringPropertyFiles();
            for (String invalid : List.of("svc: [broken\n", "spring.config.import: file:other.yaml\nsvc.timeout: 20\n")) {
                Files.writeString(file, invalid);
                assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, reload.apply(file, List.of(context), Runnable::run).outcome().state());
                assertValues(context, 10);
            }
            Files.writeString(file, "svc:\n  timeout: 30\n"); applied(reload, file, context); assertValues(context, 30);
        }
    }

    @Test void readOnlyCollectionRemovalIsHeldInsteadOfReportingStaleStateAsApplied() throws Exception {
        Path file = file("readonly.properties", "svc.items[0]=a\n");
        try (var context = new AnnotationConfigApplicationContext()) {
            new PropertiesPropertySourceLoader().load("fixture", new FileSystemResource(file))
                    .forEach(context.getEnvironment().getPropertySources()::addFirst);
            context.register(ReadOnlyConfig.class); context.refresh();
            Files.writeString(file, "");
            var result = new SpringPropertyFiles().apply(file, List.of(context), Runnable::run);
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, result.outcome().state());
            assertEquals(List.of("a"), context.getBean(ReadOnlySettings.class).getItems());
            assertEquals("a", context.getEnvironment().getProperty("svc.items[0]"));
        }
    }

    @Test void aSourceWithMixedResourceOriginsCannotOwnAFile() throws Exception {
        Path file = file("application.properties", "svc.timeout=10\n");
        Path other = file("other.properties", "other=value\n");
        try (var context = context(file, "dev")) {
            Map<String, Object> mixed = new LinkedHashMap<>();
            for (Path path : List.of(other, file)) {
                var source = (OriginTrackedMapPropertySource) new PropertiesPropertySourceLoader()
                        .load("part", new FileSystemResource(path)).get(0);
                source.getSource().forEach((key, value) -> mixed.put(String.valueOf(key), value));
            }
            context.getEnvironment().getPropertySources().replace("fixture", new OriginTrackedMapPropertySource("fixture", mixed));
            Files.writeString(file, "svc.timeout=20\n");
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE,
                    new SpringPropertyFiles().apply(file, List.of(context), Runnable::run).outcome().state());
            assertValues(context, 10);
            assertEquals("value", context.getEnvironment().getProperty("other"));
        }
    }

    @Test void deletedLoggerDataKeepsEachOwnerAndFailedResetCanRetryWithoutRebindingOthers() throws Exception {
        Path file=file("logging-owned.properties","svc.timeout=10\nlogging.level.fixture=DEBUG\n");
        try(var first=context(file,"dev");var second=context(file,"dev")) {
            var reload=new SpringPropertyFiles();Files.writeString(file,"svc.timeout=20\n");
            var result=reload.apply(file,List.of(first,second),Runnable::run);
            assertEquals(PropertyChangeOutcome.State.APPLIED,result.outcome().state());assertEquals(2,result.loggerOwners().size());
            var a=result.loggerOwners().get(0);var b=result.loggerOwners().get(1);
            assertSame(first,a.applicationContext());assertSame(second,b.applicationContext());
            assertEquals(Set.of("logging.level.fixture"),a.removedKeys());
            assertEquals(Map.of("logging.level.fixture","DEBUG"),a.previousLoggingValues());assertTrue(a.effectiveLoggingValues().isEmpty());
            a.retryLoggerReset().run();
            var retry=reload.apply(file,List.of(first,second),Runnable::run);
            assertEquals(Set.of("logging.level.fixture"),retry.loggerOwners().get(0).removedKeys());
            assertEquals(1,retry.loggerOwners().size());assertSame(first,retry.loggerOwners().get(0).applicationContext());assertValues(first,20);assertValues(second,20);
        }
    }

    @Test void loggerGroupMembersKeepEffectiveLevelsFromOtherSources() throws Exception {
        Path file=file("group.properties","svc.timeout=10\nlogging.level.fixturegroup=DEBUG\n");
        try(var context=context(file,"dev")) {
            context.getBeanFactory().registerSingleton(org.springframework.boot.context.logging.LoggingApplicationListener.LOGGER_GROUPS_BEAN_NAME,new org.springframework.boot.logging.LoggerGroups(Map.of("fixturegroup",List.of("fixture.member"))));
            context.getEnvironment().getPropertySources().addLast(new MapPropertySource("lower",Map.of("logging.level.fixture.member","ERROR")));
            Files.writeString(file,"svc.timeout=10\n");
            var result=new SpringPropertyFiles().apply(file,List.of(context),Runnable::run);
            assertEquals(PropertyChangeOutcome.State.APPLIED,result.outcome().state());
            assertEquals("fixture.member",result.loggerOwners().get(0).previousLoggingValues().get("logging.group.fixturegroup"));
            assertEquals("ERROR",result.loggerOwners().get(0).effectiveLoggingValues().get("logging.level.fixture.member"));
        }
    }

    @Test void invalidGroupMetadataDoesNotUndoOrMisreportSuccessfulPropertyApplication() throws Exception {
        Path file=file("broken-group.properties","svc.timeout=10\nlogging.level.fixturegroup=DEBUG\n");
        try(var context=context(file,"dev")) {
            context.getBeanFactory().registerSingleton(org.springframework.boot.context.logging.LoggingApplicationListener.LOGGER_GROUPS_BEAN_NAME,
                    new org.springframework.boot.logging.LoggerGroups(Map.of("fixturegroup",List.of("fixture.member"))));
            context.getEnvironment().getPropertySources().addLast(new MapPropertySource("invalid-member",Map.of("logging.level.fixture.member","${missing.logger.level}")));
            var reload=new SpringPropertyFiles();Files.writeString(file,"svc.timeout=20\n");
            var result=reload.apply(file,List.of(context),Runnable::run);
            assertValues(context,20);
            assertEquals(PropertyChangeOutcome.State.APPLIED,result.outcome().state());
            var retry=reload.apply(file,List.of(context),Runnable::run);
            assertEquals(PropertyChangeOutcome.State.APPLIED,retry.outcome().state());
            assertTrue(retry.loggerOwners().get(0).removedKeys().contains("logging.level.fixturegroup"));
            assertValues(context,20);
        }
    }

    @Test void unrelatedPropertySaveDoesNotReadBrokenLoggerGroupMetadata() throws Exception {
        Path file=file("nonlogging.properties","svc.timeout=10\nlogging.level.fixturegroup=DEBUG\n");
        try(var context=context(file,"dev")) {
            context.getBeanFactory().registerSingleton(org.springframework.boot.context.logging.LoggingApplicationListener.LOGGER_GROUPS_BEAN_NAME,
                    new org.springframework.boot.logging.LoggerGroups(Map.of("fixturegroup",List.of("fixture.member"))));
            context.getEnvironment().getPropertySources().addLast(new MapPropertySource("invalid-member",Map.of("logging.level.fixture.member","${missing.logger.level}")));
            var reload=new SpringPropertyFiles();Files.writeString(file,"svc.timeout=20\nlogging.level.fixturegroup=DEBUG\n");
            var result=reload.apply(file,List.of(context),Runnable::run);
            assertEquals(PropertyChangeOutcome.State.APPLIED,result.outcome().state());assertValues(context,20);
            assertTrue(result.loggerOwners().isEmpty(),result.loggerOwners().toString());
        }
    }

    private Path file(String name, String content) throws Exception {
        Path file = tmp.resolve(name); Files.writeString(file, content); return file;
    }
    private static AnnotationConfigApplicationContext context(Path file, String profile) throws Exception {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profile);
        PropertySourceLoader loader = file.toString().endsWith(".properties") ? new PropertiesPropertySourceLoader() : new YamlPropertySourceLoader();
        for (PropertySource<?> source : loader.load("fixture", new FileSystemResource(file))) {
            Object selector = source.getProperty("spring.config.activate.on-profile");
            if (selector == null || context.getEnvironment().acceptsProfiles(Profiles.of(selector.toString())))
                context.getEnvironment().getPropertySources().addFirst(source);
        }
        context.register(Config.class); context.refresh(); return context;
    }
    private static void applied(SpringPropertyFiles reload, Path file, AnnotationConfigApplicationContext context) {
        var result = reload.apply(file, List.of(context), Runnable::run);
        assertNotNull(result); assertEquals(PropertyChangeOutcome.State.APPLIED, result.outcome().state(), result.outcome().findings().toString());
    }
    private static void assertValues(AnnotationConfigApplicationContext context, int expected) {
        assertEquals(expected, context.getBean(Settings.class).getTimeout());
        assertEquals(expected, context.getBean(Config.class).timeout);
        assertEquals(String.valueOf(expected), context.getEnvironment().getProperty("svc.timeout"));
    }
    private static String documents(String base, String dev, String prod) {
        return "svc.timeout: " + base + "\n---\nspring.config.activate.on-profile: dev\nsvc.timeout: " + dev
                + "\n---\nspring.config.activate.on-profile: prod\nsvc.timeout: " + prod + "\n";
    }
}
