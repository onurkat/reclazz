/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AddedCallbackAdviceTest {
    private static final String TX = "Lorg/springframework/transaction/annotation/Transactional;";
    private static final List<String> ADVICE = List.of(TX,
            "Lorg/springframework/cache/annotation/Cacheable;",
            "Lorg/springframework/cache/annotation/CachePut;",
            "Lorg/springframework/cache/annotation/CacheEvict;",
            "Lorg/springframework/cache/annotation/Caching;");

    @Test void publicCallbacksAcceptTheSupportedOperationAnnotations() {
        for (boolean event : List.of(false, true)) for (String advice : ADVICE) {
            assertEquals(1, accepted(fixture(event, advice, Opcodes.ACC_PUBLIC, false), event), advice);
        }
    }

    @Test void advisedPrivateFinalAndGenericCallbacksAreRefused() {
        for (boolean event : List.of(false, true)) for (String advice : ADVICE) {
            for (int access : List.of(Opcodes.ACC_PRIVATE, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL))
                assertEquals(0, accepted(fixture(event, advice, access, false), event));
            var generic = fixture(event, advice, Opcodes.ACC_PUBLIC, false);
            generic.methods.get(0).signature = event ? "<T:Ljava/lang/Object;>(Ljava/lang/String;)V" : "<T:Ljava/lang/Object;>()V";
            assertEquals(0, accepted(generic, event));
            var finalOwner = fixture(event, advice, Opcodes.ACC_PUBLIC, false);
            finalOwner.access |= Opcodes.ACC_FINAL;
            assertEquals(0, accepted(finalOwner, event));
            var inherited = fixture(event, advice, Opcodes.ACC_PUBLIC, false);
            inherited.superName = "app/Parent";
            assertEquals(0, accepted(inherited, event));
            var implementing = fixture(event, advice, Opcodes.ACC_PUBLIC, false);
            implementing.interfaces.add("java/io/Serializable");
            assertEquals(0, accepted(implementing, event));
        }
    }

    @Test void asyncIsLimitedToOrdinaryPublicVoidEvents() {
        for (boolean event : List.of(false, true)) {
            assertEquals(event ? 1 : 0, accepted(fixture(event, "Lorg/springframework/scheduling/annotation/Async;", Opcodes.ACC_PUBLIC, false), event));
        }
        assertEquals(0, accepted(fixture(true, TX, Opcodes.ACC_PUBLIC, true), true));
        String async = "Lorg/springframework/scheduling/annotation/Async;";
        assertEquals(0, accepted(fixture(true, async, Opcodes.ACC_PUBLIC, true), true));
        assertEquals(0, accepted(fixture(true, async, Opcodes.ACC_PRIVATE, false), true));
        var returning = fixture(true, async, Opcodes.ACC_PUBLIC, false);
        returning.methods.get(0).desc = "(Ljava/lang/String;)Ljava/lang/Object;";
        assertEquals(0, accepted(returning, true));
    }

    private static int accepted(ClassNode node, boolean event) {
        var writer = new ClassWriter(0); node.accept(writer);
        var method = node.methods.get(0);
        Set<String> added = Set.of(method.name + ":" + method.desc);
        return event ? AddedEventListenerAdapter.inspect(writer.toByteArray(), added).methods().size()
                : AddedScheduledAdapter.inspect(writer.toByteArray(), added).methods().size();
    }
    private static ClassNode fixture(boolean event, String advice, int access, boolean phase) {
        var node = new ClassNode(); node.version = Opcodes.V17; node.access = Opcodes.ACC_PUBLIC;
        node.name = "app/Callbacks"; node.superName = "java/lang/Object";
        var method = new MethodNode(access, "callback", event ? "(Ljava/lang/String;)V" : "()V", null, null);
        method.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(event
                ? (phase ? AddedEventListenerAdapter.TRANSACTIONAL : AddedEventListenerAdapter.EVENT)
                : AddedScheduledAdapter.SCHEDULED), new AnnotationNode(advice)));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method); return node;
    }
}
