/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies a changed logging level to the logging framework that is running.
 *
 * Turning a logger up is the most common reason a developer restarts a server,
 * and the least deserving: the change is one field on an object that is already
 * in memory. Log4j2 exposes it on the owning configuration
 * and Logback on the logger itself. The owner must be resolved before mutation.
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
    private final java.util.function.Supplier<Class<?>[]> loadedClasses;
    private final Map<String, Class<?>> testClasses;

    public LoggingReloader(Instrumentation instrumentation) {
        this.testClasses = null;
        this.loadedClasses = () -> instrumentation == null ? new Class<?>[0] : instrumentation.getAllLoadedClasses();
        this.classFinder = name -> {
            for (Class<?> loaded : loadedClasses.get()) {
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
        this.testClasses = Map.copyOf(classes);
        this.loadedClasses = () -> classes.values().toArray(Class<?>[]::new);
    }

    /**
     * @param levels logger name to level, as the developer wrote them
     * @return the loggers whose level was set, in the order given
     */
    public java.util.List<String> applyLevels(Map<String, String> levels) {
        return applyLevels(levels, Map.of());
    }

    /** Preserve the saved SAP alias as authority; plain level maps never authorize a group. */
    public java.util.List<String> applyPropertyLevels(java.util.Properties properties, Collection<String> changedKeys) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (String key : changedKeys) {
            if (!key.startsWith("log4j2.logger.") || !(key.endsWith(".name") || key.endsWith(".level"))) continue;
            String stem = key.substring(0, key.lastIndexOf('.'));
            String name = properties.getProperty(stem + ".name", "").trim();
            if (name.isEmpty() || changedKeys.contains("logging.level." + name)) continue;
            long count = properties.stringPropertyNames().stream().filter(k -> k.startsWith("log4j2.logger.")
                    && k.endsWith(".name") && name.equals(properties.getProperty(k).trim())).count();
            if (count == 1) aliases.put(name, stem.substring("log4j2.".length()));
        }
        return applyLevels(levelsIn(properties, changedKeys), aliases);
    }

    private java.util.List<String> applyLevels(Map<String, String> levels, Map<String, String> aliases) {
        java.util.List<String> applied = new java.util.ArrayList<>();
        if (levels.isEmpty()) return applied;

        Class<?> configurator = find(LOG4J2_CONFIGURATOR);
        if (configurator != null) {
            for (Map.Entry<String, String> entry : levels.entrySet()) {
                if (setLog4j2Level(entry.getKey(), entry.getValue(), aliases.get(entry.getKey()))) {
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

    private boolean setLog4j2Level(String logger, String level, String alias) {
        try {
            if (logger == null || logger.isBlank()) throw new IllegalStateException("blank logger name");
            // Discover every loaded framework copy before touching any context. A
            // class from one loader must never be paired with another copy's Level.
            List<Class<?>> frameworks = new ArrayList<>();
            if (testClasses != null) {
                frameworks.add(find(LOG4J2_CONFIGURATOR));
            } else {
                IdentityHashMap<ClassLoader, Boolean> loaders = new IdentityHashMap<>();
                for (Class<?> loaded : loadedClasses.get()) {
                    if ((LOG4J2_CONFIGURATOR.equals(loaded.getName())
                            || "org.apache.logging.log4j.core.LoggerContext".equals(loaded.getName()))
                            && loaders.put(loaded.getClassLoader(), Boolean.TRUE) == null) {
                        frameworks.add(loaded);
                    }
                }
            }
            IdentityHashMap<Object, ContextTarget> contexts = new IdentityHashMap<>();
            List<Inventory> inventories = new ArrayList<>();
            for (Class<?> framework : frameworks) {
                Inventory inventory = inventory(new Log4jApi(framework.getClassLoader()), logger);
                inventories.add(inventory);
                contexts.putAll(inventory.contexts);
            }
            List<ContextTarget> owners = contexts.values().stream().filter(t -> t.owner).toList();
            if (owners.size() > 1) {
                if (alias == null) throw new IllegalStateException("ambiguous logger owners in " + owners.size() + " contexts");
                return applySourceGroup(logger, level, alias, owners, inventories);
            }
            if (contexts.isEmpty()) throw new IllegalStateException("no existing Log4j2 context");
            if (owners.isEmpty() && contexts.size() != 1) {
                throw new IllegalStateException("no unique owner among " + contexts.size() + " contexts");
            }
            ContextTarget target = owners.isEmpty() ? contexts.values().iterator().next() : owners.get(0);
            Log4jApi api = target.api;
            synchronized (target.context) {
                for (Inventory before : inventories) {
                    if (!before.sameAs(inventory(before.api, logger))) {
                        throw new IllegalStateException("listed contexts plus selector default inventory changed during owner selection");
                    }
                }
                if (api.context.getMethod("getConfiguration").invoke(target.context) != target.configuration) {
                    throw new IllegalStateException("configuration changed during owner selection");
                }
                Object parsed = api.level.getMethod("toLevel", String.class, api.level)
                        .invoke(null, level, api.level.getField("INFO").get(null));
                Map<?, ?> entries = (Map<?, ?>) api.configuration.getMethod("getLoggers").invoke(target.configuration);
                Object exact = entries.get(logger);
                if (exact == null) {
                    exact = api.loggerConfig.getConstructor(String.class, api.level, boolean.class)
                            .newInstance(logger, parsed, true);
                    api.configuration.getMethod("addLogger", String.class, api.loggerConfig)
                            .invoke(target.configuration, logger, exact);
                } else {
                    api.loggerConfig.getMethod("setLevel", api.level).invoke(exact, parsed);
                }
                api.context.getMethod("updateLoggers").invoke(target.context);
                if (api.context.getMethod("getConfiguration").invoke(target.context) != target.configuration) {
                    throw new IllegalStateException("configuration changed while applying level");
                }
                Map<?, ?> after = (Map<?, ?>) api.configuration.getMethod("getLoggers").invoke(target.configuration);
                if (after.get(logger) != exact || !parsed.equals(api.loggerConfig.getMethod("getLevel").invoke(exact))) {
                    throw new IllegalStateException("configuration level read-back mismatch");
                }
                List<Object> live = new ArrayList<>(target.live);
                live.add(api.context.getMethod("getLogger", String.class).invoke(target.context, logger));
                for (Object instance : live) {
                    if (api.logger.getMethod("getContext").invoke(instance) != target.context
                            || !parsed.equals(api.logger.getMethod("getLevel").invoke(instance))) {
                        throw new IllegalStateException("live logger level read-back mismatch");
                    }
                }
                if (api.context.getMethod("getConfiguration").invoke(target.context) != target.configuration) {
                    throw new IllegalStateException("configuration changed during read-back");
                }
                return true;
            }
        } catch (Throwable t) {
            StatusReporter.warn("Could not set the level of " + logger + " (listed contexts plus selector default): " + describe(t));
            return false;
        }
    }

    private static final Object SOURCE_GROUP_LOCK = new Object();
    private static final int MAX_SOURCE_BYTES = 1024 * 1024;
    private static final String PARTIAL_LEVEL = "logger level partly applied; restart to reset";

    private record SourceProof(Object source, java.util.Properties declaration, Object exact) { }
    private record Previous(ContextTarget target, Object exact, Object level, Map<Object,Object> liveLevels) { }

    private SourceProof sourceProof(ContextTarget target, String name, String alias) throws Exception {
        Log4jApi a = target.api;
        ClassLoader loader = a.context.getClassLoader();
        Class<?> sapContext = a.type("de.hybris.platform.util.logging.log4j2.HybrisLoggerContext", loader);
        Class<?> sapSelector = a.type("de.hybris.platform.util.logging.log4j2.HybrisClassLoaderContextSelector", loader);
        Object factory = a.manager.getMethod("getFactory").invoke(null);
        Object selector = a.factory.getMethod("getSelector").invoke(factory);
        Class<?> propertiesConfig = a.type("org.apache.logging.log4j.core.config.properties.PropertiesConfiguration", loader);
        Class<?> sourceType = a.type("org.apache.logging.log4j.core.config.ConfigurationSource", loader);
        if (target.context.getClass() != sapContext || selector.getClass() != sapSelector
                || target.configuration.getClass() != propertiesConfig) throw new IllegalStateException("unproven SAP property owner shape");
        Object source = a.configuration.getMethod("getConfigurationSource").invoke(target.configuration);
        if (source == null || source.getClass() != sourceType) throw new IllegalStateException("unknown property source");
        for (String accessor : List.of("getURI", "getFile", "getURL")) {
            if (sourceType.getMethod(accessor).invoke(source) != null) throw new IllegalStateException("file/XML property source is not a memory declaration");
        }
        Object copy = sourceType.getMethod("resetInputStream").invoke(source);
        if (copy == null || copy == source || copy.getClass() != sourceType) throw new IllegalStateException("missing independent memory source");
        java.util.Properties declared = new java.util.Properties();
        try (java.io.InputStream stream = (java.io.InputStream) sourceType.getMethod("getInputStream").invoke(copy)) {
            byte[] bytes = stream.readNBytes(MAX_SOURCE_BYTES + 1);
            if (bytes.length > MAX_SOURCE_BYTES) throw new IllegalStateException("oversized property source");
            declared.load(new java.io.ByteArrayInputStream(bytes));
        }
        if (!name.equals(declared.getProperty(alias + ".name", "").trim())) throw new IllegalStateException("missing or mismatched source declaration");
        long declarations = declared.stringPropertyNames().stream().filter(k -> k.startsWith("logger.")
                && k.endsWith(".name") && name.equals(declared.getProperty(k).trim())).count();
        if (declarations != 1) throw new IllegalStateException("duplicate source aliases");
        String original = declared.getProperty(alias + ".level");
        if (original == null || a.level.getMethod("toLevel", String.class, a.level).invoke(null, original.trim(), null) == null)
            throw new IllegalStateException("missing or invalid source level");
        Map<?,?> entries = (Map<?,?>) a.configuration.getMethod("getLoggers").invoke(target.configuration);
        return new SourceProof(source, declared, entries.get(name));
    }

    private boolean applySourceGroup(String name, String level, String alias, List<ContextTarget> owners,
                                     List<Inventory> inventories) throws Exception {
        synchronized (SOURCE_GROUP_LOCK) {
            if (name.isBlank()) throw new IllegalStateException("root is not a named property group");
            Class<?> framework = owners.get(0).api.context;
            List<SourceProof> proofs = new ArrayList<>();
            List<Previous> previous = new ArrayList<>();
            Object parsed = owners.get(0).api.level.getMethod("toLevel", String.class, owners.get(0).api.level)
                    .invoke(null, level, null);
            if (parsed == null) throw new IllegalStateException("invalid requested group level");
            String originalLevel = null;
            for (ContextTarget target : owners) {
                if (target.api.context != framework) throw new IllegalStateException("property group crosses framework copies");
                SourceProof proof = sourceProof(target, name, alias);
                String declaredLevel = proof.declaration.getProperty(alias + ".level").trim();
                if (originalLevel != null && !originalLevel.equals(declaredLevel)) throw new IllegalStateException("inconsistent source declarations");
                originalLevel = declaredLevel; proofs.add(proof);
                Object oldLevel = proof.exact == null ? null : target.api.loggerConfig.getMethod("getLevel").invoke(proof.exact);
                Map<Object,Object> live = new IdentityHashMap<>();
                for (Object logger : target.live) live.put(logger, target.api.logger.getMethod("getLevel").invoke(logger));
                previous.add(new Previous(target, proof.exact, oldLevel, live));
            }
            checkGroup(name, alias, owners, inventories, proofs, true);
            List<Previous> touched = new ArrayList<>();
            try {
                for (int i = 0; i < owners.size(); i++) {
                    ContextTarget target = owners.get(i); Log4jApi a = target.api;
                    synchronized (target.context) {
                        checkGroup(name, alias, owners, inventories, proofs, true);
                        Object entry = proofs.get(i).exact;
                        touched.add(previous.get(i)); // A setter can mutate before throwing.
                        if (entry == null) {
                            entry = a.loggerConfig.getConstructor(String.class, a.level, boolean.class).newInstance(name, parsed, true);
                            a.configuration.getMethod("addLogger", String.class, a.loggerConfig).invoke(target.configuration, name, entry);
                        } else a.loggerConfig.getMethod("setLevel", a.level).invoke(entry, parsed);
                        SourceProof original = proofs.get(i);
                        proofs.set(i, new SourceProof(original.source, original.declaration, entry));
                        a.context.getMethod("updateLoggers").invoke(target.context);
                        readGroupLevel(target, name, entry, parsed);
                    }
                }
                checkGroup(name, alias, owners, inventories, proofs, true);
                for (ContextTarget target : owners) {
                    Object entry = ((Map<?,?>) target.api.configuration.getMethod("getLoggers").invoke(target.configuration)).get(name);
                    readGroupLevel(target, name, entry, parsed);
                }
                return true;
            } catch (Throwable failure) {
                List<String> broken = new ArrayList<>();
                for (int i = touched.size() - 1; i >= 0; i--) {
                    Previous old = touched.get(i); ContextTarget t = old.target; Log4jApi a = t.api;
                    try {
                        synchronized (t.context) {
                            if (a.context.getMethod("getConfiguration").invoke(t.context) != t.configuration) throw new IllegalStateException("configuration replaced");
                            if (old.exact == null) a.configuration.getMethod("removeLogger", String.class).invoke(t.configuration, name);
                            else {
                                a.loggerConfig.getMethod("setLevel", a.level).invoke(old.exact, old.level);
                                a.configuration.getMethod("addLogger", String.class, a.loggerConfig).invoke(t.configuration, name, old.exact);
                            }
                            a.context.getMethod("updateLoggers").invoke(t.context);
                            Map<?,?> restored = (Map<?,?>) a.configuration.getMethod("getLoggers").invoke(t.configuration);
                            if (a.context.getMethod("getConfiguration").invoke(t.context) != t.configuration || restored.get(name) != old.exact
                                    || (old.exact != null && !java.util.Objects.equals(old.level, a.loggerConfig.getMethod("getLevel").invoke(old.exact))))
                                throw new IllegalStateException("restore entry mismatch");
                            for (Map.Entry<Object,Object> l : old.liveLevels.entrySet())
                                if (!java.util.Objects.equals(l.getValue(), a.logger.getMethod("getLevel").invoke(l.getKey()))) throw new IllegalStateException("restore live mismatch");
                        }
                    } catch (Throwable restoreFailure) { broken.add(Integer.toString(System.identityHashCode(t.context))); }
                }
                if (!broken.isEmpty()) {
                    StatusReporter.error("Logger " + name + " rollback failed in contexts " + broken + "; " + PARTIAL_LEVEL);
                    com.onurkat.reclazz.ui.RestartLedger.note(name, PARTIAL_LEVEL);
                }
                StatusReporter.warn("Logger level not applied: " + name + ": " + describe(failure));
                return false;
            }
        }
    }

    private void checkGroup(String name, String alias, List<ContextTarget> owners, List<Inventory> inventories,
                            List<SourceProof> proofs, boolean checkEntries) throws Exception {
        for (Inventory before : inventories) if (!before.sameAs(inventory(before.api, name)))
            throw new IllegalStateException("property group inventory changed");
        for (int i = 0; i < owners.size(); i++) {
            SourceProof now = sourceProof(owners.get(i), name, alias), before = proofs.get(i);
            if (now.source != before.source || !now.declaration.equals(before.declaration) || (checkEntries && now.exact != before.exact))
                throw new IllegalStateException("property group source or entry changed");
        }
    }

    private void readGroupLevel(ContextTarget t, String name, Object entry, Object expected) throws Exception {
        Log4jApi a = t.api;
        Map<?,?> entries = (Map<?,?>) a.configuration.getMethod("getLoggers").invoke(t.configuration);
        if (a.context.getMethod("getConfiguration").invoke(t.context) != t.configuration || entries.get(name) != entry
                || entry == null || !expected.equals(a.loggerConfig.getMethod("getLevel").invoke(entry)))
            throw new IllegalStateException("group configuration read-back mismatch");
        for (Object live : t.live) if (a.logger.getMethod("getContext").invoke(live) != t.context
                || !expected.equals(a.logger.getMethod("getLevel").invoke(live)))
            throw new IllegalStateException("group live logger read-back mismatch");
    }

    // Log4j 2.25.3's public list omits its Lazy default. Observe the verified
    // field layout without calling any context-creating selector method. This
    // inventory covers listed contexts plus selector default, not thread anchors.
    private Inventory inventory(Log4jApi api, String name) throws ReflectiveOperationException {
        Object factory = api.manager.getMethod("getFactory").invoke(null);
        Object selector = api.factory.getMethod("getSelector").invoke(factory);
        String scope = "listed contexts plus selector default";
        if (!api.standardSelector.isInstance(selector)) {
            throw new IllegalStateException("unsupported selector shape for " + scope);
        }
        for (java.lang.reflect.Method method : api.standardSelector.getDeclaredMethods()) {
            if (!method.getName().equals("getContext") && !method.getName().equals("getDefault")
                    && !method.getName().equals("getLoggerContexts")) continue;
            Class<?> declaring = selector.getClass();
            while (declaring != api.standardSelector) {
                try {
                    declaring.getDeclaredMethod(method.getName(), method.getParameterTypes());
                    throw new IllegalStateException("unsupported selector shape override for " + scope);
                } catch (NoSuchMethodException absent) {
                    declaring = declaring.getSuperclass();
                }
            }
        }
        // Check the examined overloads explicitly; an older/different layout is
        // not supported merely because it happens to have a field of this name.
        api.standardSelector.getDeclaredMethod("getContext", String.class, ClassLoader.class, boolean.class);
        api.standardSelector.getDeclaredMethod("getContext", String.class, ClassLoader.class, boolean.class, java.net.URI.class);
        api.standardSelector.getDeclaredMethod("getContext", String.class, ClassLoader.class, Map.Entry.class, boolean.class, java.net.URI.class);
        api.standardSelector.getDeclaredMethod("getDefault");
        java.lang.reflect.Field field = api.standardSelector.getDeclaredField("defaultLoggerContext");
        int modifiers = field.getModifiers();
        if (field.getType() != api.lazy || !java.lang.reflect.Modifier.isPrivate(modifiers)
                || !java.lang.reflect.Modifier.isFinal(modifiers) || java.lang.reflect.Modifier.isStatic(modifiers)) {
            throw new IllegalStateException("unsupported selector shape for " + scope);
        }
        if (!field.trySetAccessible()) throw new IllegalStateException("inaccessible default context for " + scope);
        Object lazy = field.get(selector);
        if (lazy == null) throw new IllegalStateException("missing default context wrapper for " + scope);
        Object defaultContext = null;
        if (Boolean.TRUE.equals(api.lazy.getMethod("isInitialized").invoke(lazy))) {
            // The supported selector never resets or exposes this private final
            // wrapper. Once initialized, value() cannot invoke its supplier.
            defaultContext = api.lazy.getMethod("value").invoke(lazy);
            if (!api.context.isInstance(defaultContext)) {
                throw new IllegalStateException("invalid default context for " + scope);
            }
        }
        List<Object> existing = new ArrayList<>((Collection<?>) api.selector.getMethod("getLoggerContexts").invoke(selector));
        if (defaultContext != null) existing.add(defaultContext);
        IdentityHashMap<Object, ContextTarget> contexts = new IdentityHashMap<>();
        for (Object context : existing) {
            if (!api.context.isInstance(context)) throw new IllegalStateException("invalid context in " + scope);
            if (contexts.containsKey(context)) continue;
            Object configuration = api.context.getMethod("getConfiguration").invoke(context);
            List<Object> live = new ArrayList<>();
            for (Object candidate : (Collection<?>) api.context.getMethod("getLoggers").invoke(context)) {
                if (name.equals(api.logger.getMethod("getName").invoke(candidate))) live.add(candidate);
            }
            Map<?, ?> entries = (Map<?, ?>) api.configuration.getMethod("getLoggers").invoke(configuration);
            contexts.put(context, new ContextTarget(api, context, configuration, live,
                    !live.isEmpty() || entries.containsKey(name)));
        }
        return new Inventory(api, factory, selector, lazy, defaultContext, contexts);
    }

    private record Inventory(Log4jApi api, Object factory, Object selector, Object lazy, Object defaultContext,
                             IdentityHashMap<Object, ContextTarget> contexts) {
        boolean sameAs(Inventory other) {
            if (factory != other.factory || selector != other.selector || lazy != other.lazy
                    || defaultContext != other.defaultContext || contexts.size() != other.contexts.size()) return false;
            for (ContextTarget before : contexts.values()) {
                ContextTarget after = other.contexts.get(before.context);
                if (after == null || before.configuration != after.configuration || before.owner != after.owner
                        || before.live.size() != after.live.size()) return false;
                for (Object logger : before.live) {
                    if (after.live.stream().noneMatch(candidate -> candidate == logger)) return false;
                }
            }
            return true;
        }
    }

    private record ContextTarget(Log4jApi api, Object context, Object configuration,
                                 List<Object> live, boolean owner) { }

    /** Public API declaring types, not potentially inaccessible implementation subclasses. */
    private final class Log4jApi {
        final Class<?> manager, factory, selector, standardSelector, lazy, context, configuration, loggerConfig, logger, level;
        Log4jApi(ClassLoader loader) throws ClassNotFoundException {
            manager = type("org.apache.logging.log4j.LogManager", loader);
            factory = type("org.apache.logging.log4j.core.impl.Log4jContextFactory", loader);
            selector = type("org.apache.logging.log4j.core.selector.ContextSelector", loader);
            standardSelector = type("org.apache.logging.log4j.core.selector.ClassLoaderContextSelector", loader);
            lazy = type("org.apache.logging.log4j.util.Lazy", loader);
            context = type("org.apache.logging.log4j.core.LoggerContext", loader);
            configuration = type("org.apache.logging.log4j.core.config.Configuration", loader);
            loggerConfig = type("org.apache.logging.log4j.core.config.LoggerConfig", loader);
            logger = type("org.apache.logging.log4j.core.Logger", loader);
            level = type(LOG4J2_LEVEL, loader);
        }
        private Class<?> type(String name, ClassLoader loader) throws ClassNotFoundException {
            if (testClasses != null && testClasses.containsKey(name)) return testClasses.get(name);
            return Class.forName(name, false, loader);
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
