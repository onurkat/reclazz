/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Raising a logger is the cheapest thing a server can be asked to do and one of
 * the most common reasons it gets restarted anyway.
 *
 * The parsing is what carries the risk here. SAP Commerce does not keep levels
 * in a log4j2.xml at all, it keeps them in the same property files as
 * everything else, split across two keys that only mean something together:
 *
 *   log4j2.logger.ExampleLoggingInterceptor.name  = com.example...Interceptor
 *   log4j2.logger.ExampleLoggingInterceptor.level = DEBUG
 *
 * Read either key alone and you have half a statement. Read the whole file on
 * every save and you undo levels the console set at runtime.
 */
class LoggingReloadTest {

    @BeforeEach
    void reset() {
        StubConfigurator.calls.clear();
        StubContext.updates = 0; StubContext.failAt = 0; StubContext.failRestore = false;
        com.onurkat.reclazz.ui.RestartLedger.clear();
        StubManager.selected = new StubSelector();
        StubManager.afterInventory = null;
        StubManager.contexts = new java.util.ArrayList<>(List.of(new StubContext()));
    }

    private static LoggingReloader log4j2() {
        return new LoggingReloader(framework());
    }

    private static Map<String, Class<?>> framework() {
        Map<String, Class<?>> types = new java.util.HashMap<>(Map.of(
                "org.apache.logging.log4j.core.config.Configurator", StubConfigurator.class,
                "org.apache.logging.log4j.Level", StubLevel.class,
                "org.apache.logging.log4j.LogManager", StubManager.class,
                "org.apache.logging.log4j.core.impl.Log4jContextFactory", StubFactory.class,
                "org.apache.logging.log4j.core.selector.ContextSelector", StubSelector.class,
                "org.apache.logging.log4j.core.LoggerContext", StubContext.class,
                "org.apache.logging.log4j.core.config.Configuration", StubConfiguration.class,
                "org.apache.logging.log4j.core.config.LoggerConfig", StubLoggerConfig.class,
                "org.apache.logging.log4j.core.Logger", StubLogger.class));
        types.put("org.apache.logging.log4j.core.selector.ClassLoaderContextSelector", StubSelector.class);
        types.put("org.apache.logging.log4j.util.Lazy", StubLazy.class);
        types.put("de.hybris.platform.util.logging.log4j2.HybrisLoggerContext", StubContext.class);
        types.put("de.hybris.platform.util.logging.log4j2.HybrisClassLoaderContextSelector", StubSelector.class);
        types.put("org.apache.logging.log4j.core.config.properties.PropertiesConfiguration", StubConfiguration.class);
        types.put("org.apache.logging.log4j.core.config.ConfigurationSource", StubSource.class);
        return types;
    }

    @Test
    void updatesUniqueDefaultOwner() {
        StubContext hidden = new StubContext();
        StubManager.selected.defaultLoggerContext.current = hidden;
        StubLogger live = hidden.getLogger("com.example.target");
        assertEquals(List.of("com.example.target"), log4j2().applyLevels(Map.of("com.example.target", "DEBUG")));
        assertEquals("DEBUG", live.level.toString());
        assertTrue(StubManager.contexts.get(0).configuration.entries.isEmpty());
    }

    @Test
    void refusesDefaultAndListedOwnersWithoutMutation() {
        StubContext hidden = new StubContext(), listed = StubManager.contexts.get(0);
        StubManager.selected.defaultLoggerContext.current = hidden;
        hidden.getLogger("com.example.target");
        listed.getLogger("com.example.target");
        refused("ambiguous");
        assertTrue(hidden.configuration.entries.isEmpty());
        assertTrue(listed.configuration.entries.isEmpty());
    }

    @Test
    void deduplicatesDefaultAlsoListed() {
        StubManager.selected.defaultLoggerContext.current = StubManager.contexts.get(0);
        assertEquals(List.of("com.example.target"), log4j2().applyLevels(Map.of("com.example.target", "DEBUG")));
    }

    @Test
    void doesNotInitializeUnusedDefault() {
        StubLazy unused = StubManager.selected.defaultLoggerContext;
        assertEquals(List.of("com.example.target"), log4j2().applyLevels(Map.of("com.example.target", "DEBUG")));
        assertEquals(0, unused.reads);
        assertFalse(unused.isInitialized());
    }

    @Test
    void refusesUnknownSelectorShape() {
        StubManager.selected = new CustomSelector();
        refused("selector shape");
        assertTrue(StubManager.contexts.get(0).configuration.entries.isEmpty());
    }

    @Test
    void refusesInaccessibleDefault(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary) throws Exception {
        // Export public selector APIs, but deliberately do not open its package.
        // This exercises the real JPMS access failure, not a mocked refusal.
        var sources = java.nio.file.Files.createDirectories(temporary.resolve("source"));
        java.nio.file.Files.writeString(sources.resolve("module-info.java"), "module fixture.selector { exports fixture; }");
        java.nio.file.Files.writeString(sources.resolve("Lazy.java"), """
                package fixture;
                public class Lazy {
                    public boolean isInitialized() { return false; }
                    public Object value() { throw new AssertionError("must not initialize"); }
                }
                """);
        java.nio.file.Files.writeString(sources.resolve("Selector.java"), """
                package fixture;
                public class Selector {
                    private final Lazy defaultLoggerContext = new Lazy();
                    public java.util.List<Object> getLoggerContexts() { return java.util.List.of(); }
                    public Object getContext(String name, ClassLoader loader, boolean current) { throw new AssertionError(); }
                    public Object getContext(String name, ClassLoader loader, boolean current, java.net.URI uri) { throw new AssertionError(); }
                    public Object getContext(String name, ClassLoader loader, java.util.Map.Entry<String, Object> entry, boolean current, java.net.URI uri) { throw new AssertionError(); }
                    protected Object getDefault() { throw new AssertionError(); }
                }
                """);
        var output = java.nio.file.Files.createDirectories(temporary.resolve("classes"));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d", output.toString(),
                sources.resolve("module-info.java").toString(), sources.resolve("Lazy.java").toString(), sources.resolve("Selector.java").toString()));
        var configuration = ModuleLayer.boot().configuration().resolve(java.lang.module.ModuleFinder.of(output),
                java.lang.module.ModuleFinder.of(), Set.of("fixture.selector"));
        var layer = ModuleLayer.boot().defineModulesWithOneLoader(configuration, getClass().getClassLoader());
        var loader = layer.findLoader("fixture.selector");
        var selectorType = loader.loadClass("fixture.Selector");
        ModularFactory.selector = selectorType.getConstructor().newInstance();
        var types = new java.util.HashMap<>(framework());
        types.put("org.apache.logging.log4j.LogManager", ModularManager.class);
        types.put("org.apache.logging.log4j.core.impl.Log4jContextFactory", ModularFactory.class);
        types.put("org.apache.logging.log4j.core.selector.ContextSelector", selectorType);
        types.put("org.apache.logging.log4j.core.selector.ClassLoaderContextSelector", selectorType);
        types.put("org.apache.logging.log4j.util.Lazy", loader.loadClass("fixture.Lazy"));
        refused(new LoggingReloader(types), "inaccessible default context");
        assertTrue(StubManager.contexts.get(0).configuration.entries.isEmpty());
    }
    public static final class ModularManager {
        public static ModularFactory getFactory() { return new ModularFactory(); }
    }
    public static final class ModularFactory {
        static Object selector;
        public Object getSelector() { return selector; }
    }

    @Test
    void refusesInvalidDefaultValue() {
        StubManager.selected.defaultLoggerContext.current = new Object();
        refused("default context");
        assertTrue(StubManager.contexts.get(0).configuration.entries.isEmpty());
    }

    @Test
    void refusesInventoryChangedBeforeMutation() {
        StubContext listed = StubManager.contexts.get(0);
        listed.getLogger("com.example.target");
        StubManager.afterInventory = () -> StubManager.contexts.add(new StubContext());
        refused("inventory changed");
        assertTrue(listed.configuration.entries.isEmpty());
    }

    @Test
    void updatesLiveOwnerInsteadOfImplicitCallerContext() {
        StubContext caller = StubManager.contexts.get(0);
        StubContext owner = new StubContext();
        StubManager.contexts.add(owner);
        StubLogger live = owner.getLogger("com.example.target");
        assertEquals(List.of("com.example.target"),
                log4j2().applyLevels(Map.of("com.example.target", "DEBUG")));
        assertEquals("DEBUG", live.getLevel().toString());
        assertTrue(caller.configuration.entries.isEmpty(), "caller context must not be mutated");
    }

    @Test
    void ambiguousOwnersAreRefusedWithoutMutation() {
        StubContext first = StubManager.contexts.get(0), second = new StubContext();
        StubManager.contexts.add(second);
        first.getLogger("com.example.target");
        second.configuration.addLogger("com.example.target",
                new StubLoggerConfig("com.example.target", StubLevel.INFO, false));
        refused("ambiguous");
        assertTrue(first.configuration.entries.isEmpty());
        assertEquals("INFO", second.configuration.entries.get("com.example.target").level.toString());
    }

    @Test
    void noExistingContextIsRefused() {
        StubManager.contexts.clear();
        refused("no existing");
    }

    @Test
    void multipleOwnerlessContextsAreRefused() {
        StubManager.contexts.add(new StubContext());
        refused("owner");
        assertTrue(StubManager.contexts.stream().allMatch(c -> c.configuration.entries.isEmpty()));
    }

    @Test
    void inheritedTargetDoesNotChangeParentRootOrControl() {
        StubContext context = StubManager.contexts.get(0);
        StubLoggerConfig parent = new StubLoggerConfig("com.example", StubLevel.INFO, false);
        context.configuration.addLogger("com.example", parent);
        StubLogger live = context.getLogger("com.example.target");
        StubLogger control = context.getLogger("com.example.control");
        assertEquals(List.of("com.example.target"), log4j2().applyLevels(Map.of("com.example.target", "DEBUG")));
        assertEquals("DEBUG", live.level.toString());
        assertEquals("INFO", control.level.toString());
        assertSame(parent, context.configuration.entries.get("com.example"));
        assertSame(StubLevel.INFO, parent.level);
        assertSame(StubLevel.INFO, context.configuration.root.level);
        assertTrue(context.configuration.entries.get("com.example.target").additive);
    }

    @Test
    void exactConfigurationKeepsItsIdentityAndAttachments() {
        StubContext context = StubManager.contexts.get(0);
        StubLoggerConfig entry = new StubLoggerConfig("com.example.target", StubLevel.INFO, false);
        context.configuration.addLogger("com.example.target", entry);
        Object attachment = entry.attachment;
        assertEquals(List.of("com.example.target"), log4j2().applyLevels(Map.of("com.example.target", "DEBUG")));
        assertSame(entry, context.configuration.entries.get("com.example.target"));
        assertSame(attachment, entry.attachment);
        assertFalse(entry.additive);
    }

    @Test
    void staleLiveLoggerDoesNotEarnAppliedClaim() {
        StubContext context = StubManager.contexts.get(0);
        context.getLogger("com.example.target");
        context.skipUpdate = true;
        refused("read-back");
    }

    @Test
    void configurationReplacementDoesNotEarnAppliedClaim() {
        StubContext context = StubManager.contexts.get(0);
        context.replaceOnUpdate = true;
        refused("configuration");
    }

    @Test
    void duplicateContextIdentitiesDoNotCreateAmbiguity() {
        StubManager.contexts.add(StubManager.contexts.get(0));
        assertEquals(List.of("com.example.target"), log4j2().applyLevels(Map.of("com.example.target", "DEBUG")));
    }

    @Test
    void blankLoggerDoesNotChangeRoot() {
        assertTrue(log4j2().applyLevels(Map.of("", "DEBUG")).isEmpty());
        assertSame(StubLevel.INFO, StubManager.contexts.get(0).configuration.root.level);
    }

    private static void refused(String reason) {
        refused(log4j2(), reason);
    }
    private static void refused(LoggingReloader reloader, String reason) {
        java.util.List<String> events = new java.util.ArrayList<>();
        com.onurkat.reclazz.ui.StatusReporter.StatusListener listener =
                (level, message) -> events.add(level + ":" + message);
        com.onurkat.reclazz.ui.StatusReporter.addListener(listener);
        try {
            assertTrue(reloader.applyLevels(Map.of("com.example.target", "DEBUG")).isEmpty());
            assertTrue(events.stream().anyMatch(e -> e.startsWith("WARN:")
                    && e.contains("com.example.target") && e.contains(reason)), events.toString());
            assertTrue(events.stream().noneMatch(e -> e.contains("Logger level applied")), events.toString());
        } finally {
            com.onurkat.reclazz.ui.StatusReporter.removeListener(listener);
        }
    }

    @Test
    void aLevelIsSetOnTheRunningFramework() {
        List<String> applied = log4j2().applyLevels(Map.of("com.example.b2b", "DEBUG"));

        assertEquals(List.of("com.example.b2b"), applied);
        assertEquals("DEBUG", StubManager.contexts.get(0).getLogger("com.example.b2b").getLevel().toString());
    }

    @Test
    void hybrisSpellsOneLevelAcrossTwoKeys() {
        Properties file = properties(
                "log4j2.logger.ExampleLoggingInterceptor.name",
                "com.example.b2b.webclients.rest.logging.ExampleLoggingInterceptor",
                "log4j2.logger.ExampleLoggingInterceptor.level", "DEBUG");

        Map<String, String> levels = LoggingReloader.levelsIn(
                file, Set.of("log4j2.logger.ExampleLoggingInterceptor.level"));

        assertEquals(
                Map.of("com.example.b2b.webclients.rest.logging.ExampleLoggingInterceptor", "DEBUG"),
                levels,
                "the level key names no logger; the .name beside it does");
    }

    /**
     * Pointing an existing level at a different class is the same edit from the
     * developer's side, and the level key does not move.
     */
    @Test
    void renamingTheLoggerCountsAsAChange() {
        Properties file = properties(
                "log4j2.logger.probe.name", "com.example.After",
                "log4j2.logger.probe.level", "TRACE");

        Map<String, String> levels =
                LoggingReloader.levelsIn(file, Set.of("log4j2.logger.probe.name"));

        assertEquals(Map.of("com.example.After", "TRACE"), levels);
    }

    @Test
    void springBootWritesTheLoggerIntoTheKey() {
        Properties file = properties("logging.level.com.example.demo", "TRACE");

        Map<String, String> levels =
                LoggingReloader.levelsIn(file, Set.of("logging.level.com.example.demo"));

        assertEquals(Map.of("com.example.demo", "TRACE"), levels);
    }

    /**
     * A developer who raises a logger from the HAC console and then edits an
     * unrelated property should not find the level back where the file left it.
     */
    @Test
    void untouchedLevelsAreLeftAlone() {
        Properties file = properties(
                "log4j2.logger.hmc.name", "de.hybris.platform.servicelayer.hmc",
                "log4j2.logger.hmc.level", "warn",
                "logging.level.com.example", "INFO");

        Map<String, String> levels =
                LoggingReloader.levelsIn(file, Set.of("db.url", "tomcat.maxthreads"));

        assertTrue(levels.isEmpty(), "nothing about logging changed in this save");
    }

    /**
     * Level.toLevel returns null for a name it does not know, and Log4j2 reads
     * that as OFF. A typo would silence the logger the developer was trying to
     * open, which is the opposite of the request.
     */
    @Test
    void anUnknownLevelFallsBackInsteadOfSilencingTheLogger() {
        StubContext context = StubManager.contexts.get(0);
        context.configuration.addLogger("com.example",
                new StubLoggerConfig("com.example", StubLevel.toLevel("DEBUG", StubLevel.INFO), false));
        context.getLogger("com.example");
        assertEquals(List.of("com.example"), log4j2().applyLevels(Map.of("com.example", "DEBGU")));

        assertEquals("INFO", StubManager.contexts.get(0).getLogger("com.example").getLevel().toString());
    }

    @Test
    void withoutALoggingFrameworkNothingIsClaimed() {
        LoggingReloader none = new LoggingReloader(Map.of());

        assertFalse(none.frameworkPresent());
        assertTrue(none.applyLevels(Map.of("com.example", "DEBUG")).isEmpty(),
                "reporting a level as applied when nothing was reached is worse than silence");
    }

    private static Properties properties(String... keyThenValue) {
        Properties p = new Properties();
        for (int i = 0; i < keyThenValue.length; i += 2) {
            p.setProperty(keyThenValue[i], keyThenValue[i + 1]);
        }
        return p;
    }

    public static final class StubSource {
        String text; java.net.URI uri; int resets; Runnable onReset;
        final java.io.InputStream original;
        StubSource(String text) { this.text = text; this.original = new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)); }
        public java.net.URI getURI() { return uri; }
        public java.io.File getFile() { return null; }
        public java.net.URL getURL() { return null; }
        public StubSource resetInputStream() {
            resets++; if (onReset != null) { Runnable r=onReset; onReset=null; r.run(); }
            return new StubSource(text);
        }
        public java.io.InputStream getInputStream() { return original; }
    }
    private static List<StubContext> group() {
        StubContext first=StubManager.contexts.get(0), second=new StubContext();
        StubManager.selected.defaultLoggerContext.current=second;
        for (StubContext c:List.of(first,second)) {
            c.configuration.addLogger("com.example.target",new StubLoggerConfig("com.example.target",StubLevel.INFO,false));
            c.getLogger("com.example.target");
        }
        return List.of(first,second);
    }
    private static List<String> applyGroup(String level) {
        return log4j2().applyPropertyLevels(properties("log4j2.logger.target.name","com.example.target",
                "log4j2.logger.target.level",level), List.of("log4j2.logger.target.level"));
    }
    @Test void sourceBackedGroupUpdatesAndRestoresBothMembers() throws Exception {
        var members=group();var entries=members.stream().map(c->c.configuration.entries.get("com.example.target")).toList();
        assertEquals(List.of("com.example.target"),applyGroup("DEBUG"));
        for(int i=0;i<2;i++) {var c=members.get(i);assertSame(entries.get(i),c.configuration.entries.get("com.example.target"));
            assertEquals("DEBUG",c.getLogger("com.example.target").getLevel().toString());assertFalse(entries.get(i).additive);
            assertSame(StubLevel.INFO,c.configuration.root.level);assertEquals((int)'l',c.configuration.source.original.read());}
        assertEquals(List.of("com.example.target"),applyGroup("INFO"));
        for(var c:members)assertEquals("INFO",c.getLogger("com.example.target").getLevel().toString());
    }
    @Test void secondMemberFailureRollsBackBothAndReturnsNoAppliedLogger() {
        var members=group();StubContext.failAt=2;
        assertTrue(applyGroup("DEBUG").isEmpty());assertEquals(4,StubContext.updates);
        for(var c:members){assertSame(StubLevel.INFO,c.configuration.entries.get("com.example.target").level);assertSame(StubLevel.INFO,c.getLogger("com.example.target").level);}
        assertEquals(0,com.onurkat.reclazz.ui.RestartLedger.size());
    }
    @Test void rollbackFailureReportsErrorAndRestartLedger() {
        group();StubContext.failAt=2;StubContext.failRestore=true;
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();var old=System.out;
        try {System.setOut(new java.io.PrintStream(bytes));assertTrue(applyGroup("DEBUG").isEmpty());}finally{System.setOut(old);}
        assertTrue(bytes.toString().contains("rollback failed"));assertFalse(bytes.toString().contains("[OK]"));
        assertTrue(com.onurkat.reclazz.ui.RestartLedger.digest().toString().contains("logger level partly applied; restart to reset"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"missing","mismatch","duplicate","invalid","file","oversized","changed"})
    void refusesUnprovenSourceBeforeAnyMutation(String mode) {
        var c=group().get(1);var source=c.configuration.source;
        switch(mode){
            case "missing" -> source.text="logger.target.name=com.example.target\n";
            case "mismatch" -> source.text="logger.target.name=com.example.other\nlogger.target.level=INFO\n";
            case "duplicate" -> source.text+="logger.other.name=com.example.target\n";
            case "invalid" -> source.text="logger.target.name=com.example.target\nlogger.target.level=INVALID\n";
            case "file" -> source.uri=java.net.URI.create("file:/not-read");
            case "oversized" -> source.text+="#"+"x".repeat(1024*1024);
            case "changed" -> source.onReset=()->c.configuration.source=new StubSource(source.text);
        }
        assertTrue(applyGroup("DEBUG").isEmpty());assertEquals(0,StubContext.updates);
    }
    @Test void readbackFailureRollsBackWithoutSuccess() {
        var members=group();members.get(1).skipUpdate=true;
        assertTrue(applyGroup("DEBUG").isEmpty());
        for(var c:members){assertSame(StubLevel.INFO,c.configuration.entries.get("com.example.target").level);assertSame(StubLevel.INFO,c.getLogger("com.example.target").level);}
        assertEquals(0,com.onurkat.reclazz.ui.RestartLedger.size());
    }
    @Test void rollbackRemovesNewEntryAndPreservesInheritedLevel() {
        var members=group();members.get(0).configuration.removeLogger("com.example.target");StubContext.failAt=2;
        assertTrue(applyGroup("DEBUG").isEmpty());assertFalse(members.get(0).configuration.entries.containsKey("com.example.target"));
        for(var c:members)assertSame(StubLevel.INFO,c.getLogger("com.example.target").level);
        assertEquals(0,com.onurkat.reclazz.ui.RestartLedger.size());
    }
    @Test void unownedContextRemainsUntouchedByGroup() {
        var members=group();var unowned=new StubContext();StubManager.contexts.add(unowned);
        assertEquals(List.of("com.example.target"),applyGroup("DEBUG"));
        assertTrue(unowned.configuration.entries.isEmpty());assertTrue(unowned.loggers.isEmpty());assertSame(StubLevel.INFO,unowned.configuration.root.level);
    }
    @Test void liveOwnerWithoutDeclarationRefusesGroup() {
        group();var unrelated=new StubContext();unrelated.configuration.source.text="rootLogger.level=INFO\n";
        unrelated.getLogger("com.example.target");StubManager.contexts.add(unrelated);
        assertTrue(applyGroup("DEBUG").isEmpty());assertEquals(0,StubContext.updates);
    }
    @Test void unknownConfigurationShapeRefusesGroup() {
        group();var types=framework();types.put("org.apache.logging.log4j.core.config.properties.PropertiesConfiguration",Object.class);
        assertTrue(new LoggingReloader(types).applyPropertyLevels(properties("log4j2.logger.target.name","com.example.target",
                "log4j2.logger.target.level","DEBUG"),List.of("log4j2.logger.target.level")).isEmpty());
        assertEquals(0,StubContext.updates);
    }
    @Test void plainAndBootLevelsDoNotAuthorizeSourceGroup() {
        group();assertTrue(log4j2().applyLevels(Map.of("com.example.target","DEBUG")).isEmpty());
        assertTrue(log4j2().applyPropertyLevels(properties("logging.level.com.example.target","DEBUG"),List.of("logging.level.com.example.target")).isEmpty());
        assertEquals(0,StubContext.updates);
    }
    @Test void duplicateSavedAliasesDoNotAuthorizeSourceGroup() {
        group();assertTrue(log4j2().applyPropertyLevels(properties("log4j2.logger.target.name","com.example.target",
                "log4j2.logger.target.level","DEBUG","log4j2.logger.other.name","com.example.target"),List.of("log4j2.logger.target.level")).isEmpty());
        assertEquals(0,StubContext.updates);
    }

    /** Stands in for org.apache.logging.log4j.Level. */
    public static final class StubLevel {
        public static final StubLevel INFO = new StubLevel("INFO");
        private static final Set<String> KNOWN = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF");

        private final String name;

        private StubLevel(String name) {
            this.name = name;
        }

        public static StubLevel toLevel(String name, StubLevel fallback) {
            return (name != null && KNOWN.contains(name.toUpperCase())) ? new StubLevel(name.toUpperCase()) : fallback;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Stands in for org.apache.logging.log4j.core.config.Configurator. */
    public static final class StubConfigurator {
        static final Map<String, String> calls = new java.util.LinkedHashMap<>();

        public static void setLevel(String logger, StubLevel level) {
            calls.put(logger, level.toString());
            StubContext caller = StubManager.contexts.get(0);
            caller.configuration.addLogger(logger, new StubLoggerConfig(logger, level, true));
            caller.updateLoggers();
        }
    }
    public static final class StubManager {
        static java.util.List<StubContext> contexts;
        static StubSelector selected;
        static Runnable afterInventory;
        static final StubFactory FACTORY = new StubFactory();
        public static StubFactory getFactory() { return FACTORY; }
    }
    public static final class StubFactory {
        public StubSelector getSelector() { return StubManager.selected; }
    }
    public static class StubSelector {
        private final StubLazy defaultLoggerContext = new StubLazy();
        public List<StubContext> getLoggerContexts() {
            List<StubContext> result = List.copyOf(StubManager.contexts);
            if (StubManager.afterInventory != null) {
                Runnable action = StubManager.afterInventory;
                StubManager.afterInventory = null;
                action.run();
            }
            return result;
        }
        public StubContext getContext(String name, ClassLoader loader, boolean current) { throw new AssertionError("creating lookup"); }
        public StubContext getContext(String name, ClassLoader loader, boolean current, java.net.URI uri) { throw new AssertionError("creating lookup"); }
        public StubContext getContext(String name, ClassLoader loader, Map.Entry<String, Object> entry, boolean current, java.net.URI uri) { throw new AssertionError("creating lookup"); }
        protected StubContext getDefault() { throw new AssertionError("creating lookup"); }
    }
    public static final class CustomSelector extends StubSelector {
        @Override protected StubContext getDefault() { return new StubContext(); }
    }
    public static final class StubLazy {
        Object current;
        int reads;
        public boolean isInitialized() { return current != null; }
        public Object value() { reads++; return current; }
    }
    public static final class StubContext {
        StubConfiguration configuration = new StubConfiguration();
        final Map<String, StubLogger> loggers = new java.util.LinkedHashMap<>();
        boolean skipUpdate, replaceOnUpdate;
        static int updates, failAt; static boolean failRestore;
        public StubConfiguration getConfiguration() { return configuration; }
        public java.util.Collection<StubLogger> getLoggers() { return loggers.values(); }
        public StubLogger getLogger(String name) {
            return loggers.computeIfAbsent(name, n -> new StubLogger(this, n));
        }
        public void updateLoggers() {
            updates++;
            if (updates == failAt || (failRestore && updates > failAt && failAt > 0)) throw new IllegalStateException("injected update failure");
            if (replaceOnUpdate) configuration = new StubConfiguration();
            if (!skipUpdate) loggers.values().forEach(l -> l.level = configuration.getLoggerConfig(l.name).level);
        }
    }
    public static final class StubConfiguration {
        StubSource source = new StubSource("logger.target.name=com.example.target\nlogger.target.level=INFO\n");
        public StubSource getConfigurationSource() { return source; }
        public void removeLogger(String name) { entries.remove(name); }
        final Map<String, StubLoggerConfig> entries = new java.util.LinkedHashMap<>();
        final StubLoggerConfig root = new StubLoggerConfig("", StubLevel.INFO, true);
        public Map<String, StubLoggerConfig> getLoggers() { return entries; }
        public void addLogger(String name, StubLoggerConfig entry) { entries.put(name, entry); }
        public StubLoggerConfig getLoggerConfig(String name) {
            String candidate = name;
            while (!candidate.isEmpty()) {
                if (entries.containsKey(candidate)) return entries.get(candidate);
                int dot = candidate.lastIndexOf('.');
                candidate = dot < 0 ? "" : candidate.substring(0, dot);
            }
            return root;
        }
    }
    public static final class StubLoggerConfig {
        StubLevel level;
        final boolean additive;
        final Object attachment = new Object();
        public StubLoggerConfig(String name, StubLevel level, boolean additive) {
            this.level = level; this.additive = additive;
        }
        public void setLevel(StubLevel value) { level = value; }
        public StubLevel getLevel() { return level; }
    }
    public static final class StubLogger {
        final StubContext context;
        final String name;
        StubLevel level;
        StubLogger(StubContext context, String name) {
            this.context = context; this.name = name;
            level = context.configuration.getLoggerConfig(name).level;
        }
        public String getName() { return name; }
        public StubContext getContext() { return context; }
        public StubLevel getLevel() { return level; }
    }

}
