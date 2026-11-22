/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;
import java.util.*;import org.objectweb.asm.*;import org.objectweb.asm.signature.*;import org.objectweb.asm.tree.*;
/** Shared class-file linkage extraction; never defines a referenced class. */
public final class ClassReferences {
    private ClassReferences(){}
    /** Class-file linkage only; candidate classification excludes lexical nest ownership. */
    public static Set<String> typed(ClassNode node,boolean ownership) {
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
        Types types=new Types();
        types.owner(node.superName);node.interfaces.forEach(types::owner);types.signature(node.signature);
        if(ownership){
            types.owner(node.outerClass);types.owner(node.nestHostClass);
            if(node.nestMembers!=null)node.nestMembers.forEach(types::owner);
            if(node.permittedSubclasses!=null)node.permittedSubclasses.forEach(types::owner);
        }
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
