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
            "excludePatterns", "startupDelaySec",
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

        return config;
    }

    public Path getHybrisHome() { return hybrisHome; }
    public Set<String> getWatchExtensions() { return Collections.unmodifiableSet(watchExtensions); }
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
