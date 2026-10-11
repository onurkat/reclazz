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
import static org.objectweb.asm.Opcodes.*;

class JakartaExceptionHandlerTransformerTest extends ExceptionHandlerTransformerTest {
    @Override protected int constructorFactories() { return 1; }

    @ParameterizedTest
    @ValueSource(strings = {"name", "descriptor", "bootstrap", "bootstrapTag", "bootstrapDescriptor", "arity",
            "sam", "constructorTag", "constructorOwner", "constructorDescriptor", "constructorInterface", "instantiated", "method", "duplicate", "missing"})
    void unknownFactoryShapeRefusesTheWholeTransformation(String mutation) throws Exception {
        ClassNode changed = node(nativeResolver()); boolean found = false;
        outer: for (var method : changed.methods) for (var instruction : method.instructions.toArray()) {
            if (!(instruction instanceof InvokeDynamicInsnNode call) || !call.name.equals("apply")) continue;
            assertEquals("getExceptionHandlerMethod", method.name);
            Handle ctor = (Handle) call.bsmArgs[1];
            switch (mutation) {
                case "name" -> call.name = "unsupported";
                case "descriptor" -> call.desc = "()Ljava/util/function/Supplier;";
                case "bootstrap" -> call.bsm = new Handle(H_INVOKESTATIC, call.bsm.getOwner(), "altMetafactory", call.bsm.getDesc(), false);
                case "bootstrapTag" -> call.bsm = new Handle(H_INVOKEVIRTUAL, call.bsm.getOwner(), call.bsm.getName(), call.bsm.getDesc(), false);
                case "bootstrapDescriptor" -> call.bsm = new Handle(H_INVOKESTATIC, call.bsm.getOwner(), call.bsm.getName(), "()V", false);
                case "arity" -> call.bsmArgs = java.util.Arrays.copyOf(call.bsmArgs, 4);
                case "sam" -> call.bsmArgs[0] = Type.getMethodType("(Ljava/lang/Class;)Ljava/lang/Object;");
                case "constructorTag" -> call.bsmArgs[1] = new Handle(H_INVOKESTATIC, ctor.getOwner(), ctor.getName(), ctor.getDesc(), false);
                case "constructorOwner" -> call.bsmArgs[1] = new Handle(H_NEWINVOKESPECIAL, "com/example/Resolver", ctor.getName(), ctor.getDesc(), false);
                case "constructorDescriptor" -> call.bsmArgs[1] = new Handle(H_NEWINVOKESPECIAL, ctor.getOwner(), ctor.getName(), "(Ljava/lang/Object;)V", false);
                case "constructorInterface" -> call.bsmArgs[1] = new Handle(H_NEWINVOKESPECIAL, ctor.getOwner(), ctor.getName(), ctor.getDesc(), true);
                case "instantiated" -> call.bsmArgs[2] = Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;");
                case "method" -> method.name = "unsupportedMethod";
                case "duplicate" -> method.instructions.insert(call, call.clone(null));
                case "missing" -> method.instructions.set(call, new InsnNode(ACONST_NULL));
                default -> fail(mutation);
            }
            // Extra bootstrap argument is intentionally unsupported but must be serializable.
            if (mutation.equals("arity")) call.bsmArgs[3] = 0;
            found = true; break outer;
        }
        assertTrue(found); assertNull(transform(bytes(changed)), mutation);
    }
}
