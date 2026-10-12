/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.bootstrap.MvcSingletonBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

public class MvcSingletonTransformerTest {
    @AfterEach void clear() { MvcSingletonBridge.clearForTests(); }
    protected byte[] nativeBytes(String name) throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream(name + ".class")) { assertNotNull(in); return in.readAllBytes(); }
    }
    private static ClassNode node(byte[] bytes) { var n = new ClassNode(); new ClassReader(bytes).accept(n, 0); return n; }
    private byte[] transform(String name, byte[] bytes) { return new MvcSingletonTransformer().transform(getClass().getClassLoader(), name, null, null, bytes); }
    @ParameterizedTest @ValueSource(strings = {MvcSingletonTransformer.MAPPING, MvcSingletonTransformer.SERVLET})
    void nativeSchemaAndSiteCountsArePreserved(String target) throws Exception {
        ClassNode before = node(nativeBytes(target)); byte[] bytes = transform(target, nativeBytes(target)); assertNotNull(bytes);
        ClassNode after = node(bytes);
        assertEquals(before.name, after.name); assertEquals(before.superName, after.superName); assertEquals(before.interfaces, after.interfaces);
        assertEquals(before.fields.stream().map(f -> f.access + f.name + f.desc).toList(), after.fields.stream().map(f -> f.access + f.name + f.desc).toList());
        assertEquals(before.methods.stream().map(m -> m.access + m.name + m.desc).toList(), after.methods.stream().map(m -> m.access + m.name + m.desc).toList());
        long calls = after.methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                .filter(i -> i instanceof MethodInsnNode c && c.owner.equals("com/onurkat/reclazz/bootstrap/MvcSingletonBridge")).count();
        assertEquals(target.equals(MvcSingletonTransformer.MAPPING) ? 2 : 4, calls);
    }
    @ParameterizedTest @ValueSource(strings = {"mapping-missing", "mapping-extra", "mapping-owner", "mapping-descriptor", "mapping-method", "mapping-signature", "servlet-missing", "servlet-extra", "servlet-owner", "servlet-descriptor", "servlet-method", "servlet-signature"})
    void unknownSitesRefuseTheWholeTarget(String mutation) throws Exception {
        boolean mapping = mutation.startsWith("mapping"); String target = mapping ? MvcSingletonTransformer.MAPPING : MvcSingletonTransformer.SERVLET;
        ClassNode n = node(nativeBytes(target)); boolean found = false;
        outer: for (var m : n.methods) for (var i : m.instructions.toArray()) {
            boolean candidate = mapping ? m.name.equals("getHandlerExecutionChain") && i instanceof MethodInsnNode c && c.name.equals("addInterceptor")
                    : m.name.equals("getHandler") && i instanceof FieldInsnNode f && f.name.equals("handlerMappings");
            if (!candidate) continue;
            switch (mutation.substring(mutation.indexOf('-') + 1)) {
                case "missing" -> m.instructions.remove(i);
                case "extra" -> m.instructions.insert(i, i.clone(null));
                case "owner" -> { if (i instanceof MethodInsnNode c) c.owner = "com/example/Chain"; else ((FieldInsnNode) i).owner = "com/example/Servlet"; }
                case "descriptor" -> { if (i instanceof MethodInsnNode c) c.desc = "(Ljava/lang/Object;)V"; else ((FieldInsnNode) i).desc = "Ljava/util/Collection;"; }
                case "method" -> m.name = "unsupported";
                case "signature" -> m.desc = "()V";
                default -> fail(mutation);
            }
            found = true; break outer;
        }
        assertTrue(found); var writer = new ClassWriter(0); n.accept(writer); assertNull(transform(target, writer.toByteArray()), mutation);
    }
}
