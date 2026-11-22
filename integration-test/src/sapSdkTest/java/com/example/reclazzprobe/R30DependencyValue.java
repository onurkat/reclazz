/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.example.reclazzprobe;
import org.springframework.beans.factory.DisposableBean;
import java.util.*;
public final class R30DependencyValue implements DisposableBean {
    private static final List<String> order=new ArrayList<>();private static int closed;private R30DependencyFirst first;
    public R30DependencyValue(){record("T");}public void setFirst(R30DependencyFirst value){first=value;}public R30DependencyFirst getFirst(){return first;}
    public static synchronized void record(String value){order.add(value);}public static synchronized List<String> order(){return List.copyOf(order);}public static synchronized int closed(){return closed;}
    public void destroy(){synchronized(R30DependencyValue.class){closed++;}}
}
