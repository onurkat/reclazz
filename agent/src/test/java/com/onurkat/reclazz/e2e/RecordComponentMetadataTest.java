/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import com.onurkat.reclazz.e2e.harness.JavaLauncher;

import com.onurkat.reclazz.bootstrap.*;
import com.onurkat.reclazz.e2e.harness.WatchedApp;
import com.onurkat.reclazz.reload.RecordMetadataCarrier;
import com.onurkat.reclazz.reload.RecordMemberSnapshot;
import com.onurkat.reclazz.transform.CallSiteAdapter;
import org.objectweb.asm.tree.ClassNode;
import java.lang.annotation.*;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real JDK roots and parsers, isolated from the worker; this is metadata preparation, not S acceptance. */
class RecordComponentMetadataTest {
    @TempDir Path tmp;
    @Test void annotationAndGenericTypesFollowThePublishedCarrier()throws Exception {runProbe(Probe.class,"COMPONENT_METADATA_OK");}
    void runProbe(Class<?> probe,String marker)throws Exception{runProbe(probe,marker,ProbeAgent.class);}
    void runProbe(Class<?> probe,String marker,Class<?> agent)throws Exception {
        String product=WatchedApp.agentJarOrSkip();
        var manifest=new Manifest();var attributes=manifest.getMainAttributes();
        attributes.putValue("Manifest-Version","1.0");attributes.putValue("Premain-Class",agent.getName());
        attributes.putValue("Can-Retransform-Classes","true");
        attributes.putValue("Can-Redefine-Classes","true");
        Path shim=tmp.resolve("metadata-probe.jar");
        String resource=agent.getName().replace('.','/')+".class";
        try(var out=new JarOutputStream(Files.newOutputStream(shim),manifest);var in=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in);out.putNextEntry(new JarEntry(resource));in.transferTo(out);out.closeEntry();
        }
        String java=JavaLauncher.resolve(Path.of(System.getProperty("reclazz.record.stock.java",Path.of(System.getProperty("java.home"),"bin","java").toString()))).toString();
        Path evidence=Path.of(System.getProperty("reclazz.record.evidenceDir",tmp.toString()));Files.createDirectories(evidence);
        Path log=evidence.resolve("component-metadata-"+UUID.randomUUID()+".log");
        var args=WatchedApp.utf8Java(java,"-javaagent:"+shim+"="+product,"-cp",System.getProperty("java.class.path"),probe.getName());
        if(Boolean.getBoolean("reclazz.record.unsafe.deny")) {
            args.add(1,"--sun-misc-unsafe-memory-access=deny");args.add(1,"-Dreclazz.record.unsafe.deny=true");
        }
        Process child=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(45,TimeUnit.SECONDS),"metadata child exceeded deadline: "+log);
            List<String> lines=Files.readAllLines(log);String tail=String.join("\n",lines.subList(Math.max(0,lines.size()-24),lines.size()));
            assertEquals(0,child.exitValue(),tail);
            assertTrue(lines.stream().anyMatch(s->s.startsWith(marker+" checks=")),tail);
        } finally {if(child.isAlive()){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}}
    }
    /** Only the actual production reflection installer runs; no application reload is simulated. */
    public static class ProbeAgent {
        public static Instrumentation instrumentation;
        public static void premain(String product,Instrumentation inst)throws Exception {
            instrumentation=inst;
            Path directory=Files.createTempDirectory("reclazz-component-probe-");directory.toFile().deleteOnExit();
            Path bootstrap=directory.resolve("bootstrap.jar");bootstrap.toFile().deleteOnExit();
            try(var jar=new JarFile(product);var in=jar.getInputStream(jar.getJarEntry("META-INF/reclazz-bootstrap.bin"))) {
                Files.copy(in,bootstrap);
            }
            inst.appendToBootstrapClassLoaderSearch(new JarFile(bootstrap.toFile()));
            Class.forName("com.onurkat.reclazz.bootstrap.UnsafeAccess",true,null)
                    .getMethod("useForFallback",Instrumentation.class).invoke(null,inst);
            var installer=Class.forName("com.onurkat.reclazz.transform.ReflectionMethodTransformer");
            installer.getMethod("install",Instrumentation.class,java.util.function.BiFunction.class)
                    .invoke(null,inst,(java.util.function.BiFunction<Class<?>,String,MethodHandle>)(owner,key)->null);
        }
    }
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.RECORD_COMPONENT)
    public @interface Decl {String value();}
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE)
    public @interface Use {String value();}
    public record Logical<T>(@Decl("old") List<@Use("old-element") T> value,int retired) {
        public static String added(Logical<?> owner){return "added";}
        public static Logical<?> initialize(Logical<?> receiver,List<?> value,int retired){return receiver;}
    }
    public record Carrier<T>(@Decl("new") List<@Use("new-element") T> value,@Decl("extra") String extra) { }
    public record Foreign<T>(List<T> value) { }
    public record AllWidths(boolean flag,byte tiny,short small,char letter,int number,long wide,float fraction,double precise,Object reference,int[] array) {
        public static AllWidths victim;
        public static AllWidths initialize(AllWidths receiver,boolean flag,byte tiny,short small,char letter,int number,long wide,float fraction,double precise,Object reference,int[] array)throws Throwable {
            if(MemberForge.delegate(AllWidths.class,Record.class,"()V",new Object[0])!=receiver)throw new AssertionError("record super delegation changed allocation");
            MemberForge.constructorField(receiver,flag,AllWidths.class,"flag");
            MemberForge.constructorField(receiver,tiny,AllWidths.class,"tiny");
            MemberForge.constructorField(receiver,small,AllWidths.class,"small");
            MemberForge.constructorField(receiver,letter,AllWidths.class,"letter");
            if(victim!=null) {
                try {
                    MemberForge.constructorField(victim,123,AllWidths.class,"number");
                    throw new AssertionError("logical frame wrote another record instance");
                }catch(IllegalStateException expected){ }
            }
            MemberForge.constructorField(receiver,number,AllWidths.class,"number");
            MemberForge.constructorField(receiver,wide,AllWidths.class,"wide");
            MemberForge.constructorField(receiver,fraction,AllWidths.class,"fraction");
            MemberForge.constructorField(receiver,precise,AllWidths.class,"precise");
            MemberForge.constructorField(receiver,reference,AllWidths.class,"reference");
            MemberForge.constructorField(receiver,array,AllWidths.class,"array");
            MemberForge.finishConstruction();return receiver;
        }
    }
    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.RECORD_COMPONENT,ElementType.CONSTRUCTOR,ElementType.PARAMETER,ElementType.FIELD,ElementType.METHOD})
    public @interface Entry {String value();}
    public record SnapshotOwner(int value,String retired) {
        public static Runnable onAux;
        public static SnapshotOwner initialize(SnapshotOwner receiver,int value)throws Throwable {
            if(value<0)throw new IllegalArgumentException("negative");
            MemberForge.delegate(SnapshotOwner.class,Record.class,"()V",new Object[0]);
            MemberForge.constructorField(receiver,value,SnapshotOwner.class,"value");MemberForge.finishConstruction();return receiver;
        }
        private static SnapshotOwner initializeNext(SnapshotOwner receiver,int value)throws Throwable {return initialize(receiver,value+100);}
        public static SnapshotOwner auxiliary(SnapshotOwner receiver,int value,String ignored)throws Throwable {
            var run=onAux;onAux=null;if(run!=null)run.run();
            return (SnapshotOwner)MemberForge.delegate(SnapshotOwner.class,SnapshotOwner.class,"(I)V",new Object[]{value+1});
        }
        public static int logicalHash(SnapshotOwner receiver)throws Throwable {return RecordObjects.hashCode(MethodHandles.lookup(),SnapshotOwner.class,receiver);}
        public static boolean logicalEquals(SnapshotOwner receiver,Object other)throws Throwable {return RecordObjects.equals(MethodHandles.lookup(),SnapshotOwner.class,receiver,other);}
        public static String logicalText(SnapshotOwner receiver)throws Throwable {return RecordObjects.toString(MethodHandles.lookup(),SnapshotOwner.class,receiver);}
    }
    public record Template(@Entry("new-value") int value) {
        @Entry("canonical") public Template {if(value<0)throw new IllegalArgumentException("negative");}
        @Entry("auxiliary") public Template(@Entry("aux-value") int value,String ignored){this(value+1);}
    }
    public static class SnapshotProbe {
        private static int checks;
        private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
        private static String key(String name,String descriptor){return InjectedNames.siteKey(name,CallSiteAdapter.descHash(descriptor));}
        public static void main(String[] args)throws Throwable {
            var lookup=MethodHandles.privateLookupIn(SnapshotOwner.class,MethodHandles.lookup());
            byte[] source;
            try(var in=Template.class.getResourceAsStream("/"+Template.class.getName().replace('.','/')+".class")) {
                var node=new ClassNode();new ClassReader(in.readAllBytes()).accept(node,0);
                node.name=SnapshotOwner.class.getName().replace('.','/');var writer=new ClassWriter(0);node.accept(writer);source=writer.toByteArray();
            }
            var targets=new LinkedHashMap<String,MethodHandle>();
            targets.put(key("value","()I"),lookup.findVirtual(SnapshotOwner.class,"value",MethodType.methodType(int.class)));
            targets.put(key("hashCode","()I"),lookup.findStatic(SnapshotOwner.class,"logicalHash",MethodType.methodType(int.class,SnapshotOwner.class)));
            targets.put(key("equals","(Ljava/lang/Object;)Z"),lookup.findStatic(SnapshotOwner.class,"logicalEquals",MethodType.methodType(boolean.class,SnapshotOwner.class,Object.class)));
            targets.put(key("toString","()Ljava/lang/String;"),lookup.findStatic(SnapshotOwner.class,"logicalText",MethodType.methodType(String.class,SnapshotOwner.class)));
            var first=lookup.findStatic(SnapshotOwner.class,"initialize",MethodType.methodType(SnapshotOwner.class,SnapshotOwner.class,int.class));
            var later=lookup.findStatic(SnapshotOwner.class,"initializeNext",MethodType.methodType(SnapshotOwner.class,SnapshotOwner.class,int.class));
            var auxiliary=lookup.findStatic(SnapshotOwner.class,"auxiliary",MethodType.methodType(SnapshotOwner.class,SnapshotOwner.class,int.class,String.class));
            LookupCapture.trust(SnapshotProbe.class);LookupCapture.trust(RecordMemberSnapshot.class);LookupCapture.seal();
            try {Outside.assemble(lookup,source,targets,Map.of("(I)V",first,"(ILjava/lang/String;)V",auxiliary));throw new AssertionError("untrusted caller used the engine snapshot factory");}
            catch(SecurityException expected){checks++;}
            try {Outside.spoofTrustedCaller();throw new AssertionError("untrusted caller spoofed an engine requester");}
            catch(SecurityException expected){checks++;}
            var prepared=RecordMemberSnapshot.prepare(SnapshotOwner.class,lookup,null,source,targets,Map.of("(I)V",first,"(ILjava/lang/String;)V",auxiliary));
            var slot=RecordSchema.slot(SnapshotOwner.class);var initial=slot.current();
            try {RecordMemberSnapshot.prepare(SnapshotOwner.class,MethodHandles.publicLookup(),null,source,targets,Map.of());throw new AssertionError("unowned snapshot lookup accepted");}
            catch(SecurityException expected){checks++;}
            try {RecordMemberSnapshot.prepare(SnapshotOwner.class,lookup,null,source,targets,Map.of());throw new AssertionError("missing initializers accepted");}
            catch(IllegalArgumentException expected){checks++;}
            var missingTarget=new LinkedHashMap<>(targets);missingTarget.remove(key("hashCode","()I"));
            try {RecordMemberSnapshot.prepare(SnapshotOwner.class,lookup,null,source,missingTarget,Map.of("(I)V",first,"(ILjava/lang/String;)V",auxiliary));throw new AssertionError("missing body silently fell back to physical old schema");}
            catch(IllegalArgumentException expected){checks++;}
            check(slot.current()==initial,"rejected private assembly never advances schema");
            check(SnapshotOwner.class.getRecordComponents().length==2,"full assembly must not publish new components");
            try {SnapshotOwner.class.getDeclaredConstructor(int.class);throw new AssertionError("prepared canonical visible early");}
            catch(NoSuchMethodException expected){checks++;}
            try(var next=slot.prepareVirtual(prepared.members())) {
                next.stageDispatch(SnapshotOwner.class,prepared.targets());
                check(SnapshotOwner.class.getRecordComponents().length==2,"staged targets do not publish metadata");check(next.commit(),"member snapshot CAS");
            }
            check(SnapshotOwner.class.getRecordComponents().length==1,"retired component hidden");
            check(Arrays.stream(SnapshotOwner.class.getDeclaredFields()).map(Field::getName).toList().equals(List.of("value")),"full logical fields hide retired field");
            try {SnapshotOwner.class.getDeclaredMethod("retired");throw new AssertionError("retired accessor visible");}
            catch(NoSuchMethodException expected){checks++;}
            var canonical=SnapshotOwner.class.getDeclaredConstructor(int.class);
            var aux=SnapshotOwner.class.getDeclaredConstructor(int.class,String.class);
            check(SnapshotOwner.class.getDeclaredConstructors().length==2,"explicit auxiliary retained with new canonical");
            check(canonical.getDeclaredAnnotation(Entry.class).value().equals("canonical"),"canonical annotation carrier");
            check(aux.getDeclaredAnnotation(Entry.class).value().equals("auxiliary"),"old physical descriptor now has actual auxiliary metadata");
            check(canonical.getParameters()[0].isNamePresent()&&canonical.getParameters()[0].getName().equals("value"),"canonical parameter name retained");
            check(canonical.getParameters()[0].getDeclaredAnnotation(Entry.class).value().equals("new-value"),"canonical parameter annotation retained");
            SnapshotOwner one=(SnapshotOwner)canonical.newInstance(7),two=(SnapshotOwner)aux.newInstance(7,"ignored");
            check(one.value()==7&&one.retired()==null,"new canonical fills logical value without old constructor");
            try {canonical.newInstance(-1);throw new AssertionError("logical constructor validation ignored");}
            catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalArgumentException,"new constructor validation exception preserved");}
            check(two.value()==8&&two.retired()==null,"auxiliary delegates into same allocation");
            check(SnapshotOwner.class.getDeclaredMethod("toString").invoke(one).equals("SnapshotOwner[value=7]"),"assembled ObjectMethods root invokes logical body");
            check((boolean)SnapshotOwner.class.getDeclaredMethod("equals",Object.class).invoke(one,new SnapshotOwner(7,"old")),"logical equality ignores retired physical component");
            var subsequent=RecordMemberSnapshot.prepare(SnapshotOwner.class,lookup,null,source,targets,Map.of("(I)V",later,"(ILjava/lang/String;)V",auxiliary));
            try(var next=slot.prepareVirtual(subsequent.members())) {
                next.stageDispatch(SnapshotOwner.class,subsequent.targets());SnapshotOwner.onAux=()->{if(!next.commit())throw new AssertionError("next epoch CAS");};
                SnapshotOwner frozen=(SnapshotOwner)aux.newInstance(9,"ignored");
                check(frozen.value()==10,"constructor delegation keeps entry snapshot across an intervening schema CAS");
            }
            check(((SnapshotOwner)canonical.newInstance(7)).value()==107,"held canonical follows the next committed initializer");
            check(SnapshotOwner.class.getRecordComponents()[0].getAccessor().invoke(one).equals(7),"assembled component accessor root is independently callable");
            System.out.println("RECORD_MEMBER_SNAPSHOT_OK checks="+checks+" runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));
        }
    }
    public static class Outside {
        public static RecordMemberSnapshot.Prepared assemble(MethodHandles.Lookup lookup,byte[] source,
                Map<String,MethodHandle> targets,Map<String,MethodHandle> initializers)throws Exception {
            return RecordMemberSnapshot.prepare(SnapshotOwner.class,lookup,null,source,targets,initializers);
        }
        public static void spoofTrustedCaller(){LookupCapture.requireTrustedCaller(SnapshotProbe.class,"preparing a record member snapshot");}
        public static Field forge()throws Exception {return MemberForge.field(Logical.class,Foreign.class.getDeclaredField("value"));}
    }
    public static class Probe {
        private static int checks;
        private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
        private static byte[] bytes(Class<?> type)throws Exception {
            try(var in=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){return in.readAllBytes();}
        }
        private static String signature(byte[] bytes,String name) {
            String[] found={null};new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public MethodVisitor visitMethod(int access,String method,String desc,String generic,String[] exceptions){
                    if(method.equals(name))found[0]=generic;return null;
                }
            },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);return found[0];
        }
        private static String fieldSignature(byte[] bytes,String name) {
            String[] found={null};new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public FieldVisitor visitField(int access,String field,String desc,String generic,Object value){
                    if(field.equals(name))found[0]=generic;return null;
                }
            },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);return found[0];
        }
        private static AnnotatedType element(Field field) {
            return ((AnnotatedParameterizedType)field.getAnnotatedType()).getAnnotatedActualTypeArguments()[0];
        }
        public static void main(String[] args)throws Throwable {
            check(MethodForge.isAvailable(),"production JDK reflection guards installed: "+MethodForge.initializationFailure);
            var lookup=MethodHandles.lookup();byte[] carrierBytes=RecordMetadataCarrier.generate(bytes(Carrier.class));
            Class<?> hidden=lookup.defineHiddenClass(carrierBytes,true,MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
            check(hidden.isRecord()&&hidden.isHidden(),"actual hidden record carrier");
            RecordComponent value=Arrays.stream(hidden.getRecordComponents()).filter(c->c.getName().equals("value")).findFirst().orElseThrow();
            RecordComponent extra=Arrays.stream(hidden.getRecordComponents()).filter(c->c.getName().equals("extra")).findFirst().orElseThrow();
            var valueTarget=lookup.findVirtual(Logical.class,"value",MethodType.methodType(List.class));
            var extraTarget=lookup.findStatic(Logical.class,"added",MethodType.methodType(String.class,Logical.class));
            Method valueRoot=MethodForge.snapshotMethod(Logical.class,value.getAccessor(),valueTarget,signature(carrierBytes,"value"));
            Method extraRoot=MethodForge.snapshotMethod(Logical.class,extra.getAccessor(),extraTarget,signature(carrierBytes,"extra"));
            MemberForge.fieldSignature(Logical.class,"value",fieldSignature(bytes(Logical.class),"value"));
            Field oldField=Logical.class.getDeclaredField("value");
            MemberForge.fieldSignature(hidden,"value",fieldSignature(carrierBytes,"value"));
            Field preparedField=MemberForge.snapshotField(Logical.class,hidden.getDeclaredField("value"));
            check(!MemberForge.isField(oldField),"preparing a field must not register an equal physical field as forged");
            check(element(oldField).getAnnotation(Use.class).value().equals("old-element"),"preparing field metadata must not overwrite an equal live root");
            check(element(preparedField).getAnnotation(Use.class).value().equals("new-element"),"prepared field owns its annotation carrier");
            preparedField.setAccessible(true);Field fieldCopy=MethodForge.reflectionCopy(preparedField);
            check(fieldCopy!=preparedField&&!fieldCopy.isAccessible(),"public field copy resets accessible flag");
            check(element(fieldCopy).getAnnotation(Use.class).value().equals("new-element"),"field copy retains snapshot carrier");
            Field secondFieldCopy=MethodForge.reflectionCopy(fieldCopy);
            check(element(secondFieldCopy).getType().equals(Logical.class.getTypeParameters()[0]),"field copy-of-copy rebinds generic owner");
            check(((ParameterizedType)secondFieldCopy.getGenericType()).getActualTypeArguments()[0].equals(Logical.class.getTypeParameters()[0]),"field generic signature belongs to logical owner");
            try {
                MemberForge.snapshotField(Logical.class,Foreign.class.getDeclaredField("value"));
                throw new AssertionError("foreign field metadata accepted");
            }catch(IllegalArgumentException expected){checks++;}
            Field addedField=MemberForge.snapshotField(Logical.class,hidden.getDeclaredField("extra"));addedField.setAccessible(true);
            var members=new RecordSchema.Members(List.of(new RecordSchema.Component("extra",String.class,extraRoot,extra),
                    new RecordSchema.Component("value",List.class,valueRoot,value)),List.of(extraRoot,valueRoot),List.of(addedField,preparedField),List.of(),Map.of(),Map.of(),Map.of());
            var slot=RecordSchema.slot(Logical.class);var before=slot.current();
            var oldInitializer=lookup.findStatic(Logical.class,"initialize",MethodType.methodType(Logical.class,Logical.class,List.class,int.class));
            Constructor<?> heldCanonical=MemberForge.snapshotConstructor(Logical.class,Logical.class.getDeclaredConstructor(List.class,int.class),Modifier.PUBLIC,null,oldInitializer);
            try(var pending=slot.prepareVirtual(members)) {
                check(Logical.class.getRecordComponents()[0].getDeclaredAnnotation(Decl.class).value().equals("old"),"pending metadata not visible");
                check(Logical.class.getRecordComponents()[1].getName().equals("retired"),"pending component order not visible");
                check(pending.commit(),"single schema CAS");
            }
            RecordComponent[] first=Logical.class.getRecordComponents(),second=Logical.class.getRecordComponents();
            check(Arrays.stream(first).map(RecordComponent::getName).toList().equals(List.of("extra","value")),"logical component order and retired removal");
            check(first[0].getDeclaringRecord()==Logical.class&&first[1].getDeclaringRecord()==Logical.class,"logical declaring record");
            check(first[0].getDeclaredAnnotation(Decl.class).value().equals("extra"),"added component declaration annotation");
            check(first[1].getDeclaredAnnotation(Decl.class).value().equals("new"),"new carrier declaration annotation");
            ParameterizedType generic=(ParameterizedType)first[1].getGenericType();
            check(generic.getRawType()==List.class&&generic.getActualTypeArguments()[0].equals(Logical.class.getTypeParameters()[0]),"generic variable belongs to logical owner");
            AnnotatedParameterizedType annotated=(AnnotatedParameterizedType)first[1].getAnnotatedType();
            var element=annotated.getAnnotatedActualTypeArguments()[0];
            check(element.getAnnotation(Use.class).value().equals("new-element"),"carrier constant pool parses type-use annotation");
            check(element.getType().equals(Logical.class.getTypeParameters()[0]),"annotated generic variable belongs to logical owner");
            check(first[0]!=second[0]&&first[0].getAccessor()!=second[0].getAccessor(),"fresh components and accessor copies");
            first[0].getAccessor().setAccessible(true);
            check(!second[0].getAccessor().isAccessible(),"accessor override flag not shared");
            var instance=new Logical<String>(List.of("a"),2);
            check(second[0].getAccessor().invoke(instance).equals("added"),"component accessor invokes prepared target");
            check(preparedField.get(instance).equals(List.of("a")),"compatible physical field value survives logical CAS");
            check(addedField.get(instance)==null,"old object added reference field defaults to null");
            check(RecordObjects.toString(lookup,Logical.class,instance).equals("Logical[extra=null, value=[a]]"),"ObjectMethods use logical order and added defaults");
            check(RecordObjects.hashCode(lookup,Logical.class,instance)==List.of("a").hashCode(),"logical hash omits retired physical component");
            check(RecordObjects.equals(lookup,Logical.class,instance,new Logical<String>(List.of("a"),999)),"logical equality omits retired physical component");
            check(Logical.class.getDeclaredMethod("extra").invoke(instance).equals("added"),"Class declared method uses root rather than non-root copy");
            check(((ParameterizedType)first[1].getAccessor().getGenericReturnType()).getActualTypeArguments()[0].equals(Logical.class.getTypeParameters()[0]),"accessor generic owner");
            check(ReflectionBridge.physicalRecordComponents(Logical.class)[1].getName().equals("retired"),"engine physical schema still original");
            check(String.class.getRecordComponents()==null,"non-record null contract");
            try {
                lookup.findConstructor(Logical.class,MethodType.methodType(void.class,List.class,int.class));
                throw new AssertionError("retired physical canonical escaped logical lookup");
            }catch(NoSuchMethodException expected){checks++;}
            try {
                heldCanonical.newInstance(List.of(),1);throw new AssertionError("held retired logical constructor ran its old body");
            }catch(InvocationTargetException expected){check(expected.getCause() instanceof NoSuchMethodError,"held retired constructor rejects before allocation");}
            check(element(Logical.class.getDeclaredField("value")).getAnnotation(Use.class).value().equals("new-element"),"Class field root copy uses snapshot carrier after CAS");
            check(element(oldField).getAnnotation(Use.class).value().equals("old-element"),"held old field keeps its metadata after CAS");
            try {
                var foreign=Foreign.class.getRecordComponents()[0];
                var bad=new RecordSchema.Members(List.of(new RecordSchema.Component("value",List.class,valueRoot,foreign)),List.of(valueRoot),List.of(),List.of(),Map.of(),Map.of(),Map.of());
                slot.prepareVirtual(bad);throw new AssertionError("foreign record carrier accepted");
            } catch(IllegalArgumentException expected){checks++;}
            check(slot.current().sequence()==before.sequence()+1,"invalid prepare does not publish");
            Class<?>[] arguments={boolean.class,byte.class,short.class,char.class,int.class,long.class,float.class,double.class,Object.class,int[].class};
            var initializerType=MethodType.methodType(AllWidths.class,arguments).insertParameterTypes(0,AllWidths.class);
            var initializer=lookup.findStatic(AllWidths.class,"initialize",initializerType);
            Constructor<?> physical=AllWidths.class.getDeclaredConstructor(arguments);
            Constructor<?> prepared=MemberForge.snapshotConstructor(AllWidths.class,physical,Modifier.PUBLIC,null,initializer);
            Object reference=new Object();int[] array={2,4};
            AllWidths.victim=new AllWidths(false,(byte)0,(short)0,'A',13,0L,0f,0d,null,null);
            AllWidths constructed=(AllWidths)prepared.newInstance(true,(byte)-8,(short)1234,'Z',70001,9000000001L,-0.0f,Double.NaN,reference,array);
            check(constructed.flag()&&constructed.tiny()==-8&&constructed.small()==1234&&constructed.letter()=='Z',"boolean/byte/short/char physical writes");
            check(constructed.number()==70001&&constructed.wide()==9000000001L,"int/long physical writes");
            check(Float.floatToRawIntBits(constructed.fraction())==Float.floatToRawIntBits(-0.0f)&&Double.isNaN(constructed.precise()),"float/double physical writes preserve signed zero and NaN");
            check(constructed.reference()==reference&&constructed.array()==array,"reference/array physical writes preserve identity");
            try {
                MemberForge.constructorField(constructed,0,AllWidths.class,"number");
                throw new AssertionError("record final write outside construction frame accepted");
            }catch(IllegalStateException expected){checks++;}
            check(constructed.number()==70001,"rejected outside-frame write leaves final field unchanged");
            check(AllWidths.victim.number()==13,"logical constructor frame cannot write a different record instance");AllWidths.victim=null;
            try {
                MemberForge.field(Logical.class,Foreign.class.getDeclaredField("value"));
                throw new AssertionError("ordinary record field factory accepted foreign metadata");
            }catch(IllegalArgumentException expected){checks++;}
            LookupCapture.trust(Probe.class);LookupCapture.seal();
            try {Outside.forge();throw new AssertionError("untrusted record field factory accepted");}
            catch(SecurityException expected){checks++;}
            System.out.println("COMPONENT_METADATA_OK checks="+checks+" runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));
        }
    }
}
