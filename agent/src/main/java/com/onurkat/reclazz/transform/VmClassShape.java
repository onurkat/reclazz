/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;

import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Exact-Class capture in the actual raw reload path; never a save-preparation lookup. */
public final class VmClassShape {
    private VmClassShape() { }
    private static final Set<Class<?>> capturing=ConcurrentHashMap.newKeySet();
    private static final ClassValue<Object> locks=new ClassValue<>() {
        @Override protected Object computeValue(Class<?> type){return new Object();}
    };
    public static boolean capturing(Class<?> type){return type!=null && capturing.contains(type);}
    public static RawClassSchema read(Class<?> owner,Instrumentation inst)throws Exception {
        RawClassSchema admitted=AdmittedBytes.trackRawSchema(owner);
        if(admitted!=null)return admitted;
        synchronized(locks.get(owner)){
            admitted=AdmittedBytes.trackRawSchema(owner);if(admitted!=null)return admitted;
            if(inst==null || !inst.isRetransformClassesSupported() || !inst.isModifiableClass(owner))
                throw new UnsupportedOperationException("VM shape capture unavailable");
            var bytes=new AtomicReference<byte[]>();
            ClassFileTransformer observer=new ClassFileTransformer(){
                @Override public byte[] transform(ClassLoader loader,String name,Class<?> target,
                        ProtectionDomain domain,byte[] raw){
                    if(target==owner && loader==owner.getClassLoader())bytes.set(raw.clone());
                    return null;
                }
            };
            boolean installed=false;
            if(!capturing.add(owner))throw new IllegalStateException("Nested VM shape capture");
            try {
                installed=true;inst.addTransformer(observer,true);
                inst.retransformClasses(owner);
                byte[] captured=bytes.get();if(captured==null)throw new IllegalStateException("VM did not expose the definition");
                return RawClassSchema.read(captured);
            } finally {
                try {if(installed)inst.removeTransformer(observer);}
                finally {capturing.remove(owner);}
            }
        }
    }
}
