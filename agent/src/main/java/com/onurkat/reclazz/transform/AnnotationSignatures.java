/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeMap;

/**
 * Renders every annotation on a class, its methods and its fields into a
 * stable set of strings, so two versions of the same class can be compared.
 *
 * <h2>Why this exists</h2>
 * The structural diff asks only whether methods and fields were added or
 * removed. An edit that changes nothing but an annotation is therefore
 * classified as body-only, and the Spring reloaders never run: the class is
 * redefined, the new annotation is genuinely live and visible to reflection,
 * and the MVC registry carries on serving the mapping it read at startup.
 * From the outside that looks like the annotation was ignored, which is what
 * this product documented for a long time.
 *
 * <h2>Values, not just presence</h2>
 * The signature includes annotation values, because the common edit is a
 * value change rather than adding or removing the annotation itself:
 * {@code @RequestMapping("/ping")} becoming {@code @RequestMapping("/pong")}
 * leaves the set of annotation types identical.
 *
 * <h2>Both sides parse bytecode</h2>
 * The old and the new signature both come from here, reading class files with
 * the same code. The tempting alternative, reading the old side off the
 * loaded {@link Class} through reflection, means two renderings of the same
 * concept that have to agree forever, and they would eventually not.
 */
public final class AnnotationSignatures {

    private AnnotationSignatures() {}

    /**
     * Signatures for every annotation in the class file, each prefixed with
     * what it is attached to so a move between members is a difference.
     */
    public static Set<String> of(byte[] bytecode) {
        Collector collector = new Collector();
        new ClassReader(bytecode).accept(collector, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        return collector.signatures;
    }

    private static class Collector extends ClassVisitor {
        final Set<String> signatures = new LinkedHashSet<>();

        Collector() {
            super(Opcodes.ASM9);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return new ValueCollector(descriptor, "class", signatures);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            String owner = "method " + name + descriptor;
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    return new ValueCollector(desc, owner, signatures);
                }
            };
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            String owner = "field " + name + ":" + descriptor;
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    return new ValueCollector(desc, owner, signatures);
                }
            };
        }
    }

    /**
     * Accumulates one annotation's values and emits its signature on
     * {@code visitEnd}. Values land in a sorted map because the order ASM
     * reports them in is the order they appear in the class file, and a
     * recompilation is free to change that without the source having changed.
     */
    private static class ValueCollector extends AnnotationVisitor {
        private final String descriptor;
        private final String owner;
        private final Set<String> sink;
        private final TreeMap<String, String> values = new TreeMap<>();

        ValueCollector(String descriptor, String owner, Set<String> sink) {
            super(Opcodes.ASM9);
            this.descriptor = descriptor;
            this.owner = owner;
            this.sink = sink;
        }

        @Override
        public void visit(String name, Object value) {
            values.put(String.valueOf(name), render(value));
        }

        @Override
        public void visitEnum(String name, String desc, String value) {
            values.put(String.valueOf(name), desc + "." + value);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String desc) {
            // Nested annotation: record its type. Going deeper would buy
            // precision nobody has asked for yet, and the type changing is
            // already a difference.
            values.put(String.valueOf(name), "@" + desc);
            return null;
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            String key = String.valueOf(name);
            StringBuilder joined = new StringBuilder("[");
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String n, Object value) {
                    if (joined.length() > 1) joined.append(',');
                    joined.append(render(value));
                }

                @Override
                public void visitEnum(String n, String desc, String value) {
                    if (joined.length() > 1) joined.append(',');
                    joined.append(desc).append('.').append(value);
                }

                @Override
                public void visitEnd() {
                    values.put(key, joined.append(']').toString());
                }
            };
        }

        @Override
        public void visitEnd() {
            sink.add(owner + " " + descriptor + values);
        }

        private static String render(Object value) {
            if (value instanceof Object[] array) {
                return Arrays.deepToString(array);
            }
            if (value != null && value.getClass().isArray()) {
                // Primitive arrays, which Arrays.deepToString will not take.
                int length = java.lang.reflect.Array.getLength(value);
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(java.lang.reflect.Array.get(value, i));
                }
                return sb.append(']').toString();
            }
            return String.valueOf(value);
        }
    }
}
