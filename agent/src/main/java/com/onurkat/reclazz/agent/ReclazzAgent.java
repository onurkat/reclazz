/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.util.ClassLookup;

import com.onurkat.reclazz.config.AgentConfig;

import com.onurkat.reclazz.ui.RestartLedger;

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
import com.onurkat.reclazz.transform.JacksonAccessorTransformer;
import com.onurkat.reclazz.transform.TransformContext;
import com.onurkat.reclazz.ui.ReloadEffects;
import com.onurkat.reclazz.ui.ReloadEvents;
import com.onurkat.reclazz.ui.SessionLog;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.watcher.FileWatcher;
import com.onurkat.reclazz.watcher.ChangeEvent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarFile;
import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.bootstrap.MethodForge;
import com.onurkat.reclazz.bootstrap.ProtectedCallResolver;
import com.onurkat.reclazz.bootstrap.UnsafeAccess;
import com.onurkat.reclazz.transform.RequestBoundaryTransformer;
import com.onurkat.reclazz.transform.CacheComputationTransformer;
import com.onurkat.reclazz.bootstrap.CacheDependencyLedger;
import com.onurkat.reclazz.hybris.HybrisConfigReloader;
import com.onurkat.reclazz.hybris.HybrisLocalizationReloader;
import com.onurkat.reclazz.hybris.PropertyFileSnapshots;
import com.onurkat.reclazz.hybris.backoffice.BackofficeConfigReloader;
import com.onurkat.reclazz.hybris.codegen.CodegenReloader;
import com.onurkat.reclazz.platform.TomcatContextScanner;
import com.onurkat.reclazz.reload.ConstantChangeWarning;
import com.onurkat.reclazz.reload.ConstantDependents;
import com.onurkat.reclazz.reload.JpaMappingRefresh;
import com.onurkat.reclazz.reload.LoggingReloader;
import com.onurkat.reclazz.reload.TemplateReloader;
import com.onurkat.reclazz.spring.SpringMessageSourceReloader;
import com.onurkat.reclazz.spring.SpringPropertyRebinder;
import com.onurkat.reclazz.spring.xml.SpringXmlReloader;
import com.onurkat.reclazz.transform.ReflectionRootFilter;
import com.onurkat.reclazz.transform.TemplateInterceptTransformer;
import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.Plural;
import com.onurkat.reclazz.util.BundleCache;
import com.onurkat.reclazz.util.ContentChangeGuard;
import com.onurkat.reclazz.util.Supervised;
import com.onurkat.reclazz.watcher.ChangeKind;

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

    /**
     * What each platform property file said last time, so a save is read as the
     * edit it was. See PropertyFileSnapshots.
     */
    private static final PropertyFileSnapshots propertySnapshots =
            new PropertyFileSnapshots();

    /**
     * Localization saves defer expensive work to whoever reads next, so a save
     * that changed nothing is worth recognising. See handleLocalizationChange.
     */
    private static final ContentChangeGuard localizationGuard =
            new ContentChangeGuard();
    private static volatile boolean running = false;

    /**
     * Java changes waiting to be compiled. Populated on the watcher thread,
     * drained on the single-threaded reload executor — every drain compiles
     * everything that accumulated while the previous batch was running.
     */
    // Retries are driven by a new source event, not leftover tasks from one save.
    private static long javaChangeGeneration;
    private static long lastJavaAttemptGeneration = -1;
    private static final java.util.LinkedHashMap<Path, ChangeEvent> pendingJavaChanges =
            new java.util.LinkedHashMap<>();

    private static volatile ExecutorService watcherExecutor;
    /** The reload thread and what is queued for it; see {@link ReloadQueue}. */
    private static volatile ReloadQueue reloadQueue;
    private static volatile StatusServer statusServer;

    /**
     * Answers "why didn't my class reload?". Kept here because the answer is
     * assembled from what the watcher, the transformer and the JVM each know.
     */
    private static volatile ReloadDiagnostics diagnostics;
    private static volatile JvmCapabilityProbe.ProbeResult probeResult;
    private static volatile TransformContext transformContext;
    private static volatile StructuralReloader structuralReloader;
    private static final TemplateReloader templateReloader =
            new TemplateReloader();
    private static volatile PlatformContext platformContext;
    private static volatile SpringXmlReloader springXmlReloader;
    private static volatile CodegenReloader codegenReloader;

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
        attached = true;
        StatusReporter.banner();
        StatusReporter.info("Agent attached to running JVM (agentmain)");
        initialize(agentArgs);
    }

    /**
     * Name the classes allowed to read a captured lookup, then close the list.
     *
     * <p>First thing either entry point does, so it is done before any
     * application class has run and there is no window in which something else
     * could add itself. {@code X.class} loads a class without initialising it,
     * so naming them here does not start the engine early.
     */
    private static void trustEngineCallers() {
        LookupCapture.trust(
                ReflectionRootFilter.class);
        LookupCapture.trust(
                StructuralReloader.class);
        LookupCapture.trust(
                ProtectedCallResolver.class);
        LookupCapture.trust(
                com.onurkat.reclazz.spring.AddedScheduledAdapter.class);
        LookupCapture.trust(
                com.onurkat.reclazz.spring.AddedEventListenerAdapter.class);
        LookupCapture.trust(
                com.onurkat.reclazz.spring.AddedBeanAdapter.class);
        LookupCapture.trust(
                com.onurkat.reclazz.spring.AddedKafkaListenerAdapter.class);
        LookupCapture.trust(
                com.onurkat.reclazz.spring.AddedJmsListenerAdapter.class);
        LookupCapture.trust(com.onurkat.reclazz.spring.AddedRabbitListenerAdapter.class);
        LookupCapture.seal();
    }

    /** True when we arrived through the Attach API rather than -javaagent. */
    private static volatile boolean attached;

    public static JvmCapabilityProbe.ProbeResult getProbeResult() {
        return probeResult;
    }

    /** The parsed agent arguments, or null before {@link #premain} has run. */
    public static AgentConfig getConfig() {
        return agentConfig;
    }

    private static volatile AgentConfig agentConfig;
    private static boolean cacheTrackingInstalled;

    /**
     * How to rebuild the sources that inlined a changed constant, or null when
     * the project's own build owns compilation and Reclazz only names them.
     */
    private static volatile java.util.function.Consumer<Map<String, List<Path>>> constantRebuild;

    /**
     * Run first inside the guarded start, by tests only, to stand in for a
     * failure the start-up has no other way to be given.
     */
    static volatile Runnable startProbe;

    private static synchronized void initialize(String agentArgs) {
        if (running) {
            StatusReporter.warn("Reclazz agent already initialised, skipping duplicate init.");
            return;
        }
        try {
            Runnable probe = startProbe;
            if (probe != null) probe.run();
            AgentConfig config = AgentConfig.parse(agentArgs);
            if (config.isRequestBoundary() && attached) {
                StatusReporter.error("reloadBoundary=request requires -javaagent at JVM startup; no reloads started.");
                return;
            }
            // Before anything else prints: the first lines of a session are
            // the banner and the capability report, and they should be laid
            // out the same way as everything after them.
            StatusReporter.setWrapMode(config.getWrapOutput());
            StatusReporter.setVerbose(config.isVerbose());
            agentConfig = config;
            // Before anything else is said, so the record has the whole session.
            if (config.getSessionLog() != null) {
                SessionLog record = SessionLog.open(config.getSessionLog(), AgentVersion.get());
                if (record != null) {
                    StatusReporter.addListener(record);
                    StatusReporter.info("Session log: " + record.file());
                }
            }
            // Before anything else is timed against it: the report's clock is
            // the agent's, not the first moment somebody asks for the report.
            SessionReport.sessionStarted(java.time.Instant.now());

            // After the wrap mode is set, so a long list of names is laid out,
            // and before anything acts on the configuration, so that a setting
            // the developer thinks they passed is corrected before its absence
            // is felt.
            config.reportUnknownKeys();

            // Check JVM capabilities
            reportCapabilities();

            // Determine if structural reload should be enabled
            boolean enableStructural = config.isStructuralReload();
            if (enableStructural && probeResult != null && probeResult.hasEnhancedRedefinition()) {
                enableStructural = false;
                StatusReporter.info("JBR/DCEVM detected: using native enhanced redefinition (Reclazz transform engine disabled)");
            }

            // The bootstrap classes go in whatever the mode, because the
            // companion engine is not the only thing that needs them: a
            // rewritten template engine constructor calls the registry, and on
            // a JVM with native enhanced redefinition, where the companion
            // engine is switched off, that class was simply absent. A Spring
            // Boot application using Thymeleaf then failed to start at all,
            // on the very runtime this project recommends for structural work.
            boolean bootstrapInstalled;
            try {
                installBootstrapJar();
                bootstrapInstalled = true;
                // The enum work writes final fields, and the door it uses is
                // on a removal schedule. The second door is a JDK-internal
                // package this Instrumentation can open; handing it over costs
                // nothing and opens nothing until the first door refuses.
                UnsafeAccess.useForFallback(instrumentation);
                if (config.isVerbose()) StatusReporter.info("Bootstrap classes installed on bootstrap classloader");
            } catch (Exception e) {
                bootstrapInstalled = false;
                StatusReporter.error("Failed to install bootstrap JAR: " + Failures.describe(e));
                if (enableStructural) {
                    StatusReporter.warn("Structural reload disabled — falling back to method-body-only mode");
                    enableStructural = false;
                }
            }

            if (bootstrapInstalled) {
                CacheDependencyLedger.reporter(message -> {
                    if (message.contains("failed")) StatusReporter.warn(message);
                    else StatusReporter.detail(message);
                });
                CacheDependencyLedger.configure(!attached);
                try {
                    instrumentation.addTransformer(new CacheComputationTransformer(), true);
                    cacheTrackingInstalled = true;
                    for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                        if (loaded.getName().equals(CacheComputationTransformer.TARGET.replace('/', '.'))) {
                            CacheDependencyLedger.partialCoverage();
                            instrumentation.retransformClasses(loaded);
                        }
                    }
                } catch (Throwable failure) {
                    CacheDependencyLedger.partialCoverage();
                    StatusReporter.warn("Spring cache dependency observation is partial; annotation fallback retained");
                }
            }

            if (config.isRequestBoundary()) {
                if (!bootstrapInstalled) {
                    StatusReporter.error("Request boundary needs the bootstrap jar; no reloads started.");
                    return;
                }
                for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                    if (loaded.getName().equals(RequestBoundaryTransformer.TARGET.replace('/', '.'))) {
                        StatusReporter.error("Spring MVC loaded before the request hook; no reloads started. "
                                + "Place Reclazz before agents that load Spring MVC.");
                        return;
                    }
                }
                instrumentation.addTransformer(new RequestBoundaryTransformer(), false);
                StatusReporter.info("Request boundary enabled: synchronous MVC class reloads wait for active requests");
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
                if (config.isVerbose()) StatusReporter.info("Spring context intercept transformer registered");
                // The rewritten constructor calls the registry, so without the
                // bootstrap classes this would break the engine it is meant to
                // reload.
                if (bootstrapInstalled) {
                    instrumentation.addTransformer(
                            new TemplateInterceptTransformer(), false);
                }
            } catch (Exception e) {
                StatusReporter.warn("Failed to register Spring context transformer: " + Failures.describe(e));
            }

            // Initialize platform context
            platformContext.initialize();

            // Attach mode: web contexts refreshed BEFORE the agent loaded are
            // invisible to the intercept transformer — discover them from the
            // running Tomcat so web-layer reloads (MVC re-scan, web beans,
            // caches) actually reach them.
            try {
                int found = TomcatContextScanner.scanAndRegister();
                if (found > 0) {
                    StatusReporter.info("Discovered "
                            + Plural.of(found, "live Spring web context")
                            + " from running Tomcat");
                }
            } catch (Throwable t) {
                StatusReporter.warn("Live web-context discovery failed: " + Failures.describe(t));
            }

            startStatusServer(config, isHybris);

            // What the JPA rebuild needs to know, handed over rather than read
            // back off this class.
            final JvmCapabilityProbe.ProbeResult probeForJpa = probeResult;
            com.onurkat.reclazz.reload.JpaMappingRefresh.configure(
                    config::isJpaRefresh,
                    () -> probeForJpa != null && probeForJpa.hasEnhancedRedefinition());

            // Set up transform context and structural reloader if enabled
            if (enableStructural) {
                transformContext = new TransformContext();
                transformContext.populateFromPlatformContext(platformContext);
                if (config.isVerbose()) StatusReporter.info("Structural reload: watching " + transformContext.getWatchedClassCount() + " class directories");

                ReclazzTransformer transformer = new ReclazzTransformer(transformContext, config);
                instrumentation.addTransformer(transformer, true);
                if (config.isVerbose()) StatusReporter.info("Reclazz ClassFileTransformer registered (retransform-capable)");

                // Jackson must take its specialised metadata route before the general
                // reflection transformer rewrites these same Class calls.
                instrumentation.addTransformer(new JacksonAccessorTransformer(), true);
                instrumentation.addTransformer(new com.onurkat.reclazz.transform.ExceptionHandlerTransformer(), true);
                instrumentation.addTransformer(new com.onurkat.reclazz.transform.MvcBindingTransformer(), true);
                instrumentation.addTransformer(new com.onurkat.reclazz.transform.RabbitConsumerTransformer(), true);
                ReflectionInterceptTransformer reflectionTransformer = new ReflectionInterceptTransformer();
                instrumentation.addTransformer(reflectionTransformer, true);
                if (config.isVerbose()) StatusReporter.info("Reflection intercept transformer registered");

                // This transformer only rewrites classes loaded after it was
                // registered, and nothing retransforms the ones already in
                // memory. Under -javaagent that is nearly everything, so it
                // works. Attached to a running server it is the opposite: the
                // framework loaded long ago, so its reflection call sites are
                // untouched and a structurally added member stays invisible to
                // any scan that looks for it. Say so rather than let it look
                // like the reload simply had no effect.
                if (attached) {
                    StatusReporter.warn("Attached to a running JVM: framework code was loaded "
                            + "before Reclazz, so members added by a structural reload will not "
                            + "be picked up by framework scans. Start with -javaagent for that.");
                }

                // Root-level reflection filtering: hides __reclazz$ members
                // inside the JDK itself, which covers the meta-reflection
                // routes the call-site bridge cannot reach. Probes once; on a
                // JVM that refuses, it says so and the bridge remains the only
                // cover. Per-class registration happens on each reload (the
                // reloader has the Class there); the sweep below catches
                // watched classes that are already loaded AND were transformed,
                // which only exists when initialize runs after classes went
                // through the transformer.
                // Attaching to a running JVM means the watched classes are
                // already loaded, and the transformer will never be shown
                // them. Recorded here so a call site generated later knows
                // they are in the JVM without the transform's members.
                for (Class<?> alreadyLoaded : instrumentation.getAllLoadedClasses()) {
                    String internal = alreadyLoaded.getName().replace('.', '/');
                    if (transformContext.isWatched(internal)) {
                        transformContext.markSeen(internal);
                    }
                }

                ReflectionRootFilter.install(instrumentation);
                for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                    String internal = loaded.getName().replace('.', '/');
                    if (transformContext.isWatched(internal)
                            && transformContext.getMetadata(internal) != null) {
                        ReflectionRootFilter
                                .registerInjectedMembersOn(loaded);
                    }
                }

                structuralReloader = new StructuralReloader(instrumentation, transformContext, config, platformContext);
                structuralReloader.setTransformer(transformer);
                StatusReporter.success("Structural reload engine active on " +
                        (probeResult != null ? probeResult.getVmDescription() : "standard JVM"));
                if (!MethodForge.isAvailable() && config.isVerbose()) {
                    StatusReporter.info("Reflection patching disabled (JDK 17+ refactored " +
                            "java.lang.reflect.Method internals). Hot-reload still works; " +
                            "only reflective scans of newly-added methods/fields are degraded.");
                }
            }

            // After the transform context exists, because half the answer to
            // "why didn't my class reload?" is what that context knows about
            // the class in question.
            diagnostics = new ReloadDiagnostics(instrumentation, platformContext, transformContext,
                    java.time.Instant.now());
            if (statusServer != null) {
                statusServer.setDiagnoser(diagnostics::explain);
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
            springXmlReloader = new SpringXmlReloader(platformContext);

            // Set up Hybris-specific reloaders (only on Hybris platform)
            final InterceptorReloader interceptorReloader;
            final ImpexAutoImporter impexImporter;
            if (isHybris) {
                interceptorReloader = new InterceptorReloader();
                impexImporter = new ImpexAutoImporter(config.isImpexAllowRemove());
                // Hybris codegen hot-reload — handles both *-beans.xml
                // (DTOs) and *-items.xml (model classes). On save, the
                // reloader shells out to platform `ant build` in the
                // background and the resulting regenerated .class
                // files flow through the existing structural reload path
                // automatically. For items.xml a HAC updatesystem
                // reminder is emitted because DB schema changes can't
                // be applied safely from here.
                if (platformContext instanceof HybrisPlatformContext) {
                    codegenReloader = new CodegenReloader(
                            ((HybrisPlatformContext) platformContext).getHybrisContext());
                }
            } else {
                interceptorReloader = null;
                impexImporter = null;
            }

            // Set up file watcher
            FileWatcher watcher = new FileWatcher(platformContext, config);
            watcher.onPropertyFileFound(ReclazzAgent::baselinePropertyFile);

            // Here rather than beside the diagnoser above, because the numbers
            // that separate "nothing reloads" from "nothing I changed was being
            // watched" belong to the watcher, and it does not exist until now.
            if (statusServer != null) {
                statusServer.setScanner(watcher::requestScan);
                statusServer.setHealthReporter(() -> withStall(SessionReport.lines(
                        watcher.watchedDirectoryCount(),
                        watcher.unwatchableCount(),
                        RestartLedger.size(),
                        watcher.watchedFileCount(),
                        watcher.polls())));
            }

            // Single-threaded, and that is a correctness requirement rather
            // than a resource decision. A reload does not only redefine a
            // class: it clears and refills framework state that is shared
            // across the whole application, injection metadata, mapping
            // registries, the validator's constraint caches, the security
            // metadata. None of that is written to be entered twice at once.
            // Serialising batches here is what lets every one of those
            // reloaders be written as though it were the only thing running,
            // which is how they are all written. A pool here would make them
            // racy without a line of them changing.
            // What a class-file batch is bracketed with: the JVM redefinitions
            // applied in one call at the end, then the Spring cascade and
            // healing run once over the result.
            ReloadQueue.Bracket batchBracket = new ReloadQueue.Bracket() {
                @Override
                public void begin() {
                    StructuralReloader structural = structuralReloader;
                    if (structural != null) structural.beginBatch();
                    springOrchestrator.beginBatch();
                }

                @Override
                public void end() {
                    try {
                        StructuralReloader structural = structuralReloader;
                        if (structural != null) structural.endBatch();
                    } finally {
                        springOrchestrator.endBatch();
                    }
                }
            };
            reloadQueue = ReloadQueue.start(
                    event -> handleChange(event, compiler, reloader,
                            springOrchestrator, interceptorReloader, impexImporter, config),
                    batchBracket, config.isRequestBoundary() ? RequestReloadBoundary::run : Runnable::run);

            if (statusServer != null) statusServer.setBuildListener(state -> reloadQueue.build(state, watcher::scanNow));

            // Register the reload pipeline. Java changes are queued BEFORE
            // the executor task is submitted: while one batch compiles, new
            // events accumulate in the queue and the next drain compiles
            // them together in a single javac invocation (save-all in the
            // IDE used to pay full javac startup per file, serially).
            // Class files that became due in the same pass are queued together
            // and drained by one task, so a build's worth of classes is one
            // batch on the reload thread rather than a batch and a straggler.
            watcher.onFileChanges(events -> {
                int classFiles = 0;
                for (ChangeEvent event : events) {
                    String fn = event.getPath().getFileName().toString();
                    if (fn.endsWith(".java") && config.isAutoCompile() && compiler != null) {
                        synchronized (pendingJavaChanges) {
                            javaChangeGeneration++;
                            if (event.getType() == ChangeEvent.Type.DELETED) pendingJavaChanges.remove(event.getPath());
                            else pendingJavaChanges.put(event.getPath(), event);
                        }
                    }
                    if (fn.endsWith(".class") && event.getType() != ChangeEvent.Type.DELETED) {
                        reloadQueue.enqueueClassFile(event);
                        classFiles++;
                        continue;
                    }
                    reloadQueue.submit(
                            "Handling " + event.getPath().getFileName(),
                            () -> handleChange(event, compiler, reloader,
                                    springOrchestrator, interceptorReloader, impexImporter, config));
                }
                if (classFiles > 0) {
                    reloadQueue.submitClassBatch(classFiles);
                }
            });

            // Rebuilding a constant's dependents is the same work as saving
            // them, so it goes through the same queue and the same drain
            // rather than a second compile path that would drift from it.
            // Only when Reclazz is the compiler: otherwise the project's build
            // owns the output directory, and writing into it behind that
            // build's back is not ours to do.
            if (config.isAutoCompile() && compiler != null) {
                constantRebuild = byModule -> {
                    synchronized (pendingJavaChanges) {
                        for (var moduleEntry : byModule.entrySet()) {
                            for (Path file : moduleEntry.getValue()) {
                                javaChangeGeneration++;
                                pendingJavaChanges.put(file, new ChangeEvent(
                                        file, ChangeEvent.Type.MODIFIED,
                                        moduleEntry.getKey(), null));
                            }
                        }
                    }
                    reloadQueue.submit("Rebuilding constant dependents",
                            () -> handleJavaBatch(compiler, reloader,
                                    springOrchestrator, interceptorReloader));
                };
            }

            watcherExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Reclazz-Watcher");
                t.setDaemon(true);
                return t;
            });

            running = true;
            // submit() hands anything thrown to a Future nobody holds. The
            // watcher dying that way is a session where saving a file does
            // nothing, for as long as the server is up, with the IDE still
            // showing a connected agent because the heartbeat is another
            // thread.
            watcherExecutor.submit(Supervised.forever(
                    "The file watcher",
                    "Nothing will reload until this application is restarted.",
                    watcher::startWatching));

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running = false;
                watcher.stopWatching();
                if (statusServer != null) {
                    statusServer.stop();
                }
                if (reloadQueue != null) {
                    reloadQueue.shutdown();
                }
                if (watcherExecutor != null) {
                    watcherExecutor.shutdownNow();
                }
                StatusReporter.info("Reclazz agent stopped.");
            }, "Reclazz-Shutdown"));

            StatusReporter.success("Reclazz is active. Watching for changes...");
            StatusReporter.info("Press Ctrl+C or stop the server to deactivate.");

        } catch (Throwable e) {
            // Throwable, not Exception. Whatever escapes premain ends the JVM
            // before the application's main has run ("processing of -javaagent
            // failed"), and an Error is what an agent's start-up dies of: a
            // class missing from the jar, a static initialiser that threw. The
            // agent failing to start costs hot reload; the application failing
            // to start costs the application, and that is not this tool's
            // call to make.
            StatusReporter.error("Failed to initialize Reclazz: " + Failures.describe(e));
            StatusReporter.error("  The application starts without hot reload.");
            StatusReporter.error("  Stack trace: " + e);
            if (statusServer != null) {
                try { statusServer.stop(); } catch (Exception ignored) {}
                statusServer = null;
            }
            if (reloadQueue != null) {
                reloadQueue.shutdown();
                reloadQueue = null;
            }
            if (watcherExecutor != null) {
                watcherExecutor.shutdownNow();
                watcherExecutor = null;
            }
        }
    }

    /**
     * Start the status server the IDE connects to. Without an explicit
     * {@code portFile=} or {@code statusPort=}, the port file goes where the
     * plugin looks for it, so a bare {@code -javaagent:...reclazz-agent.jar}
     * line in wrapper.conf is found without arguments.
     */
    private static void startStatusServer(AgentConfig config, boolean isHybris) {
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
                StatusReporter.warn("Could not create default port-file directory " + defaultDir + ": " + Failures.describe(e));
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
                StatusReporter.warn("Failed to start status server: " + Failures.describe(e));
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
            Path tempJar = extractBootstrapJar(is);
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(tempJar.toFile()));
            // The bootstrap classes exist as of this line, so this is the
            // earliest the trusted-caller list can be named, and it is still
            // inside premain: no application class has run.
            trustEngineCallers();
        }
    }

    /**
     * Write the bootstrap jar somewhere only this user can reach, under a name
     * nobody can guess.
     *
     * <p>The bootstrap loader reads classes from this file lazily for as long as
     * the JVM runs, so whoever can write to it can put code on the bootstrap
     * class path. It used to be {@code $TMPDIR/reclazz-bootstrap-<pid>.jar},
     * written with REPLACE_EXISTING. On a machine whose temp directory is shared,
     * a Linux {@code /tmp} being the common case, that is a path another local
     * user can predict and pre-create: in a sticky directory that stops the
     * agent from starting, and in one that is not sticky it is a file they can
     * replace afterwards. A fresh directory from {@code createTempDirectory} is
     * owner-only and its name is random, so there is no path to pre-create and
     * nothing for anyone else to write. Both are removed when the JVM exits,
     * the file before the directory: {@code deleteOnExit} runs last-registered
     * first.
     */
    static Path extractBootstrapJar(InputStream is) throws IOException {
        Path dir = Files.createTempDirectory("reclazz-");
        dir.toFile().deleteOnExit();
        Path tempJar = Files.createTempFile(dir, "reclazz-bootstrap-", ".jar");
        tempJar.toFile().deleteOnExit();
        // Written into the file createTempFile made, which is owner-only.
        // Files.copy with REPLACE_EXISTING would delete it and create a new
        // one under the process umask, which is usually world-readable.
        try (java.io.OutputStream out = Files.newOutputStream(tempJar)) {
            is.transferTo(out);
        }
        return tempJar;
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

            // What the file is, decided in one testable place; whether we act
            // on it stays here, because that depends on configuration.
            switch (ChangeKind.of(event.getPath())) {
                case CLASS_FILE -> {
                    if (event.getType() != ChangeEvent.Type.DELETED) {
                        handleClassFileChange(event, reloader, springOrchestrator, interceptorReloader);
                    }
                }
                case JAVA_SOURCE -> {
                    if (config.isAutoCompile() && compiler != null) {
                        handleJavaBatch(compiler, reloader, springOrchestrator, interceptorReloader);
                    } else if (!config.isAutoCompile()) {
                        StatusReporter.info("Source changed: " + fileName + " [" + event.getModuleName() + "]");
                        StatusReporter.info("Build your project to compile, Reclazz will hot-swap automatically.");
                    }
                }
                case SPRING_XML -> handleSpringXmlChange(event);
                case BACKOFFICE_CONFIG -> {
                    StatusReporter.info("Backoffice config changed: "
                            + event.getPath().getFileName() + " [" + event.getModuleName() + "]");
                    int reset = BackofficeConfigReloader
                            .reload(event.getPath().getFileName().toString(),
                                    platformContext.getAllApplicationContexts());
                    if (reset == 0) {
                        StatusReporter.info("No running backoffice holds this configuration "
                                + "(the backoffice web context is not up, or this is not a "
                                + "backoffice server). Nothing to reset.");
                    }
                }
                case CODEGEN_XML -> handleCodegenXmlChange(event);
                case PROPERTIES -> handlePropertiesChange(event);
                case LOGGING_CONFIG -> handleLoggingConfigChange(event);
                case IMPEX -> {
                    if (config.isAutoImpex() && impexImporter != null) {
                        handleImpexChange(event, impexImporter);
                    }
                }
                case LOCALIZATION -> handleLocalizationChange(event);
                case TEMPLATE -> handleTemplateChange(event, config);
                case UNKNOWN -> { }
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

        String displayName = displayName(className);
        String source = classFile.toString();

        StatusReporter.info("Class file changed: " + classFile.getFileName() + " [" + moduleName + "]");

        // Reading the file is its own step, with its own message. The try that
        // used to start here ran to the end of the Spring orchestration, so
        // anything a framework reloader threw was reported as "Failed to read
        // class file", about a file that had been read fine and a class that
        // had reloaded.
        byte[] bytecode;
        try {
            bytecode = event.getBytes();
            if (bytecode == null) bytecode = Files.readAllBytes(classFile);
        } catch (java.io.IOException e) {
            StatusReporter.error("Failed to read class file " + classFile + ": "
                    + Failures.describe(e));
            recordOutcome(className, false, "could not read the class file: " + Failures.describe(e), source);
            return;
        }

        // Null until the JVM has answered. The catch below reads it to tell
        // a reload that never happened from one that landed and then had
        // its framework refresh die; both leave a trail, different ones.
        ClassReloader.ReloadResult reloadResult = null;
        try {
            String internalName = className.replace('.', '/');

            if (transformContext != null && !transformContext.isWatched(internalName)
                    && transformContext.isInWatchedDir(classFile)) {
                transformContext.addWatched(internalName);
                StatusReporter.info("New class added to watched set: " + className);
            }

            long startTime = System.currentTimeMillis();
            ReloadEffects.begin();

            chaseChangedConstants(internalName, className, bytecode);

            // A class the JVM has never loaded is exactly the case where the
            // stock-JDK wall does not stand: everything about it is real. If
            // it carries a Spring stereotype it becomes a live bean here, and
            // the ordinary reload machinery has nothing left to do for it.
            if (!alreadyLoaded(className)) {
                if (springOrchestrator.registerNewBeanClass(className, bytecode)) {
                    recordOutcome(className, true, "registered as a new bean", source);
                    ReloadEffects.end();
                    return;
                }
                JpaMappingRefresh.maybeMapNewEntity(
                        className, bytecode, platformContext.getAllApplicationContexts());
            }

            reloadResult = reloadWithCacheBoundary(className, internalName, bytecode, reloader);

            long elapsed = System.currentTimeMillis() - startTime;

            recordOutcome(className, reloadResult.isSuccess(),
                    reloadResult.isSuccess() ? kindOf(reloadResult) : reloadResult.getError(), source);

            if (reloadResult.isSuccess()) {
                // The framework steps first, the line last, so that the line
                // can say what the steps did.
                if (reloadResult.isSpringBean()) {
                    Class<?> reloadedClass = findLoadedClass(className);
                    springOrchestrator.onClassReloaded(className, reloadedClass,
                            reloadResult.isStructuralReload(),
                            reloadResult.isAnnotationsChanged(),
                            reloadResult.isMethodsAdded(),
                            reloadResult.getAddedMethodSigs(),
                            reloadResult.getNewBytecode());
                } else {
                    springOrchestrator.onHelperReloaded(className, findLoadedClass(className));
                }

                // Hybris-specific: interceptor reload
                if (reloadResult.isInterceptor() && interceptorReloader != null) {
                    interceptorReloader.reloadInterceptor(className,
                            ((HybrisPlatformContext) platformContext).getHybrisContext());
                    ReloadEffects.note("interceptor re-registered");
                    StatusReporter.detail("Interceptor reloaded: " + displayName);
                }

                reloadLanded(className, reloadResult.isStructuralReload(), elapsed,
                        reloadResult.getShape(), source, ReloadEffects.end());
            } else {
                ReloadEffects.end();
                SessionReport.failed();
                StatusReporter.error("Hot-swap failed for " + displayName + ": " + reloadResult.getError());
                ReloadEvents.failed(className, reloadResult.getError(), source);
                // A structural failure does not always carry advice: the ones
                // raised with their own explanation have nothing to add. Printing
                // it unguarded put the literal word "null" under the message that
                // had just explained the problem properly.
                String advice = reloadResult.getStructuralChangeAdvice();
                if (reloadResult.isStructuralChange() && advice != null && !advice.isBlank()) {
                    StatusReporter.warn(advice);
                }
            }
        } catch (Throwable t) {
            // Throwable, because a reloader asking an unknown framework a
            // question answers with a NoClassDefFoundError as readily as with
            // an exception, and this runs on the watcher's thread: letting one
            // out is how a session stops reloading without saying anything.
            String why = Failures.describe(t);
            StatusReporter.error("Reload of " + displayName + " did not finish: " + why);
            if (reloadResult == null) {
                // Died before the JVM was asked. Without this, the counters
                // and the recorder hear nothing and DIAGNOSE answers that no
                // reload was attempted, which sends the developer to look at
                // their build for a failure that is in this log.
                SessionReport.failed();
                recordOutcome(className, false, "did not finish: " + why, source);
                ReloadEvents.failed(className, "did not finish: " + why, source);
            } else if (reloadResult.isSuccess()) {
                recordOutcome(className, true, kindOf(reloadResult)
                        + "; the framework refresh after it did not finish: " + why, source);
            }
        }
    }

    /** The console's name for a class: the outer class, with inner classes folded in. */
    private static String displayName(String className) {
        return className.contains("$")
                ? className.substring(0, className.indexOf('$')) + " (inner class)"
                : className;
    }

    private static String kindOf(ClassReloader.ReloadResult result) {
        return result.isStructuralReload() ? "structural" : "method bodies";
    }

    /** What DIAGNOSE will say about this class next time it is asked. */
    private static void recordOutcome(String className, boolean success, String detail, String source) {
        ReloadDiagnostics d = diagnostics;
        if (d != null) d.record(className, success, detail, source);
    }

    /**
     * One reload that landed, told to everything that keeps a trail of it:
     * the session counters, the console and the socket behind it, and the
     * Flight Recorder. The console gets the display name; the recorder gets
     * the JVM's name and the file, because a recording is where a reload is
     * traced back from and "Outer (inner class)" names every inner class of
     * Outer the same way.
     *
     * @param elapsed the measured time, or negative for one of a batch timed
     *                as a whole
     * @param source  the class file, or the source file that was compiled
     */
    private static void reloadLanded(String className, boolean structural, long elapsed,
                                     String shape, String source, String effects) {
        SessionReport.reloaded(structural, elapsed);
        if (structural) {
            StatusReporter.structuralReload(displayName(className), elapsed, shape, effects);
        } else {
            StatusReporter.reload(displayName(className), elapsed, effects);
        }
        ReloadEvents.reloaded(className, structural, elapsed, shape, source);
    }

    /**
     * The .java file a compiled class came from, by the name javac gives a
     * top-level class's file. A class declared in another class's file, which
     * javac allows when it is not public, gets the batch named instead of a
     * wrong file.
     */
    static String sourceOf(String className, List<Path> javaFiles) {
        String outer = className.substring(className.lastIndexOf('.') + 1);
        if (outer.contains("$")) outer = outer.substring(0, outer.indexOf('$'));
        String fileName = outer + ".java";
        for (Path file : javaFiles) {
            if (file.getFileName().toString().equals(fileName)) return file.toString();
        }
        return javaFiles.size() == 1
                ? javaFiles.get(0).toString()
                : "one of " + Plural.of(javaFiles.size(), "source file") + " compiled together";
    }

    /**
     * Warn about a changed compile-time constant, then go and find what
     * inlined it. The search walks a source tree, so it runs on its own
     * thread; this call returns before it starts looking.
     */
    private static void chaseChangedConstants(String internalName, String className,
                                              byte[] bytecode) {
        List<String> changed = ConstantChangeWarning.check(
                internalName, className, bytecode, alreadyLoaded(className));
        if (changed.isEmpty()) return;

        PlatformContext platform = platformContext;
        ConstantDependents.chase(className, changed,
                platform == null ? Map.of() : platform.getSourceDirs(), constantRebuild);
    }

    /** The HEALTH report, with the reload that is holding everything up named first. */
    private static List<String> withStall(List<String> report) {
        ReloadQueue queue = reloadQueue;
        String line = queue == null ? null : queue.healthLine();
        if (line == null) return report;
        List<String> withIt = new java.util.ArrayList<>(report.size() + 1);
        withIt.add(line);
        withIt.addAll(report);
        return withIt;
    }

    private static void handleJavaBatch(IncrementalCompiler compiler,
                                        ClassReloader reloader,
                                        SpringReloadOrchestrator springOrchestrator,
                                        InterceptorReloader interceptorReloader) {
        java.util.List<ChangeEvent> batch;
        synchronized (pendingJavaChanges) {
            if (lastJavaAttemptGeneration == javaChangeGeneration) return;
            lastJavaAttemptGeneration = javaChangeGeneration;
            pendingJavaChanges.keySet().removeIf(path -> !Files.isRegularFile(path));
            if (pendingJavaChanges.isEmpty()) return; // drained by an earlier task
            batch = new java.util.ArrayList<>(pendingJavaChanges.values());
            pendingJavaChanges.clear();
        }

        // Group by module; the attempt publishes only after every group succeeds.
        java.util.LinkedHashMap<String, List<Path>> byModule = new java.util.LinkedHashMap<>();
        for (ChangeEvent e : batch) {
            byModule.computeIfAbsent(e.getModuleName(), k -> new java.util.ArrayList<>()).add(e.getPath());
        }

        IncrementalCompiler.CompileResult result = compiler.compilePackage(byModule);
        SessionReport.compiled(batch.size());
        if (!result.isSuccess()) {
            StatusReporter.error("Compilation failed:");
            result.getErrors().forEach(err -> StatusReporter.error("  " + err));
            Map<Path, ChangeEvent> attempted = new java.util.LinkedHashMap<>();
            for (ChangeEvent event : batch) attempted.put(event.getPath(), event);
            com.onurkat.reclazz.compiler.CompileAttempt.retainExisting(pendingJavaChanges, attempted);
            synchronized (pendingJavaChanges) {
                StatusReporter.info("Holding " + pendingJavaChanges.size()
                        + " source files; nothing reloads until compilation succeeds. "
                        + (result.getErrors().isEmpty() ? "" : result.getErrors().get(0)));
            }
            return;
        }
        Map<String, byte[]> compiledClasses = result.getCompiledClasses();
        Map<String, String> sources = new java.util.HashMap<>();
        List<Path> files = batch.stream().map(ChangeEvent::getPath).toList();
        for (Path file : files) StatusReporter.compile(file.getFileName().toString(), result.getCompileTimeMs());
        for (String compiled : compiledClasses.keySet()) sources.put(compiled, sourceOf(compiled, files));
        if (compiledClasses.isEmpty()) return;

        Runnable apply = () -> applyCompiledClasses(compiledClasses, sources, reloader,
                springOrchestrator, interceptorReloader);
        if (agentConfig != null && agentConfig.isRequestBoundary()) RequestReloadBoundary.run(apply);
        else apply.run();
    }

    private static ClassReloader.ReloadResult reloadWithCacheBoundary(String className, String internalName,
                                                                     byte[] bytecode, ClassReloader reloader) {
        if (cacheTrackingInstalled) CacheDependencyLedger.beginMutation();
        try {
            if (structuralReloader != null && transformContext != null && transformContext.isWatched(internalName)) {
                return structuralReloader.reload(className, bytecode);
            }
            return reloader.reload(className, bytecode);
        } finally {
            if (cacheTrackingInstalled) CacheDependencyLedger.endMutation();
        }
    }

    private static void applyCompiledClasses(Map<String, byte[]> compiledClasses, Map<String, String> sources,
                                             ClassReloader reloader,
                                             SpringReloadOrchestrator springOrchestrator,
                                             InterceptorReloader interceptorReloader) {

        long startTime = System.currentTimeMillis();
        int successCount = 0;
        int failCount = 0;
        // class name -> was it a structural reload (drives event level below)
        java.util.LinkedHashMap<String, Boolean> swappedClasses = new java.util.LinkedHashMap<>();
        // What each structural change was, in the words the reload line prints.
        // The batch path is the one autoCompile uses, so it reports most of the
        // reloads a hybris developer ever sees; leaving the shape behind here
        // dropped it from every one of them.
        java.util.LinkedHashMap<String, String> swappedShapes = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, String> swappedEffects = new java.util.LinkedHashMap<>();

        // Dependent cascade + stale-reference healing sweep every singleton
        // in every context, so run them once for the whole batch; the JVM
        // redefinitions likewise go in one call at the end.
        StructuralReloader structuralForBatch = structuralReloader;
        if (structuralForBatch != null) structuralForBatch.beginBatch();
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
            chaseChangedConstants(internalName, className, bytecode);

            // Same as the single-file path: a never-loaded stereotype class
            // becomes a live bean and is done.
            if (!alreadyLoaded(className)) {
                if (springOrchestrator.registerNewBeanClass(className, bytecode)) {
                    recordOutcome(className, true, "registered as a new bean", sources.get(className));
                    successCount++;
                    continue;
                }
                JpaMappingRefresh.maybeMapNewEntity(
                        className, bytecode, platformContext.getAllApplicationContexts());
            }

            ClassReloader.ReloadResult reloadResult;
            ReloadEffects.begin();
            try {
                reloadResult = reloadWithCacheBoundary(className, internalName, bytecode, reloader);
            } catch (Throwable t) {
                reloadResult = ClassReloader.ReloadResult.failure(
                        "Unexpected " + t.getClass().getSimpleName() + ": " + t.getMessage(), false);
            }

            recordOutcome(className, reloadResult.isSuccess(),
                    reloadResult.isSuccess() ? kindOf(reloadResult) : reloadResult.getError(),
                    sources.get(className));

            if (reloadResult.isSuccess()) {
                successCount++;
                swappedClasses.put(className, reloadResult.isStructuralReload());
                if (reloadResult.getShape() != null) {
                    swappedShapes.put(className, reloadResult.getShape());
                }

                if (reloadResult.isSpringBean()) {
                    Class<?> reloadedClass = findLoadedClass(className);
                    springOrchestrator.onClassReloaded(className, reloadedClass,
                            reloadResult.isStructuralReload(),
                            reloadResult.isAnnotationsChanged(),
                            reloadResult.isMethodsAdded(),
                            reloadResult.getAddedMethodSigs(),
                            reloadResult.getNewBytecode());
                } else {
                    springOrchestrator.onHelperReloaded(className, findLoadedClass(className));
                }

                if (reloadResult.isInterceptor() && interceptorReloader != null) {
                    interceptorReloader.reloadInterceptor(className,
                            ((HybrisPlatformContext) platformContext).getHybrisContext());
                    ReloadEffects.note("interceptor re-registered");
                    StatusReporter.detail("Interceptor reloaded: " + className);
                }
                swappedEffects.put(className, ReloadEffects.end());
            } else {
                ReloadEffects.end();
                failCount++;
                StatusReporter.error("Hot-swap failed for " + className + ": " + reloadResult.getError());
                ReloadEvents.failed(className, reloadResult.getError(), sources.get(className));
                // A structural failure does not always carry advice: the ones
                // raised with their own explanation have nothing to add. Printing
                // it unguarded put the literal word "null" under the message that
                // had just explained the problem properly.
                String advice = reloadResult.getStructuralChangeAdvice();
                if (reloadResult.isStructuralChange() && advice != null && !advice.isBlank()) {
                    StatusReporter.warn(advice);
                }
            }
        }
        } finally {
            try {
                if (structuralForBatch != null) structuralForBatch.endBatch();
            } finally {
                springOrchestrator.endBatch();
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;

        // Emit STRUCTURAL_RELOAD vs RELOAD per class. The autoCompile path
        // used to always emit plain RELOAD, so STRUCTURAL_RELOAD events (and
        // the IDE widget's structural counter) never fired in this mode.
        if (compiledClasses.size() == 1 && successCount == 1) {
            var only = swappedClasses.entrySet().iterator().next();
            reloadLanded(only.getKey(), only.getValue(), elapsed,
                    swappedShapes.get(only.getKey()), sources.get(only.getKey()),
                    swappedEffects.get(only.getKey()));
        } else if (compiledClasses.size() > 1) {
            for (var entry : swappedClasses.entrySet()) {
                reloadLanded(entry.getKey(), entry.getValue(), -1,
                        swappedShapes.get(entry.getKey()), sources.get(entry.getKey()),
                        swappedEffects.get(entry.getKey()));
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

    /**
     * A template changed: drop the parsed copy the engine is serving.
     *
     * Says nothing when no engine registered itself. An application with no
     * template engine still has .html files, and a line claiming a reload that
     * did not happen is worse than silence.
     */
    private static void handleTemplateChange(ChangeEvent event, AgentConfig config) {
        String fileName = event.getPath().getFileName().toString();
        int cleared = templateReloader.reload(fileName);
        if (cleared == 0 && config != null && config.isVerbose()) {
            StatusReporter.info("Template changed: " + fileName
                    + " (no template engine registered, nothing to clear)");
        }
    }

    private static void handleSpringXmlChange(ChangeEvent event) {
        StatusReporter.info("Spring XML changed: " + event.getPath().getFileName());
        SpringXmlReloader reloader = springXmlReloader;
        if (reloader == null) {
            StatusReporter.warn("Spring XML reload not available — agent not fully initialised");
            return;
        }
        reloader.reload(event.getPath());
    }

    /** Called by the watcher at startup; see PropertyFileSnapshots. */
    public static void baselinePropertyFile(java.nio.file.Path file) {
        propertySnapshots.baseline(file);
    }

    private static void handlePropertiesChange(ChangeEvent event) {
        String fileName = event.getPath().getFileName().toString();

        // A .properties file in an extension is far more often a message bundle
        // or a library's settings than the platform's configuration, and the
        // difference matters: applying one changes what the whole server reads,
        // and it takes no edit at all to arrive, a checked-out branch will do.
        if (platformContext instanceof HybrisPlatformContext
                && !HybrisConfigReloader
                        .isPlatformConfiguration(event.getPath())) {
            StatusReporter.info(fileName + " is not platform configuration, so nothing was applied.");
            return;
        }

        StatusReporter.info("Config file changed: " + fileName);
        var candidate = propertySnapshots.pending(event.getPath());
        if (candidate == null) return;
        if (candidate.changed().isEmpty()) {
            propertySnapshots.accept(candidate);
            StatusReporter.info("No property values changed.");
            return;
        }

        // On SAP Commerce the platform keeps its properties in memory and only
        // reads the files at startup, so an edit reaches nothing on its own.
        // The keys that actually differ are applied the same way the HAC
        // console applies them.
        if (platformContext instanceof HybrisPlatformContext) {
            // The platform's classloader, not the agent's: the agent is on the
            // system classpath and cannot see de.hybris.platform.util.Config
            // from there. The application context is loaded by the platform,
            // so its loader is the one that can.
            Object appContext = platformContext.getApplicationContext();
            ClassLoader platformLoader = appContext != null
                    ? appContext.getClass().getClassLoader()
                    : platformContext.getClass().getClassLoader();
            HybrisConfigReloader configReloader =
                    new HybrisConfigReloader(platformLoader, propertySnapshots);
            java.util.List<String> applied = configReloader.apply(candidate);
            applyLoggerLevels(candidate.content(), applied);

            if (applied.isEmpty() && configReloader.isPlatformReachable()) {
                // The file was compared against the running configuration and
                // held nothing new: a comment, a reformat, or a save with no
                // edit. Warning about a restart here would be noise.
                StatusReporter.info("No property values changed.");
                return;
            }
            if (!applied.isEmpty()) {
                StatusReporter.info("Applied "
                        + Plural.of(applied.size(), "property change") + ": "
                        + (applied.size() > 8 ? applied.subList(0, 8) + " …" : applied.toString()));
                StatusReporter.info("Values read per request take effect now; "
                        + "anything consumed once at startup still needs a restart.");
                return;
            }
        }

        java.util.Map<String, String> changed = candidate.changed();
        if (platformContext instanceof HybrisPlatformContext) {
            // Preserve the logger-only fallback when the platform Config API is absent.
            propertySnapshots.accept(candidate);
            int levels = applyLoggerLevels(candidate.content(), changed.keySet());
            if (levels == 0 || !changed.keySet().stream().allMatch(ReclazzAgent::isLoggingKey))
                reportUnconsumedProperties(changed);
            return;
        }
        int[] levelsApplied = {0};
        var applied = new SpringPropertyRebinder(platformContext.getAllApplicationContexts())
                .apply(changed,
                        agentConfig != null && agentConfig.isRequestBoundary()
                                ? RequestReloadBoundary::run : Runnable::run,
                        () -> {
                            propertySnapshots.accept(candidate);
                            levelsApplied[0] = applyLoggerLevels(candidate.content(), changed.keySet());
                        });
        if (applied.state() != com.onurkat.reclazz.spring.PropertyChangeOutcome.State.APPLIED) return;
        StatusReporter.success("Applied " + Plural.of(changed.size(), "property change") + ": " + changed.keySet());
        java.util.List<String> rebound = applied.rebound();
        if (!rebound.isEmpty()) {
            StatusReporter.success("Rebound " + Plural.of(rebound.size(), "@ConfigurationProperties bean")
                    + ": " + (rebound.size() > 5 ? rebound.subList(0, 5) + " …" : rebound.toString()));
        }
        if (!rebound.isEmpty() || applied.valueFields() > 0 || !applied.rebuilt().isEmpty()) return;
        if (levelsApplied[0] > 0 && changed.keySet().stream().allMatch(ReclazzAgent::isLoggingKey)) return;
        reportUnconsumedProperties(changed);
    }

    private static void reportUnconsumedProperties(java.util.Map<String, String> changed) {
        StatusReporter.warn("Values a bean read once at startup still need a restart; "
                + "nothing here reads them again on its own.");
        RestartLedger.note(changed.keySet().toString(),
                "changed, and a bean that read the value once at startup is still holding the old one");
    }

    /**
     * Static text: type and enum names from a locales file, or backoffice
     * labels. Neither is platform configuration, and pushing them into it
     * would report changes the server never shows.
     */

    private static void handleLoggingConfigChange(ChangeEvent event) {
        String fileName = event.getPath().getFileName().toString();
        LoggingReloader reloader =
                new LoggingReloader(instrumentation);

        String framework = reloader.reconfigureFrom(event.getPath());
        if (framework != null) {
            StatusReporter.success(fileName + " applied to the running " + framework + " context");
        } else if (!reloader.frameworkPresent()) {
            StatusReporter.info(fileName + " changed, but no logging framework is loaded here.");
        }
    }

    /**
     * Sends the logger levels a property file asks for straight to the logging
     * framework.
     *
     * Turning a logger up is the most common reason to restart a server and the
     * least deserving one: the level is a field on an object already in memory.
     * SAP Commerce reads these properties once at startup, so the file is not
     * enough on its own, and Spring Boot rebinds them only on a refresh.
     *
     * Only the loggers the save actually touched are set. Reapplying the whole
     * file would undo a level raised from the HAC console minutes earlier,
     * every time any unrelated property is edited.
     */
    private static int applyLoggerLevels(java.util.Map<String, String> content,
                                         java.util.Collection<String> changedKeys) {
        if (changedKeys.isEmpty()) return 0;

        java.util.Properties fromFile = new java.util.Properties();
        fromFile.putAll(content);

        java.util.Map<String, String> levels =
                LoggingReloader.levelsIn(fromFile, changedKeys);
        if (levels.isEmpty()) return 0;

        LoggingReloader reloader =
                new LoggingReloader(instrumentation);
        java.util.List<String> applied = reloader.applyLevels(levels);
        if (!applied.isEmpty()) {
            StatusReporter.success(Plural.word(applied.size(),
                    "Logger level applied: ", "Logger levels applied: ")
                    + (applied.size() > 5 ? applied.subList(0, 5) + " …" : applied.toString()));
        }
        return applied.size();
    }

    private static boolean isLoggingKey(String key) {
        return key.startsWith("logging.level.") || key.startsWith("log4j2.logger.");
    }

    private static void handleLocalizationChange(ChangeEvent event) {
        String fileName = event.getPath().getFileName().toString();

        // Clearing either cache is instant and the next reader pays for the
        // rebuild: 830ms for the platform's localization cache and just under
        // four seconds for ZK's, measured on a 2211 server. A save that changed
        // no text must not buy that, and those are common: the platform's build
        // re-copies resource files, so one ant build touches every localization
        // file in every extension.
        if (!localizationGuard.changed(event.getPath())) {
            StatusReporter.info("Localization file saved with no text changes: " + fileName);
            return;
        }

        StatusReporter.info("Localization file changed: " + fileName);

        // First, and whatever the platform. ResourceBundle.getBundle memoises
        // per classloader, base name and locale and never looks at the file
        // again, so every framework cache above it can be emptied and the next
        // lookup is still answered from this one. It was only being cleared as
        // the last step of refreshing a Spring MessageSource, which means it
        // happened when there was such a bean to find: Bean Validation's
        // ValidationMessages, the platform's own bundles, and any code that
        // calls getBundle itself all sit under this cache and none of them is
        // a Spring bean.
        BundleCache.clear(
                platformContext == null ? new ClassLoader[0]
                        : new ClassLoader[]{platformContext.getClass().getClassLoader()});

        if (!(platformContext instanceof HybrisPlatformContext)) {
            // Spring caches a message bundle after the first lookup and ships
            // the reset for it, so this is a cache to drop rather than a
            // restart to ask for.
            SpringMessageSourceReloader.report(fileName,
                    new SpringMessageSourceReloader(platformContext)
                            .reload());
            return;
        }

        Object appContext = platformContext.getApplicationContext();
        ClassLoader platformLoader = appContext != null
                ? appContext.getClass().getClassLoader()
                : platformContext.getClass().getClassLoader();
        HybrisLocalizationReloader reloader =
                new HybrisLocalizationReloader(
                        platformLoader, instrumentation);

        java.nio.file.Path parent = event.getPath().getParent();
        boolean backofficeLabels = parent != null && parent.getFileName() != null
                && parent.getFileName().toString().endsWith("-backoffice-labels");

        if (backofficeLabels) {
            if (reloader.reloadBackofficeLabels()) {
                StatusReporter.success("Backoffice labels re-read. Reopen the view to see them.");
            } else {
                // Backoffice loads ZK lazily; before anyone opens it there is
                // no cache to clear and nothing to report.
                StatusReporter.warn("Backoffice is not running here, so its labels were left alone.");
            }
            return;
        }

        if (reloader.reloadTypeLocalizations()) {
            StatusReporter.success("Type and enum names re-read from " + fileName + ".");
        } else {
            StatusReporter.warn("Could not reach the platform's localization cache; "
                    + fileName + " needs a restart to take effect.");
            RestartLedger.note(fileName, "localization text the platform's cache did not take");
        }
    }

    private static void handleImpexChange(ChangeEvent event,
                                          ImpexAutoImporter impexImporter) {
        StatusReporter.info("ImpEx file changed: " + event.getPath().getFileName());
        impexImporter.importFile(event.getPath(),
                ((HybrisPlatformContext) platformContext).getHybrisContext());
    }

    /**
     * Unified dispatch for both *-beans.xml and *-items.xml saves —
     * the {@link CodegenReloader}
     * picks the right kind from the file suffix and customises its
     * post-run reporting accordingly.
     */
    private static void handleCodegenXmlChange(ChangeEvent event) {
        CodegenReloader reloader = codegenReloader;
        if (reloader == null) {
            StatusReporter.info("Codegen XML changed: " + event.getPath().getFileName());
            StatusReporter.warn("Codegen reload requires a Hybris platform — not available here.");
            return;
        }
        reloader.handle(event);
    }


    /**
     * Whether the JVM has ALREADY loaded a class of this name, asked without
     * loading it. {@code ClassLookup.findLoadedClass} answers a different
     * question despite its name: it resolves through {@code Class.forName}
     * and thereby loads the class it is asked about, which is fine for the
     * reload paths (their classes are loaded by definition) and exactly wrong
     * for "is this file a brand-new class", where the asking must not change
     * the answer.
     */
    private static boolean alreadyLoaded(String className) {
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (className.equals(loaded.getName())) return true;
        }
        return false;
    }

    private static Class<?> findLoadedClass(String className) {
        return ClassLookup.findLoadedClass(className, instrumentation);
    }

    private static void reportCapabilities() {
        boolean canRedefine = instrumentation.isRedefineClassesSupported();
        boolean canRetransform = instrumentation.isRetransformClassesSupported();

        probeResult = JvmCapabilityProbe.probe(instrumentation);

        // The capability block is for the case where something is missing,
        // and for verbose. On an ordinary JDK it said five lines of YES that
        // the one "engine active" line below already means.
        boolean verbose = agentConfig != null && agentConfig.isVerbose();
        if (!canRedefine || !canRetransform || verbose) {
            StatusReporter.info("JVM Capabilities:");
            StatusReporter.info("  Redefine classes: " + (canRedefine ? "YES" : "NO"));
            StatusReporter.info("  Retransform classes: " + (canRetransform ? "YES" : "NO"));
            StatusReporter.info("  VM: " + probeResult.getVmDescription());
            StatusReporter.info("  Detection method: " + probeResult.getDetectionMethod());
        }

        if (probeResult.hasEnhancedRedefinition()) {
            StatusReporter.success("Structural hot-reload: enabled via enhanced redefinition (native), "
                    + probeResult.getVmDescription());
        } else if (verbose) {
            // Without verbose the companion engine says so itself when it starts.
            StatusReporter.success("  Structural hot-reload: enabled via Reclazz companion-class reloader");
        }

        if (JvmCapabilityProbe.isGraalVM()) {
            StatusReporter.warn("  GraalVM detected — class redefinition support is limited.");
            StatusReporter.warn("  Some method body changes may fail. Consider using a standard OpenJDK or JBR instead.");
        }
    }
}
