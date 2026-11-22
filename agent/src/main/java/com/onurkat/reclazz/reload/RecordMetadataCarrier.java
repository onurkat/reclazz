/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;

/** Full, inert record metadata for a hidden nestmate; executable code belongs to the companion. */
public final class RecordMetadataCarrier {
    private RecordMetadataCarrier() { }
    public static byte[] generate(byte[] incoming) {return generate(incoming,false);}
    public static byte[] generateCaller(byte[] incoming) {return generate(incoming,true);}
    private static byte[] generate(byte[] incoming,boolean caller) {
        var source=new ClassNode();new ClassReader(incoming).accept(source,ClassReader.SKIP_CODE);
        if(!caller&&((source.access&Opcodes.ACC_RECORD)==0||!"java/lang/Record".equals(source.superName)))
            throw new IllegalArgumentException("Record metadata requires a record classfile");
        var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
        // Metadata must not become another runtime subtype of a possibly sealed interface.
        // Keep the complete interface types in the generic signature, including a plain
        // signature when javac emitted none, so interface type annotations still parse.
        String signature=source.signature;
        if(signature==null&&(caller||!source.interfaces.isEmpty())) {
            var plain=new StringBuilder("L").append(source.superName).append(';');
            for(String type:source.interfaces)plain.append('L').append(type).append(';');
            signature=plain.toString();
        }
        writer.visit(source.version,caller?Opcodes.ACC_PUBLIC|Opcodes.ACC_FINAL|Opcodes.ACC_SYNTHETIC:source.access,source.name+(caller?"$__reclazz$CallerMetadata":"$__reclazz$RecordMetadata"),signature,caller?"java/lang/Object":source.superName,new String[0]);
        if(source.visibleAnnotations!=null)for(var annotation:source.visibleAnnotations)annotation.accept(writer.visitAnnotation(annotation.desc,true));
        if(source.invisibleAnnotations!=null)for(var annotation:source.invisibleAnnotations)annotation.accept(writer.visitAnnotation(annotation.desc,false));
        if(source.visibleTypeAnnotations!=null)for(var annotation:source.visibleTypeAnnotations)
            annotation.accept(writer.visitTypeAnnotation(annotation.typeRef,annotation.typePath,annotation.desc,true));
        if(source.invisibleTypeAnnotations!=null)for(var annotation:source.invisibleTypeAnnotations)
            annotation.accept(writer.visitTypeAnnotation(annotation.typeRef,annotation.typePath,annotation.desc,false));
        if(source.recordComponents!=null)for(var component:source.recordComponents)component.accept(writer);
        for(var field:source.fields)field.accept(writer);
        for(var method:source.methods) {
            // No user initialization is needed to parse metadata. Code and ObjectMethods indy
            // descriptors naming the nominal record must not enter the hidden carrier.
            if(method.name.equals("<clinit>"))continue;
            var visitor=writer.visitMethod(method.access,method.name,method.desc,method.signature,method.exceptions.toArray(String[]::new));
            boolean concrete=(method.access&(Opcodes.ACC_NATIVE|Opcodes.ACC_ABSTRACT))==0;
            method.accept(new MethodVisitor(Opcodes.ASM9,visitor){
                @Override public void visitEnd() {
                    if(concrete){super.visitCode();super.visitInsn(Opcodes.ACONST_NULL);super.visitInsn(Opcodes.ATHROW);super.visitMaxs(0,0);}
                    super.visitEnd();
                }
            });
        }
        writer.visitEnd();return writer.toByteArray();
    }
}
