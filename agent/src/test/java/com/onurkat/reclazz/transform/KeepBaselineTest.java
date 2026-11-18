/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.spring.SingletonKeepPolicy;


import org.junit.jupiter.api.Test;
import java.lang.ref.WeakReference;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class KeepBaselineTest {
    static class Loader extends ClassLoader {
        Class<?> define(byte[] bytes){return defineClass("example.Baseline",bytes,0,bytes.length);}
    }
    private static byte[] bytes(int constant) {
        var w=new ClassWriter(0);w.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"example/Baseline",null,"java/lang/Object",null);
        var m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"value","()I",null,null);
        m.visitCode();m.visitLdcInsn(constant);m.visitInsn(Opcodes.IRETURN);m.visitMaxs(1,0);m.visitEnd();w.visitEnd();return w.toByteArray();
    }
    private static MethodNode callback(boolean wider) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "callback", "()V", null, null);
        LabelNode start = new LabelNode(), narrowEnd = new LabelNode(), wideEnd = new LabelNode(), handler = new LabelNode();
        method.instructions.add(start); method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(narrowEnd); method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(wideEnd); method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(handler); method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, wider ? wideEnd : narrowEnd, handler, "java/lang/RuntimeException"));
        return method;
    }
    @Test void exceptionHandlerBoundariesParticipateInCreationBodyDigest() {
        assertNotEquals(KeepBaseline.digest(callback(false)), KeepBaseline.digest(callback(true)),
                "creation behavior changes when the same instructions have different catch coverage");
        MethodNode repeated = callback(false);
        assertEquals(KeepBaseline.digest(repeated), KeepBaseline.digest(repeated),
                "resetting labels must keep a second fingerprint stable");
    }

    @Test void captureIsFirstDefinitionAndSuccessfulAdvanceChangesIt() {
        var loader=new Loader();byte[] old=bytes(1), next=bytes(2);Class<?> type=loader.define(old);
        KeepBaseline.capture(loader,"example/Baseline",old);
        var before=KeepBaseline.get(type);
        KeepBaseline.capture(loader,"example/Baseline",next);
        assertEquals(before,KeepBaseline.get(type),"a redefine observation cannot advance baseline");
        KeepBaseline.advance(type,next);
        assertNotEquals(before,KeepBaseline.get(type));
    }
    @Test void sameNameInDifferentLoadersHasSeparateBaseline() {
        var a=new Loader();var b=new Loader();var ca=a.define(bytes(1));var cb=b.define(bytes(2));
        KeepBaseline.capture(a,"example/Baseline",bytes(1));KeepBaseline.capture(b,"example/Baseline",bytes(2));
        assertNotEquals(KeepBaseline.get(ca),KeepBaseline.get(cb));
    }
    @Test void absentBaselineFailsClosed() {
        var l=new Loader();var c=l.define(bytes(1));
        assertNull(KeepBaseline.get(c));
        assertFalse(SingletonKeepPolicy.compare(new KeepBaseline.Input(KeepBaseline.get(c),bytes(2)),Set.of(),false).keep());
    }
    private static WeakReference<ClassLoader> temporaryLoader() {
        var l=new Loader();l.define(bytes(1));KeepBaseline.capture(l,"example/Baseline",bytes(1));return new WeakReference<>(l);
    }
    @Test void fingerprintDoesNotRetainItsLoader()throws Exception {
        var weak=temporaryLoader();
        for(int i=0;i<60&&weak.get()!=null;i++){System.gc();Thread.sleep(20);}
        assertNull(weak.get(),"baseline must not strongly retain the defining loader");
    }
    @Test void unreadableBytesNeverThrowFromCaptureAndDropBaselineOnAdvance() {
        var loader=new Loader();byte[] good=bytes(1);Class<?> type=loader.define(good);
        byte[] garbage={(byte)0xCA,(byte)0xFE,(byte)0xBA,(byte)0xBE,0,0};
        assertDoesNotThrow(()->KeepBaseline.capture(loader,"example/Other",garbage),
                "capture runs inside class definition and must never fail the transform");
        KeepBaseline.capture(loader,"example/Baseline",good);
        assertNotNull(KeepBaseline.get(type));
        assertDoesNotThrow(()->KeepBaseline.advance(type,garbage));
        assertNull(KeepBaseline.get(type),"an unreadable new definition must not leave the stale baseline deciding");
    }
}
