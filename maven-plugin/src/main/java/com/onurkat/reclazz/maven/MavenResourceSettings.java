/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Resource;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** Describe only the standard resources:resources execution whose copying rules we can reproduce. */
final class MavenResourceSettings {
    private static final String GROUP = "org.apache.maven.plugins";
    private static final String ARTIFACT = "maven-resources-plugin";
    // These options affect filtered bytes only. A filtered entry is never copied by the agent.
    private static final Set<String> FILTER_OPTIONS = Set.of("encoding", "propertiesEncoding", "delimiters",
            "useDefaultDelimiters", "useBuildFilters", "filters", "escapeString", "escapeWindowsPaths",
            "supportMultiLineFiltering", "nonFilteredFileExtensions");

    // The 3.3.1 resources goal uses Plexus Utils 3.5.1 in its own plugin realm.
    // The running Maven installation can have a different default list.
    private static final List<String> DEFAULT_EXCLUDES = List.of(
            "**/#*#", "**/%*%", "**/*~", "**/-darcs-backup*", "**/.#*", "**/.DS_Store",
            "**/.MySCMServerInfo", "**/._*", "**/.arch-ids", "**/.arch-ids/**", "**/.bzr", "**/.bzr/**",
            "**/.cvsignore", "**/.darcs-temp-mail", "**/.darcsrepo", "**/.darcsrepo/**", "**/.git", "**/.git/**",
            "**/.hg", "**/.hg/**", "**/.metadata", "**/.metadata/**", "**/.svn", "**/.svn/**", "**/BitKeeper",
            "**/BitKeeper/**", "**/CVS", "**/CVS/**", "**/ChangeSet", "**/ChangeSet/**", "**/RCS", "**/RCS/**",
            "**/SCCS", "**/SCCS/**", "**/_darcs", "**/_darcs/**", "**/project.pj", "**/vssver.scc");

    static Path snapshot(MavenProject project, MavenSession session) throws Exception {
        if (project.getBuild() == null || project.getBasedir() == null || project.getBuild().getDirectory() == null)
            return null;
        Properties props;
        try { props = copySettings(project, session); }
        catch (IOException | InvalidPathException unsupported) { props = null; }
        if (props == null) {
            props = new Properties();
            props.setProperty("mode", "advice");
            int count = 0;
            for (Resource entry : project.getResources()) {
                String directory = entry.getDirectory();
                if (directory == null || directory.contains("${")) continue;
                Path root = project.getBasedir().toPath().resolve(directory).toAbsolutePath().normalize();
                props.setProperty("root." + count++ + ".path", root.toString());
            }
            if (count == 0) return null;
            props.setProperty("roots", Integer.toString(count));
        }
        return persist(project, props);
    }

    private static Properties copySettings(MavenProject project, MavenSession session) throws IOException {
        var plugin = project.getPlugin(GROUP + ":" + ARTIFACT);
        if (plugin == null || !"3.3.1".equals(plugin.getVersion()) || !plugin.getDependencies().isEmpty()) return null;
        if (project.getBuild().getOutputDirectory() == null || project.getBuild().getDirectory() == null) return null;
        for (var execution : plugin.getExecutions()) {
            String goal = switch (execution.getId()) {
                case "default-resources" -> "resources";
                case "default-testResources" -> "testResources";
                default -> null;
            };
            if (goal == null || execution.getGoals().stream().anyMatch(g -> !goal.equals(g))) return null;
            String phase = execution.getPhase();
            if (phase != null && !phase.equals(goal.equals("resources") ? "process-resources" : "process-test-resources"))
                return null;
        }
        var config = project.getGoalConfiguration(GROUP, ARTIFACT, "default-resources", "resources");
        if (config != null) {
            for (var option : config.getChildren()) {
                if (!FILTER_OPTIONS.contains(option.getName()) && !Set.of("outputDirectory", "fileNameFiltering",
                        "skip", "overwrite", "addDefaultExcludes", "includeEmptyDirs").contains(option.getName())) return null;
            }
        }
        String skip = project.getProperties().getProperty("maven.resources.skip", "false");
        if (session != null) skip = session.getUserProperties().getProperty("maven.resources.skip",
                session.getSystemProperties().getProperty("maven.resources.skip", skip));
        if (!"false".equalsIgnoreCase(skip) || bool(config, "skip", false) || bool(config, "fileNameFiltering", false)) return null;
        Path output = Path.of(project.getBuild().getOutputDirectory()).toAbsolutePath().normalize();
        if (config != null && config.getChild("outputDirectory") != null
                && !output.equals(Path.of(config.getChild("outputDirectory").getValue()).toAbsolutePath().normalize())) return null;
        var props = new Properties();
        props.setProperty("version", plugin.getVersion());
        props.setProperty("output", output.toString());
        props.setProperty("overwrite", Boolean.toString(bool(config, "overwrite", false)));
        boolean defaults = bool(config, "addDefaultExcludes", true);
        int index = 0;
        for (Resource entry : project.getResources()) {
            String key = "root." + index++ + ".";
            Path root = project.getBasedir().toPath().resolve(entry.getDirectory()).toAbsolutePath().normalize();
            if (entry.getDirectory().contains("${") || (entry.getFiltering() != null
                    && !Set.of("true", "false").contains(entry.getFiltering().toLowerCase(Locale.ROOT)))) return null;
            String target = Objects.toString(entry.getTargetPath(), "");
            Path relative = Path.of(target).normalize();
            if (relative.isAbsolute() || relative.startsWith("..") || target.contains("${")) return null;
            if (output.startsWith(root) || root.startsWith(output)) return null;
            props.setProperty(key + "path", root.toString());
            props.setProperty(key + "target", relative.toString());
            props.setProperty(key + "filtered", Boolean.toString(entry.isFiltering()));
            patterns(props, key + "include.", entry.getIncludes().isEmpty() ? List.of("**") : entry.getIncludes());
            List<String> excludes = new ArrayList<>(entry.getExcludes());
            if (defaults) excludes.addAll(DEFAULT_EXCLUDES);
            patterns(props, key + "exclude.", excludes);
        }
        if (index == 0) return null;
        props.setProperty("roots", Integer.toString(index));
        return props;
    }

    private static Path persist(MavenProject project, Properties props) throws Exception {
        var text = new StringWriter();
        props.store(text, null);
        String content = text.toString();
        byte[] bytes = content.substring(content.indexOf('\n') + 1).getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path dir = Path.of(project.getBuild().getDirectory(), "reclazz");
        Files.createDirectories(dir);
        Path file = dir.resolve("resources-" + sha + ".properties");
        if (!Files.isRegularFile(file)) {
            Path temp = Files.createTempFile(dir, "resources-", ".tmp");
            try {
                Files.write(temp, bytes);
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        }
        return file;
    }

    private static boolean bool(Xpp3Dom config, String key, boolean fallback) throws IOException {
        if (config == null || config.getChild(key) == null) return fallback;
        String value = config.getChild(key).getValue();
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IOException("Unresolved Maven resource option " + key);
    }

    private static void patterns(Properties props, String prefix, List<String> globs) throws IOException {
        for (int i = 0; i < globs.size(); i++) props.setProperty(prefix + i, regex(globs.get(i)));
        props.setProperty(prefix + "count", Integer.toString(globs.size()));
    }

    static String regex(String glob) throws IOException {
        String path = glob.trim().replace('\\', '/');
        if (path.isEmpty() || path.startsWith("/") || path.startsWith("%") || path.contains("${"))
            throw new IOException("Unsupported Maven resource selector");
        if (path.endsWith("/")) path += "**";
        var parts = new ArrayList<String>();
        for (String part : path.split("/+")) {
            if (part.equals("**") && !parts.isEmpty() && parts.get(parts.size()-1).equals("**")) continue;
            parts.add(part);
        }
        if (parts.equals(List.of("**"))) return "(?s:.*)";
        var result = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            if (part.equals("**")) {
                result.append(i == 0 ? "(?:[^/]+/)*" : "(?:/[^/]+)*");
            } else {
                if (i > 0 && !(i == 1 && parts.get(0).equals("**"))) result.append('/');
                for (int j = 0; j < part.length(); j++) {
                    char c = part.charAt(j);
                    if (c == '*') result.append("[^/]*");
                    else if (c == '?') result.append("[^/]");
                    else result.append(Pattern.quote(String.valueOf(c)));
                }
            }
        }
        return result.toString();
    }
}
