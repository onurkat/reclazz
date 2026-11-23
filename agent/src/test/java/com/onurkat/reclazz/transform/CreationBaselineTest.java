/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** R34 audit round 2: the creation digest follows lambda targets and is stable for parameter annotations. */
class CreationBaselineTest {
    @Retention(RetentionPolicy.RUNTIME) @interface Injected { String value(); }

    static class LambdaOwner {
        final String prefix;
        LambdaOwner() { prefix = ((Supplier<String>) this::seed).get(); }
        String seed() { return "creation-old"; }
        Object product() { return prefix; }
    }
    static class ParameterOwner {
        final String value;
        ParameterOwner(@Injected("parameter-old") String value) { this.value = value; }
        Object product() { return value; }
    }

    @Test void aMethodReachedThroughAConstructorMethodReferenceIsPartOfTheDigest() throws Exception {
        byte[] original = bytes(LambdaOwner.class);
        var node = new ClassNode(); new ClassReader(original).accept(node, 0);
        for (var method : node.methods) if (method.name.equals("seed"))
            for (var insn : method.instructions) if (insn instanceof LdcInsnNode ldc) ldc.cst = "creation-new";
        var writer = new ClassWriter(0); node.accept(writer);
        assertNotEquals(CreationBaseline.digest(original), CreationBaseline.digest(writer.toByteArray()));
    }

    @Test void parameterAnnotationsGiveTheSameDigestForTheSameBytes() throws Exception {
        byte[] original = bytes(ParameterOwner.class);
        assertEquals(CreationBaseline.digest(original), CreationBaseline.digest(original.clone()));
        var node = new ClassNode(); new ClassReader(original).accept(node, 0);
        for (var method : node.methods) if (method.name.equals("<init>"))
            method.visibleParameterAnnotations[0].get(0).values.set(1, "parameter-new");
        var writer = new ClassWriter(0); node.accept(writer);
        assertNotEquals(CreationBaseline.digest(original), CreationBaseline.digest(writer.toByteArray()), "an injection value change is a change");
    }

    @Retention(RetentionPolicy.RUNTIME) @interface Names { String[] value(); }
    static class ArrayOwner {
        @Names({"a,b", "c"}) Object names;
        Object product() { return names; }
    }
    /** Round 3 F1b: {"a,b","c"} and {"a","b,c"} are different injection metadata and must not collide. */
    @Test void annotationArraysWithSeparatorsInValuesDoNotCollide() throws Exception {
        byte[] original = bytes(ArrayOwner.class);
        var node = new ClassNode(); new ClassReader(original).accept(node, 0);
        for (var field : node.fields) if (field.name.equals("names"))
            field.visibleAnnotations.get(0).values.set(1, java.util.List.of("a", "b,c"));
        var writer = new ClassWriter(0); node.accept(writer);
        assertNotEquals(CreationBaseline.digest(original), CreationBaseline.digest(writer.toByteArray()));
    }

    private static byte[] bytes(Class<?> type) throws Exception {
        try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) { return Objects.requireNonNull(in).readAllBytes(); }
    }
}
