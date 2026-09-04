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
 * Compiles individual Java source files incrementally without requiring ant.
 *
 * Uses the javax.tools.JavaCompiler API (built into JDK) to compile changed files
 * one at a time, using the full Hybris classpath for dependency resolution.
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

    /**
     * Compile a single Java source file.
     *
     * @param javaFile       Path to the .java file
     * @param moduleName     Name of the module/extension containing this file
     * @return CompileResult with compiled bytecode or errors
     */
    public CompileResult compile(Path javaFile, String moduleName) {
        if (context == null) {
            return compileGeneric(javaFile, moduleName);
        }

        long startTime = System.currentTimeMillis();
        List<String> errors = new ArrayList<>();

        try {
            ExtensionInfo extInfo = context.getExtensions().get(moduleName);
            if (extInfo == null) {
                return CompileResult.failure(List.of("Unknown extension: " + moduleName));
            }

            // Determine output directory based on source root
            Path outputDir = resolveOutputDir(javaFile, extInfo);
            Files.createDirectories(outputDir);

            // Build compilation options
            List<String> options = buildCompilerOptions(extInfo, outputDir);

            // Set up diagnostics collector
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

            // Set up file manager (try-with-resources to prevent handle leak)
            boolean success;
            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                    diagnostics, Locale.getDefault(), null)) {

                // Get the source file
                Iterable<? extends JavaFileObject> compilationUnits =
                        fileManager.getJavaFileObjectsFromPaths(List.of(javaFile));

                // Compile
                JavaCompiler.CompilationTask task = compiler.getTask(
                        null, fileManager, diagnostics, options, null, compilationUnits);

                success = task.call();
            }

            if (!success) {
                for (Diagnostic<? extends JavaFileObject> diag : diagnostics.getDiagnostics()) {
                    if (diag.getKind() == Diagnostic.Kind.ERROR) {
                        String error = String.format("%s:%d: %s",
                                javaFile.getFileName(),
                                diag.getLineNumber(),
                                diag.getMessage(Locale.getDefault()));
                        errors.add(error);
                    }
                }
                return CompileResult.failure(errors);
            }

            // Read compiled class files
            Map<String, byte[]> compiledClasses = collectCompiledClasses(javaFile, outputDir, extInfo);

            long elapsed = System.currentTimeMillis() - startTime;
            return CompileResult.success(compiledClasses, elapsed);

        } catch (Exception e) {
            errors.add("Compilation exception: " + e.getMessage());
            return CompileResult.failure(errors);
        }
    }

    /**
     * Compile several Java source files of ONE module in as few javac
     * invocations as possible (one per output directory). A save-all in the
     * IDE or a baseline restore touches many files at once; compiling them
     * one at a time paid the full javac startup cost per file.
     */
    public CompileResult compileBatch(List<Path> javaFiles, String moduleName) {
        if (javaFiles.size() == 1) {
            return compile(javaFiles.get(0), moduleName);
        }
        if (context == null) {
            // Generic platforms: loop (rare bulk-save path, keep it simple)
            Map<String, byte[]> all = new HashMap<>();
            long start = System.currentTimeMillis();
            List<String> errors = new ArrayList<>();
            for (Path f : javaFiles) {
                CompileResult r = compileGeneric(f, moduleName);
                if (!r.isSuccess()) {
                    errors.addAll(r.getErrors());
                } else {
                    all.putAll(r.getCompiledClasses());
                }
            }
            if (!errors.isEmpty()) return CompileResult.failure(errors);
            return CompileResult.success(all, System.currentTimeMillis() - start);
        }

        long startTime = System.currentTimeMillis();
        List<String> errors = new ArrayList<>();
        try {
            ExtensionInfo extInfo = context.getExtensions().get(moduleName);
            if (extInfo == null) {
                return CompileResult.failure(List.of("Unknown extension: " + moduleName));
            }

            // Group by output dir: core src -> classes/, web src -> WEB-INF/classes
            Map<Path, List<Path>> byOutputDir = new LinkedHashMap<>();
            for (Path f : javaFiles) {
                byOutputDir.computeIfAbsent(resolveOutputDir(f, extInfo), k -> new ArrayList<>()).add(f);
            }

            Map<String, byte[]> all = new HashMap<>();
            for (Map.Entry<Path, List<Path>> group : byOutputDir.entrySet()) {
                Path outputDir = group.getKey();
                Files.createDirectories(outputDir);
                List<String> options = buildCompilerOptions(extInfo, outputDir);
                DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

                boolean success;
                try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                        diagnostics, Locale.getDefault(), null)) {
                    Iterable<? extends JavaFileObject> units =
                            fileManager.getJavaFileObjectsFromPaths(group.getValue());
                    success = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
                }

                if (!success) {
                    for (Diagnostic<? extends JavaFileObject> diag : diagnostics.getDiagnostics()) {
                        if (diag.getKind() == Diagnostic.Kind.ERROR) {
                            String source = diag.getSource() != null
                                    ? diag.getSource().getName() : "?";
                            errors.add(String.format("%s:%d: %s",
                                    source.substring(source.lastIndexOf('/') + 1),
                                    diag.getLineNumber(),
                                    diag.getMessage(Locale.getDefault())));
                        }
                    }
                    continue; // other output-dir groups may still succeed
                }

                for (Path f : group.getValue()) {
                    all.putAll(collectCompiledClasses(f, outputDir, extInfo));
                }
            }

            if (all.isEmpty() && !errors.isEmpty()) {
                return CompileResult.failure(errors);
            }
            // Partial failure: report errors but still deliver what compiled
            if (!errors.isEmpty()) {
                StatusReporter.error("Batch compilation had errors:");
                errors.forEach(err -> StatusReporter.error("  " + err));
            }
            return CompileResult.success(all, System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            errors.add("Compilation exception: " + e.getMessage());
            return CompileResult.failure(errors);
        }
    }

    /**
     * Compile a single Java source file for non-Hybris platforms (Spring Boot, generic).
     */
    private CompileResult compileGeneric(Path javaFile, String moduleName) {
        long startTime = System.currentTimeMillis();
        List<String> errors = new ArrayList<>();

        try {
            // Determine output directory from PlatformContext
            Path outputDir = null;
            if (platformContext != null) {
                var outputDirs = platformContext.getClassOutputDirs();
                for (var entry : outputDirs.values()) {
                    if (!entry.isEmpty()) {
                        outputDir = entry.get(0);
                        break;
                    }
                }
            }

            if (outputDir == null) {
                // Fallback: use target/classes or build/classes/java/main
                Path cwd = Paths.get(System.getProperty("user.dir"));
                Path mavenDir = cwd.resolve("target").resolve("classes");
                Path gradleDir = cwd.resolve("build").resolve("classes").resolve("java").resolve("main");
                if (Files.isDirectory(mavenDir)) {
                    outputDir = mavenDir;
                } else if (Files.isDirectory(gradleDir)) {
                    outputDir = gradleDir;
                } else {
                    outputDir = mavenDir;
                }
            }

            Files.createDirectories(outputDir);

            // Build simple compiler options
            List<String> options = new ArrayList<>();
            options.add("-d");
            options.add(outputDir.toString());
            options.add("-classpath");
            options.add(classpath);
            String javaVersion = System.getProperty("java.specification.version", "17");
            options.add("--release");
            options.add(javaVersion);
            options.add("-nowarn");
            // Match the build tool's compile settings: without -g/-parameters
            // Spring MVC cannot resolve @RequestParam names on recompiled
            // controllers (IllegalArgumentException: "parameter name
            // information not available via reflection").
            options.add("-g");
            options.add("-parameters");

            // Try to add source path
            Path sourceRoot = resolveSourceRoot(javaFile);
            if (sourceRoot != null) {
                options.add("-sourcepath");
                options.add(sourceRoot.toString());
            }

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            boolean success;
            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                    diagnostics, Locale.getDefault(), null)) {
                Iterable<? extends JavaFileObject> compilationUnits =
                        fileManager.getJavaFileObjectsFromPaths(List.of(javaFile));
                JavaCompiler.CompilationTask task = compiler.getTask(
                        null, fileManager, diagnostics, options, null, compilationUnits);
                success = task.call();
            }

            if (!success) {
                for (Diagnostic<? extends JavaFileObject> diag : diagnostics.getDiagnostics()) {
                    if (diag.getKind() == Diagnostic.Kind.ERROR) {
                        errors.add(String.format("%s:%d: %s",
                                javaFile.getFileName(), diag.getLineNumber(),
                                diag.getMessage(Locale.getDefault())));
                    }
                }
                return CompileResult.failure(errors);
            }

            // Collect compiled classes
            Map<String, byte[]> classes = new HashMap<>();
            String className = resolveClassNameFromSource(javaFile);
            if (className != null) {
                String classPath = className.replace('.', File.separatorChar);
                Path mainClassFile = outputDir.resolve(classPath + ".class");
                if (Files.exists(mainClassFile)) {
                    classes.put(className, Files.readAllBytes(mainClassFile));
                }
                // Inner classes
                String simpleClassName = className.substring(className.lastIndexOf('.') + 1);
                Path classDir = mainClassFile.getParent();
                if (classDir != null && Files.isDirectory(classDir)) {
                    try (var stream = Files.list(classDir)) {
                        stream.filter(p -> {
                            String name = p.getFileName().toString();
                            return name.startsWith(simpleClassName + "$") && name.endsWith(".class");
                        }).forEach(innerClassFile -> {
                            try {
                                String innerClassName = className.substring(0, className.lastIndexOf('.') + 1) +
                                        innerClassFile.getFileName().toString().replace(".class", "");
                                classes.put(innerClassName, Files.readAllBytes(innerClassFile));
                            } catch (IOException e) {
                                StatusReporter.error("Failed to read inner class: " + innerClassFile);
                            }
                        });
                    }
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            return CompileResult.success(classes, elapsed);
        } catch (Exception e) {
            errors.add("Compilation exception: " + e.getMessage());
            return CompileResult.failure(errors);
        }
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
     * Collect the compiled .class files and return their bytecode.
     * A single .java file may produce multiple .class files (inner classes, anonymous classes).
     */
    private Map<String, byte[]> collectCompiledClasses(Path javaFile, Path outputDir, ExtensionInfo extInfo) throws IOException {
        Map<String, byte[]> classes = new HashMap<>();

        // Determine the expected class file location
        String className = resolveClassName(javaFile, extInfo);
        if (className == null) {
            return classes;
        }

        // Convert class name to path
        String classPath = className.replace('.', File.separatorChar);
        Path mainClassFile = outputDir.resolve(classPath + ".class");

        // Read the main class
        if (Files.exists(mainClassFile)) {
            classes.put(className, Files.readAllBytes(mainClassFile));
        }

        // Find inner classes (ClassName$InnerName.class, ClassName$1.class, etc.)
        String simpleClassName = className.substring(className.lastIndexOf('.') + 1);
        Path classDir = mainClassFile.getParent();
        if (classDir != null && Files.isDirectory(classDir)) {
            try (var stream = Files.list(classDir)) {
                stream.filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith(simpleClassName + "$") && name.endsWith(".class");
                }).forEach(innerClassFile -> {
                    try {
                        String innerClassName = className.substring(0, className.lastIndexOf('.') + 1) +
                                innerClassFile.getFileName().toString().replace(".class", "");
                        classes.put(innerClassName, Files.readAllBytes(innerClassFile));
                    } catch (IOException e) {
                        StatusReporter.error("Failed to read inner class: " + innerClassFile);
                    }
                });
            }
        }

        return classes;
    }

    /**
     * Resolve the fully qualified class name from a Java source file path.
     */
    private String resolveClassName(Path javaFile, ExtensionInfo extInfo) {
        String filePath = javaFile.toString();

        // Try to extract package from the source directories
        String[] sourceRoots = {"src", "web/src", "gensrc", "testsrc"};

        for (String root : sourceRoots) {
            Path sourceRoot = extInfo.getPath().resolve(root);
            if (filePath.startsWith(sourceRoot.toString())) {
                String relative = sourceRoot.relativize(javaFile).toString();
                // Remove .java extension and convert path separators to dots
                return relative.replace(".java", "")
                        .replace(File.separatorChar, '.')
                        .replace('/', '.');
            }
        }

        // Fallback: try to parse the package declaration from the file
        return resolveClassNameFromSource(javaFile);
    }

    private String resolveClassNameFromSource(Path javaFile) {
        try {
            // Not Files.readString: a source that is not UTF-8 threw, and the
            // package declaration this is looking for is ASCII either way.
            String content = com.onurkat.reclazz.util.SourceText.readForScanning(javaFile);
            String packageName = null;
            String className = javaFile.getFileName().toString().replace(".java", "");

            for (String line : content.split("\n")) {
                line = line.trim();
                if (line.startsWith("package ")) {
                    packageName = line.replace("package ", "").replace(";", "").trim();
                    break;
                }
                if (line.startsWith("import ") || line.startsWith("public ") ||
                        line.startsWith("class ") || line.startsWith("interface ")) {
                    break; // No package declaration found
                }
            }

            if (packageName != null) {
                return packageName + "." + className;
            }
            return className;
        } catch (IOException e) {
            return null;
        }
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
