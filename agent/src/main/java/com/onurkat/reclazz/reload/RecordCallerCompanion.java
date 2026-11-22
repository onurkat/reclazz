/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.*;
import com.onurkat.reclazz.transform.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

/** Prepare executable caller bodies and complete roots without publishing ordinary registries. */
public final class RecordCallerCompanion {
    private static final StackWalker WALKER=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private RecordCallerCompanion() { }
    public record Prepared(RecordCaller.View members,Map<String,MethodHandle> targets,Map<String,MethodHandle> fieldInitializers,Map<String,String> refusedInitializers) {
        public Prepared {targets=Map.copyOf(targets);fieldInitializers=Map.copyOf(fieldInitializers);refusedInitializers=Map.copyOf(refusedInitializers);}
    }
    public static Prepared prepare(Class<?> owner,MethodHandles.Lookup ownerLookup,byte[] incoming,TransformContext context,int version)throws ReflectiveOperationException {
        LookupCapture.requireTrustedCaller(WALKER.getCallerClass(),"preparing record-cohort caller companion");
        if(owner.isRecord()||owner.isInterface()||owner.isEnum()||owner.isAnnotation()||Modifier.isAbstract(owner.getModifiers()))
            throw new IllegalArgumentException("A record/interface/enum/abstract class that calls the changed record is not supported yet; restart to apply");
        if(!ownerLookup.hasFullPrivilegeAccess()||ownerLookup.lookupClass().getClassLoader()!=owner.getClassLoader()||ownerLookup.lookupClass().getNestHost()!=owner.getNestHost())
            throw new SecurityException("Caller companion requires its own nest lookup");
        var source=new ClassNode();new ClassReader(incoming).accept(source,ClassReader.SKIP_CODE);
        String name=owner.getName().replace('.','/');
        if(!source.name.equals(name)||(source.access&(Opcodes.ACC_RECORD|Opcodes.ACC_INTERFACE|Opcodes.ACC_ENUM|Opcodes.ACC_ANNOTATION|Opcodes.ACC_ABSTRACT))!=0)
            throw new IllegalArgumentException("Caller source owner changed, or a record/interface/enum/abstract caller is not supported yet; restart to apply");
        RecordCompanion.requirePhysicalHeader(owner,source,"caller");
        String superclass=owner.getSuperclass()==null?null:owner.getSuperclass().getName().replace('.','/');
        if(!Objects.equals(superclass,source.superName)||!Set.copyOf(source.interfaces).equals(Arrays.stream(owner.getInterfaces()).map(c->c.getName().replace('.','/')).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalArgumentException("Caller physical hierarchy changed");
        if(source.methods.stream().anyMatch(m->(m.access&(Opcodes.ACC_NATIVE|Opcodes.ACC_ABSTRACT))!=0))
            throw new IllegalArgumentException("Caller requires executable Java bodies");
        var baseline=context.getMetadata(name);if(baseline==null)throw new IllegalArgumentException("Caller requires a watched physical baseline");
        var diff=StructuralAnalyzer.analyze(baseline,incoming);
        var descriptors=new LinkedHashSet<String>();for(var method:source.methods)if(method.name.equals("<init>"))descriptors.add(method.desc);
        var generated=CompanionGenerator.generate(name,incoming,diff,version,Set.of(),Set.of(),context,descriptors,owner.getClassLoader());
        var present=new HashSet<String>();for(var method:source.methods)present.add(InjectedNames.siteKey(method.name,CallSiteAdapter.descHash(method.desc),(method.access&Opcodes.ACC_STATIC)!=0));
        var previous=new LinkedHashMap<String,TransformContext.MethodSig>();for(var method:baseline.getMethods())previous.put(method.name()+method.descriptor(),method);
        for(var method:owner.getDeclaredMethods())if(!InjectedNames.isInjected(method.getName()))previous.put(method.getName()+Type.getMethodDescriptor(method),new TransformContext.MethodSig(method.getName(),Type.getMethodDescriptor(method),method.getModifiers()));
        var removed=new ArrayList<TransformContext.MethodSig>();for(var method:previous.values())if(!method.name().startsWith("<")&&!present.contains(InjectedNames.siteKey(method.name(),CallSiteAdapter.descHash(method.descriptor()),(method.access()&Opcodes.ACC_STATIC)!=0)))removed.add(method);
        var removal=RemovedMethodTargets.plan(owner,ownerLookup,removed);
        if(!removal.kept().isEmpty())throw new IllegalArgumentException("Caller removal requires unresolved lambda salvage");
        var mappings=new LinkedHashMap<>(generated.getMethodHandleKeys());
        byte[] executable=RecordCompanion.withRemovals(generated.getBytecode(),removal.targets(),mappings);
        var lookup=ownerLookup.defineHiddenClassWithClassData(executable,Map.copyOf(removal.targets()),false,MethodHandles.Lookup.ClassOption.NESTMATE);
        var targets=new LinkedHashMap<String,MethodHandle>();for(var entry:mappings.entrySet()) {
            String value=entry.getValue();int start=value.indexOf('(');
            targets.put(entry.getKey(),lookup.findStatic(lookup.lookupClass(),value.substring(0,start),MethodType.fromMethodDescriptorString(value.substring(start),owner.getClassLoader())));
        }
        Class<?> metadata=ownerLookup.defineHiddenClass(RecordMetadataCarrier.generateCaller(incoming),false,MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
        var header=RecordHeader.prepareCaller(owner,metadata);
        var carriers=new HashMap<String,Method>();for(var method:metadata.getDeclaredMethods())carriers.put(method.getName()+Type.getMethodDescriptor(method),method);
        var methods=new ArrayList<Method>();var fields=new ArrayList<Field>();var constructors=new ArrayList<Constructor<?>>();var initializers=new LinkedHashMap<String,MethodHandle>();
        for(var field:source.fields){MemberForge.fieldSignature(metadata,field.name,field.signature);Field root=MemberForge.snapshotField(owner,metadata.getDeclaredField(field.name));RecordHeader.bind(root,header);fields.add(root);}
        for(var method:source.methods) {
            if(method.name.equals("<clinit>"))continue;
            MethodType type=MethodType.fromMethodDescriptorString(method.desc,owner.getClassLoader());
            if(method.name.equals("<init>")) {
                MethodHandle initializer=lookup.findStatic(lookup.lookupClass(),AddedConstructorGenerator.name(method.desc),MethodType.fromMethodDescriptorString(AddedConstructorGenerator.descriptor(name,method.desc),owner.getClassLoader()));
                Constructor<?> root=MemberForge.snapshotConstructor(owner,metadata.getDeclaredConstructor(type.parameterArray()),method.access,method.signature,initializer);
                RecordHeader.bind(root,header);constructors.add(root);initializers.put(method.desc,initializer);continue;
            }
            String key=InjectedNames.siteKey(method.name,CallSiteAdapter.descHash(method.desc),(method.access&Opcodes.ACC_STATIC)!=0);
            MethodHandle target=targets.get(key);if(target==null)throw new IllegalArgumentException("Caller method target missing: "+method.name+method.desc);
            Method root=MethodForge.callerDispatchMethod(owner,carriers.get(method.name+method.desc),target,key,method.signature);RecordHeader.bind(root,header);methods.add(root);
        }
        var fieldInitializers=new LinkedHashMap<String,MethodHandle>();for(String key:generated.getInstancePlan().initialisers.keySet()) {
            String field=key.substring(0,key.indexOf(':'));
            fieldInitializers.put(key,lookup.findStatic(lookup.lookupClass(),InstanceInitialiserSlicer.methodName(field),MethodType.methodType(Object.class,owner)));
        }
        return new Prepared(RecordCaller.prepare(owner,methods,fields,constructors,initializers,header),targets,fieldInitializers,generated.getInstancePlan().refused);
    }
}
