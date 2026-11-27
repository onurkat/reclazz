/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.util.ClassLookup;
import com.onurkat.reclazz.ext.*;

import com.onurkat.reclazz.config.AgentConfig;

import com.onurkat.reclazz.ui.RestartLedger;

import com.onurkat.reclazz.compiler.IncrementalCompiler;
import com.onurkat.reclazz.platform.HybrisPlatformContext;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.platform.PlatformDetector;
import com.onurkat.reclazz.platform.SpringBootContext;
import com.onurkat.reclazz.platform.SapIndexingGuidance;
import com.onurkat.reclazz.platform.SapConfigFiles;
import com.onurkat.reclazz.platform.SpringContextInterceptTransformer;
import com.onurkat.reclazz.reload.StructuralReloader;
import com.onurkat.reclazz.reload.SwitchMapPlan;
import com.onurkat.reclazz.reload.SwitchMapPreflight;
import com.onurkat.reclazz.reload.SwitchMapRefresh;
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
import com.onurkat.reclazz.platform.PropertyFileSnapshots;
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
    private static volatile SpringReloadOrchestrator activeSpringOrchestrator;

    /**
     * What each platform property file said last time, so a save is read as the
     * edit it was. See PropertyFileSnapshots.
     */
    private static final PropertyFileSnapshots propertySnapshots =
            new PropertyFileSnapshots();
    private static final com.onurkat.reclazz.spring.SpringPropertyFiles springPropertyFiles =
            new com.onurkat.reclazz.spring.SpringPropertyFiles();

    /**
     * Localization saves defer expensive work to whoever reads next, so a save
     * that changed nothing is worth recognising. See handleLocalizationChange.
     */
    private static final ContentChangeGuard localizationGuard =
            new ContentChangeGuard();
    private static volatile boolean running = false;

    /**
     * Java changes waiting to be compiled. Registered after their codegen inputs,
     * drained on the single-threaded reload executor, every drain compiles
     * everything that accumulated while the previous batch was running.
     */
    // Retries require a new source event or accepted codegen, not leftover tasks from one save.
    private static long javaChangeGeneration;
    private static long lastJavaAttemptGeneration = -1;
    private static final java.util.LinkedHashMap<Path, ChangeEvent> pendingJavaChanges =
            new java.util.LinkedHashMap<>();
    /** Pending files queued only as constant dependents, never saved; guarded by pendingJavaChanges. */
    private static final java.util.Set<Path> speculativeJava = new java.util.HashSet<>();

    private static volatile ExecutorService watcherExecutor;
    /** The reload thread and what is queued for it; see {@link ReloadQueue}. */
    private static volatile ReloadQueue reloadQueue;
    private static volatile Runnable generatedClassScan;
    private static volatile Runnable heldSourceRetry;
    private static volatile CodegenSourceGate codegenSourceGate = new CodegenSourceGate();
    private static volatile Runnable resumeJavaSources;
    private static volatile StatusServer statusServer;

    /**
     * Answers "why didn't my class reload?". Kept here because the answer is
     * assembled from what the watcher, the transformer and the JVM each know.
     */
    private static volatile ReloadDiagnostics diagnostics;
    private static volatile ReloadVerification verification;
    private static volatile JvmCapabilityProbe.ProbeResult probeResult;
    private static volatile TransformContext transformContext;
    private static final com.onurkat.reclazz.reload.RefusalTaint recordRefusals = new com.onurkat.reclazz.reload.RefusalTaint();
    private static final Map<String,String> lateCodecConcerns=new java.util.LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String,String> entry){return size()>200;}
    };
    private static void lateCodecFailure(String subject,String reason) {
        String previous;
        synchronized(lateCodecConcerns){previous=reason==null?lateCodecConcerns.remove(subject):lateCodecConcerns.put(subject,reason);}
        if(previous!=null&&!previous.equals(reason))com.onurkat.reclazz.ui.RestartLedger.resolve(subject,previous);
        if(reason!=null&&!reason.equals(previous)){com.onurkat.reclazz.ui.RestartLedger.note(subject,reason);StatusReporter.warn(reason);}
    }
    private static volatile boolean recordCodecHooksInstalled;
    private static volatile JvmCapabilityProbe.RecordCapability nativeRecordCapability;
    private static volatile RecordStockSelfCheck.Result stockRecordCapability;
    private static final RecordStockSelfCheck.Result fatalStockRecordCapability=new RecordStockSelfCheck.Result(false,"fatal VM error during stock record capability check; restart to apply");
    private static final java.util.concurrent.atomic.AtomicInteger stockRecordVersion=new java.util.concurrent.atomic.AtomicInteger();
    private static final Map<Class<?>,Map<String,String>> nativeRecordRepairConcerns = new java.util.WeakHashMap<>();
    private static final Map<Class<?>,Map<String,String>> nativeFrameworkRepairConcerns = new java.util.WeakHashMap<>();
    private static String repairSurface(String surface) {
        return surface.startsWith("native aftermath ")?"record aftermath "+surface.substring("native aftermath ".length()):surface;
    }
    private static void nativeFrameworkFailure(Class<?> type,String surface,String reason) {
        if(type==null)return;
        String concern="Changes are installed; "+repairSurface(surface)+" did not finish: "+reason+"; save "+type.getName()+" again to retry";
        String previous;
        synchronized(nativeFrameworkRepairConcerns){previous=nativeFrameworkRepairConcerns.computeIfAbsent(type,key->new java.util.LinkedHashMap<>()).put(surface,concern);}
        String subject=type.getName()+" / "+surface;
        if(previous!=null&&!previous.equals(concern))com.onurkat.reclazz.ui.RestartLedger.resolve(subject,previous);
        if(!concern.equals(previous))com.onurkat.reclazz.ui.RestartLedger.note(subject,concern);
    }
    private static boolean nativeFrameworkPending(Class<?> type) {
        synchronized(nativeFrameworkRepairConcerns){return nativeFrameworkRepairConcerns.containsKey(type);}
    }
    private static void nativeFrameworkCompleted(Class<?> type,boolean published) {
        if(type==null)return;
        synchronized(nativeFrameworkRepairConcerns){
            Map<String,String> pending=nativeFrameworkRepairConcerns.get(type);if(pending==null)return;
            pending.entrySet().removeIf(entry->{
                if(!published&&entry.getKey().startsWith("receipt publication "))return false;
                com.onurkat.reclazz.ui.RestartLedger.resolve(type.getName()+" / "+entry.getKey(),entry.getValue());return true;
            });
            if(pending.isEmpty())nativeFrameworkRepairConcerns.remove(type);
        }
    }
    private static boolean nativeRepairPending(Class<?> type) {
        synchronized (nativeRecordRepairConcerns) { return nativeRecordRepairConcerns.containsKey(type); }
    }
    private static final ThreadLocal<NativeRecordSave> nativeRecordSave = new ThreadLocal<>();
    private static final ThreadLocal<Map<String,String>> compiledSaveRefusals = new ThreadLocal<>();
    private static final class NativeRecordSave {
        com.onurkat.reclazz.reload.RecordBatch batch;
        final boolean stock;
        com.onurkat.reclazz.reload.RecordNativeRepairs repairs;
        final Map<String, java.util.function.Supplier<ClassReloader.ReloadResult>> aftermath = new java.util.LinkedHashMap<>();
        final Map<String, ClassReloader.ReloadResult> results = new java.util.LinkedHashMap<>();
        final Map<String, String> repairFailures = new java.util.LinkedHashMap<>();
        final java.util.Set<String> aftermathFailures = new java.util.HashSet<>();
        String preflightFailure;
        boolean applied;
        NativeRecordSave(com.onurkat.reclazz.reload.RecordNativeBatch batch) { this.batch = batch; this.stock = false; }
        NativeRecordSave(com.onurkat.reclazz.reload.RecordStockBatch batch) { this.batch = batch; this.stock = true; }
        java.util.Set<String> eligible() {
            return preflightFailure == null && batch.refusals().isEmpty() ? batch.names() : java.util.Set.of();
        }
        Map<String, String> refusals() {
            if (preflightFailure == null && batch.refusals().isEmpty()) return java.util.Map.of();
            recordRefusals.nativeRefused(batch.changes().stream().filter(change -> batch.recordNames().contains(change.name()))
                    .collect(java.util.stream.Collectors.toMap(com.onurkat.reclazz.reload.RecordNativeBatch.Change::owner,
                            com.onurkat.reclazz.reload.RecordNativeBatch.Change::bytes)));
            if (preflightFailure == null) return batch.refusals();
            Map<String,String> refused = new java.util.LinkedHashMap<>();
            for (String name : batch.names()) refused.put(name, preflightFailure + "; restart to apply");
            return refused;
        }
        void failure(String surface, String reason) {
            repairFailures.put(surface, reason);
            for(var change:batch.changes()) {
                if(surface.equals("framework refresh "+change.name())||surface.equals("receipt publication "+change.name())
                        ||surface.equals("native aftermath "+change.name())) {
                    nativeFrameworkFailure(change.owner(),surface,reason);StatusReporter.warn("Changes are installed; "+repairSurface(surface)+" did not finish: "+reason);return;
                }
            }
            String message = "Record shape changes are installed; " + repairSurface(surface) + " repair did not finish: " + reason;
            for (var change : batch.changes()) {
                if (!batch.recordNames().contains(change.name())) continue;
                String previous;
                synchronized (nativeRecordRepairConcerns) {
                    previous = nativeRecordRepairConcerns.computeIfAbsent(change.owner(), ignored -> new java.util.LinkedHashMap<>()).put(surface, message + "; save " + change.name() + " again to retry");
                }
                String subject = change.name() + " / " + surface;
                String concern = message + "; save " + change.name() + " again to retry";
                if (previous != null && !previous.equals(concern)) com.onurkat.reclazz.ui.RestartLedger.resolve(subject, previous);
                com.onurkat.reclazz.ui.RestartLedger.note(subject, concern);
            }
            StatusReporter.warn(message);
        }
        void repairsCompleted() {
            for (var change : batch.changes()) {
                if (!batch.recordNames().contains(change.name())) continue;
                Map<String,String> previous;
                synchronized (nativeRecordRepairConcerns) { previous = nativeRecordRepairConcerns.remove(change.owner()); }
                if (previous != null) previous.forEach((surface, reason) ->
                        com.onurkat.reclazz.ui.RestartLedger.resolve(change.name() + " / " + surface, reason));
            }
        }
        ClassReloader.ReloadResult apply(String name, ClassReloader reloader) {
            if (!applied) {
                applied = true;
                try {
                    for (var change : batch.changes()) {
                        String dependentFailure = SwitchMapRefresh.dependencyFailure(change.owner(), structuralReloader);
                        if (dependentFailure != null) { preflightFailure = dependentFailure; break; }
                        aftermath.put(change.name(), stock
                                ? structuralReloader.prepareRecordAftermath(change.owner(),change.bytes(),reloader)
                                : reloader.prepareNativeAftermath(change.owner(), change.bytes()));
                    }
                } catch (RuntimeException | LinkageError failure) {
                    preflightFailure = "record aftermath preflight failed: " + Failures.describe(failure);
                }
                if (preflightFailure != null) {
                    for (var change : batch.changes()) {
                        SwitchMapRefresh.result(change.owner(), false);
                        results.put(change.name(), ClassReloader.ReloadResult.failure(preflightFailure, true));
                    }
                    recordRefusals.nativeRefused(batch.changes().stream().filter(c -> batch.recordNames().contains(c.name()))
                            .collect(java.util.stream.Collectors.toMap(com.onurkat.reclazz.reload.RecordNativeBatch.Change::owner,
                                    com.onurkat.reclazz.reload.RecordNativeBatch.Change::bytes)));
                    return results.get(name);
                }
                for (var change : batch.changes()) {
                    SwitchMapRefresh.mutationStarting(change.owner());
                    com.onurkat.reclazz.transform.AdmittedBytes.invalidate(change.owner());
                    if (verification != null) verification.invalidateUntracked(change.name());
                }
                var outcome = batch.commit(change -> {
                    if (!batch.recordNames().contains(change.name())) return;
                    if(stock)recordRefusals.stockAccepted(change.owner(),change.bytes());
                    else {
                        if (!com.onurkat.reclazz.transform.KeepBaseline.recordNativeSchemaApplied(change.owner(), change.bytes()))
                            throw new IllegalStateException("physical native record constructor receipt failed readback");
                        recordRefusals.nativeAccepted(change.owner(), change.bytes());
                    }
                });
                if (!outcome.committed()) {
                    recordRefusals.nativeRefused(batch.changes().stream().filter(c -> batch.recordNames().contains(c.name()))
                            .collect(java.util.stream.Collectors.toMap(com.onurkat.reclazz.reload.RecordNativeBatch.Change::owner,
                                    com.onurkat.reclazz.reload.RecordNativeBatch.Change::bytes)));
                    for (var change : batch.changes()) {
                        SwitchMapRefresh.result(change.owner(), false);
                        results.put(change.name(), ClassReloader.ReloadResult.failure(outcome.reason(), true));
                    }
                } else {
                    outcome.repairFailures().forEach((surface, reason) -> failure((stock?"record proof ":"physical constructor receipt ") + surface, reason));
                    for (var change : batch.changes()) {
                        SwitchMapRefresh.result(change.owner(), true);
                        try { results.put(change.name(), aftermath.get(change.name()).get()); }
                        catch (RuntimeException | LinkageError failure) {
                            aftermathFailures.add(change.name());
                            failure("native aftermath " + change.name(), Failures.describe(failure));
                            results.put(change.name(), ClassReloader.ReloadResult.success(false, false));
                        }
                    }
                    repairs.apply().forEach(this::failure);
                    if (repairFailures.isEmpty()) repairsCompleted();
                }
            }
            return results.get(name);
        }
    }

    private static void closeRecordSave() {
        NativeRecordSave save=nativeRecordSave.get();
        try {if(save!=null)save.batch.close();}finally{nativeRecordSave.remove();}
    }

    private static volatile StructuralReloader structuralReloader;
    private static final TemplateReloader templateReloader =
            new TemplateReloader();
    private static volatile PlatformContext platformContext;
    private static volatile SpringXmlReloader springXmlReloader;

    private static void retryXmlForClass(String name) {
        SpringXmlReloader xml = springXmlReloader;
        if (xml != null) xml.classReady(name);
    }
    private static volatile PremiumModules premiumModules = new PremiumModules(new AlwaysAdmit());

    /**
     * Called when agent is loaded at JVM startup via -javaagent.
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        if (LauncherDryRun.current()) return;
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
        LookupCapture.trust(com.onurkat.reclazz.transform.ReflectionMethodTransformer.class);
        LookupCapture.trust(com.onurkat.reclazz.reload.ConstructorInitializerFactory.class);
        LookupCapture.trust(com.onurkat.reclazz.reload.RecordMemberSnapshot.class);
        LookupCapture.trust(com.onurkat.reclazz.reload.RecordCompanion.class);
        LookupCapture.trust(com.onurkat.reclazz.reload.RecordCallerCompanion.class);
        LookupCapture.trust(com.onurkat.reclazz.reload.RecordStockBatch.class);
        LookupCapture.trust(ReclazzAgent.class);
        LookupCapture.trust(RecordStockSelfCheck.class);
        LookupCapture.trust(NativeRecordSave.class);
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
        LookupCapture.trust(com.onurkat.reclazz.spring.AddedLifecycleAdapter.class);
        com.onurkat.reclazz.bootstrap.BootLoggingBaselineRecorder.install(LoggingReloader::captureBootLevel);
        LookupCapture.trust(com.onurkat.reclazz.spring.SpringProxyReferences.class);
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

    private static void initialize(String agentArgs) {
        initialize(agentArgs, null);
    }

    /** Explicit optional-module injection; normal entry points assemble the shipped module. */
    private static synchronized void initialize(String agentArgs, PremiumModules suppliedModules) {
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
                    StatusReporter.warn("Structural reload disabled, falling back to method-body-only mode");
                    enableStructural = false;
                }
            }

            if (bootstrapInstalled && !attached) {
                com.onurkat.reclazz.reload.EnumConstantAppender.observeHibernateFactories();
                instrumentation.addTransformer(new com.onurkat.reclazz.transform.HibernateFactoryTransformer(), false);
            }

            if (bootstrapInstalled) {
                com.onurkat.reclazz.bootstrap.OrikaBridge.reporter((subject, active) -> {
                    String reason = com.onurkat.reclazz.bootstrap.OrikaBridge.REMOVED_MAPPING;
                    if (active) {
                        StatusReporter.warn(reason + ": " + subject);
                        RestartLedger.note(subject, reason);
                    } else RestartLedger.resolve(subject, reason);
                });
                CacheDependencyLedger.reporter(message -> {
                    if (message.contains("failed")) StatusReporter.warn(message);
                    else StatusReporter.detail(message);
                });
                try {
                    com.onurkat.reclazz.spring.NativeKafkaRetryOwnership.install();
                    var routes = new com.onurkat.reclazz.transform.KafkaRetryResolverTransformer();
                    instrumentation.addTransformer(routes, true);
                    var listeners = new com.onurkat.reclazz.transform.NativeListenerSetupTransformer();
                    instrumentation.addTransformer(listeners, true);
                    for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                        String name = loaded.getName().replace('.', '/');
                        if (routes.targets(name) || listeners.targets(name))
                            instrumentation.retransformClasses(loaded);
                    }
                } catch (Exception | LinkageError unavailable) {
                    StatusReporter.detail("Kafka retry route observation unavailable: " + unavailable.getClass().getSimpleName());
                }
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
                StatusReporter.info("Request boundary enabled: class reloads wait for MVC dispatches and supported javax/Jakarta async requests");
            }

            // Detect platform and create context
            platformContext = PlatformDetector.detect(config);
            if (platformContext == null) {
                StatusReporter.error("Failed to detect platform. Cannot initialize Reclazz.");
                return;
            }

            boolean isHybris = platformContext.getPlatformId() == PlatformContext.Platform.HYBRIS;

            // Install before native lazy definition loads. Already loaded classes stay unobserved.
            instrumentation.addTransformer(new com.onurkat.reclazz.transform.SapProcessDefinitionTransformer(), false);
            if (isHybris) {
                instrumentation.addTransformer(new com.onurkat.reclazz.transform.SingletonRegistryRevisionTransformer(), false);
                instrumentation.addTransformer(new com.onurkat.reclazz.spring.xml.SapListDirectiveTransformer(), false);
            }

            // Register SpringContextInterceptTransformer for ALL platforms,
            // it instruments AbstractApplicationContext.refresh() to register
            // the context in ApplicationContextHolder. On Hybris this is
            // strictly additive alongside the Registry-based lookup, and
            // covers the case where Registry.getMasterTenant() returns a
            // tenant whose context field isn't populated yet (observed on
            // Commerce Cloud 2205+ where tenant wiring happens late in
            // tenant init, AFTER Spring refresh has completed).
            try {
                if (bootstrapInstalled) {
                    instrumentation.addTransformer(new com.onurkat.reclazz.transform.BootLoggingBaselineTransformer(), false);
                }
                var devTools = new com.onurkat.reclazz.platform.BootDevToolsNotice();
                com.onurkat.reclazz.platform.ApplicationContextHolder.setRefreshObserver(context -> {
                    com.onurkat.reclazz.spring.SpringConfigurationOwners.refreshed(context);
                    if (!isHybris) devTools.refreshed(context);
                });
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
            PremiumModules modules = suppliedModules != null ? suppliedModules : new PremiumModules(new AlwaysAdmit());
            premiumModules = modules;

            // Attach mode: web contexts refreshed BEFORE the agent loaded are
            // invisible to the intercept transformer, discover them from the
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

            if (!enableStructural) {
                transformContext = new TransformContext();
                transformContext.populateFromPlatformContext(platformContext);
                transformContext.bindRuntime(config,platformContext);
                com.onurkat.reclazz.transform.KeepBaseline.setWatchedPredicate(transformContext::isWatched);
                instrumentation.addTransformer(new java.lang.instrument.ClassFileTransformer() {
                    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                            java.security.ProtectionDomain domain, byte[] bytes) {
                        if (name != null && loader != null && redefining == null && transformContext.isWatched(name)) {
                            try { com.onurkat.reclazz.transform.KeepBaseline.capture(loader, name, bytes);
                                com.onurkat.reclazz.transform.AdmittedBytes.defined(loader,name,bytes);
                                com.onurkat.reclazz.transform.StaticFieldPreparation.recordInterfaceDefinition(loader, name, bytes); }
                            catch (RuntimeException unavailable) { /* Missing evidence selects recreation. */ }
                        }
                        return null;
                    }
                }, false);
            }

            // Set up transform context and structural reloader if enabled
            if (enableStructural) {
                transformContext = new TransformContext();
                transformContext.populateFromPlatformContext(platformContext);
                transformContext.bindRuntime(config,platformContext);
                com.onurkat.reclazz.transform.KeepBaseline.setWatchedPredicate(transformContext::isWatched);
                if (config.isVerbose()) StatusReporter.info("Structural reload: watching " + transformContext.getWatchedClassCount() + " class directories");

                ReclazzTransformer transformer = new ReclazzTransformer(transformContext, config);
                instrumentation.addTransformer(transformer, true);
                if (config.isVerbose()) StatusReporter.info("Reclazz ClassFileTransformer registered (retransform-capable)");

                // Jackson must take its specialised metadata route before the general
                // reflection transformer rewrites these same Class calls.
                instrumentation.addTransformer(new JacksonAccessorTransformer(), true);
                instrumentation.addTransformer(new com.onurkat.reclazz.transform.GsonAccessorTransformer(), true);
                instrumentation.addTransformer(new com.onurkat.reclazz.transform.CacheOwnerTransformer(), true);
                instrumentation.addTransformer(new com.onurkat.reclazz.transform.OrikaPropertyTransformer(), true);
                instrumentation.addTransformer(new com.onurkat.reclazz.transform.OrikaMappingPolicyTransformer(), true);
                instrumentation.addTransformer(new com.onurkat.reclazz.transform.OrikaMapperCacheTransformer(), true);
                instrumentation.addTransformer(new com.onurkat.reclazz.transform.OrikaGeneratedMapperTransformer(transformContext), true);
                instrumentation.addTransformer(new com.onurkat.reclazz.transform.ExceptionHandlerTransformer(), true);
                instrumentation.addTransformer(new com.onurkat.reclazz.transform.MvcBindingTransformer(), true);
                if (bootstrapInstalled) instrumentation.addTransformer(new com.onurkat.reclazz.transform.MvcSingletonTransformer(), false);
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
                    if (transformContext.isWatched(internal) && !transformContext.isTransformed(alreadyLoaded)) {
                        transformContext.markRaw(alreadyLoaded.getClassLoader(),internal);
                    }
                }

                ReflectionRootFilter.install(instrumentation);
                com.onurkat.reclazz.transform.ReflectionMethodTransformer.install(instrumentation,
                        com.onurkat.reclazz.reload.ConstructorInitializerFactory::create);
                for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                    String internal = loaded.getName().replace('.', '/');
                    if (transformContext.isWatched(internal)
                            && transformContext.getMetadata(internal) != null) {
                        ReflectionRootFilter
                                .registerInjectedMembersOn(loaded);
                    }
                }

                structuralReloader = new StructuralReloader(instrumentation, transformContext, config, platformContext, modules);
                structuralReloader.setTransformer(transformer);
                StatusReporter.success("Structural reload engine active on " +
                        (probeResult != null ? probeResult.getVmDescription() : "standard JVM"));
                if (!MethodForge.isAvailable() && config.isVerbose()) {
                    StatusReporter.info("Reflection patching disabled (unavailable " +
                            "java.lang.reflect.Method internals). Hot-reload still works; " +
                            "only reflective scans of newly-added methods/fields are degraded.");
                }
            }

            if (bootstrapInstalled && instrumentation.isRetransformClassesSupported()) {
                com.onurkat.reclazz.bootstrap.RecordCodecs.reporter(ReclazzAgent::lateCodecFailure);
                var codecs = new com.onurkat.reclazz.transform.RecordCodecTransformer();
                instrumentation.addTransformer(codecs, true);
                try {
                    java.util.List<Class<?>> loadedCodecs = new java.util.ArrayList<>();
                    for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                        String name = type.getName();
                        if ((name.startsWith("com.fasterxml.jackson.databind.") || name.startsWith("tools.jackson.databind."))
                                && java.util.Set.of("ObjectMapper", "ObjectReader", "ObjectWriter", "MappingIterator")
                                .contains(name.substring(name.lastIndexOf('.') + 1))) {
                            if (!instrumentation.isModifiableClass(type)) throw new IllegalStateException("unmodifiable Jackson codec " + name);
                            loadedCodecs.add(type);
                        }
                    }
                    if (!loadedCodecs.isEmpty()) instrumentation.retransformClasses(loadedCodecs.toArray(Class<?>[]::new));
                    recordCodecHooksInstalled = true;
                } catch (Exception | LinkageError failure) {
                    recordCodecHooksInstalled = false;
                    StatusReporter.warn("Record snapshot hooks unavailable: " + Failures.describe(failure));
                }
            }

            // After the transform context exists, because half the answer to
            // "why didn't my class reload?" is what that context knows about
            // the class in question.
            diagnostics = new ReloadDiagnostics(instrumentation, platformContext, transformContext,
                    java.time.Instant.now());
            verification = new ReloadVerification(() -> {
                Map<String, Integer> counts = new java.util.HashMap<>();
                for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                    counts.merge(loaded.getName(), 1, Integer::sum);
                }
                return counts;
            });
            if (statusServer != null) {
                statusServer.setVerification(verification);
                statusServer.setDiagnoser(diagnostics::explain);
            }

            var compilerHolder = new java.util.concurrent.atomic.AtomicReference<IncrementalCompiler>();
            if (config.isAutoCompile()) {
                prepareCompiler(platformContext); compilerHolder.set(createCompiler(platformContext, config));
                StatusReporter.info("AutoCompile enabled: watching source files, compiling internally");
            } else StatusReporter.info("Default mode: watching compiled class files");

            // Set up class reloader
            ClassReloader reloader = new ClassReloader(instrumentation, config);

            // Set up Spring reload orchestrator (works for all platforms)
            SpringReloadOrchestrator springOrchestrator = new SpringReloadOrchestrator(platformContext);
            activeSpringOrchestrator=springOrchestrator;
            springOrchestrator.setPreservedSingletons(modules::preservedSingletons);
            springOrchestrator.setLoadedClasses(()->java.util.Arrays.stream(instrumentation.getAllLoadedClasses())
                    .filter(type->transformContext.isWatched(type.getName().replace('.','/'))).toArray(Class<?>[]::new));
            springOrchestrator.setRefreshOwnersOnHelper(config.getRefreshOwnersOnHelper());

            // Set up XML reloader, parses *-spring.xml changes into a
            // throwaway factory, diffs against the live bean factory, and
            // applies safe changes (property mutations + new-bean adds) in
            // place without destroy+recreate. Unsafe changes become a single
            // "restart required" warning, live context stays untouched.
            springXmlReloader = new SpringXmlReloader(platformContext, paths -> {
                java.util.Set<java.nio.file.Path> admitted = new java.util.LinkedHashSet<>();
                for (var path : paths) {
                    String module = platformContext.resolveModuleName(path);
                    if (platformContext.shouldWatch(module) && !config.isExcluded(path.getFileName().toString())
                            && (!isHybris || platformContext.getResourceDirs().containsKey(module))) admitted.add(path);
                    else com.onurkat.reclazz.platform.SapProcessDefinitions.forget(path);
                }
                for (var path : admitted) {
                    String module = platformContext.resolveModuleName(path);
                    modules.handle(new PlatformChangeHandler.Change(Capability.PROCESS_RELOAD, path,
                            "CREATED", module, "sap-process-registration"));
                }
            }, modules::beforeXmlBean);

            // The registry owns optional automation; discovery and the reload engine do not.
            codegenSourceGate = new CodegenSourceGate();
            if (suppliedModules == null) registerPremiumAutomation(modules, config, platformContext);

            // Set up file watcher
            FileWatcher watcher = new FileWatcher(platformContext, config, attached);
            watcher.onPlatformChange(modules);
            watcher.onPropertyFileFound(ReclazzAgent::baselinePropertyFile);
            watcher.onPropertySnapshots(propertySnapshots);

            // Here rather than beside the diagnoser above, because the numbers
            // that separate "nothing reloads" from "nothing I changed was being
            // watched" belong to the watcher, and it does not exist until now.
            if (statusServer != null) {
                statusServer.setScanner(watcher::requestScan);
                statusServer.setScanCompleter(watcher::scanNowAndCount);
                statusServer.setHealthReporter(() -> withStall(SessionReport.lines(
                        watcher.watchedDirectoryCount(),
                        watcher.unwatchableCount(),
                        RestartLedger.size(),
                        watcher.watchedFileCount(),
                        watcher.polls(),watcher.health())));
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
                    beginReloadBatch(springOrchestrator);
                }

                @Override
                public void end() {
                    endReloadBatch(springOrchestrator);
                }
            };
            reloadQueue = ReloadQueue.start(
                    event -> handleChange(event, compilerHolder.get(), reloader,
                            springOrchestrator, modules, watcher::dispatchPlatformChange, config),
                    batchBracket, config.isRequestBoundary() ? RequestReloadBoundary::run : Runnable::run,
                    new ReloadQueue.SavePreparer() {
                        public ReloadQueue.SavePlan prepare(java.util.List<ChangeEvent> captured) {
                            return prepareSwitchMaps(captured, watcher);
                        }
                        public void complete() { try { SwitchMapRefresh.end(); } finally { closeRecordSave(); } }
                    });

            reloadQueue.setVerification(verification);
            watcher.onGeneratedOutputHold(reloadQueue::generatedOutputHeld);
            generatedClassScan=watcher::scanNow;
            final ReloadQueue sourceQueue = reloadQueue;
            Runnable compileSources = () -> {
                var compiler = compilerHolder.get();
                if (config.liveOptions().autoCompile() && compiler != null)
                    handleJavaBatch(compiler, reloader, springOrchestrator, modules);
            };
            resumeJavaSources = () -> sourceQueue.submitAutomatic("java-source-batch", "Compiling saved Java sources", compileSources);
            heldSourceRetry = () -> retryHeldSourcesAfterCodegen(sourceQueue, compileSources);
            var compilerLifecycle = new CompilerLifecycle(reloadQueue, watcher, compilerHolder,config,platformContext,
                    transformContext,instrumentation::getAllLoadedClasses,p -> createCompiler(p, config),ReclazzAgent::prepareCompiler,
                    () -> { var retry=heldSourceRetry; if(retry!=null)retry.run(); }, scopeFence);
            if (statusServer != null) statusServer.setLiveConfig(new LiveConfigController(config,platformContext,compilerLifecycle::transitionRuntime));
            final ReloadQueue repairQueue=reloadQueue;
            final StatusServer repairTimer=statusServer;
            springOrchestrator.setOperationScheduler(repairTimer==null?null:repairTimer::scheduleOperationRepair,
                    work->repairQueue.submitAutomatic("operation-metadata-repair","Repairing operation metadata",work));
            if (statusServer != null) {
                statusServer.setOwnedBuildListener((owner, state) -> reloadQueue.build(owner, state, watcher::scanNow));
                statusServer.setDoctorContext(watcher, reloadQueue::buildHoldKind);
                // PENDING asks for this read-only record too; link it before jar replacement.
                reloadQueue.control("status", watcher::scanNow).fields();
                statusServer.setReloadControl(action -> reloadQueue.control(action, watcher::scanNow).fields());
            }

            // Register Java saves on the reload queue, after this pass's codegen inputs.
            // An older drain cannot see these callers before the XML handler admits the run.
            watcher.onFileChanges(events -> {
                int classFiles = 0;
                for (ChangeEvent event : codegenFirst(events)) {
                    String fn = event.getPath().getFileName().toString();
                    if (fn.endsWith(".java") && config.isAutoCompile() && compilerHolder.get() != null) {
                        sourceQueue.submitAutomatic(event.getPath().toString(), "Handling " + fn, () -> {
                            if (!platformContext.shouldWatch(event.getModuleName())) return;
                            synchronized (pendingJavaChanges) {
                                javaChangeGeneration++;
                                speculativeJava.remove(event.getPath());
                                if (event.getType() == ChangeEvent.Type.DELETED) pendingJavaChanges.remove(event.getPath());
                                else pendingJavaChanges.put(event.getPath(), event);
                            }
                            sourceQueue.submitAutomatic("java-source-batch", "Compiling saved Java sources", compileSources);
                        });
                        continue;
                    }
                    if (fn.endsWith(".class") && event.getType() != ChangeEvent.Type.DELETED) {
                        reloadQueue.enqueueClassFile(event);
                        classFiles++;
                        continue;
                    }
                    reloadQueue.submitAutomatic(event.getPath().toString(),
                            "Handling " + event.getPath().getFileName(),
                            () -> handleChange(event, compilerHolder.get(), reloader,
                                    springOrchestrator, modules, watcher::dispatchPlatformChange, config));
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
            {
                constantRebuild = byModule -> {
                    if (!config.liveOptions().autoCompile() || compilerHolder.get() == null) return;
                    synchronized (pendingJavaChanges) {
                        for (var moduleEntry : byModule.entrySet()) {
                            for (Path file : moduleEntry.getValue()) {
                                javaChangeGeneration++;
                                if (pendingJavaChanges.containsKey(file)) continue;
                                speculativeJava.add(file);
                                pendingJavaChanges.put(file, new ChangeEvent(
                                        file, ChangeEvent.Type.MODIFIED,
                                        moduleEntry.getKey(), null));
                            }
                        }
                    }
                    reloadQueue.submitAutomatic("constant-dependents", "Rebuilding constant dependents",
                            () -> {
                                var compiler=compilerHolder.get();
                                if(config.liveOptions().autoCompile() && compiler!=null)
                                    handleJavaBatch(compiler, reloader, springOrchestrator, modules);
                            });
                };
            }

            // The watch loop occupies one worker; XML deadlines must fire even with no filesystem events.
            var watchPool = Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "Reclazz-Watcher");
                t.setDaemon(true);
                return t;
            });

            watcherExecutor = watchPool;
            springXmlReloader.setClassWaitTimer(watchPool);
            running = true;
            // submit() hands anything thrown to a Future nobody holds. The
            // watcher dying that way is a session where saving a file does
            // nothing, for as long as the server is up, with the IDE still
            // showing a connected agent because the heartbeat is another
            // thread.
            watcherExecutor.submit(watcher::startWatching);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running = false;
                watcher.stopWatching();
                if(activeSpringOrchestrator!=null)activeSpringOrchestrator.closeOperationRepairs();
                if (statusServer != null) {
                    statusServer.stop();
                }
                if (reloadQueue != null) {
                    generatedClassScan=null;
                    heldSourceRetry=null;
                    resumeJavaSources=null;
                    com.onurkat.reclazz.transform.KeepBaseline.setWatchedPredicate(null);
                    reloadQueue.shutdown();
                }
                if (watcherExecutor != null) {
                    watcherExecutor.shutdownNow();
                }
                StatusReporter.info("Reclazz agent stopped.");
            }, "Reclazz-Shutdown"));

            StatusReporter.info("Press Ctrl+C or stop the server to deactivate.");

        } catch (Throwable e) {
            // Throwable, not Exception. Whatever escapes premain ends the JVM
            // before the application's main has run ("processing of -javaagent
            // failed"), and an Error is what an agent's start-up dies of: a
            // class missing from the jar, a static initialiser that threw. The
            // agent failing to start costs hot reload; the application failing
            // to start costs the application, and that is not this tool's
            // call to make.
            if(activeSpringOrchestrator!=null)activeSpringOrchestrator.closeOperationRepairs();
            activeSpringOrchestrator=null;
            StatusReporter.error("Failed to initialize Reclazz: " + Failures.describe(e));
            StatusReporter.error("  The application starts without hot reload.");
            StatusReporter.error("  Stack trace: " + e);
            if (statusServer != null) {
                try { statusServer.stop(); } catch (Exception ignored) {}
                statusServer = null;
            }
            if (reloadQueue != null) {
                generatedClassScan=null;
                heldSourceRetry=null;
                resumeJavaSources=null;
                com.onurkat.reclazz.transform.KeepBaseline.setWatchedPredicate(null);
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

    /** One fixed optional entry point, isolated by the registry's Throwable boundary. */
    private static final com.onurkat.reclazz.config.ScopeFence scopeFence = new com.onurkat.reclazz.config.ScopeFence();

    private static void registerPremiumAutomation(PremiumModules modules, AgentConfig config, PlatformContext platform) {
        final CodegenSourceGate sourceGate = codegenSourceGate;
        modules.register(() -> {
            try {
                var factory = (PremiumModuleFactory) Class.forName(
                        "com.onurkat.reclazz.hybris.SapPremiumModuleFactory", true,
                        ReclazzAgent.class.getClassLoader()).getDeclaredConstructor().newInstance();
                return factory.create(new PremiumContext() {
                    @Override public com.onurkat.reclazz.config.ScopeFence scopeFence() { return scopeFence; }
                    @Override public PlatformContext platform() { return platform; }
                    @Override public AgentConfig config() { return config; }
                    @Override public PropertyFileSnapshots propertySnapshots() { return propertySnapshots; }
                    @Override public Instrumentation instrumentation() { return instrumentation; }
                    @Override public int applyLoggerLevels(Map<String, String> content, java.util.Collection<String> keys) {
                        return ReclazzAgent.applyLoggerLevels(content, keys);
                    }
                    @Override public int resetLoggerLevels(Map<String,String> previous, java.util.Collection<String> keys, Map<String,String> effective) {
                        return ReclazzAgent.resetLoggerLevels(previous, keys, effective);
                    }
                    @Override public PremiumContext.GeneratedOutput holdGeneratedClasses(java.util.Collection<Path> roots) {
                        ReloadQueue queue=reloadQueue; Runnable scan=generatedClassScan;
                        if (queue == null || scan == null) throw new IllegalStateException("Generated class admission is not ready");
                        return queue.holdGeneratedClasses(roots,scan);
                    }
                    @Override public Runnable beginCodegenSourceHold() { return sourceGate.begin(); }
                    @Override public void generatedOutputAccepted() {
                        Runnable retry = heldSourceRetry;
                        if (retry != null) sourceGate.accepted(retry);
                    }
                    @Override public boolean requestRunningSystemUpdate() {
                        return modules.request(Capability.RUNNING_SYSTEM_UPDATE).granted();
                    }
                    @Override public void reapplyXmlProperties(java.util.Map<String,String> values) {
                        new com.onurkat.reclazz.spring.xml.SpringXmlReloader(platformContext).reapplyPlaceholderProperties(values);
                    }
                    @Override public void propertiesApplied(java.util.Collection<String> keys) {
                        com.onurkat.reclazz.spring.SapCommerceMappings.reselect(keys);
                    }
                });
            } catch (ClassNotFoundException absent) {
                StatusReporter.info("Optional platform automation is absent; free reload remains active.");
                return () -> java.util.Set.of();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Optional platform automation factory could not be created", failure);
            }
        });
    }

    private static void prepareCompiler(PlatformContext platform) {
        com.onurkat.reclazz.transform.AdmittedBytes.clear();
        if (platform instanceof HybrisPlatformContext hybrisPlatform) {
            var hybris = hybrisPlatform.getHybrisContext();
            com.onurkat.reclazz.platform.BootstrapGeneratedCopies.remove(hybris.getPlatformHome(),
                    hybris.getExtensions().values().stream().filter(ext -> platform.shouldWatch(ext.getName())).toList());
        }
    }
    private static IncrementalCompiler createCompiler(PlatformContext platform, AgentConfig config) {
        boolean isHybris = platform.getPlatformId() == PlatformContext.Platform.HYBRIS;
        IncrementalCompiler compiler;
                String classpath = platform.resolveClasspath();
                if (isHybris) {
                    compiler = new IncrementalCompiler(classpath,
                            ((HybrisPlatformContext) platform).getHybrisContext(),
                            ((HybrisPlatformContext) platform).getBackofficeClassDirs().keySet());
                } else {
                    compiler = new IncrementalCompiler(classpath, platform);
                    compiler.configureMaven(config);
                }
                compiler.setByproductProof(names -> {
                    Map<String, List<Class<?>>> byName = new java.util.HashMap<>();
                    if (transformContext != null && instrumentation != null) {
                        java.util.Set<String> watched = new java.util.HashSet<>();
                        for (String name : names)
                            if (transformContext.isWatched(name.replace('.', '/'))) watched.add(name);
                        // One census per attempt, never Class.forName: an unloaded
                        // constant owner must still reach dependent rebuilding.
                        if (!watched.isEmpty()) for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                            String name = loaded.getName();
                            if (watched.contains(name))
                                byName.computeIfAbsent(name, ignored -> new java.util.ArrayList<>()).add(loaded);
                        }
                    }
                    return (name, bytes) -> {
                        List<Class<?>> loaded = byName.get(name);
                        if (loaded == null) return false;
                        for (Class<?> type : loaded)
                            if (nativeRepairPending(type) || nativeFrameworkPending(type)
                                    || !com.onurkat.reclazz.transform.AdmittedBytes.matches(type, bytes)) return false;
                        return true;
                    };
                });
        return compiler;
    }

    private static void handleChange(ChangeEvent event,
                                     IncrementalCompiler compiler,
                                     ClassReloader reloader,
                                     SpringReloadOrchestrator springOrchestrator,
                                     PremiumModules modules,
                                     java.util.function.BiFunction<Capability, ChangeEvent, PlatformChangeHandler.Outcome> platformChanges,
                                     AgentConfig config) {
        try {
            // The queue may still contain an event admitted before scope removal.
            if(platformContext!=null&&!platformContext.shouldWatch(event.getModuleName()))return;
            String fileName = event.getPath().getFileName().toString();

            // What the file is, decided in one testable place; whether we act
            // on it stays here, because that depends on configuration.
            ChangeKind kind = ChangeKind.of(event.getPath());
            if (platformContext instanceof SpringBootContext boot
                    && boot.handleMavenResource(event.getPath(), event.getType() == ChangeEvent.Type.DELETED)) return;
            if (platformContext instanceof SpringBootContext boot && boot.sourceResource(event.getPath())
                    && (kind == ChangeKind.TEMPLATE || kind == ChangeKind.UNKNOWN)) {
                boot.sourceResourceSaved(event.getPath());
                return;
            }
            switch (kind) {
                case CLASS_FILE -> {
                    if (event.getType() != ChangeEvent.Type.DELETED) {
                        handleClassFileChange(event, reloader, springOrchestrator, modules);
                    }
                }
                case JAVA_SOURCE -> {
                    if (config.isAutoCompile() && compiler != null) {
                        handleJavaBatch(compiler, reloader, springOrchestrator, modules);
                    } else if (!config.isAutoCompile()) {
                        StatusReporter.info("Source changed: " + fileName + " [" + event.getModuleName() + "]");
                        StatusReporter.info("Build your project to compile, Reclazz will hot-swap automatically.");
                    }
                }
                case SPRING_XML -> handleSpringXmlChange(event);
                case SAP_PROCESS_XML -> platformChanges.apply(Capability.PROCESS_RELOAD, event);
                case BACKOFFICE_CONFIG -> platformChanges.apply(Capability.BACKOFFICE_RELOAD, event);
                case CODEGEN_XML -> {
                    if (platformContext instanceof HybrisPlatformContext) platformChanges.apply(Capability.CODEGEN, event);
                    else {
                        StatusReporter.info("Codegen XML changed: " + fileName);
                        StatusReporter.warn("Codegen reload requires a Hybris platform, not available here.");
                    }
                }
                case PROPERTIES -> {
                    if (platformContext instanceof HybrisPlatformContext) {
                        // Classification is free even when no automation module is registered.
                        if (SapConfigFiles.isPlatformConfiguration(event.getPath()))
                            platformChanges.apply(Capability.CONFIG_APPLY, event);
                        else StatusReporter.info(fileName + " is not platform configuration, so nothing was applied.");
                    } else handleSpringPropertiesChange(event);
                }
                case LOGGING_CONFIG -> handleLoggingConfigChange(event);
                case IMPEX -> {
                    if (config.isAutoImpex() && platformContext != null
                            && platformContext.getPlatformId() == PlatformContext.Platform.HYBRIS) {
                        platformChanges.apply(Capability.IMPEX_IMPORT, event);
                    }
                }
                case LOCALIZATION -> {
                    if (prepareLocalizationChange(event)) {
                        if (platformContext instanceof HybrisPlatformContext) platformChanges.apply(Capability.LOCALIZATION_RELOAD, event);
                        else handleLocalizationChange(event);
                    }
                }
                case TEMPLATE -> handleTemplateChange(event, config);
                case UNKNOWN -> { }
            }
        } catch (Throwable e) {
            // Throwable, not Exception: ASM and defineHiddenClass surface
            // LinkageError/VerifyError, which previously escaped this handler
            // and silently killed the batch (no event, no log, a test just
            // times out waiting for RELOAD).
            StatusReporter.error("Error handling change: " + e);
            if (e.getCause() != null) {
                StatusReporter.error("  Caused by: " + e.getCause());
            }
        }
    }

    private static Map<String,String> prepareConstructorDependencies(Map<String,byte[]> accepted,
            com.onurkat.reclazz.util.ClassLookup.LoadedClasses loaded) {
        if (platformContext == null || accepted.isEmpty()) return Map.of();
        return com.onurkat.reclazz.spring.SpringConstructorSave.apply(accepted, loaded, platformContext::getAllApplicationContexts);
    }

    private static void refuseSave(String name, String reason, String source) {
        recordOutcome(name, false, reason, source);
        StatusReporter.error("Reload refused for " + name + ": " + reason);
        ReloadEvents.failed(name, reason, source);
        SessionReport.failed();
    }

    private static void refuseSwitchMap(String name, String reason, String source) {
        String why = "switch table could not be refreshed; restart to apply: " + reason;
        recordOutcome(name, false, why, source);
        StatusReporter.error("Hot-swap failed for " + displayName(name) + ": " + why);
        ReloadEvents.failed(name, why, source);
        SessionReport.failed();
    }

    private static Path switchOutputRoot(Path file, String internalName) throws IOException {
        Path root = file.toAbsolutePath().normalize();
        for (String part : (internalName + ".class").split("/")) root = root.getParent();
        if (root == null || !root.resolve(internalName + ".class").equals(file.toAbsolutePath().normalize()))
            throw new IOException("switch holder path does not match its name");
        return root;
    }

    private static Path classOutputRoot(Class<?> type) throws Exception {
        var source = type.getProtectionDomain().getCodeSource();
        if (source == null || !"file".equals(source.getLocation().getProtocol()))
            throw new IOException("class output root is unavailable");
        return Path.of(source.getLocation().toURI()).toRealPath();
    }

    /** Capture only a proven append from this holder's actual output and defining loader. */
    private static void capturePendingEnums(Map<String, byte[]> candidates, Map<String, Path> paths,
                                           Map<String, SwitchMapPlan> plans, Map<String, String> refused,
                                           com.onurkat.reclazz.util.ClassLookup.LoadedClasses loaded) {
        for (var item : plans.entrySet()) {
            String holderName = item.getKey();
            Map<String, byte[]> pulled = new java.util.LinkedHashMap<>();
            Map<String, Path> pulledPaths = new java.util.LinkedHashMap<>();
            try {
                Class<?> holder = loaded.find(holderName);
                Class<?> origin=holder;
                if (origin == null) {
                    for (var candidate : candidates.entrySet()) {
                        if (!SwitchMapPlan.references(candidate.getValue()).contains(holderName.replace('.', '/'))) continue;
                        Class<?> outer = loaded.find(candidate.getKey());
                        if (outer != null) { origin=outer; break; }
                    }
                }
                if (origin == null) continue; // A genuinely new class has no old table to refresh.
                for (var table : item.getValue().tables()) {
                    Class<?> type = loaded.find(table.enumName().replace('/', '.'), origin.getClassLoader());
                    if (type == null) type=loaded.find(table.enumName().replace('/', '.'));
                    if (type == null) continue; // First use will load its fresh native definition.
                    java.util.Set<String> missing = new java.util.LinkedHashSet<>();
                    for (var entry : table.entries()) missing.add(entry.constant());
                    Object[] values = type.getEnumConstants();
                    if (values == null) throw new IOException("switch target is not an enum");
                    for (Object value : values) missing.remove(((Enum<?>) value).name());
                    if (missing.isEmpty()) continue;
                    Path holderPath = paths.get(holderName);
                    if (holderPath == null) throw new IOException("switch holder output path is unavailable");
                    Path root = switchOutputRoot(holderPath, holderName.replace('.', '/'));
                    if (type.getClassLoader() != origin.getClassLoader() || !root.toRealPath().equals(classOutputRoot(type))
                            || !root.toRealPath().equals(classOutputRoot(origin)))
                        throw new IOException("pending enum has another output root or defining loader");
                    String enumName = type.getName();
                    Path file = root.resolve(table.enumName() + ".class");
                    byte[] pending = candidates.get(enumName);
                    if (pending != null && !file.toRealPath().equals(paths.get(enumName).toRealPath()))
                        throw new IOException("pending enum event has another output path");
                    if (pending == null) pending = Files.readAllBytes(file);
                    org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(pending);
                    if (!reader.getClassName().equals(table.enumName()) || (reader.getAccess() & org.objectweb.asm.Opcodes.ACC_ENUM) == 0)
                        throw new IOException("pending enum bytes have another identity");
                    java.util.Set<String> declared = new java.util.HashSet<>();
                    reader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor,
                                                                                  String signature, Object value) {
                            if ((access & org.objectweb.asm.Opcodes.ACC_ENUM) != 0) declared.add(name);
                            return null;
                        }
                    }, org.objectweb.asm.ClassReader.SKIP_CODE);
                    missing.retainAll(declared); // absent in both versions: javac's NoSuchFieldError guard skips it.
                    if (missing.isEmpty()) continue;
                    if (!com.onurkat.reclazz.reload.EnumConstantChange.isAppendOnly(type, pending)
                            || !com.onurkat.reclazz.reload.EnumConstantChange.appendedNames(type, pending).containsAll(missing))
                        throw new IOException("pending enum is not an append of the missing cases");
                    pulled.put(enumName, pending); pulledPaths.put(enumName, file);
                }
                candidates.putAll(pulled); paths.putAll(pulledPaths);
            } catch (Exception unavailable) {
                refused.put(holderName, unavailable.getMessage() == null ? unavailable.getClass().getSimpleName() : unavailable.getMessage());
            }
        }
        candidates.forEach((name, bytes) -> {
            for (String reference : SwitchMapPlan.references(bytes)) {
                String reason = refused.get(reference.replace('/', '.'));
                if (reason != null) refused.put(name, reason);
            }
        });
    }

    private static NativeRecordSave prepareNativeRecords(Map<String, byte[]> incoming, Class<?>[] census) {
        boolean stock=structuralReloader!=null;
        if(transformContext==null||!com.onurkat.reclazz.reload.RecordReloadPlan.ready(stock
                ?com.onurkat.reclazz.reload.RecordReloadPlan.Route.STOCK:com.onurkat.reclazz.reload.RecordReloadPlan.Route.NATIVE))return null;
        boolean shape = false; java.util.Set<String> pendingRepairs = new java.util.HashSet<>();
        for (Class<?> type : census) {
            byte[] raw = incoming.get(type.getName());
            if (raw == null || !type.isRecord()) continue;
            if (nativeRepairPending(type)) { pendingRepairs.add(type.getName()); shape = true; }
            if (com.onurkat.reclazz.reload.RecordShapeCheck.changed(type, raw)
                    || com.onurkat.reclazz.transform.KeepBaseline.recordMetadataChanged(type, raw)
                    || stock&&com.onurkat.reclazz.transform.KeepBaseline.hasVirtualRecordReceipt(type)) shape = true;
        }
        if (!shape) return null;
        NativeRecordSave save;
        if(stock) {
            RecordStockSelfCheck.Result capability=stockRecordCapability;
            if(capability==null) {
                try{stockRecordCapability=capability=RecordStockSelfCheck.run(instrumentation);}
                catch(Throwable failure){
                    if(failure instanceof VirtualMachineError fatal){stockRecordCapability=fatalStockRecordCapability;throw fatal;}
                    stockRecordCapability=capability=new RecordStockSelfCheck.Result(false,Failures.describe(failure));
                }
                if(!capability.usable()) {
                    String restartReason="Stock record capability check failed; record shape changes still require restart: "+capability.reason();
                    RestartLedger.note("stock record component reload",restartReason);
                    StatusReporter.warn(restartReason);
                }
            }
            if(!capability.usable())return null;
            save=new NativeRecordSave(com.onurkat.reclazz.reload.RecordStockBatch.prepare(incoming,census,
                    name->transformContext.isWatched(name.replace('.','/')),instrumentation,transformContext,stockRecordVersion.incrementAndGet(),pendingRepairs));
            // S already owns pending sites. Every later preflight failure must reach the save cleanup.
            nativeRecordSave.set(save);
        }else {
            JvmCapabilityProbe.RecordCapability capability = nativeRecordCapability;
            if (capability == null) nativeRecordCapability = capability = JvmCapabilityProbe.probeRecordSchema(instrumentation);
            if (!capability.nativeSchema()) return null; // Keep the existing explicit R20 fallback unchanged.
            save = new NativeRecordSave(com.onurkat.reclazz.reload.RecordNativeBatch.prepare(incoming, census,
                    name -> transformContext.isWatched(name.replace('.', '/')), capability, instrumentation, pendingRepairs));
        }
        if (save.batch.refusals().isEmpty()) {
            try {
                if (!recordCodecHooksInstalled) throw new IllegalStateException("Jackson snapshot hooks were not installed");
                save.repairs = com.onurkat.reclazz.reload.RecordNativeRepairs.prepare(save.batch.changes().stream()
                        .map(com.onurkat.reclazz.reload.RecordNativeBatch.Change::owner).toList(),
                        platformContext == null ? java.util.List.of() : platformContext.getAllApplicationContexts());
            } catch (Exception | LinkageError failure) {
                save.preflightFailure = (stock?"stock":"native")+" record repair preflight failed: " + Failures.describe(failure);
            }
        }
        return save;
    }
    private static boolean nativeCohortMember(String name) {
        NativeRecordSave save = nativeRecordSave.get();
        return save != null && save.batch.names().contains(name);
    }
    private static void finishNativePreflight(NativeRecordSave save, java.util.Set<String> accepted) {
        if (save == null) { closeRecordSave(); return; }
        save.batch = save.batch.requireComplete(accepted);
        save.refusals(); // A late refusal must establish the same record provenance as an early one.
        nativeRecordSave.set(save);
    }

    private static com.onurkat.reclazz.reload.InterfaceBatchCheck.Hold capturedSaveHold(
            Map<String, byte[]> incoming, com.onurkat.reclazz.util.ClassLookup.LoadedClasses loaded) {
        if (probeResult != null && probeResult.hasEnhancedRedefinition()) return null;
        var hold = com.onurkat.reclazz.reload.JpaBatchCheck.check(incoming, loaded);
        if (hold == null) hold = com.onurkat.reclazz.reload.InterfaceBatchCheck.check(incoming, loaded);
        if (hold != null) {
            RestartLedger.note(hold.subject(), hold.reason());
            StatusReporter.warn(hold.reason());
            ReloadEvents.failed(hold.subject(), hold.reason(), null);
            SessionReport.failed();
        }
        return hold;
    }

    private static ReloadQueue.SavePlan prepareSwitchMaps(java.util.List<ChangeEvent> captured, FileWatcher watcher) {
        var loaded = com.onurkat.reclazz.util.ClassLookup.loadedClasses(instrumentation);
        Map<String, byte[]> recordBytes = new java.util.LinkedHashMap<>();
        Map<String, Path> recordPaths = new java.util.LinkedHashMap<>();
        for (ChangeEvent event : captured) {
            String name = new org.objectweb.asm.ClassReader(event.getBytes()).getClassName().replace('/', '.');
            recordBytes.put(name, event.getBytes()); recordPaths.put(name, event.getPath());
        }
        var batchHold = capturedSaveHold(recordBytes, loaded);
        if (batchHold != null) {
            Map<ChangeEvent, String> refused = new java.util.LinkedHashMap<>();
            for (ChangeEvent event : captured) {
                String name = new org.objectweb.asm.ClassReader(event.getBytes()).getClassName().replace('/', '.');
                recordOutcome(name, false, batchHold.reason(), event.getPath().toString());
                watcher.retryHeldClassOnRewrite(event);
                refused.put(event, batchHold.reason());
            }
            return new ReloadQueue.SavePlan(List.of(), refused);
        }
        NativeRecordSave nativeSave = prepareNativeRecords(recordBytes, loaded.all());
        Map<String, String> recordRejected = new java.util.LinkedHashMap<>(recordRefusals.preflight(recordBytes, recordPaths,
                loaded.all(), nativeSave == null ? java.util.Set.of() : nativeSave.eligible()));
        if (nativeSave != null) recordRejected.putAll(nativeSave.refusals());
        Map<ChangeEvent, String> recordEvents = new java.util.LinkedHashMap<>();
        for (ChangeEvent event : captured) {
            String name = new org.objectweb.asm.ClassReader(event.getBytes()).getClassName().replace('/', '.');
            if (recordRejected.containsKey(name)) recordEvents.put(event, recordRejected.get(name));
        }
        captured = captured.stream().filter(event -> !recordEvents.containsKey(event)).toList();
        java.util.Map<Path, ChangeEvent> events = new java.util.LinkedHashMap<>();
        for (ChangeEvent event : captured) {
            Class<?> owner = loaded.find(new org.objectweb.asm.ClassReader(event.getBytes()).getClassName().replace('/', '.'));
            boolean sameOutput = false;
            try { sameOutput = owner != null && classOutputRoot(owner).equals(switchOutputRoot(event.getPath(), owner.getName().replace('.', '/')).toRealPath()); }
            catch (Exception unavailable) { /* Unproven origins never suppress an event. */ }
            if (sameOutput && SwitchMapRefresh.alreadyApplied(owner, event.getBytes())
                    && !nativeFrameworkPending(owner)
                    && !(nativeSave != null && nativeSave.batch.names().contains(owner.getName()))) {
                recordRefusals.accepted(owner, event.getBytes());
                StatusReporter.detail("Unchanged applied class skipped: " + owner.getName());
                continue;
            }
            events.put(event.getPath().toAbsolutePath().normalize(), event);
        }
        java.util.Map<ChangeEvent, String> refused = new java.util.LinkedHashMap<>(recordEvents);
        for (ChangeEvent outer : java.util.List.copyOf(events.values())) {
            byte[] bytes = outer.getBytes();
            for (String reference : SwitchMapPlan.references(bytes)) {
                Path sibling = outer.getPath().toAbsolutePath().normalize().getParent()
                        .resolve(reference.substring(reference.lastIndexOf('/') + 1) + ".class");
                if (events.containsKey(sibling)) continue;
                try {
                    byte[] holderBytes = Files.readAllBytes(sibling);
                    if (!new org.objectweb.asm.ClassReader(holderBytes).getClassName().equals(reference)) {
                        refused.put(outer, "referenced switch holder has a different class name");
                        continue;
                    }
                    var parsed = SwitchMapPlan.parse(holderBytes);
                    if (parsed.plan() != null && SwitchMapRefresh.matches(
                            loaded.find(reference.replace('/', '.')), parsed.plan(), loaded)) continue;
                    events.put(sibling, new ChangeEvent(sibling, ChangeEvent.Type.MODIFIED,
                            outer.getModuleName(), outer.getSourceRoot()).withBytes(holderBytes));
                } catch (java.io.IOException unavailable) {
                    refused.put(outer, "referenced switch holder bytes are unavailable");
                }
            }
        }
        java.util.Map<String, byte[]> bytes = new java.util.LinkedHashMap<>();
        for (ChangeEvent event : events.values()) bytes.put(
                new org.objectweb.asm.ClassReader(event.getBytes()).getClassName().replace('/', '.'), event.getBytes());
        var preflight = SwitchMapPreflight.check(bytes);
        Map<String, Path> paths = new java.util.LinkedHashMap<>();
        events.values().forEach(event -> paths.put(new org.objectweb.asm.ClassReader(event.getBytes()).getClassName().replace('/', '.'), event.getPath()));
        Map<String, String> pendingRefused = new java.util.LinkedHashMap<>(preflight.refused());
        capturePendingEnums(bytes, paths, preflight.plans(), pendingRefused, loaded);
        SwitchMapPreflight.refuseDirectSwitches(bytes, loaded, pendingRefused);
        ChangeEvent context = captured.isEmpty() ? null : captured.get(0);
        if (context != null) bytes.forEach((name, value) -> {
            Path path = paths.get(name).toAbsolutePath().normalize();
            events.putIfAbsent(path, new ChangeEvent(path, ChangeEvent.Type.MODIFIED, context.getModuleName(), context.getSourceRoot()).withBytes(value));
        });
        java.util.List<ChangeEvent> apply = new java.util.ArrayList<>();
        java.util.Map<String, byte[]> accepted = new java.util.LinkedHashMap<>();
        for (ChangeEvent event : events.values()) {
            String name = new org.objectweb.asm.ClassReader(event.getBytes()).getClassName().replace('/', '.');
            String reason = pendingRefused.get(name);
            if (reason != null) refused.put(event, reason);
            if (!refused.containsKey(event)) { apply.add(event); accepted.put(name, event.getBytes()); }
        }
        finishNativePreflight(nativeSave, accepted.keySet());
        if (nativeSave != null) nativeSave.refusals().forEach((name, reason) -> {
            apply.removeIf(event -> {
                if (!new org.objectweb.asm.ClassReader(event.getBytes()).getClassName().replace('/', '.').equals(name)) return false;
                refused.put(event, reason); return true;
            });
            accepted.remove(name); recordRejected.put(name, reason);
        });
        Map<String,String> dependencies = prepareConstructorDependencies(accepted, loaded);
        apply.removeIf(event -> {
            String name = new org.objectweb.asm.ClassReader(event.getBytes()).getClassName().replace('/', '.');
            if (!dependencies.containsKey(name)) return false;
            refused.put(event, dependencies.get(name));
            accepted.remove(name);
            return true;
        });
        refused.forEach((event, reason) -> {
            String name = new org.objectweb.asm.ClassReader(event.getBytes()).getClassName().replace('/', '.');
            if (dependencies.containsKey(name) || recordRejected.containsKey(name)) refuseSave(name, reason, event.getPath().toString());
            else refuseSwitchMap(name, reason, event.getPath().toString());
        });
        SwitchMapRefresh.begin(accepted, preflight.plans(), loaded);
        return new ReloadQueue.SavePlan(com.onurkat.reclazz.reload.BatchOrder.calleesFirst(apply), refused);
    }

    private static void recordCompiledRefusals(Map<String,String> refusals,Map<String,String> sources) {
        // Preparation already emitted DIAGNOSE and failure events; only VERIFY needs the active bracket.
        ReloadVerification current=verification;
        if(refusals!=null&&current!=null)refusals.forEach((name,reason)->current.outcome(name,false,reason));
    }

    private static Map<String, byte[]> prepareCompiledSwitchMaps(Map<String, byte[]> candidates, Map<String, String> sources) {
        var loaded = com.onurkat.reclazz.util.ClassLookup.loadedClasses(instrumentation);
        var batchHold = capturedSaveHold(candidates, loaded);
        if (batchHold != null) {
            Map<String, String> refused = new java.util.LinkedHashMap<>();
            for (String name : candidates.keySet()) {
                recordOutcome(name, false, batchHold.reason(), sources.get(name));
                refused.put(name, batchHold.reason());
            }
            compiledSaveRefusals.set(refused);
            return Map.of();
        }
        Map<String, Path> recordPaths = new java.util.LinkedHashMap<>();
        if (platformContext != null) for (String name : candidates.keySet()) {
            List<Path> matches = new java.util.ArrayList<>();
            for (var directories : platformContext.getClassOutputDirs().values()) for (Path root : directories) {
                Path file = root.resolve(name.replace('.', '/') + ".class");
                try { if (Files.isRegularFile(file) && java.util.Arrays.equals(Files.readAllBytes(file), candidates.get(name))) matches.add(file); }
                catch (IOException unavailable) { /* Missing origin stays unknown, never guessed. */ }
            }
            if (matches.size() == 1) recordPaths.put(name, matches.get(0));
        }
        NativeRecordSave nativeSave = prepareNativeRecords(candidates, loaded.all());
        Map<String, String> recordRejected = new java.util.LinkedHashMap<>(recordRefusals.preflight(candidates, recordPaths,
                loaded.all(), nativeSave == null ? java.util.Set.of() : nativeSave.eligible()));
        if (nativeSave != null) recordRejected.putAll(nativeSave.refusals());
        Map<String, byte[]> compiled = new java.util.LinkedHashMap<>(candidates);
        recordRejected.keySet().forEach(compiled::remove);
        var preflight = SwitchMapPreflight.check(compiled);
        Map<String, String> refused = new java.util.LinkedHashMap<>(preflight.refused());
        refused.putAll(recordRejected);
        compiled.forEach((name, value) -> {
            for (String reference : SwitchMapPlan.references(value)) {
                if (compiled.containsKey(reference.replace('/', '.'))) continue;
                Class<?> holder = loaded.find(reference.replace('/', '.'));
                if (holder == null) continue;
                try (var input = holder.getResourceAsStream("/" + reference + ".class")) {
                    var parsed = input == null ? null : SwitchMapPlan.parse(input.readAllBytes());
                    if (parsed == null || parsed.plan() == null || !SwitchMapRefresh.matches(holder, parsed.plan(), loaded))
                        refused.put(name, "referenced switch holder is absent from compiler output");
                } catch (java.io.IOException unavailable) {
                    refused.put(name, "referenced switch holder bytes are unavailable");
                }
            }
        });
        Map<String, byte[]> accepted = new java.util.LinkedHashMap<>(compiled);
        Map<String, Path> paths = new java.util.LinkedHashMap<>();
        for (String name : preflight.plans().keySet()) {
            Class<?> holder = loaded.find(name);
            if (holder != null) try { paths.put(name, classOutputRoot(holder).resolve(name.replace('.', '/') + ".class")); }
            catch (Exception unavailable) { refused.put(name, "switch holder output root is unavailable"); }
        }
        for (String name : compiled.keySet()) {
            Class<?> type = loaded.find(name);
            if (type != null) try { paths.putIfAbsent(name, classOutputRoot(type).resolve(name.replace('.', '/') + ".class")); }
            catch (Exception unavailable) { /* Needed enum origins are refused by capture. */ }
        }
        capturePendingEnums(accepted, paths, preflight.plans(), refused, loaded);
        SwitchMapPreflight.refuseDirectSwitches(accepted, loaded, refused);
        finishNativePreflight(nativeSave, accepted.keySet().stream().filter(name -> !refused.containsKey(name))
                .collect(java.util.stream.Collectors.toSet()));
        if (nativeSave != null) { refused.putAll(nativeSave.refusals()); recordRejected.putAll(nativeSave.refusals()); }
        refused.keySet().forEach(accepted::remove);
        Map<String,String> dependencies = prepareConstructorDependencies(accepted, loaded);
        refused.putAll(dependencies);
        refused.forEach((name, reason) -> {
            accepted.remove(name);
            if (dependencies.containsKey(name) || recordRejected.containsKey(name)) refuseSave(name, reason, sources.get(name));
            else refuseSwitchMap(name, reason, sources.get(name));
        });
        java.util.List<ChangeEvent> events = new java.util.ArrayList<>();
        accepted.forEach((name, value) -> events.add(new ChangeEvent(
                paths.getOrDefault(name, Path.of(name.replace('.', '/') + ".class")), ChangeEvent.Type.MODIFIED,
                "compiled", "classes").withBytes(value)));
        Map<String, byte[]> ordered = new java.util.LinkedHashMap<>();
        for (ChangeEvent event : com.onurkat.reclazz.reload.BatchOrder.calleesFirst(events)) {
            String name = new org.objectweb.asm.ClassReader(event.getBytes()).getClassName().replace('/', '.');
            ordered.put(name, event.getBytes());

        }
        SwitchMapRefresh.begin(ordered, preflight.plans(), loaded);
        compiledSaveRefusals.set(Map.copyOf(refused));
        return ordered;
    }

    private static void handleClassFileChange(ChangeEvent event,
                                              ClassReloader reloader,
                                              SpringReloadOrchestrator springOrchestrator,
                                              PremiumModules modules) {
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
        // A single-file record recovery must wait for the native terminal
        // result too; companion publication alone cannot clear its restart note.
        boolean recordRecovery = frameworkBatchDepth == 0 && recordRefusals.recovering(className);
        if (recordRecovery) beginReloadBatch(springOrchestrator);
        try {
            String internalName = className.replace('.', '/');

            if (transformContext != null && !transformContext.isWatched(internalName)
                    && transformContext.isInWatchedDir(classFile)) {
                transformContext.addWatched(internalName);
                StatusReporter.info("New class added to watched set: " + className);
            }

            long startTime = System.currentTimeMillis();
            ReloadEffects.begin(className);

            Class<?> owner = findLoadedClass(className);
            if (!nativeCohortMember(className)) chaseChangedConstants(internalName, className, bytecode);

            // A class the JVM has never loaded is exactly the case where the
            // stock-JDK wall does not stand: everything about it is real. If
            // it carries a Spring stereotype it becomes a live bean here, and
            // the ordinary reload machinery has nothing left to do for it.
            boolean wasLoaded = owner != null;
            if (!wasLoaded) {
                var registration = springOrchestrator.registerNewBeanClass(className, bytecode);
                if (registration.isHandled()) {
                    if (registration.isSuccess()) retryXmlForClass(className);
                    recordOutcome(className, registration.isSuccess(), registration.description(), source);
                    ReloadEffects.end();
                    return;
                }
                JpaMappingRefresh.maybeMapNewEntity(
                        className, bytecode, platformContext.getAllApplicationContexts());
                owner = findLoadedClass(className);
            }

            var keepInput = new com.onurkat.reclazz.transform.KeepBaseline.Input(
                    com.onurkat.reclazz.transform.KeepBaseline.get(owner), bytecode);
            reloadResult = reloadWithCacheBoundary(className, internalName, bytecode, reloader, owner);

            long elapsed = System.currentTimeMillis() - startTime;

            recordOutcome(className, reloadResult.isSuccess(),
                    reloadResult.isSuccess() ? kindOf(reloadResult) : reloadResult.getError(), source);

            if (reloadResult.isSuccess()) {
                if (nativeCohortMember(className)) chaseChangedConstants(internalName, className, bytecode);
                deferOrComplete(new FrameworkReload(className, owner, reloadResult, bytecode, wasLoaded,
                        source, startTime, true, springOrchestrator, modules, keepInput));
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
        } finally {
            if (recordRecovery) endReloadBatch(springOrchestrator);
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
        ReloadVerification v = verification;
        if (v != null) v.outcome(className, success, detail);
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
    private static void reloadLanded(String className, Class<?> owner, boolean structural, long elapsed,
                                     String shape, String source, String effects) {
        SessionReport.reloaded(structural, elapsed);
        if (structural) {
            StatusReporter.structuralReload(displayName(className), elapsed, shape, effects);
        } else {
            StatusReporter.reload(displayName(className), elapsed, effects);
        }
        ReloadEvents.reloaded(className, structural, elapsed, shape, source);
        SapIndexingGuidance.afterReload(owner,
                agentConfig != null && agentConfig.isAutoSolrReindex(), premiumModules::reindex);
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

    static List<ChangeEvent> codegenFirst(List<ChangeEvent> events) {
        if (events.size() < 2 || events.stream().noneMatch(e -> ChangeKind.of(e.getPath()) == ChangeKind.CODEGEN_XML))
            return events;
        return events.stream().sorted(java.util.Comparator.comparing(e -> ChangeKind.of(e.getPath()) != ChangeKind.CODEGEN_XML)).toList();
    }

    /** Decide after any in-flight compile has retained its failed sources on the same queue. */
    static void retryHeldSourcesAfterCodegen(ReloadQueue queue, Runnable compile) {
        if (queue == null) return;
        final long retryGeneration;
        synchronized (pendingJavaChanges) { retryGeneration = lastJavaAttemptGeneration; }
        queue.submitAutomatic("codegen-held-retry", "Compiling held sources after codegen", () -> {
            synchronized (pendingJavaChanges) {
                pendingJavaChanges.keySet().removeIf(path -> !Files.isRegularFile(path));
                if (pendingJavaChanges.isEmpty()) return;
                if (javaChangeGeneration == retryGeneration) javaChangeGeneration++;
                else if (javaChangeGeneration == lastJavaAttemptGeneration) return;
                StatusReporter.info("Codegen finished; compiling " + pendingJavaChanges.size()
                        + " held source files again");
            }
            compile.run();
        });
    }

    private static void handleJavaBatch(IncrementalCompiler compiler,
                                        ClassReloader reloader,
                                        SpringReloadOrchestrator springOrchestrator,
                                        PremiumModules modules) {
        java.util.List<ChangeEvent> batch;
        synchronized (pendingJavaChanges) {
            if (lastJavaAttemptGeneration == javaChangeGeneration) return;
            pendingJavaChanges.keySet().removeIf(path -> !Files.isRegularFile(path));
            if (pendingJavaChanges.isEmpty()) return;
            if (codegenSourceGate.defer(resumeJavaSources, pendingJavaChanges.size())) return;
            lastJavaAttemptGeneration = javaChangeGeneration;
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
            List<Path> dropped = com.onurkat.reclazz.compiler.CompileAttempt.dropSpeculative(
                    pendingJavaChanges, speculativeJava);
            boolean retrySaved;
            synchronized (pendingJavaChanges) {
                retrySaved = !dropped.isEmpty() && !pendingJavaChanges.isEmpty();
                if (retrySaved) javaChangeGeneration++;
                if (!dropped.isEmpty()) {
                    StatusReporter.warn("Gave up rebuilding " + Plural.of(dropped.size(), "constant dependent")
                            + " that did not compile; they keep the value they were built with until "
                            + "your build compiles them.");
                }
                if (!pendingJavaChanges.isEmpty()) {
                    StatusReporter.info("Holding " + pendingJavaChanges.size()
                            + " source files; nothing reloads until compilation succeeds. "
                            + (result.getErrors().isEmpty() ? "" : result.getErrors().get(0)));
                }
            }
            // The dependents may have been all that failed; what was saved gets
            // its own attempt rather than waiting for the next save.
            if (retrySaved) handleJavaBatch(compiler, reloader, springOrchestrator, modules);
            return;
        }
        Map<String, byte[]> compiledClasses = result.getCompiledClasses();
        Map<String, String> sources = new java.util.HashMap<>();
        List<Path> files = batch.stream().map(ChangeEvent::getPath).toList();
        for (Path file : files) StatusReporter.compile(file.getFileName().toString(), result.getCompileTimeMs());
        for (String compiled : compiledClasses.keySet()) sources.put(compiled, sourceOf(compiled, files));
        if (compiledClasses.isEmpty()) return;

        Runnable apply = () -> {
            try {
                Map<String, byte[]> prepared = prepareCompiledSwitchMaps(compiledClasses, sources);
                int refusedCount = (int) compiledClasses.keySet().stream().filter(name -> !prepared.containsKey(name)).count();
                Runnable reload = () -> {
                    Map<String,String> refusals = compiledSaveRefusals.get();
                    recordCompiledRefusals(refusals, sources);
                    if (!prepared.isEmpty()) applyCompiledClasses(prepared, sources, reloader, springOrchestrator, modules, refusedCount);
                };
                ReloadVerification v = verification;
                if (v == null) reload.run();
                else {
                    Map<String,byte[]> attempted = new java.util.LinkedHashMap<>(compiledClasses); attempted.putAll(prepared);
                    v.apply(attempted, sources, reload);
                }
            } finally { try { SwitchMapRefresh.end(); } finally { closeRecordSave(); compiledSaveRefusals.remove(); } }
        };
        if (agentConfig != null && agentConfig.isRequestBoundary()) RequestReloadBoundary.run(apply);
        else apply.run();
    }

    private static final class FrameworkReload {
        final String className;
        final Class<?> owner;
        final ClassReloader.ReloadResult result;
        final byte[] bytes;
        final com.onurkat.reclazz.transform.KeepBaseline.Input keepInput;
        final boolean wasLoaded;
        final String source;
        final long started;
        final boolean timed;
        final SpringReloadOrchestrator spring;
        final PremiumModules modules;
        boolean success = true;
        String effects;
        boolean frameworkCompleted, frameworkFailed, nativeRepair;

        FrameworkReload(String className, Class<?> owner, ClassReloader.ReloadResult result, byte[] bytes,
                        boolean wasLoaded, String source, long started, boolean timed,
                        SpringReloadOrchestrator spring, PremiumModules modules,
                        com.onurkat.reclazz.transform.KeepBaseline.Input keepInput) {
            this.owner = owner;
            this.keepInput = keepInput;
            this.className = className; this.result = result; this.bytes = bytes;
            this.wasLoaded = wasLoaded; this.source = source; this.started = started;
            this.timed = timed; this.spring = spring; this.modules = modules;
        }
    }

    private static int frameworkBatchDepth;
    private static final java.util.List<FrameworkReload> pendingFramework = new java.util.ArrayList<>();

    private static void beginReloadBatch(SpringReloadOrchestrator spring) {
        if (frameworkBatchDepth++ == 0) pendingFramework.clear();
        ReloadEffects.beginBatch();
        StructuralReloader structural = structuralReloader;
        if (structural != null) structural.beginBatch();
        spring.beginBatch();
    }

    private static void endReloadBatch(SpringReloadOrchestrator spring) {
        java.util.List<FrameworkReload> ready = java.util.List.of();
        try {
            StructuralReloader structural = structuralReloader;
            if (structural != null) structural.endBatch();
            SwitchMapRefresh.complete(structural);
        } finally {
            try {
                if (--frameworkBatchDepth == 0) {
                    ready = java.util.List.copyOf(pendingFramework);
                    pendingFramework.clear();
                    // Every keep decision must see all successful reloads in this save,
                    // even when a child is completed before its changed ancestor.
                    StructuralReloader completedStructural = structuralReloader;
                    for (FrameworkReload work : ready) {
                        Class<?> type = work.owner;
                        if (SwitchMapRefresh.failure(type) == null && (completedStructural == null || completedStructural.nativeOutcome(type)
                                != StructuralReloader.NativeOutcome.FAILED)) {
                            com.onurkat.reclazz.transform.KeepBaseline.advance(type, work.bytes);
                        }
                    }
                    for (FrameworkReload work : ready) completeFramework(work, true);
                }
            } finally {
                try {
                    try { spring.endBatch(); }
                    catch (RuntimeException | Error failure) {
                        NativeRecordSave nativeSave = nativeRecordSave.get();
                        if (failure instanceof ThreadDeath || failure instanceof VirtualMachineError || nativeSave == null
                                || !nativeSave.applied || nativeSave.results.values().stream().noneMatch(ClassReloader.ReloadResult::isSuccess))
                            throw failure;
                        String why = Failures.describe(failure);
                        nativeSave.failure("Spring batch completion", why);
                        for (FrameworkReload work : ready) {
                            work.success = false; work.frameworkCompleted = false;
                            boolean installed = nativeSave.batch.names().contains(work.className);
                            if(installed)nativeFrameworkFailure(work.owner,"Spring batch completion "+work.className,why);
                            recordOutcome(work.className, installed, installed
                                    ? "record shape changes are installed; Spring batch completion did not finish: " + why
                                    : "framework batch completion did not finish: " + why, work.source);
                        }
                    }
                    // Ordinary constructor owners can be recreated only here.
                    // No client may observe RELOAD while those old receivers
                    // still own timers or references.
                    for (FrameworkReload work : ready) {
                        if (work.frameworkCompleted) publishFramework(work);
                    }
                } finally {
                    if (frameworkBatchDepth == 0) {
                        pendingFramework.clear();
                        com.onurkat.reclazz.spring.SpringConstructorSave.endSave();
                        StructuralReloader structural = structuralReloader;
                        if (structural != null) structural.clearNativeOutcomes();
                    }
                    ReloadEffects.endBatch();
                }
            }
        }
    }

    private static void deferOrComplete(FrameworkReload work) {
        if (frameworkBatchDepth > 0) {
            pendingFramework.add(work);
            ReloadEffects.suspend();
        } else completeFramework(work, false);
    }

    private static void completeFramework(FrameworkReload work, boolean deferred) {
        if (deferred) ReloadEffects.begin(work.className);
        try {
            Class<?> type = work.owner;
            StructuralReloader structural = structuralReloader;
            String switchFailure = SwitchMapRefresh.failure(type);
            if (switchFailure != null || (deferred && structural != null
                    && structural.nativeOutcome(type) == StructuralReloader.NativeOutcome.FAILED)) {
                work.success = false;
                String why = switchFailure != null ? switchFailure : "native redefinition failed; framework refresh was not run";
                recordOutcome(work.className, false, why, work.source);
                SessionReport.failed();
                StatusReporter.error("Reload of " + work.className + ": " + why);
                ReloadEvents.failed(work.className, why, work.source);
                return;
            }
            NativeRecordSave nativeSave = nativeRecordSave.get();
            work.nativeRepair=(nativeSave!=null&&nativeSave.batch.names().contains(work.className))||nativeFrameworkPending(type);
            work.frameworkFailed=nativeSave!=null&&nativeSave.aftermathFailures.contains(work.className);
            if (!deferred) com.onurkat.reclazz.transform.KeepBaseline.advance(type, work.bytes);
            if (structural == null || structural.nativeOutcome(type) == StructuralReloader.NativeOutcome.APPLIED) {
                com.onurkat.reclazz.transform.AdmittedBytes.admitted(type, work.bytes);
                recordRefusals.accepted(type, work.bytes);
            } else com.onurkat.reclazz.transform.AdmittedBytes.invalidate(type);
            retryXmlForClass(work.className);
            if (work.wasLoaded) JpaMappingRefresh.retryPendingEntity(work.className, work.bytes);
            var interceptor = work.result.isInterceptor() && work.modules != null
                    && platformContext.getPlatformId() == PlatformContext.Platform.HYBRIS
                    ? work.modules.prepareInterceptor(work.className, platformContext.getAllApplicationContexts()) : null;
            work.spring.setPreviousOperationDescriptors(work.result.getPreviousOperationDescriptors());
            try (com.onurkat.reclazz.spring.ReloadSteps.FailureScope observed=com.onurkat.reclazz.spring.ReloadSteps.observeFailures(work.nativeRepair?reason->{
                work.frameworkFailed=true;
                if(nativeSave!=null)nativeSave.failure("framework refresh "+work.className,reason);
                else nativeFrameworkFailure(type,"framework refresh "+work.className,reason);
            }:null)) {
            boolean ownedConfiguration=nativeSave!=null&&nativeSave.repairs!=null&&nativeSave.repairs.ownsConfiguration(type);
            if(!ownedConfiguration) {
            if (structural == null && work.result.getNewBytecode() == null)
                work.spring.onNativeHierarchyReloaded(type, work.bytes);
            if (work.result.isSpringBean()) {
                work.spring.onClassReloaded(work.className, type, work.result.isStructuralReload(),
                        work.result.isAnnotationsChanged(), work.result.isMethodsAdded(),
                        work.result.getAddedMethodSigs(), work.result.getNewBytecode(), work.keepInput);
            } else {
                work.spring.onHelperReloaded(work.className, type, work.result.isStructuralReload(),
                        work.result.isAnnotationsChanged(), work.result.isMethodsAdded(),
                        work.result.getAddedMethodSigs(), work.result.getNewBytecode());
            }
            }
            } finally { work.spring.setPreviousOperationDescriptors(null); }
            if (interceptor != null && interceptor.complete()) {
                ReloadEffects.note("interceptor re-registered");
                StatusReporter.detail("Interceptor reloaded: " + work.className);
            }
            work.effects = ReloadEffects.end();
            work.frameworkCompleted = !work.frameworkFailed;
            if(work.frameworkFailed){recordOutcome(work.className,true,"native changes are installed; framework refresh did not finish",work.source);return;}
            if (!deferred) publishFramework(work);
        } catch (Throwable t) {
            work.success = false;
            String why = Failures.describe(t);
            NativeRecordSave nativeSave = nativeRecordSave.get();
            if (nativeSave != null && nativeSave.batch.names().contains(work.className))
                nativeSave.failure("framework refresh " + work.className, why);
            else if(work.nativeRepair)nativeFrameworkFailure(work.owner,"framework refresh "+work.className,why);
            StatusReporter.error("Reload of " + work.className + " did not finish: " + why);
            recordOutcome(work.className, true, kindOf(work.result)
                    + "; the framework refresh after it did not finish: " + why, work.source);
        } finally {
            if (!deferred) work.spring.clearUnbatchedHierarchy();
            ReloadEffects.end();
        }
    }

    private static void publishFramework(FrameworkReload work) {
        try {
            Class<?> type=work.owner;
            nativeFrameworkCompleted(type,false);
            NativeRecordSave save=nativeRecordSave.get();
            if(save!=null&&save.batch.names().contains(work.className)&&!save.repairFailures.isEmpty()) {
                String reason="native changes are installed; post-native repairs did not finish: "+String.join(", ",save.repairFailures.keySet());
                StatusReporter.warn(reason);recordOutcome(work.className,true,reason,work.source);return;
            }
            reloadLanded(work.className, work.owner, work.result.isStructuralReload(),
                    work.timed ? System.currentTimeMillis() - work.started : -1,
                    work.result.getShape(), work.source, work.effects);
            nativeFrameworkCompleted(type,true);
        } catch (Throwable t) {
            // Receipt delivery retains the same per-class failure boundary as
            // framework work; one listener cannot suppress sibling receipts.
            work.success = false;
            String why = Failures.describe(t);
            NativeRecordSave nativeSave = nativeRecordSave.get();
            if (nativeSave != null && nativeSave.batch.names().contains(work.className))
                nativeSave.failure("receipt publication " + work.className, why);
            else if(work.nativeRepair)nativeFrameworkFailure(work.owner,"receipt publication "+work.className,why);
            StatusReporter.error("Reload of " + work.className + " did not finish: " + why);
            recordOutcome(work.className, true, kindOf(work.result)
                    + "; the reload receipt did not finish: " + why, work.source);
        }
    }

    private static ClassReloader.ReloadResult reloadWithCacheBoundary(String className, String internalName,
                                                                     byte[] bytecode, ClassReloader reloader, Class<?> owner) {
        NativeRecordSave nativeSave = nativeRecordSave.get();
        if (nativeSave != null && nativeSave.batch.names().contains(className)) {
            if (cacheTrackingInstalled) CacheDependencyLedger.beginMutation();
            try { return nativeSave.apply(className, reloader); }
            finally { if (cacheTrackingInstalled) CacheDependencyLedger.endMutation(); }
        }
        String dependencyFailure = SwitchMapRefresh.dependencyFailure(owner, structuralReloader);
        if (dependencyFailure != null) {
            SwitchMapRefresh.result(owner, false);
            return ClassReloader.ReloadResult.failure(dependencyFailure, true);
        }
        SwitchMapRefresh.mutationStarting(owner);
        com.onurkat.reclazz.transform.AdmittedBytes.invalidate(owner);
        ReloadVerification v = verification;
        if (v != null) v.invalidateUntracked(className);
        if (cacheTrackingInstalled) CacheDependencyLedger.beginMutation();
        try {
            ClassReloader.ReloadResult result = structuralReloader != null && transformContext != null
                    && transformContext.isWatched(internalName) ? structuralReloader.reload(className, bytecode, owner)
                    : reloader.reload(className, bytecode, owner);
            SwitchMapRefresh.result(owner, result.isSuccess());
            if (frameworkBatchDepth == 0) SwitchMapRefresh.complete(structuralReloader);
            return result;
        } catch (RuntimeException | Error failure) {
            SwitchMapRefresh.result(owner, false);
            throw failure;
        } finally {
            if (cacheTrackingInstalled) CacheDependencyLedger.endMutation();
        }
    }

    private static void applyCompiledClasses(Map<String, byte[]> compiledClasses, Map<String, String> sources,
                                             ClassReloader reloader,
                                             SpringReloadOrchestrator springOrchestrator,
                                             PremiumModules modules, int refusedCount) {

        long startTime = System.currentTimeMillis();
        int successCount = 0;
        int failCount = refusedCount;
        java.util.List<FrameworkReload> frameworkResults = new java.util.ArrayList<>();

        // Dependent cascade + stale-reference healing sweep every singleton
        // in every context, so run them once for the whole batch; the JVM
        // redefinitions likewise go in one call at the end.
        beginReloadBatch(springOrchestrator);
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
            Class<?> owner;
            boolean wasLoaded;
            try {
                owner = findLoadedClass(className);
                wasLoaded = owner != null;
                if (!nativeCohortMember(className)) chaseChangedConstants(internalName, className, bytecode);
                if (!wasLoaded) {
                    var registration = springOrchestrator.registerNewBeanClass(className, bytecode);
                    if (registration.isHandled()) {
                        if (registration.isSuccess()) retryXmlForClass(className);
                        recordOutcome(className, registration.isSuccess(), registration.description(), sources.get(className));
                        if (registration.isSuccess()) successCount++;
                        else failCount++;
                        continue;
                    }
                    JpaMappingRefresh.maybeMapNewEntity(className, bytecode, platformContext.getAllApplicationContexts());
                    owner = findLoadedClass(className);
                }
            } catch (IllegalStateException unavailable) {
                String reason = Failures.describe(unavailable);
                StatusReporter.error(reason);
                recordOutcome(className, false, reason, sources.get(className));
                ReloadEvents.failed(className, reason, sources.get(className));
                failCount++;
                continue;
            }

            var keepInput = new com.onurkat.reclazz.transform.KeepBaseline.Input(
                    com.onurkat.reclazz.transform.KeepBaseline.get(owner), bytecode);
            ClassReloader.ReloadResult reloadResult;
            ReloadEffects.begin(className);
            try {
                reloadResult = reloadWithCacheBoundary(className, internalName, bytecode, reloader, owner);
            } catch (Throwable t) {
                reloadResult = ClassReloader.ReloadResult.failure(
                        "Unexpected " + t.getClass().getSimpleName() + ": " + t.getMessage(), false);
            }

            recordOutcome(className, reloadResult.isSuccess(),
                    reloadResult.isSuccess() ? kindOf(reloadResult) : reloadResult.getError(),
                    sources.get(className));

            if (reloadResult.isSuccess()) {
                if (nativeCohortMember(className)) chaseChangedConstants(internalName, className, bytecode);
                successCount++;
                FrameworkReload work = new FrameworkReload(className, owner, reloadResult, bytecode, wasLoaded,
                        sources.get(className), startTime, compiledClasses.size() == 1, springOrchestrator, modules, keepInput);
                frameworkResults.add(work);
                deferOrComplete(work);
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
            endReloadBatch(springOrchestrator);
        }

        for (FrameworkReload work : frameworkResults) {
            if (!work.success) { successCount--; failCount++; }
        }
        long elapsed = System.currentTimeMillis() - startTime;

        if (compiledClasses.size() > 1) {
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
        String resource = platformContext instanceof SpringBootContext boot ? boot.runtimeResourceName(event.getPath()) : null;
        int cleared = templateReloader.reload(fileName, resource);
        if (cleared == 0 && config != null && config.isVerbose()) {
            StatusReporter.info("Template changed: " + fileName
                    + " (no template engine registered, nothing to clear)");
        }
    }

    private static void handleSpringXmlChange(ChangeEvent event) {
        StatusReporter.info("Spring XML changed: " + event.getPath().getFileName());
        SpringXmlReloader reloader = springXmlReloader;
        if (reloader == null) {
            StatusReporter.warn("Spring XML reload not available, agent not fully initialised");
            return;
        }
        if (ChangeKind.of(event.getPath().getFileName().toString()) == ChangeKind.UNKNOWN
                || "spring-xml".equals(event.getSourceRoot())
                || com.onurkat.reclazz.platform.SpringXmlResources.isLoaded(event.getPath()))
            reloader.reloadLoaded(event.getPath());
        else
            reloader.reload(event.getPath());
    }

    /** Called by the watcher at startup; see PropertyFileSnapshots. */
    public static void baselinePropertyFile(java.nio.file.Path file) {
        propertySnapshots.baseline(file);
    }

    private static void handleSpringPropertiesChange(ChangeEvent event) {
        Path path = event.getPath();
        String name = path.getFileName().toString();
        var logging = LoggingReloader.Log4j2Outcome.NOT_OWNED;
        if (name.endsWith(".properties") && (name.startsWith("log4j2.") || name.startsWith("log4j2-"))) {
            logging = new LoggingReloader(instrumentation).reconfigureActiveLog4j2(path);
            if (logging == LoggingReloader.Log4j2Outcome.APPLIED) {
                StatusReporter.success(name + " applied to the running Log4j2 context");
            } else if (logging == LoggingReloader.Log4j2Outcome.REJECTED) {
                StatusReporter.warn(name + " was not applied: Log4j2 did not confirm the new configuration. Check the configuration and save again.");
            }
        }
        java.util.Set<Object> owners = name.endsWith(".properties")
                ? new SpringMessageSourceReloader(platformContext).ownersOf(path) : java.util.Set.of();
        if (owners.isEmpty() && logging == LoggingReloader.Log4j2Outcome.NOT_OWNED) {
            handlePropertiesChange(event);
            return;
        }
        if (springPropertyFiles.mayOwn(path, platformContext.getAllApplicationContexts())) handlePropertiesChange(event);
        if (!owners.isEmpty() && prepareLocalizationChange(event)) {
            SpringMessageSourceReloader.report(name, SpringMessageSourceReloader.reloadOwners(owners));
        }
    }

    private static void handlePropertiesChange(ChangeEvent event) {
        String fileName = event.getPath().getFileName().toString();

        StatusReporter.info("Config file changed: " + fileName);
        var candidate = fileName.endsWith(".properties") ? propertySnapshots.pending(event.getPath()) : null;
        SpringBootContext boot = platformContext instanceof SpringBootContext ctx ? ctx : null;
        boolean sourceSave = boot != null && boot.sourceResource(event.getPath());
        var fileResult = springPropertyFiles.apply(event.getPath(), platformContext::getAllApplicationContexts,
                agentConfig != null && agentConfig.isRequestBoundary() ? RequestReloadBoundary::run : Runnable::run, sourceSave);
        if (sourceSave && fileResult != null && fileResult.outcome().state()
                == com.onurkat.reclazz.spring.PropertyChangeOutcome.State.NOT_RUN) {
            boot.sourceResourceSaved(event.getPath());
            return;
        }
        if (fileResult != null) {
            if (fileResult.outcome().state() == com.onurkat.reclazz.spring.PropertyChangeOutcome.State.APPLIED) {
                StatusReporter.success("Applied " + Plural.of(fileResult.changed().size(), "property change")
                        + " from " + fileName + ": " + fileResult.changed());
                for (var owner : fileResult.loggerOwners()) {
                    var pending=new java.util.LinkedHashSet<String>();
                    pending.addAll(owner.changedKeys());pending.addAll(owner.removedKeys());pending.removeIf(key->!LoggingReloader.isLevelKey(key));
                    if(owner.metadataFailure()!=null) {
                        keepLoggerResetPending(owner,pending,"logger-group-metadata","logger group metadata could not be verified");
                        continue;
                    }
                    try {
                        // Merge the whole save once: mixed spellings retain their precedence and alias guards.
                        var properties=new java.util.Properties();properties.putAll(owner.effectiveLoggingValues());
                        var desired=LoggingReloader.levelsIn(properties,owner.changedKeys());
                        var setFailures=new java.util.ArrayList<String>();
                        var reloader=new LoggingReloader(instrumentation,setFailures::add);
                        boolean unsupported=!desired.isEmpty() && !reloader.frameworkPresent();
                        var applied=unsupported?java.util.List.<String>of():reloader.applyPropertyLevels(properties,owner.changedKeys());
                        for(String key:owner.changedKeys())if(LoggingReloader.isLevelKey(key)
                                && (unsupported || applied.containsAll(LoggingReloader.levelsIn(properties,java.util.Set.of(key)).keySet())))pending.remove(key);
                        var attemptedSetKeys=new java.util.LinkedHashSet<>(owner.changedKeys());attemptedSetKeys.removeIf(key->!LoggingReloader.isLevelKey(key));
                        var unappliedSetKeys=new java.util.LinkedHashSet<String>();
                        for(String key:attemptedSetKeys) {
                            var names=LoggingReloader.levelsIn(properties,java.util.Set.of(key)).keySet();
                            if(!names.isEmpty() && (unsupported || !applied.containsAll(names)))unappliedSetKeys.add(key);
                        }
                        owner.acceptance().setConcern(attemptedSetKeys,unappliedSetKeys);
                        if(unsupported) {
                            owner.acceptance().warn("logger-set-unsupported","Logger level changes not applied: no supported loaded Logback/Log4j2 backend (JUL is unsupported); restart with a supported backend: "+desired.keySet());
                        } else if(!applied.containsAll(desired.keySet())) {
                            var failedNames=new java.util.TreeSet<>(desired.keySet());failedNames.removeAll(applied);
                            owner.acceptance().warn("logger-set-pending:"+failedNames,"Logger level SET remains pending for "+failedNames+": "+(setFailures.isEmpty()?"backend did not verify the requested levels":String.join("; ",setFailures)));
                        }
                        if(!applied.isEmpty())StatusReporter.success(Plural.word(applied.size(),"Logger level applied: ","Logger levels applied: ")+applied);
                        var resetKeys=LoggingReloader.resetKeys(owner.previousLoggingValues(),owner.removedKeys(),owner.changedKeys(),owner.effectiveLoggingValues());
                        ClassLoader loader=(ClassLoader)owner.applicationContext().getClass().getMethod("getClassLoader").invoke(owner.applicationContext());
                        var resets=new LoggingReloader(instrumentation).resetLevelsResult(loader,owner.previousLoggingValues(),resetKeys,owner.effectiveLoggingValues(),owner.acceptance()::warn);
                        var settled=new java.util.HashSet<>(resets.applied());settled.addAll(resets.restartOnly());
                        for(String key:resetKeys) {
                            if(settled.containsAll(LoggingReloader.resetLoggerNames(owner.previousLoggingValues(),java.util.Set.of(key))))pending.remove(key);
                            else pending.add(key);
                        }
                        // Missing baseline already has its warning and ledger; acknowledge it, do not retry forever.
                        if(pending.isEmpty())owner.acceptance().resolved();
                        else owner.acceptance().retry(pending);
                    } catch(Exception failure) {
                        keepLoggerResetPending(owner,pending,"logger-update-failure","logger update failed: "+Failures.describe(failure));
                    }
                }
            }
            return;
        }
        if (candidate == null) return;
        var previous=propertySnapshots.previous(candidate);
        var removed=propertySnapshots.removed(candidate);
        var resetKeys=LoggingReloader.resetKeys(previous,removed,candidate.changed().keySet(),candidate.content());
        var otherRemoved=new java.util.TreeSet<>(removed);otherRemoved.removeIf(LoggingReloader::isLevelKey);
        if(!otherRemoved.isEmpty()) {
            StatusReporter.warn("Removed from "+fileName+": "+otherRemoved+". The running configuration keeps its value until a restart.");
            RestartLedger.note(fileName,"removed configuration keys still have their running values: "+otherRemoved);
            propertySnapshots.acceptKeys(candidate,otherRemoved);
        }
        if (candidate.changed().isEmpty() && resetKeys.isEmpty()) {
            propertySnapshots.accept(candidate);
            StatusReporter.info("No property values changed.");
            return;
        }

        java.util.Map<String, String> changed = candidate.changed();
        int[] levelsApplied = {0};
        var rebinder=new SpringPropertyRebinder(platformContext.getAllApplicationContexts());
        var applied = rebinder.apply(changed,
                        agentConfig != null && agentConfig.isRequestBoundary()
                                ? RequestReloadBoundary::run : Runnable::run,
                        () -> {
                            levelsApplied[0] = applyLoggerLevels(candidate.content(), changed.keySet());
                            var effective=new java.util.LinkedHashMap<>(candidate.content());
                            effective.putAll(rebinder.loggerFallbacks(removed.stream().filter(LoggingReloader::isLevelKey).toList()));
                            int resets=resetLoggerLevels(previous,resetKeys,effective);
                            levelsApplied[0]+=resets;
                            if(resets==LoggingReloader.resetLoggerNames(previous,resetKeys).size()) {
                                rebinder.forgetLoggerOverrides(removed.stream().filter(LoggingReloader::isLevelKey).toList());
                                propertySnapshots.accept(candidate);
                            }
                            else propertySnapshots.acceptKeys(candidate,changed.keySet().stream().filter(key -> !resetKeys.contains(key)).toList());
                        });
        if (applied.state() != com.onurkat.reclazz.spring.PropertyChangeOutcome.State.APPLIED) return;
        if(!changed.isEmpty())StatusReporter.success("Applied " + Plural.of(changed.size(), "property change") + ": " + changed.keySet());
        else if(levelsApplied[0]<LoggingReloader.resetLoggerNames(previous,resetKeys).size())
            StatusReporter.info("Logger reset remains pending for "+LoggingReloader.resetLoggerNames(previous,resetKeys));
        java.util.List<String> rebound = applied.rebound();
        if (!rebound.isEmpty()) {
            StatusReporter.success("Rebound " + Plural.of(rebound.size(), "@ConfigurationProperties bean")
                    + ": " + (rebound.size() > 5 ? rebound.subList(0, 5) + " …" : rebound.toString()));
        }
        if (!rebound.isEmpty() || applied.valueFields() > 0 || !applied.rebuilt().isEmpty()) return;
        if (levelsApplied[0] > 0 && changed.keySet().stream().allMatch(ReclazzAgent::isLoggingKey)) return;
        if(!changed.isEmpty())reportUnconsumedProperties(changed);
    }

    private static void keepLoggerResetPending(com.onurkat.reclazz.spring.SpringPropertyFiles.LoggerOwner owner,java.util.Set<String> keys,String concern,String reason) {
        try {owner.acceptance().retry(keys);}
        catch(Exception failure) {
            owner.acceptance().warn("logger-ownership-changed","Logger reset remains pending; newer file ownership was kept: "+Failures.describe(failure));
            return;
        }
        owner.acceptance().warn(concern,"Logger reset remains pending: "+reason);
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
        java.util.List<String> applied = reloader.applyPropertyLevels(fromFile, changedKeys);
        if (!applied.isEmpty()) {
            StatusReporter.success(Plural.word(applied.size(),
                    "Logger level applied: ", "Logger levels applied: ")
                    + (applied.size() > 5 ? applied.subList(0, 5) + " …" : applied.toString()));
        }
        return applied.size();
    }

    private static int resetLoggerLevels(Map<String,String> previous, java.util.Collection<String> keys, Map<String,String> effective) {
        return new LoggingReloader(instrumentation).resetLevels(null, previous, keys, effective).size();
    }

    private static boolean isLoggingKey(String key) { return LoggingReloader.isLevelKey(key); }

    private static boolean prepareLocalizationChange(ChangeEvent event) {
        String fileName = event.getPath().getFileName().toString();

        // Clearing either cache is instant and the next reader pays for the
        // rebuild: 830ms for the platform's localization cache and just under
        // four seconds for ZK's, measured on a 2211 server. A save that changed
        // no text must not buy that, and those are common: the platform's build
        // re-copies resource files, so one ant build touches every localization
        // file in every extension.
        if (!localizationGuard.changed(event.getPath())) {
            StatusReporter.info("Localization file saved with no text changes: " + fileName);
            return false;
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

        return true;
    }

    private static void handleLocalizationChange(ChangeEvent event) {
        String fileName = event.getPath().getFileName().toString();
        SpringMessageSourceReloader.report(fileName,
                new SpringMessageSourceReloader(platformContext).reload());
    }



    /** Presence alone is sufficient for constant-dependency admission. */
    private static boolean alreadyLoaded(String className) {
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (className.equals(loaded.getName())) return true;
        }
        return false;
    }

    private static Class<?> findLoadedClass(String className) {
        try {
            TransformContext context = transformContext;
            return ClassLookup.findLoadedClass(className, instrumentation,
                    context == null ? null : context::recordedLoaders);
        } catch (ClassLookup.AmbiguousDefinition ambiguity) {
            RestartLedger.note(className, ambiguity.getMessage());
            throw ambiguity;
        }
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
            StatusReporter.warn("  GraalVM detected, class redefinition support is limited.");
            StatusReporter.warn("  Some method body changes may fail. Consider using a standard OpenJDK or JBR instead.");
        }
    }
}
