/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package de.hybris.platform.core;
public record PK(long value) {
    public static PK parse(String text) { return new PK(Long.parseLong(text)); }
    public long getLongValue() { return value; }
    @Override public String toString() { return Long.toString(value); }
}
