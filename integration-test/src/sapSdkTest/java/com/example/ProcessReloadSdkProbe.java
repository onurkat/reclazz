/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import com.onurkat.reclazz.ext.*;
import com.onurkat.reclazz.hybris.SapProcessDefinitionReloader;
import com.onurkat.reclazz.platform.*;
import de.hybris.platform.processengine.definition.*;
import de.hybris.platform.scripting.engine.ScriptingLanguagesService;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Real native parser, cache, provider and transformed initial-load path; no tenant/task execution. */
public final class ProcessReloadSdkProbe {
    private static int checks;
    static void check(boolean result, String label) {
        if (!result) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
    static void save(Path file, String name, String start) throws Exception {
        Files.writeString(file, "<process xmlns='http://www.hybris.de/xsd/processdefinition' name='" + name
                + "' start='" + start + "' processClass='de.hybris.platform.processengine.model.BusinessProcessModel'>"
                + "<end id='v1' state='SUCCEEDED'>v1</end><end id='v2' state='SUCCEEDED'>v2</end>"
                + "<end id='v3' state='SUCCEEDED'>v3</end></process>");
    }
    /** Native initial resource enumeration/parsing is inherited unchanged; no model jars or DB needed. */
    public static final class EmptySearchProvider extends ProcessDefinitionsProvider {
        int queries;
        public EmptySearchProvider(XMLProcessDefinitionsReader reader) { super(reader, null); }
        @Override public ProcessDefinitionId getLatestDefinitionIdFor(ProcessDefinitionId id) { queries++; return null; }
    }
    static final class Fixture implements AutoCloseable {
        final GenericApplicationContext context = new GenericApplicationContext();
        final XMLProcessDefinitionsReader reader;
        final EmptySearchProvider provider;
        final ProcessDefinitionsCache cache = new ProcessDefinitionsCache();
        final DefaultProcessDefinitionFactory factory = new DefaultProcessDefinitionFactory();
        final Path file;
        Fixture(Path directory) throws Exception {
            file = Files.createDirectories(directory).resolve("flow.xml"); save(file, "exampleA", "v1");
            var scripting = (ScriptingLanguagesService) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ScriptingLanguagesService.class}, (p, m, a) -> { throw new AssertionError("No scripts"); });
            reader = new XMLProcessDefinitionsReader(scripting);
            provider = new EmptySearchProvider(reader);
            provider.setApplicationContext(context); cache.setProcessDefinitionsProvider(provider);
            factory.setDefinitionsCache(cache); factory.setXmlDefinitionsReader(reader);
            context.getBeanFactory().registerSingleton("factory", factory);
            var holder = new ProcessDefinitionResource(); holder.setResource(new FileSystemResource(file));
            context.getBeanFactory().registerSingleton("resource", holder);
            context.refresh(); ApplicationContextHolder.register(context);
        }
        PlatformChangeHandler.Outcome apply() {
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            ClassLoader watcherLoader = ClassLoader.getPlatformClassLoader();
            thread.setContextClassLoader(watcherLoader);
            try {
                return new SapProcessDefinitionReloader().handle(new PlatformChangeHandler.Change(
                        Capability.PROCESS_RELOAD, file, "MODIFIED", "example", "resources"));
            } finally {
                boolean restored = thread.getContextClassLoader() == watcherLoader;
                thread.setContextClassLoader(previous);
                if (!restored) throw new AssertionError("reloader leaked the native context loader");
            }
        }
        void load() { cache.getAllIds(); }
        ProcessDefinition active(String name) { return factory.getProcessDefinition(name); }
        @Override public void close() { context.close(); ApplicationContextHolder.clear(); SapProcessDefinitions.discard(cache); }
    }
    public static void main(String[] args) throws Exception {
        com.onurkat.reclazz.ui.StatusReporter.setVerbose(true);
        com.onurkat.reclazz.ui.StatusReporter.addListener((level, message) -> {
            if (message.startsWith("SAP process source")) System.out.println("SDK_DIAGNOSTIC " + message);
        });
        Path directory = Path.of(args[0]);
        boolean late = args.length > 1 && args[1].equals("late");
        try (var f = new Fixture(directory.resolve("main"))) {
            check(SapProcessDefinitions.field(f.cache, "definitions") == null, "fresh native map is null");
            check(f.apply() == PlatformChangeHandler.Outcome.IGNORED, "save before native load does not apply");
            check(f.provider.queries == 0 && SapProcessDefinitions.field(f.cache, "definitions") == null,
                    "Reclazz neither calls provider nor initialises cache");
            f.load();
            var a = f.active("exampleA");
            System.out.println("SDK_DEFINITION_TYPE " + a.getClass().getName()
                    + " public=" + java.lang.reflect.Modifier.isPublic(a.getClass().getModifiers()));
            if (late) {
                check(SapProcessDefinitions.observed(f.cache, f.provider, f.context).isEmpty(), "late load has no fabricated association");
                for (String start : List.of("v2", "v1", "v3")) {
                    save(f.file, "exampleA", start);
                    check(f.apply() == PlatformChangeHandler.Outcome.ADVICE && f.active("exampleA") == a,
                            "late attach refuses save " + start);
                }
            } else {
                check(SapProcessDefinitions.observed(f.cache, f.provider, f.context).size() == 1,
                        "real transformed native startup records source");
                check(a.getVersion().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(f.file)))), "native version equals physical content hash");
                save(f.file, "exampleA", "v2");
                check(f.apply() == PlatformChangeHandler.Outcome.HANDLED, "native reloader publishes edited graph");
                check(f.active("exampleA").getStartNode().getId().equals("v2"), "new lookup follows v2");
                check(f.factory.getProcessDefinition(a.getId()) == a, "historical v1 object retained");
                var lastGood = f.active("exampleA"); Files.writeString(f.file, "<process>");
                check(f.apply() == PlatformChangeHandler.Outcome.ADVICE && f.active("exampleA") == lastGood,
                        "native parse failure preserves active definition");
                for (String start : List.of("v3", "v1")) {
                    save(f.file, "exampleA", start);
                    check(f.apply() == PlatformChangeHandler.Outcome.HANDLED
                            && f.active("exampleA").getStartNode().getId().equals(start), "repeated save/restore " + start);
                }
                Path bFile = directory.resolve("unregistered-b.xml"); save(bFile, "exampleB", "v1");
                var b = f.reader.from(new FileSystemResource(bFile)); f.factory.add(b);
                Files.copy(bFile, f.file, StandardCopyOption.REPLACE_EXISTING);
                check(f.apply() == PlatformChangeHandler.Outcome.ADVICE && f.active("exampleB") == b,
                        "copied identical B cannot change observed A source");
                save(f.file, "exampleB", "v2");
                check(f.apply() == PlatformChangeHandler.Outcome.ADVICE && f.active("exampleB") == b,
                        "second copied-file save cannot overwrite unrelated B");
                save(f.file, "exampleA", "v2");
                check(f.apply() == PlatformChangeHandler.Outcome.HANDLED, "retained association recovers after refused rename");
                // Discard simulates missing observation; equal original bytes must not re-establish it.
                SapProcessDefinitions.discard(f.cache); var old = f.active("exampleA");
                check(f.apply() == PlatformChangeHandler.Outcome.ADVICE && f.active("exampleA") == old,
                        "missing association cannot be adopted from matching bytes");
            }
        }
        if (!late) try (var f = new Fixture(directory.resolve("failed-initial-load"))) {
            Files.writeString(f.file, "<process>");
            boolean rejected = false;
            try { f.load(); } catch (RuntimeException malformed) { rejected = true; }
            check(rejected, "malformed native startup load fails");
            check(SapProcessDefinitions.observed(f.cache, f.provider, f.context).isEmpty(), "failed startup commits no association");
            save(f.file, "exampleA", "v1");
            f.reader.from(new FileSystemResource(f.file));
            check(SapProcessDefinitions.observed(f.cache, f.provider, f.context).isEmpty(),
                    "finally clears observation scope before unrelated parser call");
        }
        if (!late) try (var f = new Fixture(directory.resolve("malformed-sibling"))) {
            Path sibling = f.file.resolveSibling("sibling.xml"); save(sibling, "exampleB", "v1");
            var holder = new ProcessDefinitionResource(); holder.setResource(new FileSystemResource(sibling));
            f.context.getBeanFactory().registerSingleton("sibling", holder);
            f.load(); var b = f.active("exampleB");
            check(SapProcessDefinitions.observed(f.cache, f.provider, f.context).size() == 2,
                    "native startup observes both distinct resource names");
            Files.writeString(sibling, "<process>"); save(f.file, "exampleA", "v2");
            check(f.apply() == PlatformChangeHandler.Outcome.HANDLED
                    && f.active("exampleA").getStartNode().getId().equals("v2") && f.active("exampleB") == b,
                    "malformed sibling cannot block valid native reload or change its own active definition");
        }
        if (!late) try (var f = new Fixture(directory.resolve("duplicate-name"))) {
            Path duplicate = f.file.resolveSibling("duplicate.xml"); Files.copy(f.file, duplicate);
            var holder = new ProcessDefinitionResource(); holder.setResource(new FileSystemResource(duplicate));
            f.context.getBeanFactory().registerSingleton("duplicate", holder);
            f.load(); var a = f.active("exampleA");
            check(SapProcessDefinitions.observed(f.cache, f.provider, f.context).size() == 2,
                    "native startup observes both duplicate-name resource paths");
            save(f.file, "exampleA", "v2");
            check(f.apply() == PlatformChangeHandler.Outcome.ADVICE && f.active("exampleA") == a,
                    "observed duplicate native names still refuse publication");
        }
        System.out.println("SDK_RELOAD_CHECKS=" + checks + " MODE=" + (late ? "late" : "observed"));
        System.out.println("SCOPE=real transformed native parser/cache/provider startup; synthetic latest-id override; no tenant/tasks/live watcher");
    }
}
