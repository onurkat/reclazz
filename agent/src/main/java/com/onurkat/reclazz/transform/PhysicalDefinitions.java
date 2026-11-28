/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;

import java.io.ByteArrayOutputStream;
import java.lang.instrument.*;
import java.lang.ref.*;
import java.util.*;
import java.util.zip.*;

/** Loader-specific emitted physical payloads; observations never commit a failed native attempt. */
public final class PhysicalDefinitions {
    private PhysicalDefinitions() { }
    private record Payload(byte[] deflated,int length) { }
    private static final class Initial {Payload payload;Initial(Payload payload){this.payload=payload;}}
    private static final Object LOCK=new Object();
    private static final ReferenceQueue<ClassLoader> COLLECTED=new ReferenceQueue<>();
    private static final Map<LoaderKey,Map<String,Initial>> definitions=new HashMap<>();
    private static final class LoaderKey extends WeakReference<ClassLoader> {
        final int hash;
        LoaderKey(ClassLoader loader){super(loader,COLLECTED);hash=System.identityHashCode(loader);}
        public int hashCode(){return hash;}
        public boolean equals(Object other){return this==other||other instanceof LoaderKey key&&get()!=null&&get()==key.get();}
    }
    private static Map<String,Initial> definitions(ClassLoader loader,boolean create) {
        Reference<? extends ClassLoader> dead;while((dead=COLLECTED.poll())!=null)definitions.remove(dead);
        var key=new LoaderKey(loader);return create?definitions.computeIfAbsent(key,k->new HashMap<>()):definitions.getOrDefault(key,Map.of());
    }
    private static final class Current {boolean initialized;Initial initial;Payload payload;long revision;}
    private static final ClassValue<Current> current=new ClassValue<>(){@Override protected Current computeValue(Class<?> type){return new Current();}};
    private static Current state(Class<?> owner) {
        Current state=current.get(owner);
        if(!state.initialized) {
            state.initial=definitions(owner.getClassLoader(),false).get(owner.getName().replace('.','/'));
            state.payload=state.initial==null?null:state.initial.payload;
            if(state.initial!=null)state.initial.payload=null;state.initialized=true;
        } else if(state.initial!=null) {
            Initial observed=definitions(owner.getClassLoader(),false).get(owner.getName().replace('.','/'));
            if(observed!=state.initial){state.payload=null;state.initial=observed;}
        }
        return state;
    }
    /** No post-definition callback exists: a repeated attempt has no trustworthy initial payload. */
    public static void defined(ClassLoader loader,String name,byte[] emitted) {
        if(loader==null||name==null||emitted==null)return;
        Payload payload=pack(emitted);
        synchronized(LOCK) {var values=definitions(loader,true);values.put(name,new Initial(values.containsKey(name)?null:payload));}
    }
    public static byte[] get(Class<?> owner) {
        if(owner==null||owner.getClassLoader()==null)return null;
        Payload payload;synchronized(LOCK){if(!tracked(owner))return null;payload=state(owner).payload;}
        return payload==null?null:unpack(payload);
    }
    private static boolean tracked(Class<?> owner){return owner.getClassLoader()!=null&&definitions(owner.getClassLoader(),false).containsKey(owner.getName().replace('.','/'));}
    private record Candidate(Payload payload,long revision) { }
    private static final class Attempt {
        final Set<Class<?>> owners=Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<Class<?>,Candidate> candidates=new IdentityHashMap<>();
        final Map<Class<?>,Current> states=new IdentityHashMap<>();
        Attempt(ClassDefinition[] definitions){
            synchronized(LOCK){for(var definition:definitions){Class<?> owner=definition.getDefinitionClass();if(tracked(owner)){owners.add(owner);states.put(owner,state(owner));}}}
        }
    }
    private static final ThreadLocal<Attempt> attempt=new ThreadLocal<>();
    /** Called only at the actual transformer emission, including the already-transformed path. */
    public static void emitted(Class<?> owner,byte[] bytes) {
        if(owner==null||owner.getClassLoader()==null)return;
        synchronized(LOCK) {
            if(!tracked(owner))return;
            Payload payload=bytes==null?null:new Payload(bytes.clone(),-1);
            Current state=state(owner);state.revision++;
            Attempt active=attempt.get();
            if(active!=null&&active.owners.contains(owner))active.candidates.put(owner,new Candidate(payload,state.revision));
            else {state.payload=null;state.initial=null;}
        }
    }
    /** Same native call and exceptions; only a successful return confirms its observed payloads. */
    public static void redefine(Instrumentation instrumentation,ClassDefinition... definitions)throws ClassNotFoundException,UnmodifiableClassException {
        Attempt previous=attempt.get(),active=new Attempt(definitions);attempt.set(active);
        try {
            instrumentation.redefineClasses(definitions);
            for (Class<?> owner : active.owners) com.onurkat.reclazz.bootstrap.MethodForge.invalidateOverrideLookups(owner);
            synchronized(LOCK) {
                for(Class<?> owner:active.owners) {
                    Current state=active.states.get(owner);Candidate candidate=active.candidates.get(owner);
                    state.payload=candidate!=null&&candidate.revision==state.revision?candidate.payload:null;
                    state.initial=null;state.revision++;
                }
            }
        }finally {if(previous==null)attempt.remove();else attempt.set(previous);}
    }
    private static Payload pack(byte[] bytes) {
        var deflater=new Deflater();
        try {
            deflater.setInput(bytes);deflater.finish();var out=new ByteArrayOutputStream(bytes.length/3+32);byte[] buffer=new byte[8192];
            while(!deflater.finished())out.write(buffer,0,deflater.deflate(buffer));
            return new Payload(out.toByteArray(),bytes.length);
        }finally{deflater.end();}
    }
    private static byte[] unpack(Payload payload) {
        if(payload.length<0)return payload.deflated.clone();
        var inflater=new Inflater();
        try {
            inflater.setInput(payload.deflated);byte[] output=new byte[payload.length];int written=0;
            while(written<output.length&&!inflater.finished()) {
                int count=inflater.inflate(output,written,output.length-written);
                if(count==0&&(inflater.needsInput()||inflater.needsDictionary()))break;
                written+=count;
            }
            return written==output.length&&inflater.finished()?output:null;
        }catch(DataFormatException unavailable){return null;}finally{inflater.end();}
    }
}
