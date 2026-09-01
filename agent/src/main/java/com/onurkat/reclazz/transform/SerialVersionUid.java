/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The serialVersionUID a class would have had if Reclazz had never seen it.
 *
 * <p>A class that does not declare one gets a default computed from its own
 * shape: its name, its modifiers, its interfaces, its non-private members. The
 * load-time transform adds members to that shape, so it changes the number.
 * Measured with the JDK's own {@code ObjectStreamClass}, on a plain
 * Serializable class:
 *
 * <pre>
 *   without the agent  -5455223060129582737
 *   with the agent      2754914338756156902
 * </pre>
 *
 * <p>Which means anything serialized before the agent was attached cannot be
 * read after, and a cluster node running with it cannot exchange objects with
 * one running without: {@code InvalidClassException, local class incompatible}.
 * For an audience whose sessions and caches are serialized, that is not a
 * development-time inconvenience.
 *
 * <p>The obvious repair, hiding the injected members from the computation, is
 * not available. The spec leaves out private static and private transient
 * fields, which the injected fields can be, but it counts every non-private
 * method, and the renamed method bodies have to keep the visibility they had:
 * a private one is bound statically by the verifier, so an override would stop
 * being found. So the number is computed from the original bytes instead and
 * written into the class as an explicit field, which is what a developer who
 * cared about this would have done by hand.
 *
 * <p>This is the algorithm from the Java Object Serialization Specification,
 * section 4.6, and being close is not useful: a wrong number is as unreadable
 * as a changed one. It is checked against {@code ObjectStreamClass} for every
 * shape the tests can think of rather than against a reading of the spec.
 */
public final class SerialVersionUid {

    private SerialVersionUid() {
    }

    /**
     * @return the default UID for these bytes, or null when it cannot be
     *         computed, in which case nothing should be written
     */
    public static Long computeFrom(byte[] bytecode) {
        try {
            Shape shape = read(bytecode);
            if (shape == null) return null;

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);

            out.writeUTF(shape.name);
            out.writeInt(shape.access & (Modifier.PUBLIC | Modifier.FINAL
                    | Modifier.INTERFACE | Modifier.ABSTRACT));

            shape.interfaces.sort(Comparator.naturalOrder());
            for (String each : shape.interfaces) {
                out.writeUTF(each);
            }

            // Excluded: a private field that is also static or transient. Every
            // other field counts, private ones included.
            shape.fields.sort(Comparator.comparing(m -> m.name));
            for (Member field : shape.fields) {
                boolean skipped = Modifier.isPrivate(field.access)
                        && (Modifier.isStatic(field.access) || isTransient(field.access));
                if (skipped) continue;
                out.writeUTF(field.name);
                out.writeInt(field.access & (Modifier.PUBLIC | Modifier.PRIVATE
                        | Modifier.PROTECTED | Modifier.STATIC | Modifier.FINAL
                        | Modifier.VOLATILE | Modifier.TRANSIENT));
                out.writeUTF(field.descriptor);
            }

            if (shape.hasStaticInitialiser) {
                out.writeUTF("<clinit>");
                out.writeInt(Modifier.STATIC);
                out.writeUTF("()V");
            }

            shape.constructors.sort(Comparator.comparing(m -> m.descriptor));
            for (Member constructor : shape.constructors) {
                if (Modifier.isPrivate(constructor.access)) continue;
                out.writeUTF("<init>");
                out.writeInt(constructor.access & METHOD_MODS);
                out.writeUTF(constructor.descriptor.replace('/', '.'));
            }

            shape.methods.sort(Comparator.comparing((Member m) -> m.name)
                    .thenComparing(m -> m.descriptor));
            for (Member method : shape.methods) {
                if (Modifier.isPrivate(method.access)) continue;
                out.writeUTF(method.name);
                out.writeInt(method.access & METHOD_MODS);
                out.writeUTF(method.descriptor.replace('/', '.'));
            }

            out.flush();
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(bytes.toByteArray());
            long uid = 0;
            for (int i = Math.min(7, hash.length - 1); i >= 0; i--) {
                uid = (uid << 8) | (hash[i] & 0xFF);
            }
            return uid;
        } catch (Throwable cannotCompute) {
            return null;
        }
    }

    private static final int METHOD_MODS = Modifier.PUBLIC | Modifier.PRIVATE | Modifier.PROTECTED
            | Modifier.STATIC | Modifier.FINAL | Modifier.SYNCHRONIZED | Modifier.NATIVE
            | Modifier.ABSTRACT | Modifier.STRICT;

    /** ACC_TRANSIENT and Modifier.TRANSIENT share the value 0x0080. */
    private static boolean isTransient(int access) {
        return (access & Modifier.TRANSIENT) != 0;
    }

    /** Whether these bytes already say what their UID is. */
    public static boolean alreadyDeclared(byte[] bytecode) {
        Shape shape = read(bytecode);
        if (shape == null) return true;      // unreadable: write nothing
        for (Member field : shape.fields) {
            if ("serialVersionUID".equals(field.name)) return true;
        }
        return false;
    }

    /** Whether a UID is meaningful here at all. */
    public static boolean worthWriting(byte[] bytecode) {
        Shape shape = read(bytecode);
        if (shape == null) return false;
        // An interface's UID is computed differently and an enum's is ignored
        // by the serialization machinery entirely.
        return (shape.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ENUM)) == 0;
    }

    private record Member(String name, String descriptor, int access) {
    }

    private static final class Shape {
        String name;
        int access;
        boolean hasStaticInitialiser;
        final List<String> interfaces = new ArrayList<>();
        final List<Member> fields = new ArrayList<>();
        final List<Member> constructors = new ArrayList<>();
        final List<Member> methods = new ArrayList<>();
    }

    private static Shape read(byte[] bytecode) {
        try {
            Shape shape = new Shape();
            new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String name, String signature,
                                  String superName, String[] interfaces) {
                    shape.name = name.replace('/', '.');
                    shape.access = access;
                    if (interfaces != null) {
                        for (String each : interfaces) {
                            shape.interfaces.add(each.replace('/', '.'));
                        }
                    }
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                                               String signature, Object value) {
                    shape.fields.add(new Member(name, descriptor, access));
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if ("<clinit>".equals(name)) {
                        shape.hasStaticInitialiser = true;
                    } else if ("<init>".equals(name)) {
                        shape.constructors.add(new Member(name, descriptor, access));
                    } else {
                        shape.methods.add(new Member(name, descriptor, access));
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return shape.name == null ? null : shape;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
