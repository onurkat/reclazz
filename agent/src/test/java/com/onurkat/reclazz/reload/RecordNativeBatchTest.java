/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.agent.JvmCapabilityProbe.RecordCapability;
import com.onurkat.reclazz.transform.KeepBaseline;
import java.lang.instrument.*;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Coordinator unit evidence only; real enhanced-VM admission is a separate C2 gate. */
class RecordNativeBatchTest {
    @TempDir Path root;
    private static final String P="com.example.P",CALLER="com.example.Caller",GENERIC="com.example.Generic",OTHER="com.example.Other";
    private Map<String,byte[]> compile(Path dir,String components)throws Exception {
        Path source=dir.resolve("src/com/example");Files.createDirectories(source);
        Map<String,String> files=Map.of("P","package com.example; public record P("+components+") {}",
                "Caller","package com.example; public class Caller { P value; public int version(){return 1;} }",
                "Generic","package com.example; public class Generic { java.util.List<P[]> value; }",
                "Other","package com.example; public class Other { public String text(){return \"com.example.P\";} }");
        List<String> args=new ArrayList<>(List.of("--release","17","-d",dir.toString()));
        for(var entry:files.entrySet()) {Path p=source.resolve(entry.getKey()+".java");Files.writeString(p,entry.getValue());args.add(p.toString());}
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        Map<String,byte[]> bytes=new LinkedHashMap<>();
        for(String name:List.of(P,CALLER,GENERIC,OTHER))bytes.put(name,Files.readAllBytes(dir.resolve(name.replace('.','/')+".class")));
        return bytes;
    }
    private final class Fixture implements AutoCloseable {
        final Map<String,byte[]> old,changed;
        final URLClassLoader loader;
        final Class<?>[] classes;
        Fixture(boolean witnessed)throws Exception {
            Path before=root.resolve("before"),after=root.resolve("after");old=compile(before,"int a");changed=compile(after,"int a,String b");
            loader=new URLClassLoader(new java.net.URL[]{before.toUri().toURL()},null);
            classes=new Class<?>[]{loader.loadClass(P),loader.loadClass(CALLER),loader.loadClass(GENERIC),loader.loadClass(OTHER)};
            if(witnessed)for(Class<?> type:classes)KeepBaseline.capture(loader,type.getName().replace('.','/'),old.get(type.getName()));
        }
        RecordNativeBatch prepare(Probe probe,boolean capable) {
            return RecordNativeBatch.prepare(changed,classes,n->n.startsWith("com.example."),
                    new RecordCapability(capable,capable?"unit capability":"unit unsupported"),probe.instrumentation);
        }
        public void close()throws Exception {loader.close();}
    }
    private static final class Probe {
        final List<ClassDefinition[]> calls=new ArrayList<>();
        String unmodifiable;
        Throwable failure;
        final Instrumentation instrumentation=(Instrumentation)Proxy.newProxyInstance(Instrumentation.class.getClassLoader(),new Class<?>[]{Instrumentation.class},(p,m,args)->{
            if(m.getName().equals("isRedefineClassesSupported"))return true;
            if(m.getName().equals("isModifiableClass"))return !((Class<?>)args[0]).getName().equals(unmodifiable);
            if(m.getName().equals("redefineClasses")) {
                calls.add(((ClassDefinition[])args[0]).clone());if(failure!=null)throw failure;return null;
            }
            if(m.getName().equals("toString"))return "native-unit-probe";
            throw new AssertionError("Unexpected instrumentation use: "+m.getName());
        });
    }
    @Test void includesDescriptorAndGenericCallersButNotStringOnlyText()throws Exception {
        try(var fixture=new Fixture(true)) {
            Probe probe=new Probe();var batch=fixture.prepare(probe,true);
            assertTrue(batch.active());assertEquals(Set.of(P,CALLER,GENERIC),batch.names());
            assertEquals(Set.of(P),batch.recordNames());assertTrue(batch.refusals().isEmpty());assertTrue(probe.calls.isEmpty());
            assertEquals(1,fixture.classes[0].getRecordComponents().length);
        }
    }
    @Test void sameErasureGenericMetadataStillSelectsTheAtomicRecordCohort()throws Exception {
        Path before=root.resolve("generic-before"),after=root.resolve("generic-after");
        var old=compile(before,"java.util.List<String> a");var changed=compile(after,"java.util.List<Integer> a");
        try(var loader=new URLClassLoader(new java.net.URL[]{before.toUri().toURL()},null)) {
            Class<?>[] classes={loader.loadClass(P),loader.loadClass(CALLER),loader.loadClass(GENERIC),loader.loadClass(OTHER)};
            for(var type:classes)KeepBaseline.capture(loader,type.getName().replace('.','/'),old.get(type.getName()));
            assertFalse(RecordShapeCheck.changed(classes[0],changed.get(P)));
            assertTrue(KeepBaseline.recordMetadataChanged(classes[0],changed.get(P)));
            var probe=new Probe();var batch=RecordNativeBatch.prepare(changed,classes,n->true,new RecordCapability(true,"unit"),probe.instrumentation);
            assertEquals(Set.of(P,CALLER,GENERIC),batch.names());assertTrue(batch.refusals().isEmpty());assertTrue(probe.calls.isEmpty());
        }
    }
    @Test void unwatchedChangedCallerRefusesTheWholeSaveBeforeNativeEntry()throws Exception {
        try(var fixture=new Fixture(true)) {
            var probe=new Probe();var batch=RecordNativeBatch.prepare(fixture.changed,fixture.classes,
                    name->!name.equals(CALLER),new RecordCapability(true,"unit"),probe.instrumentation);
            assertEquals(Set.of(P,CALLER,GENERIC),batch.names());
            assertEquals(batch.names(),batch.refusals().keySet());
            assertFalse(batch.commit().attempted());assertTrue(probe.calls.isEmpty());
        }
    }
    @Test void lateIncompleteCohortRefusalTaintsRecordAndRejectsLaterCallerSave()throws Exception {
        try(var fixture=new Fixture(true)) {
            var batch=fixture.prepare(new Probe(),true);
            Class<?> saveType=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent$NativeRecordSave");
            var constructor=saveType.getDeclaredConstructor(RecordNativeBatch.class);constructor.setAccessible(true);Object save=constructor.newInstance(batch);
            Class<?> agent=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent");
            var finish=agent.getDeclaredMethod("finishNativePreflight",saveType,Set.class);finish.setAccessible(true);
            var field=agent.getDeclaredField("recordRefusals");field.setAccessible(true);var taint=(RefusalTaint)field.get(null);
            try {
                finish.invoke(null,save,Set.of(P,GENERIC));
                assertTrue(taint.recovering(P),"late refusal must keep record provenance and restart concern");
                var later=Map.of(CALLER,caller("(ILjava/lang/String;)V"));
                var paths=Map.of(CALLER,root.resolve("before/com/example/Caller.class"));
                assertTrue(taint.preflight(later,paths,fixture.classes).containsKey(CALLER));
                assertTrue(com.onurkat.reclazz.ui.RestartLedger.digest().stream().anyMatch(line->line.contains(P)));
            }finally{
                taint.accepted(fixture.classes[0],fixture.old.get(P));
                var local=agent.getDeclaredField("nativeRecordSave");local.setAccessible(true);((ThreadLocal<?>)local.get(null)).remove();
            }
        }
    }
    @Test void pendingRepairConcernNamesTheExactSaveNeededForRetry()throws Exception {
        try(var fixture=new Fixture(true)) {
            var batch=fixture.prepare(new Probe(),true);Class<?> saveType=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent$NativeRecordSave");
            var constructor=saveType.getDeclaredConstructor(RecordNativeBatch.class);constructor.setAccessible(true);Object save=constructor.newInstance(batch);
            var fail=saveType.getDeclaredMethod("failure",String.class,String.class);fail.setAccessible(true);
            var complete=saveType.getDeclaredMethod("repairsCompleted");complete.setAccessible(true);
            try{
                fail.invoke(save,"retained Jackson codecs","synthetic failure");
                assertTrue(com.onurkat.reclazz.ui.RestartLedger.digest().stream().anyMatch(line->line.contains("save "+P+" again to retry")));
                assertTrue(com.onurkat.reclazz.ui.RestartLedger.digest().stream().anyMatch(line->line.startsWith("  "+P+" / retained Jackson codecs: Record shape changes are installed; retained Jackson codecs repair did not finish: synthetic failure; save "+P+" again to retry")),"mode-neutral reason preserves the record concern subject");
            }
            finally{complete.invoke(save);}
        }
    }
    @Test void recordOnlyRepairCannotClearAnUnfinishedCallerFrameworkConcern()throws Exception {
        try(var fixture=new Fixture(true)) {
            var batch=fixture.prepare(new Probe(),true);Class<?> saveType=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent$NativeRecordSave");
            var constructor=saveType.getDeclaredConstructor(RecordNativeBatch.class);constructor.setAccessible(true);Object save=constructor.newInstance(batch);
            var fail=saveType.getDeclaredMethod("failure",String.class,String.class);fail.setAccessible(true);
            var complete=saveType.getDeclaredMethod("repairsCompleted");complete.setAccessible(true);
            try{
                fail.invoke(save,"framework refresh "+CALLER,"synthetic caller failure");complete.invoke(save);
                assertTrue(com.onurkat.reclazz.ui.RestartLedger.digest().stream().anyMatch(line->line.contains(CALLER)),"P-only cache/config success cannot certify C's unfinished framework refresh");
            }finally{com.onurkat.reclazz.ui.RestartLedger.clear();}
        }
    }
    @Test void callerConcernResolvesOnlyItsActualFrameworkAndPublicationPhases()throws Exception {
        try(var fixture=new Fixture(true)) {
            Class<?> agent=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent");
            var fail=agent.getDeclaredMethod("nativeFrameworkFailure",Class.class,String.class,String.class);fail.setAccessible(true);
            var complete=agent.getDeclaredMethod("nativeFrameworkCompleted",Class.class,boolean.class);complete.setAccessible(true);
            Class<?> record=fixture.classes[0],caller=fixture.classes[1];
            try {
                fail.invoke(null,caller,"framework refresh "+CALLER,"synthetic framework");fail.invoke(null,caller,"receipt publication "+CALLER,"synthetic receipt");
                complete.invoke(null,record,true);assertTrue(com.onurkat.reclazz.ui.RestartLedger.digest().stream().anyMatch(line->line.contains("synthetic framework")));
                complete.invoke(null,caller,false);assertFalse(com.onurkat.reclazz.ui.RestartLedger.digest().stream().anyMatch(line->line.contains("synthetic framework")));
                assertTrue(com.onurkat.reclazz.ui.RestartLedger.digest().stream().anyMatch(line->line.contains("synthetic receipt")));
                complete.invoke(null,caller,true);assertFalse(com.onurkat.reclazz.ui.RestartLedger.digest().stream().anyMatch(line->line.contains("synthetic receipt")));
                fail.invoke(null,caller,"native aftermath "+CALLER,"synthetic aftermath");
                assertTrue(com.onurkat.reclazz.ui.RestartLedger.digest().stream().anyMatch(line->line.startsWith("  "+CALLER+" / native aftermath "+CALLER+": Changes are installed; record aftermath "+CALLER+" did not finish: synthetic aftermath; save "+CALLER+" again to retry")),"legacy subject stays stable while the displayed repair reason is mode-neutral");
                complete.invoke(null,caller,true);assertFalse(com.onurkat.reclazz.ui.RestartLedger.digest().stream().anyMatch(line->line.contains("synthetic aftermath")));
            }finally{complete.invoke(null,caller,true);}
        }
    }
    private static byte[] caller(String constructor) {
        org.objectweb.asm.ClassWriter w = new org.objectweb.asm.ClassWriter(0);
        w.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, CALLER.replace('.', '/'), null, "java/lang/Object", null);
        var method = w.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "create", "()Lcom/example/P;", null, null);
        method.visitCode(); method.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, "com/example/P");
        method.visitInsn(org.objectweb.asm.Opcodes.DUP); method.visitInsn(org.objectweb.asm.Opcodes.ICONST_1);
        if (constructor.contains("String")) method.visitLdcInsn("new");
        method.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "com/example/P", "<init>", constructor, false);
        method.visitInsn(org.objectweb.asm.Opcodes.ARETURN); method.visitMaxs(4,0); method.visitEnd(); w.visitEnd(); return w.toByteArray();
    }
    @Test void staleCanonicalConstructorRefusesWholeCohortBeforeNativeCall() throws Exception {
        try (var fixture = new Fixture(true)) {
            fixture.changed.put(CALLER, caller("(I)V")); Probe probe = new Probe(); var batch = fixture.prepare(probe, true);
            assertEquals(batch.names(), batch.refusals().keySet());
            assertTrue(batch.refusals().get(P).contains("stale linkage"));
            assertFalse(batch.commit().attempted()); assertTrue(probe.calls.isEmpty());
        }
    }
    @Test void callerClassKindChangeIsRefusedBeforeEnteringEnhancedVm() throws Exception {
        try (var fixture = new Fixture(true)) {
            var node = new org.objectweb.asm.tree.ClassNode(); new org.objectweb.asm.ClassReader(fixture.changed.get(CALLER)).accept(node,0);
            node.access |= org.objectweb.asm.Opcodes.ACC_INTERFACE | org.objectweb.asm.Opcodes.ACC_FINAL;
            var writer = new org.objectweb.asm.ClassWriter(0); node.accept(writer); fixture.changed.put(CALLER, writer.toByteArray());
            Probe probe = new Probe(); var batch = fixture.prepare(probe,true);
            assertTrue(batch.refusals().get(P).contains("kind/modifiers")); assertFalse(batch.commit().attempted()); assertTrue(probe.calls.isEmpty());
        }
    }
    @Test void invalidMethodReturnIsRejectedWithoutRelyingOnEnhancedVmDeferredVerification() throws Exception {
        try (var fixture = new Fixture(true)) {
            var node = new org.objectweb.asm.tree.ClassNode(); new org.objectweb.asm.ClassReader(fixture.changed.get(CALLER)).accept(node,0);
            var method = node.methods.stream().filter(m -> m.name.equals("version")).findFirst().orElseThrow();
            method.instructions.clear(); method.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ACONST_NULL));
            method.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ARETURN));
            var writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS); node.accept(writer);
            fixture.changed.put(CALLER,writer.toByteArray()); Probe probe = new Probe(); var batch = fixture.prepare(probe,true);
            assertTrue(batch.refusals().get(P).contains("did not verify")); assertFalse(batch.commit().attempted()); assertTrue(probe.calls.isEmpty());
        }
    }
    @Test void updatedCanonicalConstructorInSameSaveIsAdmitted() throws Exception {
        try (var fixture = new Fixture(true)) {
            fixture.changed.put(CALLER, caller("(ILjava/lang/String;)V")); Probe probe = new Probe(); var batch = fixture.prepare(probe, true);
            assertTrue(batch.refusals().isEmpty()); assertTrue(batch.commit().committed());
            assertEquals(1, probe.calls.size()); assertEquals(3, probe.calls.get(0).length);
        }
    }
    @Test void rejectedNativeCallNeverFallsBackOrRunsRepair()throws Exception {
        try(var fixture=new Fixture(true)) {
            Probe probe=new Probe();probe.failure=new UnmodifiableClassException("unit native rejection");
            var batch=fixture.prepare(probe,true);AtomicInteger repairs=new AtomicInteger();
            var result=batch.commit(c->repairs.incrementAndGet());
            assertTrue(result.attempted());assertFalse(result.committed());assertTrue(result.reason().contains("unit native rejection"));
            assertEquals(1,probe.calls.size());assertEquals(3,probe.calls.get(0).length);assertEquals(0,repairs.get());
            assertSame(result,batch.commit(c->fail("repair retry")));assertEquals(1,probe.calls.size());
            assertEquals(1,fixture.classes[0].getRecordComponents().length);
            assertTrue(KeepBaseline.constructorBodiesUnchanged(fixture.classes[0],fixture.old.get(P)));
        }
    }
    @Test void postNativeRepairFailureCannotEraseCommitOrSuppressSiblingRepair()throws Exception {
        try(var fixture=new Fixture(true)) {
            Probe probe=new Probe();var batch=fixture.prepare(probe,true);Set<String> repairs=new HashSet<>();
            var result=batch.commit(c->{repairs.add(c.name());if(c.name().equals(P))throw new IllegalStateException("unit cache repair");});
            assertTrue(result.attempted());assertTrue(result.committed());assertEquals(Set.of(P),result.repairFailures().keySet());
            assertEquals(Set.of(P,CALLER,GENERIC),repairs);assertEquals(1,probe.calls.size());
            assertSame(result,batch.commit());assertEquals(1,probe.calls.size());
        }
    }
    @Test void unsupportedProbeDoesNotReachNativeOrRepair()throws Exception {
        try(var fixture=new Fixture(true)) {
            Probe probe=new Probe();var batch=fixture.prepare(probe,false);
            assertEquals(batch.names(),batch.refusals().keySet());assertTrue(batch.refusals().get(P).contains("unit unsupported"));
            assertFalse(batch.commit(c->fail("repair")).attempted());assertTrue(probe.calls.isEmpty());
        }
    }
    @Test void recordLoadedBeforeWatchKeepsRefusal()throws Exception {
        try(var fixture=new Fixture(false)) {
            Probe probe=new Probe();var batch=fixture.prepare(probe,true);
            assertTrue(batch.refusals().get(P).contains("not witnessed"));assertFalse(batch.commit().attempted());assertTrue(probe.calls.isEmpty());
        }
    }
    @Test void laterBodyFingerprintCannotUpgradeALoadedBeforeWatchRecord() throws Exception {
        try (var fixture = new Fixture(false)) {
            for (Class<?> type : fixture.classes) KeepBaseline.advance(type, fixture.old.get(type.getName()));
            assertNotNull(KeepBaseline.get(fixture.classes[0])); assertFalse(KeepBaseline.witnessedLoad(fixture.classes[0]));
            Probe probe = new Probe(); var batch = fixture.prepare(probe,true);
            assertTrue(batch.refusals().get(P).contains("not witnessed")); assertFalse(batch.commit().attempted()); assertTrue(probe.calls.isEmpty());
        }
    }
    @Test void unmodifiableCallerRefusesTheCompleteCohort()throws Exception {
        try(var fixture=new Fixture(true)) {
            Probe probe=new Probe();probe.unmodifiable=CALLER;var batch=fixture.prepare(probe,true);
            assertEquals(Set.of(P,CALLER,GENERIC),batch.refusals().keySet());assertFalse(batch.commit().attempted());assertTrue(probe.calls.isEmpty());
        }
    }
    @Test void laterPreflightCannotSilentlyDropACaller()throws Exception {
        try(var fixture=new Fixture(true)) {
            Probe probe=new Probe();var batch=fixture.prepare(probe,true).requireComplete(Set.of(P,GENERIC,OTHER));
            assertEquals(Set.of(P,CALLER,GENERIC),batch.refusals().keySet());assertTrue(batch.refusals().get(P).contains(CALLER));
            assertFalse(batch.commit().attempted());assertTrue(probe.calls.isEmpty());
        }
    }
    @Test void frozenBytesDoNotFollowLaterCompilerMutations()throws Exception {
        try(var fixture=new Fixture(true)) {
            Probe probe=new Probe();var batch=fixture.prepare(probe,true);byte[] expected=fixture.changed.get(P).clone();
            Arrays.fill(fixture.changed.get(P),(byte)0);Arrays.fill(batch.changes().get(0).bytes(),(byte)0);
            assertTrue(batch.commit().committed());
            var observed=Arrays.stream(probe.calls.get(0)).filter(d->d.getDefinitionClass().getName().equals(P)).findFirst().orElseThrow();
            assertArrayEquals(expected,observed.getDefinitionClassFile());
        }
    }
    @Test void nonRecordSaveNeverUsesTheCohortCall()throws Exception {
        try(var fixture=new Fixture(true)) {
            Probe probe=new Probe();var batch=RecordNativeBatch.prepare(Map.of(OTHER,fixture.changed.get(OTHER)),fixture.classes,n->true,
                    new RecordCapability(true,"unit capability"),probe.instrumentation);
            assertFalse(batch.active());assertTrue(batch.names().isEmpty());assertFalse(batch.commit().attempted());assertTrue(probe.calls.isEmpty());
        }
    }
    @Test void ambiguousRecordOwnersAreNotChosenByCensusOrder()throws Exception {
        try(var fixture=new Fixture(true);var other=new URLClassLoader(fixture.loader.getURLs(),null)) {
            Class<?> duplicate=other.loadClass(P);KeepBaseline.capture(other,P.replace('.','/'),fixture.old.get(P));
            Class<?>[] census=Arrays.copyOf(fixture.classes,fixture.classes.length+1);census[census.length-1]=duplicate;
            Probe probe=new Probe();var batch=RecordNativeBatch.prepare(fixture.changed,census,n->true,new RecordCapability(true,"unit capability"),probe.instrumentation);
            assertFalse(batch.refusals().isEmpty());assertFalse(batch.commit().attempted());assertTrue(probe.calls.isEmpty());
            assertEquals(1,duplicate.getRecordComponents().length);assertEquals(1,fixture.classes[0].getRecordComponents().length);
        }
    }
}
