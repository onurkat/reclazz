/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import com.onurkat.reclazz.agent.ReclazzAgent;
import com.onurkat.reclazz.compiler.IncrementalCompiler;
import com.onurkat.reclazz.ext.*;
import com.onurkat.reclazz.platform.HybrisPlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.tools.ToolProvider;

/** Compiled against, and launched with, only the selected agent jar. */
public class PhysicalAgentProbe {
    private static final List<String> messages = new CopyOnWriteArrayList<>();
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); boolean core = args[1].equals("core");
        StatusReporter.addListener((level, message) -> messages.add(message));
        // Startup may already have printed Watching before main; the parent synchronizes
        // by an acknowledgement file after observing the real watcher-ready output.
        System.out.println("PROBE_MAIN_READY"); System.out.flush();
        await(() -> Files.exists(root.resolve("watcher-ready")), "parent watcher acknowledgement");
        var field = ReclazzAgent.class.getDeclaredField("premiumModules"); field.setAccessible(true);
        var modules = (PremiumModules) field.get(null);
        for (Capability capability : Capability.values()) {
            boolean expected = !core && capability != Capability.SAP_MAPPER;
            require(modules.has(capability) == expected, "physical module wiring " + capability);
        }
        var pf = ReclazzAgent.class.getDeclaredField("platformContext"); pf.setAccessible(true);
        var platform = (HybrisPlatformContext) pf.get(null);
        require(platform.getHybrisContext().getExtensions().keySet().containsAll(List.of("core", "web")), "SAP discovery");
        var compiler = new IncrementalCompiler(platform.resolveClasspath(), platform.getHybrisContext());
        Map<String, List<Path>> work = new LinkedHashMap<>();
        work.put("web", List.of(root.resolve("bin/custom/web/web/src/com/example/Web.java")));
        work.put("core", List.of(root.resolve("bin/custom/core/src/com/example/Core.java")));
        var result = compiler.compilePackage(work);
        require(result.isSuccess(), "dependency compile " + result.getErrors());
        Path coreOutput = root.resolve("bin/custom/core/classes");
        Path webOutput = root.resolve("bin/custom/web/web/webroot/WEB-INF/classes");
        Path modelOutput = root.resolve("bin/platform/bootstrap/modelclasses");
        require(Files.isRegularFile(webOutput.resolve("com/example/Web.class")), "web output");
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{coreOutput.toUri().toURL(),
                webOutput.toUri().toURL(), modelOutput.toUri().toURL()}, PhysicalAgentProbe.class.getClassLoader())) {
            Class<?> web = loader.loadClass("com.example.Web"); Object heldWeb = web.getConstructor().newInstance();
            var value = web.getMethod("value"); require(value.invoke(heldWeb).equals(7), "initial dependency value");
            Path edit = root.resolve("staged/com/example/Core.java"); Files.createDirectories(edit.getParent());
            Files.writeString(edit, "package com.example; public class Core { public int value() { return 9; } }");
            Path staged = Files.createDirectories(root.resolve("staged-output"));
            require(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", staged.toString(), edit.toString()) == 0, "compile body save");
            Files.write(coreOutput.resolve("com/example/Core.class"), Files.readAllBytes(staged.resolve("com/example/Core.class")));
            await(() -> value.invoke(heldWeb).equals(9), "held generic class reload");
            Class<?> model = loader.loadClass("com.example.ManualModel"); Object heldModel = model.getConstructor().newInstance();
            var run = model.getMethod("run"); require(run.invoke(heldModel).equals("before"), "initial model");
            Files.write(modelOutput.resolve("com/example/ManualModel.class"), Files.readAllBytes(root.resolve("saved-model.bin")));
            await(() -> {
                try { return run.invoke(heldModel).equals("after") && model.getMethod("added").invoke(heldModel).equals(21); }
                catch (NoSuchMethodException pending) { return false; }
            }, "manual model reload and added member");
        }
        if (core) {
            // Every contract capability advises even with physically absent implementations.
            for (Capability capability : Capability.values()) {
                require(modules.handle(new PlatformChangeHandler.Change(capability, root.resolve("contract.txt"),
                        "MODIFIED", "example", "resources")) == PlatformChangeHandler.Outcome.ADVICE, "advice " + capability);
            }
            require(modules.prepareInterceptor("com.example.Interceptor", List.of()) == null, "interceptor advice");
            modules.afterReload(PhysicalAgentProbe.class);
            // These five edits must take the real watcher, classification and reload queue.
            List<Path> edits = List.of(root.resolve("bin/custom/core/resources/example-items.xml"),
                    root.resolve("config/local.properties"), root.resolve("bin/custom/core/resources/data.impex"),
                    root.resolve("bin/custom/core/resources/localization/example-locales_en.properties"),
                    root.resolve("bin/custom/core/resources/example-backoffice-config.xml"));
            for (Path edit : edits) Files.writeString(edit, Files.readString(edit) + "\n# changed\n");
            for (String advice : List.of("example-items.xml: Automatic code generation is unavailable",
                    "local.properties: Platform properties were not applied", "data.impex: Import the changed ImpEx file manually",
                    "example-locales_en.properties: Localization text was not applied",
                    "example-backoffice-config.xml: Backoffice configuration was not applied"))
                await(() -> messages.stream().anyMatch(m -> m.contains(advice)), advice);
        }
        System.out.println("PHYSICAL_" + args[1].toUpperCase(Locale.ROOT) + "_OK");
    }
    @FunctionalInterface private interface Check { boolean get() throws Exception; }
    private static void await(Check check, String reason) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < end) { if (check.get()) return; Thread.sleep(50); }
        throw new AssertionError(reason + ": " + messages);
    }
    private static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
