/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A changed superclass costs the whole save, and it should only cost the
 * hierarchy.
 *
 * <p>No JVM applies a changed superclass to a loaded class. That was measured
 * on a stock JDK, on JetBrains Runtime, and on JetBrains Runtime with enhanced
 * class redefinition, and all three answer "attempted to change superclass or
 * interfaces". So the hierarchy is settled. What was wrong was the blast
 * radius: one line in the extends clause refused the three method bodies
 * edited beside it, and the developer restarted to get back work that had
 * nothing to do with the hierarchy.
 *
 * <p>The salvage rests on where javac puts the reference. An inherited call
 * compiles to an invocation on the class itself, not on the superclass, so it
 * resolves against whatever the loaded class actually extends. The one
 * unavoidable mention is the {@code super()} call in the constructor. Point
 * that back and the file becomes redefinable with its new bodies.
 *
 * <p>These tests exist mostly to hold the refusals. Salvaging a body that
 * genuinely needs the new superclass would plant a NoSuchMethodError to be
 * discovered later, which is worse than the refusal it replaced.
 */
class HierarchyRevertTest {

    @Test
    void theSuperclassIsPutBackAndTheBodiesSurvive() throws IOException {
        var result = revert(RebasedPlain.class);

        assertTrue(result.applied(), "nothing here needs the new superclass: " + result.reason());
        assertEquals(internalName(OldBase.class), new ClassReader(result.bytecode()).getSuperName(),
                "the payload has to declare the superclass the JVM already has");
    }

    /**
     * Every class with a constructor calls {@code super()}, so this is the one
     * reference that always has to be rewritten rather than refused.
     */
    @Test
    void theSuperConstructorCallIsRedirected() throws IOException {
        var result = revert(RebasedPlain.class);
        assertTrue(result.applied());

        var remaining = referencesTo(result.bytecode(), internalName(NewBase.class));
        assertTrue(remaining.isEmpty(),
                "a super() call left pointing at the new superclass resolves to "
                + "a class this object does not extend. Found: " + remaining);
    }

    /**
     * The salvage is only honest while the bodies do not need what they no
     * longer have. A call to a method that lives on the new superclass would
     * compile into the payload and fail at the call.
     */
    @Test
    void aBodyThatCallsTheNewSuperclassIsRefused() throws IOException {
        var result = revert(RebasedCallingNewBase.class);

        assertFalse(result.applied());
        assertTrue(result.reason().contains("onlyOnNewBase"),
                "the reason has to name the call that blocked it: " + result.reason());
    }

    @Test
    void aFieldTypedAsTheNewSuperclassIsRefused() throws IOException {
        var result = revert(RebasedHoldingNewBase.class);

        assertFalse(result.applied());
        assertTrue(result.reason().contains("held"), result.reason());
    }

    @Test
    void aCastToTheNewSuperclassIsRefused() throws IOException {
        var result = revert(RebasedCasting.class);

        assertFalse(result.applied());
        assertTrue(result.reason().contains("as a type"), result.reason());
    }

    /**
     * Changing a superclass and its constructor signature in the same edit is
     * ordinary, and the redirected {@code super()} call would then resolve to
     * a constructor the old superclass does not have.
     */
    @Test
    void aSuperConstructorTheOldClassDoesNotHaveIsRefused() throws IOException {
        var result = HierarchyRevert.toLoadedSuperclass(
                bytecodeOf(RebasedPlain.class), internalName(NarrowBase.class), NarrowBase.class);

        assertFalse(result.applied(),
                "NarrowBase has no no-arg constructor, so the rewritten call points at nothing");
        assertTrue(result.reason().contains("no constructor"), result.reason());
    }

    @Test
    void aClassWhoseSuperclassDidNotChangeIsNotTouched() throws IOException {
        var result = HierarchyRevert.toLoadedSuperclass(
                bytecodeOf(RebasedPlain.class), internalName(NewBase.class), NewBase.class);

        assertFalse(result.applied());
        assertTrue(result.reason().contains("did not change"), result.reason());
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    static class OldBase {
        String who() { return "old"; }
    }

    static class NewBase {
        String who() { return "new"; }
        String onlyOnNewBase() { return "only here"; }
    }

    /** No constructor the rewritten super() call could resolve to. */
    static class NarrowBase {
        NarrowBase(int needed) { }
    }

    /** The ordinary case: the extends clause moved, the bodies did not follow. */
    @SuppressWarnings("unused")
    static class RebasedPlain extends NewBase {
        String report() { return "v2 " + who(); }
        String unrelated() { return "edited in the same save"; }
    }

    @SuppressWarnings("unused")
    static class RebasedCallingNewBase extends NewBase {
        String report() { return onlyOnNewBase(); }
    }

    @SuppressWarnings("unused")
    static class RebasedHoldingNewBase extends NewBase {
        NewBase held;
    }

    @SuppressWarnings("unused")
    static class RebasedCasting extends NewBase {
        String report(Object o) { return ((NewBase) o).who(); }
    }

    private static HierarchyRevert.Result revert(Class<?> rebased) throws IOException {
        return HierarchyRevert.toLoadedSuperclass(
                bytecodeOf(rebased), internalName(OldBase.class), OldBase.class);
    }

    /** Every instruction that still names the type, with where it sits. */
    private static java.util.List<String> referencesTo(byte[] bytecode, String wanted) {
        java.util.List<String> hits = new java.util.ArrayList<>();
        new ClassReader(bytecode).accept(
                new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.MethodVisitor visitMethod(
                            int access, String methodName, String methodDescriptor,
                            String signature, String[] exceptions) {
                        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String name,
                                                         String descriptor, boolean isInterface) {
                                if (wanted.equals(owner)) {
                                    hits.add(methodName + " calls " + owner + "." + name);
                                }
                            }

                            @Override
                            public void visitTypeInsn(int opcode, String type) {
                                if (wanted.equals(type)) hits.add(methodName + " names " + type);
                            }
                        };
                    }
                }, 0);
        return hits;
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
}
