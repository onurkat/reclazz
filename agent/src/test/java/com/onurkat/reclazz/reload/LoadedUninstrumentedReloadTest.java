/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.transform.TransformContext;
import com.onurkat.reclazz.transform.TransformTestBase;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LoadedUninstrumentedReloadTest extends TransformTestBase {
    private static final AtomicInteger names=new AtomicInteger();
    private void edit(String first,String next,boolean accepted)throws Exception {
        String name="LoadedRaw"+names.incrementAndGet();
        byte[] original=compile(new SourceFile(name,first.replace("TYPE",name))).get(name);
        byte[] replacement=compile(new SourceFile(name,next.replace("TYPE",name))).get(name);
        Class<?> owner=defineAndLoad(Map.of(name,original),name);Object held=owner.getConstructor().newInstance();
        assertEquals(1,owner.getMethod("value").invoke(held));
        var real=ByteBuddyAgent.install();var calls=new AtomicInteger();
        Instrumentation counted=(Instrumentation)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Instrumentation.class},(proxy,method,args)->{
            if(method.getName().equals("redefineClasses"))calls.incrementAndGet();
            try{return method.invoke(real,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        });
        var context=new TransformContext();context.addWatched(name);context.markSeen(name);
        var reloader=new StructuralReloader(counted,context,AgentConfig.parse(null),null);
        ClassLoader previous=Thread.currentThread().getContextClassLoader();Thread.currentThread().setContextClassLoader(owner.getClassLoader());
        try {
            var result=reloader.reload(name,replacement);
            assertEquals(accepted,result.isSuccess(),String.valueOf(result.getError()));
            assertEquals(accepted?1:0,calls.get(),"structural changes must refuse before native redefine");
            assertEquals(accepted?2:1,owner.getMethod("value").invoke(held));
            assertNull(context.getMetadata(name),"never claim instrumentation for the raw loaded definition");
        } finally {Thread.currentThread().setContextClassLoader(previous);com.onurkat.reclazz.ui.RestartLedger.clear();}
    }
    @Test void addedMethodRefusesBeforeNativeRedefine()throws Exception {
        edit("public class TYPE {public int value(){return 1;}}","public class TYPE {public int value(){return 2;} public int added(){return 3;}}",false);
    }
    @Test void addedClassInitializerRefusesBeforeNativeRedefine()throws Exception {
        edit("public class TYPE {public int value(){return 1;}}","public class TYPE {static {System.getProperty(\"java.version\");} public int value(){return 2;}}",false);
    }
    @Test void bodyOnlyWithExistingClassInitializerStillReloads()throws Exception {
        edit("public class TYPE {static int state=1; public int value(){return state;}}","public class TYPE {static int state=99; public int value(){return state+1;}}",true);
    }
    private final class RawFixture implements AutoCloseable {
        final String name="LoadedRaw"+names.incrementAndGet();
        final String source="public class TYPE {static int state=1; public int value(){return state%s;}}";
        final byte[] original=bytes("");
        final Class<?> owner;final Object held;
        final Instrumentation real=ByteBuddyAgent.install();
        final AtomicInteger captures=new AtomicInteger(),redefines=new AtomicInteger(),adds=new AtomicInteger(),removes=new AtomicInteger();
        final AtomicInteger censuses = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean failCapture=new java.util.concurrent.atomic.AtomicBoolean();
        final TransformContext context=new TransformContext();
        final com.onurkat.reclazz.transform.ReclazzTransformer transformer=new com.onurkat.reclazz.transform.ReclazzTransformer(context,AgentConfig.parse(null));
        final Instrumentation counted;final StructuralReloader reloader;final ClassLoader previous;
        RawFixture()throws Exception {
            owner=raw(original);held=owner.getConstructor().newInstance();context.addWatched(name);context.markSeen(name);
            counted=(Instrumentation)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Instrumentation.class},(proxy,method,args)->{
                switch(method.getName()){
                    case "getAllLoadedClasses" -> censuses.incrementAndGet();
                    case "retransformClasses" -> {captures.incrementAndGet();if(failCapture.get())throw new java.lang.instrument.UnmodifiableClassException("forced capture failure");}
                    case "redefineClasses" -> redefines.incrementAndGet();
                    case "addTransformer" -> adds.incrementAndGet();
                    case "removeTransformer" -> removes.incrementAndGet();
                }
                try{return method.invoke(real,args);}catch(InvocationTargetException failure){throw failure.getCause();}
            });
            reloader=new StructuralReloader(counted,context,AgentConfig.parse(null),null);
            previous=Thread.currentThread().getContextClassLoader();Thread.currentThread().setContextClassLoader(owner.getClassLoader());
            real.addTransformer(transformer,true);
        }
        byte[] bytes(String change){return compile(new SourceFile(name,source.replace("TYPE",name).formatted(change))).get(name);}
        Class<?> raw(byte[] bytes){return new ClassLoader(LoadedUninstrumentedReloadTest.class.getClassLoader()){
            Class<?> define(){return defineClass(name,bytes,0,bytes.length);}
        }.define();}
        int value()throws Exception{return (int)owner.getMethod("value").invoke(held);}
        @Override public void close(){
            real.removeTransformer(transformer);com.onurkat.reclazz.transform.AdmittedBytes.invalidate(owner);
            Thread.currentThread().setContextClassLoader(previous);com.onurkat.reclazz.ui.RestartLedger.clear();
        }
    }
    @Test void secondBodyEditUsesOnlyTerminalAdmittedSchema()throws Exception {
        try(var f=new RawFixture()){
            byte[] next=f.bytes("+1");var first=f.reloader.reload(f.name,next);assertTrue(first.isSuccess(),first.getError());
            assertEquals(2,f.value());assertEquals(1,f.captures.get());assertEquals(1,f.redefines.get());
            assertNull(com.onurkat.reclazz.transform.AdmittedBytes.trackRawSchema(f.owner),"capture alone is not admission");
            // The real agent's terminal callback supplies these accepted bytes.
            com.onurkat.reclazz.transform.AdmittedBytes.admitted(f.owner,next);
            assertNotNull(com.onurkat.reclazz.transform.AdmittedBytes.trackRawSchema(f.owner));
            byte[] again=f.bytes("+2");var second=f.reloader.reload(f.name,again);assertTrue(second.isSuccess(),second.getError());
            assertEquals(3,f.value());assertEquals(1,f.captures.get(),"trusted admitted shape avoids another capture");assertEquals(2,f.redefines.get());
            assertNull(f.context.getMetadata(f.name));assertFalse(f.context.isTransformed(f.owner));assertEquals(f.adds.get(),f.removes.get());
        }
    }
    @Test void rawDefinitionDoesNotBorrowAnotherLoadersInstrumentationMetadata()throws Exception {
        try(var f=new RawFixture()){
            Class<?> foreign=new ClassLoader(getClass().getClassLoader()){
                Class<?> define()throws Exception {byte[] bytes=f.transformer.transform(this,f.name,null,null,f.original);assertNotNull(bytes);return defineClass(f.name,bytes,0,bytes.length);}
            }.define();
            Object other=foreign.getConstructor().newInstance();var metadata=f.context.getMetadata(f.name);assertNotNull(metadata);assertTrue(f.context.isTransformed(foreign));
            int beforeLookup = f.censuses.get();
            assertFalse(f.reloader.reload(f.name,f.bytes("+1")).isSuccess(), "an ambiguous raw name is insufficient");
            assertEquals(beforeLookup, f.censuses.get(), "two visible definitions prove ambiguity without a VM census");
            var result=f.reloader.reload(f.name,f.bytes("+1"),f.held.getClass());assertTrue(result.isSuccess(),result.getError());
            assertEquals(2,f.value());assertEquals(1,foreign.getMethod("value").invoke(other));
            assertSame(metadata,f.context.getMetadata(f.name));assertFalse(f.context.isTransformed(f.owner));
            assertEquals(1,f.captures.get());assertEquals(1,f.redefines.get());
            assertFalse(Arrays.stream(f.owner.getDeclaredFields()).anyMatch(field->field.getName().startsWith("__reclazz$")));
        }
    }
    @Test void ambiguousSaveCensusRefusesBeforeCaptureOrRedefinition()throws Exception {
        try(var f=new RawFixture()){
            Class<?> other=f.raw(f.original);byte[] next=f.bytes("+1");
            SwitchMapRefresh.begin(Map.of(f.name,next),Map.of(),com.onurkat.reclazz.util.ClassLookup.loadedClasses(new Class<?>[]{f.owner,other}));
            try {
                var result=f.reloader.reload(f.name,next);assertFalse(result.isSuccess());assertTrue(result.getError().contains("multiple defining class loaders"));
                assertEquals(0,f.captures.get());assertEquals(0,f.redefines.get());assertEquals(1,f.value());
            } finally {SwitchMapRefresh.end();}
        }
    }
    @Test void failedCaptureAlwaysRemovesObserverAndExactIdentityBypass()throws Exception {
        try(var f=new RawFixture()){
            f.failCapture.set(true);
            assertThrows(java.lang.instrument.UnmodifiableClassException.class,()->com.onurkat.reclazz.transform.VmClassShape.read(f.owner,f.counted));
            assertEquals(1,f.captures.get());assertEquals(1,f.adds.get());assertEquals(1,f.removes.get());assertEquals(0,f.redefines.get());
            assertFalse(com.onurkat.reclazz.transform.VmClassShape.capturing(f.owner));assertNull(f.context.getMetadata(f.name));
            assertNull(com.onurkat.reclazz.transform.AdmittedBytes.trackRawSchema(f.owner));
        }
    }

    @Test void unavailableCaptureStillRefusesAProvenAddedMethodBeforeRedefine()throws Exception {
        try(var f=new RawFixture()){
            f.failCapture.set(true);
            byte[] next=compile(new SourceFile(f.name,"public class "+f.name+" {static int state=1;public int value(){return state+1;}public int added(){return 3;}}" )).get(f.name);
            var result=f.reloader.reload(f.name,next);assertFalse(result.isSuccess());assertEquals(0,f.redefines.get());assertEquals(1,f.value());
            assertEquals(1,f.adds.get());assertEquals(1,f.removes.get());
        }
    }
    @Test void unavailableCaptureKeepsTheAtomicJvmBodyFallback()throws Exception {
        try(var f=new RawFixture()){
            f.failCapture.set(true);byte[] next=f.bytes("+1");var result=f.reloader.reload(f.name,next);
            assertTrue(result.isSuccess(),result.getError());assertEquals(2,f.value());assertEquals(1,f.redefines.get());
            assertEquals(1,f.adds.get());assertEquals(1,f.removes.get());assertFalse(com.onurkat.reclazz.transform.VmClassShape.capturing(f.owner));
        }
    }

}
