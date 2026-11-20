/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecordCapabilityProbeTest {
    private static Instrumentation instrumentation(AtomicInteger redefinitions,boolean reject) {
        return (Instrumentation)Proxy.newProxyInstance(RecordCapabilityProbeTest.class.getClassLoader(),new Class<?>[]{Instrumentation.class},(proxy,method,args)->{
            if(method.getName().equals("isRedefineClassesSupported")||method.getName().equals("isModifiableClass"))return true;
            if(method.getName().equals("redefineClasses")){redefinitions.incrementAndGet();if(reject)throw new UnsupportedOperationException("record change refused");return null;}
            throw new AssertionError("unexpected probe API: "+method.getName());
        });
    }
    @Test void flagWithoutActualCapabilityRefuses() {
        var claimed=new JvmCapabilityProbe.ProbeResult(JvmCapabilityProbe.CapabilityLevel.ENHANCED_REDEFINITION,"synthetic enhanced VM","jvm-flag");
        assertTrue(claimed.hasEnhancedRedefinition());var calls=new AtomicInteger();
        var record=JvmCapabilityProbe.probeRecordSchema(instrumentation(calls,true));
        assertFalse(record.nativeSchema());assertEquals(1,calls.get());assertTrue(record.reason().contains("record change refused"));
    }
    @Test void noExceptionWithoutChangedReflectionIsNotProof() {
        var calls=new AtomicInteger();var result=JvmCapabilityProbe.probeRecordSchema(instrumentation(calls,false));
        assertFalse(result.nativeSchema());assertEquals(1,calls.get());assertTrue(result.reason().contains("component count"));
    }
    @Test void generatedProbeConstructorsAndAccessorsAreExecutableForEveryShape()throws Exception {
        var method=JvmCapabilityProbe.class.getDeclaredMethod("recordProbeClass",String.class,List.class);method.setAccessible(true);
        var verify=JvmCapabilityProbe.class.getDeclaredMethod("verifyRecordProbe",Class.class,List.class);verify.setAccessible(true);
        for(var shape:List.of(List.of("a:I","b:Ljava/lang/String;"),List.of("a:I","b:Ljava/lang/String;","c:I"),
                List.of("b:Ljava/lang/String;","c:I"),List.of("b:I","c:I"),List.of("c:I","b:I"))) {
            byte[] bytes=(byte[])method.invoke(null,"com/example/PrivateRecordProbe",shape);
            class Loader extends ClassLoader {Class<?> define(){return defineClass("com.example.PrivateRecordProbe",bytes,0,bytes.length);}}
            assertDoesNotThrow(()->verify.invoke(null,new Loader().define(),shape));
        }
    }
    @Test void missingInstrumentationRefusesWithoutGuessing(){assertFalse(JvmCapabilityProbe.probeRecordSchema(null).nativeSchema());}
}
