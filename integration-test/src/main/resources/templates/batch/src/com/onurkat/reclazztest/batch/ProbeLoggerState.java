package com.onurkat.reclazztest.batch;

import java.lang.management.ManagementFactory;
import java.util.*;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.*;

/** A bounded private sink; observation never changes a backend level. */
public final class ProbeLoggerState {
    public static final String TARGET = "com.onurkat.reclazztest.logprobe.target";
    public static final String CONTROL = "com.onurkat.reclazztest.logprobe.control";
    private static final String SINK = "reclazz-owned-level-probe";
    private static String run;
    private static Logger target, control;
    private static LoggerContext context;
    private static Configuration config;
    private static LoggerConfig oldTarget, oldControl, targetConfig, controlConfig;
    private static Map<String,List<Object>> original, installed;
    private static Map<String,Appender> global;
    private static Sink sink;
    private static final Set<String> phases = new HashSet<>();
    private ProbeLoggerState() { }
    private static void valid(String id) {
        if (id == null || !id.matches("reclazz-logger-[0-9a-f-]{36}")) throw new IllegalArgumentException("Invalid logger run");
    }
    private static void owned(String id) {
        valid(id);
        if (!id.equals(run)) throw new IllegalStateException("No matching logger run");
    }
    private static final class Sink extends AbstractAppender {
        private final List<Map<String,String>> events = new ArrayList<>();
        private boolean overflow;
        Sink() { super(SINK, null, null, false, Property.EMPTY_ARRAY); }
        @Override public synchronized void append(LogEvent event) {
            if (events.size() >= 32) { overflow = true; return; }
            events.add(Map.of("logger", event.getLoggerName(), "level", event.getLevel().name(),
                    "message", event.getMessage().getFormattedMessage()));
        }
        synchronized List<Map<String,String>> copy() {
            if (overflow) throw new IllegalStateException("Logger sink overflow");
            return new ArrayList<>(events);
        }
    }
    private static Map<String,List<Object>> snapshot(boolean allowTargetLevel) {
        Map<String,LoggerConfig> loggers = new TreeMap<>(config.getLoggers());
        loggers.put("", config.getRootLogger());
        Map<String,List<Object>> result = new TreeMap<>();
        for (var entry : loggers.entrySet()) {
            LoggerConfig c = entry.getValue();
            boolean mutable = allowTargetLevel && TARGET.equals(entry.getKey());
            result.put(entry.getKey(), Arrays.asList(c, mutable ? "owned" : c.getExplicitLevel(),
                    mutable ? "owned" : c.getLevel(), c.isAdditive(), c.getFilter(), c.getParent(),
                    new TreeMap<>(c.getAppenders()), new ArrayList<>(c.getAppenderRefs())));
        }
        return result;
    }
    public static synchronized Map<String,Object> begin(String id) {
        valid(id);
        if (run != null) throw new IllegalStateException("Logger probe already active");
        target = (Logger) LogManager.getLogger(TARGET);
        control = (Logger) LogManager.getLogger(CONTROL);
        context = target.getContext();
        if (context != control.getContext()) throw new IllegalStateException("Different logger contexts");
        config = context.getConfiguration();
        if (config.getAppenders().containsKey(SINK) || config.getLoggers().values().stream()
                .anyMatch(c -> c.getAppenders().containsKey(SINK))) throw new IllegalStateException("Sink collision");
        oldTarget = config.getLoggers().get(TARGET); oldControl = config.getLoggers().get(CONTROL);
        if (target.getLevel() != Level.INFO || control.getLevel() != Level.INFO)
            throw new IllegalStateException("Expected configured INFO baseline");
        original = snapshot(false); global = new TreeMap<>(config.getAppenders());
        run = id;
        try {
            sink = new Sink(); sink.start();
            targetConfig = new LoggerConfig(TARGET, Level.INFO, false);
            controlConfig = new LoggerConfig(CONTROL, Level.INFO, false);
            targetConfig.addAppender(sink, Level.ALL, null); controlConfig.addAppender(sink, Level.ALL, null);
            config.removeLogger(TARGET); config.removeLogger(CONTROL);
            config.addLogger(TARGET, targetConfig); config.addLogger(CONTROL, controlConfig);
            context.updateLoggers(); phases.clear(); installed = snapshot(true);
            return observe(id);
        } catch (RuntimeException | Error failure) {
            try { cleanup(id); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    public static synchronized Map<String,Object> emit(String id, String phase, boolean omit) {
        owned(id);
        if (!Set.of("before", "changed", "restored").contains(phase) || !phases.add(phase))
            throw new IllegalArgumentException("Invalid or duplicate logger phase");
        if (!omit) {
            target.debug(id + ":" + phase + ":target:DEBUG");
            target.info(id + ":" + phase + ":target:INFO");
            control.debug(id + ":" + phase + ":control:DEBUG");
            control.info(id + ":" + phase + ":control:INFO");
        }
        return observe(id);
    }
    public static synchronized Map<String,Object> observe(String id) {
        owned(id);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("run", id);
        result.put("jvm", ManagementFactory.getRuntimeMXBean().getName() + ":" + ManagementFactory.getRuntimeMXBean().getStartTime());
        result.put("sameContext", context == target.getContext() && context == control.getContext() && config == context.getConfiguration());
        result.put("unchanged", installed.equals(snapshot(true)) && global.equals(config.getAppenders()));
        result.put("targetLevel", target.getLevel().name()); result.put("controlLevel", control.getLevel().name());
        result.put("events", sink.copy());
        return result;
    }
    public static synchronized Map<String,Object> cleanup(String id) {
        valid(id);
        if (run == null) return Map.of("run",id,"active",false,"attachments",0,"restored",true);
        owned(id);
        if (targetConfig != null) targetConfig.removeAppender(SINK);
        if (controlConfig != null) controlConfig.removeAppender(SINK);
        if (sink != null) sink.stop();
        config.removeLogger(TARGET); config.removeLogger(CONTROL);
        if (oldTarget != null) config.addLogger(TARGET, oldTarget);
        if (oldControl != null) config.addLogger(CONTROL, oldControl);
        context.updateLoggers();
        boolean restored = config == context.getConfiguration() && original.equals(snapshot(false)) && global.equals(config.getAppenders());
        long attachments = config.getLoggers().values().stream().filter(c -> c.getAppenders().containsKey(SINK)).count()
                + (config.getRootLogger().getAppenders().containsKey(SINK) ? 1 : 0)
                + (config.getAppenders().containsKey(SINK) ? 1 : 0);
        run = null; phases.clear(); sink = null; targetConfig = null; controlConfig = null;
        return Map.of("run",id,"active",false,"attachments",attachments,"restored",restored);
    }
}
