/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import com.onurkat.reclazz.e2e.harness.JavaLauncher;

import com.onurkat.reclazz.bootstrap.*;
import com.onurkat.reclazz.e2e.harness.WatchedApp;
import java.lang.annotation.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Snapshot metadata preparation must leave equal live roots and constructor sites untouched. */
class RecordConstructorVisibilityTest {
    @org.junit.jupiter.api.io.TempDir Path metadataTmp;
    @Test void hidesRetiredCanonicalPreservesAuxiliary()throws Exception {
        var harness=new RecordComponentMetadataTest();harness.tmp=metadataTmp;
        harness.runProbe(RecordComponentMetadataTest.SnapshotProbe.class,"RECORD_MEMBER_SNAPSHOT_OK");
    }
    @Test void preparedRootsDoNotRetargetLiveMembersOrShareAccessibleCopies() throws Exception {
        String java=JavaLauncher.resolve(Path.of(System.getProperty("reclazz.record.stock.java",Path.of(System.getProperty("java.home"),"bin","java").toString()))).toString();
        assertTrue(Files.isRegularFile(Path.of(java)),java);
        Path evidence=Path.of(System.getProperty("reclazz.record.evidenceDir",Path.of("build","record-stock-evidence").toString()));
        Files.createDirectories(evidence);
        Path log=evidence.resolve("snapshot-roots-"+UUID.randomUUID()+".log");
        var args=new ArrayList<>(WatchedApp.utf8Java(java,
                "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
                "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.reflect=ALL-UNNAMED",
                "--add-opens=java.base/sun.invoke.util=ALL-UNNAMED",
                "-cp",System.getProperty("java.class.path"),Probe.class.getName()));
        Process child=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(40,TimeUnit.SECONDS),"snapshot child exceeded deadline: "+log);
            List<String> lines=Files.readAllLines(log);
            String tail=String.join("\n",lines.subList(Math.max(0,lines.size()-24),lines.size()));
            assertEquals(0,child.exitValue(),tail);
            assertTrue(lines.stream().anyMatch(s->s.startsWith("SNAPSHOT_ROOTS_OK checks=")),tail);
        } finally {
            if(child.isAlive()){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}
        }
    }
    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD,ElementType.CONSTRUCTOR})
    public @interface Tag {String value();}
    public static class Box {
        int number;
        public Box(int number){this.number=number;}
        public String value(){return "physical";}
        public static String oldValue(Box receiver){return "old";}
        public static String newValue(Box receiver){return "new";}
        public static Box oldInit(Box receiver,int value){receiver.number=value;return receiver;}
        public static Box newInit(Box receiver,int value){receiver.number=value+10;return receiver;}
    }
    public record RecordOwner(int value) {
        public String text(){return "record";}
        public static String updatedText(RecordOwner receiver){return "updated";}
        public static RecordOwner initialize(RecordOwner receiver,int value){return receiver;}
    }
    public static class OldMetadata {
        @Tag("old-constructor") public OldMetadata(int value) { }
        @Tag("old-method") public String value(){return "carrier";}
    }
    public static class NewMetadata {
        @Tag("new-constructor") public NewMetadata(int value) { }
        @Tag("new-method") public String value(){return "carrier";}
    }
    @SuppressWarnings("deprecation")
    public static class Probe {
        private static int checks;
        private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
        public static void main(String[] args)throws Throwable {
            check(MethodForge.initialize(),"reflection API unavailable: "+MethodForge.initializationFailure);
            var lookup=MethodHandles.lookup();
            var oldTarget=lookup.findStatic(Box.class,"oldValue",MethodType.methodType(String.class,Box.class));
            var newTarget=lookup.findStatic(Box.class,"newValue",MethodType.methodType(String.class,Box.class));
            Method oldMetadata=OldMetadata.class.getDeclaredMethod("value"),newMetadata=NewMetadata.class.getDeclaredMethod("value");
            Method live=MethodForge.forgeMethod(Box.class,"value",new Class<?>[0],String.class,Modifier.PUBLIC,oldTarget,(String)null,new Class<?>[0]);
            check(live!=null,"old root created");MethodForge.metadata(live,oldMetadata,oldTarget);
            Box receiver=new Box(1);
            check(live.invoke(receiver).equals("old"),"old root starts old");
            Method prepared=MethodForge.snapshotMethod(Box.class,newMetadata,newTarget,null);
            check(prepared!=null,"snapshot root created");
            check(MethodForge.unreflect(lookup,live,null).invoke(receiver).equals("old"),"prepared method overwrote equal live root target before publication");
            check(live.getDeclaredAnnotation(Tag.class).value().equals("old-method"),"live annotations remain old");
            check(prepared.getDeclaredAnnotation(Tag.class).value().equals("new-method"),"prepared root owns new annotations");
            check(prepared.invoke(receiver).equals("new"),"prepared target is independently callable");
            check(MethodForge.unreflect(lookup,prepared,null).invoke(receiver).equals("new"),"snapshot lookup uses its own target");
            prepared.setAccessible(true);Method copy=MethodForge.reflectionCopy(prepared);
            check(copy!=prepared&&!copy.isAccessible(),"public method copy does not share override flag");
            check(copy.invoke(receiver).equals("new"),"method copy retains accessor");
            check(MethodForge.hasMetadata(copy),"method copy retains carrier metadata");
            check(MethodForge.unreflect(lookup,copy,null).invoke(receiver).equals("new"),"method copy resolves snapshot metadata");
            Method twice=MethodForge.reflectionCopy(copy);
            check(twice!=copy&&twice.invoke(receiver).equals("new"),"copy-of-copy resolves original root");

            var oldInit=lookup.findStatic(Box.class,"oldInit",MethodType.methodType(Box.class,Box.class,int.class));
            var newInit=lookup.findStatic(Box.class,"newInit",MethodType.methodType(Box.class,Box.class,int.class));
            Constructor<?> liveCtor=MemberForge.constructor(Box.class,OldMetadata.class.getDeclaredConstructor(int.class),Modifier.PUBLIC,null,oldInit);
            check(((Box)liveCtor.newInstance(2)).number==2,"live constructor starts old");
            Constructor<?> preparedCtor=MemberForge.snapshotConstructor(Box.class,NewMetadata.class.getDeclaredConstructor(int.class),Modifier.PUBLIC,null,newInit);
            check(((Box)liveCtor.newInstance(3)).number==3,"prepared constructor retargeted live initializer before publication");
            check(((Box)MemberForge.unreflectConstructor(lookup,liveCtor).invoke(4)).number==4,"prepared constructor overwrote live registry");
            check(((Box)preparedCtor.newInstance(5)).number==15,"frozen prepared initializer works");
            check(preparedCtor.getDeclaredAnnotation(Tag.class).value().equals("new-constructor"),"snapshot constructor carrier annotations");
            check(liveCtor.getDeclaredAnnotation(Tag.class).value().equals("old-constructor"),"old constructor annotations unchanged");
            preparedCtor.setAccessible(true);Constructor<?> copyCtor=MethodForge.reflectionCopy(preparedCtor);
            check(copyCtor!=preparedCtor&&!copyCtor.isAccessible(),"public constructor copy does not share override flag");
            check(((Box)copyCtor.newInstance(6)).number==16,"constructor copy retains frozen accessor");
            check(((Box)MemberForge.unreflectConstructor(lookup,copyCtor).invoke(7)).number==17,"constructor copy uses snapshot lookup");
            check(MethodForge.hasMetadata(copyCtor),"constructor copy retains carrier metadata");
            Method recordMethod=RecordOwner.class.getDeclaredMethod("text");
            MethodHandle recordTarget=lookup.findVirtual(RecordOwner.class,"text",MethodType.methodType(String.class));
            Method recordRoot=MethodForge.snapshotMethod(RecordOwner.class,recordMethod,recordTarget,null);
            check(recordRoot!=null&&recordRoot.invoke(new RecordOwner(2)).equals("record"),"direct record-owned root works");
            check(!MethodForge.hasMetadata(recordMethod),"prepared record root does not label the physical method as forged");
            Method dynamic=MethodForge.recordDispatchMethod(RecordOwner.class,recordMethod,recordTarget,"live-record-text",null);
            check(dynamic.invoke(new RecordOwner(2)).equals("record"),"record dispatch root starts with owned fallback");
            var slot=RecordSchema.slot(RecordOwner.class);
            var updated=lookup.findStatic(RecordOwner.class,"updatedText",MethodType.methodType(String.class,RecordOwner.class));
            try(var next=slot.prepare(slot.current().members())) {
                next.stageDispatch(RecordOwner.class,Map.of("live-record-text",updated));
                check(dynamic.invoke(new RecordOwner(3)).equals("record"),"held record root stays old before CAS");
                check(next.commit(),"record method target CAS");
            }
            check(dynamic.invoke(new RecordOwner(4)).equals("updated"),"held record root follows current dispatch after CAS");
            try {
                MethodForge.snapshotMethod(RecordOwner.class,NewMetadata.class.getDeclaredMethod("value"),recordTarget,null);
                throw new AssertionError("foreign record metadata accepted");
            }catch(IllegalArgumentException expected){checks++;}
            MethodHandle captured=MethodHandles.dropArguments(MethodHandles.constant(String.class,"captured"),0,RecordOwner.class);
            try {
                MethodForge.snapshotMethod(RecordOwner.class,recordMethod,captured,null);
                throw new AssertionError("opaque record handle accepted");
            }catch(IllegalArgumentException expected){checks++;}
            try {
                MethodForge.recordDispatchMethod(RecordOwner.class,recordMethod,captured,"opaque-record",null);
                throw new AssertionError("opaque dispatch root target accepted");
            }catch(IllegalArgumentException expected){checks++;}
            MethodHandle recordInit=lookup.findStatic(RecordOwner.class,"initialize",MethodType.methodType(RecordOwner.class,RecordOwner.class,int.class));
            try {
                MemberForge.snapshotConstructor(RecordOwner.class,NewMetadata.class.getDeclaredConstructor(int.class),Modifier.PUBLIC,null,recordInit);
                throw new AssertionError("foreign record constructor metadata accepted");
            }catch(IllegalArgumentException expected){checks++;}
            System.out.println("SNAPSHOT_ROOTS_OK checks="+checks+" runtime="+Runtime.version());
        }
    }
}
