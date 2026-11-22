/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import com.onurkat.reclazz.bootstrap.LookupCapture;import com.onurkat.reclazz.reload.RecordCallerCompanion;import com.onurkat.reclazz.transform.TransformContext;import java.lang.invoke.*;import java.nio.file.Path;import java.util.*;import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
class RecordCallerKindTest {
 @TempDir Path tmp;
 @Test void unchangedUnsupportedCallerKindsHaveAnExplicitRestartReason()throws Exception{var h=new RecordComponentMetadataTest();h.tmp=tmp;h.runProbe(Probe.class,"RECORD_CALLER_KIND_OK");}
 public abstract static class AbstractCaller{}public interface InterfaceCaller{}public enum EnumCaller{ONE}public record RecordCaller(int value){}
 public static class Probe{
  public static void main(String[]args)throws Exception{LookupCapture.trust(Probe.class);LookupCapture.trust(RecordCallerCompanion.class);LookupCapture.seal();int checks=0;
   for(Class<?> kind:List.of(AbstractCaller.class,InterfaceCaller.class,EnumCaller.class,RecordCaller.class)){
    byte[] raw;try(var in=kind.getResourceAsStream("/"+kind.getName().replace('.','/')+".class")){raw=in.readAllBytes();}
    try{RecordCallerCompanion.prepare(kind,MethodHandles.privateLookupIn(kind,MethodHandles.lookup()),raw,new TransformContext(),1);throw new AssertionError("unsupported kind admitted");}
    catch(IllegalArgumentException expected){if(!expected.getMessage().contains("not supported yet; restart to apply")||expected.getMessage().contains("kind changed"))throw new AssertionError(expected);checks++;}
   }
   System.out.println("RECORD_CALLER_KIND_OK checks="+checks+" runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));
  }
 }
}
