/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.example.reclazzprobe;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic live-acceptance fixture; intentionally has no Spring stereotype. */
public class DefaultR29Service {
    private static final AtomicInteger IDS=new AtomicInteger();
    private final int instanceId=IDS.incrementAndGet();
    private int calls;
    public static int getCreatedCount(){return IDS.get();}
    public int getInstanceId(){return instanceId;}
    public int getCalls(){return calls;}
    // R29_TRANSACTION
    public Map<String,Object> read(){
        long frames=StackWalker.getInstance().walk(s->s.filter(f->f.getClassName().equals("org.springframework.transaction.interceptor.TransactionInterceptor")&&f.getMethodName().equals("invoke")).count());
        return Map.of("active",TransactionSynchronizationManager.isActualTransactionActive(),"transaction",String.valueOf(TransactionSynchronizationManager.getCurrentTransactionName()),"frames",frames,"version","base","calls",++calls);
    }
}
