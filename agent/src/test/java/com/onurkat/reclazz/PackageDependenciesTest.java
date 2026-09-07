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
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "ui", Set.of(),
            "config", Set.of("ui"),
            "util", Set.of("ui", "config"),
            "spring", Set.of("ui", "util", "config", "platform", "transform", "bootstrap", "reload"),
            "hybris", Set.of("ui", "util", "config", "platform", "watcher", "bootstrap"),
            "compiler", Set.of("ui", "util", "config", "platform", "hybris"),
            // The composition root assembles these; they do not know it.
            "platform", Set.of("ui", "util", "config", "compiler", "hybris"),
            "transform", Set.of("ui", "util", "config", "bootstrap", "platform"),
            "watcher", Set.of("ui", "util", "config", "platform", "transform", "hybris"));

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

    /** The one that drove most of the cycles: nothing but the ledger's own package and its users' reporting. */
    @Test
    void theRestartLedgerIsReportingAndLivesWithIt() throws IOException {
        Path classes = classesDir();
        assertTrue(Files.exists(classes.resolve("ui/RestartLedger.class")), "RestartLedger belongs to ui");
        assertFalse(Files.exists(classes.resolve("agent/RestartLedger.class")), "and not to agent");
    }

    private static Map<String, Set<String>> dependencyGraph() throws IOException {
        Map<String, Set<String>> graph = new TreeMap<>();
        Path classes = classesDir();
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
