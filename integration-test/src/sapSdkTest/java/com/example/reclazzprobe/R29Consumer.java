/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.example.reclazzprobe;

import java.util.concurrent.atomic.AtomicInteger;

/** Native constructor-consumer and rollback witness in a disposable SAP tenant factory. */
public class R29Consumer {
    private static final AtomicInteger IDS=new AtomicInteger();
    private static final AtomicInteger ATTEMPTS=new AtomicInteger();
    public static volatile boolean fail;
    public static int getConstructorAttempts(){return ATTEMPTS.get();}
    private final int instanceId;
    public final DefaultR29Service service;
    public R29Consumer(DefaultR29Service service){
        ATTEMPTS.incrementAndGet();
        if(fail)throw new IllegalStateException("R29 synthetic consumer constructor failure");
        this.service=service;instanceId=IDS.incrementAndGet();
    }
    public int getInstanceId(){return instanceId;}
}
