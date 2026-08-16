/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.TemplateEngineRegistry;
import com.onurkat.reclazz.watcher.ChangeKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A template is data, not code. The engine parsed it once and will serve that
 * copy until something drops it, so editing a template did nothing to a
 * running application: the one change that looks like it should be the easiest
 * to see was the one that needed a restart.
 *
 * Measured against real engines before writing this. With Freemarker's own
 * update delay set the way production sets it, and Thymeleaf caching on,
 * neither picked up an edited template; with the agent attached both did.
 *
 * The only hard part is reaching the engine, which application code or Spring
 * builds and keeps private. It registers itself from a rewritten constructor,
 * so the pieces that can quietly stop working are the classification, the
 * watch list, and the registration, and those are what this pins.
 */
class TemplateReloadTest {

    @BeforeEach
    @AfterEach
    void isolate() {
        TemplateEngineRegistry.clearForTests();
    }

    // ── The change has to be recognised at all ────────────────────────────

    @Test
    void templateFilesAreClassifiedAsTemplates() {
        assertEquals(ChangeKind.TEMPLATE, ChangeKind.of("greeting.ftl"));
        assertEquals(ChangeKind.TEMPLATE, ChangeKind.of("greeting.ftlh"));
        assertEquals(ChangeKind.TEMPLATE, ChangeKind.of("card.html"));
        assertEquals(ChangeKind.TEMPLATE, ChangeKind.of("card.htm"));
    }

    /**
     * The XML kinds share the directory and some of the naming. Sending a
     * Spring context to the template reloader would clear nothing and, worse,
     * stop it reaching the reloader that does the work.
     */
    @Test
    void theOtherKindsAreNotSweptUp() {
        assertEquals(ChangeKind.SPRING_XML, ChangeKind.of("demo-spring.xml"));
        assertEquals(ChangeKind.CODEGEN_XML, ChangeKind.of("demo-items.xml"));
        assertEquals(ChangeKind.CLASS_FILE, ChangeKind.of("Demo.class"));
        assertEquals(ChangeKind.IMPEX, ChangeKind.of("data.impex"));
    }

    @Test
    void theWatcherAcceptsTemplateExtensions() throws IOException {
        String source = java.nio.file.Files.readString(sourceOf(
                "agent/src/main/java/com/onurkat/reclazz/watcher/FileWatcher.java"));

        for (String ext : List.of(".ftl", ".ftlh", ".html", ".htm")) {
            assertTrue(source.contains("endsWith(\"" + ext + "\")"),
                    ext + " must be watched, or the change never reaches the reloader");
        }
    }

    // ── Reaching the engine ───────────────────────────────────────────────

    @Test
    void bothEnginesAreIntercepted() throws IOException {
        List<String> constants = stringsIn(
                "com/onurkat/reclazz/transform/TemplateInterceptTransformer");

        assertTrue(constants.contains("org/thymeleaf/TemplateEngine"),
                "Thymeleaf is the common one in Spring Boot. Found: " + constants);
        assertTrue(constants.contains("freemarker/template/Configuration"),
                "Freemarker holds its cache on Configuration. Found: " + constants);
    }

    @Test
    void theEngineRegistersItselfFromItsConstructor() throws IOException {
        List<String> constants = stringsIn(
                "com/onurkat/reclazz/transform/TemplateInterceptTransformer");
        assertTrue(constants.stream().anyMatch(c -> c.contains("TemplateEngineRegistry")),
                "the rewritten constructor must call the registry. Found: " + constants);
        assertTrue(constants.stream().anyMatch(c -> c.equals("<init>") || c.contains("init")),
                "registration belongs in the constructor; there is no other moment "
                + "when the instance is known. Found: " + constants);
        assertTrue(constants.stream().anyMatch(c -> c.equals("register")),
                "the registry method actually called. Found: " + constants);
    }

    /**
     * Frame computation resolves types, and resolving types loads classes from
     * inside a transform, which is how a class ends up permanently
     * uninstrumented. Adding two instructions does not need frames.
     */
    @Test
    void theInterceptDoesNotRecomputeFrames() throws IOException {
        List<String> calls = callsIn("com/onurkat/reclazz/transform/TemplateInterceptTransformer");
        assertTrue(calls.stream().noneMatch(c -> c.endsWith("Class.forName")),
                "loading a class from inside a transform costs that class its "
                + "instrumentation. Calls: " + calls);
    }

    // ── Clearing, and staying quiet when there is nothing to clear ────────

    @Test
    void anApplicationWithNoTemplateEngineHearsNothing() {
        assertEquals(0, new TemplateReloader().reload("card.html"),
                "plenty of applications have .html files and no engine; claiming "
                + "a reload there would be a lie");
    }

    @Test
    void anUnrecognisedObjectIsNotMistakenForAnEngine() {
        TemplateEngineRegistry.register("not an engine");
        assertEquals(0, new TemplateReloader().reload("card.html"),
                "the registry is weakly typed by necessity; the reloader must "
                + "check what it is holding");
    }

    @Test
    void nullIsIgnoredRatherThanStored() {
        TemplateEngineRegistry.register(null);
        assertEquals(0, TemplateEngineRegistry.size(),
                "a constructor that failed halfway must not leave a null behind");
    }

    /**
     * An application may build several engines and discard most of them. A
     * hot-reload tool holding the discarded ones alive would be a leak in
     * exactly the long-running process it is meant to help.
     */
    @Test
    void theRegistryHoldsEnginesWeakly() throws IOException {
        List<String> source = List.of(java.nio.file.Files.readString(sourceOf(
                "agent/src/main/java/com/onurkat/reclazz/bootstrap/TemplateEngineRegistry.java")));
        assertTrue(source.get(0).contains("WeakHashMap"),
                "engines must be held weakly");
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    private static java.nio.file.Path sourceOf(String repoRelative) {
        java.nio.file.Path p = java.nio.file.Path.of(repoRelative);
        if (!java.nio.file.Files.isRegularFile(p)) {
            p = java.nio.file.Path.of(repoRelative.replaceFirst("^agent/", ""));
        }
        if (!java.nio.file.Files.isRegularFile(p)) fail("cannot find " + repoRelative);
        return p;
    }

    private static ClassNode parse(String internalName) throws IOException {
        String path = "agent/build/classes/java/main/" + internalName + ".class";
        if (!new java.io.File(path).isFile()) {
            path = "build/classes/java/main/" + internalName + ".class";
        }
        java.io.File f = new java.io.File(path);
        if (!f.isFile()) fail(internalName + " is not compiled at " + path);
        ClassNode node = new ClassNode();
        new ClassReader(java.nio.file.Files.readAllBytes(f.toPath())).accept(node, 0);
        return node;
    }

    /** Includes inner classes: the adapters carry most of the constants. */
    private static List<String> stringsIn(String internalName) throws IOException {
        List<String> all = new java.util.ArrayList<>(stringsInOne(internalName));
        for (String inner : innerClassesOf(internalName)) {
            all.addAll(stringsInOne(inner));
        }
        return all;
    }

    private static List<String> innerClassesOf(String internalName) {
        java.io.File dir = new java.io.File("agent/build/classes/java/main/"
                + internalName.substring(0, internalName.lastIndexOf('/')));
        if (!dir.isDirectory()) {
            dir = new java.io.File("build/classes/java/main/"
                    + internalName.substring(0, internalName.lastIndexOf('/')));
        }
        String simple = internalName.substring(internalName.lastIndexOf('/') + 1);
        String prefix = internalName.substring(0, internalName.lastIndexOf('/') + 1);
        java.io.File[] files = dir.listFiles();
        if (files == null) return List.of();
        return java.util.Arrays.stream(files)
                .map(java.io.File::getName)
                .filter(n -> n.startsWith(simple + "$") && n.endsWith(".class"))
                .map(n -> prefix + n.substring(0, n.length() - 6))
                .collect(Collectors.toList());
    }

    private static List<String> stringsInOne(String internalName) throws IOException {
        ClassNode node = parse(internalName);
        return node.methods.stream()
                .flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                .filter(i -> i instanceof LdcInsnNode)
                .map(i -> ((LdcInsnNode) i).cst)
                .filter(c -> c instanceof String)
                .map(Object::toString)
                .collect(Collectors.toList());
    }

    private static List<String> callsIn(String internalName) throws IOException {
        List<String> all = new java.util.ArrayList<>(callsInOne(internalName));
        for (String inner : innerClassesOf(internalName)) {
            all.addAll(callsInOne(inner));
        }
        return all;
    }

    private static List<String> callsInOne(String internalName) throws IOException {
        ClassNode node = parse(internalName);
        return node.methods.stream()
                .flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                .filter(i -> i instanceof MethodInsnNode)
                .map(i -> ((MethodInsnNode) i).owner + "." + ((MethodInsnNode) i).name)
                .collect(Collectors.toList());
    }
}
