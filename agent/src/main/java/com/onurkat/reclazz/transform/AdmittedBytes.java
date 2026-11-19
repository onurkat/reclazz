/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;

import java.lang.ref.*;
import java.util.*;
import java.security.MessageDigest;

/** Raw user-byte proof only; values never retain a Class or ClassLoader. */
public final class AdmittedBytes {
    private AdmittedBytes() { }
    private static final ReferenceQueue<ClassLoader> COLLECTED=new ReferenceQueue<>();
    private static final Map<LoaderKey,Map<String,byte[]>> OWNERS=new HashMap<>();
    private static final class LoaderKey extends WeakReference<ClassLoader> {
        private final int hash;
        LoaderKey(ClassLoader loader){super(loader,COLLECTED);hash=System.identityHashCode(loader);}
        public int hashCode(){return hash;}
        public boolean equals(Object other){return this==other || other instanceof LoaderKey key && get()!=null && get()==key.get();}
    }
    private static Map<String,byte[]> owners(ClassLoader loader,boolean create) {
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
        owned.put(name,owned.containsKey(name) ? null : hash(raw));
    }
    public static synchronized void invalidate(Class<?> type) {
        if(type!=null && type.getClassLoader()!=null)
            owners(type.getClassLoader(),true).put(type.getName().replace('.','/'),null);
    }
    /** Called only after the terminal successful reload, never from a redefine observation. */
    public static synchronized void admitted(Class<?> type,byte[] raw) {
        if(type!=null && type.getClassLoader()!=null)
            owners(type.getClassLoader(),true).put(type.getName().replace('.','/'),hash(raw));
    }
    public static synchronized boolean matches(Class<?> type,byte[] raw) {
        if(type==null || type.getClassLoader()==null)return false;
        byte[] old=owners(type.getClassLoader(),false).get(type.getName().replace('.','/'));
        byte[] next=hash(raw);
        return old!=null && next!=null && Arrays.equals(old,next);
    }
}
