/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The S reflection root view changes at the schema CAS, not during preparation. */
class RecordMetadataTest {
    record Pair(int a,String b) { Pair(int a){this(a,"auxiliary");} }
    record Aborted(int a,String b) { }
    record Untouched(int a) { }
    private static RecordSchema.Members onlyA(Class<?> owner) throws Exception {
        Method accessor=owner.getDeclaredMethod("a");
        List<Constructor<?>> constructors=owner==Pair.class?List.of(owner.getDeclaredConstructor(int.class)):List.of();
        return new RecordSchema.Members(List.of(new RecordSchema.Component("a",int.class,accessor)),
                List.of(accessor),List.of(owner.getDeclaredField("a")),constructors,Map.of(),Map.of(),Map.of());
    }
    @Test void declaredRootsAndCachesStayPhysicalUntilCommit() throws Exception {
        Method[] methods=Pair.class.getDeclaredMethods();Field[] fields=Pair.class.getDeclaredFields();
        Constructor<?>[] constructors=Pair.class.getDeclaredConstructors();
        var slot=RecordSchema.slot(Pair.class);
        try(var next=slot.prepareVirtual(onlyA(Pair.class))) {
            assertNull(RecordSchema.virtualMembers(Pair.class));
            assertSame(methods,ReflectionBridge.mergeDeclaredRoots(methods,Pair.class,false));
            assertSame(fields,ReflectionBridge.mergeFieldRoots(fields,Pair.class,false));
            assertSame(constructors,ReflectionBridge.mergeConstructorRoots(constructors,Pair.class,false));
            assertSame(methods,ReflectionBridge.publicMethodCache(methods,Pair.class));
            assertTrue(next.commit());
        }
        assertEquals(List.of("a"),Arrays.stream(ReflectionBridge.mergeDeclaredRoots(methods,Pair.class,false)).map(Method::getName).toList());
        assertEquals(List.of("a"),Arrays.stream(ReflectionBridge.mergeFieldRoots(fields,Pair.class,false)).map(Field::getName).toList());
        var active=ReflectionBridge.mergeConstructorRoots(constructors,Pair.class,false);
        assertEquals(1,active.length);assertArrayEquals(new Class<?>[]{int.class},active[0].getParameterTypes(),"explicit valid auxiliary root is not filtered by a canonical-only heuristic");
        assertNull(ReflectionBridge.publicMethodCache(methods,Pair.class));
        assertNull(ReflectionBridge.publicFieldCache(fields,Pair.class));
        assertEquals(2,slot.physicalComponents().size());
    }
    @Test void discardedVirtualPreparationDoesNotActivateTheView() throws Exception {
        var slot=RecordSchema.slot(Aborted.class);var before=slot.current();
        try(var next=slot.prepareVirtual(onlyA(Aborted.class))) {assertSame(before,slot.current());}
        assertSame(before,slot.current());assertNull(RecordSchema.virtualMembers(Aborted.class));
        try(var ordinary=slot.prepare(onlyA(Aborted.class))){assertTrue(ordinary.commit());}
        assertNull(RecordSchema.virtualMembers(Aborted.class),"a later unrelated commit cannot revive the discarded virtual view");
    }
    @Test void unrelatedAndUnpreparedReflectionKeepsItsOriginalArrays() {
        Method[] methods=Untouched.class.getDeclaredMethods();Field[] fields=Untouched.class.getDeclaredFields();
        assertNull(RecordSchema.virtualMembers(Untouched.class));
        assertSame(methods,ReflectionBridge.mergeDeclaredRoots(methods,Untouched.class,false));
        assertSame(fields,ReflectionBridge.mergeFieldRoots(fields,Untouched.class,false));
        assertSame(methods,ReflectionBridge.publicMethodCache(methods,Untouched.class));
        assertSame(methods,ReflectionBridge.mergeDeclaredRoots(methods,String.class,false));
    }
}
