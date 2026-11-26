/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.transform.KeepBaseline;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import static org.objectweb.asm.Opcodes.*;

/** Proves that running the physical constructor followed by the added stores is equivalent. */
final class SpringConstructorExtension {
    record Slot(String field, String descriptor, String qualifier) { }
    private SpringConstructorExtension() { }

    static List<Slot> assignments(ClassNode owner, java.lang.reflect.Constructor<?> original, KeepBaseline.Fingerprint baseline) {
        String physical = Type.getConstructorDescriptor(original);
        List<MethodNode> constructors = owner.methods.stream().filter(m -> m.name.equals("<init>")).toList();
        require(constructors.size() == 1, "multiple constructors");
        MethodNode constructor = constructors.get(0);
        Type[] old = Type.getArgumentTypes(physical), args = Type.getArgumentTypes(constructor.desc);
        require(args.length > old.length && Arrays.equals(old, Arrays.copyOf(args, old.length)), "removed or reordered constructor arguments");
        for (int i = 0; i < old.length; i++) {
            var parameter = original.getParameters()[i];
            if (parameter.isNamePresent()) require(constructor.parameters != null
                    && parameter.getName().equals(constructor.parameters.get(i).name), "existing dependency parameter name changed");
            String qualifier = null;
            for (var annotation : parameter.getDeclaredAnnotations()) {
                require(annotation.annotationType().getName().equals("org.springframework.beans.factory.annotation.Qualifier"),
                        "unsupported existing dependency annotation");
                try { qualifier = (String) annotation.annotationType().getMethod("value").invoke(annotation); }
                catch (ReflectiveOperationException unavailable) { throw new IllegalArgumentException(unavailable); }
            }
            require(Objects.equals(qualifier, qualifier(constructor, i)), "existing dependency qualifier changed");
        }
        require(constructor.tryCatchBlocks.isEmpty() && constructor.signature == null, "generic or guarded constructor");
        Map<Integer,Integer> positions = new HashMap<>();
        int local = 1;
        for (int i = 0; i < args.length; i++) {
            positions.put(local, i);
            local += args[i].getSize();
        }
        List<Slot> added = new ArrayList<>();
        Set<String> fields = new HashSet<>();
        Set<Integer> assigned = new HashSet<>();
        var instructions = constructor.instructions;
        var code = Arrays.stream(instructions.toArray()).filter(n -> n.getOpcode() >= 0).toList();
        require(!code.isEmpty() && self(code.get(0)), "different superclass initialization");
        int at = 1;
        while (at < code.size() && code.get(at) instanceof VarInsnNode load && positions.containsKey(load.var)
                && positions.get(load.var) < old.length && load.getOpcode() == args[positions.get(load.var)].getOpcode(ILOAD)) at++;
        require(at < code.size() && code.get(at++) instanceof MethodInsnNode call && call.getOpcode() == INVOKESPECIAL
                && call.name.equals("<init>") && call.owner.equals(owner.superName), "constructor delegates or runs custom logic");
        while (at + 2 < code.size()) {
            AbstractInsnNode receiver = code.get(at++), value = code.get(at++), store = code.get(at++);
            require(self(receiver) && value instanceof VarInsnNode && store instanceof FieldInsnNode, "constructor body contains logic");
            VarInsnNode load = (VarInsnNode) value;
            FieldInsnNode field = (FieldInsnNode) store;
            Integer index = positions.get(load.var);
            require(index != null && load.getOpcode() == args[index].getOpcode(ILOAD)
                    && field.getOpcode() == PUTFIELD && field.owner.equals(owner.name) && fields.add(field.name), "unsupported constructor assignment");
            if (index >= old.length) {
                require(assigned.add(index) && args[index].getSort() == Type.OBJECT && field.desc.equals(args[index].getDescriptor())
                        && !baseline.fields().contains(field.name + ":" + field.desc)
                        && owner.fields.stream().anyMatch(f -> f.name.equals(field.name) && f.desc.equals(field.desc) && (f.access & ACC_STATIC) == 0),
                        "new argument must initialize a new dependency field");
                added.add(new Slot(field.name, field.desc, qualifier(constructor, index)));
                instructions.remove(receiver);
                instructions.remove(value);
                instructions.remove(store);
            }
        }
        require(at == code.size() - 1 && code.get(at).getOpcode() == RETURN && assigned.size() == args.length - old.length,
                "constructor contains non-assignment logic or unused dependencies");
        constructor.desc = physical;
        require(Objects.equals(baseline.constructors().get(physical), KeepBaseline.digest(constructor)),
                "previous constructor work changed");
        return List.copyOf(added);
    }

    private static String qualifier(MethodNode constructor, int index) {
        var annotations = constructor.visibleParameterAnnotations;
        String result = null;
        if (annotations != null && annotations[index] != null) for (var a : annotations[index]) {
            require(a.desc.equals("Lorg/springframework/beans/factory/annotation/Qualifier;")
                    && a.values != null && a.values.size() == 2 && "value".equals(a.values.get(0))
                    && a.values.get(1) instanceof String && !((String) a.values.get(1)).isBlank(), "unsupported dependency annotation");
            result = (String) a.values.get(1);
        }
        return result;
    }

    private static boolean self(AbstractInsnNode n) {
        return n instanceof VarInsnNode v && v.getOpcode() == ALOAD && v.var == 0;
    }

    static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
