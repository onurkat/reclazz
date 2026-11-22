/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Read-only, noninitializing static reference inspection for the trusted reload engine. */
public final class StaticReferenceInspection {
    private static final StackWalker WALKER=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private StaticReferenceInspection(){}
    public static Object read(Field field) {
        LookupCapture.requireTrusted(WALKER.getCallerClass(),"inspecting a static reference");
        if(field==null||!Modifier.isStatic(field.getModifiers())||field.getType().isPrimitive())
            throw new IllegalArgumentException("a static reference field is required");
        return UnsafeAccess.getStatic(field);
    }
}
