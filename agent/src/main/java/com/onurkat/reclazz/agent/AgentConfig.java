/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.ui.StatusReporter;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Parses and holds agent configuration from -javaagent args.
 *
 * Agent args format: key1=value1,key2=value2
 * Example: hybrisHome=/opt/hybris,watchExtensions=myext1;myext2,autoImpex=true
 *
 * Values may contain commas (e.g. Windows paths). The parser splits on commas
 * only when followed by a known key name, so paths like "C:\Users\John,Jr\hybris"
 * are handled correctly.
 */
public class AgentConfig {

    private static final Set<String> KNOWN_KEYS = Set.of(
            "hybrisHome", "watchExtensions", "autoCompile", "autoImpex",
            "impexAllowRemove",
            "debounceMs", "verbose", "statusPort", "portFile", "wrapOutput",
            "excludePatterns", "excludeClasses", "startupDelaySec",
            "structuralReload", "transformDumpDir", "verifyTransform",
            "platform", "watchDirs", "jpaRefresh"
    );

    // Split on comma followed by a known key= pattern
    private static final Pattern SPLIT_PATTERN;
    static {
        String keys = String.join("|", KNOWN_KEYS);
        SPLIT_PATTERN = Pattern.compile(",(?=(?:" + keys + ")=)");
    }

    private Path hybrisHome;
    private Set<String> watchExtensions = new HashSet<>();
    private boolean watchAllExtensions = true;
    private boolean autoImpex = false;
    /**
     * Whether an auto-imported ImpEx may contain REMOVE headers.
     *
     * Off by default even when auto-import is on. Importing runs against the
     * live database with no confirmation and nothing to undo it, and saving a
     * file is not the same act as asking for rows to be deleted. INSERT and
     * UPDATE are what the edit-and-see-it loop is for.
     */
    private boolean impexAllowRemove = false;
    private boolean autoCompile = false;
    private long debounceMs = 500;
    private boolean verbose = false;

    /**
     * Whether to lay long messages out for a fixed-width view: "auto" wraps
     * when standard output is a terminal, "always" and "never" say so outright.
     *
     * <p>It is a setting rather than a guess because the guess cannot be made
     * from here. An application server writes to a console a person is reading
     * AND to a file somebody greps, and the two want opposite things: a
     * paragraph laid out in a shape, or a phrase left on one line where a
     * search can find it. Auto keeps the searchable shape wherever it cannot
     * tell, which is the safe half to be wrong on.
     */
    private String wrapOutput = "auto";
    private int statusPort = 0;
    private Path portFile;
    private List<String> excludePatterns = new ArrayList<>();

    /**
     * Classes to leave uninstrumented, by fully qualified name.
     *
     * <p>The way out when instrumentation itself is the problem. It happens:
     * a class the transform cannot handle, a framework whose own bytecode
     * tricks do not survive being rewritten, a bug in this agent. Until this
     * existed the only escape was detaching the agent, which is what a
     * developer who hit one class did, and it costs them every other class too.
     * `excludePatterns` did not help, because it excludes files from being
     * watched, and instrumentation is not watching: it happens at load time
     * whether the file ever changes or not.
     *
     * <p>An excluded class loads exactly as it would without Reclazz. Method
     * body changes still reload, since that is the JVM's own redefinition and
     * needs nothing from the agent; adding or removing members does not, and
     * says so with this as the reason.
     */
    private List<String> excludeClasses = new ArrayList<>();

    private final List<java.util.regex.Pattern> compiledExcludeClasses = new ArrayList<>();
    private List<Pattern> compiledExcludePatterns = new ArrayList<>();
    private int startupDelaySec = 30;
    private boolean structuralReload = true;
    private Path transformDumpDir;
    private boolean verifyTransform = false;
    private String platform = "auto";
    private List<Path> watchDirs = new ArrayList<>();
    /**
     * Whether a qualifying entity reload may rebuild its persistence unit.
     *
     * Off by default. The rebuild closes every persistence context opened
     * against the old factory, which is a correct thing to do to a development
     * server only when the developer asked for it.
     */
    private boolean jpaRefresh = false;

    public static AgentConfig parse(String agentArgs) {
        AgentConfig config = new AgentConfig();

        if (agentArgs == null || agentArgs.isBlank()) {
            return config;
        }

        Map<String, String> params = new HashMap<>();
        for (String pair : SPLIT_PATTERN.split(agentArgs)) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0].trim(), kv[1].trim());
            }
        }

        if (params.containsKey("hybrisHome")) {
            config.hybrisHome = Paths.get(params.get("hybrisHome"));
        }

        if (params.containsKey("watchExtensions")) {
            String[] exts = params.get("watchExtensions").split(";");
            for (String ext : exts) {
                config.watchExtensions.add(ext.trim());
            }
            config.watchAllExtensions = false;
        }

        if (params.containsKey("autoImpex")) {
            config.autoImpex = Boolean.parseBoolean(params.get("autoImpex"));
        }
        if (params.containsKey("impexAllowRemove")) {
            config.impexAllowRemove = Boolean.parseBoolean(params.get("impexAllowRemove"));
        }

        if (params.containsKey("autoCompile")) {
            config.autoCompile = Boolean.parseBoolean(params.get("autoCompile"));
        }

        if (params.containsKey("debounceMs")) {
            try {
                long val = Long.parseLong(params.get("debounceMs"));
                config.debounceMs = Math.max(0, Math.min(val, 60_000));
            } catch (NumberFormatException e) {
                StatusReporter.warn("Invalid debounceMs value, using default: 500");
            }
        }

        if (params.containsKey("wrapOutput")) {
            String value = String.valueOf(params.get("wrapOutput")).trim().toLowerCase();
            // "true"/"false" are what a developer types out of habit for a
            // setting that reads like a boolean, and refusing them would be
            // pedantry rather than safety.
            if (value.equals("always") || value.equals("true")) config.wrapOutput = "always";
            else if (value.equals("never") || value.equals("false")) config.wrapOutput = "never";
            else config.wrapOutput = "auto";
        }
        if (params.containsKey("verbose")) {
            config.verbose = Boolean.parseBoolean(params.get("verbose"));
        }

        if (params.containsKey("statusPort")) {
            try {
                config.statusPort = Integer.parseInt(params.get("statusPort"));
            } catch (NumberFormatException e) {
                StatusReporter.warn("Invalid statusPort value, using default: 0");
            }
        }

        if (params.containsKey("portFile")) {
            config.portFile = Paths.get(params.get("portFile"));
        }

        if (params.containsKey("excludeClasses")) {
            for (String pattern : params.get("excludeClasses").split(";")) {
                String trimmed = pattern.trim();
                if (!trimmed.isEmpty()) {
                    config.excludeClasses.add(trimmed);
                    config.compiledExcludeClasses.add(
                            java.util.regex.Pattern.compile(globToRegex(trimmed)));
                }
            }
        }
        if (params.containsKey("excludePatterns")) {
            String[] patterns = params.get("excludePatterns").split(";");
            for (String pattern : patterns) {
                String trimmed = pattern.trim();
                if (!trimmed.isEmpty()) {
                    config.excludePatterns.add(trimmed);
                    config.compiledExcludePatterns.add(Pattern.compile(globToRegex(trimmed)));
                }
            }
        }

        if (params.containsKey("startupDelaySec")) {
            try {
                config.startupDelaySec = Integer.parseInt(params.get("startupDelaySec"));
                if (config.startupDelaySec < 0) config.startupDelaySec = 0;
                if (config.startupDelaySec > 300) config.startupDelaySec = 300;
            } catch (NumberFormatException e) {
                StatusReporter.warn("Invalid startupDelaySec value, using default: 30");
            }
        }

        if (params.containsKey("structuralReload")) {
            config.structuralReload = Boolean.parseBoolean(params.get("structuralReload"));
        }

        if (params.containsKey("transformDumpDir")) {
            config.transformDumpDir = Paths.get(params.get("transformDumpDir"));
        }

        if (params.containsKey("verifyTransform")) {
            config.verifyTransform = Boolean.parseBoolean(params.get("verifyTransform"));
        }

        if (params.containsKey("platform")) {
            config.platform = params.get("platform").toLowerCase();
        }

        if (params.containsKey("jpaRefresh")) {
            config.jpaRefresh = Boolean.parseBoolean(params.get("jpaRefresh"));
        }

        if (params.containsKey("watchDirs")) {
            String[] dirs = params.get("watchDirs").split(";");
            for (String dir : dirs) {
                String trimmed = dir.trim();
                if (!trimmed.isEmpty()) {
                    config.watchDirs.add(Paths.get(trimmed));
                }
            }
        }

        config.unknownKeys = unknownKeysIn(params, agentArgs);
        return config;
    }

    /**
     * What was passed that this agent does not know, and what that cost.
     *
     * <p>Unrecognised keys were dropped, and dropping is not what happens to
     * them. The splitter breaks the argument string on a comma only when a
     * KNOWN key follows it, so that a value may contain a comma of its own,
     * which means an unknown key does not become an argument: it becomes part
     * of the value of the argument before it.
     * {@code hybrisHome=/srv/hybris,debouceMs=200} parses as one setting whose
     * hybrisHome is {@code /srv/hybris,debouceMs=200}, a path that does not
     * exist, and the developer is told something about their platform rather
     * than about their typo.
     *
     * <p>Three things arrive this way and none is rare: a typo, an argument
     * copied from notes older than the jar, and a jar older than the argument,
     * which is the ordinary state of a SAP Commerce server whose
     * {@code wrapper.conf} was written by a newer plugin and whose staged jar
     * has not been refreshed. Where it lands decides how bad it is: in front of
     * {@code portFile} it means the agent writes its port somewhere nobody
     * reads and the IDE never connects.
     *
     * <p>The parse is left alone. Splitting on every comma that looks like an
     * argument would take the commas out of the values that are allowed them,
     * and refusing to start over a stale line in a config file is a worse trade
     * than a warning. So this only says what happened.
     */
    static java.util.List<String> unknownKeysIn(Map<String, String> params, String agentArgs) {
        java.util.Set<String> unknown = new java.util.LinkedHashSet<>();

        // The ones that did become an argument of their own: a key at the very
        // start, or one following another unknown.
        for (String key : params.keySet()) {
            if (!KNOWN_KEYS.contains(key)) unknown.add(key);
        }

        // And the ones that were swallowed into the value before them.
        if (agentArgs != null) {
            java.util.regex.Matcher m = SWALLOWED.matcher(agentArgs);
            while (m.find()) {
                String key = m.group(1);
                if (!KNOWN_KEYS.contains(key)) unknown.add(key);
            }
        }

        java.util.List<String> sorted = new java.util.ArrayList<>(unknown);
        java.util.Collections.sort(sorted);
        return sorted;
    }

    /** A comma, then something shaped like an argument name, then an equals. */
    private static final Pattern SWALLOWED =
            Pattern.compile(",([A-Za-z][A-Za-z0-9_.-]*)=");

    /** Names them, in the one place that knows what the accepted ones are. */
    public void reportUnknownKeys() {
        if (unknownKeys.isEmpty()) return;
        java.util.List<String> accepted = new java.util.ArrayList<>(KNOWN_KEYS);
        java.util.Collections.sort(accepted);
        com.onurkat.reclazz.ui.StatusReporter.warn(
                com.onurkat.reclazz.ui.Plural.word(unknownKeys.size(),
                        "This agent argument is not one this version knows: ",
                        "These agent arguments are not ones this version knows: ")
                        + String.join(", ", unknownKeys)
                        + com.onurkat.reclazz.ui.Plural.word(unknownKeys.size(),
                                ". It was not ignored: unless it came first, it became part of "
                                        + "the value of the argument before it. ",
                                ". They were not ignored: unless one came first, each became "
                                        + "part of the value of the argument before it. ")
                        + "This version accepts: " + String.join(", ", accepted) + ".");
    }

    /** For tests, and for the startup line that names them. */
    public java.util.List<String> getUnknownKeys() {
        return Collections.unmodifiableList(unknownKeys);
    }

    private java.util.List<String> unknownKeys = java.util.List.of();

    public Path getHybrisHome() { return hybrisHome; }
    public boolean isWatchAllExtensions() { return watchAllExtensions; }
    public boolean isAutoImpex() { return autoImpex; }
    public boolean isImpexAllowRemove() { return impexAllowRemove; }
    public boolean isAutoCompile() { return autoCompile; }
    public long getDebounceMs() { return debounceMs; }
    public boolean isVerbose() { return verbose; }

    public String getWrapOutput() { return wrapOutput; }
    public int getStatusPort() { return statusPort; }
    public Path getPortFile() { return portFile; }
    public List<String> getExcludePatterns() { return Collections.unmodifiableList(excludePatterns); }
    public int getStartupDelaySec() { return startupDelaySec; }
    public boolean isStructuralReload() { return structuralReload; }
    public Path getTransformDumpDir() { return transformDumpDir; }
    public boolean isVerifyTransform() { return verifyTransform; }
    public String getPlatform() { return platform; }
    public List<Path> getWatchDirs() { return Collections.unmodifiableList(watchDirs); }
    public boolean isJpaRefresh() { return jpaRefresh; }

    public boolean shouldWatchExtension(String extensionName) {
        return watchAllExtensions || watchExtensions.contains(extensionName);
    }

    /**
     * Check if a file path matches any exclude pattern.
     * Patterns are matched against the filename using pre-compiled glob patterns.
     */
    /**
     * Whether this class should be left alone by the transform.
     *
     * @param className either the internal name or the binary name; both are
     *                  matched against the pattern as a binary name, since
     *                  that is what a developer writes
     */
    public boolean isClassExcluded(String className) {
        if (className == null || compiledExcludeClasses.isEmpty()) return false;
        String binary = className.replace('/', '.');
        for (java.util.regex.Pattern compiled : compiledExcludeClasses) {
            if (compiled.matcher(binary).matches()) return true;
        }
        return false;
    }

    public List<String> getExcludeClasses() {
        return Collections.unmodifiableList(excludeClasses);
    }

    public boolean isExcluded(String fileName) {
        for (Pattern compiled : compiledExcludePatterns) {
            if (compiled.matcher(fileName).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Convert a glob pattern to a regex string.
     * Escapes all regex metacharacters, then converts glob wildcards.
     */
    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                case '.', '\\', '[', ']', '(', ')', '{', '}',
                     '+', '^', '$', '|' -> regex.append('\\').append(c);
                default -> regex.append(c);
            }
        }
        return regex.toString();
    }
}
