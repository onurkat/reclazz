/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;
import java.util.*;

/** Immutable shape, not byte identity or executable code. Member order is normalized for VM bytes. */
public record RawClassSchema(int access, String name, String parent, List<String> interfaces,
        List<Member> fields, List<Member> methods, String nestHost, Set<String> nestMembers,
        List<Component> components, Set<String> permitted, Set<String> restrictedAttributes) {
    public record Member(String name,String descriptor,int access) { }
    public record Component(String name,String descriptor,String signature) { }
    public RawClassSchema {
        interfaces=List.copyOf(interfaces);fields=List.copyOf(fields);methods=List.copyOf(methods);
        nestMembers=Set.copyOf(nestMembers);components=List.copyOf(components);permitted=Set.copyOf(permitted);
        restrictedAttributes=Set.copyOf(restrictedAttributes);
    }
    public static RawClassSchema read(byte[] bytes) {
        var reader=new ClassReader(bytes);var node=new ClassNode();
        reader.accept(node,ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        var fields=new ArrayList<Member>();for(var f:node.fields)fields.add(new Member(f.name,f.desc,f.access&0xffff));
        var methods=new ArrayList<Member>();for(var m:node.methods)methods.add(new Member(m.name,m.desc,m.access&0xffff));
        var order=Comparator.comparing(Member::name).thenComparing(Member::descriptor).thenComparingInt(Member::access);
        fields.sort(order);methods.sort(order);
        var components=new ArrayList<Component>();if(node.recordComponents!=null)for(var c:node.recordComponents)components.add(new Component(c.name,c.descriptor,c.signature));
        return new RawClassSchema(node.access&~(Opcodes.ACC_SUPER|Opcodes.ACC_DEPRECATED),node.name,node.superName,
                node.interfaces,fields,methods,node.nestHostClass,
                node.nestMembers==null?Set.of():new HashSet<>(node.nestMembers),components,
                node.permittedSubclasses==null?Set.of():new HashSet<>(node.permittedSubclasses),restrictedAttributes(reader));
    }
    /** Partial fallback proof: <clinit>/restricted attribute presence still needs VM bytes. */
    public boolean knownChangeFrom(Class<?> owner) {
        try {
            int flags=Opcodes.ACC_PUBLIC|Opcodes.ACC_FINAL|Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT|Opcodes.ACC_SYNTHETIC|Opcodes.ACC_ANNOTATION|Opcodes.ACC_ENUM;
            if((access&flags)!=(owner.getModifiers()&flags))return true;
            if(restrictedAttributes.contains("Record")!=owner.isRecord())return true;
            String superclass=owner.isInterface()?"java/lang/Object":owner.getSuperclass()==null?null:Type.getInternalName(owner.getSuperclass());
            if(!Objects.equals(parent,superclass) || !interfaces.equals(Arrays.stream(owner.getInterfaces()).map(Type::getInternalName).toList()))return true;
            Set<Member> liveFields=new HashSet<>(),liveMethods=new HashSet<>();
            for(var f:com.onurkat.reclazz.bootstrap.ReflectionBridge.physicalDeclaredFields(owner))liveFields.add(new Member(f.getName(),f.getType().descriptorString(),f.getModifiers()&0xffff));
            for(var m:com.onurkat.reclazz.bootstrap.ReflectionBridge.physicalDeclaredMethods(owner))liveMethods.add(new Member(m.getName(),Type.getMethodDescriptor(m),m.getModifiers()&0xffff));
            for(var c:com.onurkat.reclazz.bootstrap.ReflectionBridge.physicalDeclaredConstructors(owner))liveMethods.add(new Member("<init>",Type.getConstructorDescriptor(c),c.getModifiers()&0xffff));
            Set<Member> nextMethods=new HashSet<>();for(Member m:methods)if(!m.name().equals("<clinit>"))nextMethods.add(m);
            return !liveFields.equals(new HashSet<>(fields)) || !liveMethods.equals(nextMethods);
        } catch(RuntimeException|LinkageError unavailable){return false;}
    }
    // Preserve empty restricted attributes too; ClassVisitor has no callback for
    // an empty NestMembers/PermittedSubclasses table (JVMS 4.1,4.5,4.6,4.7).
    private static Set<String> restrictedAttributes(ClassReader reader) {
        int p=reader.header+6;p+=2+2*reader.readUnsignedShort(p);
        for(int table=0;table<2;table++){
            int members=reader.readUnsignedShort(p);p+=2;
            for(int i=0;i<members;i++){
                int attributes=reader.readUnsignedShort(p+6);p+=8;
                for(int a=0;a<attributes;a++)p+=6+reader.readInt(p+2);
            }
        }
        int count=reader.readUnsignedShort(p);p+=2;Set<String> result=new HashSet<>();char[] chars=new char[reader.getMaxStringLength()];
        for(int i=0;i<count;i++){
            String name=reader.readUTF8(p,chars);
            if(Set.of("NestHost","NestMembers","Record","PermittedSubclasses").contains(name))result.add(name);
            p+=6+reader.readInt(p+2);
        }
        return result;
    }
}
