/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;
class AdmittedBytesTest {
    static class Loader extends ClassLoader { Class<?> define(byte[] bytes){return defineClass("example.Owner",bytes,0,bytes.length);} }
    byte[] raw(){ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"example/Owner",null,"java/lang/Object",null);w.visitEnd();return w.toByteArray();}
    @BeforeEach void clear(){AdmittedBytes.clear();}
    @Test void rawDefinitionMatchesOnlyThatLoaderAndHash(){
        byte[] raw=raw();Loader a=new Loader(),b=new Loader();Class<?> one=a.define(raw),two=b.define(raw);
        AdmittedBytes.defined(a,"example/Owner",raw);
        assertTrue(AdmittedBytes.matches(one,raw));assertFalse(AdmittedBytes.matches(two,raw));
        assertFalse(AdmittedBytes.matches(one,new byte[]{1}));assertFalse(AdmittedBytes.matches(null,raw));
    }
    @Test void repeatedDefinitionAttemptIsUnknownUntilSuccessfulReload(){
        byte[] raw=raw();Loader loader=new Loader();Class<?> type=loader.define(raw);
        AdmittedBytes.defined(loader,"example/Owner",raw);AdmittedBytes.defined(loader,"example/Owner",raw);
        assertFalse(AdmittedBytes.matches(type,raw));AdmittedBytes.admitted(type,raw);assertTrue(AdmittedBytes.matches(type,raw));
    }
    @Test void failedOrIncompleteReloadCannotKeepOldProof(){
        byte[] raw=raw();Loader loader=new Loader();Class<?> type=loader.define(raw);AdmittedBytes.admitted(type,raw);
        AdmittedBytes.invalidate(type);assertFalse(AdmittedBytes.matches(type,raw));
        byte[] next={2};AdmittedBytes.admitted(type,next);assertTrue(AdmittedBytes.matches(type,next));assertFalse(AdmittedBytes.matches(type,raw));
    }
}
