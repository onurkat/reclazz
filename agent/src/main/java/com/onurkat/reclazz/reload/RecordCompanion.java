/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.bootstrap.InjectedNames;
import com.onurkat.reclazz.transform.TransformContext;
import com.onurkat.reclazz.transform.SafeClassWriter;
import com.onurkat.reclazz.transform.CallSiteAdapter;
import java.lang.invoke.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;

/** Build executable record bodies and private roots without publishing a target or executing user code. */
public final class RecordCompanion {
    private static final StackWalker WALKER=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private RecordCompanion() { }
    public record Prepared(MethodHandles.Lookup lookup,RecordMemberSnapshot.Prepared snapshot) { }
    public static Prepared prepare(Class<?> owner,MethodHandles.Lookup ownerLookup,byte[] incoming,
            TransformContext context,int version)throws ReflectiveOperationException {
        LookupCapture.requireTrustedCaller(WALKER.getCallerClass(),"preparing a record companion");
        if(!owner.isRecord()||!ownerLookup.hasFullPrivilegeAccess()
                ||ownerLookup.lookupClass().getClassLoader()!=owner.getClassLoader()
                ||ownerLookup.lookupClass().getNestHost()!=owner.getNestHost())
            throw new SecurityException("Record companion requires its own record nest lookup");
        var source=new ClassNode();new ClassReader(incoming).accept(source,ClassReader.SKIP_CODE);
        String name=owner.getName().replace('.','/');
        if(!source.name.equals(name)||(source.access&Opcodes.ACC_RECORD)==0||!"java/lang/Record".equals(source.superName))
            throw new IllegalArgumentException("Record companion source owner or kind changed");
        requirePhysicalHeader(owner,source,"record");
        var baseline=context.getMetadata(name);
        if(baseline==null)throw new IllegalArgumentException("Record companion requires a watched physical baseline");
        var diff=StructuralAnalyzer.analyze(baseline,incoming);
        Set<String> constructors=new LinkedHashSet<>();
        for(var method:source.methods)if(method.name.equals("<init>"))constructors.add(method.desc);
        var generated=CompanionGenerator.generate(name,RecordObjectMethodsAdapter.rewrite(incoming),diff,version,
                Set.of(),Set.of(),context,constructors,owner.getClassLoader());
        var declared=new HashSet<String>();
        for(var method:source.methods)declared.add(InjectedNames.siteKey(method.name,CallSiteAdapter.descHash(method.desc),(method.access&Opcodes.ACC_STATIC)!=0));
        var previous=new LinkedHashMap<String,TransformContext.MethodSig>();
        for(var method:baseline.getMethods())previous.put(method.name()+method.descriptor(),method);
        // Logical additions from previous saves are absent from the physical baseline.
        for(var method:owner.getDeclaredMethods())if(!InjectedNames.isInjected(method.getName()))
            previous.put(method.getName()+Type.getMethodDescriptor(method),new TransformContext.MethodSig(method.getName(),Type.getMethodDescriptor(method),method.getModifiers()));
        var removed=new ArrayList<TransformContext.MethodSig>();
        for(var method:previous.values())if(!method.name().startsWith("<")
                &&!declared.contains(InjectedNames.siteKey(method.name(),CallSiteAdapter.descHash(method.descriptor()),(method.access()&Opcodes.ACC_STATIC)!=0)))
            // Record bridge removals are real retired descriptors, unlike ordinary-class lambda salvage.
            removed.add(new TransformContext.MethodSig(method.name(),method.descriptor(),method.access()&~0x1040));
        var removal=RemovedMethodTargets.plan(owner,ownerLookup,removed);
        if(!removal.kept().isEmpty()||removal.targets().size()!=removed.size())
            throw new IllegalArgumentException("Record removal target could not be prepared");
        var mappings=new LinkedHashMap<>(generated.getMethodHandleKeys());
        byte[] executable=withRemovals(generated.getBytecode(),removal.targets(),mappings);
        // The frozen class data has only record-owned/inherited removal semantics. No caller handles
        // are stored in the record Slot, and no name-keyed registry keeps this hidden class alive.
        var lookup=ownerLookup.defineHiddenClassWithClassData(executable,Map.copyOf(removal.targets()),false,MethodHandles.Lookup.ClassOption.NESTMATE);
        var targets=new LinkedHashMap<String,MethodHandle>();
        for(var entry:mappings.entrySet()) {
            String signature=entry.getValue();int start=signature.indexOf('(');
            targets.put(entry.getKey(),lookup.findStatic(lookup.lookupClass(),signature.substring(0,start),
                    MethodType.fromMethodDescriptorString(signature.substring(start),owner.getClassLoader())));
        }
        var initializers=new LinkedHashMap<String,MethodHandle>();
        for(String descriptor:constructors)initializers.put(descriptor,lookup.findStatic(lookup.lookupClass(),
                AddedConstructorGenerator.name(descriptor),MethodType.fromMethodDescriptorString(
                        AddedConstructorGenerator.descriptor(name,descriptor),owner.getClassLoader())));
        return new Prepared(lookup,RecordMemberSnapshot.prepare(owner,ownerLookup,lookup,incoming,targets,initializers));
    }
    /** Stable physical admission cannot change class visibility, finality or interface order. */
    static void requirePhysicalHeader(Class<?> owner,ClassNode source,String kind) {
        int visibility=Opcodes.ACC_PUBLIC|Opcodes.ACC_PRIVATE|Opcodes.ACC_PROTECTED|Opcodes.ACC_STATIC;
        int headerAccess=source.access;
        for(var inner:source.innerClasses)if(inner.name.equals(source.name))headerAccess=(headerAccess&~visibility)|(inner.access&visibility);
        int stable=visibility|Opcodes.ACC_FINAL|Opcodes.ACC_ABSTRACT|Opcodes.ACC_INTERFACE|Opcodes.ACC_ENUM|Opcodes.ACC_ANNOTATION|Opcodes.ACC_SYNTHETIC;
        if(((headerAccess^owner.getModifiers())&stable)!=0
                ||!source.interfaces.equals(Arrays.stream(owner.getInterfaces()).map(Type::getInternalName).toList()))
            throw new IllegalArgumentException("physical "+kind+" header changed; restart to apply");
    }
    static byte[] withRemovals(byte[] generated,Map<String,MethodHandle> removals,Map<String,String> mappings) {
        if(removals.isEmpty())return generated;
        var reader=new ClassReader(generated);var writer=new SafeClassWriter(reader,ClassWriter.COMPUTE_FRAMES);
        reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
            @Override public void visitEnd() {
                int ordinal=0;
                for(var entry:removals.entrySet()) {
                    String name=InjectedNames.PREFIX+"removed$"+ordinal++;
                    String descriptor=entry.getValue().type().toMethodDescriptorString();
                    var method=super.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_SYNTHETIC,name,descriptor,null,null);
                    method.visitCode();int local=0;
                    for(Type argument:Type.getArgumentTypes(descriptor)) {
                        method.visitVarInsn(argument.getOpcode(Opcodes.ILOAD),local);local+=argument.getSize();
                    }
                    method.visitInvokeDynamicInsn(name,descriptor,new Handle(Opcodes.H_INVOKESTATIC,
                            "com/onurkat/reclazz/bootstrap/RecordRemovedTargets","bootstrap",
                            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;",false),entry.getKey());
                    method.visitInsn(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN));method.visitMaxs(0,0);method.visitEnd();
                    mappings.put(entry.getKey(),name+descriptor);
                }
                super.visitEnd();
            }
        },0);
        return writer.toByteArray();
    }

}
