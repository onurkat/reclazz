/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.bootstrap.ExceptionHandlerBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Actual dependency bytecode, not a hand-built approximation of the resolver. */
public class ExceptionHandlerTransformerTest {
    protected int constructorFactories() { return 0; }
    protected byte[] nativeResolver() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(ExceptionHandlerTransformer.TARGET + ".class")) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }
    protected byte[] transform(byte[] bytes) {
        return new ExceptionHandlerTransformer().transform(getClass().getClassLoader(),
                ExceptionHandlerTransformer.TARGET, null, null, bytes);
    }
    protected static ClassNode node(byte[] bytes) {
        var node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node;
    }
    protected static byte[] bytes(ClassNode node) {
        var writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }
    @Test void actualResolverIsAdaptedWithoutChangingItsRetransformationSchema() throws Exception {
        ClassNode before = node(nativeResolver());
        byte[] adapted = transform(nativeResolver()); assertNotNull(adapted);
        ClassNode after = node(adapted);
        assertEquals(before.name, after.name);
        assertEquals(before.superName, after.superName);
        assertEquals(before.interfaces, after.interfaces);
        assertEquals(before.fields.stream().map(f -> f.access + ":" + f.name + f.desc).toList(),
                after.fields.stream().map(f -> f.access + ":" + f.name + f.desc).toList());
        assertEquals(before.methods.stream().map(m -> m.access + ":" + m.name + m.desc).toList(),
                after.methods.stream().map(m -> m.access + ":" + m.name + m.desc).toList());
        for (String name : List.of("metadataClass", "receiver", "controllerClass", "resolverFactory")) {
            long count = after.methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                    .filter(i -> i instanceof MethodInsnNode call && call.owner.equals("com/onurkat/reclazz/bootstrap/ExceptionHandlerBridge")
                            && call.name.equals(name)).count();
            int expected = switch (name) {
                case "metadataClass" -> 2 - constructorFactories();
                case "receiver" -> 2;
                case "controllerClass" -> 1;
                default -> constructorFactories();
            };
            assertEquals(expected, count, name);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"constructor", "receiver", "controller"})
    void missingRequiredSiteRefusesTheWholeTransformation(String site) throws Exception {
        ClassNode changed = node(nativeResolver()); boolean found = false;
        outer: for (var method : changed.methods) for (var instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            boolean matches = switch (site) {
                case "constructor" -> call.owner.endsWith("/ExceptionHandlerMethodResolver") && call.name.equals("<init>");
                case "receiver" -> call.owner.endsWith("/ServletInvocableHandlerMethod") && call.name.equals("<init>");
                default -> call.owner.endsWith("/HandlerMethod") && call.name.equals("getBeanType");
            };
            if (matches) { call.name = "unsupported"; found = true; break outer; }
        }
        assertTrue(found); assertNull(transform(bytes(changed)), site);
    }
    @Test void unrelatedClassIsUntouched() throws Exception {
        assertNull(new ExceptionHandlerTransformer().transform(getClass().getClassLoader(), "com/example/Other", null, null, nativeResolver()));
    }
    static class Owner { }
    static class FirstMetadata { }
    static class SecondMetadata { }
    @Test void factoryUsesCurrentMetadataAndPreservesTheOriginalFactoryResultAndFailure() {
        AtomicInteger calls = new AtomicInteger(); var seen = new java.util.ArrayList<Class<?>>(); Object result = new Object();
        Function<Class<?>, Object> original = type -> { calls.incrementAndGet(); seen.add(type); return result; };
        Function<Class<?>, Object> adapted = ExceptionHandlerBridge.resolverFactory(original);
        try {
            assertSame(result, adapted.apply(Owner.class));
            ExceptionHandlerBridge.replace(Owner.class, FirstMetadata.class, null, new java.lang.invoke.MethodHandle[0]);
            assertSame(result, adapted.apply(Owner.class));
            ExceptionHandlerBridge.replace(Owner.class, SecondMetadata.class, null, new java.lang.invoke.MethodHandle[0]);
            assertSame(result, adapted.apply(Owner.class));
            ExceptionHandlerBridge.replace(Owner.class, null, null, new java.lang.invoke.MethodHandle[0]);
            assertSame(result, adapted.apply(Owner.class));
            assertEquals(List.of(Owner.class, FirstMetadata.class, SecondMetadata.class, Owner.class), seen);
            assertEquals(4, calls.get());
            var failure = new IllegalStateException("native factory failure");
            Function<Class<?>, Object> failing = ExceptionHandlerBridge.resolverFactory(type -> { throw failure; });
            assertSame(failure, assertThrows(IllegalStateException.class, () -> failing.apply(Owner.class)));
        } finally {
            ExceptionHandlerBridge.replace(Owner.class, null, null, new java.lang.invoke.MethodHandle[0]);
        }
    }
}
