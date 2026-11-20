/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecordSchemaPublicationTest {
    record Pair(int a,String b) { }
    private RecordSchema.Slot slot;
    private RecordSchema.Members full,one;
    private static MethodHandle target(String value){return MethodHandles.constant(String.class,value);}
    private static String call(RecordSchema.CallerSite site)throws Throwable{return (String)site.dispatcher().invokeExact();}
    @BeforeEach void reset() {
        var components=new ArrayList<RecordSchema.Component>();
        for(var c:Pair.class.getRecordComponents())components.add(new RecordSchema.Component(c.getName(),c.getType(),c.getAccessor()));
        full=new RecordSchema.Members(components,List.of(),List.of(),List.of(),Map.of(),Map.of(),Map.of());
        one=new RecordSchema.Members(components.subList(0,1),List.of(),List.of(),List.of(),Map.of(),Map.of(),Map.of());
        slot=RecordSchema.slot(Pair.class);
        try(var reset=slot.prepare(full)){assertTrue(reset.commit());}
    }
    @Test void pendingMembersAndCallerStayOldUntilOneCommit()throws Throwable {
        var before=slot.current();var site=RecordSchema.callerSite(getClass(),slot,"atomic",target("old"));
        try(var next=slot.prepare(one)) {
            next.stage(site,target("new"));assertSame(before,slot.current());assertEquals("old",call(site));
            assertEquals(2,slot.current().members().components().size());assertTrue(next.commit());
            assertEquals(before.sequence()+1,slot.current().sequence());assertEquals(1,slot.current().members().components().size());assertEquals("new",call(site));
        }
        assertEquals(2,before.members().components().size(),"an operation's retained snapshot stays immutable");
    }
    @Test void abortedGuardCannotActivateOnLaterCommit()throws Throwable {
        var site=RecordSchema.callerSite(getClass(),slot,"aborted",target("old"));
        long sequence=slot.current().sequence();
        try(var aborted=slot.prepare(one)){aborted.stage(site,target("aborted"));assertEquals("old",call(site));}
        assertEquals(sequence,slot.current().sequence());
        try(var different=slot.prepare(one)){assertTrue(different.commit());}
        assertEquals(sequence+1,slot.current().sequence());assertEquals("old",call(site));
    }
    @Test void untouchedCallerKeepsCommittedTargetAcrossLaterEpochs()throws Throwable {
        var site=RecordSchema.callerSite(getClass(),slot,"copyForward",target("v0"));
        try(var first=slot.prepare(one)){first.stage(site,target("v1"));assertTrue(first.commit());}
        for(int i=0;i<4;i++)try(var later=slot.prepare(full)){assertTrue(later.commit());}
        assertEquals("v1",call(site),"a dispatcher left installed must not revert on the next epoch");
    }
    @Test void losingPreparationCannotPolluteTheWinner()throws Throwable {
        var site=RecordSchema.callerSite(getClass(),slot,"competing",target("old"));
        try(var loser=slot.prepare(one);var winner=slot.prepare(full)) {
            loser.stage(site,target("loser"));winner.stage(site,target("winner"));
            assertTrue(winner.commit());assertFalse(loser.commit());assertEquals("winner",call(site));
        }
    }
    @Test void uncommittedAttemptCannotBorrowAnotherCommitsSequence()throws Throwable {
        var site=RecordSchema.callerSite(getClass(),slot,"sameSequence",target("old"));
        try(var pending=slot.prepare(one);var other=slot.prepare(full)) {
            pending.stage(site,target("never committed"));assertTrue(other.commit());
            assertEquals("old",call(site),"a pending attempt is not made valid by a different successful CAS at the same sequence");
        }
    }
    @Test void abortedTouchAfterUntouchedEpochsKeepsLastCommittedCaller()throws Throwable {
        var site=RecordSchema.callerSite(getClass(),slot,"untouchedThenAbort",target("v0"));
        try(var first=slot.prepare(one)){first.stage(site,target("v1"));assertTrue(first.commit());}
        for(int i=0;i<3;i++)try(var untouched=slot.prepare(full)){assertTrue(untouched.commit());}
        try(var abort=slot.prepare(one)){abort.stage(site,target("not committed"));}
        assertEquals("v1",call(site),"pruning a later aborted touch must keep the site's last committed entry, not just the current epoch");
    }
    @Test void unclosedAttemptStaysInvalidAcrossLaterUntouchedCommits()throws Throwable {
        var site=RecordSchema.callerSite(getClass(),slot,"unclosed",target("v0"));
        try(var first=slot.prepare(one)){first.stage(site,target("v1"));assertTrue(first.commit());}
        for(int i=0;i<3;i++)try(var untouched=slot.prepare(full)){assertTrue(untouched.commit());}
        var pending=slot.prepare(one);
        try {
            pending.stage(site,target("never committed"));
            try(var other=slot.prepare(full)){assertTrue(other.commit());}
            assertEquals("v1",call(site),"validity must reject the pending entry before discard runs, even when another commit reused its sequence");
            try(var another=slot.prepare(one)){assertTrue(another.commit());}
            assertEquals("v1",call(site));
        } finally {pending.close();}
    }
    @Test void prepareCopiesMutableInputsAndPreservesPhysicalLayout()throws Exception {
        var components=new ArrayList<>(full.components());var map=new java.util.HashMap<String,MethodHandle>();map.put("()V",MethodHandles.publicLookup().findStatic(String.class,"valueOf",java.lang.invoke.MethodType.methodType(String.class,int.class)));
        var members=new RecordSchema.Members(components,List.of(),List.of(),List.of(),map,Map.of(),Map.of());
        components.clear();map.clear();
        try(var next=slot.prepare(members)){assertTrue(next.commit());}
        assertEquals(2,slot.current().members().components().size());assertEquals(1,slot.current().members().constructorTargets().size());
        try(var remove=slot.prepare(one)){assertTrue(remove.commit());}
        assertEquals(2,slot.physicalComponents().size());assertThrows(UnsupportedOperationException.class,()->slot.current().members().components().clear());
    }
    @Test void invalidTargetDoesNotChangeTheCaller()throws Throwable {
        var site=RecordSchema.callerSite(getClass(),slot,"typed",target("old"));
        try(var next=slot.prepare(one)) {
            assertThrows(IllegalArgumentException.class,()->next.stage(site,MethodHandles.constant(int.class,1)));
            assertEquals("old",call(site));
        }
        assertThrows(IllegalArgumentException.class,()->RecordSchema.slot(String.class));
    }
    @Test void concurrentHistoryPruningNeverRestoresPreCommitTarget()throws Throwable {
        var site=RecordSchema.callerSite(getClass(),slot,"pruning",target("v0"));
        try(var first=slot.prepare(one)){first.stage(site,target("v1"));assertTrue(first.commit());}
        var running=new AtomicBoolean(true);var failure=new AtomicReference<Throwable>();
        var started=new java.util.concurrent.CountDownLatch(1);
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        Thread reader=new Thread(()->{try{while(running.get()){
            if(call(site).equals("v0"))throw new AssertionError("pre-commit target revived");
            reads.incrementAndGet();started.countDown();
        }}catch(Throwable t){failure.set(t);started.countDown();}},"record-history-reader");
        reader.start();
        try {
            assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS),"the reader must actually execute");
            for(int i=2;i<500;i++)try(var next=slot.prepare(full)){next.stage(site,target("v"+i));assertTrue(next.commit());}
        }
        finally {running.set(false);reader.join(5000);}
        assertFalse(reader.isAlive());assertNull(failure.get());assertTrue(reads.get()>0);assertEquals("v499",call(site));
    }
    static double mix(int a,long b,double c,Object d){return a+b+c+d.toString().length();}
    static String join(String... values){return String.join("/",values);}
    @Test void dispatchKeepsExactPrimitiveAndVarargsSignatures()throws Throwable {
        var lookup=MethodHandles.lookup();
        var mixed=lookup.findStatic(getClass(),"mix",java.lang.invoke.MethodType.methodType(double.class,int.class,long.class,double.class,Object.class));
        var site=RecordSchema.callerSite(getClass(),slot,"mixed",mixed);
        assertEquals(8.5d,(double)site.dispatcher().invokeExact(1,2L,3.5d,(Object)"ab"));
        var joined=lookup.findStatic(getClass(),"join",java.lang.invoke.MethodType.methodType(String.class,String[].class));
        var varargs=RecordSchema.callerSite(getClass(),slot,"varargs",joined);
        assertTrue(varargs.dispatcher().isVarargsCollector());assertEquals("a/b",varargs.dispatcher().invokeWithArguments("a","b"));
    }
    static class Untrusted {
        static Object steal(){return RecordSchema.slot(Pair.class);}
        static Object read(RecordSchema.Slot slot){return slot.current();}
    }
    @Test void sealedPublicationCapabilitiesRemainEngineInternal() {
        LookupCapture.trust(getClass());
        try {
            LookupCapture.seal();
            assertThrows(SecurityException.class,Untrusted::steal);
            assertThrows(SecurityException.class,()->Untrusted.read(slot));
            assertSame(slot,RecordSchema.slot(Pair.class));
        } finally {LookupCapture.unsealForTests();}
    }
}
