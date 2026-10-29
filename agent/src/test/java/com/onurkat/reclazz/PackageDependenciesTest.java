/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which packages may know about which, read from the compiled classes.
 *
 * <p>The agent is one module, so the compiler enforces nothing between its
 * packages, and the graph had grown ten two-way dependencies. Three of
 * them came from one class: the restart ledger lived in {@code agent}, the
 * composition root, and every reloader that noted a restart pulled
 * {@code agent} in with it. The ledger is reporting, and lives with the
 * status reporter now, the agent's options live in {@code config} with nothing
 * under them but {@code ui}, and the watcher is handed a callback instead of
 * calling the agent. What this pins is the layering that leaves: {@code ui}
 * at the bottom, {@code config} and {@code util} on it, and nothing but the
 * composition root ({@code agent}) and {@code reload} knowing the agent.
 * Javadoc references do not count; only what the bytecode names.
 */
class PackageDependenciesTest {

    private static final Pattern PROJECT_PACKAGE = Pattern.compile("com/onurkat/reclazz/([a-z]+)/");

    /** Package -> the project packages it may reference. Absent means unconstrained. */
    private static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
            Map.entry("ui", Set.of()),
            Map.entry("config", Set.of("ui")),
            Map.entry("util", Set.of("ui", "config")),
            Map.entry("spring", Set.of("ui", "util", "config", "platform", "transform", "bootstrap", "reload")),
            Map.entry("hybris", Set.of("ui", "util", "config", "platform", "watcher", "bootstrap", "ext")),
            Map.entry("compiler", Set.of("ui", "util", "config", "platform")),
            // The composition root assembles these; they do not know it.
            Map.entry("platform", Set.of("ui", "util", "config", "compiler")),
            Map.entry("transform", Set.of("ui", "util", "config", "bootstrap", "platform")),
            Map.entry("watcher", Set.of("ui", "util", "config", "platform", "transform", "ext")),
            Map.entry("ext", Set.of("ui", "util", "config", "platform")),
            Map.entry("reload", Set.of("agent", "bootstrap", "config", "ext", "platform", "spring", "transform", "ui", "util", "watcher")));

    @Test
    void theLowerPackagesDoNotReachUp() throws IOException {
        Map<String, Set<String>> graph = dependencyGraph();
        assertFalse(graph.isEmpty(), "no compiled classes found; run the build first");
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Set<String>> rule : ALLOWED.entrySet()) {
            Set<String> actual = graph.getOrDefault(rule.getKey(), Set.of());
            for (String dep : actual) {
                if (!rule.getValue().contains(dep)) {
                    violations.add(rule.getKey() + " -> " + dep);
                }
            }
        }
        assertEquals(List.of(), violations,
                "packages reaching where they should not; the graph is\n" + render(graph));
    }

    @Test
    void reloadAndWatcherDoNotNamePlatformAutomationClasses() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String pkg : List.of("reload", "watcher")) {
            try (Stream<Path> files = Files.walk(classesDir().resolve(pkg))) {
                for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                    for (String name : referencedNames(Files.readAllBytes(file))) {
                        if (name.startsWith("com/onurkat/reclazz/hybris/backoffice/")
                                || name.startsWith("com/onurkat/reclazz/hybris/codegen/")
                                || name.startsWith("com/onurkat/reclazz/hybris/interceptor/")
                                || name.startsWith("com/onurkat/reclazz/hybris/impex/")
                                || name.equals("com/onurkat/reclazz/hybris/HybrisLocalizationReloader")
                                || name.equals("com/onurkat/reclazz/hybris/HacRunningSystemUpdater")) {
                            violations.add(file.getFileName() + " -> " + name);
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test
    void onlyCompositionRootReferencesPremiumAutomation() throws IOException {
        Map<String, Set<String>> graph = dependencyGraph();
        assertFalse(graph.isEmpty(), "no compiled classes found; run the build first");
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : graph.entrySet()) {
            if (!Set.of("agent", "hybris").contains(entry.getKey()) && entry.getValue().contains("hybris")) {
                violations.add(entry.getKey() + " -> hybris");
            }
        }
        assertEquals(List.of(), violations, "premium references outside composition root: " + render(graph));
    }

    @Test
    void coreBytecodeNeverReferencesPremium() throws IOException {
        try (var files = Files.walk(classesDir())) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                assertFalse(classesDir().relativize(file).toString().startsWith("hybris"), file.toString());
                assertTrue(referencedNames(Files.readAllBytes(file)).stream()
                        .noneMatch(name -> name.startsWith("com/onurkat/reclazz/hybris/")), file.toString());
            }
        }
    }

    private static Path premiumClassesDir() {
        Path dir = classesDir().getParent().getParent().getParent().getParent()
                .resolve("premium/com/onurkat/reclazz");
        assertTrue(Files.isDirectory(dir), "premium classes missing: " + dir);
        return dir;
    }

    /** The one that drove most of the cycles: nothing but the ledger's own package and its users' reporting. */
    @Test
    void theRestartLedgerIsReportingAndLivesWithIt() throws IOException {
        Path classes = classesDir();
        assertTrue(Files.exists(classes.resolve("ui/RestartLedger.class")), "RestartLedger belongs to ui");
        assertFalse(Files.exists(classes.resolve("agent/RestartLedger.class")), "and not to agent");
    }

    private static Map<String, Set<String>> dependencyGraph() throws IOException {
        Map<String, Set<String>> graph = new TreeMap<>();
        for (Path classes : List.of(classesDir(), premiumClassesDir())) {
            try (Stream<Path> files = Files.walk(classes)) {
                for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                    String rel = classes.relativize(file).toString().replace('\\', '/');
                    int slash = rel.indexOf('/');
                    if (slash < 0) continue;
                    String pkg = rel.substring(0, slash);
                    Set<String> deps = graph.computeIfAbsent(pkg, k -> new TreeSet<>());
                    for (String name : referencedNames(Files.readAllBytes(file))) {
                        Matcher m = PROJECT_PACKAGE.matcher(name + "/");
                        if (m.find() && !m.group(1).equals(pkg)) deps.add(m.group(1));
                    }
                }
            }
        }
        return graph;
    }

    /** Every class the bytecode names, in references, descriptors and signatures. Comments are not in here. */
    private static List<String> referencedNames(byte[] bytecode) {
        Set<String> names = new TreeSet<>();
        Remapper recorder = new Remapper() {
            @Override
            public String map(String internalName) {
                names.add(internalName);
                return internalName;
            }
        };
        new ClassReader(bytecode).accept(new ClassRemapper(new ClassNode(), recorder), 0);
        return new ArrayList<>(names);
    }

    private static Path classesDir() {
        Path here = Path.of("").toAbsolutePath();
        for (Path candidate : List.of(
                here.resolve("build/classes/java/main/com/onurkat/reclazz"),
                here.resolve("agent/build/classes/java/main/com/onurkat/reclazz"))) {
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("compiled agent classes not found under " + here);
    }

    private static String render(Map<String, Set<String>> graph) {
        StringBuilder sb = new StringBuilder();
        graph.forEach((k, v) -> sb.append("  ").append(k).append(" -> ").append(v).append('\n'));
        return sb.toString();
    }
}
