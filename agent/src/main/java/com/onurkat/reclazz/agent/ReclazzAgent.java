/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.compiler.IncrementalCompiler;
import com.onurkat.reclazz.hybris.interceptor.InterceptorReloader;
import com.onurkat.reclazz.hybris.impex.ImpexAutoImporter;
import com.onurkat.reclazz.platform.HybrisPlatformContext;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.platform.PlatformDetector;
import com.onurkat.reclazz.platform.SpringContextInterceptTransformer;
import com.onurkat.reclazz.reload.StructuralReloader;
import com.onurkat.reclazz.spring.SpringReloadOrchestrator;
import com.onurkat.reclazz.transform.ReclazzTransformer;
import com.onurkat.reclazz.transform.ReflectionInterceptTransformer;
import com.onurkat.reclazz.transform.TransformContext;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.watcher.FileWatcher;
import com.onurkat.reclazz.watcher.ChangeEvent;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarFile;

/**
 * Reclazz Java Agent entry point.
 *
 * Supports multiple platforms:
 *
 * Spring Boot:
 *   -javaagent:/path/to/reclazz-agent.jar
 *   -javaagent:/path/to/reclazz-agent.jar=watchDirs=/path/to/target/classes
 *
 * SAP Commerce (Hybris):
 *   -javaagent:/path/to/reclazz-agent.jar
 *   (auto-detects Hybris via platform.home, HYBRIS_BIN_DIR, or classpath)
 *
 * For structural class changes (new methods/fields), use JetBrains Runtime:
 *   -XX:+AllowEnhancedClassRedefinition
 */
public class ReclazzAgent {

    private static volatile Instrumentation instrumentation;
    private static volatile boolean running = false;

    /**
     * Java changes waiting to be compiled. Populated on the watcher thread,
     * drained on the single-threaded reload executor — every drain compiles
     * everything that accumulated while the previous batch was running.
     */
    private static final java.util.LinkedHashMap<Path, ChangeEvent> pendingJavaChanges =
            new java.util.LinkedHashMap<>();
    private static volatile ExecutorService watcherExecutor;
    private static volatile ExecutorService reloadExecutor;
    private static volatile StatusServer statusServer;
    private static volatile JvmCapabilityProbe.ProbeResult probeResult;
    private static volatile TransformContext transformContext;
    private static volatile StructuralReloader structuralReloader;
    private static volatile PlatformContext platformContext;
    private static volatile com.onurkat.reclazz.spring.xml.SpringXmlReloader springXmlReloader;
    private static volatile com.onurkat.reclazz.hybris.codegen.CodegenReloader codegenReloader;

    /**
     * Called when agent is loaded at JVM startup via -javaagent.
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        StatusReporter.banner();
        StatusReporter.info("Agent loaded via -javaagent (premain)");
        initialize(agentArgs);
    }

    /**
     * Called when agent is attached to a running JVM via Attach API.
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        StatusReporter.banner();
        StatusReporter.info("Agent attached to running JVM (agentmain)");
        initialize(agentArgs);
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public static boolean isRunning() {
        return running;
    }

    public static JvmCapabilityProbe.ProbeResult getProbeResult() {
        return probeResult;
    }

    public static TransformContext getTransformContext() {
        return transformContext;
    }

    public static StructuralReloader getStructuralReloader() {
        return structuralReloader;
    }

    public static PlatformContext getPlatformContext() {
        return platformContext;
    }

    private static synchronized void initialize(String agentArgs) {
        if (running) {
            StatusReporter.warn("Reclazz agent already initialized, skipping duplicate init.");
            return;
        }
        try {
            AgentConfig config = AgentConfig.parse(agentArgs);

            // Check JVM capabilities
            reportCapabilities();

            // Determine if structural reload should be enabled
            boolean enableStructural = config.isStructuralReload();
            if (enableStructural && probeResult != null && probeResult.hasEnhancedRedefinition()) {
                enableStructural = false;
                StatusReporter.info("JBR/DCEVM detected: using native enhanced redefinition (Reclazz transform engine disabled)");
            }

            // Initialize bootstrap classloader and transformer if structural reload is enabled
            if (enableStructural) {
                try {
                    installBootstrapJar();
                    StatusReporter.info("Bootstrap classes installed on bootstrap classloader");
                } catch (Exception e) {
                    StatusReporter.error("Failed to install bootstrap JAR: " + e.getMessage());
                    StatusReporter.warn("Structural reload disabled — falling back to method-body-only mode");
                    enableStructural = false;
                }
            }

            // Detect platform and create context
            platformContext = PlatformDetector.detect(config);
            if (platformContext == null) {
                StatusReporter.error("Failed to detect platform. Cannot initialize Reclazz.");
                return;
            }

            boolean isHybris = platformContext.getPlatformId() == PlatformContext.Platform.HYBRIS;

            // Register SpringContextInterceptTransformer for ALL platforms —
            // it instruments AbstractApplicationContext.refresh() to register
            // the context in ApplicationContextHolder. On Hybris this is
            // strictly additive alongside the Registry-based lookup, and
            // covers the case where Registry.getMasterTenant() returns a
            // tenant whose context field isn't populated yet (observed on
            // Commerce Cloud 2205+ where tenant wiring happens late in
            // tenant init, AFTER Spring refresh has completed).
            try {
                SpringContextInterceptTransformer contextTransformer = new SpringContextInterceptTransformer();
                instrumentation.addTransformer(contextTransformer, false);
                StatusReporter.info("Spring context intercept transformer registered");
            } catch (Exception e) {
                StatusReporter.warn("Failed to register Spring context transformer: " + e.getMessage());
            }

            // Initialize platform context
            platformContext.initialize();

            // Attach mode: web contexts refreshed BEFORE the agent loaded are
            // invisible to the intercept transformer — discover them from the
            // running Tomcat so web-layer reloads (MVC re-scan, web beans,
            // caches) actually reach them.
            try {
                int found = com.onurkat.reclazz.platform.TomcatContextScanner.scanAndRegister();
                if (found > 0) {
                    StatusReporter.info("Discovered " + found + " live Spring web context(s) from running Tomcat");
                }
            } catch (Throwable t) {
                StatusReporter.warn("Live web-context discovery failed: " + t.getMessage());
            }

            // Start status server for plugin communication. If the user didn't
            // pass an explicit portFile= or statusPort=, derive a sensible
            // default so the IntelliJ plugin can discover us out of the box —
            // even when the user added a bare `-javaagent:.../reclazz-agent.jar`
            // line to wrapper.conf without args.
            java.nio.file.Path effectivePortFile = config.getPortFile();
            int effectivePort = config.getStatusPort();
            if (effectivePortFile == null) {
                java.nio.file.Path defaultDir;
                if (isHybris && platformContext instanceof HybrisPlatformContext) {
                    defaultDir = ((HybrisPlatformContext) platformContext)
                            .getHybrisContext().getHybrisHome().resolve(".reclazz");
                } else {
                    defaultDir = java.nio.file.Paths.get(System.getProperty("user.dir"), ".reclazz");
                }
                try {
                    java.nio.file.Files.createDirectories(defaultDir);
                    effectivePortFile = defaultDir.resolve("agent.port");
                } catch (Exception e) {
                    StatusReporter.warn("Could not create default port-file directory " + defaultDir + ": " + e.getMessage());
                }
            }
            if (effectivePortFile != null || effectivePort > 0) {
                try {
                    statusServer = new StatusServer(Math.max(0, effectivePort), effectivePortFile);
                    statusServer.start();
                    if (effectivePortFile != null) {
                        StatusReporter.info("Status server port file: " + effectivePortFile);
                    }
                } catch (Exception e) {
                    StatusReporter.warn("Failed to start status server: " + e.getMessage());
                }
            }

            // Set up transform context and structural reloader if enabled
            if (enableStructural) {
                transformContext = new TransformContext();
                transformContext.populateFromPlatformContext(platformContext);
                StatusReporter.info("Structural reload: watching " + transformContext.getWatchedClassCount() + " class directories");

                ReclazzTransformer transformer = new ReclazzTransformer(transformContext, config);
                instrumentation.addTransformer(transformer, true);
                StatusReporter.info("Reclazz ClassFileTransformer registered (retransform-capable)");

                ReflectionInterceptTransformer reflectionTransformer = new ReflectionInterceptTransformer();
                instrumentation.addTransformer(reflectionTransformer, true);
                StatusReporter.info("Reflection intercept transformer registered");

                structuralReloader = new StructuralReloader(instrumentation, transformContext, config, platformContext);
                structuralReloader.setTransformer(transformer);
                StatusReporter.success("Structural reload engine active on " +
                        (probeResult != null ? probeResult.getVmDescription() : "standard JVM"));
                if (!com.onurkat.reclazz.bootstrap.MethodForge.isAvailable()) {
                    StatusReporter.info("Reflection patching disabled (JDK 17+ refactored " +
                            "java.lang.reflect.Method internals). Hot-reload still works; " +
                            "only reflective scans of newly-added methods/fields are degraded.");
                }
            }

            // Set up incremental compiler (only if autoCompile is enabled)
            final IncrementalCompiler compiler;
            if (config.isAutoCompile()) {
                String classpath = platformContext.resolveClasspath();
                if (isHybris) {
                    compiler = new IncrementalCompiler(classpath,
                            ((HybrisPlatformContext) platformContext).getHybrisContext());
                } else {
                    compiler = new IncrementalCompiler(classpath, platformContext);
                }
                StatusReporter.info("AutoCompile enabled: watching source files, compiling internally");
            } else {
                compiler = null;
                StatusReporter.info("Default mode: watching compiled class files");
            }

            // Set up class reloader
            ClassReloader reloader = new ClassReloader(instrumentation);

            // Set up Spring reload orchestrator (works for all platforms)
            SpringReloadOrchestrator springOrchestrator = new SpringReloadOrchestrator(platformContext);

            // Set up XML reloader — parses *-spring.xml changes into a
            // throwaway factory, diffs against the live bean factory, and
            // applies safe changes (property mutations + new-bean adds) in
            // place without destroy+recreate. Unsafe changes become a single
            // "restart required" warning, live context stays untouched.
            springXmlReloader = new com.onurkat.reclazz.spring.xml.SpringXmlReloader(platformContext);

            // Set up Hybris-specific reloaders (only on Hybris platform)
            final InterceptorReloader interceptorReloader;
            final ImpexAutoImporter impexImporter;
            if (isHybris) {
                interceptorReloader = new InterceptorReloader();
                impexImporter = new ImpexAutoImporter();
                // Hybris codegen hot-reload — handles both *-beans.xml
                // (DTOs) and *-items.xml (model classes). On save, the
                // reloader shells out to platform `ant build` in the
                // background and the resulting regenerated .class
                // files flow through the existing structural reload path
                // automatically. For items.xml a HAC updatesystem
                // reminder is emitted because DB schema changes can't
                // be applied safely from here.
                if (platformContext instanceof HybrisPlatformContext) {
                    codegenReloader = new com.onurkat.reclazz.hybris.codegen.CodegenReloader(
                            ((HybrisPlatformContext) platformContext).getHybrisContext());
                }
            } else {
                interceptorReloader = null;
                impexImporter = null;
            }

            // Set up file watcher
            FileWatcher watcher = new FileWatcher(platformContext, config);

            reloadExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Reclazz-Reloader");
                t.setDaemon(true);
                return t;
            });

            // Register the reload pipeline. Java changes are queued BEFORE
            // the executor task is submitted: while one batch compiles, new
            // events accumulate in the queue and the next drain compiles
            // them together in a single javac invocation (save-all in the
            // IDE used to pay full javac startup per file, serially).
            watcher.onFileChange(event -> {
                String fn = event.getPath().getFileName().toString();
                if (fn.endsWith(".java") && config.isAutoCompile() && compiler != null
                        && event.getType() != ChangeEvent.Type.DELETED) {
                    synchronized (pendingJavaChanges) {
                        pendingJavaChanges.put(event.getPath(), event);
                    }
                }
                reloadExecutor.submit(() ->
                        handleChange(event, compiler, reloader,
                                springOrchestrator, interceptorReloader, impexImporter, config));
            });

            watcherExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Reclazz-Watcher");
                t.setDaemon(true);
                return t;
            });

            running = true;
            watcherExecutor.submit(watcher::startWatching);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running = false;
                watcher.stopWatching();
                if (statusServer != null) {
                    statusServer.stop();
                }
                if (reloadExecutor != null) {
                    reloadExecutor.shutdownNow();
                }
                if (watcherExecutor != null) {
                    watcherExecutor.shutdownNow();
                }
                StatusReporter.info("Reclazz agent stopped.");
            }, "Reclazz-Shutdown"));

            StatusReporter.success("Reclazz is active. Watching for changes...");
            StatusReporter.info("Press Ctrl+C or stop the server to deactivate.");

        } catch (Exception e) {
            StatusReporter.error("Failed to initialize Reclazz: " + e.getMessage());
            StatusReporter.error("  Stack trace: " + e);
            if (statusServer != null) {
                try { statusServer.stop(); } catch (Exception ignored) {}
                statusServer = null;
            }
            if (reloadExecutor != null) {
                reloadExecutor.shutdownNow();
                reloadExecutor = null;
            }
            if (watcherExecutor != null) {
                watcherExecutor.shutdownNow();
                watcherExecutor = null;
            }
        }
    }

    /**
     * Extract the bootstrap JAR from resources and append it to the bootstrap classloader.
     */
    private static void installBootstrapJar() throws Exception {
        try (InputStream is = ReclazzAgent.class.getResourceAsStream("/META-INF/reclazz-bootstrap.bin")) {
            if (is == null) {
                throw new IllegalStateException("reclazz-bootstrap.bin not found in agent resources");
            }
            // Use PID-scoped path to avoid race between multiple JVMs
            Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
            Path tempJar = tempDir.resolve("reclazz-bootstrap-" + ProcessHandle.current().pid() + ".jar");
            Files.copy(is, tempJar, StandardCopyOption.REPLACE_EXISTING);
            tempJar.toFile().deleteOnExit();
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(tempJar.toFile()));
        }
    }

    private static void handleChange(ChangeEvent event,
                                     IncrementalCompiler compiler,
                                     ClassReloader reloader,
                                     SpringReloadOrchestrator springOrchestrator,
                                     InterceptorReloader interceptorReloader,
                                     ImpexAutoImporter impexImporter,
                                     AgentConfig config) {
        try {
            String fileName = event.getPath().getFileName().toString();

            if (fileName.endsWith(".class") && event.getType() != ChangeEvent.Type.DELETED) {
                handleClassFileChange(event, reloader, springOrchestrator, interceptorReloader);
            } else if (fileName.endsWith(".java") && config.isAutoCompile() && compiler != null) {
                handleJavaBatch(compiler, reloader, springOrchestrator, interceptorReloader);
            } else if (fileName.endsWith(".java") && !config.isAutoCompile()) {
                StatusReporter.info("Source changed: " + fileName + " [" + event.getModuleName() + "]");
                StatusReporter.info("Build your project to compile, Reclazz will hot-swap automatically.");
            } else if (fileName.endsWith("-spring.xml")) {
                handleSpringXmlChange(event);
            } else if (fileName.endsWith(".properties") || fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
                handlePropertiesChange(event);
            } else if (fileName.endsWith(".impex") && config.isAutoImpex() && impexImporter != null) {
                handleImpexChange(event, impexImporter);
            } else if (fileName.endsWith("-items.xml") || fileName.endsWith("-beans.xml")) {
                handleCodegenXmlChange(event);
            }
        } catch (Throwable e) {
            // Throwable, not Exception: ASM and defineHiddenClass surface
            // LinkageError/VerifyError, which previously escaped this handler
            // and silently killed the batch (no event, no log — a test just
            // times out waiting for RELOAD).
            StatusReporter.error("Error handling change: " + e);
            if (e.getCause() != null) {
                StatusReporter.error("  Caused by: " + e.getCause());
            }
        }
    }

    private static void handleClassFileChange(ChangeEvent event,
                                              ClassReloader reloader,
                                              SpringReloadOrchestrator springOrchestrator,
                                              InterceptorReloader interceptorReloader) {
        Path classFile = event.getPath();
        String moduleName = event.getModuleName();

        // Resolve class name using platform context
        String className = platformContext.resolveClassName(classFile);
        if (className == null) {
            StatusReporter.warn("Could not determine class name for: " + classFile);
            return;
        }

        String displayName = className.contains("$")
                ? className.substring(0, className.indexOf('$')) + " (inner class)"
                : className;

        StatusReporter.info("Class file changed: " + classFile.getFileName() + " [" + moduleName + "]");

        try {
            byte[] bytecode = Files.readAllBytes(classFile);
            String internalName = className.replace('.', '/');

            if (transformContext != null && !transformContext.isWatched(internalName)
                    && transformContext.isInWatchedDir(classFile)) {
                transformContext.addWatched(internalName);
                StatusReporter.info("New class added to watched set: " + className);
            }

            long startTime = System.currentTimeMillis();

            ClassReloader.ReloadResult reloadResult;
            if (structuralReloader != null && transformContext != null
                    && transformContext.isWatched(internalName)) {
                reloadResult = structuralReloader.reload(className, bytecode);
            } else {
                reloadResult = reloader.reload(className, bytecode);
            }

            long elapsed = System.currentTimeMillis() - startTime;

            if (reloadResult.isSuccess()) {
                if (reloadResult.isStructuralReload()) {
                    StatusReporter.structuralReload(displayName, elapsed);
                } else {
                    StatusReporter.reload(displayName, elapsed);
                }

                // Run Spring reloaders
                if (reloadResult.isSpringBean()) {
                    Class<?> reloadedClass = findLoadedClass(className);
                    springOrchestrator.onClassReloaded(className, reloadedClass,
                            reloadResult.isStructuralReload());
                }

                // Hybris-specific: interceptor reload
                if (reloadResult.isInterceptor() && interceptorReloader != null) {
                    interceptorReloader.reloadInterceptor(className,
                            ((HybrisPlatformContext) platformContext).getHybrisContext());
                    StatusReporter.success("Interceptor reloaded: " + displayName);
                }
            } else {
                StatusReporter.error("Hot-swap failed for " + displayName + ": " + reloadResult.getError());
                if (reloadResult.isStructuralChange()) {
                    StatusReporter.warn(reloadResult.getStructuralChangeAdvice());
                }
            }
        } catch (Exception e) {
            StatusReporter.error("Failed to read class file " + classFile + ": " + e.getMessage());
        }
    }

    private static void handleJavaBatch(IncrementalCompiler compiler,
                                        ClassReloader reloader,
                                        SpringReloadOrchestrator springOrchestrator,
                                        InterceptorReloader interceptorReloader) {
        java.util.List<ChangeEvent> batch;
        synchronized (pendingJavaChanges) {
            if (pendingJavaChanges.isEmpty()) return; // drained by an earlier task
            batch = new java.util.ArrayList<>(pendingJavaChanges.values());
            pendingJavaChanges.clear();
        }

        // Group by module — each module compiles as ONE javac invocation
        java.util.LinkedHashMap<String, List<Path>> byModule = new java.util.LinkedHashMap<>();
        for (ChangeEvent e : batch) {
            byModule.computeIfAbsent(e.getModuleName(), k -> new java.util.ArrayList<>()).add(e.getPath());
        }

        Map<String, byte[]> compiledClasses = new java.util.LinkedHashMap<>();
        for (var moduleEntry : byModule.entrySet()) {
            String moduleName = moduleEntry.getKey();
            List<Path> files = moduleEntry.getValue();
            if (files.size() == 1) {
                StatusReporter.info("Java file changed: " + files.get(0).getFileName() + " [" + moduleName + "]");
            } else {
                StatusReporter.info("Java files changed: " + files.size() + " files [" + moduleName + "] — batch compiling");
            }

            IncrementalCompiler.CompileResult result = compiler.compileBatch(files, moduleName);
            if (!result.isSuccess()) {
                StatusReporter.error("Compilation failed:");
                result.getErrors().forEach(err -> StatusReporter.error("  " + err));
                continue;
            }
            for (Path f : files) {
                StatusReporter.compile(f.getFileName().toString(), result.getCompileTimeMs());
            }
            compiledClasses.putAll(result.getCompiledClasses());
        }
        if (compiledClasses.isEmpty()) return;

        long startTime = System.currentTimeMillis();
        int successCount = 0;
        int failCount = 0;
        // class name -> was it a structural reload (drives event level below)
        java.util.LinkedHashMap<String, Boolean> swappedClasses = new java.util.LinkedHashMap<>();

        // Dependent cascade + stale-reference healing sweep every singleton
        // in every context, so run them once for the whole batch.
        springOrchestrator.beginBatch();
        try {
        for (var entry : compiledClasses.entrySet()) {
            String className = entry.getKey();
            byte[] bytecode = entry.getValue();
            String internalName = className.replace('.', '/');

            if (transformContext != null && !transformContext.isWatched(internalName)) {
                transformContext.addWatched(internalName);
            }

            // Per-class isolation: one class failing (possibly with an Error,
            // e.g. VerifyError from companion generation on a nested class)
            // must not abort the rest of the batch.
            ClassReloader.ReloadResult reloadResult;
            try {
                if (structuralReloader != null && transformContext != null
                        && transformContext.isWatched(internalName)) {
                    reloadResult = structuralReloader.reload(className, bytecode);
                } else {
                    reloadResult = reloader.reload(className, bytecode);
                }
            } catch (Throwable t) {
                reloadResult = ClassReloader.ReloadResult.failure(
                        "Unexpected " + t.getClass().getSimpleName() + ": " + t.getMessage(), false);
            }

            if (reloadResult.isSuccess()) {
                successCount++;
                swappedClasses.put(className, reloadResult.isStructuralReload());

                if (reloadResult.isSpringBean()) {
                    Class<?> reloadedClass = findLoadedClass(className);
                    springOrchestrator.onClassReloaded(className, reloadedClass,
                            reloadResult.isStructuralReload());
                }

                if (reloadResult.isInterceptor() && interceptorReloader != null) {
                    interceptorReloader.reloadInterceptor(className,
                            ((HybrisPlatformContext) platformContext).getHybrisContext());
                    StatusReporter.success("Interceptor reloaded: " + className);
                }
            } else {
                failCount++;
                StatusReporter.error("Hot-swap failed for " + className + ": " + reloadResult.getError());
                if (reloadResult.isStructuralChange()) {
                    StatusReporter.warn(reloadResult.getStructuralChangeAdvice());
                }
            }
        }
        } finally {
            springOrchestrator.endBatch();
        }

        long elapsed = System.currentTimeMillis() - startTime;

        // Emit STRUCTURAL_RELOAD vs RELOAD per class. The autoCompile path
        // used to always emit plain RELOAD, so STRUCTURAL_RELOAD events (and
        // the IDE widget's structural counter) never fired in this mode.
        if (compiledClasses.size() == 1 && successCount == 1) {
            var only = swappedClasses.entrySet().iterator().next();
            if (only.getValue()) {
                StatusReporter.structuralReload(only.getKey(), elapsed);
            } else {
                StatusReporter.reload(only.getKey(), elapsed);
            }
        } else if (compiledClasses.size() > 1) {
            for (var entry : swappedClasses.entrySet()) {
                if (entry.getValue()) {
                    StatusReporter.structuralReload(entry.getKey(), -1);
                } else {
                    StatusReporter.reload(entry.getKey(), -1);
                }
            }
            String summary = String.format("Batch summary: %d/%d classes hot-swapped in %dms",
                    successCount, compiledClasses.size(), elapsed);
            if (failCount > 0) {
                StatusReporter.warn(summary + " (" + failCount + " failed)");
            } else {
                StatusReporter.success(summary);
            }
        }
    }

    private static void handleSpringXmlChange(ChangeEvent event) {
        StatusReporter.info("Spring XML changed: " + event.getPath().getFileName());
        com.onurkat.reclazz.spring.xml.SpringXmlReloader reloader = springXmlReloader;
        if (reloader == null) {
            StatusReporter.warn("Spring XML reload not available — agent not fully initialized");
            return;
        }
        reloader.reload(event.getPath());
    }

    private static void handlePropertiesChange(ChangeEvent event) {
        StatusReporter.info("Config file changed: " + event.getPath().getFileName());
        StatusReporter.warn("Configuration changes may require a restart to take effect.");
    }

    private static void handleImpexChange(ChangeEvent event,
                                          ImpexAutoImporter impexImporter) {
        StatusReporter.info("ImpEx file changed: " + event.getPath().getFileName());
        impexImporter.importFile(event.getPath(),
                ((HybrisPlatformContext) platformContext).getHybrisContext());
    }

    /**
     * Unified dispatch for both *-beans.xml and *-items.xml saves —
     * the {@link com.onurkat.reclazz.hybris.codegen.CodegenReloader}
     * picks the right kind from the file suffix and customises its
     * post-run reporting accordingly.
     */
    private static void handleCodegenXmlChange(ChangeEvent event) {
        com.onurkat.reclazz.hybris.codegen.CodegenReloader reloader = codegenReloader;
        if (reloader == null) {
            StatusReporter.info("Codegen XML changed: " + event.getPath().getFileName());
            StatusReporter.warn("Codegen reload requires a Hybris platform — not available here.");
            return;
        }
        reloader.handle(event);
    }

    private static Class<?> findLoadedClass(String className) {
        return ClassLookup.findLoadedClass(className, instrumentation);
    }

    private static void reportCapabilities() {
        boolean canRedefine = instrumentation.isRedefineClassesSupported();
        boolean canRetransform = instrumentation.isRetransformClassesSupported();

        StatusReporter.info("JVM Capabilities:");
        StatusReporter.info("  Redefine classes: " + (canRedefine ? "YES" : "NO"));
        StatusReporter.info("  Retransform classes: " + (canRetransform ? "YES" : "NO"));

        probeResult = JvmCapabilityProbe.probe(instrumentation);

        StatusReporter.info("  VM: " + probeResult.getVmDescription());
        StatusReporter.info("  Detection method: " + probeResult.getDetectionMethod());

        if (probeResult.hasEnhancedRedefinition()) {
            StatusReporter.success("  Structural hot-reload: enabled via enhanced redefinition (native)");
        } else {
            StatusReporter.success("  Structural hot-reload: enabled via Reclazz companion-class reloader");
        }

        if (JvmCapabilityProbe.isGraalVM()) {
            StatusReporter.warn("  GraalVM detected — class redefinition support is limited.");
            StatusReporter.warn("  Some method body changes may fail. Consider using a standard OpenJDK or JBR instead.");
        }
    }
}
