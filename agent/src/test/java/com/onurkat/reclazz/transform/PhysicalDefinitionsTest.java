/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;

import java.lang.instrument.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

class PhysicalDefinitionsTest {
    static class Loader extends ClassLoader {Class<?> define(byte[] bytes){return defineClass("com.example.Payload",bytes,0,bytes.length);}}
    static byte[] bytes(int number) {
        var writer=new ClassWriter(0);writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"com/example/Payload",null,"java/lang/Object",null);
        var method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"value","()I",null,null);
        method.visitCode();method.visitLdcInsn(number);method.visitInsn(Opcodes.IRETURN);method.visitMaxs(1,0);method.visitEnd();writer.visitEnd();return writer.toByteArray();
    }
    static Class<?> captured(int number) {
        var loader=new Loader();byte[] raw=bytes(number);PhysicalDefinitions.defined(loader,"com/example/Payload",raw);return loader.define(raw);
    }
    interface NativeCall {void run(ClassDefinition[] definitions)throws Throwable;}
    static Instrumentation instrumentation(NativeCall call) {
        return (Instrumentation)Proxy.newProxyInstance(PhysicalDefinitionsTest.class.getClassLoader(),new Class<?>[]{Instrumentation.class},(proxy,method,args)->{
            if(method.getName().equals("redefineClasses")){call.run((ClassDefinition[])args[0]);return null;}
            throw new AssertionError("Unexpected instrumentation call: "+method.getName());
        });
    }
    @Test void loaderSpecificPayloadsAndDefensiveCopies() {
        Class<?> one=captured(1),two=captured(2);
        assertArrayEquals(bytes(1),PhysicalDefinitions.get(one));assertArrayEquals(bytes(2),PhysicalDefinitions.get(two));
        byte[] exposed=PhysicalDefinitions.get(one);Arrays.fill(exposed,(byte)0);assertArrayEquals(bytes(1),PhysicalDefinitions.get(one));
        var loader=new Loader();byte[] source=bytes(3);PhysicalDefinitions.defined(loader,"com/example/Payload",source);Class<?> three=loader.define(source);
        Arrays.fill(source,(byte)0);assertArrayEquals(bytes(3),PhysicalDefinitions.get(three));
    }
    @Test void repeatedDefinitionAttemptInvalidatesEvenAnAlreadyReadInitialPayload() {
        Class<?> owner=captured(1);assertNotNull(PhysicalDefinitions.get(owner));
        PhysicalDefinitions.defined(owner.getClassLoader(),"com/example/Payload",bytes(2));assertNull(PhysicalDefinitions.get(owner));
    }
    @Test void successfulNativeReturnCommitsTheObservedPayload()throws Exception {
        Class<?> owner=captured(1);byte[] next=bytes(2);
        var nativeCall=instrumentation(definitions->{PhysicalDefinitions.emitted(owner,next);assertArrayEquals(bytes(1),PhysicalDefinitions.get(owner),"observation is not a commit");});
        PhysicalDefinitions.redefine(nativeCall,new ClassDefinition(owner,next));assertArrayEquals(next,PhysicalDefinitions.get(owner));
    }
    @Test void failedNativeReturnRetainsTheOldPayload() {
        Class<?> owner=captured(1);
        var nativeCall=instrumentation(definitions->{PhysicalDefinitions.emitted(owner,bytes(2));throw new UnmodifiableClassException("deliberate refusal");});
        assertThrows(UnmodifiableClassException.class,()->PhysicalDefinitions.redefine(nativeCall,new ClassDefinition(owner,bytes(2))));
        assertArrayEquals(bytes(1),PhysicalDefinitions.get(owner));
    }
    @Test void anUnobservedNativeSuccessCannotGuessThePayload()throws Exception {
        Class<?> owner=captured(1);PhysicalDefinitions.redefine(instrumentation(definitions->{}),new ClassDefinition(owner,bytes(2)));
        assertNull(PhysicalDefinitions.get(owner));
    }
    @Test void anObservationOutsideOurNativeOperationMakesThePayloadUnknown() {
        Class<?> owner=captured(1);PhysicalDefinitions.emitted(owner,bytes(2));assertNull(PhysicalDefinitions.get(owner));
    }
    @Test void newerUnobservedNativeCommitInvalidatesAnOlderConfirmation()throws Exception {
        Class<?> owner=captured(1);
        var first=instrumentation(definitions->{
            PhysicalDefinitions.emitted(owner,bytes(2));
            var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread other=new Thread(()->{try{PhysicalDefinitions.redefine(instrumentation(ignored->{}),new ClassDefinition(owner,bytes(3)));}catch(Throwable problem){failure.set(problem);}});
            other.start();other.join(5000);assertFalse(other.isAlive());if(failure.get()!=null)throw failure.get();
        });
        PhysicalDefinitions.redefine(first,new ClassDefinition(owner,bytes(2)));
        assertTrue(PhysicalDefinitions.get(owner)==null,"a later unknown native operation must not be overwritten by an earlier callback");
    }
    @Test void theEvictableNameCacheCannotSupplyOrRemovePhysicalProof() {
        Class<?> owner=captured(1);TransformedClassCache.put("com/example/Payload",bytes(2));
        byte[] oversized=new byte[9*1024*1024];new Random(1521).nextBytes(oversized);
        try {
            TransformedClassCache.put("com/example/Unrelated",oversized);
            assertNull(TransformedClassCache.get("com/example/Payload"));
            assertArrayEquals(bytes(1),PhysicalDefinitions.get(owner));
        }finally{TransformedClassCache.put("com/example/Unrelated",new byte[0]);}
        assertNull(PhysicalDefinitions.get(new Loader().define(bytes(1))),"same name or LRU history cannot invent witnessed definition bytes");
    }
    static WeakReference<ClassLoader> disposable() {
        Class<?> owner=captured(7);assertNotNull(PhysicalDefinitions.get(owner));return new WeakReference<>(owner.getClassLoader());
    }
    @Test void payloadBytesDoNotRetainTheirDefiningLoader()throws Exception {
        var reference=disposable();for(int count=0;count<80&&reference.get()!=null;count++){System.gc();Thread.sleep(20);}
        assertNull(reference.get());assertNull(PhysicalDefinitions.get(null));assertNull(PhysicalDefinitions.get(String.class));
    }
}
