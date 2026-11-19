/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.ref.*;
import java.util.*;
import java.security.MessageDigest;
import java.nio.ByteBuffer;

/** Raw user-class fingerprints; weak loader ownership, independent of transformed schemas. */
public final class KeepBaseline {
    private KeepBaseline() { }
    public record Fingerprint(Map<String,Long> constructors, Map<String,Long> methods,
                              Set<String> injection, Set<String> lifecycle,
                              Set<String> fields, Set<String> publicMethods, String annotations,
                              Map<String,String> memberAnnotations) { }
    public record Input(Fingerprint old, byte[] newRaw) { }
    private static final ReferenceQueue<ClassLoader> COLLECTED = new ReferenceQueue<>();
    private static final Map<LoaderKey,Map<String,Entry>> OWNERS = new HashMap<>();
    private static final class Entry {
        Fingerprint fingerprint;
        long reloads;
        Entry(Fingerprint fingerprint) { this.fingerprint=fingerprint; }
    }
    private static volatile java.util.function.Predicate<String> watched = name -> true;
    /** No installed watch inventory means unknown, not immutable. */
    public static void setWatchedPredicate(java.util.function.Predicate<String> predicate) {
        watched=predicate==null ? name -> true : predicate;
    }
    /** An ancestor's creation code is safe only with complete loader-specific history. */
    public static boolean unchangedAncestor(Class<?> type) {
        if(type==null)return false;
        try {
            if(!watched.test(type.getName().replace('.','/')))return true;
            synchronized(KeepBaseline.class) {
                Entry entry=owners(type.getClassLoader(),false).get(type.getName().replace('.','/'));
                return entry!=null && entry.fingerprint!=null && entry.reloads==0;
            }
        } catch(RuntimeException|LinkageError unavailable) { return false; }
    }
    public static synchronized long reloadCount(Class<?> type) {
        if(type==null)return 0;
        Entry entry=owners(type.getClassLoader(),false).get(type.getName().replace('.','/'));
        return entry==null ? 0 : entry.reloads;
    }
    private static final class LoaderKey extends WeakReference<ClassLoader> {
        private final int hash;
        LoaderKey(ClassLoader loader) { super(loader,COLLECTED); hash=System.identityHashCode(loader); }
        public int hashCode() { return hash; }
        public boolean equals(Object other) { return this==other || other instanceof LoaderKey k && get()!=null && get()==k.get(); }
    }
    private static Map<String,Entry> owners(ClassLoader loader, boolean create) {
        Reference<? extends ClassLoader> dead;
        while ((dead=COLLECTED.poll())!=null) OWNERS.remove(dead);
        if (loader==null) return Map.of();
        var key=new LoaderKey(loader);
        return create ? OWNERS.computeIfAbsent(key,k->new HashMap<>()) : OWNERS.getOrDefault(key,Map.of());
    }
    /** Never throws: it runs inside class definition, and missing evidence already selects recreation. */
    public static void capture(ClassLoader loader,String internalName,byte[] raw) {
        if(loader==null)return;
        Fingerprint print;
        try { print=fingerprint(raw); } catch(RuntimeException|LinkageError unavailable) { return; }
        synchronized(KeepBaseline.class) { owners(loader,true).putIfAbsent(internalName,new Entry(print)); }
    }
    public static synchronized Fingerprint get(Class<?> type) {
        Entry entry=type==null ? null : owners(type.getClassLoader(),false).get(type.getName().replace('.','/'));
        return entry==null ? null : entry.fingerprint;
    }
    /** A fingerprint that cannot be read drops the old one: a stale baseline must not decide the next reload. */
    public static void advance(Class<?> type,byte[] raw) {
        if(type==null || type.getClassLoader()==null)return;
        String name=type.getName().replace('.','/');
        synchronized(KeepBaseline.class) {
            owners(type.getClassLoader(),true).computeIfAbsent(name,k->new Entry(null)).reloads++;
        }
        Fingerprint print;
        try { print=fingerprint(raw); } catch(RuntimeException|LinkageError unavailable) { print=null; }
        synchronized(KeepBaseline.class) {
            var owned=owners(type.getClassLoader(),true);
            owned.get(name).fingerprint=print;
        }
    }
    public static ClassNode read(byte[] raw) {
        ClassNode c=new ClassNode(); new ClassReader(raw).accept(c,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES); return c;
    }
    public static Fingerprint fingerprint(byte[] raw) {
        ClassNode c=read(raw);
        Map<String,Long> ctors=new TreeMap<>(), methods=new TreeMap<>();
        Map<String,String> members=new TreeMap<>();
        Set<String> injection=new TreeSet<>(), lifecycle=new TreeSet<>(), fields=new TreeSet<>(), publics=new TreeSet<>();
        for(FieldNode f:c.fields) {
            if((f.access&Opcodes.ACC_STATIC)==0) fields.add(f.name+":"+f.desc);
            String metadata=annotations(f.visibleAnnotations)+annotations(f.invisibleAnnotations);
            if(!metadata.isEmpty())members.put(f.name+":"+f.desc,metadata);
            points(f.name+":"+f.desc,f.visibleAnnotations,injection,lifecycle);
            points(f.name+":"+f.desc,f.invisibleAnnotations,injection,lifecycle);
        }
        for(MethodNode m:c.methods) {
            String key=m.name+":"+m.desc;
            if(m.name.equals("<init>")) ctors.put(m.desc,digest(m));
            else if(!m.name.equals("<clinit>")) {
                // A business-body edit can remove a compiler-generated lambda helper.
                // Creation callbacks are user-declared; synthetic members are not creation signatures.
                if ((m.access & Opcodes.ACC_SYNTHETIC) == 0) methods.put(key,digest(m));
                if((m.access&Opcodes.ACC_PUBLIC)!=0) publics.add(key);
            }
            if((m.access&Opcodes.ACC_SYNTHETIC)==0) {
                String metadata=annotations(m.visibleAnnotations)+annotations(m.invisibleAnnotations);
                if(m.visibleParameterAnnotations!=null) for(int i=0;i<m.visibleParameterAnnotations.length;i++) {
                    String parameter=annotations(m.visibleParameterAnnotations[i]);
                    if(!parameter.isEmpty())metadata+="visible@"+i+parameter;
                }
                if(m.invisibleParameterAnnotations!=null) for(int i=0;i<m.invisibleParameterAnnotations.length;i++) {
                    String parameter=annotations(m.invisibleParameterAnnotations[i]);
                    if(!parameter.isEmpty())metadata+="invisible@"+i+parameter;
                }
                if(!metadata.isEmpty())members.put(key,metadata);
            }
            points(key,m.visibleAnnotations,injection,lifecycle);
            points(key,m.invisibleAnnotations,injection,lifecycle);
            if(m.visibleParameterAnnotations!=null) for(int i=0;i<m.visibleParameterAnnotations.length;i++)
                points(key+"@"+i,m.visibleParameterAnnotations[i],injection,lifecycle);
            if(m.invisibleParameterAnnotations!=null) for(int i=0;i<m.invisibleParameterAnnotations.length;i++)
                points(key+"@"+i,m.invisibleParameterAnnotations[i],injection,lifecycle);
            if(m.name.equals("afterPropertiesSet")||m.name.equals("destroy")) lifecycle.add(key);
        }
        return new Fingerprint(Map.copyOf(ctors),Map.copyOf(methods),Set.copyOf(injection),Set.copyOf(lifecycle),
                Set.copyOf(fields),Set.copyOf(publics),annotations(c.visibleAnnotations)+annotations(c.invisibleAnnotations),Map.copyOf(members));
    }
    public static boolean injection(String desc) {
        return Set.of("Lorg/springframework/beans/factory/annotation/Autowired;","Lorg/springframework/beans/factory/annotation/Value;",
                "Ljavax/annotation/Resource;","Ljakarta/annotation/Resource;","Ljavax/inject/Inject;","Ljakarta/inject/Inject;").contains(desc);
    }
    public static boolean lifecycle(String desc) {
        return Set.of("Ljavax/annotation/PostConstruct;","Ljakarta/annotation/PostConstruct;",
                "Ljavax/annotation/PreDestroy;","Ljakarta/annotation/PreDestroy;").contains(desc);
    }
    private static void points(String key,List<AnnotationNode> nodes,Set<String> injection,Set<String> lifecycle) {
        if(nodes==null)return;
        for(var a:nodes) {
            if(injection(a.desc)) injection.add(key+"="+a.desc+value(a.values));
            if(lifecycle(a.desc)) lifecycle.add(key+"="+a.desc+value(a.values));
        }
    }
    private static String annotations(List<AnnotationNode> nodes) {
        if(nodes==null)return "";
        var result=new TreeSet<String>(); for(var a:nodes)result.add(a.desc+value(a.values)); return result.toString();
    }
    private static String value(Object o) {
        if(o instanceof AnnotationNode a)return a.desc+value(a.values);
        if(o instanceof List<?> l)return l.stream().map(KeepBaseline::value).toList().toString();
        if(o instanceof String[] a)return Arrays.toString(a);
        return String.valueOf(o);
    }
    /** Canonical method-only constant pool; debug data and frames never participate. */
    public static long digest(MethodNode m) {
        ClassWriter w=new ClassWriter(0);
        w.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"Fingerprint",null,"java/lang/Object",null);
        MethodVisitor v=w.visitMethod(m.access,m.name,m.desc,null,null);
        v.visitCode();
        m.instructions.resetLabels();
        for(var t:m.tryCatchBlocks)t.accept(v);
        for(AbstractInsnNode insn:m.instructions)if(!(insn instanceof FrameNode)&&!(insn instanceof LineNumberNode))insn.accept(v);
        v.visitMaxs(0,0); v.visitEnd(); w.visitEnd();
        try { return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(w.toByteArray())).getLong(); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
