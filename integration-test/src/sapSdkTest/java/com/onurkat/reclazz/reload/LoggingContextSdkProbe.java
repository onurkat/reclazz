/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

/** Real SDK regression: a reflective caller must not select its own context. */
public final class LoggingContextSdkProbe {
    private static final String TARGET = "com.example.logging.target";
    private static int checks;
    private static void require(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }
    private static void verifySourceGroup() throws Exception {
        var selectorType=Class.forName("de.hybris.platform.util.logging.log4j2.HybrisClassLoaderContextSelector");
        var selector=(org.apache.logging.log4j.core.selector.ContextSelector)selectorType.getConstructor().newInstance();
        org.apache.logging.log4j.LogManager.setFactory(new org.apache.logging.log4j.core.impl.Log4jContextFactory(selector));
        LoggerContext listed=selector.getContext(LoggingContextSdkProbe.class.getName(),LoggingContextSdkProbe.class.getClassLoader(),false);
        LoggerContext hidden=selector.getContext(LoggingContextSdkProbe.class.getName(),null,true);
        require(listed!=hidden,"distinct actual SAP listed/default contexts");
        String declaration="logger.target.name="+TARGET+"\nlogger.target.level=INFO\nrootLogger.level=OFF\n";
        List<LoggerContext> members=List.of(listed,hidden);
        for(LoggerContext c:members) {
            var source=new org.apache.logging.log4j.core.config.ConfigurationSource(new java.io.ByteArrayInputStream(declaration.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
            var config=new org.apache.logging.log4j.core.config.properties.PropertiesConfigurationFactory().getConfiguration(c,source);
            c.start(config);
        }
        List<java.io.InputStream> originalStreams=new ArrayList<>(); List<Integer> remaining=new ArrayList<>();
        for(LoggerContext c:members){var stream=c.getConfiguration().getConfigurationSource().getInputStream();originalStreams.add(stream);remaining.add(stream.available());}
        Sink sink=new Sink();sink.start();
        var config=listed.getConfiguration();var entry=config.getLoggers().get(TARGET);
        entry.addAppender(sink,null,null);entry.setAdditive(false);listed.updateLoggers();
        Logger live=listed.getLogger(TARGET);Logger control=listed.getLogger("com.example.control");
        Level root=config.getRootLogger().getLevel(), controlLevel=control.getLevel();
        var reloader=new LoggingReloader(Map.of("org.apache.logging.log4j.core.config.Configurator",Configurator.class,"org.apache.logging.log4j.Level",Level.class));
        java.util.Properties saved=new java.util.Properties();saved.setProperty("log4j2.logger.target.name",TARGET);saved.setProperty("log4j2.logger.target.level","DEBUG");
        try {
            live.debug("before");require(sink.messages.isEmpty(),"INFO initially suppresses debug");
            require(applySaved(reloader,saved).equals(List.of(TARGET)),"source group applied claim (v5 refuses ambiguity)");
            require(hidden.getConfiguration().getLoggers().get(TARGET).getLevel()==Level.DEBUG,"default exact entry updated");
            require(live.getLevel()==Level.DEBUG,"listed live logger updated");live.debug("group-debug");
            require(sink.messages.equals(List.of("group-debug")),"real private sink receives DEBUG: " + sink.messages + " enabled=" + live.isDebugEnabled() + " config=" + config.getState() + " sink=" + sink.getState());
            require(control.getLevel()==controlLevel&&config.getRootLogger().getLevel()==root,"control/root unchanged");
            require(config.getLoggers().get(TARGET)==entry&&!entry.isAdditive(),"entry/additivity retained");
            require(entry.getAppenders().containsValue(sink),"sink attachment retained");
            require(hidden.getLoggers().stream().noneMatch(l->TARGET.equals(l.getName())),"group did not create default live logger");
            saved.setProperty("log4j2.logger.target.level","INFO");require(applySaved(reloader,saved).equals(List.of(TARGET)),"group restoration applied");
            require(hidden.getConfiguration().getLoggers().get(TARGET).getLevel()==Level.INFO&&live.getLevel()==Level.INFO,"both restored INFO");
            live.debug("restored-debug");require(sink.messages.equals(List.of("group-debug")),"restored sink suppresses DEBUG");
            require(reloader.applyLevels(Map.of(TARGET,"DEBUG")).isEmpty(),"plain map still refuses two owners");
            for(int i=0;i<members.size();i++) {
                require(members.get(i).getConfiguration().getConfigurationSource().getInputStream()==originalStreams.get(i),"original source stream identity retained");
                require(originalStreams.get(i).available()==remaining.get(i),"original source stream position retained");
            }
        } finally {entry.removeAppender(sink.getName());sink.stop();listed.stop();hidden.stop();}
    }
    @SuppressWarnings("unchecked")
    private static List<String> applySaved(LoggingReloader reloader,java.util.Properties saved) throws Exception {
        try {return (List<String>)LoggingReloader.class.getMethod("applyPropertyLevels",java.util.Properties.class,java.util.Collection.class)
                    .invoke(reloader,saved,List.of("log4j2.logger.target.level"));}
        catch(NoSuchMethodException oldVersion) {return reloader.applyLevels(LoggingReloader.levelsIn(saved,List.of("log4j2.logger.target.level")));}
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            if (args[0].equals("source-group")) verifySourceGroup();
            else if (args[0].equals("unused-default")) verifyUnusedDefault();
            else if (args[0].equals("default-owner")) verifyDefaultOwner();
            else if (args[0].equals("isolated-default")) verifyIsolatedFrameworkCopy(true);
            else throw new IllegalArgumentException(args[0]);
            System.out.println("LOGGING_CONTEXT_SDK_PASS[" + args[0] + "]=" + checks);
            return;
        }
        LoggerContext caller = LoggerContext.getContext(false);
        try (URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
            LoggerContext owner = LoggerContext.getContext(loader, false,
                    java.nio.file.Path.of(System.getProperty("log4j.configurationFile")).toUri());
            require(caller != owner, "probe needs distinct contexts");
            Sink sink = new Sink();
            sink.start();
            var configuration = owner.getConfiguration();
            LoggerConfig parent = new LoggerConfig("com.example.logging", Level.INFO, false);
            parent.addAppender(sink, null, null);
            configuration.addLogger("com.example.logging", parent);
            owner.updateLoggers();
            Logger live = owner.getLogger(TARGET);
            Logger control = owner.getLogger("com.example.logging.control");
            Object root = configuration.getRootLogger();
            Level rootLevel = configuration.getRootLogger().getLevel();
            Map<String, LoggerConfig> callerEntries = Map.copyOf(caller.getConfiguration().getLoggers());
            LoggingReloader reloader = new LoggingReloader(Map.of(
                    "org.apache.logging.log4j.core.config.Configurator", Configurator.class,
                    "org.apache.logging.log4j.Level", Level.class));
            try {
                live.debug("before"); live.info("before-info");
                require(sink.messages.equals(List.of("before-info")), "baseline sink");
                require(reloader.applyLevels(Map.of(TARGET, "DEBUG")).equals(List.of(TARGET)), "applied claim");
                require(live.getLevel() == Level.DEBUG, "live owner stayed INFO: implicit caller context was changed");
                live.debug("changed"); live.info("changed-info"); control.debug("control-debug");
                require(sink.messages.equals(List.of("before-info", "changed", "changed-info")), "real DEBUG delivery");
                require(configuration.getLoggers().get("com.example.logging") == parent && parent.getLevel() == Level.INFO,
                        "parent identity/level changed");
                require(control.getLevel() == Level.INFO, "control changed");
                require(root == configuration.getRootLogger() && rootLevel == configuration.getRootLogger().getLevel(), "root changed");
                require(callerEntries.equals(caller.getConfiguration().getLoggers()), "caller context changed");
                require(reloader.applyLevels(Map.of(TARGET, "INFO")).equals(List.of(TARGET)), "restore claim");
                live.debug("restored-debug"); live.info("restored-info");
                require(sink.messages.equals(List.of("before-info", "changed", "changed-info", "restored-info")), "restore delivery");
                caller.getLogger(TARGET);
                require(reloader.applyLevels(Map.of(TARGET, "DEBUG")).isEmpty(), "ambiguous owner was accepted");
                require(live.getLevel() == Level.INFO && caller.getLogger(TARGET).getLevel() == rootLevel,
                        "ambiguity mutated a context");
                verifyIsolatedFrameworkCopy(false);
                System.out.println("LOGGING_CONTEXT_SDK_PASS=" + checks);
            } finally {
                parent.removeAppender(sink.getName());
                sink.stop();
                owner.stop();
                caller.stop();
            }
        }
    }
    private static void verifyIsolatedFrameworkCopy(boolean defaultOwner) throws Exception {
        URL apiJar = Level.class.getProtectionDomain().getCodeSource().getLocation();
        URL coreJar = Configurator.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader isolated = new URLClassLoader(new URL[]{apiJar, coreJar}, null)) {
            // Context discovery must work even before this copy loads Configurator.
            Class<?> foreignContext = isolated.loadClass(LoggerContext.class.getName());
            Object context = foreignContext.getMethod("getContext", ClassLoader.class, boolean.class, java.net.URI.class)
                    .invoke(null, isolated, defaultOwner, java.nio.file.Path.of(System.getProperty("log4j.configurationFile")).toUri());
            String name = "com.example.isolated.target";
            Object logger = foreignContext.getMethod("getLogger", String.class).invoke(context, name);
            try {
                // Level from the wrong copy appears FIRST. Discovery must use
                // the owning framework loader, not the first class-name match.
                java.lang.instrument.Instrumentation instrumentation = (java.lang.instrument.Instrumentation)
                        java.lang.reflect.Proxy.newProxyInstance(LoggingContextSdkProbe.class.getClassLoader(),
                                new Class<?>[]{java.lang.instrument.Instrumentation.class}, (proxy, method, args) -> {
                                    if (method.getName().equals("getAllLoadedClasses")) {
                                        return new Class<?>[]{Level.class, foreignContext, Configurator.class};
                                    }
                                    throw new UnsupportedOperationException(method.getName());
                                });
                require(new LoggingReloader(instrumentation).applyLevels(Map.of(name, "DEBUG")).equals(List.of(name)),
                        "isolated framework ownership was not resolved");
                Object actual = isolated.loadClass(Logger.class.getName()).getMethod("getLevel").invoke(logger);
                require(actual.getClass().getClassLoader() == isolated && actual.toString().equals("DEBUG"),
                        "Level was paired with the wrong framework copy");
                require(!LoggerContext.getContext(false).getConfiguration().getLoggers().containsKey(name),
                        "other framework copy was mutated");
                Logger parentCopy = LoggerContext.getContext(defaultOwner).getLogger(name);
                Level before = parentCopy.getLevel();
                require(new LoggingReloader(instrumentation).applyLevels(Map.of(name, "TRACE")).isEmpty(),
                        "same name in separate framework copies was not ambiguous");
                require(parentCopy.getLevel() == before
                                && isolated.loadClass(Logger.class.getName()).getMethod("getLevel").invoke(logger).toString().equals("DEBUG"),
                        "cross-framework ambiguity mutated an owner");
            } finally {
                foreignContext.getMethod("stop").invoke(context);
            }
        }
    }
    private static LoggingReloader reloader() {
        return new LoggingReloader(Map.of("org.apache.logging.log4j.core.config.Configurator", Configurator.class));
    }
    private static org.apache.logging.log4j.core.selector.ClassLoaderContextSelector selector() {
        return (org.apache.logging.log4j.core.selector.ClassLoaderContextSelector)
                ((org.apache.logging.log4j.core.impl.Log4jContextFactory) org.apache.logging.log4j.LogManager.getFactory()).getSelector();
    }
    private static org.apache.logging.log4j.util.Lazy<?> defaultHolder() throws Exception {
        var field = org.apache.logging.log4j.core.selector.ClassLoaderContextSelector.class.getDeclaredField("defaultLoggerContext");
        field.setAccessible(true);
        return (org.apache.logging.log4j.util.Lazy<?>) field.get(selector());
    }
    private static void verifyUnusedDefault() throws Exception {
        var lazy = defaultHolder();
        require(!lazy.isInitialized(), "fresh JVM default already initialized");
        LoggerContext listed = LoggerContext.getContext(false);
        try {
            String name = "com.example.unused.target";
            listed.getLogger(name);
            require(reloader().applyLevels(Map.of(name, "DEBUG")).equals(List.of(name)), "listed owner refused");
            require(!lazy.isInitialized(), "inventory initialized the unused default");
            require(listed.getLogger(name).getLevel() == Level.DEBUG, "listed owner not changed");
        } finally { listed.stop(); }
    }
    private static void verifyDefaultOwner() throws Exception {
        LoggerContext listed = LoggerContext.getContext(false), hidden = LoggerContext.getContext(true);
        Sink sink = new Sink(); sink.start();
        String name = "com.example.default.target", controlName = "com.example.default.control";
        var config = hidden.getConfiguration();
        LoggerConfig parent = new LoggerConfig("com.example.default", Level.INFO, false);
        parent.addAppender(sink, null, null); config.addLogger("com.example.default", parent); hidden.updateLoggers();
        Logger live = hidden.getLogger(name), control = hidden.getLogger(controlName);
        var listedBefore = Map.copyOf(listed.getConfiguration().getLoggers());
        var root = config.getRootLogger(); Level rootLevel = root.getLevel();
        try {
            require(hidden != listed && !selector().getLoggerContexts().contains(hidden), "probe needs omitted default");
            live.debug("before-debug"); live.info("before-info");
            require(sink.messages.equals(List.of("before-info")), "default baseline");
            require(reloader().applyLevels(Map.of(name, "DEBUG")).equals(List.of(name)), "unique default refused");
            live.debug("changed"); control.debug("control-debug");
            require(sink.messages.equals(List.of("before-info", "changed")), "default actual DEBUG delivery");
            require(listedBefore.equals(listed.getConfiguration().getLoggers()), "listed context mutated");
            require(control.getLevel() == Level.INFO && parent.getLevel() == Level.INFO
                    && config.getLoggers().get("com.example.default") == parent && config.getRootLogger() == root
                    && root.getLevel() == rootLevel, "parent/control/root changed");
            require(reloader().applyLevels(Map.of(name, "INFO")).equals(List.of(name)), "default restoration refused");
            live.debug("restored-debug"); live.info("restored-info");
            require(sink.messages.equals(List.of("before-info", "changed", "restored-info")), "default INFO restore delivery");
            // Deterministic v3 false-applied regression: a listed exact entry and
            // the omitted default's real logger have the same name.
            LoggerConfig listedEntry = new LoggerConfig(name, Level.INFO, false);
            listed.getConfiguration().addLogger(name, listedEntry); listed.updateLoggers();
            require(reloader().applyLevels(Map.of(name, "DEBUG")).isEmpty(), "hidden/listed ambiguity accepted");
            require(live.getLevel() == Level.INFO && listedEntry.getLevel() == Level.INFO, "ambiguous owners mutated");
            listed.getConfiguration().removeLogger(name); listed.updateLoggers();
            // Fixture-only topology: default is also listed. Production never
            // writes CONTEXT_MAP. Clean this synthetic entry even on failure.
            var field = org.apache.logging.log4j.core.selector.ClassLoaderContextSelector.class.getDeclaredField("CONTEXT_MAP");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.concurrent.ConcurrentMap<String, java.util.concurrent.atomic.AtomicReference<java.lang.ref.WeakReference<LoggerContext>>>) field.get(null);
            String key = "reclazz-sdk-default-duplicate";
            try {
                map.put(key, new java.util.concurrent.atomic.AtomicReference<>(new java.lang.ref.WeakReference<>(hidden)));
                require(reloader().applyLevels(Map.of(name, "DEBUG")).equals(List.of(name)), "default/listed identity not deduplicated");
                require(live.getLevel() == Level.DEBUG, "deduplicated default not changed");
            } finally { map.remove(key); }
        } finally { parent.removeAppender(sink.getName()); sink.stop(); hidden.stop(); listed.stop(); }
    }
    private static final class Sink extends AbstractAppender {
        final List<String> messages = new ArrayList<>();
        Sink() { super("private-probe", null, null, true, Property.EMPTY_ARRAY); }
        @Override public void append(LogEvent event) { messages.add(event.getMessage().getFormattedMessage()); }
    }
}
