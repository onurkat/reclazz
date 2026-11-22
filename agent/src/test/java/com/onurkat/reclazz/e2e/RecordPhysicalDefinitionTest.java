/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.bootstrap.*;
import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.reload.*;
import com.onurkat.reclazz.transform.*;
import java.lang.instrument.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;

/** Real stock JNI admission of retained physical payloads, distinct from the still-disabled S coordinator. */
class RecordPhysicalDefinitionTest {
    @TempDir Path tmp;
    @Test void nativeBatchUsesStablePhysicalDefinitionsAndKeepsFailureAtomic()throws Exception {
        var harness=new RecordComponentMetadataTest();harness.tmp=tmp;harness.runProbe(Probe.class,"RECORD_PHYSICAL_DEFINITION_OK");
    }
    public static class Probe extends TransformTestBase {
        static int checks;
        static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
        static byte[] addField(byte[] bytes) {
            var reader=new ClassReader(bytes);var writer=new ClassWriter(reader,0);
            reader.accept(new ClassVisitor(Opcodes.ASM9,writer){@Override public void visitEnd(){super.visitField(Opcodes.ACC_PUBLIC,"__reclazz$invalidAddedField","I",null,null).visitEnd();super.visitEnd();}},0);
            return writer.toByteArray();
        }
        static byte[] callerBody(byte[] bytes) {
            var reader=new ClassReader(bytes);var writer=new ClassWriter(reader,0);boolean[] patched={false};
            reader.accept(new ClassVisitor(Opcodes.ASM9,writer){@Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] errors) {
                var method=super.visitMethod(access,name,desc,signature,errors);
                if(!name.equals(InjectedNames.renamed("value",CallSiteAdapter.descHash("()I"))))return method;
                return new MethodVisitor(Opcodes.ASM9,method){@Override public void visitInsn(int opcode){if(opcode==Opcodes.ICONST_1){opcode=Opcodes.ICONST_2;patched[0]=true;}super.visitInsn(opcode);}};
            }},0);check(patched[0],"patched the actual renamed physical caller body");return writer.toByteArray();
        }
        static Throwable refusal(Instrumentation instrumentation,ClassDefinition... definitions)throws Exception {
            try {PhysicalDefinitions.redefine(instrumentation,definitions);throw new AssertionError("invalid physical cohort was admitted");}
            catch(UnmodifiableClassException|UnsupportedOperationException expected){checks++;return expected;}
        }
        public static void main(String[] args)throws Throwable {
            String recordName="com/example/PhysicalRecord",callerName="com/example/PhysicalCaller";
            var recordSource=new SourceFile("com.example.PhysicalRecord","package com.example; public record PhysicalRecord(int value){public String label(){return \"old\";}}");
            var original=compile(recordSource,new SourceFile("com.example.PhysicalCaller","package com.example; public class PhysicalCaller {public PhysicalRecord record;public static int value(){return 1;}}"));
            byte[] incoming=compile(new SourceFile("com.example.PhysicalRecord","package com.example; public record PhysicalRecord(int value,String added){public String label(){return \"new:\"+added;}}" )).get(recordName);
            var context=new TransformContext();context.addWatched(recordName);context.addWatched(callerName);
            var transformer=new ReclazzTransformer(context,AgentConfig.parse(null));
            class Loader extends ClassLoader {
                final Map<String,byte[]> emitted=new HashMap<>();
                Loader(){super(Probe.class.getClassLoader());}
                @Override protected Class<?> findClass(String binary)throws ClassNotFoundException {
                    String internal=binary.replace('.','/');byte[] raw=original.get(internal);if(raw==null)throw new ClassNotFoundException(binary);
                    try {byte[] changed=transformer.transform(this,internal,null,null,raw);if(changed!=null)raw=changed;}
                    catch(Exception failure){throw new ClassNotFoundException(binary,failure);}
                    emitted.put(internal,raw.clone());return defineClass(binary,raw,0,raw.length);
                }
            }
            var loader=new Loader();Class<?> owner=loader.loadClass(recordName.replace('/','.')),caller=loader.loadClass(callerName.replace('/','.'));
            byte[] recordPhysical=loader.emitted.get(recordName),callerPhysical=loader.emitted.get(callerName);
            check(Arrays.equals(PhysicalDefinitions.get(owner),recordPhysical)&&Arrays.equals(PhysicalDefinitions.get(caller),callerPhysical),"retain actual complete emitted definitions by loader");
            var field=owner.getDeclaredField(InjectedNames.LOOKUP_FIELD);field.setAccessible(true);var lookup=(MethodHandles.Lookup)field.get(null);
            var physicalConstructor=owner.getDeclaredConstructor(int.class);Object previous=physicalConstructor.newInstance(7);var held=owner.getDeclaredMethod("label");
            check(held.invoke(previous).equals("old")&&caller.getDeclaredMethod("value").invoke(null).equals(1),"actual old record and caller execute");
            LookupCapture.trust(Probe.class);LookupCapture.trust(RecordCompanion.class);LookupCapture.trust(RecordMemberSnapshot.class);LookupCapture.trust(ProtectedCallResolver.class);LookupCapture.store(owner,lookup);LookupCapture.seal();
            var instrumentation=RecordComponentMetadataTest.ProbeAgent.instrumentation;
            check(instrumentation.isRedefineClassesSupported()&&instrumentation.isModifiableClass(owner)&&instrumentation.isModifiableClass(caller),"real child Instrumentation admits redefinition capability");
            ClassFileTransformer observer=new ClassFileTransformer(){@Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] raw)throws IllegalClassFormatException {
                return type==owner||type==caller?transformer.transform(loader,name,type,domain,raw):null;
            }};
            instrumentation.addTransformer(observer,true);
            try {
                byte[] changedCaller=callerBody(callerPhysical);var prepared=RecordCompanion.prepare(owner,lookup,incoming,context,1);var slot=RecordSchema.slot(owner);var initial=slot.current();
                var baseline=KeepBaseline.get(owner);
                try(var failed=slot.prepareVirtual(prepared.snapshot().members())) {
                    failed.stageDispatch(owner,prepared.snapshot().targets());
                    Throwable refused=refusal(instrumentation,new ClassDefinition(caller,changedCaller),new ClassDefinition(owner,addField(recordPhysical)));
                    check(slot.current()==initial&&owner.getRecordComponents().length==1&&held.invoke(previous).equals("old"),"native failure cannot publish staged logical record");
                    check(caller.getDeclaredMethod("value").invoke(null).equals(1),"actual native batch failure keeps the valid caller old too");
                    check(Arrays.equals(PhysicalDefinitions.get(owner),recordPhysical)&&Arrays.equals(PhysicalDefinitions.get(caller),callerPhysical),"native refusal preserves both confirmed payloads");
                    System.out.println("PHYSICAL_NATIVE_REFUSAL runtime="+Runtime.version()+" type="+refused.getClass().getSimpleName());
                }
                check(slot.current()==initial,"closing failed native preparation preserves epoch");
                try(var pending=slot.prepareVirtual(prepared.snapshot().members())) {
                    pending.stageDispatch(owner,prepared.snapshot().targets());
                    PhysicalDefinitions.redefine(instrumentation,new ClassDefinition(owner,recordPhysical),new ClassDefinition(caller,changedCaller));
                    check(slot.current()==initial&&owner.getRecordComponents().length==1&&held.invoke(previous).equals("old"),"real native success still precedes logical activation");
                    check(caller.getDeclaredMethod("value").invoke(null).equals(2),"real VM ran the newly admitted physical caller body");
                    check(Arrays.equals(PhysicalDefinitions.get(caller),changedCaller)&&Arrays.equals(PhysicalDefinitions.get(owner),recordPhysical),"successful JNI return confirms its actual emitted payloads");
                    check(pending.commit(),"logical record activation after physical batch admission");
                }
                check(KeepBaseline.recordVirtualSchemaApplied(owner,incoming),"committed logical constructor receipt is recorded separately");
                check(KeepBaseline.recordLogicalConstructorBodiesUnchanged(owner,incoming)&&!KeepBaseline.constructorBodiesUnchanged(owner,incoming),"logical constructor receipt does not impersonate physical admission");
                check(owner.getRecordComponents().length==2&&ReflectionBridge.physicalRecordComponents(owner).length==1,"logical components change while full VM Record attribute stays stable");
                check(held.invoke(previous).equals("new:null"),"held physical method reaches new logical companion after real JNI admission");
                Object next=owner.getDeclaredConstructor(int.class,String.class).newInstance(8,"fresh");
                check(owner.getDeclaredMethod("label").invoke(next).equals("new:fresh"),"new canonical constructor works after real physical admission");
                check(owner.getDeclaredMethod("value").invoke(previous).equals(7)&&owner.getDeclaredMethod("added").invoke(previous)==null,"compatible old value is preserved and added field defaults");
                check(Objects.equals(KeepBaseline.get(owner),baseline)&&KeepBaseline.constructorBodiesUnchanged(owner,original.get(recordName)),"S physical admission does not advance or invent physical constructor proof");
                check(RecordReloadPlan.ready(RecordReloadPlan.Route.STOCK),"C3 physical proof is separate from save-specific stock admission");
                // A later ordinary native body update must be retained instead of restoring startup code.
                PhysicalDefinitions.redefine(instrumentation,new ClassDefinition(caller,callerPhysical));
                check(caller.getDeclaredMethod("value").invoke(null).equals(1)&&Arrays.equals(PhysicalDefinitions.get(caller),callerPhysical),"later terminal physical payload supersedes its earlier native body");
            }finally{instrumentation.removeTransformer(observer);}
            System.out.println("RECORD_PHYSICAL_DEFINITION_OK checks="+checks+" runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));
        }
    }
}
