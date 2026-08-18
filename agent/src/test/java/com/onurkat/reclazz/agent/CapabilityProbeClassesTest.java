/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two classes the capability probe redefines between.
 *
 * <p>They used to come from a code-generation library that was in the agent for
 * this and nothing else. The agent is loaded into the JVM that runs somebody
 * else's application, so every library inside it is a library inside that
 * application: 25 MB of classes to emit two methods returning a constant was
 * the wrong trade, and it is now ASM, which was already there. The shipped
 * agent went from 10.2 MB to 0.7 MB.
 *
 * <p>What has to stay true is that the probe still answers correctly, and the
 * failure mode if it does not is quiet: malformed bytes make the probe throw a
 * format error, which it reads as "inconclusive" and hands to the fallback,
 * and the agent then picks its engine by VM name instead of by measurement. So
 * the classes are checked for being well-formed and for differing in exactly
 * the way the probe is asking the JVM about.
 */
class CapabilityProbeClassesTest {

    @Test
    void bothVersionsAreWellFormedClasses() throws Exception {
        byte[] v1 = generate("probe/One", false);
        byte[] v2 = generate("probe/One", true);

        assertDoesNotThrow(() -> verify(v1), "v1 must load, or the probe reads as inconclusive");
        assertDoesNotThrow(() -> verify(v2), "v2 must load, or the redefinition never happens");
    }

    /**
     * The probe asks one question: does this JVM accept a redefinition that
     * adds a member. If the two versions did not differ that way, every JVM
     * would answer yes and the agent would install the wrong engine
     * everywhere.
     */
    @Test
    void theSecondVersionAddsAMethodAndTheFirstDoesNot() throws Exception {
        List<String> before = methodsIn(generate("probe/Two", false));
        List<String> after = methodsIn(generate("probe/Two", true));

        assertTrue(before.contains("probeMethod"), before.toString());
        assertFalse(before.contains("addedMethod"), "v1 is the one without it: " + before);
        assertTrue(after.contains("addedMethod"), "v2 has to be a structural change: " + after);
        assertTrue(after.containsAll(before), "v2 must keep everything v1 had");
    }

    /**
     * The generated class has to be loadable and callable, not merely
     * verifiable: the probe defines v1 into the JVM before redefining it.
     */
    @Test
    void theGeneratedClassLoadsAndItsMethodAnswers() throws Exception {
        byte[] v1 = generate("probe/Three", false);
        Class<?> loaded = new Definer().define("probe.Three", v1);

        Object instance = loaded.getDeclaredConstructor().newInstance();
        assertEquals("v1", loaded.getMethod("probeMethod").invoke(instance),
                "the constant is what tells the two versions apart at runtime");
    }

    /** The probe defines its class before redefining it, so this does the same. */
    private static final class Definer extends ClassLoader {
        Definer() {
            super(CapabilityProbeClassesTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }

    /** Reaches the private generator the probe uses, so the test covers the shipped code. */
    private static byte[] generate(String internalName, boolean withAddedMethod) throws Exception {
        Method m = JvmCapabilityProbe.class.getDeclaredMethod(
                "probeClass", String.class, boolean.class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, internalName, withAddedMethod);
    }

    private static void verify(byte[] bytecode) {
        CheckClassAdapter.verify(new ClassReader(bytecode), false,
                new java.io.PrintWriter(java.io.OutputStream.nullOutputStream()));
    }

    private static List<String> methodsIn(byte[] bytecode) {
        List<String> names = new ArrayList<>();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                if (!"<init>".equals(name)) names.add(name);
                return null;
            }
        }, 0);
        return names;
    }
}
