/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ext;

import com.onurkat.reclazz.agent.ReclazzAgent;
import com.onurkat.reclazz.compiler.IncrementalCompiler;
import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.HybrisPlatformContext;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.reload.StructuralReloader;
import com.onurkat.reclazz.transform.*;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.watcher.ChangeEvent;
import com.onurkat.reclazz.watcher.FileWatcher;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import static org.junit.jupiter.api.Assertions.*;

/** Real free startup/reload and optional automation dispatch, without any SAP binaries. */
class PremiumModuleOptionalTest extends TransformTestBase {
    @TempDir Path tmp;

    @Test void phaseOneAdmitsEveryCapability() {
        for (Capability capability : Capability.values()) assertTrue(new AlwaysAdmit().request(capability).granted());
    }

    @ParameterizedTest @ValueSource(strings = {"empty", "failed", "incompatible"})
    void freeSapAgentStartsAndReloadsWithNoRegisteredAutomation(String registration) throws Exception {
        runAgent(registration);
    }

    @Test void shippedModulePreservesTheSameFreeSapStartupAndReload() throws Exception {
        runAgent("present");
    }

    private void runAgent(String registration) throws Exception {
        HybrisPlatformContext platform = platform();
        Path model = tmp.resolve("bin/platform/bootstrap/modelclasses/com/example/ManualModel.class");
        Files.createDirectories(model.getParent());
        Files.write(model, compile(new SourceFile("com.example.ManualModel", model("before", ""))).get("com/example/ManualModel"));
        Path saved = tmp.resolve("saved-model.bin");
        Files.write(saved, compile(new SourceFile("com.example.ManualModel", model("after", "public int added() { return 21; }"))).get("com/example/ManualModel"));
        assertEquals("com.example.ManualModel", platform.resolveClassName(model));
        Path log = tmp.resolve("child.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Djdk.attach.allowAttachSelf=true", "-cp", System.getProperty("java.class.path"),
                EmptyAgent.class.getName(), tmp.toString(), model.toString(), saved.toString(), registration)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), () -> read(log));
            assertEquals(0, process.exitValue(), () -> read(log));
            assertTrue(read(log).contains("FREE_STARTUP_RELOAD_OK"), () -> read(log));
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
    }

    /** The actual agent startup with an explicitly empty registry, isolated from the test JVM. */
    public static class EmptyAgent {
        public static void main(String[] args) throws Exception {
            PremiumModules modules = new PremiumModules(new AlwaysAdmit());
            if (args[3].equals("failed") || args[3].equals("incompatible")) {
                boolean registered = modules.register(() -> {
                    if (args[3].equals("failed")) throw new IllegalStateException("synthetic initialization failure");
                    return new PremiumModule() {
                        public int contractVersion() { return PremiumModules.CONTRACT_VERSION + 1; }
                        public Set<Capability> capabilities() { return Set.of(Capability.CODEGEN); }
                    };
                });
                if (registered) throw new AssertionError("unavailable module registered");
            }
            for (Capability capability : Capability.values()) if (modules.has(capability)) throw new AssertionError(capability);
            Field instrumentation = ReclazzAgent.class.getDeclaredField("instrumentation");
            instrumentation.setAccessible(true); instrumentation.set(null, ByteBuddyAgent.install());
            var watching = new CountDownLatch(1);
            StatusReporter.addListener((level, message) -> { if (message.startsWith("Watching ")) watching.countDown(); });
            Method initialize = ReclazzAgent.class.getDeclaredMethod("initialize", String.class, PremiumModules.class);
            initialize.setAccessible(true);
            initialize.invoke(null, "platform=hybris,hybrisHome=" + args[0] + ",startupDelaySec=0,debounceMs=100",
                    args[3].equals("present") ? null : modules);
            if (args[3].equals("present")) {
                Field registry = ReclazzAgent.class.getDeclaredField("premiumModules"); registry.setAccessible(true);
                var shipped = (PremiumModules) registry.get(null);
                for (Capability capability : Capability.values()) {
                    if (shipped.has(capability) != (capability != Capability.SAP_MAPPER))
                        throw new AssertionError("shipped automation wiring: " + capability);
                }
            }
            if (!watching.await(20, TimeUnit.SECONDS)) throw new AssertionError("free watcher did not start");
            Path output = Path.of(args[0], "bin/platform/bootstrap/modelclasses");
            try (var loader = new java.net.URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, EmptyAgent.class.getClassLoader())) {
                Class<?> type = loader.loadClass("com.example.ManualModel");
                Object held = type.getConstructor().newInstance();
                Method run = type.getMethod("run");
                if (!run.invoke(held).equals("before")) throw new AssertionError("initial model");
                Files.write(Path.of(args[1]), Files.readAllBytes(Path.of(args[2])));
                // Body retargeting can precede reflection publication. Observe both expected results.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                Method added = null;
                while (System.nanoTime() < deadline) {
                    try { added = type.getMethod("added"); }
                    catch (NoSuchMethodException notPublishedYet) { }
                    if (added != null && run.invoke(held).equals("after")) break;
                    Thread.sleep(50);
                }
                if (!run.invoke(held).equals("after")) throw new AssertionError("held model did not reload");
                if (added == null || !added.invoke(held).equals(21)) throw new AssertionError("manual model addition");
                System.out.println("FREE_STARTUP_RELOAD_OK");
            }
        }
    }

    @Test void emptyRegistryKeepsDependencyOrderAndWebOutputCompilation() throws Exception {
        HybrisPlatformContext platform = platform();
        Path core = source("core", "src", "Core", "public int value() { return 7; }");
        Path web = source("web", "web/src", "Web", "public int value() { return new Core().value(); }");
        var compiler = new IncrementalCompiler(platform.resolveClasspath(), platform.getHybrisContext());
        Map<String, List<Path>> work = new LinkedHashMap<>();
        work.put("web", List.of(web)); work.put("core", List.of(core));
        var result = compiler.compilePackage(work);
        assertTrue(result.isSuccess(), result.getErrors().toString());
        assertTrue(Files.isRegularFile(tmp.resolve("bin/custom/core/classes/com/example/Core.class")));
        assertTrue(Files.isRegularFile(tmp.resolve("bin/custom/web/web/webroot/WEB-INF/classes/com/example/Web.class")));
    }

    @Test void emptyRegistryAgentDispatchReportsBackofficeAdvice() throws Exception {
        var modules = new PremiumModules(new AlwaysAdmit());
        HybrisPlatformContext platform = platform();
        var watcher = new FileWatcher(platform, AgentConfig.parse(null));
        watcher.onPlatformChange(modules);
        Field field = ReclazzAgent.class.getDeclaredField("platformContext"); field.setAccessible(true);
        Object previous = field.get(null); field.set(null, platform);
        RestartLedger.clear();
        try {
            Method dispatch = Arrays.stream(ReclazzAgent.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals("handleChange")).findFirst().orElseThrow();
            dispatch.setAccessible(true);
            BiFunction<Capability, ChangeEvent, PlatformChangeHandler.Outcome> changes = watcher::dispatchPlatformChange;
            dispatch.invoke(null, new ChangeEvent(tmp.resolve("example-backoffice-config.xml"), ChangeEvent.Type.MODIFIED,
                    "example", "resources"), null, null, null, modules, changes, AgentConfig.parse(null));
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains("Backoffice configuration was not applied")),
                    RestartLedger.digest().toString());
        } finally { field.set(null, previous); watcher.stopWatching(); RestartLedger.clear(); }
    }

    @Test void emptyRegistryStillRefreshesGenericResourceBundles() throws Exception {
        Path resources = Files.createDirectories(tmp.resolve("i18n"));
        Path file = resources.resolve("example-locales_en.properties");
        Files.writeString(file, "label=before\n");
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{resources.toUri().toURL()}, previous)) {
            Thread.currentThread().setContextClassLoader(loader);
            assertEquals("before", ResourceBundle.getBundle("example-locales", Locale.ENGLISH, loader).getString("label"));
            Files.writeString(file, "label=after\n");
            dispatchEmpty(file);
            assertEquals("after", ResourceBundle.getBundle("example-locales", Locale.ENGLISH, loader).getString("label"),
                    "the generic JDK bundle cache remains free inside SAP");
            ResourceBundle.clearCache(loader);
        } finally { Thread.currentThread().setContextClassLoader(previous); RestartLedger.clear(); }
    }

    @Test void emptyRegistryDoesNotInventARestartForUnrelatedProperties() throws Exception {
        Path file = tmp.resolve("library.properties"); Files.writeString(file, "example.value=one\n");
        var messages = new ArrayList<String>();
        StatusReporter.StatusListener listener = (level, message) -> messages.add(message);
        StatusReporter.addListener(listener); RestartLedger.clear();
        try {
            dispatchEmpty(file);
            assertEquals(0, RestartLedger.size(), "free classification must happen before optional platform apply");
            assertTrue(messages.stream().anyMatch(m -> m.contains("not platform configuration")), messages.toString());
        } finally { StatusReporter.removeListener(listener); RestartLedger.clear(); }
    }

    private void dispatchEmpty(Path file) throws Exception {
        var modules = new PremiumModules(new AlwaysAdmit());
        HybrisPlatformContext platform = platform();
        var watcher = new FileWatcher(platform, AgentConfig.parse(null)); watcher.onPlatformChange(modules);
        Field field = ReclazzAgent.class.getDeclaredField("platformContext"); field.setAccessible(true);
        Object previous = field.get(null); field.set(null, platform);
        try {
            Method dispatch = Arrays.stream(ReclazzAgent.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals("handleChange")).findFirst().orElseThrow();
            dispatch.setAccessible(true);
            BiFunction<Capability, ChangeEvent, PlatformChangeHandler.Outcome> changes = watcher::dispatchPlatformChange;
            dispatch.invoke(null, new ChangeEvent(file, ChangeEvent.Type.MODIFIED, "example", "resources"),
                    null, null, null, modules, changes, AgentConfig.parse(null));
        } finally { field.set(null, previous); watcher.stopWatching(); }
    }

    @Test void presentModuleGetsTheHookAfterTheHeldObjectChanges() throws Exception {
        String name = "OptionalWidget";
        var context = new TransformContext(); context.addWatched(name);
        var config = AgentConfig.parse(null); var transformer = new ReclazzTransformer(context, config);
        var original = compile(new SourceFile(name, widget("before", "")), annotation());
        original.put(name, transformer.transform(getClass().getClassLoader(), name, null, null, original.get(name)));
        Class<?> type = defineAndLoad(original, name); Object held = type.getConstructor().newInstance();
        byte[] saved = compile(new SourceFile(name, widget("after", "@com.hybris.cockpitng.annotations.SocketEvent public void input() {}")), annotation()).get(name);
        var calls = new AtomicInteger(); var admissions = new ArrayList<Capability>();
        var modules = new PremiumModules(c -> { admissions.add(c); return new PremiumAdmission.Admission(true, ""); });
        assertTrue(modules.register(() -> new PremiumModule() {
            public Set<Capability> capabilities() { return Set.of(Capability.BACKOFFICE_RELOAD); }
            public StructuralReloadHook structuralReloadHook() { return reloaded -> {
                assertSame(type, reloaded);
                try { assertEquals("after", type.getMethod("run").invoke(held)); }
                catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                calls.incrementAndGet();
            }; }
        }));
        var inst = ByteBuddyAgent.install();
        var reloader = new StructuralReloader(inst, context, config, platform(), modules); reloader.setTransformer(transformer);
        RestartLedger.clear(); inst.addTransformer(transformer, true);
        try {
            var result = reloader.reload(name, saved);
            assertTrue(result.isSuccess(), result.getError());
            assertEquals(1, calls.get());
            assertEquals(List.of(Capability.BACKOFFICE_RELOAD), admissions);
            assertEquals(0, RestartLedger.size());
        } finally { inst.removeTransformer(transformer); RestartLedger.clear(); }
    }

    @Test void aPartlyConstructedModulePublishesNoHandlers() {
        var calls = new AtomicInteger(); var modules = new PremiumModules(new AlwaysAdmit());
        assertFalse(modules.register(() -> new PremiumModule() {
            public Set<Capability> capabilities() { return Set.of(Capability.CONFIG_APPLY, Capability.IMPEX_IMPORT); }
            public Map<Capability, PlatformChangeHandler> changeHandlers() {
                throw new IllegalStateException("synthetic handler setup failure");
            }
            public StructuralReloadHook structuralReloadHook() { return type -> calls.incrementAndGet(); }
        }));
        for (Capability capability : Capability.values()) assertFalse(modules.has(capability));
        assertEquals(0, calls.get());
    }

    @Test void initializerAssertionLeavesTheFreeRegistryUsable() {
        var modules = new PremiumModules(new AlwaysAdmit());
        assertFalse(modules.register(() -> { throw new AssertionError("synthetic initializer assertion"); }));
        for (Capability capability : Capability.values()) assertFalse(modules.has(capability));
        RestartLedger.clear();
        try {
            assertEquals(PlatformChangeHandler.Outcome.ADVICE, modules.handle(new PlatformChangeHandler.Change(
                    Capability.CODEGEN, tmp.resolve("example-items.xml"), "MODIFIED", "example", "resources")));
            assertEquals(1, RestartLedger.size());
        } finally { RestartLedger.clear(); }
    }

    @Test void agentRoutesClassifiedSapChangesThroughAdmissionOnTheCallingThread() throws Exception {
        var seen = new ArrayList<PlatformChangeHandler.Change>();
        var admitted = new ArrayList<Capability>();
        Thread dispatchThread = Thread.currentThread();
        var modules = new PremiumModules(c -> { admitted.add(c); return new PremiumAdmission.Admission(true, ""); });
        Set<Capability> capabilities = Set.of(Capability.CODEGEN, Capability.CONFIG_APPLY,
                Capability.IMPEX_IMPORT, Capability.LOCALIZATION_RELOAD, Capability.BACKOFFICE_RELOAD);
        assertTrue(modules.register(() -> new PremiumModule() {
            public Set<Capability> capabilities() { return capabilities; }
            public Map<Capability, PlatformChangeHandler> changeHandlers() {
                var handlers = new EnumMap<Capability, PlatformChangeHandler>(Capability.class);
                for (Capability capability : capabilities) handlers.put(capability, change -> {
                    assertSame(dispatchThread, Thread.currentThread(), "dispatch must not create a new execution lane");
                    seen.add(change); return PlatformChangeHandler.Outcome.HANDLED;
                });
                return handlers;
            }
        }));
        HybrisPlatformContext platform = platform();
        var watcher = new FileWatcher(platform, AgentConfig.parse(null)); watcher.onPlatformChange(modules);
        Field field = ReclazzAgent.class.getDeclaredField("platformContext"); field.setAccessible(true);
        Object previous = field.get(null); field.set(null, platform);
        try {
            Method dispatch = Arrays.stream(ReclazzAgent.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals("handleChange")).findFirst().orElseThrow();
            dispatch.setAccessible(true);
            BiFunction<Capability, ChangeEvent, PlatformChangeHandler.Outcome> changes = watcher::dispatchPlatformChange;
            List<String> files = List.of("example-items.xml", "local.properties", "data.impex",
                    "localization/example-locales_en.properties", "example-backoffice-config.xml");
            for (String file : files) {
                dispatch.invoke(null, new ChangeEvent(tmp.resolve(file), ChangeEvent.Type.MODIFIED, "example", "resources"),
                        null, null, null, modules, changes, AgentConfig.parse("autoImpex=true"));
            }
            assertEquals(List.of(Capability.CODEGEN, Capability.CONFIG_APPLY, Capability.IMPEX_IMPORT,
                    Capability.LOCALIZATION_RELOAD, Capability.BACKOFFICE_RELOAD), admitted);
            assertEquals(admitted, seen.stream().map(PlatformChangeHandler.Change::capability).toList());
            assertEquals(files.stream().map(tmp::resolve).toList(), seen.stream().map(PlatformChangeHandler.Change::path).toList());
            assertTrue(seen.stream().allMatch(c -> c.eventType().equals("MODIFIED")
                    && c.moduleName().equals("example") && c.sourceRoot().equals("resources")));
        } finally { field.set(null, previous); watcher.stopWatching(); }
    }

    @Test void interceptorPreparationAndCompletionShareOneAdmission() {
        List<String> order = new ArrayList<>();
        var modules = new PremiumModules(c -> { order.add("admit:" + c); return new PremiumAdmission.Admission(true, ""); });
        assertTrue(modules.register(() -> new PremiumModule() {
            public Set<Capability> capabilities() { return Set.of(Capability.INTERCEPTOR_REFRESH); }
            public PreparedRefresh prepareInterceptor(String name, List<Object> contexts) {
                order.add("prepare"); return () -> { order.add("complete"); return true; };
            }
        }));
        var prepared = modules.prepareInterceptor("com.example.Interceptor", List.of());
        order.add("spring-cascade"); assertTrue(prepared.complete());
        assertEquals(List.of("admit:INTERCEPTOR_REFRESH", "prepare", "spring-cascade", "complete"), order);
    }

    private HybrisPlatformContext platform() throws Exception {
        Files.createDirectories(tmp.resolve("bin/platform/bootstrap/modelclasses"));
        Files.createDirectories(tmp.resolve("config"));
        for (String name : List.of("core", "web")) {
            Path ext = Files.createDirectories(tmp.resolve("bin/custom/" + name));
            Files.createDirectories(ext.resolve("classes"));
            Files.writeString(ext.resolve("extensioninfo.xml"), "<extensioninfo><extension name='" + name + "'>"
                    + (name.equals("web") ? "<requires-extension name='core'/>" : "") + "</extension></extensioninfo>");
        }
        Files.writeString(tmp.resolve("config/localextensions.xml"), "<hybrisconfig><extensions><extension name='core'/><extension name='web'/></extensions></hybrisconfig>");
        var platform = new HybrisPlatformContext(tmp, AgentConfig.parse(null)); platform.initialize(); return platform;
    }

    private Path source(String module, String group, String name, String body) throws Exception {
        Path file = tmp.resolve("bin/custom/" + module + "/" + group + "/com/example/" + name + ".java");
        Files.createDirectories(file.getParent()); Files.writeString(file, "package com.example; public class " + name + " { " + body + " }");
        return file;
    }
    private static String model(String value, String added) { return "package com.example; public class ManualModel { public String run() { return \"" + value + "\"; } " + added + " }"; }
    private static String widget(String value, String added) { return "public class OptionalWidget { public String run() { return \"" + value + "\"; } " + added + " }"; }
    private static SourceFile annotation() {
        return new SourceFile("com.hybris.cockpitng.annotations.SocketEvent", "package com.hybris.cockpitng.annotations; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface SocketEvent {}");
    }
    private static String read(Path log) {
        try { return Files.readString(log); } catch (Exception failure) { return failure.toString(); }
    }
}
