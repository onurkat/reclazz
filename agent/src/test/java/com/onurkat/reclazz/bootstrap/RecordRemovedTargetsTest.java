/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

class RecordRemovedTargetsTest {
    private static MethodHandles.Lookup hidden(Object data)throws Exception {
        var writer=new ClassWriter(0);writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,
                "com/onurkat/reclazz/bootstrap/RemovalData",null,"java/lang/Object",null);writer.visitEnd();
        return MethodHandles.lookup().defineHiddenClassWithClassData(writer.toByteArray(),data,false,MethodHandles.Lookup.ClassOption.NESTMATE);
    }
    @Test void targetIsPrivateClassDataRatherThanANameKeyedRegistry()throws Throwable {
        var type=MethodType.methodType(int.class);var one=hidden(Map.of("gone",MethodHandles.constant(int.class,7)));
        var two=hidden(Map.of("gone",MethodHandles.constant(int.class,9)));
        assertEquals(7,(int)RecordRemovedTargets.bootstrap(one,"removed",type,"gone").dynamicInvoker().invokeExact());
        assertEquals(9,(int)RecordRemovedTargets.bootstrap(two,"removed",type,"gone").dynamicInvoker().invokeExact());
        assertThrows(IllegalAccessException.class,()->RecordRemovedTargets.bootstrap(one.dropLookupMode(MethodHandles.Lookup.ORIGINAL),"removed",type,"gone"));
    }
    @Test void unownedMissingAndWrongTypedTargetsAreRefused()throws Exception {
        var type=MethodType.methodType(int.class);var owner=hidden(Map.of("gone",MethodHandles.constant(int.class,7)));
        assertThrows(IllegalAccessException.class,()->RecordRemovedTargets.bootstrap(MethodHandles.lookup(),"removed",type,"gone"));
        assertThrows(IllegalArgumentException.class,()->RecordRemovedTargets.bootstrap(owner,"removed",type,"other"));
        assertThrows(IllegalArgumentException.class,()->RecordRemovedTargets.bootstrap(owner,"removed",MethodType.methodType(long.class),"gone"));
        assertThrows(IllegalArgumentException.class,()->RecordRemovedTargets.bootstrap(hidden(Map.of("gone","not a target")),"removed",type,"gone"));
    }
}
