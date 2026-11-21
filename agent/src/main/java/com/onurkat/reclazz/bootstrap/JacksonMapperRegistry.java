/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Live mappers, including standalone/copy instances, without retaining their loaders. */
public final class JacksonMapperRegistry {
    private static final List<WeakReference<Object>> mappers = new ArrayList<>();
    private static final ReferenceQueue<Object> collected = new ReferenceQueue<>();
    private static final ConcurrentHashMap<Identity,Boolean> registered = new ConcurrentHashMap<>();
    private JacksonMapperRegistry() { }
    private static final class Identity extends WeakReference<Object> {
        final int hash;
        Identity(Object target){super(target,collected);hash=System.identityHashCode(target);}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object other){return this==other||other instanceof Identity key&&get()!=null&&get()==key.get();}
    }
    private record Lookup(Object target) {
        @Override public int hashCode(){return System.identityHashCode(target);}
        @Override public boolean equals(Object other){return other instanceof Identity key&&target==key.get();}
    }
    private static void drain() {
        Identity dead;while((dead=(Identity)collected.poll())!=null){registered.remove(dead);mappers.remove(dead);}
    }
    /** Constructor registration and first post-attach use share one identity guard; hot calls never lock/scan. */
    public static void track(Object mapper) {
        if(mapper==null||registered.containsKey(new Lookup(mapper)))return;
        synchronized(JacksonMapperRegistry.class) {
            if(registered.containsKey(new Lookup(mapper)))return;
            drain();Identity key=new Identity(mapper);mappers.add(key);registered.put(key,Boolean.TRUE);
        }
    }
    public static synchronized List<Object> live() {
        drain();var result=new ArrayList<Object>();
        mappers.removeIf(ref->ref.get()==null);
        for(var ref:mappers){Object mapper=ref.get();if(mapper!=null)result.add(mapper);}
        return result;
    }
}
