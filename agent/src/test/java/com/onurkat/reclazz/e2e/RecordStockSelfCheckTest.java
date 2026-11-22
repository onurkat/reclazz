/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.agent.RecordStockSelfCheck;
import com.onurkat.reclazz.bootstrap.*;
import com.onurkat.reclazz.reload.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordStockSelfCheckTest {
    @TempDir Path tmp;
    private void probe(Class<?> type,String mode)throws Exception {var h=new RecordComponentMetadataTest();h.tmp=tmp;h.runProbe(type,"RECORD_STOCK_SELF_CHECK_OK mode="+mode);}
    @Test void actualPrivateRecordCallerAndNativeReadbackAreRequired()throws Exception {probe(Actual.class,"actual");}
    @Test void noExceptionFromANoopNativeApiIsNotCapability()throws Exception {probe(Noop.class,"noop");}
    @Test void missingReflectionGuardsRefuseWithoutNativeEntry()throws Exception {probe(Unavailable.class,"unavailable");}
    @Test void privateProbeErrorProducesARefusalResult()throws Exception{probe(Error.class,"error");}
    @Test void virtualMachineErrorPropagates()throws Exception{probe(VmError.class,"vme");}
    public static class Error {public static void main(String[]args)throws Throwable{Probe.run("error");}}
    public static class VmError {public static void main(String[]args)throws Throwable{Probe.run("vme");}}
    public static class Actual {public static void main(String[] args)throws Throwable {Probe.run("actual");}}
    public static class Noop {public static void main(String[] args)throws Throwable {Probe.run("noop");}}
    public static class Unavailable {public static void main(String[] args)throws Throwable {Probe.run("unavailable");}}
    public static class Probe {
        static void run(String mode)throws Throwable {
            LookupCapture.trust(Probe.class);LookupCapture.trust(RecordStockSelfCheck.class);LookupCapture.trust(RecordStockBatch.class);LookupCapture.trust(RecordCompanion.class);LookupCapture.trust(RecordCallerCompanion.class);LookupCapture.trust(RecordMemberSnapshot.class);LookupCapture.trust(ProtectedCallResolver.class);LookupCapture.seal();
            var actual=RecordComponentMetadataTest.ProbeAgent.instrumentation;int[] calls={0};
            var instrumentation=(Instrumentation)Proxy.newProxyInstance(Instrumentation.class.getClassLoader(),new Class<?>[]{Instrumentation.class},(proxy,method,args)->{
                if(method.getName().equals("redefineClasses")){calls[0]++;if(mode.equals("noop"))return null;if(mode.equals("error"))throw new AssertionError("synthetic private probe Error");if(mode.equals("vme"))throw new InternalError("synthetic probe VM error");}
                try{return method.invoke(actual,args);}catch(InvocationTargetException failed){throw failed.getCause();}
            });
            if(mode.equals("unavailable"))MethodForge.disable();
            if(mode.equals("vme")){
                try{RecordStockSelfCheck.run(instrumentation);throw new AssertionError("VM error swallowed");}
                catch(InternalError fatal){if(!fatal.getMessage().equals("synthetic probe VM error"))throw fatal;}
                if(calls[0]!=1)throw new AssertionError("VM error boundary");
                System.out.println("RECORD_STOCK_SELF_CHECK_OK mode=vme checks=2 runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));return;
            }
            var result=RecordStockSelfCheck.run(instrumentation);
            if(result.usable()!=mode.equals("actual"))throw new AssertionError("self-check result: "+result);
            if(calls[0]!=(mode.equals("unavailable")?0:1))throw new AssertionError("actual native call count: "+calls[0]);
            if(mode.equals("error")&&!result.reason().contains("synthetic private probe Error"))throw new AssertionError(result.reason());
            if(mode.equals("noop")&&!result.reason().contains("no confirmed transformer readback"))throw new AssertionError(result.reason());
            if(mode.equals("unavailable")&&!result.reason().contains("reflection guards are unavailable"))throw new AssertionError(result.reason());
            System.out.println("RECORD_STOCK_SELF_CHECK_OK mode="+mode+" checks="+(mode.equals("actual")?2:3)+" runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));
        }
    }
}
