/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deciding which added methods are handlers at all.
 *
 * <p>The added-endpoint reporting used to fire on every added method: a
 * reload that added a private helper, or the {@code lambda$} synthetics an
 * edited body brings along, ended in "a handler method added by this reload
 * ... needs a restart" about handlers that never existed (measured on every
 * lambda edit in a controller). The filter held here reads the mapping
 * annotations from the bytecode, so only methods that actually carry one are
 * mapped, claimed, or warned about.
 *
 * <p>The fixture bytecode is built with ASM rather than compiled, because the
 * check is a descriptor-string comparison and must work without Spring on the
 * classpath, exactly as it does inside the agent.
 */
class SpringMvcReloaderMappedMethodsTest {

    private static final String GET_MAPPING =
            "Lorg/springframework/web/bind/annotation/GetMapping;";
    private static final String REQUEST_MAPPING =
            "Lorg/springframework/web/bind/annotation/RequestMapping;";
    private static final String UNRELATED =
            "Lcom/example/SomethingElseMapping;";

    @Test
    void onlyMethodsCarryingAMappingAnnotationCount() {
        byte[] bytecode = classWith(
                method("handler", "()Ljava/lang/String;", GET_MAPPING),
                method("helper", "(Ljava/lang/String;)Ljava/lang/String;", null),
                method("lambda$handler$0", "()Ljava/lang/String;", null));

        Set<String> added = Set.of(
                "handler:()Ljava/lang/String;",
                "helper:(Ljava/lang/String;)Ljava/lang/String;",
                "lambda$handler$0:()Ljava/lang/String;");

        assertEquals(Set.of("handler:()Ljava/lang/String;"),
                SpringMvcReloader.mappedMethodsAmong(added, bytecode),
                "the helper and the lambda synthetic are not handlers and must not be counted");
    }

    @Test
    void requestMappingCountsAndForeignMappingSuffixesDoNot() {
        byte[] bytecode = classWith(
                method("legacy", "()V", REQUEST_MAPPING),
                method("decoy", "()V", UNRELATED));

        Set<String> added = Set.of("legacy:()V", "decoy:()V");

        assertEquals(Set.of("legacy:()V"),
                SpringMvcReloader.mappedMethodsAmong(added, bytecode),
                "only Spring's own web.bind.annotation package marks a handler");
    }

    @Test
    void aReloadWithoutHandlerAddsIsSilentByReturningEmpty() {
        byte[] bytecode = classWith(method("helper", "()V", null));

        assertTrue(SpringMvcReloader.mappedMethodsAmong(Set.of("helper:()V"), bytecode).isEmpty(),
                "an empty answer is what keeps the false restart warning from printing");
        assertTrue(SpringMvcReloader.mappedMethodsAmong(Set.of(), bytecode).isEmpty());
        assertTrue(SpringMvcReloader.mappedMethodsAmong(null, bytecode).isEmpty());
        assertTrue(SpringMvcReloader.mappedMethodsAmong(Set.of("helper:()V"), null).isEmpty());
    }

    // ── fixture bytecode ──────────────────────────────────────────────────

    private record MethodSpec(String name, String descriptor, String annotationDesc) {
    }

    private static MethodSpec method(String name, String descriptor, String annotationDesc) {
        return new MethodSpec(name, descriptor, annotationDesc);
    }

    private static byte[] classWith(MethodSpec... methods) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Controller", null,
                "java/lang/Object", null);
        for (MethodSpec spec : methods) {
            MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, spec.name,
                    spec.descriptor, null, null);
            if (spec.annotationDesc != null) {
                mv.visitAnnotation(spec.annotationDesc, true).visitEnd();
            }
            mv.visitCode();
            if (spec.descriptor.endsWith(")V")) {
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(0, 1);
            } else {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(1, 1);
            }
            mv.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }
}
