/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The reflection intercept sees every class the JVM loads, and decides from
 * the constant pool alone whether a class can call one of the intercepted
 * methods. That answer has to match what a walk over the instructions would
 * say, in both directions: a missed class keeps calling the JDK and never
 * sees a structurally added member, and a false hit is only a wasted pass.
 */
class ReflectionInterceptPrefilterTest extends TransformTestBase {

    private static final String BRIDGE = "com/onurkat/reclazz/bootstrap/ReflectionBridge";

    @Test
    void aClassThatCallsAnInterceptedMethodIsStillRewritten() throws Exception {
        Map<String, byte[]> compiled = compile(new SourceFile("Asks",
                "public class Asks {\n"
                + "    public static int count(Class<?> c) { return c.getDeclaredMethods().length; }\n"
                + "}"));
        byte[] out = new ReflectionInterceptTransformer().transform(
                null, "Asks", null, null, compiled.get("Asks"));
        assertNotNull(out, "a getDeclaredMethods call site must be rewritten");
        assertTrue(mentionsClass(out, BRIDGE), "the rewritten call goes through the bridge");
    }

    /**
     * The tempting shortcut, searching the bytes for {@code java/lang/Class},
     * is wrong in this direction: a class that merely takes a {@code Class}
     * parameter carries that string in a descriptor and calls nothing.
     */
    @Test
    void aClassThatOnlyMentionsClassInADescriptorIsLeftAlone() throws Exception {
        Map<String, byte[]> compiled = compile(new SourceFile("Names",
                "public class Names {\n"
                + "    public static String of(Class<?> c) { return c.getName() + c.getSimpleName(); }\n"
                + "}"));
        assertNull(new ReflectionInterceptTransformer().transform(
                null, "Names", null, null, compiled.get("Names")));
        assertFalse(ReflectionInterceptTransformer.constantPoolMentionsTarget(
                new ClassReader(compiled.get("Names"))));
    }

    @Test
    void everyInterceptedFormIsFoundInThePool() throws Exception {
        String[] calls = {
                "c.getDeclaredMethods()", "c.getDeclaredFields()", "c.getDeclaredMethod(\"m\")",
                "c.getDeclaredField(\"f\")", "c.getMethods()", "c.getFields()",
                "c.getMethod(\"m\")", "c.getField(\"f\")"};
        for (int i = 0; i < calls.length; i++) {
            String name = "Form" + i;
            Map<String, byte[]> compiled = compile(new SourceFile(name,
                    "public class " + name + " {\n"
                    + "    public static Object call(Class<?> c) throws Exception { return " + calls[i] + "; }\n"
                    + "}"));
            assertTrue(ReflectionInterceptTransformer.constantPoolMentionsTarget(
                    new ClassReader(compiled.get(name))), calls[i] + " must be found");
        }
    }

    /**
     * Over real library bytecode, the pool answer and the instruction answer
     * agree class for class. The corpus is whatever is at hand: ASM's own jar
     * and the built agent jar, a few hundred classes of assorted shapes,
     * including ones that hold {@code Class} in fields and descriptors.
     */
    @Test
    void agreesWithAnInstructionWalkOverRealJars() throws Exception {
        List<Path> jars = new ArrayList<>();
        CodeSource asm = ClassReader.class.getProtectionDomain().getCodeSource();
        if (asm != null) jars.add(Path.of(asm.getLocation().toURI()));
        String agentJar = System.getProperty("reclazz.agent.jar");
        if (agentJar != null && Files.exists(Path.of(agentJar))) jars.add(Path.of(agentJar));
        assertFalse(jars.isEmpty(), "no jar to walk");

        int classes = 0, hits = 0;
        for (Path jar : jars) {
            try (JarFile jf = new JarFile(jar.toFile())) {
                var entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    if (!e.getName().endsWith(".class") || e.getName().endsWith("module-info.class")) continue;
                    byte[] bytes;
                    try (InputStream in = jf.getInputStream(e)) {
                        bytes = in.readAllBytes();
                    }
                    ClassReader reader = new ClassReader(bytes);
                    boolean pool = ReflectionInterceptTransformer.constantPoolMentionsTarget(reader);
                    boolean walk = instructionsCallTarget(reader);
                    assertEquals(walk, pool, e.getName() + ": pool says " + pool + ", instructions say " + walk);
                    classes++;
                    if (pool) hits++;
                }
            }
        }
        assertTrue(classes > 100, "expected a real corpus, walked " + classes);
        assertTrue(hits > 0, "expected at least one reflective caller among " + classes + " classes");
    }

    /** The oracle: what the transformer used to do, one instruction at a time. */
    private static boolean instructionsCallTarget(ClassReader reader) {
        boolean[] found = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName,
                                                String mDescriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && "java/lang/Class".equals(owner)
                                && ReflectionInterceptTransformer.isTargetMethod(mName, mDescriptor)) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean mentionsClass(byte[] bytes, String internalName) {
        ClassReader r = new ClassReader(bytes);
        char[] buf = new char[r.getMaxStringLength()];
        for (int i = 1; i < r.getItemCount(); i++) {
            int off = r.getItem(i);
            if (off != 0 && r.readByte(off - 1) == 7 && internalName.equals(r.readUTF8(off, buf))) return true;
        }
        return false;
    }
}
