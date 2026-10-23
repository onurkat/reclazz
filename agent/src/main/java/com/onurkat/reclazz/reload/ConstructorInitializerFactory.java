/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.InjectedNames;
import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.transform.SafeClassWriter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.invoke.*;
import java.util.Set;

/** Lazily extracts the physical constructor of an instrumented delegation target. */
public final class ConstructorInitializerFactory {
    private ConstructorInitializerFactory() { }
    public static MethodHandle create(Class<?> owner,String descriptor) {
        if(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass()!=com.onurkat.reclazz.bootstrap.MemberForge.class)
            throw new SecurityException("Constructor initializers are reload-engine internal");
        try {
            var lookup=LookupCapture.get(owner);
            if(lookup==null) {
                java.lang.reflect.Field field;
                try{field=owner.getDeclaredField(InjectedNames.LOOKUP_FIELD);}catch(NoSuchFieldException absent){return null;}
                field.setAccessible(true);lookup=(MethodHandles.Lookup)field.get(null);
            }
            if(lookup==null)return null;
            ClassNode source=new ClassNode();
            try(var stream=owner.getResourceAsStream("/"+owner.getName().replace('.','/')+".class")) {
                if(stream==null)return null;
                new ClassReader(stream).accept(source,ClassReader.EXPAND_FRAMES);
            }
            MethodNode constructor=source.methods.stream().filter(m->m.name.equals("<init>") && m.desc.equals(descriptor)).findFirst().orElse(null);
            if(constructor==null)return null;
            String name=source.name+"$__reclazz$ConstructorInitializer";
            ClassWriter writer=new SafeClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
            writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,name,null,"java/lang/Object",null);
            var method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,AddedConstructorGenerator.name(descriptor),AddedConstructorGenerator.descriptor(source.name,descriptor),null,null);
            var adapter=new CompanionGenerator.CompanionMethodAdapter(method,source.name,name,Set.of(),true);
            AddedConstructorGenerator.emit(source,constructor,writer,adapter);writer.visitEnd();
            var hidden=lookup.defineHiddenClass(writer.toByteArray(),true,MethodHandles.Lookup.ClassOption.NESTMATE);
            return hidden.findStatic(hidden.lookupClass(),AddedConstructorGenerator.name(descriptor),
                    MethodType.fromMethodDescriptorString(AddedConstructorGenerator.descriptor(source.name,descriptor),owner.getClassLoader()));
        }catch(Throwable failure){throw new IllegalStateException("Cannot extract constructor initializer: "+owner.getName()+descriptor,failure);}
    }
}
