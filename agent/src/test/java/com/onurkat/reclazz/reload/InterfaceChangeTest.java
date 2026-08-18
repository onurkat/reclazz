/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.transform.TransformContext;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adding an interface used to be the quietest failure in the tool.
 *
 * <p>The analyser did not compare interfaces at all; the field was hardcoded to
 * "unchanged" with a comment saying it was simplified. So the reload went down
 * the ordinary path: the method bodies landed through the companion, the
 * redefinition that would have carried the interface was refused by the JVM,
 * and that refusal was caught by the handler written for the benign
 * constructor-refresh case. The log said "Reloaded". The class did not
 * implement the interface.
 *
 * <p>Measured on a running JVM before the fix, on SapMachine 21:
 *
 * <pre>
 *   [SWAP] Reloaded Service (19ms)
 *   TICK 40 -&gt; v2 | interfaces=[] | instanceof Tag=false
 * </pre>
 *
 * <p>A developer reading that goes looking for the bug in their own code,
 * which is the precise outcome this tool exists to prevent.
 *
 * <p>The superclass is a different question and stays refused: measured on the
 * same three VMs, including JetBrains Runtime with
 * {@code -XX:+AllowEnhancedClassRedefinition}, {@code redefineClasses} rejects
 * a changed superclass every time. A changed interface list that same VM
 * accepts, existing instances included, so it is reported rather than refused.
 */
class InterfaceChangeTest {

    @Test
    void addingAnInterfaceIsDetected() throws IOException {
        var diff = StructuralAnalyzer.analyze(metadataOf(Plain.class), bytecodeOf(Tagged.class));

        assertTrue(diff.isInterfacesChanged(),
                "an added interface was invisible to the diff, which is how it "
                + "reached the user as a successful reload");
        assertEquals(Set.of(internalName(Tag.class)), diff.getAddedInterfaces());
        assertTrue(diff.getRemovedInterfaces().isEmpty());
    }

    @Test
    void removingAnInterfaceIsDetectedToo() throws IOException {
        var diff = StructuralAnalyzer.analyze(metadataOf(Tagged.class), bytecodeOf(Plain.class));

        assertTrue(diff.isInterfacesChanged());
        assertEquals(Set.of(internalName(Tag.class)), diff.getRemovedInterfaces());
    }

    @Test
    void anUnchangedInterfaceListIsNotReported() throws IOException {
        var diff = StructuralAnalyzer.analyze(metadataOf(Tagged.class), bytecodeOf(Tagged.class));

        assertFalse(diff.isInterfacesChanged(), "nothing moved here");
    }

    /**
     * A class recorded by an older build has no interface set at all. Reading
     * that as "it had none" would announce every interface it has as newly
     * added, on the first reload after an upgrade, for every class in the
     * application.
     */
    @Test
    void anUnknownInterfaceSetIsNotMistakenForAnEmptyOne() throws IOException {
        ClassNode node = parse(bytecodeOf(Tagged.class));
        var legacy = new TransformContext.ClassMetadata(
                sigsOf(node).methods, sigsOf(node).fields, 0, node.superName, Set.of());

        var diff = StructuralAnalyzer.analyze(legacy, bytecodeOf(Tagged.class));

        assertFalse(diff.isInterfacesChanged(),
                "unknown is not the same as none, and guessing turns an upgrade "
                + "into a screenful of warnings about nothing");
    }

    /**
     * The two cases have different answers, so they must not share a verdict.
     * A changed interface list is applied by an enhanced-redefinition VM; a
     * changed superclass is refused by every VM there is.
     */
    @Test
    void anInterfaceChangeIsNotTreatedAsUnredefinable() throws IOException {
        var diff = StructuralAnalyzer.analyze(metadataOf(Plain.class), bytecodeOf(Tagged.class));

        assertFalse(diff.isUnsupported(),
                "refusing the whole reload here would throw away the method "
                + "bodies, which do reload, to punish the one part that cannot");
    }

    @Test
    void aSuperclassChangeStaysUnredefinable() throws IOException {
        var diff = StructuralAnalyzer.analyze(metadataOf(Plain.class), bytecodeOf(Rebased.class));

        assertTrue(diff.isUnsupported(),
                "no JVM applies a changed superclass to a loaded class");
    }

    /** The reason has to name the interface, or the developer goes hunting. */
    @Test
    void theWarningNamesWhatChanged() throws IOException {
        List<String> text = stringsIn("com/onurkat/reclazz/reload/StructuralReloader");

        assertTrue(text.stream().anyMatch(t -> t.contains("will not change the")),
                "the stock-JVM case has to be stated: " + sample(text));
        assertTrue(text.stream().anyMatch(t -> t.contains("AllowEnhancedClassRedefinition")),
                "and the way out of it, since one exists: " + sample(text));
        assertTrue(text.stream().anyMatch(t -> t.contains("changed its superclass")),
                "the superclass case keeps its own, different answer: " + sample(text));
    }

    /**
     * A structural failure that explains itself carries no separate advice,
     * and the two places that print advice did not check. The superclass
     * message came out correct and was followed, on its own line, by the word
     * "null".
     */
    @Test
    void adviceIsOnlyPrintedWhenThereIsSome() throws IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/onurkat/reclazz/agent/ReclazzAgent.java"));

        int printed = source.split("StatusReporter\\.warn\\(advice\\)", -1).length - 1;
        assertEquals(2, printed,
                "both call sites have to print the checked local, not the raw getter");
        assertFalse(source.contains("warn(reloadResult.getStructuralChangeAdvice())"),
                "printing the getter straight through is what put \"null\" on screen");
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    interface Tag {
        default String tag() { return "tagged"; }
    }

    static class Base { }

    static class Other { }

    @SuppressWarnings("unused")
    static class Plain extends Base {
        String report() { return "plain"; }
    }

    @SuppressWarnings("unused")
    static class Tagged extends Base implements Tag {
        String report() { return "tagged"; }
    }

    @SuppressWarnings("unused")
    static class Rebased extends Other {
        String report() { return "rebased"; }
    }

    /** Metadata the way the load-time transform records it, interfaces included. */
    private static TransformContext.ClassMetadata metadataOf(Class<?> c) throws IOException {
        ClassNode node = parse(bytecodeOf(c));
        Sigs sigs = sigsOf(node);
        return new TransformContext.ClassMetadata(
                sigs.methods, sigs.fields, 0, node.superName, Set.of(),
                new LinkedHashSet<>(node.interfaces));
    }

    private record Sigs(List<TransformContext.MethodSig> methods,
                        List<TransformContext.FieldSig> fields) { }

    private static Sigs sigsOf(ClassNode node) {
        List<TransformContext.MethodSig> methods = new ArrayList<>();
        List<TransformContext.FieldSig> fields = new ArrayList<>();
        for (var m : node.methods) methods.add(new TransformContext.MethodSig(m.name, m.desc, m.access));
        for (var f : node.fields) fields.add(new TransformContext.FieldSig(f.name, f.desc, f.access));
        return new Sigs(methods, fields);
    }

    private static ClassNode parse(byte[] bytecode) {
        ClassNode node = new ClassNode();
        new ClassReader(bytecode).accept(node, ClassReader.SKIP_CODE);
        return node;
    }

    private static String internalName(Class<?> c) {
        return c.getName().replace('.', '/');
    }

    private static byte[] bytecodeOf(Class<?> c) throws IOException {
        try (InputStream in = c.getClassLoader()
                .getResourceAsStream(internalName(c) + ".class")) {
            assertNotNull(in, "cannot read " + c.getName());
            return in.readAllBytes();
        }
    }

    /** Message text, read from the constant pool rather than by running it. */
    private static List<String> stringsIn(String internalName) throws IOException {
        try (InputStream in = InterfaceChangeTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, "cannot read " + internalName);
            ClassReader reader = new ClassReader(in.readAllBytes());
            List<String> out = new ArrayList<>();
            char[] buffer = new char[reader.getMaxStringLength()];
            for (int i = 1; i < reader.getItemCount(); i++) {
                int offset = reader.getItem(i);
                if (offset == 0) continue;
                try {
                    if (reader.readByte(offset - 1) == 8) {   // CONSTANT_String
                        Object value = reader.readConst(i, buffer);
                        if (value instanceof String s) out.add(s);
                    }
                } catch (RuntimeException ignored) {
                    // Not every pool slot is readable as a constant; skip it.
                }
            }
            return out;
        }
    }

    private static String sample(List<String> text) {
        return text.stream().filter(t -> t.length() > 25).limit(6).toList().toString();
    }
}
