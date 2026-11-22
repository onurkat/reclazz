/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.bootstrap.*;
import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.reload.*;
import com.onurkat.reclazz.transform.*;
import java.io.*;
import java.lang.instrument.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Constructor method handles inside indy must use the same logical factories as NEW/DUP. */
class RecordConstructorReferenceTest {
    @TempDir Path tmp;
    @Test void retainedAuxiliaryReferencesAndSerializationFollowReload()throws Exception {var harness=new RecordComponentMetadataTest();harness.tmp=tmp;harness.runProbe(AuxiliaryProbe.class,"RECORD_CTOR_REFERENCE_OK mode=auxiliary");}
    @Test void aNewCanonicalReferenceLinksWithoutAPhysicalConstructor()throws Exception {var harness=new RecordComponentMetadataTest();harness.tmp=tmp;harness.runProbe(CanonicalProbe.class,"RECORD_CTOR_REFERENCE_OK mode=canonical");}
    public static class AuxiliaryProbe extends Probe {public static void main(String[] args)throws Throwable{run(false);}}
    public static class CanonicalProbe extends Probe {public static void main(String[] args)throws Throwable{run(true);}}
    public static class Probe extends TransformTestBase {
        static int checks;
        static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
        static SourceFile source(boolean next) {
            return new SourceFile("com.example.RefRecord","""
                package com.example;
                import java.util.function.*;import java.io.Serializable;
                public record RefRecord(%s) {
                    public RefRecord(String text){%s}
                    public RefRecord(int... values){%s}
                    public static Supplier<RefRecord> varargs(){return RefRecord::new;}
                    public static Supplier<RefRecord> serialVarargs(){return (Supplier<RefRecord>&Serializable)RefRecord::new;}
                    public interface Named extends Function<String,RefRecord> {default String label(){return "default-method";}}
                    public static Function<String,RefRecord> auxiliary(){return RefRecord::new;}
                    public static Function<String,RefRecord> serialAuxiliary(){return (Function<String,RefRecord>&Serializable&Cloneable)RefRecord::new;}
                    public static Named named(){return RefRecord::new;}
                    public static Supplier<Ordinary> normal(){return Ordinary::new;}
                    public static Supplier<Ordinary> serialNormal(){return (Supplier<Ordinary>&Serializable)Ordinary::new;}
                    public static Object canonical(){return %s;}
                }
                """.formatted(next?"int value,String added":"int value",next?"this(Integer.parseInt(text)+100,\"aux\");":"this(Integer.parseInt(text));",next?"this(values.length+200,\"varargs\");":"this(values.length);",next?"(BiFunction<Integer,String,RefRecord>)RefRecord::new":"(Function<Integer,RefRecord>)RefRecord::new"));
        }
        static Object roundTrip(Object value,ClassLoader loader)throws Exception {
            var bytes=new ByteArrayOutputStream();try(var out=new ObjectOutputStream(bytes)){out.writeObject(value);}
            try(var in=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())){@Override protected Class<?> resolveClass(ObjectStreamClass description)throws IOException,ClassNotFoundException {try{return Class.forName(description.getName(),false,loader);}catch(ClassNotFoundException absent){return super.resolveClass(description);}}}){return in.readObject();}
        }
        static void serializedIdentity(Object value,String capturing,String owner,String descriptor)throws Exception {
            Method write=value.getClass().getDeclaredMethod("writeReplace");write.setAccessible(true);var lambda=(SerializedLambda)write.invoke(value);
            check(lambda.getCapturingClass().equals(capturing)&&lambda.getImplClass().equals(owner)&&lambda.getImplMethodName().equals("<init>")&&lambda.getImplMethodKind()==MethodHandleInfo.REF_newInvokeSpecial&&lambda.getImplMethodSignature().equals(descriptor),"constructor reference preserves javac SerializedLambda identity");
        }
        @SuppressWarnings("unchecked") static void run(boolean canonicalFirst)throws Throwable {
            String name="com/example/RefRecord";
            var ordinary=new SourceFile("com.example.Ordinary","package com.example; public class Ordinary {static {Initialization.count++;} public int value=17;public Ordinary() { }public Ordinary(UnusedConstructorType ignored) { }public static Ordinary poison(Ordinary receiver){receiver.value=999;return receiver;}}");
            var initialization=new SourceFile("com.example.Initialization","package com.example; public class Initialization {public static int count;}");
            var unused=new SourceFile("com.example.UnusedConstructorType","package com.example; public class UnusedConstructorType {}");
            var original=new LinkedHashMap<>(compile(source(false),ordinary,initialization,unused));original.remove("com/example/UnusedConstructorType");
            byte[] incoming=compile(source(true),ordinary,initialization,unused).get(name);
            var context=new TransformContext();context.addWatched(name);context.addWatched("com/example/Ordinary");
            var transformer=new ReclazzTransformer(context,AgentConfig.parse(null));
            class Loader extends ClassLoader {
                Loader(){super(Probe.class.getClassLoader());}
                @Override protected Class<?> findClass(String binary)throws ClassNotFoundException {
                    byte[] raw=original.get(binary.replace('.','/'));if(raw==null)throw new ClassNotFoundException(binary);
                    try {byte[] changed=transformer.transform(this,binary.replace('.','/'),null,null,raw);if(changed!=null)raw=changed;}
                    catch(Exception failure){throw new ClassNotFoundException(binary,failure);}
                    return defineClass(binary,raw,0,raw.length);
                }
            }
            var loader=new Loader();Class<?> owner=loader.loadClass(name.replace('/','.'));
            var lookupField=owner.getDeclaredField(InjectedNames.LOOKUP_FIELD);lookupField.setAccessible(true);var lookup=(MethodHandles.Lookup)lookupField.get(null);
            var auxiliary=(Function<Object,Object>)owner.getDeclaredMethod("auxiliary").invoke(null);
            var serial=(Function<Object,Object>)owner.getDeclaredMethod("serialAuxiliary").invoke(null);
            var named=(Function<Object,Object>)owner.getDeclaredMethod("named").invoke(null);
            var varargs=(Supplier<Object>)owner.getDeclaredMethod("varargs").invoke(null);
            var serialVarargs=(Supplier<Object>)owner.getDeclaredMethod("serialVarargs").invoke(null);
            var oldCanonical=(Function<Object,Object>)owner.getDeclaredMethod("canonical").invoke(null);
            var normal=(Supplier<Object>)owner.getDeclaredMethod("normal").invoke(null);
            var serialNormal=(Supplier<Object>)owner.getDeclaredMethod("serialNormal").invoke(null);
            Class<?> initialized=loader.loadClass("com.example.Initialization");
            check(initialized.getDeclaredField("count").getInt(null)==0,"constructor reference linking must not initialize the target class");
            check(!(auxiliary instanceof Serializable)&&serial instanceof Serializable&&serial instanceof Cloneable,"record ctor reference serializable/marker flags");
            check(!(normal instanceof Serializable)&&serialNormal instanceof Serializable&&!Proxy.isProxyClass(normal.getClass())&&normal.getClass().isHidden(),"ordinary references retain concrete hidden lambda semantics");
            check(owner.getDeclaredMethod("value").invoke(auxiliary.apply("2")).equals(2),"old auxiliary reference executes");
            serializedIdentity(serial,name,name,"(Ljava/lang/String;)V");serializedIdentity(serialNormal,name,"com/example/Ordinary","()V");
            check(owner.getDeclaredMethod("value").invoke(((Function<Object,Object>)roundTrip(serial,loader)).apply("3")).equals(3),"serial record ctor reference round trip before reload");
            check(owner.getDeclaredMethod("value").invoke(varargs.get()).equals(0),"old varargs constructor reference executes");
            check(owner.getDeclaredMethod("value").invoke(((Supplier<Object>)roundTrip(serialVarargs,loader)).get()).equals(0),"serial varargs round trip before reload");
            Object plain=((Supplier<Object>)roundTrip(serialNormal,loader)).get();check(plain.getClass().getField("value").getInt(plain)==17,"serial ordinary ctor reference round trip before reload");
            LookupCapture.trust(Probe.class);LookupCapture.trust(AuxiliaryProbe.class);LookupCapture.trust(CanonicalProbe.class);LookupCapture.trust(RecordCompanion.class);LookupCapture.trust(RecordMemberSnapshot.class);LookupCapture.trust(ProtectedCallResolver.class);LookupCapture.store(owner,lookup);LookupCapture.seal();
            Class<?> plainOwner=loader.loadClass("com.example.Ordinary");
            var poison=MethodHandles.publicLookup().findStatic(plainOwner,"poison",MethodType.methodType(plainOwner,plainOwner));
            MemberForge.initializer(plainOwner,"()V",poison);
            check(((Supplier<Object>)normal).get().getClass().getField("value").getInt(normal.get())==17,"ordinary constructor reference uses physical native fallback, never the unadmitted initializer map");
            check(initialized.getDeclaredField("count").getInt(null)==1,"ordinary class initialization occurs exactly on first invocation");
            var prepared=RecordCompanion.prepare(owner,lookup,incoming,context,1);
            var instrumentation=RecordComponentMetadataTest.ProbeAgent.instrumentation;check(instrumentation.isRedefineClassesSupported(),"actual child native capability");
            ClassFileTransformer observer=new ClassFileTransformer(){@Override public byte[] transform(ClassLoader loader,String internal,Class<?> type,ProtectionDomain domain,byte[] raw)throws IllegalClassFormatException{return type==owner?transformer.transform(loader,internal,type,domain,raw):null;}};
            instrumentation.addTransformer(observer,true);
            try(var pending=RecordSchema.slot(owner).prepareVirtual(prepared.snapshot().members())) {
                pending.stageDispatch(owner,prepared.snapshot().targets());PhysicalDefinitions.redefine(instrumentation,new ClassDefinition(owner,PhysicalDefinitions.get(owner)));check(pending.commit(),"real physical admission then logical constructor activation");
            }finally{instrumentation.removeTransformer(observer);}
            if(canonicalFirst) {
                var canonical=(BiFunction<Object,Object,Object>)owner.getDeclaredMethod("canonical").invoke(null);Object value=canonical.apply(8,"fresh");
                check(owner.getDeclaredMethod("value").invoke(value).equals(8)&&owner.getDeclaredMethod("added").invoke(value).equals("fresh"),"new canonical constructor method reference links without a physical descriptor");
            } else {
                check(owner.getDeclaredMethod("value").invoke(varargs.get()).equals(200),"retained varargs reference follows logical body");
                check(owner.getDeclaredMethod("value").invoke(((Supplier<Object>)roundTrip(serialVarargs,loader)).get()).equals(200),"retained serial varargs round trip after reload");
                var freshVarargs=owner.getDeclaredMethod("serialVarargs").invoke(null);
                check(owner.getDeclaredMethod("value").invoke(((Supplier<Object>)roundTrip(freshVarargs,loader)).get()).equals(200),"new companion serial varargs round trip after reload");
                Object value=auxiliary.apply("4");check(owner.getDeclaredMethod("value").invoke(value).equals(104)&&owner.getDeclaredMethod("added").invoke(value).equals("aux"),"retained auxiliary constructor reference follows committed logical body");
                check(owner.getDeclaredMethod("value").invoke(named.apply("5")).equals(105),"specialized record SAM reaches logical body");
                check(named.getClass().getMethod("label").invoke(named).equals("default-method"),"record SAM default method");
                check(owner.getDeclaredMethod("value").invoke(((Function<Object,Object>)roundTrip(serial,loader)).apply("6")).equals(106),"retained serial record reference round trip after reload");
                var newSerial=owner.getDeclaredMethod("serialAuxiliary").invoke(null);serializedIdentity(newSerial,name,name,"(Ljava/lang/String;)V");
                check(owner.getDeclaredMethod("value").invoke(((Function<Object,Object>)roundTrip(newSerial,loader)).apply("7")).equals(107),"new companion serial record reference uses a resolvable capturing class");
                var newPlain=owner.getDeclaredMethod("serialNormal").invoke(null);serializedIdentity(newPlain,name,"com/example/Ordinary","()V");
                check(((Supplier<Object>)roundTrip(newPlain,loader)).get().getClass().getField("value").getInt(((Supplier<Object>)newPlain).get())==17,"ordinary serial reference still round trips from companion code");
                try {oldCanonical.apply(9);throw new AssertionError("retained reference silently invokes a retired physical constructor");}catch(NoSuchMethodError expected){checks++;}
            }
            System.out.println("RECORD_CTOR_REFERENCE_OK mode="+(canonicalFirst?"canonical":"auxiliary")+" checks="+checks+" runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));
        }
    }
}
