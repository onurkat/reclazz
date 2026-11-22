/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The actual warmed/cold DispatchTable and caller version join the record schema CAS. */
class RecordDispatchPublicationTest {
    record First(int value) { }
    record Second(String value) { }
    static class Warm { }
    static class ColdDuring { }
    static class ColdAfter { }
    static class ColdAbort { }
    static class Aborted { }
    static class Competing { }
    static class Untouched { }
    static class CrossRecord { }
    static class NormalAfter { }
    static class Invalid { }
    static class PendingCross { }
    static class Sealed { }
    private static RecordSchema.Members members(Class<?> owner) {
        var components=Arrays.stream(owner.getRecordComponents())
                .map(c->new RecordSchema.Component(c.getName(),c.getType(),c.getAccessor())).toList();
        return new RecordSchema.Members(components,List.of(),List.of(),List.of(),Map.of(),Map.of(),Map.of());
    }
    private static RecordSchema.Preparation prepare(Class<?> owner){return RecordSchema.slot(owner).prepare(members(owner));}
    private static MethodHandle target(String value){return MethodHandles.constant(String.class,value);}
    private static MutableCallSite site(Class<?> caller){return DispatchTable.getOrCreate(caller).getOrCreateMethodSite("view",new MutableCallSite(target("v0")));}
    private static String call(MutableCallSite site)throws Throwable{return (String)site.dynamicInvoker().invokeExact();}
    private static void stage(RecordSchema.Preparation p,Class<?> caller,String value){p.stageDispatch(caller,Map.of("view",target(value)));}
    @Test void warmTargetAndVersionChangeOnlyAtTheSchemaCas()throws Throwable {
        var dispatch=DispatchTable.getOrCreate(Warm.class);var site=site(Warm.class);
        try(var next=prepare(First.class)) {
            stage(next,Warm.class,"v1");assertEquals("v0",call(site));assertEquals(0,dispatch.getVersion());
            assertTrue(next.commit());assertEquals("v1",call(site));assertEquals(1,dispatch.getVersion());
        }
    }
    @Test void firstTouchDuringPrepareGetsItsRealOldBootstrapTarget()throws Throwable {
        var dispatch=DispatchTable.getOrCreate(ColdDuring.class);
        try(var next=prepare(First.class)) {
            stage(next,ColdDuring.class,"v1");var site=site(ColdDuring.class);
            assertEquals("v0",call(site));assertEquals(0,dispatch.getVersion());
            assertTrue(next.commit());assertEquals("v1",call(site));assertEquals(1,dispatch.getVersion());
        }
    }
    @Test void firstTouchAfterCommitGetsTheCommittedTarget()throws Throwable {
        try(var next=prepare(First.class)){stage(next,ColdAfter.class,"v1");assertTrue(next.commit());}
        assertEquals("v1",call(site(ColdAfter.class)));assertEquals(1,DispatchTable.getOrCreate(ColdAfter.class).getVersion());
    }
    @Test void coldAbortStillAllowsTheOriginalFirstBootstrap()throws Throwable {
        try(var next=prepare(First.class)){stage(next,ColdAbort.class,"never");}
        assertEquals("v0",call(site(ColdAbort.class)));assertEquals(0,DispatchTable.getOrCreate(ColdAbort.class).getVersion());
    }
    @Test void abortedTargetsCannotBorrowTheLaterSuccessfulSequence()throws Throwable {
        var site=site(Aborted.class);var dispatch=DispatchTable.getOrCreate(Aborted.class);
        try(var next=prepare(First.class)){stage(next,Aborted.class,"never");assertEquals("v0",call(site));}
        assertEquals("v0",call(site));assertEquals(0,dispatch.getVersion());
        try(var other=prepare(First.class)){assertTrue(other.commit());}
        assertEquals("v0",call(site));assertEquals(0,dispatch.getVersion());
        try(var next=prepare(First.class)){stage(next,Aborted.class,"v1");assertTrue(next.commit());}
        assertEquals("v1",call(site));assertEquals(1,dispatch.getVersion());
    }
    @Test void losingPreparedDispatchCannotOverrideTheWinner()throws Throwable {
        var site=site(Competing.class);var dispatch=DispatchTable.getOrCreate(Competing.class);
        try(var winner=prepare(First.class);var loser=prepare(First.class)) {
            stage(winner,Competing.class,"winner");stage(loser,Competing.class,"loser");
            assertEquals("v0",call(site));assertTrue(winner.commit());
            assertEquals("winner",call(site));assertEquals(1,dispatch.getVersion());assertFalse(loser.commit());
        }
        assertEquals("winner",call(site));assertEquals(1,dispatch.getVersion());
    }
    @Test void unchangedCallerKeepsItsTargetAndVersionAcrossRecordOnlyEpochs()throws Throwable {
        var site=site(Untouched.class);var dispatch=DispatchTable.getOrCreate(Untouched.class);
        try(var first=prepare(First.class)){stage(first,Untouched.class,"v1");assertTrue(first.commit());}
        for(int i=0;i<4;i++)try(var next=prepare(First.class)){assertTrue(next.commit());}
        assertEquals("v1",call(site));assertEquals(1,dispatch.getVersion());
    }
    @Test void revisitingARecordKeepsTheMostRecentOtherRecordsCallerBeforeCas()throws Throwable {
        var site=site(CrossRecord.class);var dispatch=DispatchTable.getOrCreate(CrossRecord.class);
        try(var first=prepare(First.class)){stage(first,CrossRecord.class,"first");assertTrue(first.commit());}
        try(var second=prepare(Second.class)){stage(second,CrossRecord.class,"second");assertEquals("first",call(site));assertTrue(second.commit());}
        try(var abort=prepare(First.class)){stage(abort,CrossRecord.class,"never");assertEquals("second",call(site));assertEquals(2,dispatch.getVersion());}
        assertEquals("second",call(site));assertEquals(2,dispatch.getVersion());
        try(var next=prepare(First.class)){stage(next,CrossRecord.class,"third");assertEquals("second",call(site));assertTrue(next.commit());}
        assertEquals("third",call(site));assertEquals(3,dispatch.getVersion());
        try(var untouched=prepare(Second.class)){assertTrue(untouched.commit());}
        assertEquals("third",call(site));assertEquals(3,dispatch.getVersion());
    }
    @Test void normalRetargetCanFollowAndPrecedeARecordCohort()throws Throwable {
        var site=site(NormalAfter.class);var dispatch=DispatchTable.getOrCreate(NormalAfter.class);
        try(var first=prepare(First.class)){stage(first,NormalAfter.class,"record1");assertTrue(first.commit());}
        dispatch.retarget(Map.of("view",target("normal")));assertEquals("normal",call(site));assertEquals(2,dispatch.getVersion());
        try(var next=prepare(First.class)){stage(next,NormalAfter.class,"record2");assertEquals("normal",call(site));assertEquals(2,dispatch.getVersion());assertTrue(next.commit());}
        assertEquals("record2",call(site));assertEquals(3,dispatch.getVersion());
    }
    @Test void invalidTypeIsRefusedBeforeTargetOrVersionChanges()throws Throwable {
        var site=site(Invalid.class);var dispatch=DispatchTable.getOrCreate(Invalid.class);
        try(var next=prepare(First.class)) {
            assertThrows(IllegalArgumentException.class,()->next.stageDispatch(Invalid.class,Map.of("view",MethodHandles.constant(int.class,1))));
            assertEquals("v0",call(site));assertEquals(0,dispatch.getVersion());
        }
        try(var next=prepare(First.class)) {
            assertThrows(IllegalArgumentException.class,()->next.stageDispatch(First.class,Map.of("opaque",target("foreign"))),"record-owned dispatch must reject composed foreign targets");
        }
    }
    @Test void pendingDifferentRecordCohortDoesNotOverwriteAnUnfinishedCaller()throws Throwable {
        var site=site(PendingCross.class);var dispatch=DispatchTable.getOrCreate(PendingCross.class);
        try(var first=prepare(First.class);var other=prepare(Second.class)) {
            stage(first,PendingCross.class,"first");
            assertThrows(IllegalStateException.class,()->stage(other,PendingCross.class,"other"));
            assertEquals("v0",call(site));assertEquals(0,dispatch.getVersion());assertTrue(first.commit());
        }
        assertEquals("first",call(site));assertEquals(1,dispatch.getVersion());
    }
    public static class ProxyBase { public String value(){return "original";} }
    public static class ProxyReceiver extends ProxyBase { @Override public String value(){return "intercepted";} }
    private static String proxyCall(MutableCallSite site,ProxyBase receiver)throws Throwable {
        return (String)site.dynamicInvoker().invokeExact(receiver);
    }
    private void proxy(boolean cold)throws Throwable {
        var dispatch=DispatchTable.getOrCreate(ProxyBase.class);String key=cold?"cold":"warm";
        var publicCall=MethodHandles.lookup().findVirtual(ProxyBase.class,"value",MethodType.methodType(String.class));
        MutableCallSite site=null;
        if(!cold) {
            dispatch.registerOverrideGuard(key,ProxyBase.class,"value",publicCall);
            site=dispatch.getOrCreateMethodSite(key,new MutableCallSite(publicCall));
        }
        try(var next=prepare(First.class)) {
            var body=MethodHandles.dropArguments(target("updated"),0,ProxyBase.class);
            next.stageDispatch(ProxyBase.class,Map.of(key,body));
            if(cold) {
                dispatch.registerOverrideGuard(key,ProxyBase.class,"value",publicCall);
                site=dispatch.getOrCreateMethodSite(key,new MutableCallSite(publicCall));
            }
            assertEquals("original",proxyCall(site,new ProxyBase()));assertEquals("intercepted",proxyCall(site,new ProxyReceiver()));
            assertTrue(next.commit());
            assertEquals("updated",proxyCall(site,new ProxyBase()));assertEquals("intercepted",proxyCall(site,new ProxyReceiver()));
        }
    }
    @Test void warmRecordDispatchStillRespectsReceiverOverrides()throws Throwable {proxy(false);}
    @Test void coldRecordDispatchStillRespectsReceiverOverrides()throws Throwable {proxy(true);}
    static class Partial { }
    @Test void multipleTargetChunksInOnePreparationAdvanceVersionOnlyOnce()throws Throwable {
        var dispatch=DispatchTable.getOrCreate(Partial.class);var first=site(Partial.class);
        var second=dispatch.getOrCreateMethodSite("other",new MutableCallSite(target("other0")));
        try(var next=prepare(First.class)) {
            stage(next,Partial.class,"view1");next.stageDispatch(Partial.class,Map.of("other",target("other1")));
            stage(next,Partial.class,"view2");
            assertEquals("v0",call(first));assertEquals("other0",call(second));assertEquals(0,dispatch.getVersion());
            assertTrue(next.commit());
        }
        assertEquals("view2",call(first));assertEquals("other1",call(second));assertEquals(1,dispatch.getVersion());
    }
    @Test void closedPreparationCannotStageAnotherCaller() {
        var next=prepare(First.class);next.close();
        assertThrows(IllegalStateException.class,()->stage(next,Partial.class,"closed"));
    }
    static class Untrusted {
        static void stage(RecordSchema.Preparation p){p.stageDispatch(Sealed.class,Map.of("view",target("stolen")));}
    }
    @Test void dispatchPreparationStillRequiresAnEngineCapability() {
        try(var next=prepare(First.class)) {
            LookupCapture.trust(getClass());
            try {
                LookupCapture.seal();assertThrows(SecurityException.class,()->Untrusted.stage(next));
                stage(next,Sealed.class,"trusted");assertTrue(next.commit());
            } finally {LookupCapture.unsealForTests();}
        }
    }
    static class LockFreeReader { }
    @Test void committedCallerReadDoesNotWaitForPublicationOrSiteMonitor()throws Throwable {
        var slot=RecordSchema.slot(First.class);var site=RecordSchema.callerSite(LockFreeReader.class,slot,"reader",target("old"));
        try(var pending=slot.prepare(slot.current().members())){pending.stage(site,target("new"));assertTrue(pending.commit());}
        var ready=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        Thread holder=new Thread(()->{synchronized(slot){synchronized(site){ready.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}}},"publication-lock-holder");
        holder.start();assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));
        try {
            var result=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return (String)site.dispatcherInternal().invokeExact();}catch(Throwable failure){throw new RuntimeException(failure);}});
            assertEquals("new",result.get(5,java.util.concurrent.TimeUnit.SECONDS));
        }finally{release.countDown();holder.join(5000);}
    }

    static class OrdinaryDuringPending { }
    @Test void ordinaryRetargetCancelsTheOwnersPendingCohort()throws Throwable {
        var dispatch=DispatchTable.getOrCreate(OrdinaryDuringPending.class);var current=site(OrdinaryDuringPending.class);
        try(var pending=prepare(First.class)) {
            stage(pending,OrdinaryDuringPending.class,"prepared");
            dispatch.retarget(Map.of("view",target("ordinary")));
            assertFalse(pending.commit(),"ordinary retarget must abort its uncommitted cohort");
            assertEquals("ordinary",call(current));assertEquals(1,dispatch.getVersion());
        }
        try(var retry=prepare(First.class)) {
            stage(retry,OrdinaryDuringPending.class,"later-record");assertTrue(retry.commit());
        }
        assertEquals("later-record",call(current));assertEquals(2,dispatch.getVersion());
    }
}
