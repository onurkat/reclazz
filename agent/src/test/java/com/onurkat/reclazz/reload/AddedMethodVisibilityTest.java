/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.transform.TransformContext.MethodSig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which added methods are worth telling the developer about.
 *
 * <p>The rule is not "anything added": a private helper added beside the method
 * that calls it is nobody's business, and saying so on every reload would train
 * the developer to stop reading. It is the methods somebody else was going to
 * come looking for, and will not find.
 */
class AddedMethodVisibilityTest {

    /** A class with the given methods, so the check has real bytecode to read. */
    private static byte[] classWith(Method... methods) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "app/Sample", null,
                "java/lang/Object", null);
        for (Method method : methods) {
            MethodVisitor mv = writer.visitMethod(
                    method.access, method.name, method.descriptor, null, null);
            if (method.annotation != null) {
                mv.visitAnnotation(method.annotation, true).visitEnd();
            }
            mv.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private record Method(int access, String name, String descriptor, String annotation) {
    }

    private static Method plain(String name, String descriptor) {
        return new Method(Opcodes.ACC_PUBLIC, name, descriptor, null);
    }

    private static List<MethodSig> added(String name, String descriptor) {
        return List.of(new MethodSig(name, descriptor, Opcodes.ACC_PUBLIC));
    }

    @Test
    void anAddedBeanMethodIsReported() {
        byte[] bytes = classWith(new Method(Opcodes.ACC_PUBLIC, "dataSource",
                "()Ljava/lang/Object;", "Lorg/springframework/context/annotation/Bean;"));

        List<AddedMethodVisibility.Unseen> unseen =
                AddedMethodVisibility.check(bytes, added("dataSource", "()Ljava/lang/Object;"));

        assertEquals(1, unseen.size());
        assertEquals("dataSource()", unseen.get(0).method());
        assertTrue(unseen.get(0).reason().contains("@Bean"));
    }

    @Test
    void anAddedGetterIsReportedForSerialisation() {
        byte[] bytes = classWith(plain("getEmail", "()Ljava/lang/String;"));

        List<AddedMethodVisibility.Unseen> unseen =
                AddedMethodVisibility.check(bytes, added("getEmail", "()Ljava/lang/String;"));

        assertEquals(1, unseen.size());
        assertTrue(unseen.get(0).reason().contains("serialisation"),
                () -> unseen.get(0).reason());
    }

    /**
     * The endpoint adapter hands the mapping scan a class carrying a copy of
     * the method, so an added endpoint really does answer. Warning about it
     * would be telling the developer something untrue.
     */
    @Test
    void anAddedRequestMappingIsNotReportedBecauseItIsCarried() {
        byte[] bytes = classWith(new Method(Opcodes.ACC_PUBLIC, "handle",
                "()Ljava/lang/String;",
                "Lorg/springframework/web/bind/annotation/GetMapping;"));

        assertEquals(List.of(),
                AddedMethodVisibility.check(bytes, added("handle", "()Ljava/lang/String;")));
    }

    @Test
    void anOrdinaryAddedMethodIsNobodysBusiness() {
        byte[] bytes = classWith(plain("recompute", "()V"));

        assertEquals(List.of(), AddedMethodVisibility.check(bytes, added("recompute", "()V")));
    }

    @Test
    void aPrivateGetterIsNotAProperty() {
        byte[] bytes = classWith(new Method(Opcodes.ACC_PRIVATE, "getSecret",
                "()Ljava/lang/String;", null));

        assertEquals(List.of(),
                AddedMethodVisibility.check(bytes, added("getSecret", "()Ljava/lang/String;")));
    }

    @Test
    void aGetterTakingArgumentsIsNotAProperty() {
        byte[] bytes = classWith(plain("getAt", "(I)Ljava/lang/String;"));

        assertEquals(List.of(),
                AddedMethodVisibility.check(bytes, added("getAt", "(I)Ljava/lang/String;")));
    }

    /** isX only counts when it answers a boolean, which is what a property is. */
    @Test
    void anIsMethodReturningSomethingElseIsNotAProperty() {
        byte[] bytes = classWith(plain("island", "()Ljava/lang/String;"));

        assertEquals(List.of(),
                AddedMethodVisibility.check(bytes, added("island", "()Ljava/lang/String;")));
        assertEquals(1, AddedMethodVisibility.check(
                classWith(plain("isActive", "()Z")), added("isActive", "()Z")).size());
    }

    @Test
    void aMethodThatWasAlreadyThereIsNotAnAddition() {
        byte[] bytes = classWith(plain("getName", "()Ljava/lang/String;"),
                plain("getEmail", "()Ljava/lang/String;"));

        List<AddedMethodVisibility.Unseen> unseen =
                AddedMethodVisibility.check(bytes, added("getEmail", "()Ljava/lang/String;"));

        assertEquals(1, unseen.size(), "only the added one is reported");
        assertEquals("getEmail()", unseen.get(0).method());
    }

    @Test
    void nothingAddedSaysNothing() {
        assertEquals(List.of(),
                AddedMethodVisibility.check(classWith(plain("getName", "()Ljava/lang/String;")),
                        List.of()));
    }

    @Test
    void unreadableBytecodeSaysNothingRatherThanFailingTheReload() {
        assertEquals(List.of(),
                AddedMethodVisibility.check(new byte[]{1, 2, 3},
                        added("getEmail", "()Ljava/lang/String;")));
    }
}
