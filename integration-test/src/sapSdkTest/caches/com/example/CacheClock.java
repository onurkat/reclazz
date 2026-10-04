/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import com.google.common.base.Ticker;
public class CacheClock extends Ticker {
    private static long now = 1000;
    public long read() { return now; }
    public static long now() { return now; }
    public static void expire() { now += java.util.concurrent.TimeUnit.SECONDS.toNanos(61); }
}
