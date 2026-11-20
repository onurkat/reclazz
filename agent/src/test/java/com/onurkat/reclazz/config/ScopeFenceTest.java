/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.config;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ScopeFenceTest {
    @Test void admittedRunDefersTransitionUntilItsFinalPermitIsReleased() {
        var fence=new ScopeFence();var wakes=new AtomicInteger();
        var first=fence.tryEnterRun(()->fail("not deferred"));
        var second=fence.tryEnterRun(()->fail("not deferred"));
        assertFalse(fence.tryCloseOrRegister(wakes::incrementAndGet));assertEquals(0,wakes.get());
        first.close();assertEquals(0,wakes.get());second.close();second.close();assertEquals(1,wakes.get());
        assertTrue(fence.tryCloseOrRegister(()->fail("no active run")));fence.reopen();
    }
    @Test void closedFenceCoalescesNewAdmissionsAndReopensOutsideItsLock()throws Exception {
        var fence=new ScopeFence();assertTrue(fence.tryCloseOrRegister(()->fail("no active run")));
        var retried=new AtomicInteger();Runnable retry=()->{
            assertFalse(Thread.holdsLock(fence));
            try(var permit=fence.tryEnterRun(()->fail("reopen must precede callback"))){assertNotNull(permit);retried.incrementAndGet();}
        };
        assertNull(fence.tryEnterRun(retry));assertNull(fence.tryEnterRun(retry));assertEquals(0,retried.get());
        fence.reopen();assertEquals(1,retried.get());fence.reopen();assertEquals(1,retried.get());
    }
    @Test void pendingTransitionRetainsAdmissionPriorityAcrossTheWakeQueueGap() {
        var fence=new ScopeFence();var permit=fence.tryEnterRun(()->{});var wakes=new AtomicInteger();
        assertFalse(fence.tryCloseOrRegister(wakes::incrementAndGet));permit.close();assertEquals(1,wakes.get());
        var retry=new AtomicInteger();assertNull(fence.tryEnterRun(retry::incrementAndGet));
        assertTrue(fence.tryCloseOrRegister(()->fail("the released permit is not active")));
        fence.reopen();assertEquals(1,retry.get());
    }
    @Test void releaseBeforeRegistrationClosesImmediatelyAndDoesNotNeedAWake() {
        var fence=new ScopeFence();fence.tryEnterRun(()->{}).close();
        assertTrue(fence.tryCloseOrRegister(()->fail("release already happened")));fence.reopen();
    }
    @Test void concurrentReleaseAndRegistrationNeverLoseTheWake()throws Exception {
        for(int i=0;i<40;i++){
            var fence=new ScopeFence();var permit=fence.tryEnterRun(()->{});var wakes=new AtomicInteger();var start=new CyclicBarrier(2);
            var close=CompletableFuture.supplyAsync(()->{try{start.await();return fence.tryCloseOrRegister(()->{assertFalse(Thread.holdsLock(fence));wakes.incrementAndGet();});}catch(Exception e){throw new CompletionException(e);}});
            start.await(2,TimeUnit.SECONDS);permit.close();
            boolean immediate=close.get(2,TimeUnit.SECONDS);
            assertEquals(immediate?0:1,wakes.get());
            if(!immediate)assertTrue(fence.tryCloseOrRegister(()->fail("all permits released")));
            fence.reopen();
        }
    }
}
