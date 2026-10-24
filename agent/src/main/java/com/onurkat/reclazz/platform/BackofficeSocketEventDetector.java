/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import org.objectweb.asm.*;
import java.util.Set;

/** Free saved-bytecode detection; it performs no platform action. */
public final class BackofficeSocketEventDetector {
    private static final String SOCKET_EVENT = "Lcom/hybris/cockpitng/annotations/SocketEvent;";

    /** Read saved metadata: ordinary reflection cannot see a new companion method. */
    public static boolean hasAddedSocketEvent(byte[] bytecode, Set<String> addedMethods) {
        if (bytecode == null || addedMethods.isEmpty()) return false;
        boolean[] found = {false};
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!addedMethods.contains(name + ":" + desc)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (SOCKET_EVENT.equals(descriptor)) found[0] = true;
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }
}
