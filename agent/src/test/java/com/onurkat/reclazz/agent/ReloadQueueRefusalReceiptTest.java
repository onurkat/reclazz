/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.watcher.ChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReloadQueueRefusalReceiptTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void preflightRefusalHasExactFailedReceiptWithOrWithoutAnAcceptedSibling(boolean sibling) throws Exception {
        byte[] a=ReloadVerificationTest.bytes("A",1),b=ReloadVerificationTest.bytes("B",1);
        ChangeEvent denied=new ChangeEvent(Path.of("/synthetic/A.class"),ChangeEvent.Type.MODIFIED,"test","classes").withBytes(a);
        ChangeEvent accepted=new ChangeEvent(Path.of("/synthetic/B.class"),ChangeEvent.Type.MODIFIED,"test","classes").withBytes(b);
        List<String> handled=new ArrayList<>();ReloadVerification verification=new ReloadVerification(()->Map.of("A",2,"B",1));
        ReloadQueue queue=new ReloadQueue(event->{handled.add("B");verification.outcome("B",true,"accepted sibling");},new ReloadQueue.Bracket(){public void begin(){fail("one accepted sibling needs no bracket");}public void end(){fail("bracket");}},Runnable::run,
                new ReloadStall(()->1000L,ReloadQueue.STALL_WARN_MS),()->1000L,millis->{},path->a,new ReloadQueue.SavePreparer(){
                    public ReloadQueue.SavePlan prepare(List<ChangeEvent> captured){return new ReloadQueue.SavePlan(sibling?List.of(accepted):List.of(),Map.of(denied,"record cohort preflight refused"));}
                    public void complete(){handled.add("clear");}
                });
        queue.setVerification(verification);var apply=ReloadQueue.class.getDeclaredMethod("applyClassBatch",List.class);apply.setAccessible(true);apply.invoke(queue,List.of(denied,accepted));
        var result=ReloadVerificationTest.result(verification,"A",a);assertEquals("failed",result.path("status").asText());
        assertEquals(ReloadVerification.sha256(a),result.path("observedSha256").asText());assertEquals("record cohort preflight refused",result.path("detail").asText());
        assertEquals(sibling?List.of("B","clear"):List.of("clear"),handled);
        if(sibling)assertEquals("applied",ReloadVerificationTest.state(verification,"B",b));
    }
    @Test void ordinaryPreflightRefusalChangesOnlyTheReceiptAndNeverCallsHandlerOrBracket()throws Exception {
        byte[] bytes=ReloadVerificationTest.bytes("Plain",1);
        ChangeEvent event=new ChangeEvent(Path.of("/synthetic/Plain.class"),ChangeEvent.Type.MODIFIED,"test","classes").withBytes(bytes);
        var verification=new ReloadVerification(()->Map.of("Plain",1));
        ReloadQueue queue=new ReloadQueue(e->fail("ordinary refused class cannot mutate"),new ReloadQueue.Bracket(){public void begin(){fail("bracket");}public void end(){fail("bracket");}},Runnable::run,
                new ReloadStall(()->1000L,ReloadQueue.STALL_WARN_MS),()->1000L,millis->{},path->bytes,new ReloadQueue.SavePreparer(){
                    public ReloadQueue.SavePlan prepare(List<ChangeEvent> captured){return new ReloadQueue.SavePlan(List.of(),Map.of(event,"ordinary switch preflight refused"));}
                    public void complete(){}
                });queue.setVerification(verification);
        var apply=ReloadQueue.class.getDeclaredMethod("applyClassBatch",List.class);apply.setAccessible(true);apply.invoke(queue,List.of(event));
        assertEquals("failed",ReloadVerificationTest.state(verification,"Plain",bytes));
    }
    @Test void compiledRefusalGetsOneDiagnosticAndOneActiveVerificationReceipt()throws Exception {
        var diagnosticsField=ReclazzAgent.class.getDeclaredField("diagnostics");diagnosticsField.setAccessible(true);
        var verificationField=ReclazzAgent.class.getDeclaredField("verification");verificationField.setAccessible(true);
        Object previousDiagnostics=diagnosticsField.get(null),previousVerification=verificationField.get(null);
        var diagnostics=new ReloadDiagnostics(null,null,null,java.time.Instant.EPOCH);
        var verification=new ReloadVerification(()->Map.of("Plain",1));byte[] bytes=ReloadVerificationTest.bytes("Plain",1);
        var outcome=ReclazzAgent.class.getDeclaredMethod("recordOutcome",String.class,boolean.class,String.class,String.class);outcome.setAccessible(true);
        var refusals=ReclazzAgent.class.getDeclaredMethod("recordCompiledRefusals",Map.class,Map.class);refusals.setAccessible(true);
        var outcomesField=ReloadDiagnostics.class.getDeclaredField("outcomes");outcomesField.setAccessible(true);
        try {
            diagnosticsField.set(null,diagnostics);verificationField.set(null,verification);
            outcome.invoke(null,"Plain",false,"preflight refused","synthetic.java");
            Map<?,?> outcomes=(Map<?,?>)outcomesField.get(diagnostics);Object once=outcomes.get("Plain");assertNotNull(once);
            verification.apply(Map.of("Plain",bytes),Map.of("Plain","synthetic.java"),()->{
                try{refusals.invoke(null,Map.of("Plain","preflight refused"),Map.of("Plain","synthetic.java"));}
                catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            });
            assertSame(once,outcomes.get("Plain"),"the verification bracket must not record diagnostics a second time");
            assertEquals("failed",ReloadVerificationTest.state(verification,"Plain",bytes));
        }finally{diagnosticsField.set(null,previousDiagnostics);verificationField.set(null,previousVerification);}
    }
    @Test void preparationExceptionStillReleasesItsScope() throws Exception {
        List<String> log=new ArrayList<>();
        ReloadQueue queue=new ReloadQueue(event->fail("handler"),new ReloadQueue.Bracket(){public void begin(){fail("bracket");}public void end(){fail("bracket");}},Runnable::run,
                new ReloadStall(()->1000L,ReloadQueue.STALL_WARN_MS),()->1000L,millis->{},path->new byte[]{1},new ReloadQueue.SavePreparer(){
                    public ReloadQueue.SavePlan prepare(List<ChangeEvent> captured){throw new IllegalStateException("preflight failure");}
                    public void complete(){log.add("clear");}
                });
        var apply=ReloadQueue.class.getDeclaredMethod("applyClassBatch",List.class);apply.setAccessible(true);
        var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->apply.invoke(queue,List.of()));
        assertEquals("preflight failure",failure.getCause().getMessage());assertEquals(List.of("clear"),log);
    }
}
