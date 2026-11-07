/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import org.objectweb.asm.*;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.Modifier;

/** Free saved-bytecode detection; it performs no platform action. */
public final class BackofficeSocketEventDetector {
    private static final String SOCKET_EVENT = "Lcom/hybris/cockpitng/annotations/SocketEvent;";

    public enum Binding { NATIVE_INVALID, RELOAD_VERIFIED, RELOAD_UNVERIFIED }

    public record Handler(String name, String descriptor, int access, int parameterCount) {
        public Binding binding() {
            if (!Modifier.isPublic(access) || parameterCount > 1) return Binding.NATIVE_INVALID;
            return Modifier.isStatic(access) ? Binding.RELOAD_UNVERIFIED : Binding.RELOAD_VERIFIED;
        }
    }

    /** Read saved metadata without loading the native annotation or controller. */
    public static List<Handler> addedHandlers(byte[] bytecode, Set<String> addedMethods) {
        if (bytecode == null || addedMethods.isEmpty()) return List.of();
        List<Handler> found = new ArrayList<>();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!addedMethods.contains(name + ":" + desc)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (visible && SOCKET_EVENT.equals(descriptor))
                            found.add(new Handler(name, desc, access, Type.getArgumentTypes(desc).length));
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return List.copyOf(found);
    }

    public static boolean hasAddedSocketEvent(byte[] bytecode, Set<String> addedMethods) {
        return !addedHandlers(bytecode, addedMethods).isEmpty();
    }
}
