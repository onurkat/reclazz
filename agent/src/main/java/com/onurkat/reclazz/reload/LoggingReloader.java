/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies a changed logging level to the logging framework that is running.
 *
 * Turning a logger up is the most common reason a developer restarts a server,
 * and the least deserving: the change is one field on an object that is already
 * in memory. Both frameworks in use here expose it, Log4j2 through Configurator
 * and Logback through the logger itself, so the only real work is reaching them.
 *
 * They are found among the loaded classes rather than through a classloader we
 * name. Logging is initialised early and by whichever loader got there first,
 * the platform's in SAP Commerce and the application's in Spring Boot, and the
 * loaded-class list is the one place that sees all of them.
 */
public class LoggingReloader {

    private static final String LOG4J2_CONFIGURATOR =
            "org.apache.logging.log4j.core.config.Configurator";
    private static final String LOG4J2_LEVEL = "org.apache.logging.log4j.Level";
    private static final String LOGBACK_CONTEXT = "ch.qos.logback.classic.LoggerContext";
    private static final String LOGBACK_LEVEL = "ch.qos.logback.classic.Level";

    private final java.util.function.Function<String, Class<?>> classFinder;

    public LoggingReloader(Instrumentation instrumentation) {
        this.classFinder = name -> {
            if (instrumentation == null) return null;
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (name.equals(loaded.getName())) return loaded;
            }
            return null;
        };
    }

    /**
     * Test seam: a logging framework cannot be reached the same way outside a
     * server, where it was loaded by someone else's classloader.
     */
    LoggingReloader(Map<String, Class<?>> classes) {
        this.classFinder = classes::get;
    }

    /**
     * @param levels logger name to level, as the developer wrote them
     * @return the loggers whose level was set, in the order given
     */
    public java.util.List<String> applyLevels(Map<String, String> levels) {
        java.util.List<String> applied = new java.util.ArrayList<>();
        if (levels.isEmpty()) return applied;

        Class<?> configurator = find(LOG4J2_CONFIGURATOR);
        if (configurator != null) {
            for (Map.Entry<String, String> entry : levels.entrySet()) {
                if (setLog4j2Level(configurator, entry.getKey(), entry.getValue())) {
                    applied.add(entry.getKey());
                }
            }
            return applied;
        }

        Class<?> logbackContext = find(LOGBACK_CONTEXT);
        if (logbackContext != null) {
            for (Map.Entry<String, String> entry : levels.entrySet()) {
                if (setLogbackLevel(entry.getKey(), entry.getValue())) {
                    applied.add(entry.getKey());
                }
            }
        }
        return applied;
    }

    /**
     * The logger levels a save asks for, in the two spellings that matter here.
     *
     * SAP Commerce keeps them in its own properties rather than a log4j2.xml,
     * as a pair: one key names the logger and another gives its level. Spring
     * Boot writes {@code logging.level.<logger>} directly.
     *
     * The whole file is read but only the touched keys count, so editing an
     * unrelated property does not push every level in the file back over
     * whatever the console set at runtime. A changed {@code .name} counts too:
     * it points the level at a different logger.
     *
     * @param properties the file as saved
     * @param changedKeys the keys this save changed
     */
    public static Map<String, String> levelsIn(java.util.Properties properties,
                                               java.util.Collection<String> changedKeys) {
        Map<String, String> levels = new LinkedHashMap<>();

        for (String key : changedKeys) {
            if (!key.startsWith("logging.level.")) continue;
            String logger = key.substring("logging.level.".length());
            String level = properties.getProperty(key);
            if (!logger.isEmpty() && level != null) levels.put(logger, level.trim());
        }

        for (String key : changedKeys) {
            if (!key.startsWith("log4j2.logger.")) continue;

            String stem;
            if (key.endsWith(".level")) {
                stem = key.substring(0, key.length() - ".level".length());
            } else if (key.endsWith(".name")) {
                stem = key.substring(0, key.length() - ".name".length());
            } else {
                continue;
            }

            String logger = properties.getProperty(stem + ".name");
            String level = properties.getProperty(stem + ".level");
            if (logger != null && !logger.isBlank() && level != null && !level.isBlank()) {
                levels.put(logger.trim(), level.trim());
            }
        }
        return levels;
    }

    /**
     * Points the running logging context at a configuration file that has just
     * been edited, which covers what a level alone cannot: an appender, a
     * pattern, an additivity flag.
     *
     * Log4j2 can do this itself through {@code monitorInterval}, but only when
     * someone set it, and Logback's {@code scan} likewise. Doing it here means
     * the file the developer saved is the file that counts either way.
     *
     * @return the framework that took it, or null when nothing did
     */
    public String reconfigureFrom(java.nio.file.Path configFile) {
        Class<?> configurator = find(LOG4J2_CONFIGURATOR);
        if (configurator != null) {
            try {
                configurator.getMethod("reconfigure", java.net.URI.class)
                        .invoke(null, configFile.toUri());
                return "Log4j2";
            } catch (Throwable t) {
                StatusReporter.warn("Could not apply " + configFile.getFileName()
                        + ": " + describe(t));
                return null;
            }
        }

        Class<?> logbackContext = find(LOGBACK_CONTEXT);
        if (logbackContext == null) return null;
        try {
            Object context = find("org.slf4j.LoggerFactory")
                    .getMethod("getILoggerFactory").invoke(null);

            Class<?> joran = find("ch.qos.logback.classic.joran.JoranConfigurator");
            Object configuratorInstance = joran.getConstructor().newInstance();
            joran.getMethod("setContext", find("ch.qos.logback.core.Context"))
                    .invoke(configuratorInstance, context);

            // Without the reset every appender in the file is added a second
            // time, and each line is logged twice for as long as the server
            // runs.
            logbackContext.getMethod("reset").invoke(context);
            joran.getMethod("doConfigure", java.io.File.class)
                    .invoke(configuratorInstance, configFile.toFile());
            return "Logback";
        } catch (Throwable t) {
            StatusReporter.warn("Could not apply " + configFile.getFileName()
                    + ": " + describe(t));
            return null;
        }
    }

    private boolean setLog4j2Level(Class<?> configurator, String logger, String level) {
        try {
            Class<?> levelClass = find(LOG4J2_LEVEL);
            if (levelClass == null) return false;

            // toLevel with a fallback keeps a typo from silently turning a
            // logger off: an unknown name would otherwise resolve to null.
            Method toLevel = levelClass.getMethod("toLevel", String.class, levelClass);
            Object fallback = levelClass.getField("INFO").get(null);
            Object parsed = toLevel.invoke(null, level, fallback);

            configurator.getMethod("setLevel", String.class, levelClass)
                    .invoke(null, logger, parsed);
            return true;
        } catch (Throwable t) {
            StatusReporter.warn("Could not set the level of " + logger + ": " + describe(t));
            return false;
        }
    }

    private boolean setLogbackLevel(String logger, String level) {
        try {
            Class<?> levelClass = find(LOGBACK_LEVEL);
            Class<?> factory = find("org.slf4j.LoggerFactory");
            if (levelClass == null || factory == null) return false;

            Object parsed = levelClass.getMethod("toLevel", String.class, levelClass)
                    .invoke(null, level, levelClass.getField("INFO").get(null));
            Object slf4jLogger = factory.getMethod("getLogger", String.class).invoke(null, logger);
            slf4jLogger.getClass().getMethod("setLevel", levelClass).invoke(slf4jLogger, parsed);
            return true;
        } catch (Throwable t) {
            StatusReporter.warn("Could not set the level of " + logger + ": " + describe(t));
            return false;
        }
    }

    /** Whether a logging framework is here at all, so silence can be explained. */
    public boolean frameworkPresent() {
        return find(LOG4J2_CONFIGURATOR) != null || find(LOGBACK_CONTEXT) != null;
    }

    private Class<?> find(String name) {
        return classFinder.apply(name);
    }

    private static String describe(Throwable t) {
        Throwable cause = (t.getCause() != null) ? t.getCause() : t;
        return (cause.getMessage() != null)
                ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                : cause.getClass().getName();
    }
}
