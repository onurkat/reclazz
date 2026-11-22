/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.transform.KeepBaseline;
import com.onurkat.reclazz.ui.RestartLedger;
import org.objectweb.asm.Type;
import java.nio.file.*;
import java.util.*;

/** Refused record dependency provenance, retained without retaining defining loaders. */
public final class RefusalTaint {
    private static final String REASON="record component changes require a restart";
    private int rootResolutions;
    int rootResolutions() { return rootResolutions; }
    private final Map<Class<?>,Boolean> loaded=new WeakHashMap<>();
    private final Set<Class<?>> pending=Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Class<?>> metadataPending=Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<ClassLoader,Set<String>> absent=new WeakHashMap<>();
    private record Origin(Class<?> type,ClassLoader loader,boolean known,boolean ambiguous) { }

    public synchronized Map<String,String> preflight(Map<String,byte[]> bytes,Map<String,Path> paths,Class<?>[] census) {
        return preflight(bytes, paths, census, Set.of());
    }
    /** Only a proven, complete native cohort may skip the fallback shape refusal in this save. */
    public synchronized Map<String,String> preflight(Map<String,byte[]> bytes, Map<String,Path> paths,
                                                     Class<?>[] census, Set<String> nativeCohort) {
        rootResolutions = 0;
        Map<String,String> refused=new LinkedHashMap<>();Map<String,Origin> origins=new HashMap<>();
        Map<String,List<Class<?>>> names=new HashMap<>();for(Class<?> type:census)names.computeIfAbsent(type.getName(),n->new ArrayList<>()).add(type);
        RootLookup roots = new RootLookup(census);
        java.util.function.Function<String, Origin> originFor = name -> origins.computeIfAbsent(name,
                key -> origin(key, paths.get(key), names, roots));
        bytes.forEach((name,raw)->{
            List<Class<?>> matches=names.getOrDefault(name,List.of());
            boolean shapeRecord = RecordShapeCheck.incoming(raw).record() || matches.stream().anyMatch(Class::isRecord);
            // A plain class restored after a refused plain->record edit still
            // needs its pending admission checked. Other unrelated inputs need no origin.
            if (!shapeRecord && matches.stream().noneMatch(pending::contains)) return;
            Origin origin=originFor.apply(name);
            if(origin.ambiguous && (RecordShapeCheck.incoming(raw).record()||matches.stream().anyMatch(Class::isRecord))) {
                refused.put(name,"ambiguous record owner "+name+" across class loaders; restart to apply");
                matches.forEach(type->taint(type,true));return;
            }
            if(origin.type!=null && (RecordShapeCheck.changed(origin.type,raw) || KeepBaseline.recordMetadataChanged(origin.type,raw))
                    && !nativeCohort.contains(name) && !RecordReloadPlan.canAdmitShape(origin.type,raw)) {
                refused.put(name,RecordShapeCheck.changed(origin.type,raw)
                        ? "record components of "+name+" changed (a JVM limit): restart to apply"
                        : "record metadata of "+name+" changed: restart to apply");
                if(!RecordShapeCheck.changed(origin.type,raw)&&KeepBaseline.recordMetadataChanged(origin.type,raw))metadataPending.add(origin.type);
                taint(origin.type,true);
            } else if(matches.size()>1 && (RecordShapeCheck.incoming(raw).record()||matches.stream().anyMatch(Class::isRecord))) {
                // The downstream legacy reload path still resolves a target by name.
                // Never let an otherwise safe payload mutate the other loader's record.
                refused.put(name,"ambiguous record reload target "+name+" across class loaders; restart to apply");
                mark(origin,name);
            } else if(origin.type!=null && com.onurkat.reclazz.transform.AdmittedBytes.matches(origin.type,raw)) {
                accepted(origin.type,raw); // Identical already-admitted bytes need no new JVM mutation.
            }
        });
        boolean progress;
        do {
            progress=false;
            Set<String> relevant=new HashSet<>();loaded.keySet().forEach(type->relevant.add(type.getName()));absent.values().forEach(relevant::addAll);
            for(var entry:bytes.entrySet()) {
                if(refused.containsKey(entry.getKey()))continue;
                Origin origin=null;var refs=ReferenceScan.read(entry.getValue());String reason=null;
                for(var member:refs.members()) {
                    if(!relevant.contains(member.owner()))continue;
                    // Native preflight has validated these exact frozen references against the incoming layout.
                    if(nativeCohort.contains(entry.getKey()) && nativeCohort.contains(member.owner()))continue;
                    if (origin == null) origin=originFor.apply(entry.getKey());
                    reason=unsafe(origin,member.owner(),member,names);
                    if(reason!=null)break;
                }
                if(reason==null && refs.reflection())for(String literal:refs.literals()) {
                    if(!relevant.contains(literal))continue;
                    if (origin == null) origin=originFor.apply(entry.getKey());
                    String unsafe=unsafe(origin,literal,null,names);
                    if(unsafe!=null){reason="reflective use of changed record dependency "+literal+"; restart to apply";break;}
                }
                if(reason!=null) {
                    refused.put(entry.getKey(),reason);mark(origin,entry.getKey());progress=true;
                }
            }
        }while(progress);
        return Map.copyOf(refused);
    }
    private void taint(Class<?> type,boolean record) {
        loaded.merge(type,record,(old,value)->old||value);
        if(record){pending.add(type);RestartLedger.note(subject(type),REASON);}
    }
    private void mark(Origin origin,String name) {
        if(origin.type!=null)taint(origin.type,false);
        else if(origin.known)absent.computeIfAbsent(origin.loader,k->new HashSet<>()).add(name);
    }
    private String unsafe(Origin origin,String owner,ReferenceScan.Member member,Map<String,List<Class<?>>> names) {
        if(!origin.known||origin.ambiguous)return "unresolved defining loader for changed record dependency "+owner+"; restart to apply";
        List<Class<?>> visible=names.getOrDefault(owner,List.of()).stream().filter(type->visible(origin.loader,type.getClassLoader())).toList();
        if(visible.size()>1)return "ambiguous owner "+owner+" across class loaders; restart to apply";
        if(visible.isEmpty()) {
            for(ClassLoader loader=origin.loader;;loader=loader.getParent()) {
                if(absent.getOrDefault(loader,Set.of()).contains(owner))return "uses refused record dependency "+owner+"; restart to apply";
                if(loader==null)break;
            }
            return null;
        }
        Class<?> type=visible.get(0);if(!loaded.containsKey(type))return null;
        if(metadataPending.contains(type))return "uses refused record metadata of "+owner+"; restart to apply";
        if(member==null)return "reflective use of tainted owner "+owner;
        try {if(exists(type,member,new HashSet<>()))return null;}
        catch(LinkageError|RuntimeException unavailable){return "live members unavailable for "+owner+"; restart to apply";}
        return "uses "+owner+"."+member.name()+member.descriptor()+" from a changed record; restart to apply";
    }
    private static boolean exists(Class<?> owner,ReferenceScan.Member member,Set<Class<?>> seen) {
        if(owner==null||!seen.add(owner))return false;
        var baseline=KeepBaseline.get(owner);
        if(baseline!=null) {
            if(member.field()) {
                if(baseline.fields().contains(member.name()+":"+member.descriptor()))return true;
                // Fingerprints omit static fields; physical reflection supplies that subset.
                for(var field:owner.getDeclaredFields())if(java.lang.reflect.Modifier.isStatic(field.getModifiers())&&field.getName().equals(member.name())&&Type.getDescriptor(field.getType()).equals(member.descriptor()))return true;
            } else if(member.name().equals("<init>"))return baseline.constructors().containsKey(member.descriptor());
            else if(baseline.methods().containsKey(member.name()+":"+member.descriptor())||baseline.publicMethods().contains(member.name()+":"+member.descriptor()))return true;
        } else {
            if(member.field())for(var field:owner.getDeclaredFields())if(field.getName().equals(member.name())&&Type.getDescriptor(field.getType()).equals(member.descriptor()))return true;
            if(member.name().equals("<init>")) {
                for(var ctor:owner.getDeclaredConstructors())if(Type.getConstructorDescriptor(ctor).equals(member.descriptor()))return true;
                return false;
            }
            if(!member.field())for(var method:owner.getDeclaredMethods())if(method.getName().equals(member.name())&&Type.getMethodDescriptor(method).equals(member.descriptor()))return true;
        }
        if(member.name().equals("<init>"))return false;
        if(exists(owner.getSuperclass(),member,seen))return true;
        for(Class<?> face:owner.getInterfaces())if(exists(face,member,seen))return true;
        return false;
    }
    private static boolean visible(ClassLoader from,ClassLoader defining) {
        for(ClassLoader loader=from;;loader=loader.getParent()){if(loader==defining)return true;if(loader==null)return false;}
    }
    /** One preflight snapshot: filesystem work is per CodeSource identity, never per class/name. */
    private final class RootLookup {
        private final Class<?>[] census;
        private final Map<java.security.CodeSource, Path> bySource = new IdentityHashMap<>();
        private Map<Path, Set<ClassLoader>> loadersByRoot;
        RootLookup(Class<?>[] census) { this.census=census; }
        Path root(Class<?> type) {
            java.security.CodeSource source;
            try { var domain=type.getProtectionDomain(); source=domain==null?null:domain.getCodeSource(); }
            catch (RuntimeException unavailable) { return null; }
            if (source==null) return null;
            if (bySource.containsKey(source)) return bySource.get(source);
            rootResolutions++;
            Path root=null;
            try { root=Path.of(source.getLocation().toURI()).toRealPath(); }
            catch (Exception unavailable) { /* Cache unavailable roots too. */ }
            bySource.put(source,root);
            return root;
        }
        Set<ClassLoader> loadersAt(Path directory) {
            if (directory==null) return Set.of();
            if (loadersByRoot==null) {
                loadersByRoot=new HashMap<>();
                for (Class<?> type:census) {
                    Path root=root(type);
                    if (root!=null) loadersByRoot.computeIfAbsent(root,
                            ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(type.getClassLoader());
                }
            }
            return loadersByRoot.getOrDefault(directory,Set.of());
        }
    }
    private Origin origin(String name,Path path,Map<String,List<Class<?>>> names,RootLookup roots) {
        var matches=names.getOrDefault(name,List.of());
        if(matches.size()==1)return new Origin(matches.get(0),matches.get(0).getClassLoader(),true,false);
        Path directory=null;
        if(path!=null)try {directory=path.toAbsolutePath().normalize();for(String part:(name.replace('.','/')+".class").split("/"))directory=directory.getParent();if(directory!=null)directory=directory.toRealPath();}catch(Exception unavailable){directory=null;}
        if(matches.size()>1) {
            List<Class<?>> exact=new ArrayList<>();
            if(directory!=null)for(Class<?> type:matches)if(directory.equals(roots.root(type)))exact.add(type);
            return exact.size()==1?new Origin(exact.get(0),exact.get(0).getClassLoader(),true,false):new Origin(null,null,false,true);
        }
        Set<ClassLoader> loaders=roots.loadersAt(directory);
        return loaders.size()==1?new Origin(null,loaders.iterator().next(),true,false):new Origin(null,null,false,loaders.size()>1);
    }
    private static String subject(Class<?> type){return type.getName()+" [loader "+Integer.toHexString(System.identityHashCode(type.getClassLoader()))+"]";}
    /** A record recovery needs terminal native admission even when it is the only saved file. */
    public synchronized boolean recovering(String name) {
        return pending.stream().anyMatch(type -> type.getName().equals(name));
    }
    public synchronized void nativeAccepted(Class<?> type, byte[] raw) {
        if (type == null || !type.isRecord() || RecordShapeCheck.changed(type, raw))
            throw new IllegalStateException("native record shape was not physically admitted");
        loaded.put(type, true);
        pending.remove(type); metadataPending.remove(type); RestartLedger.resolve(subject(type), REASON);
    }
    /** Logical S admission has its own proof; it never certifies a physical constructor schema. */
    public synchronized void stockAccepted(Class<?> type,byte[] raw) {
        if(type==null||!type.isRecord()||RecordShapeCheck.changed(type,raw)
                ||!KeepBaseline.hasVirtualRecordReceipt(type)||!KeepBaseline.recordLogicalConstructorBodiesUnchanged(type,raw))
            throw new IllegalStateException("stock record logical schema was not admitted");
        loaded.put(type,true);pending.remove(type);metadataPending.remove(type);RestartLedger.resolve(subject(type),REASON);
    }
    public synchronized void nativeRefused(Map<Class<?>,byte[]> records) {
        records.forEach((type,raw)->{
            if(!RecordShapeCheck.changed(type,raw)&&KeepBaseline.recordMetadataChanged(type,raw))metadataPending.add(type);
            taint(type,true);
        });
    }
    /** Only terminal successful admission resolves the visible restart concern; provenance remains. */
    public synchronized void accepted(Class<?> type,byte[] raw) {
        if(type!=null&&pending.contains(type)&&!RecordShapeCheck.changed(type,raw)&&!KeepBaseline.recordMetadataChanged(type,raw)) {
            pending.remove(type);metadataPending.remove(type);RestartLedger.resolve(subject(type),REASON);
            com.onurkat.reclazz.ui.StatusReporter.info("Record component shape restored: "+type.getName());
        }
    }
}
