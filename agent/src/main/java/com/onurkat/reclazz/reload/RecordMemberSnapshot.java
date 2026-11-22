/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.*;
import com.onurkat.reclazz.transform.CallSiteAdapter;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** Private record members are assembled completely before a Slot preparation can publish them. */
public final class RecordMemberSnapshot {
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private RecordMemberSnapshot() { }
    public record Prepared(RecordSchema.Members members,Map<String,MethodHandle> targets) {
        public Prepared {targets=Map.copyOf(targets);}
    }
    public static Prepared prepare(Class<?> owner,MethodHandles.Lookup ownerLookup,MethodHandles.Lookup companionLookup,
            byte[] incoming,Map<String,MethodHandle> targets,Map<String,MethodHandle> initializers)throws ReflectiveOperationException {
        LookupCapture.requireTrustedCaller(WALKER.getCallerClass(), "preparing a record member snapshot");
        if(!owner.isRecord())throw new IllegalArgumentException("Record member snapshot requires a record");
        if(!ownerLookup.hasFullPrivilegeAccess()||ownerLookup.lookupClass().getClassLoader()!=owner.getClassLoader()
                ||ownerLookup.lookupClass().getNestHost()!=owner.getNestHost())
            throw new SecurityException("Record member snapshot requires its own nest lookup");
        var source=new ClassNode();new ClassReader(incoming).accept(source,ClassReader.SKIP_CODE);
        if((source.access&Opcodes.ACC_RECORD)==0||!"java/lang/Record".equals(source.superName)
                ||!source.name.equals(owner.getName().replace('.','/')))
            throw new IllegalArgumentException("Record snapshot source owner or kind changed");
        String canonical="("+(source.recordComponents==null?"":source.recordComponents.stream().map(c->c.descriptor).collect(java.util.stream.Collectors.joining()))+")V";
        if(source.methods.stream().noneMatch(m->m.name.equals("<init>")&&m.desc.equals(canonical)))
            throw new IllegalArgumentException("Record canonical constructor is missing");
        Class<?> metadata=ownerLookup.defineHiddenClass(RecordMetadataCarrier.generate(incoming),false,MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
        RecordHeader.View header=RecordHeader.prepare(owner,metadata);
        Map<String,Method> carriers=new HashMap<>();
        for(Method method:metadata.getDeclaredMethods())carriers.put(method.getName()+Type.getMethodDescriptor(method),method);
        var methods=new ArrayList<Method>();var fields=new ArrayList<Field>();var constructors=new ArrayList<Constructor<?>>();
        var methodsBySignature=new HashMap<String,Method>();var resolved=new LinkedHashMap<String,MethodHandle>(targets);
        var requiredInitializers=new LinkedHashMap<String,MethodHandle>();var objectMethods=new LinkedHashMap<String,MethodHandle>();
        for(var field:source.fields) {
            MemberForge.fieldSignature(metadata,field.name,field.signature);
            Field root=MemberForge.snapshotField(owner,metadata.getDeclaredField(field.name));RecordHeader.bind(root,header);fields.add(root);
        }
        for(var method:source.methods) {
            if(method.name.equals("<clinit>"))continue;
            MethodType type=MethodType.fromMethodDescriptorString(method.desc,owner.getClassLoader());
            if(method.name.equals("<init>")) {
                MethodHandle initializer=initializers.get(method.desc);
                if(initializer==null)throw new IllegalArgumentException("Record initializer missing: "+method.desc);
                Constructor<?> root=MemberForge.snapshotConstructor(owner,metadata.getDeclaredConstructor(type.parameterArray()),method.access,method.signature,initializer);
                RecordHeader.bind(root,header);constructors.add(root);
                requiredInitializers.put(method.desc,initializer);continue;
            }
            if((method.access&Opcodes.ACC_ABSTRACT)!=0)throw new IllegalArgumentException("Abstract record method: "+method.name);
            boolean statik=Modifier.isStatic(method.access);String hash=CallSiteAdapter.descHash(method.desc);
            String key=statik?InjectedNames.staticSiteKey(method.name,hash):InjectedNames.siteKey(method.name,hash);
            MethodHandle target=resolved.get(key);
            MethodType companionType=statik?type:type.insertParameterTypes(0,owner);
            if(target==null&&companionLookup!=null)try {
                target=companionLookup.findStatic(companionLookup.lookupClass(),method.name,companionType);resolved.put(key,target);
            }catch(NoSuchMethodException absent){/* missing proof is refused below; never quietly publish an old body */}
            if(target==null)throw new IllegalArgumentException("Record method target missing: "+method.name+method.desc);
            Method carrier=carriers.get(method.name+method.desc);
            if(carrier==null)throw new IllegalArgumentException("Record method metadata missing: "+method.name+method.desc);
            Method root=MethodForge.recordDispatchMethod(owner,carrier,target,key,method.signature);
            if(root==null)throw new IllegalStateException("Record method roots unavailable");
            RecordHeader.bind(root,header);
            methods.add(root);methodsBySignature.put(method.name+method.desc,root);
            if(method.name.equals("hashCode")&&method.desc.equals("()I")||method.name.equals("equals")&&method.desc.equals("(Ljava/lang/Object;)Z")
                    ||method.name.equals("toString")&&method.desc.equals("()Ljava/lang/String;"))objectMethods.put(method.name,target);
        }
        var components=new ArrayList<RecordSchema.Component>();
        for(var component:metadata.getRecordComponents()) {
            RecordHeader.bind(component,header);
            Method accessor=methodsBySignature.get(component.getName()+"()"+component.getType().descriptorString());
            if(accessor==null)throw new IllegalArgumentException("Record accessor missing: "+component.getName());
            components.add(new RecordSchema.Component(component.getName(),component.getType(),accessor,component));
        }
        return new Prepared(new RecordSchema.Members(components,methods,fields,constructors,requiredInitializers,requiredInitializers,objectMethods,header),resolved);
    }
}
