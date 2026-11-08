/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.ref.*;
import java.util.*;

/** Frozen first-definition schema and original initializer evidence; no reload dependency. */
public final class StaticFieldPreparation implements Opcodes {
    private StaticFieldPreparation() { }
    private record Definition(Set<String> mask, byte[] original) { }
    private static final ReferenceQueue<ClassLoader> COLLECTED = new ReferenceQueue<>();
    private static final Map<LoaderKey, Map<String,Definition>> DEFINITIONS = new HashMap<>();
    private static final Map<String,Definition> BOOT = new HashMap<>();
    private static final class LoaderKey extends WeakReference<ClassLoader> {
        final int hash;
        LoaderKey(ClassLoader loader, ReferenceQueue<ClassLoader> queue) { super(loader,queue);hash=System.identityHashCode(loader); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || (other instanceof LoaderKey key && get() != null && get() == key.get());
        }
    }
    private static Map<String,Definition> owners(ClassLoader loader, boolean create) {
        Reference<? extends ClassLoader> dead;
        while ((dead=COLLECTED.poll()) != null) DEFINITIONS.remove(dead);
        if (loader == null) return BOOT;
        LoaderKey key=new LoaderKey(loader,COLLECTED);
        return create ? DEFINITIONS.computeIfAbsent(key,ignored->new HashMap<>()) : DEFINITIONS.getOrDefault(key,Map.of());
    }
    /** Only after the complete first-load transform has passed bytecode validation. */
    public static synchronized void recordDefinition(ClassLoader loader, String name, byte[] bytes, boolean enabled) {
        owners(loader,true).putIfAbsent(name,new Definition(mask(bytes,enabled), snapshot(bytes)));
    }
    public static synchronized byte[] originalBytes(Class<?> owner) {
        Definition definition=owners(owner.getClassLoader(),false).get(owner.getName().replace('.','/'));
        return definition==null ? null : definition.original.clone();
    }
    public static byte[] firstDefinition(byte[] bytes, boolean enabled) { return applyMask(bytes,mask(bytes,enabled)); }
    public static synchronized byte[] stableSchema(ClassLoader loader,String name,byte[] bytes) {
        Definition definition=owners(loader,false).get(name);
        return definition==null ? bytes : applyMask(bytes,definition.mask);
    }
    private static ClassNode read(byte[] bytes) {
        var cls=new ClassNode();new ClassReader(bytes).accept(cls,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);return cls;
    }
    private static byte[] snapshot(byte[] bytes) {
        // Only the fields/<clinit> are evidence. Do not retain unrelated method
        // bodies for every prepared class, or any reference to its defining loader.
        ClassNode cls=read(bytes);
        cls.methods.removeIf(m->!m.name.equals("<clinit>"));
        cls.fields.removeIf(f->(f.access&ACC_STATIC)==0);
        ClassWriter writer=new ClassWriter(0);cls.accept(writer);return writer.toByteArray();
    }
    private static Set<String> mask(byte[] bytes,boolean enabled) {
        if(!enabled)return Set.of();
        ClassNode cls=read(bytes);
        if((cls.access&(ACC_INTERFACE|ACC_ENUM))!=0)return Set.of();
        Set<String> keys=new HashSet<>();
        for(FieldNode field:cls.fields)
            if((field.access&(ACC_STATIC|ACC_FINAL|ACC_SYNTHETIC))==(ACC_STATIC|ACC_FINAL)
                    &&field.value==null&&!field.name.equals("serialVersionUID"))keys.add(field.name+":"+field.desc);
        return Set.copyOf(keys);
    }
    private static byte[] applyMask(byte[] bytes,Set<String> mask) {
        if(mask.isEmpty())return bytes;
        ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,0);
        reader.accept(new ClassVisitor(ASM9,writer) {
            @Override public FieldVisitor visitField(int access,String name,String descriptor,String signature,Object value) {
                return super.visitField(mask.contains(name+":"+descriptor)?access&~ACC_FINAL:access,name,descriptor,signature,value);
            }
        },0);
        return writer.toByteArray();
    }
}
