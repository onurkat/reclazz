/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

/** The validated payload; its field carries the synthetic constraint. */
public class Bean {
    @ProbeConstraint
    public final String field;

    public Bean(String field) { this.field = field; }
}
