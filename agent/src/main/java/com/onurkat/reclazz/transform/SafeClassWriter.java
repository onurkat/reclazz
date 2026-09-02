/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * A {@link ClassWriter} that can compute frames for classes it cannot load.
 *
 * <p>{@code COMPUTE_FRAMES} makes ASM work out the stack map itself, and where
 * two reference types meet it asks {@link #getCommonSuperClass} which one to
 * write down. The stock answer calls {@code Class.forName} through the writer's
 * own classloader, which is the agent's, and the types in question belong to
 * the application: a companion class that is not defined yet, a hidden class
 * that has no name to load by, a controller on a web application's loader. When
 * that lookup fails the exception comes out of the middle of frame computation
 * and takes the whole transform with it.
 *
 * <p>{@code java/lang/Object} is always a correct answer to the question, just
 * a less precise one, and the verifier accepts it. So an unresolvable pair
 * falls back to it rather than failing.
 *
 * <p>This existed twice, written out by hand in the two places that had hit the
 * problem, and not at all in the third place that computes frames: the adapter
 * that carries an added {@code @RequestMapping} method to the mapping scan,
 * which builds a class referring to the application's own controller. Being a
 * type rather than a habit is what puts it there too.
 */
public class SafeClassWriter extends ClassWriter {

    public SafeClassWriter(int flags) {
        super(flags);
    }

    public SafeClassWriter(ClassReader classReader, int flags) {
        super(classReader, flags);
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        try {
            return super.getCommonSuperClass(type1, type2);
        } catch (Throwable notResolvable) {
            // Correct, and less precise: every reference type is an Object.
            return "java/lang/Object";
        }
    }
}
