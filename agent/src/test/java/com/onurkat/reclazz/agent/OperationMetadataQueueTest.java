/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.agent;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class OperationMetadataQueueTest {
    @Test void pauseAndBuildHoldDeferRepair(){
        AtomicInteger repairs=new AtomicInteger();
        ReloadQueue queue=new ReloadQueue(e->{},new ReloadQueue.Bracket(){public void begin(){}public void end(){}},Runnable::run,
            new ReloadStall(()->0L,ReloadQueue.STALL_WARN_MS),()->0L,ms->{});
        queue.control("pause",()->{});queue.submitAutomatic("operation-metadata-repair","repair",repairs::incrementAndGet);assertEquals(0,repairs.get());
        queue.control("resume",()->{});assertEquals(1,repairs.get());
        queue.build("started",()->{});queue.submitAutomatic("operation-metadata-repair","repair",repairs::incrementAndGet);assertEquals(1,repairs.get());
        queue.build("ok",()->{});assertEquals(2,repairs.get());queue.shutdown();
    }
    @Test void stoppedStatusTimerRejectsRepair(){
        StatusServer server=new StatusServer(0,null);server.stop();
        assertThrows(java.util.concurrent.RejectedExecutionException.class,()->server.scheduleOperationRepair(()->fail("stopped timer ran"),1));
    }
}
