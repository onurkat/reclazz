/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.example.reclazzprobe;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.InitializingBean;

/** Synthetic future-creation counters for a native XML prototype definition. */
public class R30PrototypeValue implements InitializingBean {
    private static final AtomicInteger IDS=new AtomicInteger(),INITIALIZED=new AtomicInteger();
    private final int id=IDS.incrementAndGet();
    private String label;
    public void setLabel(String value){label=value;}
    public void afterPropertiesSet(){INITIALIZED.incrementAndGet();label+=":init";}
    public String getLabel(){return label;}
    public int getId(){return id;}
    public static int constructed(){return IDS.get();}
    public static int initialized(){return INITIALIZED.get();}
}
