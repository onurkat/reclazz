/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.bootstrap.*;
import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.reload.*;
import com.onurkat.reclazz.transform.*;
import com.onurkat.reclazz.ui.Failures;
import java.lang.annotation.*;
import java.lang.instrument.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.security.ProtectionDomain;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

/** Private disposable record/caller readback. Neither a VM flag nor a no-op native API is proof. */
public final class RecordStockSelfCheck {
    private static final StackWalker WALKER=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private RecordStockSelfCheck() { }
    public record Result(boolean usable,String reason) { }
    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE,ElementType.RECORD_COMPONENT})
    public @interface Tag {String value();}
    @Tag("before") public record Before<T>(@Tag("old") T value) { }
    @Tag("after") public record After<U>(@Tag("new") U value,@Tag("added") String extra) { }
    public static final class BeforeCaller {
        public Before<?> record;
        public BeforeCaller(){record=new Before<>("before");}
        public static Before<?> create(Object value){return new Before<>(value);}
    }
    public static final class AfterCaller {
        public After<?> record;
        public AfterCaller(){record=new After<>("after","fresh");}
        public static After<?> create(Object value){return new After<>(value,"fresh");}
    }
    public static Result run(Instrumentation instrumentation) {
        LookupCapture.requireTrustedCaller(WALKER.getCallerClass(),"checking stock record capability");
        if(instrumentation==null||!instrumentation.isRedefineClassesSupported())return new Result(false,"record redefinition is unavailable");
        if(!MethodForge.isAvailable()||!ReflectionMethodTransformer.recordHooksAvailable())return new Result(false,"record reflection guards are unavailable");
        try {
            String stem="com/example/reclazzprobe/Stock"+Long.toUnsignedString(System.nanoTime());
            String recordName=stem+"Record",callerName=stem+"Caller";
            Map<String,String> oldNames=Map.of(internal(Before.class),recordName,internal(BeforeCaller.class),callerName);
            Map<String,String> nextNames=Map.of(internal(After.class),recordName,internal(AfterCaller.class),callerName);
            Map<String,byte[]> before=Map.of(recordName,renamed(Before.class,oldNames),callerName,renamed(BeforeCaller.class,oldNames));
            Map<String,byte[]> incoming=Map.of(recordName.replace('/','.'),renamed(After.class,nextNames),callerName.replace('/','.'),renamed(AfterCaller.class,nextNames));
            var context=new TransformContext();context.addWatched(recordName);context.addWatched(callerName);
            var transformer=new ReclazzTransformer(context,AgentConfig.parse(null));
            class Loader extends ClassLoader {
                Loader(){super(RecordStockSelfCheck.class.getClassLoader());}
                @Override protected Class<?> findClass(String name)throws ClassNotFoundException {
                    String internal=name.replace('.','/');byte[] raw=before.get(internal);if(raw==null)throw new ClassNotFoundException(name);
                    try{byte[] changed=transformer.transform(this,internal,null,null,raw);if(changed!=null)raw=changed;}catch(Exception failure){throw new ClassNotFoundException(name,failure);}
                    return defineClass(name,raw,0,raw.length);
                }
            }
            var loader=new Loader();Class<?> record=loader.loadClass(recordName.replace('/','.')),caller=loader.loadClass(callerName.replace('/','.'));
            capture(record);capture(caller);
            Constructor<?> held=record.getDeclaredConstructor(Object.class),heldCaller=caller.getDeclaredConstructor();
            Method creator=caller.getDeclaredMethod("create",Object.class);Object old=held.newInstance(7);
            require(record.getRecordComponents().length==1&&record.getDeclaredAnnotation(Tag.class).value().equals("before"),"initial record metadata");
            ClassFileTransformer observer=new ClassFileTransformer(){@Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] raw)throws IllegalClassFormatException {return type==record||type==caller?transformer.transform(loader,name,type,domain,raw):null;}};
            instrumentation.addTransformer(observer,true);
            try(var batch=RecordStockBatch.prepare(incoming,new Class<?>[]{record,caller},name->context.isWatched(name.replace('.','/')),instrumentation,context,1,Set.of())) {
                require(batch.active()&&batch.refusals().isEmpty(),"private cohort preflight: "+batch.refusals());
                require(record.getRecordComponents().length==1&&record.getDeclaredAnnotation(Tag.class).value().equals("before"),"pending record metadata leaked");
                var outcome=batch.commit(change->{});require(outcome.attempted()&&outcome.committed()&&outcome.repairFailures().isEmpty(),"private cohort admission: "+outcome);
                require(PhysicalDefinitions.get(record)!=null&&PhysicalDefinitions.get(caller)!=null,"native admission had no confirmed transformer readback");
                var components=record.getRecordComponents();
                require(components.length==2&&components[0].getName().equals("value")&&components[1].getName().equals("extra"),"logical record component readback");
                require(record.getDeclaredAnnotation(Tag.class).value().equals("after")&&components[0].getDeclaredAnnotation(Tag.class).value().equals("new"),"record annotation readback");
                var variable=record.getTypeParameters()[0];require(variable.getName().equals("U")&&variable.getGenericDeclaration()==record&&components[0].getGenericType().equals(variable),"record generic scope readback");
                Object fresh=record.getDeclaredConstructor(Object.class,String.class).newInstance(7,"fresh");
                Object equal=creator.invoke(null,7);
                require(fresh.getClass()==record&&fresh.equals(equal)&&fresh.hashCode()==equal.hashCode()&&fresh.toString().contains("extra=fresh"),"record construction/ObjectMethods readback");
                require(components[0].getAccessor().invoke(old).equals(7)&&components[1].getAccessor().invoke(old)==null,"old record compatible values/defaults");
                Object receiver=heldCaller.newInstance();Object created=caller.getDeclaredField("record").get(receiver);
                require(created.getClass()==record&&components[0].getAccessor().invoke(created).equals("after")&&components[1].getAccessor().invoke(created).equals("fresh"),"held caller constructor/canonical linkage readback");
                try{held.newInstance(8);throw new IllegalStateException("retired physical record canonical constructor remains active");}
                catch(InvocationTargetException retired){require(retired.getCause() instanceof NoSuchMethodError,"retired canonical error type");}
            }finally{instrumentation.removeTransformer(observer);}
            return new Result(true,"private actual native/CAS record and caller construction, reflection/generics/annotations/ObjectMethods read back");
        }catch(Throwable failure){if(failure instanceof VirtualMachineError fatal)throw fatal;return new Result(false,Failures.describe(failure));}
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private static String internal(Class<?> type){return type.getName().replace('.','/');}
    private static void capture(Class<?> owner)throws IllegalAccessException {
        if(LookupCapture.get(owner)!=null)return;
        for(Field field:ReflectionBridge.physicalDeclaredFields(owner))if(field.getName().equals(InjectedNames.LOOKUP_FIELD)) {
            field.setAccessible(true);LookupCapture.store(owner,(MethodHandles.Lookup)field.get(null));return;
        }
        throw new IllegalStateException("private probe lookup missing");
    }
    private static byte[] renamed(Class<?> source,Map<String,String> names)throws java.io.IOException {
        byte[] raw;try(var in=source.getResourceAsStream("/"+internal(source)+".class")){if(in==null)throw new java.io.IOException("private probe class resource missing");raw=in.readAllBytes();}
        var writer=new ClassWriter(0);var remap=new ClassRemapper(writer,new SimpleRemapper(Opcodes.ASM9,names));
        new ClassReader(raw).accept(new ClassVisitor(Opcodes.ASM9,remap) {
            @Override public void visitNestHost(String host) { }
            @Override public void visitNestMember(String member) { }
            @Override public void visitOuterClass(String owner,String name,String descriptor) { }
            @Override public void visitInnerClass(String name,String outer,String inner,int access) { }
        },0);
        return writer.toByteArray();
    }
}
