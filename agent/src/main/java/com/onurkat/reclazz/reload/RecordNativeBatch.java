/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.agent.JvmCapabilityProbe.RecordCapability;
import com.onurkat.reclazz.transform.KeepBaseline;
import com.onurkat.reclazz.util.BytecodeVersion;
import com.onurkat.reclazz.ui.Failures;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.objectweb.asm.*;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.tree.*;

/** One native admission for a changed record and its changed, watched, typed callers.
 * The enabling caller owns the readiness gate, actual capability probe and save lifetime.
 * This object never enables a route, loads a referenced class or retries individual members.
 */
public final class RecordNativeBatch {
    public record Change(Class<?> owner, byte[] bytes) {
        public Change { Objects.requireNonNull(owner); bytes=bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        public String name() { return owner.getName(); }
    }
    public record Outcome(boolean attempted, boolean committed, String reason, Map<String,String> repairFailures) {
        public Outcome { repairFailures=Map.copyOf(repairFailures); }
    }
    private final Instrumentation instrumentation;
    private final Set<String> records;
    private final Set<String> names;
    private final List<Change> changes;
    private final Map<String,String> refusals;
    private Outcome outcome;

    private RecordNativeBatch(Instrumentation instrumentation, Set<String> records, Set<String> names,
                              List<Change> changes, String refusal) {
        this.instrumentation=instrumentation;
        this.records=Set.copyOf(records);this.names=Set.copyOf(names);this.changes=List.copyOf(changes);
        Map<String,String> rejected=new LinkedHashMap<>();
        if(refusal!=null)for(String name:names)rejected.put(name,refusal);
        this.refusals=Map.copyOf(rejected);
    }
    public boolean active() { return !records.isEmpty(); }
    public Set<String> recordNames() { return records; }
    public Set<String> names() { return names; }
    public List<Change> changes() { return changes; }
    public Map<String,String> refusals() { return refusals; }

    /** Read-only preflight. Names use binary form (com.example.P), not internal slash form. */
    public static RecordNativeBatch prepare(Map<String,byte[]> incoming,Class<?>[] census,
                                             Predicate<String> watched,RecordCapability capability,
                                             Instrumentation instrumentation) {
        return prepare(incoming, census, watched, capability, instrumentation, Set.of());
    }
    /** A previous committed shape with failed repairs must re-enter the same complete admission on retry. */
    public static RecordNativeBatch prepare(Map<String,byte[]> incoming,Class<?>[] census,
                                             Predicate<String> watched,RecordCapability capability,
                                             Instrumentation instrumentation,Set<String> repairPending) {
        Objects.requireNonNull(watched);Objects.requireNonNull(capability);
        Map<String,List<Class<?>>> loaded=new HashMap<>();
        for(Class<?> type:census)loaded.computeIfAbsent(type.getName(),n->new ArrayList<>()).add(type);
        Set<String> records=new LinkedHashSet<>();String refusal=null;
        for(var entry:incoming.entrySet()) {
            List<Class<?>> matches=loaded.getOrDefault(entry.getKey(),List.of());
            if(matches.isEmpty())continue; // A new type is not a redefinition of an old record.
            var shape=RecordShapeCheck.incoming(entry.getValue());
            boolean candidate=matches.stream().anyMatch(type -> (type.isRecord()||shape.record())
                    && (RecordShapeCheck.changed(type,entry.getValue()) || KeepBaseline.recordMetadataChanged(type,entry.getValue()) || (type.isRecord() && repairPending.contains(entry.getKey()))));
            if(!candidate)continue;
            records.add(entry.getKey());
            if(matches.size()!=1)refusal="ambiguous record owner "+entry.getKey()+" across class loaders; restart to apply";
            else if(!matches.get(0).isRecord()||!shape.record())refusal="plain/record kind changes require a restart: "+entry.getKey();
        }
        if(records.isEmpty())return new RecordNativeBatch(instrumentation,Set.of(),Set.of(),List.of(),null);
        Set<String> cohort=new LinkedHashSet<>(records);
        boolean progress;
        do {
            progress = false;
            for(var entry:incoming.entrySet()) {
                if(cohort.contains(entry.getKey()))continue;
                if(!Collections.disjoint(references(entry.getValue()),cohort)) { cohort.add(entry.getKey()); progress = true; }
            }
        } while (progress);
        if(!capability.nativeSchema())refusal="native record capability unavailable: "+capability.reason()+"; restart to apply";
        if(instrumentation==null||!instrumentation.isRedefineClassesSupported())refusal="native record redefinition is unavailable; restart to apply";
        List<Change> changes=new ArrayList<>();
        for(String name:cohort) {
            List<Class<?>> matches=loaded.getOrDefault(name,List.of());
            byte[] raw=incoming.get(name);
            if(matches.size()!=1) {refusal="record cohort member is not uniquely loaded: "+name+"; restart to apply";continue;}
            Class<?> owner=matches.get(0);
            if(!watched.test(name)||!KeepBaseline.witnessedLoad(owner))refusal="record cohort member was not witnessed under the watch: "+name+"; restart to apply";
            int access = new ClassReader(raw).getAccess();
            if (((access & Opcodes.ACC_INTERFACE) != 0) != owner.isInterface()
                    || ((access & Opcodes.ACC_ENUM) != 0) != owner.isEnum()
                    || ((access & Opcodes.ACC_ANNOTATION) != 0) != owner.isAnnotation()
                    || ((access & Opcodes.ACC_INTERFACE) != 0 && (access & Opcodes.ACC_FINAL) != 0))
                refusal = "record cohort caller class kind/modifiers changed: " + name + "; restart to apply";
            String version=BytecodeVersion.rejectionReason(raw);
            if(version!=null)refusal=version;
            if(!new ClassReader(raw).getClassName().replace('/','.').equals(name))refusal="record cohort class name mismatch: "+name;
            if(instrumentation!=null&&!instrumentation.isModifiableClass(owner))refusal="record cohort member is not modifiable: "+name+"; restart to apply";
            if (refusal == null) {
                try {
                    ClassNode checked = new ClassNode();
                    new ClassReader(raw).accept(new org.objectweb.asm.util.CheckClassAdapter(checked, true), ClassReader.SKIP_DEBUG);
                } catch (RuntimeException | LinkageError invalid) {
                    refusal = "record cohort bytecode did not verify: " + name + "; " + invalid.getClass().getSimpleName() + "; restart to apply";
                }
            }
            Set<String> linked=references(raw);
            for(String record:records) {
                if(!name.equals(record)&&!linked.contains(record))continue;
                List<Class<?>> owners=loaded.getOrDefault(record,List.of());
                if(owners.size()==1&&!visible(owner.getClassLoader(),owners.get(0).getClassLoader()))
                    refusal="record cohort cannot prove loader visibility: "+name+" -> "+record+"; restart to apply";
            }
            changes.add(new Change(owner,raw));
        }
        for (Change change : changes) {
            ReferenceScan.References refs = ReferenceScan.read(change.bytes());
            for (ReferenceScan.Member member : refs.members()) {
                if (!records.contains(member.owner())) continue;
                ClassNode layout = new ClassNode();
                new ClassReader(incoming.get(member.owner())).accept(layout, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                boolean present = member.field()
                        ? layout.fields.stream().anyMatch(f -> f.name.equals(member.name()) && f.desc.equals(member.descriptor()))
                        : layout.methods.stream().anyMatch(m -> m.name.equals(member.name()) && m.desc.equals(member.descriptor()));
                Class<?> recordOwner = loaded.getOrDefault(member.owner(), List.of()).stream().findFirst().orElse(null);
                if (!present && (recordOwner == null || !inherited(recordOwner, member)))
                    refusal = "record cohort has stale linkage: " + change.name() + " uses " + member.owner() + "."
                            + member.name() + member.descriptor() + "; no cohort member was redefined";
            }
            if (refs.reflection() && !Collections.disjoint(refs.literals(), records))
                refusal = "record cohort contains unproven reflective record linkage: " + change.name() + "; restart to apply";
        }
        return new RecordNativeBatch(instrumentation,records,cohort,changes,refusal);
    }

    private static boolean inherited(Class<?> owner, ReferenceScan.Member member) {
        if (member.name().equals("<init>") || member.name().equals("<clinit>")) return false;
        Set<Class<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Class<?>> parents = new ArrayDeque<>();
        if (owner.getSuperclass() != null) parents.add(owner.getSuperclass());
        parents.addAll(List.of(owner.getInterfaces()));
        while (!parents.isEmpty()) {
            Class<?> type = parents.remove(); if (!seen.add(type)) continue;
            if (member.field()) {
                for (var field : type.getDeclaredFields())
                    if (!java.lang.reflect.Modifier.isPrivate(field.getModifiers()) && field.getName().equals(member.name())
                            && Type.getDescriptor(field.getType()).equals(member.descriptor())) return true;
            } else {
                for (var method : type.getDeclaredMethods())
                    if (!java.lang.reflect.Modifier.isPrivate(method.getModifiers()) && method.getName().equals(member.name())
                            && Type.getMethodDescriptor(method).equals(member.descriptor())) return true;
            }
            if (type.getSuperclass() != null) parents.add(type.getSuperclass());
            parents.addAll(List.of(type.getInterfaces()));
        }
        return false;
    }

    /** A later preflight (e.g. switch holders) may reject a caller. Never admit a reduced cohort. */
    public RecordNativeBatch requireComplete(Set<String> accepted) {
        if(!active()||accepted.containsAll(names))return this;
        Set<String> missing=new TreeSet<>(names);missing.removeAll(accepted);
        return new RecordNativeBatch(instrumentation,records,names,changes,
                "record cohort preflight rejected "+String.join(", ",missing)+"; no cohort member was redefined; restart to apply");
    }

    public Outcome commit() { return commit(change -> { }); }
    /** Repair happens only after the one native call returned. Its failure never means rollback. */
    public synchronized Outcome commit(Consumer<Change> repair) {
        if(outcome!=null)return outcome;
        if(!active())return outcome=new Outcome(false,false,"not a record shape cohort",Map.of());
        if(!refusals.isEmpty())return outcome=new Outcome(false,false,refusals.values().iterator().next(),Map.of());
        ClassDefinition[] definitions=changes.stream().map(c -> new ClassDefinition(c.owner(),c.bytes())).toArray(ClassDefinition[]::new);
        try { instrumentation.redefineClasses(definitions); }
        catch(Exception|LinkageError failure) {
            return outcome=new Outcome(true,false,"native record cohort refused: "+Failures.describe(failure),Map.of());
        }
        Map<String,String> failures=new LinkedHashMap<>();
        // Install the committed fact before callbacks: an exceptional repair cannot erase it.
        outcome=new Outcome(true,true,"native record cohort admitted",Map.of());
        for(Change change:changes)try { repair.accept(change); }
        catch(RuntimeException|LinkageError failure) { failures.put(change.name(),Failures.describe(failure)); }
        return outcome=new Outcome(true,true,"native record cohort admitted",failures);
    }

    private static boolean visible(ClassLoader caller,ClassLoader record) {
        for(ClassLoader loader=caller;;loader=loader.getParent()) {
            if(loader==record)return true;
            if(loader==null)return false;
        }
    }

    /** Typed references, including descriptor-only and generic roots; string text is not linkage. */
    private static Set<String> references(byte[] raw) {
        Set<String> names=new HashSet<>();
        class Types {
            void owner(String value) { if(value!=null) {if(value.startsWith("["))descriptor(value);else names.add(value.replace('/','.'));} }
            void type(Type type) {
                if(type.getSort()==Type.OBJECT)owner(type.getInternalName());
                else if(type.getSort()==Type.ARRAY)type(type.getElementType());
                else if(type.getSort()==Type.METHOD) {for(Type arg:type.getArgumentTypes())type(arg);type(type.getReturnType());}
            }
            void descriptor(String value) {if(value!=null)type(Type.getType(value));}
            void signature(String value) {if(value!=null)new SignatureReader(value).accept(new SignatureVisitor(Opcodes.ASM9) {
                private final Deque<String> enclosing=new ArrayDeque<>();
                @Override public void visitClassType(String name) {enclosing.push(name);owner(name);}
                @Override public void visitInnerClassType(String name) {
                    String nested=enclosing.pop()+"$"+name;enclosing.push(nested);owner(nested);
                }
                @Override public void visitEnd() {if(!enclosing.isEmpty())enclosing.pop();}
            });}
            void constant(Object value) {
                if(value instanceof Type type)type(type);
                else if(value instanceof Handle handle) {owner(handle.getOwner());descriptor(handle.getDesc());}
                else if(value instanceof ConstantDynamic dynamic) {
                    descriptor(dynamic.getDescriptor());constant(dynamic.getBootstrapMethod());
                    for(int i=0;i<dynamic.getBootstrapMethodArgumentCount();i++)constant(dynamic.getBootstrapMethodArgument(i));
                } else if(value instanceof AnnotationNode annotation)annotations(List.of(annotation));
                else if(value instanceof List<?> values)values.forEach(this::constant);
            }
            void annotations(List<? extends AnnotationNode> annotations) {
                if(annotations!=null)for(AnnotationNode annotation:annotations) {
                    descriptor(annotation.desc);if(annotation.values!=null)for(int i=1;i<annotation.values.size();i+=2)constant(annotation.values.get(i));
                }
            }
        }
        Types types=new Types();ClassNode node=new ClassNode();
        new ClassReader(raw).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        types.owner(node.superName);node.interfaces.forEach(types::owner);types.signature(node.signature);
        types.owner(node.outerClass);types.owner(node.nestHostClass);
        if(node.nestMembers!=null)node.nestMembers.forEach(types::owner);
        if(node.permittedSubclasses!=null)node.permittedSubclasses.forEach(types::owner);
        types.annotations(node.visibleAnnotations);types.annotations(node.invisibleAnnotations);
        for(FieldNode field:node.fields) {types.descriptor(field.desc);types.signature(field.signature);types.constant(field.value);types.annotations(field.visibleAnnotations);types.annotations(field.invisibleAnnotations);}
        if(node.recordComponents!=null)for(RecordComponentNode component:node.recordComponents) {types.descriptor(component.descriptor);types.signature(component.signature);types.annotations(component.visibleAnnotations);types.annotations(component.invisibleAnnotations);}
        for(MethodNode method:node.methods) {
            types.descriptor(method.desc);types.signature(method.signature);if(method.exceptions!=null)method.exceptions.forEach(types::owner);
            types.annotations(method.visibleAnnotations);types.annotations(method.invisibleAnnotations);types.constant(method.annotationDefault);
            for(TryCatchBlockNode block:method.tryCatchBlocks)types.owner(block.type);
            for(AbstractInsnNode instruction:method.instructions) {
                if(instruction instanceof TypeInsnNode value)types.owner(value.desc);
                else if(instruction instanceof FieldInsnNode value) {types.owner(value.owner);types.descriptor(value.desc);}
                else if(instruction instanceof MethodInsnNode value) {types.owner(value.owner);types.descriptor(value.desc);}
                else if(instruction instanceof InvokeDynamicInsnNode value) {types.descriptor(value.desc);types.constant(value.bsm);for(Object argument:value.bsmArgs)types.constant(argument);}
                else if(instruction instanceof LdcInsnNode value)types.constant(value.cst);
                else if(instruction instanceof MultiANewArrayInsnNode value)types.descriptor(value.desc);
            }
        }
        return names;
    }
}
