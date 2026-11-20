/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

class RecordSchemaLifetimeTest {
    record ParentRecord(int value) { static ParentRecord initialize(ParentRecord receiver,int value){return receiver;} }
    private static class Child extends ClassLoader {
        Child(){super(RecordSchema.class.getClassLoader());}
        Class<?> define(){
            var writer=new ClassWriter(0);writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"com/example/RecordCaller",null,"java/lang/Object",null);
            for(String name:new String[]{"before","after"}) {
                var method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,name,"()Ljava/lang/String;",null,null);
                method.visitCode();method.visitLdcInsn(name);method.visitInsn(Opcodes.ARETURN);method.visitMaxs(1,0);method.visitEnd();
            }
            writer.visitEnd();byte[] bytes=writer.toByteArray();return defineClass("com.example.RecordCaller",bytes,0,bytes.length);
        }
    }
    private static WeakReference<ClassLoader> commitChild(RecordSchema.Slot slot)throws Throwable {
        var loader=new Child();Class<?> caller=loader.define();
        MethodHandle before=MethodHandles.publicLookup().findStatic(caller,"before",MethodType.methodType(String.class));
        MethodHandle after=MethodHandles.publicLookup().findStatic(caller,"after",MethodType.methodType(String.class));
        var site=RecordSchema.callerSite(caller,slot,"view()",before);
        try(var next=slot.prepare(slot.current().members())){next.stage(site,after);assertTrue(next.commit());}
        assertEquals("after",(String)site.dispatcher().invokeExact());return new WeakReference<>(loader);
    }
    private static RecordSchema.Members withTarget(RecordSchema.Slot slot,int group,MethodHandle target) {
        var base=slot.current().members();Map<String,MethodHandle> handle=Map.of("probe",target);
        return new RecordSchema.Members(base.components(),base.methods(),base.fields(),base.constructors(),
                group==0?handle:base.constructorTargets(),group==1?handle:base.initializerTargets(),group==2?handle:base.objectMethods());
    }
    private static WeakReference<ClassLoader> rejectForeignTargets(RecordSchema.Slot slot)throws Throwable {
        var loader=new Child();Class<?> caller=loader.define();
        MethodHandle foreign=MethodHandles.publicLookup().findStatic(caller,"after",MethodType.methodType(String.class));
        var before=slot.current();
        for(int group=0;group<3;group++) {
            var members=withTarget(slot,group,foreign);
            assertThrows(IllegalArgumentException.class,()->{try(var bad=slot.prepare(members)){bad.commit();}},"every persistent handle map needs ownership validation");
            assertSame(before,slot.current());
        }
        var jdk=MethodHandles.publicLookup().findStatic(String.class,"valueOf",MethodType.methodType(String.class,Object.class));
        for(MethodHandle opaque:List.of(MethodHandles.insertArguments(jdk,0,(Object)caller),
                jdk.asType(MethodType.methodType(String.class,caller)))) {
            var members=withTarget(slot,0,opaque);
            assertThrows(IllegalArgumentException.class,()->{try(var bad=slot.prepare(members)){bad.commit();}},"JDK declaration alone cannot prove ownership of captured data or an adapted signature");
            assertSame(before,slot.current());
        }
        return new WeakReference<>(loader);
    }
    @Test void recordHandleMapsRejectForeignAndOpaqueTargetsWithoutRetainingTheirLoader()throws Throwable {
        var slot=RecordSchema.slot(ParentRecord.class);var weak=rejectForeignTargets(slot);
        for(int i=0;i<80&&weak.get()!=null;i++){System.gc();Thread.sleep(20);}
        assertNull(weak.get(),"rejected handle input must never become owned by the parent record slot");
    }
    @Test void recordDeclaredExecutableTargetsRemainUsable()throws Throwable {
        var slot=RecordSchema.slot(ParentRecord.class);var lookup=MethodHandles.lookup();var base=slot.current().members();
        var constructor=lookup.findConstructor(ParentRecord.class,MethodType.methodType(void.class,int.class));
        var initializer=lookup.findStatic(ParentRecord.class,"initialize",MethodType.methodType(ParentRecord.class,ParentRecord.class,int.class));
        var text=lookup.findVirtual(ParentRecord.class,"toString",MethodType.methodType(String.class));
        var members=new RecordSchema.Members(base.components(),base.methods(),base.fields(),base.constructors(),
                Map.of("(I)V",constructor),Map.of("(I)V",initializer),Map.of("toString",text));
        try(var next=slot.prepare(members)){assertTrue(next.commit());}
        ParentRecord value=(ParentRecord)slot.current().members().constructorTargets().get("(I)V").invokeExact(7);
        assertEquals("ParentRecord[value=7]",(String)slot.current().members().objectMethods().get("toString").invokeExact(value));
    }
    @Test void hiddenCompanionInTheRecordsOwnNestRemainsUsable()throws Throwable {
        var writer=new ClassWriter(0);
        writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"com/onurkat/reclazz/bootstrap/RecordOwnedInitializer",null,"java/lang/Object",null);
        var body=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"target","("+org.objectweb.asm.Type.getDescriptor(ParentRecord.class)+")Ljava/lang/String;",null,null);
        body.visitCode();body.visitLdcInsn("owned");body.visitInsn(Opcodes.ARETURN);body.visitMaxs(1,1);body.visitEnd();writer.visitEnd();
        var lookup=MethodHandles.privateLookupIn(ParentRecord.class,MethodHandles.lookup())
                .defineHiddenClass(writer.toByteArray(),true,MethodHandles.Lookup.ClassOption.NESTMATE);
        assertTrue(lookup.lookupClass().isHidden());
        var target=lookup.findStatic(lookup.lookupClass(),"target",MethodType.methodType(String.class,ParentRecord.class));
        var slot=RecordSchema.slot(ParentRecord.class);
        try(var next=slot.prepare(withTarget(slot,2,target))){assertTrue(next.commit());}
        assertEquals("owned",(String)slot.current().members().objectMethods().get("probe").invokeExact(new ParentRecord(1)));
    }
    @Test void recordDoesNotKeepRetiredCallerLoaderAlive()throws Throwable {
        var slot=RecordSchema.slot(ParentRecord.class);var weak=commitChild(slot);
        for(int i=0;i<80&&weak.get()!=null;i++){System.gc();Thread.sleep(20);}
        assertNull(weak.get(),"the live parent record must not retain the disposable caller through a target table");
        assertNotNull(slot.current());assertEquals(ParentRecord.class,slot.physicalComponents().get(0).accessor().getDeclaringClass());
    }
}
