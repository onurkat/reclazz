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

/** Source-generated caller roots, headers and constructors join the real record native/CAS boundary. */
public class RecordCallerAssemblyTest {
    @TempDir Path tmp;
    @Test void sourceCallerMetadataAndBodiesArePrivateUntilCommit()throws Exception {
        var harness=new RecordComponentMetadataTest();harness.tmp=tmp;harness.runProbe(Probe.class,"RECORD_CALLER_ASSEMBLY_OK");
    }
    @Test void saveScopedCoordinatorPublishesFromThePhysicalStartupView()throws Exception {
        var harness=new RecordComponentMetadataTest();harness.tmp=tmp;harness.runProbe(BatchProbe.class,"RECORD_STOCK_BATCH_OK");
    }
    @Test void prepareFailurePreservesItsPublicationBoundary()throws Exception {var h=new RecordComponentMetadataTest();h.tmp=tmp;h.runProbe(PrepareFailureProbe.class,"RECORD_STOCK_FAILURE_OK mode=prepare");}
    public static class PrepareFailureProbe extends Probe {public static void main(String[] args)throws Throwable{failureMode="prepare";run(true);}}
    @Test void nativeFailurePreservesItsPublicationBoundary()throws Exception {var h=new RecordComponentMetadataTest();h.tmp=tmp;h.runProbe(NativeFailureProbe.class,"RECORD_STOCK_FAILURE_OK mode=native");}
    public static class NativeFailureProbe extends Probe {public static void main(String[] args)throws Throwable{failureMode="native";run(true);}}
    @Test void activationFailurePreservesItsPublicationBoundary()throws Exception {var h=new RecordComponentMetadataTest();h.tmp=tmp;h.runProbe(ActivationFailureProbe.class,"RECORD_STOCK_FAILURE_OK mode=activation");}
    public static class ActivationFailureProbe extends Probe {public static void main(String[] args)throws Throwable{failureMode="activation";run(true);}}
    @Test void lateFailurePreservesItsPublicationBoundary()throws Exception {var h=new RecordComponentMetadataTest();h.tmp=tmp;h.runProbe(LateFailureProbe.class,"RECORD_STOCK_FAILURE_OK mode=late");}
    public static class LateFailureProbe extends Probe {public static void main(String[] args)throws Throwable{failureMode="late";run(true);}}
    @Test void repairFailurePreservesItsPublicationBoundary()throws Exception {var h=new RecordComponentMetadataTest();h.tmp=tmp;h.runProbe(RepairFailureProbe.class,"RECORD_STOCK_FAILURE_OK mode=repair");}
    public static class RepairFailureProbe extends Probe {public static void main(String[] args)throws Throwable{failureMode="repair";run(true);}}
    @Test void engineSaveAdapterAppliesTheEntireStockCohortOnce()throws Exception {var h=new RecordComponentMetadataTest();h.tmp=tmp;h.runProbe(EngineProbe.class,"RECORD_ENGINE_SAVE_OK");}
    public static class EngineProbe extends Probe {public static void main(String[] args)throws Throwable{engine=true;run(true);}}
    @Test void previouslyUncapturedWatchedLookupsAreCapturedBeforeAdmission()throws Exception {var h=new RecordComponentMetadataTest();h.tmp=tmp;h.runProbe(UncapturedProbe.class,"RECORD_UNCAPTURED_STOCK_OK");}
    public static class UncapturedProbe extends Probe {public static void main(String[] args)throws Throwable{uncaptured=true;run(true);}}
    @Test void ordinaryCallerSaveBetweenRecordCohortsKeepsVirtualInitializers()throws Exception {var h=new RecordComponentMetadataTest();h.tmp=tmp;h.runProbe(InterleavedProbe.class,"RECORD_STOCK_INTERLEAVED_OK");}
    public static class InterleavedProbe extends Probe {public static void main(String[] args)throws Throwable{interleaved=true;run(true);}}
    @Test void preparationErrorDiscardsPendingHistory()throws Exception {var h=new RecordComponentMetadataTest();h.tmp=tmp;h.runProbe(ErrorProbe.class,"RECORD_STOCK_FAILURE_OK mode=error");}
    public static class ErrorProbe extends Probe {public static void main(String[]args)throws Throwable{failureMode="error";run(true);}}
    public static class BatchProbe extends Probe {public static void main(String[] args)throws Throwable{run(true);}}
    public static class Probe extends TransformTestBase {
        static int checks;static String failureMode="";static boolean engine,uncaptured,interleaved;
        static boolean armedError;static Class<?> errorRecord;
        static boolean pendingErrorHistory() {
            try {
                var site=RecordSchema.callerSite(errorRecord,RecordSchema.slot(errorRecord),"__reclazz$dispatchVersion",MethodHandles.constant(int.class,0));
                var pending=site.getClass().getDeclaredMethod("hasPending");pending.setAccessible(true);return (boolean)pending.invoke(site);
            }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        }
        static void bumpEpoch(Class<?> record) {var slot=RecordSchema.slot(record);try(var pending=slot.prepare(slot.current().members())){check(pending.commit(),"competing native callback commits unchanged route");}}
        static void failureMarker(){System.out.println("RECORD_STOCK_FAILURE_OK mode="+failureMode+" checks="+checks+" runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));}
        static byte[] nativeLabel(byte[] raw) {
            var node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(raw).accept(node,0);
            for(var method:node.methods)if(method.name.equals("label")){method.access|=org.objectweb.asm.Opcodes.ACC_NATIVE;method.instructions.clear();method.tryCatchBlocks.clear();method.localVariables=null;method.visibleLocalVariableAnnotations=null;method.invisibleLocalVariableAnnotations=null;}
            var writer=new org.objectweb.asm.ClassWriter(0);node.accept(writer);return writer.toByteArray();
        }
        static byte[] addedPhysicalField(byte[] raw) {
            var reader=new org.objectweb.asm.ClassReader(raw);var writer=new org.objectweb.asm.ClassWriter(reader,0);
            reader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9,writer){@Override public void visitEnd(){super.visitField(org.objectweb.asm.Opcodes.ACC_PRIVATE|org.objectweb.asm.Opcodes.ACC_FINAL,InjectedNames.PREFIX+"nativeFailure","I",null,null).visitEnd();super.visitEnd();}},0);
            return writer.toByteArray();
        }
        static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
        static SourceFile record(boolean next){return new SourceFile("com.example.CohortRecord","package com.example; public record CohortRecord("+(next?"int value,String added":"int value")+") { }");}
        static SourceFile caller(boolean next){return new SourceFile("com.example.CohortCaller","""
            package com.example;
            import java.util.*;import java.io.Serializable;
            @CallerTag("%s") public class CohortCaller<%s> extends @CallerUse("super") CohortBase<%s> implements Serializable {
                public int value;public CohortRecord record;
                public List<%s> items;
                public static java.util.function.IntFunction<CohortCaller<?>> reference(){return CohortCaller::new;}
                %s
                @CallerTag("%s") public CohortCaller(int value){this.value=%s;this.record=%s;}
                %s
                @CallerTag("%s") public String label(){return "%s:"+record.value();}
            }
            """.formatted(next?"new":"old",next?"U extends Number & Comparable<U>":"T extends Number",next?"U":"T",next?"U":"T",
                next?"public String added=\"fresh\";":"public String retired;",
                next?"new":"old",next?"value+100":"value",next?"new CohortRecord(value,\"cohort\")":"new CohortRecord(value)",
                next?"public CohortCaller(String text){this(Integer.parseInt(text));}":"public String retired(){return \"retired\";}",next?"new":"old",next?"new":"old"));}
        static String tag(AnnotatedElement element)throws Exception {for(var annotation:element.getDeclaredAnnotations())if(annotation.annotationType().getName().equals("com.example.CallerTag"))return (String)annotation.annotationType().getMethod("value").invoke(annotation);return "none";}
        static RecordSchema.Preparation stage(Class<?> record,RecordCompanion.Prepared schema,Class<?> caller,RecordCallerCompanion.Prepared compiled) {
            var pending=RecordSchema.slot(record).prepareVirtual(schema.snapshot().members());pending.stageDispatch(record,schema.snapshot().targets());
            pending.stageCallerMembers(caller,compiled.members());pending.stageDispatch(caller,compiled.targets());pending.stageFieldInitializers(caller,compiled.fieldInitializers());return pending;
        }
        public static void main(String[] args)throws Throwable {run(false);}
        static void run(boolean batch)throws Throwable {
            var annotation=new SourceFile("com.example.CallerTag","package com.example; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE,ElementType.METHOD,ElementType.CONSTRUCTOR}) public @interface CallerTag {String value();}");
            var use=new SourceFile("com.example.CallerUse","package com.example; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) public @interface CallerUse {String value();}");
            var inherited=new SourceFile("com.example.CallerInherited","package com.example; import java.lang.annotation.*; @Inherited @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) public @interface CallerInherited {String value();}");
            var base=new SourceFile("com.example.CohortBase","package com.example; @CallerInherited(\"base\") public class CohortBase<X> {public int baseValue;public CohortBase(){baseValue=1;}}");
            var old=compile(record(false),caller(false),annotation,use,inherited,base);var next=compile(record(true),caller(true),annotation,use,inherited,base);
            var context=new TransformContext(){@Override public ClassMetadata getMetadata(String name){
                if(armedError&&name.equals("com/example/CohortCaller")){
                    armedError=false;check(pendingErrorHistory(),"injected Error reaches genuinely staged record history");throw new AssertionError("synthetic prepare Error");
                }
                return super.getMetadata(name);
            }};context.addWatched("com/example/CohortRecord");context.addWatched("com/example/CohortCaller");context.addWatched("com/example/CohortBase");
            var transformer=new ReclazzTransformer(context,AgentConfig.parse(null));
            class Loader extends ClassLoader {
                Loader(){super(Probe.class.getClassLoader());}
                @Override protected Class<?> findClass(String name)throws ClassNotFoundException {
                    byte[] bytes=old.get(name.replace('.','/'));if(bytes==null)throw new ClassNotFoundException(name);
                    try{byte[] changed=transformer.transform(this,name.replace('.','/'),null,null,bytes);if(changed!=null)bytes=changed;}catch(Exception failed){throw new ClassNotFoundException(name,failed);}
                    return defineClass(name,bytes,0,bytes.length);
                }
            }
            var loader=new Loader();Class<?> record=loader.loadClass("com.example.CohortRecord"),owner=loader.loadClass("com.example.CohortCaller");
            Class<?> baseOwner=loader.loadClass("com.example.CohortBase");
            var baseField=baseOwner.getDeclaredField(InjectedNames.LOOKUP_FIELD);baseField.setAccessible(true);var baseLookup=(MethodHandles.Lookup)baseField.get(null);
            var recordField=record.getDeclaredField(InjectedNames.LOOKUP_FIELD);recordField.setAccessible(true);var recordLookup=(MethodHandles.Lookup)recordField.get(null);
            var callerField=owner.getDeclaredField(InjectedNames.LOOKUP_FIELD);callerField.setAccessible(true);var callerLookup=(MethodHandles.Lookup)callerField.get(null);
            LookupCapture.trust(Probe.class);LookupCapture.trust(BatchProbe.class);LookupCapture.trust(RecordStockBatch.class);LookupCapture.trust(StructuralReloader.class);LookupCapture.trust(com.onurkat.reclazz.agent.ReclazzAgent.class);LookupCapture.trust(Class.forName("com.onurkat.reclazz.agent.ReclazzAgent$NativeRecordSave"));LookupCapture.trust(RecordCompanion.class);LookupCapture.trust(RecordMemberSnapshot.class);LookupCapture.trust(RecordCallerCompanion.class);LookupCapture.trust(ProtectedCallResolver.class);if(!uncaptured){LookupCapture.store(record,recordLookup);LookupCapture.store(owner,callerLookup);}LookupCapture.store(baseOwner,baseLookup);LookupCapture.seal();
            var oldSchema=RecordCompanion.prepare(record,recordLookup,old.get("com/example/CohortRecord"),context,1);
            var oldCaller=RecordCallerCompanion.prepare(owner,callerLookup,old.get("com/example/CohortCaller"),context,1);
            var oldBase=RecordCallerCompanion.prepare(baseOwner,baseLookup,old.get("com/example/CohortBase"),context,1);
            var slot=RecordSchema.slot(record);
            try(var pending=batch?slot.prepare(slot.current().members()):stage(record,oldSchema,owner,oldCaller)){pending.stageCallerMembers(baseOwner,oldBase.members());pending.stageDispatch(baseOwner,oldBase.targets());check(pending.commit(),"old source snapshot published");}
            Constructor<?> heldConstructor=owner.getDeclaredConstructor(int.class);Method heldMethod=owner.getDeclaredMethod("label");Field heldItems=owner.getDeclaredField("items");Method heldRetired=owner.getDeclaredMethod("retired");
            MethodHandle heldFound=callerLookup.findConstructor(owner,MethodType.methodType(void.class,int.class));
            MethodHandle heldUnreflected=callerLookup.unreflectConstructor(heldConstructor);
            var heldReference=(java.util.function.IntFunction<?>)owner.getDeclaredMethod("reference").invoke(null);
            TypeVariable<?> heldVariable=owner.getTypeParameters()[0];Object oldObject=heldConstructor.newInstance(3);Object unread=heldConstructor.newInstance(13);
            check(tag(owner).equals("old")&&tag(heldConstructor).equals("old")&&tag(heldMethod).equals("old"),"old caller annotations");
            check(heldMethod.invoke(oldObject).equals("old:3"),"old caller executable body");
            var schema=RecordCompanion.prepare(record,recordLookup,next.get("com/example/CohortRecord"),context,2);
            var compiled=RecordCallerCompanion.prepare(owner,callerLookup,next.get("com/example/CohortCaller"),context,2);
            check(tag(owner).equals("old")&&owner.getTypeParameters()[0].getName().equals("T"),"candidate class header stays private");
            check(heldMethod.invoke(oldObject).equals("old:3"),"candidate dispatch stays private");
            check(owner.getDeclaredField("retired")!=null,"candidate root removal stays private");
            try(var abandoned=stage(record,schema,owner,compiled)){check(tag(owner).equals("old")&&heldMethod.invoke(oldObject).equals("old:3"),"staged header and body stay old");}
            check(tag(owner).equals("old")&&heldConstructor.newInstance(4).getClass()==owner,"abort keeps caller roots and constructors");
            var actual=RecordComponentMetadataTest.ProbeAgent.instrumentation;int[] nativeCalls={0};
            var instrumentation=(Instrumentation)Proxy.newProxyInstance(Instrumentation.class.getClassLoader(),new Class<?>[]{Instrumentation.class},(proxy,method,values)->{
                if(method.getName().equals("redefineClasses"))nativeCalls[0]++;
                try{return method.invoke(actual,values);}catch(InvocationTargetException failed){throw failed.getCause();}
            });
            ClassFileTransformer observer=new ClassFileTransformer(){@Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] raw)throws IllegalClassFormatException {
                if(type!=owner&&type!=record)return null;
                byte[] changed=transformer.transform(loader,name,type,domain,raw);if(changed==null)changed=raw;
                if(type==record&&failureMode.equals("native"))return addedPhysicalField(changed);
                if(type==record&&failureMode.equals("activation"))bumpEpoch(record);
                return changed;
            }};
            var bookkeeping=new StructuralReloader(instrumentation,context,AgentConfig.parse(null),null);
            var nativeReloader=new com.onurkat.reclazz.agent.ClassReloader(instrumentation);
            var startupMetadata=context.getMetadata("com/example/CohortCaller");bookkeeping.setTransformer(transformer);
            instrumentation.addTransformer(observer,true);
            if(batch) {
                var incoming=new LinkedHashMap<String,byte[]>();next.forEach((key,bytes)->incoming.put(key.replace('/','.'),bytes));
                byte[] physicalRecord=PhysicalDefinitions.get(record),physicalCaller=PhysicalDefinitions.get(owner);
                if(failureMode.equals("prepare"))incoming.put(owner.getName(),nativeLabel(incoming.get(owner.getName())));
                if(failureMode.equals("error")) {
                    errorRecord=record;armedError=true;var beforeError=slot.current();
                    try(var failed=RecordStockBatch.prepare(incoming,new Class<?>[]{record,owner,baseOwner},name->context.isWatched(name.replace('.','/')),instrumentation,context,3,Set.of())){throw new AssertionError("prepare Error was not reached");}
                    catch(AssertionError failure){check("synthetic prepare Error".equals(failure.getMessage()),"exact injected Error propagated");}
                    check(!pendingErrorHistory(),"Error exit discards every prepared caller history entry");
                    check(slot.current()==beforeError&&nativeCalls[0]==0,"Error cannot publish or enter native admission");
                    try(var retry=RecordStockBatch.prepare(incoming,new Class<?>[]{record,owner,baseOwner},name->context.isWatched(name.replace('.','/')),instrumentation,context,4,Set.of())){check(retry.active()&&retry.refusals().isEmpty(),"next cohort can prepare after Error cleanup");}
                    check(!pendingErrorHistory(),"closing retry also clears pending entries");failureMarker();return;
                }
                try(var save=RecordStockBatch.prepare(incoming,new Class<?>[]{record,owner,baseOwner},name->context.isWatched(name.replace('.','/')),instrumentation,context,3,Set.of())) {
                    check(failureMode.equals("prepare")?!save.refusals().isEmpty():save.refusals().isEmpty(),"stock coordinator preflight: "+save.refusals());
                    check(save.names().equals(Set.of(record.getName(),owner.getName())),"stock coordinator exact changed typed caller scope");
                    check(tag(owner).equals("old")&&heldMethod.invoke(oldObject).equals("old:3"),"stock save preparation keeps physical startup route");
                    save.requireComplete(failureMode.equals("late")?Set.of(record.getName()):save.names());
                    var aftermath=new LinkedHashMap<Class<?>,java.util.function.Supplier<com.onurkat.reclazz.agent.ClassReloader.ReloadResult>>();
                    if(failureMode.isEmpty())for(var change:save.changes())aftermath.put(change.owner(),bookkeeping.prepareRecordAftermath(change.owner(),change.bytes(),nativeReloader));
                    var results=new LinkedHashMap<Class<?>,com.onurkat.reclazz.agent.ClassReloader.ReloadResult>();
                    RecordNativeBatch.Outcome outcome;
                    if(engine) {
                        Class<?> agent=com.onurkat.reclazz.agent.ReclazzAgent.class;var structural=agent.getDeclaredField("structuralReloader");structural.setAccessible(true);structural.set(null,bookkeeping);
                        Class<?> adapter=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent$NativeRecordSave");
                        var constructor=adapter.getDeclaredConstructor(RecordStockBatch.class);constructor.setAccessible(true);Object state=constructor.newInstance(save);
                        var repairs=adapter.getDeclaredField("repairs");repairs.setAccessible(true);repairs.set(state,RecordNativeRepairs.prepare(save.changes().stream().map(RecordNativeBatch.Change::owner).toList(),List.of()));
                        var apply=adapter.getDeclaredMethod("apply",String.class,com.onurkat.reclazz.agent.ClassReloader.class);apply.setAccessible(true);
                        for(var change:save.changes())results.put(change.owner(),(com.onurkat.reclazz.agent.ClassReloader.ReloadResult)apply.invoke(state,change.name(),nativeReloader));
                        check(results.values().stream().allMatch(com.onurkat.reclazz.agent.ClassReloader.ReloadResult::isSuccess),"engine save returns whole-cohort success");
                        var failures=adapter.getDeclaredField("repairFailures");failures.setAccessible(true);check(((Map<?,?>)failures.get(state)).isEmpty(),"engine save exact repairs have no hidden failure: "+failures.get(state));
                        outcome=save.commit(change->{}); // Idempotent receipt: must not perform a second JNI/CAS.
                    }else outcome=save.commit(change->{
                        if(failureMode.equals("repair")&&change.owner()==owner)throw new IllegalStateException("synthetic caller repair failure");
                        if(failureMode.isEmpty())results.put(change.owner(),aftermath.get(change.owner()).get());
                    });
                    boolean rejected=Set.of("prepare","native","activation","late").contains(failureMode);
                    if(rejected) {
                        check(!outcome.committed(),"negative control cannot publish: "+outcome);
                        check(nativeCalls[0]==(Set.of("prepare","late").contains(failureMode)?0:1),"exact actual JNI call count on refusal");
                        check(tag(owner).equals("old")&&heldMethod.invoke(oldObject).equals("old:3"),"failure keeps caller header and dispatch old");
                        check(owner.getDeclaredField("value").getInt(heldConstructor.newInstance(9))==9,"failure keeps caller constructor old");
                        check(record.getRecordComponents().length==1&&owner.getDeclaredField("retired")!=null,"failure keeps record and caller roots old");
                        check(!KeepBaseline.hasVirtualRecordReceipt(record),"failure never advances logical constructor receipt");
                        check(Arrays.equals(physicalRecord,PhysicalDefinitions.get(record))&&Arrays.equals(physicalCaller,PhysicalDefinitions.get(owner)),"failure retains complete physical definitions");
                        check(save.refusals().keySet().equals(save.names()),"late or failed admission reports whole cohort refusal");
                        failureMarker();return;
                    }
                    check(outcome.attempted()&&outcome.committed()&&(failureMode.equals("repair")?outcome.repairFailures().keySet().equals(Set.of(owner.getName())):outcome.repairFailures().isEmpty()),"stock coordinator real native/CAS: "+outcome);
                    check(nativeCalls[0]==1,"exactly one full native cohort call");
                    check(KeepBaseline.hasVirtualRecordReceipt(record),"stock coordinator records actual logical constructor receipt");
                    if(failureMode.isEmpty()) {
                        check(results.get(record).isStructuralReload()&&results.get(owner).isStructuralReload(),"first S metadata reports actual structural edit");
                        check(context.getMetadata("com/example/CohortCaller")==startupMetadata,"stock aftermath preserves physical field rewrite baseline");
                    }
                }finally{instrumentation.removeTransformer(observer);}
            }else try(var pending=stage(record,schema,owner,compiled)) {
                PhysicalDefinitions.redefine(instrumentation,new ClassDefinition(record,PhysicalDefinitions.get(record)),new ClassDefinition(owner,PhysicalDefinitions.get(owner)));
                check(tag(owner).equals("old")&&heldMethod.invoke(oldObject).equals("old:3"),"real native admission does not publish logical caller early");
                check(pending.commit(),"record and source caller activate at one CAS");
            }finally{instrumentation.removeTransformer(observer);}
            check(tag(owner).equals("new"),"new caller class annotation");
            check(Arrays.stream(owner.getAnnotations()).anyMatch(a->a.annotationType().getName().equals("com.example.CallerInherited")),"caller inherits real superclass annotations");
            var parent=(ParameterizedType)owner.getGenericSuperclass();check(parent.getRawType()==baseOwner&&parent.getActualTypeArguments()[0].equals(owner.getTypeParameters()[0]),"caller generic superclass resolves candidate variable");
            check(Arrays.stream(owner.getAnnotatedSuperclass().getAnnotations()).anyMatch(a->a.annotationType().getName().equals("com.example.CallerUse")),"caller annotated superclass preserved by inert carrier");
            var variable=owner.getTypeParameters()[0];check(variable.getName().equals("U")&&variable.getGenericDeclaration()==owner&&variable.getBounds().length==2,"new caller type variable declaration and bounds");
            var generic=(ParameterizedType)owner.getDeclaredField("items").getGenericType();check(generic.getActualTypeArguments()[0].equals(variable),"new caller member resolves candidate generic scope");
            check(((ParameterizedType)heldItems.getGenericType()).getActualTypeArguments()[0].equals(heldVariable)&&heldVariable.getName().equals("T"),"held caller field and variable stay frozen");
            check(tag(heldMethod).equals("old")&&heldMethod.invoke(oldObject).equals("new:3"),"held caller method freezes metadata and follows new body");
            Object value=heldConstructor.newInstance(5);check(owner.getDeclaredField("value").getInt(value)==105,"held logical caller constructor follows new body");
            check(baseOwner.getDeclaredField("baseValue").getInt(value)==1,"source caller delegates through captured parent initializer");
            check(owner.getDeclaredField("value").getInt(heldFound.invokeWithArguments(7))==107,"caller constructor MH found before S follows committed body");
            check(owner.getDeclaredField("value").getInt(heldUnreflected.invokeWithArguments(8))==108,"caller constructor MH unreflected before S follows committed body");
            check(owner.getDeclaredField("value").getInt(heldReference.apply(10))==110,"caller constructor reference retained before S follows committed body");
            Object auxiliary=owner.getDeclaredConstructor(String.class).newInstance("6");check(owner.getDeclaredField("value").getInt(auxiliary)==106,"new caller constructor delegates on one allocation");
            Object recordValue=owner.getDeclaredField("record").get(value);check(record.getDeclaredMethod("added").invoke(recordValue).equals("cohort"),"source caller links new record canonical constructor");
            check(owner.getDeclaredField("added").get(oldObject).equals("fresh"),"old caller object uses committed staged field initializer");
            try{owner.getDeclaredField("retired");throw new AssertionError("removed caller field remains");}catch(NoSuchFieldException expected){checks++;}
            try{owner.getDeclaredMethod("retired");throw new AssertionError("removed caller method remains");}catch(NoSuchMethodException expected){checks++;}
            try{heldRetired.invoke(oldObject);throw new AssertionError("retained caller method executes removed body");}catch(InvocationTargetException expected){check(expected.getCause() instanceof NoSuchMethodError,"held removed caller method reports retired descriptor");}
            if(batch&&failureMode.isEmpty()) {
                if(interleaved) {
                    Constructor<?> duringS=owner.getDeclaredConstructor(int.class);
                    Constructor<?> auxiliaryDuringS=owner.getDeclaredConstructor(String.class);
                    var referenceDuringS=(java.util.function.IntFunction<?>)owner.getDeclaredMethod("reference").invoke(null);
                    var ordinaryCaller=new SourceFile(owner.getName(),caller(true).getCharContent(false).toString().replace("return \"new:","return \"ordinary:").replace("value+100","value+200"));
                    byte[] ordinary=compile(record(true),ordinaryCaller,annotation,use,inherited,base).get("com/example/CohortCaller");
                    instrumentation.addTransformer(observer,true);
                    try{check(bookkeeping.reload(owner.getName(),ordinary).isSuccess(),"ordinary caller-only save applies without a record cohort");}
                    finally{instrumentation.removeTransformer(observer);}
                    check(heldMethod.invoke(oldObject).equals("ordinary:3"),"ordinary caller-only save keeps its existing publication path");
                    check(owner.getDeclaredField("value").getInt(heldConstructor.newInstance(5))==205,"ordinary accepted physical caller constructor changes");
                    check(owner.getDeclaredField("value").getInt(duringS.newInstance(5))==205,"caller Constructor acquired during S follows later ordinary native body");
                    check(owner.getDeclaredField("value").getInt(referenceDuringS.apply(5))==205,"caller reference acquired during S follows later ordinary native body");
                    check(owner.getDeclaredField("value").getInt(heldReference.apply(5))==205,"caller reference retained before S follows later ordinary native body");
                    check(owner.getDeclaredField("value").getInt(heldFound.invokeWithArguments(5))==205,"caller MH retained before S follows later ordinary native body");
                    check(owner.getDeclaredField("value").getInt(auxiliaryDuringS.newInstance("5"))==205,"virtual-only caller Constructor follows later ordinary added constructor body");
                }
                var beforeLaterMetadata=context.getMetadata("com/example/CohortCaller");
                KeepBaseline.advance(record,next.get("com/example/CohortRecord"));KeepBaseline.advance(owner,next.get("com/example/CohortCaller"));
                var laterCaller=new SourceFile(owner.getName(),caller(true).getCharContent(false).toString().replace("return \"new:","return \"later:"));
                var later=compile(record(true),laterCaller,annotation,use,inherited,base);var incoming=new LinkedHashMap<String,byte[]>();later.forEach((key,bytes)->incoming.put(key.replace('/','.'),bytes));
                instrumentation.addTransformer(observer,true);
                try(var save=RecordStockBatch.prepare(incoming,new Class<?>[]{record,owner,baseOwner},name->context.isWatched(name.replace('.','/')),instrumentation,context,4,Set.of())) {
                    check(save.active()&&save.refusals().isEmpty(),"body-only committed record re-enters stock coordinator");
                    var aftermath=new LinkedHashMap<Class<?>,java.util.function.Supplier<com.onurkat.reclazz.agent.ClassReloader.ReloadResult>>();
                    for(var change:save.changes())aftermath.put(change.owner(),bookkeeping.prepareRecordAftermath(change.owner(),change.bytes(),nativeReloader));
                    var results=new LinkedHashMap<Class<?>,com.onurkat.reclazz.agent.ClassReloader.ReloadResult>();
                    check(save.commit(change->results.put(change.owner(),aftermath.get(change.owner()).get())).committed()&&nativeCalls[0]==(interleaved?3:2),"later body-only save uses one additional actual native/CAS");
                    check(results.values().stream().noneMatch(com.onurkat.reclazz.agent.ClassReloader.ReloadResult::isStructuralReload),"later body-only save does not invent structural reload");
                    check(context.getMetadata("com/example/CohortCaller")==beforeLaterMetadata,"later logical metadata never overwrites ordinary baseline");
                    check(owner.getDeclaredField("added").get(unread).equals("fresh"),"later cohort preserves initialization of a never-read virtual field");
                    check(heldMethod.invoke(oldObject).equals("later:3"),"later body-only caller body reaches original held method");
                    check(owner.getDeclaredField("value").getInt(heldReference.apply(5))==105,"retained caller reference follows cohort after ordinary replacement");
                }finally{instrumentation.removeTransformer(observer);}
            }
            check(RecordReloadPlan.ready(RecordReloadPlan.Route.STOCK),"C3 stock entry still requires actual capability and complete save preflight");
            if(!failureMode.isEmpty()){failureMarker();return;}
            System.out.println((interleaved?"RECORD_STOCK_INTERLEAVED_OK":uncaptured?"RECORD_UNCAPTURED_STOCK_OK":engine?"RECORD_ENGINE_SAVE_OK":batch?"RECORD_STOCK_BATCH_OK":"RECORD_CALLER_ASSEMBLY_OK")+" checks="+checks+" runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));
        }
    }
}
