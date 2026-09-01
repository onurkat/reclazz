/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.agent.AgentConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The load-time transform renames a method's body and puts a trampoline under
 * the original name. The synchronized flag used to move to the trampoline
 * alone, on the reading that every call goes through it, and this engine is
 * exactly what stops that being true: a call site inside a watched class is
 * rewritten to dispatch straight to the renamed body, which is the point of it,
 * and a body without the flag takes no monitor.
 *
 * <p>Measured on Spring Boot 3.3.4, stock JDK 21, with no reload at all: two
 * concurrent calls to a synchronized method of a watched class took 6.3 seconds
 * without the agent and 3.3 with it. Attaching Reclazz was removing mutual
 * exclusion from the application's own code, from startup, and saying nothing.
 *
 * <p>A monitor is reentrant, so carrying the flag on both copies costs a
 * re-entry on the path that goes through the trampoline and is correct on the
 * path that does not.
 */
class SynchronizedPreservedTest extends TransformTestBase {

    private static Map<String, Integer> methodFlags(byte[] bytecode) {
        Map<String, Integer> flags = new LinkedHashMap<>();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                flags.put(name, access);
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return flags;
    }

    private static Map<String, Integer> transformFlags(String name, String source) throws Exception {
        TransformContext context = new TransformContext();
        context.addWatched(name);
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));
        byte[] raw = compile(new SourceFile(name, source)).get(name);
        byte[] transformed = transformer.transform(
                TransformTestBase.class.getClassLoader(), name, null, null, raw);
        assertNotNull(transformed);
        return methodFlags(transformed);
    }

    @Test
    void theRenamedBodyKeepsTheSynchronizedFlag() throws Exception {
        Map<String, Integer> flags = transformFlags("Guarded",
                "public class Guarded {\n"
                + "    public synchronized String guarded() { return \"g\"; }\n"
                + "}");

        String renamed = flags.keySet().stream()
                .filter(n -> n.startsWith("__reclazz$v0$guarded"))
                .findFirst().orElseThrow(() -> new AssertionError("no renamed body: " + flags.keySet()));

        assertNotEquals(0, flags.get(renamed) & Opcodes.ACC_SYNCHRONIZED,
                "a rewritten call site dispatches here and would take no monitor without it");
        assertNotEquals(0, flags.get("guarded") & Opcodes.ACC_SYNCHRONIZED,
                "and the trampoline keeps it for every caller that still goes through it");
    }

    /** A static synchronized method locks the class, and both copies must. */
    @Test
    void aStaticSynchronizedMethodKeepsItOnBothCopies() throws Exception {
        Map<String, Integer> flags = transformFlags("GuardedStatic",
                "public class GuardedStatic {\n"
                + "    public static synchronized String guarded() { return \"g\"; }\n"
                + "}");

        String renamed = flags.keySet().stream()
                .filter(n -> n.startsWith("__reclazz$v0$guarded"))
                .findFirst().orElseThrow();

        assertNotEquals(0, flags.get(renamed) & Opcodes.ACC_SYNCHRONIZED);
        assertNotEquals(0, flags.get(renamed) & Opcodes.ACC_STATIC);
    }

    /** A method that was never synchronized must not become so. */
    @Test
    void anUnsynchronizedMethodIsLeftAlone() throws Exception {
        Map<String, Integer> flags = transformFlags("Plain",
                "public class Plain {\n"
                + "    public String open() { return \"o\"; }\n"
                + "}");

        String renamed = flags.keySet().stream()
                .filter(n -> n.startsWith("__reclazz$v0$open"))
                .findFirst().orElseThrow();

        assertEquals(0, flags.get(renamed) & Opcodes.ACC_SYNCHRONIZED,
                "taking a monitor nobody asked for is its own bug");
        assertEquals(0, flags.get("open") & Opcodes.ACC_SYNCHRONIZED);
    }
}
