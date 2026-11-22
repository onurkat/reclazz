/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.example.reclazzprobe;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.DisposableBean;

/** The existing native singleton must keep its original prototype across XML saves. */
public class R30PrototypeHolder implements DisposableBean {
    private static final AtomicInteger IDS=new AtomicInteger(),CLOSED=new AtomicInteger();
    private final int id=IDS.incrementAndGet();
    private final R30PrototypeValue value;
    public R30PrototypeHolder(R30PrototypeValue value){this.value=value;}
    public R30PrototypeValue getValue(){return value;}
    public int getId(){return id;}
    public static int closed(){return CLOSED.get();}
    public void destroy(){CLOSED.incrementAndGet();}
}
