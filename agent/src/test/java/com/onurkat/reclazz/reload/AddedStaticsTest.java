/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AddedStaticsTest implements Opcodes {
    private static byte[] shape(boolean field) {
        ClassWriter w = new ClassWriter(0); w.visit(V17, ACC_PUBLIC, "sample/Holder", null, "java/lang/Object", null);
        if (field) w.visitField(ACC_PUBLIC | ACC_STATIC, "$SwitchMap$sample$Mode", "[I", null, null).visitEnd();
        w.visitEnd(); return w.toByteArray();
    }
    private static Class<?> owner(boolean physical) {
        return new ClassLoader(AddedStaticsTest.class.getClassLoader()) {
            Class<?> define() { byte[] b = shape(physical); return defineClass("sample.Holder", b, 0, b.length); }
        }.define();
    }
    private static boolean routed(Class<?> caller) {
        return AddedStatics.generating(caller, () -> AddedStatics.contains("sample/Holder", "$SwitchMap$sample$Mode", "[I"));
    }
    @Test void sameSaveSeesAddedFieldBeforePublicationAndIsolatesBothOtherLoaders() {
        Class<?> added = owner(false), physical = owner(true), unknown = owner(false);
        AddedStatics.begin(Map.of(added, shape(true)));
        try { assertTrue(routed(added)); assertFalse(routed(physical)); assertFalse(routed(unknown)); }
        finally { AddedStatics.finish(Set.of(added)); }
        assertTrue(routed(added)); assertFalse(routed(physical)); assertFalse(routed(unknown));
    }
    @Test void refusedOrFailedSaveDoesNotPublishAndLaterSaveHasNoSpeculativeKey() {
        Class<?> refused = owner(false), later = owner(false);
        AddedStatics.begin(Map.of(refused, shape(true))); assertTrue(routed(refused)); AddedStatics.finish(Set.of());
        assertFalse(routed(refused));
        AddedStatics.begin(Map.of(later, shape(false)));
        try { assertFalse(routed(refused)); assertFalse(routed(later)); }
        finally { AddedStatics.finish(Set.of(later)); }
    }
}
