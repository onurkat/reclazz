/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Which class a {@code super} call actually lands in.
 *
 * <p>javac writes the direct superclass as the owner of a super call and lets
 * the JVM resolve it upwards, so the owner in the bytecode is frequently not
 * the class that declares the method. Rewriting the call to the renamed copy
 * against that owner therefore names a method the owner was never going to
 * have, and instrumenting it would not have helped: a class gets renamed copies
 * of the methods it declares, and it declares none.
 *
 * <p>Reported from a SAP Commerce project, as an ImpEx import aborting:
 *
 * <pre>
 *   NoSuchMethodError: GeneratedBadge.__reclazz$v0$createItem$2c9e54f0e5226bd3(...)
 *       at Badge.__reclazz$v0$createItem$2c9e54f0e5226bd3(Badge.java:19)
 * </pre>
 *
 * <p>{@code Badge extends GeneratedBadge} and calls {@code super.createItem},
 * and GeneratedBadge inherits createItem from the platform's own Item several
 * classes further up. Every hybris item class has that shape.
 *
 * <p>So the chain is walked, from the owner upwards, reading each class file
 * through the loader that is defining the class being transformed. Reading
 * rather than loading: this runs inside a class definition, and loading a class
 * from there is how a circularity error happens. The answer is cached, since a
 * hierarchy is walked once per call site and applications have many call sites
 * into the same few parents.
 */
final class SuperCallTarget {

    /** Enough for a deep framework hierarchy, short of a runaway. */
    private static final int MAX_DEPTH = 40;

    /** Bounded: this is a lookup table, not a record of the application. */
    private static final int MAX_CACHED = 4096;

    private static final Map<String, Shape> SHAPES =
            Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Shape> eldest) {
                    return size() > MAX_CACHED;
                }
            });

    private record Shape(String superName, Set<String> declared) {
    }

    private SuperCallTarget() {
    }

    /**
     * @return the internal name of the class that declares this method, or
     *         null when the chain cannot be read or nothing declares it
     */
    static String declaringClass(ClassLoader loader, String owner, String name, String descriptor) {
        String key = name + descriptor;
        String current = owner;
        for (int depth = 0; depth < MAX_DEPTH && current != null; depth++) {
            Shape shape = shapeOf(loader, current);
            if (shape == null) return null;
            if (shape.declared().contains(key)) return current;
            current = shape.superName();
        }
        return null;
    }

    private static Shape shapeOf(ClassLoader loader, String internalName) {
        Shape cached = SHAPES.get(internalName);
        if (cached != null) return cached;

        byte[] bytes = read(loader, internalName);
        if (bytes == null) return null;

        Set<String> declared = new java.util.HashSet<>();
        String[] superName = new String[1];
        try {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String name, String signature,
                                  String supername, String[] interfaces) {
                    superName[0] = supername;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    declared.add(name + descriptor);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException unreadable) {
            return null;
        }

        Shape shape = new Shape(superName[0], declared);
        SHAPES.put(internalName, shape);
        return shape;
    }

    private static byte[] read(ClassLoader loader, String internalName) {
        String resource = internalName + ".class";
        try (InputStream in = loader == null
                ? ClassLoader.getSystemResourceAsStream(resource)
                : loader.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception notReadable) {
            return null;
        }
    }
}
