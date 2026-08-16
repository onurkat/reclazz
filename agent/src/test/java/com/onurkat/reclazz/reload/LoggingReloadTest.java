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
 *   log4j2.logger.GeneracLoggingInterceptor.name  = com.generac...Interceptor
 *   log4j2.logger.GeneracLoggingInterceptor.level = DEBUG
 *
 * Read either key alone and you have half a statement. Read the whole file on
 * every save and you undo levels the console set at runtime.
 */
class LoggingReloadTest {

    @BeforeEach
    void reset() {
        StubConfigurator.calls.clear();
    }

    private static LoggingReloader log4j2() {
        return new LoggingReloader(Map.of(
                "org.apache.logging.log4j.core.config.Configurator", StubConfigurator.class,
                "org.apache.logging.log4j.Level", StubLevel.class));
    }

    @Test
    void aLevelIsSetOnTheRunningFramework() {
        List<String> applied = log4j2().applyLevels(Map.of("com.generac.b2b", "DEBUG"));

        assertEquals(List.of("com.generac.b2b"), applied);
        assertEquals(Map.of("com.generac.b2b", "DEBUG"), StubConfigurator.calls);
    }

    @Test
    void hybrisSpellsOneLevelAcrossTwoKeys() {
        Properties file = properties(
                "log4j2.logger.GeneracLoggingInterceptor.name",
                "com.generac.b2b.webclients.rest.logging.GeneracLoggingInterceptor",
                "log4j2.logger.GeneracLoggingInterceptor.level", "DEBUG");

        Map<String, String> levels = LoggingReloader.levelsIn(
                file, Set.of("log4j2.logger.GeneracLoggingInterceptor.level"));

        assertEquals(
                Map.of("com.generac.b2b.webclients.rest.logging.GeneracLoggingInterceptor", "DEBUG"),
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
        log4j2().applyLevels(Map.of("com.example", "DEBGU"));

        assertEquals("INFO", StubConfigurator.calls.get("com.example"));
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
        }
    }
}
