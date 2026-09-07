/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.compiler;

import com.onurkat.reclazz.hybris.ExtensionInfo;
import com.onurkat.reclazz.hybris.HybrisContext;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;

import javax.tools.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Compiles source groups into staging with javac and the application classpath.
 * CompileAttempt owns publication after all groups and modules succeed.
 */
public class IncrementalCompiler {

    private final String classpath;
    private final HybrisContext context; // null for non-Hybris platforms
    private final PlatformContext platformContext; // used for non-Hybris platforms
    private final JavaCompiler compiler;

    public IncrementalCompiler(String classpath, HybrisContext context) {
        this.classpath = classpath;
        this.context = context;
        this.platformContext = null;
        this.compiler = ToolProvider.getSystemJavaCompiler();

        if (this.compiler == null) {
            StatusReporter.error("No Java compiler found! Make sure you're running with a JDK, not JRE.");
            throw new RuntimeException("javax.tools.JavaCompiler not available. JDK required.");
        }
    }

    public IncrementalCompiler(String classpath, PlatformContext platformContext) {
        this.classpath = classpath;
        this.context = null;
        this.platformContext = platformContext;
        this.compiler = ToolProvider.getSystemJavaCompiler();

        if (this.compiler == null) {
            StatusReporter.error("No Java compiler found! Make sure you're running with a JDK, not JRE.");
            throw new RuntimeException("javax.tools.JavaCompiler not available. JDK required.");
        }
    }

    /** A convenience attempt for callers compiling one module. */
    public CompileResult compile(Path javaFile, String moduleName) {
        return compileBatch(List.of(javaFile), moduleName);
    }

    public CompileResult compileBatch(List<Path> javaFiles, String moduleName) {
        return compilePackage(Map.of(moduleName, javaFiles));
    }

    public CompileResult compilePackage(Map<String, List<Path>> modules) {
        return new CompileAttempt(this).compile(modules);
    }

    List<String> dependencies(String module) {
        if (context == null) return List.of();
        ExtensionInfo extension = context.getExtensions().get(module);
        if (extension == null) throw new IllegalArgumentException("Unknown extension: " + module);
        return extension.getRequiredExtensions();
    }

    Map<Path, List<Path>> groups(String module, List<Path> sources) {
        Map<Path, List<Path>> groups = new LinkedHashMap<>();
        ExtensionInfo ext = context == null ? null : context.getExtensions().get(module);
        if (context != null && ext == null) throw new IllegalArgumentException("Unknown extension: " + module);
        // The core output is available before a web group compiles against it.
        List<Path> ordered = new ArrayList<>(sources);
        if (ext != null) ordered.sort(Comparator.comparing(f -> f.startsWith(ext.getPath().resolve("web/src"))));
        for (Path source : ordered) {
            Path output = ext == null ? genericOutput() : resolveOutputDir(source, ext);
            groups.computeIfAbsent(output, p -> new ArrayList<>()).add(source);
        }
        return groups;
    }

    private Path genericOutput() {
        if (platformContext != null) {
            for (var dirs : platformContext.getClassOutputDirs().values()) {
                if (!dirs.isEmpty()) return dirs.get(0);
            }
        }
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path maven = cwd.resolve("target/classes");
        if (Files.isDirectory(maven)) return maven;
        Path gradle = cwd.resolve("build/classes/java/main");
        if (Files.isDirectory(gradle)) return gradle;
        return cwd.resolve("classes");
    }

    /** Only writes to staging. The attempt owns publication of every module. */
    List<String> compileStaged(String module, List<Path> files, Path staging, List<Path> preceding) throws IOException {
        Files.createDirectories(staging);
        List<String> options;
        if (context != null) {
            options = buildCompilerOptions(context.getExtensions().get(module), staging);
        } else {
            options = new ArrayList<>(List.of("-d", staging.toString(), "-classpath", classpath,
                    "--release", System.getProperty("java.specification.version", "17"),
                    "-nowarn", "-g", "-parameters"));
            Set<String> roots = new LinkedHashSet<>();
            for (Path file : files) {
                Path root = resolveSourceRoot(file);
                if (root != null) roots.add(root.toString());
            }
            if (!roots.isEmpty()) options.addAll(List.of("-sourcepath", String.join(File.pathSeparator, roots)));
        }
        int cpIndex = options.indexOf("-classpath") + 1;
        List<String> cp = new ArrayList<>();
        for (Path path : preceding) cp.add(path.toString());
        cp.add(options.get(cpIndex));
        options.set(cpIndex, String.join(File.pathSeparator, cp));
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean success;
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.getDefault(), null)) {
            success = compiler.getTask(null, manager, diagnostics, options, null,
                    manager.getJavaFileObjectsFromPaths(files)).call();
        }
        List<String> errors = new ArrayList<>();
        if (!success) {
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    String name = d.getSource() == null ? "?" : Paths.get(d.getSource().toUri()).getFileName().toString();
                    errors.add(name + ":" + d.getLineNumber() + ": " + d.getMessage(Locale.getDefault()));
                }
            }
            if (errors.isEmpty()) errors.add("Compilation failed for " + module);
        }
        return errors;
    }

    /**
     * Resolve the source root directory from a Java file path.
     * E.g., /project/src/main/java/com/example/Foo.java -> /project/src/main/java
     */
    private Path resolveSourceRoot(Path javaFile) {
        // Walk up until we find a typical source root pattern
        Path dir = javaFile.getParent();
        while (dir != null) {
            String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
            if ("java".equals(name) || "src".equals(name) || "gensrc".equals(name)) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private Path resolveOutputDir(Path javaFile, ExtensionInfo extInfo) {
        // Check if the file is under web/src
        String filePath = javaFile.toString();
        String extPath = extInfo.getPath().toString();

        if (filePath.contains(extPath + "/web/src") || filePath.contains(extPath + File.separator + "web" + File.separator + "src")) {
            // Web module classes go to web/webroot/WEB-INF/classes
            return extInfo.getPath().resolve("web").resolve("webroot").resolve("WEB-INF").resolve("classes");
        }

        // Core module classes go to classes/ directory
        return extInfo.getPath().resolve("classes");
    }

    private List<String> buildCompilerOptions(ExtensionInfo extInfo, Path outputDir) {
        List<String> options = new ArrayList<>();

        // Output directory
        options.add("-d");
        options.add(outputDir.toString());

        // Classpath: combine global classpath with extension-specific paths
        StringBuilder cp = new StringBuilder(classpath);

        // Add extension's own classes directory
        Path classesDir = extInfo.getPath().resolve("classes");
        if (Files.isDirectory(classesDir)) {
            cp.append(File.pathSeparator).append(classesDir);
        }

        // Add extension's lib directory jars
        Path libDir = extInfo.getPath().resolve("lib");
        if (Files.isDirectory(libDir)) {
            try (var stream = Files.list(libDir)) {
                stream.filter(p -> p.toString().endsWith(".jar"))
                        .forEach(jar -> cp.append(File.pathSeparator).append(jar));
            } catch (IOException ignored) {}
        }

        // Add web module classpath if applicable
        Path webLib = extInfo.getPath().resolve("web").resolve("webroot").resolve("WEB-INF").resolve("lib");
        if (Files.isDirectory(webLib)) {
            try (var stream = Files.list(webLib)) {
                stream.filter(p -> p.toString().endsWith(".jar"))
                        .forEach(jar -> cp.append(File.pathSeparator).append(jar));
            } catch (IOException ignored) {}
        }
        Path webClasses = extInfo.getPath().resolve("web").resolve("webroot").resolve("WEB-INF").resolve("classes");
        if (Files.isDirectory(webClasses)) {
            cp.append(File.pathSeparator).append(webClasses);
        }

        options.add("-classpath");
        options.add(cp.toString());

        // Use --release to match the running JVM (preferred over -source/-target since Java 9,
        // as it also sets the correct boot classpath for cross-compilation safety)
        String javaVersion = System.getProperty("java.specification.version", "17");
        options.add("--release");
        options.add(javaVersion);

        // Suppress warnings for faster compilation
        options.add("-nowarn");

        // Match the build tool's compile settings: -g keeps the local
        // variable table and -parameters keeps reflective parameter names —
        // Spring MVC needs one of them to resolve @RequestParam names on
        // recompiled controllers.
        options.add("-g");
        options.add("-parameters");

        // Add source path for the extension
        List<String> sourcePaths = new ArrayList<>();
        Path srcDir = extInfo.getPath().resolve("src");
        if (Files.isDirectory(srcDir)) {
            sourcePaths.add(srcDir.toString());
        }
        Path genSrcDir = extInfo.getPath().resolve("gensrc");
        if (Files.isDirectory(genSrcDir)) {
            sourcePaths.add(genSrcDir.toString());
        }
        // Also include platform bootstrap gensrc for model classes
        Path bootstrapGensrc = context.getPlatformHome().resolve("bootstrap").resolve("gensrc");
        if (Files.isDirectory(bootstrapGensrc)) {
            sourcePaths.add(bootstrapGensrc.toString());
        }

        if (!sourcePaths.isEmpty()) {
            options.add("-sourcepath");
            options.add(String.join(File.pathSeparator, sourcePaths));
        }

        return options;
    }

    /**
     * Result of an incremental compilation.
     */
    public static class CompileResult {
        private final boolean success;
        private final Map<String, byte[]> compiledClasses;
        private final List<String> errors;
        private final long compileTimeMs;

        private CompileResult(boolean success, Map<String, byte[]> compiledClasses,
                              List<String> errors, long compileTimeMs) {
            this.success = success;
            this.compiledClasses = compiledClasses != null ? compiledClasses : Map.of();
            this.errors = errors != null ? errors : List.of();
            this.compileTimeMs = compileTimeMs;
        }

        public static CompileResult success(Map<String, byte[]> compiledClasses, long compileTimeMs) {
            return new CompileResult(true, compiledClasses, null, compileTimeMs);
        }

        public static CompileResult failure(List<String> errors) {
            return new CompileResult(false, null, errors, 0);
        }

        public boolean isSuccess() { return success; }
        public Map<String, byte[]> getCompiledClasses() { return compiledClasses; }
        public List<String> getErrors() { return errors; }
        public long getCompileTimeMs() { return compileTimeMs; }
    }
}
