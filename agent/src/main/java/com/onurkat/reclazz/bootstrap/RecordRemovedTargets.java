/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.*;
import java.util.Map;

/** Frozen removal targets belong to the hidden record companion, without a global registry. */
public final class RecordRemovedTargets {
    private RecordRemovedTargets() { }
    public static CallSite bootstrap(MethodHandles.Lookup caller,String name,MethodType type,String key)
            throws IllegalAccessException {
        if(!caller.lookupClass().isHidden())throw new IllegalAccessException("Record removal target requires a hidden companion");
        Map<?,?> targets=MethodHandles.classData(caller,"_",Map.class);
        Object found=targets==null?null:targets.get(key);
        if(!(found instanceof MethodHandle target)||!type.equals(target.type()))
            throw new IllegalArgumentException("Record removal target missing or type changed: "+key);
        return new ConstantCallSite(target);
    }
}
