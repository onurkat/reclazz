/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.objectweb.asm.Opcodes.*;

/** Actual isolated Spring6/7 bytes with the same schema and refusal assertions. */
class JakartaMvcSingletonTransformerTest extends MvcSingletonTransformerTest {
    @Override protected int mappingSites() {
        try {
            Class.forName(MvcSingletonTransformer.MAPPING.replace('/', '.')
                    + "$ApiVersionDeprecationHandlerInterceptor");
            return 3;
        } catch (ClassNotFoundException legacy) { return 2; }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingThird", "constructorOwner", "constructorDescriptor", "newOwner"})
    void changedVersionInterceptorRefusesTheWholeTarget(String mutation) throws Exception {
        assumeTrue(mappingSites() == 3, "Native Spring6 has no version interceptor");
        var n = new ClassNode();
        new ClassReader(nativeBytes(MvcSingletonTransformer.MAPPING)).accept(n, 0);
        boolean changed = false;
        outer: for (var method : n.methods) {
            if (!method.name.equals("getHandlerExecutionChain")) continue;
            for (var instruction : method.instructions.toArray()) {
                if (mutation.equals("newOwner") && instruction instanceof TypeInsnNode type
                        && type.getOpcode() == NEW && type.desc.endsWith("$ApiVersionDeprecationHandlerInterceptor")) {
                    type.desc = "com/example/OtherInterceptor"; changed = true; break outer;
                }
                if (!(instruction instanceof MethodInsnNode call)
                        || !call.owner.endsWith("$ApiVersionDeprecationHandlerInterceptor")) continue;
                if (mutation.equals("constructorOwner")) call.owner = "com/example/OtherInterceptor";
                else if (mutation.equals("constructorDescriptor")) call.desc = "(Ljava/lang/Object;Ljava/lang/Comparable;)V";
                else if (mutation.equals("missingThird")) {
                    var next = call.getNext();
                    while (next != null && !(next instanceof MethodInsnNode)) next = next.getNext();
                    assertNotNull(next); assertEquals("addInterceptor", ((MethodInsnNode)next).name);
                    method.instructions.remove(next);
                } else fail(mutation);
                changed = true; break outer;
            }
        }
        assertTrue(changed);
        var writer = new ClassWriter(0); n.accept(writer);
        assertNull(new MvcSingletonTransformer().transform(getClass().getClassLoader(),
                MvcSingletonTransformer.MAPPING, null, null, writer.toByteArray()), mutation);
    }
}
