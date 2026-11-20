/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;

import java.lang.ref.*;
import java.util.*;
import java.security.MessageDigest;

/** Raw user-byte proof only; values never retain a Class or ClassLoader. */
public final class AdmittedBytes {
    private AdmittedBytes() { }
    private static final ReferenceQueue<ClassLoader> COLLECTED=new ReferenceQueue<>();
    private record Admission(byte[] digest, RawClassSchema schema, boolean tracked) { }
    private static final Map<LoaderKey,Map<String,Admission>> OWNERS=new HashMap<>();
    private static final class LoaderKey extends WeakReference<ClassLoader> {
        private final int hash;
        LoaderKey(ClassLoader loader){super(loader,COLLECTED);hash=System.identityHashCode(loader);}
        public int hashCode(){return hash;}
        public boolean equals(Object other){return this==other || other instanceof LoaderKey key && get()!=null && get()==key.get();}
    }
    private static Map<String,Admission> owners(ClassLoader loader,boolean create) {
        Reference<? extends ClassLoader> dead;
        while((dead=COLLECTED.poll())!=null)OWNERS.remove(dead);
        var key=new LoaderKey(loader);
        return create ? OWNERS.computeIfAbsent(key,k->new HashMap<>()) : OWNERS.getOrDefault(key,Map.of());
    }
    private static byte[] hash(byte[] raw) {
        try{return MessageDigest.getInstance("SHA-256").digest(raw);}
        catch(java.security.NoSuchAlgorithmException|RuntimeException unavailable){return null;}
    }
    public static synchronized void clear(){OWNERS.clear();}
    /** Definition attempts have no after-VM callback: a repeated attempt becomes unknown. */
    public static synchronized void defined(ClassLoader loader,String name,byte[] raw) {
        if(loader==null || name==null)return;
        var owned=owners(loader,true);
        Admission old=owned.get(name);
        owned.put(name,new Admission(owned.containsKey(name)?null:hash(raw),null,old!=null&&old.tracked()));
    }
    public static synchronized void invalidate(Class<?> type) {
        if(type!=null && type.getClassLoader()!=null){
            var owned=owners(type.getClassLoader(),true);String name=type.getName().replace('.','/');Admission old=owned.get(name);
            owned.put(name,new Admission(null,null,old!=null&&old.tracked()));
        }
    }
    /** Called only after the terminal successful reload, never from a redefine observation. */
    public static synchronized void admitted(Class<?> type,byte[] raw) {
        if(type!=null && type.getClassLoader()!=null){
            var owned=owners(type.getClassLoader(),true);String name=type.getName().replace('.','/');Admission old=owned.get(name);
            boolean tracked=old!=null&&old.tracked();RawClassSchema schema=null;
            if(tracked)try{schema=RawClassSchema.read(raw);}catch(RuntimeException|LinkageError unavailable){/* unknown stays unknown */}
            owned.put(name,new Admission(hash(raw),schema,tracked));
        }
    }
    public static synchronized boolean matches(Class<?> type,byte[] raw) {
        if(type==null || type.getClassLoader()==null)return false;
        Admission value=owners(type.getClassLoader(),false).get(type.getName().replace('.','/'));
        byte[] old=value==null?null:value.digest();
        byte[] next=hash(raw);
        return old!=null && next!=null && Arrays.equals(old,next);
    }
    /** Captured VM shapes never enter this store; only terminal admitted user bytes do. */
    public static synchronized RawClassSchema trackRawSchema(Class<?> type){
        if(type==null || type.getClassLoader()==null)return null;
        var owned=owners(type.getClassLoader(),true);String name=type.getName().replace('.','/');Admission old=owned.get(name);
        if(old==null){owned.put(name,new Admission(null,null,true));return null;}
        if(!old.tracked())owned.put(name,new Admission(old.digest(),old.schema(),true));
        return old.digest()==null?null:old.schema();
    }

}
