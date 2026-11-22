/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Caller-owned initializer generations publish with the record CAS, only for record cohorts. */
class RecordFieldInitialiserPublicationTest {
    record First(int value) { }
    record Second(int value) { }
    public static class Caller {
        public Object[] __reclazz$ext;
        static Runnable duringOld;
        public static Object old(Caller receiver){var action=duringOld;duringOld=null;if(action!=null)action.run();return "old";}
        public static Object next(Caller receiver){return "new";}
        public static Object other(Caller receiver){return "other";}
    }
    private static final String KEY="value:Ljava/lang/String;";
    private static MethodHandle target(String name)throws Exception {
        return MethodHandles.lookup().findStatic(Caller.class,name,MethodType.methodType(Object.class,Caller.class));
    }
    private static Object read(Caller receiver){return FieldStore.getExtField(receiver,Caller.class.getName(),"value","Ljava/lang/String;");}
    private static RecordSchema.Preparation prepare(Class<?> record) {
        var components=Arrays.stream(record.getRecordComponents()).map(c->new RecordSchema.Component(c.getName(),c.getType(),c.getAccessor())).toList();
        return RecordSchema.slot(record).prepare(new RecordSchema.Members(components,List.of(),List.of(),List.of(),Map.of(),Map.of(),Map.of()));
    }
    @BeforeEach void normalBaseline()throws Exception {
        Caller.duringOld=null;
        FieldStore.setInstanceInitialisers(Caller.class,Map.of(KEY,target("old").asType(MethodType.methodType(Object.class,Object.class))));
    }
    @Test void preparedInitializersRemainOldUntilTheRecordCas()throws Exception {
        Caller alreadyRead=new Caller();assertEquals("old",read(alreadyRead));Caller unread=new Caller();
        try(var next=prepare(First.class)) {
            next.stageFieldInitializers(Caller.class,Map.of(KEY,target("next"),"added:Ljava/lang/String;",target("next")));
            assertEquals("old",read(new Caller()));assertFalse(FieldStore.hasInstanceInitialiser(Caller.class,"added","Ljava/lang/String;"));
            assertTrue(next.commit());assertEquals("new",read(unread));assertEquals("old",read(alreadyRead));
            assertTrue(FieldStore.hasInstanceInitialiser(Caller.class,"added","Ljava/lang/String;"));
        }
    }
    @Test void abortLeavesTheNormalInitializerGeneration()throws Exception {
        try(var next=prepare(First.class)){next.stageFieldInitializers(Caller.class,Map.of(KEY,target("next")));}
        assertEquals("old",read(new Caller()));
    }
    @Test void competingPreparationsUseAttemptIdentity()throws Exception {
        try(var lost=prepare(First.class);var winner=prepare(First.class)) {
            lost.stageFieldInitializers(Caller.class,Map.of(KEY,target("next")));
            winner.stageFieldInitializers(Caller.class,Map.of(KEY,target("other")));
            assertTrue(winner.commit());assertFalse(lost.commit());assertEquals("other",read(new Caller()));
        }
    }
    @Test void untouchedLaterEpochKeepsTheCommittedCallerInitializers()throws Exception {
        try(var next=prepare(First.class)){next.stageFieldInitializers(Caller.class,Map.of(KEY,target("next")));assertTrue(next.commit());}
        try(var untouched=prepare(First.class)){assertTrue(untouched.commit());}
        assertEquals("new",read(new Caller()));
    }
    @Test void sequentialRecordCohortsRebaseTheSelectedCallerGeneration()throws Exception {
        try(var one=prepare(First.class)){one.stageFieldInitializers(Caller.class,Map.of(KEY,target("next")));assertTrue(one.commit());}
        try(var two=prepare(Second.class)) {
            two.stageFieldInitializers(Caller.class,Map.of(KEY,target("other")));assertEquals("new",read(new Caller()));
            assertTrue(two.commit());assertEquals("other",read(new Caller()));
        }
    }
    @Test void overlappingOtherRecordIsRefusedWithoutDiscardingTheFirst()throws Exception {
        try(var one=prepare(First.class);var two=prepare(Second.class)) {
            one.stageFieldInitializers(Caller.class,Map.of(KEY,target("next")));
            assertThrows(IllegalStateException.class,()->two.stageFieldInitializers(Caller.class,Map.of(KEY,target("other"))));
            assertTrue(one.commit());assertEquals("new",read(new Caller()));
        }
    }
    @Test void ordinaryRegistrationAfterCommitReplacesTheGenerationAndNextStageRebasesIt()throws Exception {
        try(var one=prepare(First.class)){one.stageFieldInitializers(Caller.class,Map.of(KEY,target("next")));assertTrue(one.commit());}
        normalBaseline();assertEquals("old",read(new Caller()));
        try(var two=prepare(First.class)) {
            two.stageFieldInitializers(Caller.class,Map.of(KEY,target("other")));assertEquals("old",read(new Caller()));
            assertTrue(two.commit());assertEquals("other",read(new Caller()));
        }
    }
    @Test void ordinaryRegistrationCancelsTheWholePendingRecordAttempt()throws Throwable {
        var dispatch=DispatchTable.getOrCreate(Caller.class);
        DispatchTable.retargetAll(Caller.class,null,Map.of("pending-field-test",MethodHandles.constant(String.class,"old")));
        var call=dispatch.getOrCreateMethodSite("pending-field-test",new MutableCallSite(MethodHandles.constant(String.class,"old"))).dynamicInvoker();
        var before=RecordSchema.slot(First.class).current();
        try(var next=prepare(First.class)) {
            next.stageDispatch(Caller.class,Map.of("pending-field-test",MethodHandles.constant(String.class,"new")));
            next.stageFieldInitializers(Caller.class,Map.of(KEY,target("next")));normalBaseline();
            assertFalse(next.commit());assertSame(before,RecordSchema.slot(First.class).current());
            assertEquals("old",(String)call.invokeExact());assertEquals("old",read(new Caller()));
        }
    }
    @Test void emptyGenerationPublishesRemovalOnlyAtCommit()throws Exception {
        try(var next=prepare(First.class)) {
            next.stageFieldInitializers(Caller.class,Map.of());assertEquals("old",read(new Caller()));assertTrue(next.commit());
        }
        assertNull(read(new Caller()));assertFalse(FieldStore.hasInstanceInitialiser(Caller.class,"value","Ljava/lang/String;"));
    }
    @Test void closedPreparationAndUnownedOrAdaptedTargetsAreRefused()throws Exception {
        var next=prepare(First.class);next.close();
        assertThrows(IllegalStateException.class,()->next.stageFieldInitializers(Caller.class,Map.of(KEY,target("next"))));
        try(var pending=prepare(First.class)) {
            var opaque=MethodHandles.dropArguments(MethodHandles.constant(Object.class,"foreign"),0,Caller.class);
            assertThrows(IllegalArgumentException.class,()->pending.stageFieldInitializers(Caller.class,Map.of(KEY,opaque)));
            assertThrows(IllegalArgumentException.class,()->pending.stageFieldInitializers(Caller.class,Map.of("bad",target("next"))));
            assertEquals("old",read(new Caller()));
        }
    }
    @Test void initializerCrossingTheRecordCasCannotPublishItsOldGeneration()throws Exception {
        Caller receiver=new Caller();
        try(var next=prepare(First.class)) {
            next.stageFieldInitializers(Caller.class,Map.of(KEY,target("next")));
            Caller.duringOld=()->assertTrue(next.commit());
            assertNull(read(receiver),"a computed old value is discarded after the schema CAS");
            assertEquals("new",read(receiver),"the next read uses the committed initializer generation");
        }
    }

    static class Outside {
        static void stage(RecordSchema.Preparation pending)throws Exception {pending.stageFieldInitializers(Caller.class,Map.of(KEY,target("next")));}
    }
    @Test void untrustedCallerCannotStageInitializerCapabilitiesAfterSealing()throws Exception {
        var pending=prepare(First.class);LookupCapture.trust(RecordFieldInitialiserPublicationTest.class);
        try {LookupCapture.seal();assertThrows(SecurityException.class,()->Outside.stage(pending));}
        finally {LookupCapture.unsealForTests();pending.close();}
        assertEquals("old",read(new Caller()));
    }

}
