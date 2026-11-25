/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.maven;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

/** Snapshot the effective main-compile settings for the application JVM. */
final class MavenCompilerSettings {
    private static final String GROUP = "org.apache.maven.plugins";
    private static final String COMPILER = "maven-compiler-plugin";
    private static final String SERVICE = "META-INF/services/javax.annotation.processing.Processor";
    private static final String MAPSTRUCT = "org.mapstruct.ap.MappingProcessor";
    private static final Set<String> LOMBOK = Set.of("lombok.launch.AnnotationProcessorHider$AnnotationProcessor",
            "lombok.launch.AnnotationProcessorHider$ClaimingProcessor");
    private static final Set<String> SUPPORTED = Set.of("release", "source", "target", "encoding", "parameters",
            "debug", "debuglevel", "showWarnings", "showDeprecation", "failOnWarning", "compilerArgs",
            "compilerArgument", "compilerId", "fork", "executable", "proc", "annotationProcessors",
            "annotationProcessorPaths", "generatedSourcesDirectory");
    private final MavenProject project;
    private final MavenSession session;
    private final RepositorySystem resolver;
    private final Xpp3Dom config;
    private final List<String> options = new ArrayList<>();
    private final Properties manifest = new Properties();
    private boolean verifiedInPlaceProcessors;

    MavenCompilerSettings(MavenProject project, MavenSession session, RepositorySystem resolver) {
        this.project = project;
        this.session = session;
        this.resolver = resolver;
        this.config = project.getGoalConfiguration(GROUP, COMPILER, "default-compile", "compile");
    }

    String prepare(boolean forced) throws Exception {
        Plugin plugin = project.getPlugin(GROUP + ":" + COMPILER);
        if (plugin == null || !Set.of("3.13.0", "3.14.0", "3.14.1", "3.15.0").contains(Objects.toString(plugin.getVersion(), "")))
            throw new IOException("unverified Maven compiler plugin version");
        for (Plugin active : project.getBuildPlugins()) {
            String id = active.getArtifactId();
            if (id.contains("kotlin") || id.contains("groovy") || id.contains("scala") || id.contains("toolchain"))
                throw new IOException("compiler or toolchain plugin " + id);
        }
        for (var execution : plugin.getExecutions())
            if (!"default-compile".equals(execution.getId()) && execution.getGoals().contains("compile"))
                throw new IOException("additional compiler execution " + execution.getId());
        if (!plugin.getDependencies().isEmpty()) throw new IOException("custom dependencies of the compiler plugin");
        for (String key : List.of("maven.compiler.enablePreview", "maven.compiler.implicit",
                "maven.compiler.outputDirectory", "maven.main.skip")) {
            String flag = property(key, "");
            if (!flag.isEmpty() && !flag.equalsIgnoreCase("false"))
                throw new IOException("unsupported Maven compiler property " + key);
        }
        if (config != null)
            for (var child : config.getChildren())
                if (!SUPPORTED.contains(child.getName())) throw new IOException("unsupported compiler option " + child.getName());
        List<String> classpath = project.getCompileClasspathElements();
        configureProcessors(classpath, forced);
        verifiedInPlaceProcessors = manifest.getProperty("mapstruct") == null
                && child("compilerArgs") == null && child("compilerArgument") == null;
        if (!"javac".equals(value("compilerId", "javac"))) throw new IOException("compilerId=" + value("compilerId", ""));
        if (bool("fork", false)) {
            String executable = value("executable", "");
            String name = File.separatorChar == '\\' ? "javac.exe" : "javac";
            if (!executable.isBlank() && !Files.isSameFile(Path.of(executable), Path.of(System.getProperty("java.home"), "bin", name)))
                throw new IOException("different forked javac");
        }
        List<String> roots = project.getCompileSourceRoots();
        if (roots.isEmpty()) throw new IOException("missing Java source roots");
        for (String root : roots) if (Files.exists(Path.of(root, "module-info.java"))) throw new IOException("Java module path");
        Path base = project.getBasedir().toPath().toAbsolutePath().normalize();
        Path source = base.resolve("src/main/java");
        Path build = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
        if (!Path.of(project.getBuild().getOutputDirectory()).normalize().equals(base.resolve("target/classes"))
                || roots.stream().map(Path::of).map(Path::normalize).noneMatch(source::equals)
                || roots.stream().map(Path::of).map(Path::normalize)
                        .anyMatch(root -> !root.equals(source) && !root.startsWith(build)))
            throw new IOException("custom Maven source or output layout");
        manifest.setProperty("roots", String.join(File.pathSeparator, roots));
        manifest.setProperty("classpath", String.join(File.pathSeparator, classpath));
        manifest.setProperty("jdk", System.getProperty("java.specification.version"));
        String encoding = value("encoding", property("encoding", property("project.build.sourceEncoding", Charset.defaultCharset().name())));
        manifest.setProperty("encoding", Charset.forName(encoding).name());
        String release = value("release", "");
        if (!release.isEmpty()) Collections.addAll(options, "--release", release);
        else Collections.addAll(options, "-source", value("source", "1.8"), "-target", value("target", "1.8"));
        String level = value("debuglevel", "");
        options.add(bool("debug", true) ? (level.isEmpty() ? "-g" : "-g:" + level) : "-g:none");
        if (bool("parameters", false)) options.add("-parameters");
        if (bool("failOnWarning", false)) options.add("-Werror");
        if (!bool("showWarnings", true)) options.add("-nowarn");
        if (bool("showDeprecation", false)) options.add("-deprecation");
        Xpp3Dom arguments = child("compilerArgs");
        if (arguments != null) for (var arg : arguments.getChildren()) addFlag(arg.getValue());
        String single = value("compilerArgument", "");
        if (!single.isBlank()) for (String arg : single.trim().split("\\s+")) addFlag(arg);
        for (int i = 0; i < options.size(); i++) manifest.setProperty("option." + i, options.get(i));
        return snapshot();
    }

    boolean canHonorUnmodeledOverride() {
        return verifiedInPlaceProcessors;
    }

    private void addFlag(String arg) throws IOException {
        // Flags which alter inputs, outputs, compiler plugins or the classpath need separate modeling.
        if (arg == null || !(arg.equals("-Werror") || arg.equals("-parameters") || arg.equals("-nowarn")
                || arg.equals("-deprecation") || arg.matches("-Xlint(?::[A-Za-z0-9_,-]+)?")
                || arg.matches("-A[A-Za-z0-9_.]+(?:=.*)?"))) throw new IOException("unsupported compiler argument");
        options.add(arg);
    }

    private void configureProcessors(List<String> classpath, boolean forced) throws Exception {
        String proc = value("proc", "");
        if ("none".equals(proc)) { options.add("-proc:none"); return; }
        if (!proc.isEmpty() && !proc.equals("full")) throw new IOException("unsupported annotation processing mode " + proc);
        if (!proc.isEmpty()) options.add("-proc:" + proc);
        var paths = child("annotationProcessorPaths");
        List<String> search = new ArrayList<>();
        if (paths == null || paths.getChildCount() == 0) search.addAll(classpath);
        else {
            for (var path : paths.getChildren()) {
                String group = coordinate(path, "groupId"), artifact = coordinate(path, "artifactId");
                String version = coordinate(path, "version");
                for (var field : path.getChildren())
                    if (!Set.of("groupId", "artifactId", "version").contains(field.getName()))
                        throw generates(group + ":" + artifact);
                if (version.isEmpty() && project.getDependencyManagement() != null) {
                    for (var managed : project.getDependencyManagement().getDependencies())
                        if (group.equals(managed.getGroupId()) && artifact.equals(managed.getArtifactId())
                                && "jar".equals(managed.getType()) && managed.getClassifier() == null)
                            version = managed.getVersion();
                }
                if (version == null || version.isEmpty()) throw generates(group + ":" + artifact);
                if (!(group.equals("org.projectlombok") && artifact.equals("lombok")
                        || group.equals("org.mapstruct") && artifact.equals("mapstruct-processor")))
                    throw generates(group + ":" + artifact);
                var root = new Dependency(new DefaultArtifact(group, artifact, "jar", version), "runtime");
                var collect = new CollectRequest(root, project.getRemoteProjectRepositories());
                var request = new DependencyRequest(collect, DependencyFilterUtils.classpathFilter("runtime"));
                try {
                    for (var resolved : resolver.resolveDependencies(session.getRepositorySession(), request).getArtifactResults())
                        search.add(resolved.getArtifact().getFile().getAbsolutePath());
                } catch (org.eclipse.aether.resolution.DependencyResolutionException unavailable) {
                    throw new IOException("annotation processor " + group + ":" + artifact + " could not be resolved", unavailable);
                }
            }
            Collections.addAll(options, "-processorpath", String.join(File.pathSeparator, search));
        }
        Set<String> processors = new LinkedHashSet<>();
        Xpp3Dom named = child("annotationProcessors");
        if (named != null && named.getChildCount() != 0) {
            for (var p : named.getChildren()) processors.add(p.getValue());
            Collections.addAll(options, "-processor", String.join(",", processors));
        } else {
            for (String entry : search) readProviders(Path.of(entry), processors);
        }
        for (String provider : processors) {
            if (LOMBOK.contains(provider)) continue;
            if (!MAPSTRUCT.equals(provider) || !forced) throw generates(provider);
            manifest.setProperty("mapstruct", "true");
        }
        if (!processors.isEmpty()) {
            String generated = value("generatedSourcesDirectory", project.getBuild().getDirectory() + "/generated-sources/annotations");
            Path directory = project.getBasedir().toPath().resolve(generated).normalize();
            Path source = project.getBasedir().toPath().resolve("src/main/java").normalize();
            if (source.startsWith(directory) || directory.startsWith(source))
                throw new IOException("annotation processor generated sources overlap the watched source tree");
            manifest.setProperty("generated", directory.toString());
        }
    }

    private static IOException generates(String name) {
        return new IOException("annotation processor " + name + " generates code from other classes");
    }

    private static void readProviders(Path entry, Set<String> found) throws IOException {
        if (!Files.exists(entry)) return;
        if (Files.isDirectory(entry)) {
            Path service = entry.resolve(SERVICE);
            if (Files.isRegularFile(service)) providers(Files.readAllLines(service), found);
        } else try (JarFile jar = new JarFile(entry.toFile())) {
            var service = jar.getJarEntry(SERVICE);
            if (service != null) try (var in = jar.getInputStream(service)) {
                providers(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList(), found);
            }
        }
    }

    private static void providers(List<String> lines, Set<String> found) {
        for (String line : lines) {
            String name = line.split("#", 2)[0].trim();
            if (!name.isEmpty()) found.add(name);
        }
    }

    private static String coordinate(Xpp3Dom node, String key) {
        var child = node.getChild(key);
        return child == null ? "" : Objects.toString(child.getValue(), "").trim();
    }

    private Xpp3Dom child(String key) { return config == null ? null : config.getChild(key); }

    private String property(String key, String fallback) {
        String projectValue = project.getProperties().getProperty(key, fallback);
        return session == null ? projectValue : session.getUserProperties().getProperty(key, projectValue);
    }

    private String value(String key, String fallback) throws IOException {
        var field = child(key);
        String value = field == null ? property("maven.compiler." + key, fallback) : field.getValue();
        if (value == null || value.contains("${")) throw new IOException("unresolved compiler option " + key);
        return value.trim();
    }

    private boolean bool(String key, boolean fallback) throws IOException {
        String flag = value(key, Boolean.toString(fallback));
        if (!flag.equalsIgnoreCase("true") && !flag.equalsIgnoreCase("false")) throw new IOException("invalid compiler option " + key);
        return Boolean.parseBoolean(flag);
    }

    private String snapshot() throws Exception {
        Path dir = Path.of(project.getBuild().getDirectory(), "reclazz");
        Files.createDirectories(dir);
        StringWriter writer = new StringWriter();
        manifest.store(writer, null);
        String data = writer.toString();
        data = data.substring(data.indexOf('\n') + 1);
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path dest = dir.resolve("javac-" + hash + ".properties");
        if (!Files.isRegularFile(dest)) {
            Path tmp = Files.createTempFile(dir, "javac-", ".tmp");
            try {
                Files.write(tmp, bytes);
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(tmp); }
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(dest.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
    }
}
