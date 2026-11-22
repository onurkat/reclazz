/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.MethodHandles;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecordObjectMethodsTest {
    record Wide(boolean flag,byte tiny,short small,char letter,int number,long wide,float fraction,double precise,Object reference,int[] array,String nullable) { }
    record Pair(int left,int right) { }
    record Masked(int value) { @Override public int value(){return -1;} }
    record Switching(int left,Trigger trigger,int right) { }
    static class Trigger {
        Runnable action;
        @Override public int hashCode(){var run=action;action=null;if(run!=null)run.run();return 5;}
    }
    private static RecordSchema.Members members(Class<?> owner,List<String> names)throws Exception {
        var source=ReflectionBridge.physicalRecordComponents(owner);var components=new ArrayList<RecordSchema.Component>();
        var fields=new ArrayList<java.lang.reflect.Field>();
        for(String name:names) {
            var c=Arrays.stream(source).filter(p->p.getName().equals(name)).findFirst().orElseThrow();
            components.add(new RecordSchema.Component(name,c.getType(),c.getAccessor(),c));
            fields.add(owner.getDeclaredField(name));
        }
        return new RecordSchema.Members(components,List.of(),fields,List.of(),Map.of(),Map.of(),Map.of());
    }
    private static RecordSchema.Members all(Class<?> owner)throws Exception {
        return members(owner,Arrays.stream(ReflectionBridge.physicalRecordComponents(owner)).map(c->c.getName()).toList());
    }
    private static void install(Class<?> owner,RecordSchema.Members members) {
        try(var next=RecordSchema.slot(owner).prepareVirtual(members)){assertTrue(next.commit());}
    }
    @Test void allWidthsNullsNaNsAndArrayIdentityMatchNativeRecordMethods()throws Throwable {
        Object reference=new Object();int[] array={2,4};
        var one=new Wide(true,(byte)-8,(short)1234,'Z',70001,9000000001L,-0.0f,Double.NaN,reference,array,null);
        var equal=new Wide(true,(byte)-8,(short)1234,'Z',70001,9000000001L,-0.0f,Double.longBitsToDouble(0x7ff8000000000001L),reference,array,null);
        var positiveZero=new Wide(true,(byte)-8,(short)1234,'Z',70001,9000000001L,0.0f,Double.NaN,reference,array,null);
        var separateArray=new Wide(true,(byte)-8,(short)1234,'Z',70001,9000000001L,-0.0f,Double.NaN,reference,array.clone(),null);
        var nulls=new Wide(false,(byte)0,(short)0,'\0',0,0,Float.NaN,-0.0d,null,null,null);
        List<Wide> values=List.of(one,equal,positiveZero,separateArray,nulls);
        var hashes=values.stream().map(Wide::hashCode).toList();var texts=values.stream().map(Wide::toString).toList();
        var expected=new ArrayList<Boolean>();for(var left:values)for(var right:values)expected.add(left.equals(right));
        install(Wide.class,all(Wide.class));var lookup=MethodHandles.lookup();
        for(int i=0;i<values.size();i++) {
            assertEquals(hashes.get(i).intValue(),RecordObjects.hashCode(lookup,Wide.class,values.get(i)));
            assertEquals(texts.get(i),RecordObjects.toString(lookup,Wide.class,values.get(i)));
        }
        int index=0;for(var left:values)for(var right:values)
            assertEquals(expected.get(index++),RecordObjects.equals(lookup,Wide.class,left,right));
        assertFalse(RecordObjects.equals(lookup,Wide.class,one,null));
        assertFalse(RecordObjects.equals(lookup,Wide.class,one,new Object()));
    }
    @Test void reorderAndRemoveChangeOnlyAtTheRecordCas()throws Throwable {
        var value=new Pair(1,2);var sameLeft=new Pair(1,9);var lookup=MethodHandles.lookup();
        install(Pair.class,all(Pair.class));var slot=RecordSchema.slot(Pair.class);
        int physicalHash=value.hashCode();String physicalText=value.toString();
        try(var reordered=slot.prepareVirtual(members(Pair.class,List.of("right","left")))) {
            assertEquals(physicalHash,RecordObjects.hashCode(lookup,Pair.class,value));
            assertEquals(physicalText,RecordObjects.toString(lookup,Pair.class,value));assertTrue(reordered.commit());
        }
        assertEquals(2*31+1,RecordObjects.hashCode(lookup,Pair.class,value));
        assertEquals("Pair[right=2, left=1]",RecordObjects.toString(lookup,Pair.class,value));
        try(var removed=slot.prepareVirtual(members(Pair.class,List.of("left")))) {
            assertFalse(RecordObjects.equals(lookup,Pair.class,value,sameLeft));assertTrue(removed.commit());
        }
        assertEquals(1,RecordObjects.hashCode(lookup,Pair.class,value));assertTrue(RecordObjects.equals(lookup,Pair.class,value,sameLeft));
        assertEquals("Pair[left=1]",RecordObjects.toString(lookup,Pair.class,value));
    }
    @Test void defaultObjectMethodsReadRawComponentsRatherThanCustomAccessors()throws Throwable {
        var value=new Masked(7);assertEquals(-1,value.value());
        int nativeHash=value.hashCode();String nativeText=value.toString();install(Masked.class,all(Masked.class));
        assertEquals(nativeHash,RecordObjects.hashCode(MethodHandles.lookup(),Masked.class,value));
        assertEquals(nativeText,RecordObjects.toString(MethodHandles.lookup(),Masked.class,value));
        assertEquals("Masked[value=7]",nativeText);
    }
    @Test void hashRetainsOneSnapshotWhenUserHashCodeCommitsTheNextShape()throws Throwable {
        var trigger=new Trigger();var value=new Switching(2,trigger,7);int before=value.hashCode();
        install(Switching.class,all(Switching.class));var slot=RecordSchema.slot(Switching.class);
        try(var removed=slot.prepareVirtual(members(Switching.class,List.of("left")))) {
            trigger.action=()->assertTrue(removed.commit());
            assertEquals(before,RecordObjects.hashCode(MethodHandles.lookup(),Switching.class,value),"one invocation must keep its original component set");
            assertEquals(2,RecordObjects.hashCode(MethodHandles.lookup(),Switching.class,value));
        }
    }
    @Test void unrelatedLookupCannotUseThePrivateComponentBridge()throws Throwable {
        install(Masked.class,all(Masked.class));var value=new Masked(7);
        assertThrows(SecurityException.class,()->RecordObjects.toString(MethodHandles.publicLookup(),Masked.class,value));
    }
    @Test void linkedObjectMethodsKeepFollowingTheCommittedComponentOrder()throws Throwable {
        var value=new Pair(2,1);install(Pair.class,all(Pair.class));
        var type=java.lang.invoke.MethodType.methodType(int.class,Pair.class);
        var linked=RecordObjects.bootstrap(MethodHandles.lookup(),"hashCode",type,Pair.class).dynamicInvoker();
        assertEquals(2*31+1,(int)linked.invokeExact(value));
        try(var pending=RecordSchema.slot(Pair.class).prepareVirtual(members(Pair.class,List.of("right","left")))) {
            assertEquals(2*31+1,(int)linked.invokeExact(value));assertTrue(pending.commit());
        }
        assertEquals(1*31+2,(int)linked.invokeExact(value));
    }
    @Test void objectMethodsLinkageRejectsForeignLookupNamesAndTypes() {
        var lookup=MethodHandles.lookup();var type=java.lang.invoke.MethodType.methodType(int.class,Pair.class);
        assertThrows(SecurityException.class,()->RecordObjects.bootstrap(MethodHandles.publicLookup(),"hashCode",type,Pair.class));
        assertThrows(IllegalArgumentException.class,()->RecordObjects.bootstrap(lookup,"other",type,Pair.class));
        assertThrows(IllegalArgumentException.class,()->RecordObjects.bootstrap(lookup,"hashCode",type.changeParameterType(0,Object.class),Pair.class));
    }

}
