/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

/** Same status/body, selector, priority and five-save assertions on Spring 6. */
class JakartaAddedExceptionHandlerReloadTest extends AddedExceptionHandlerReloadTest {
    @Override protected String servletNamespace() { return "jakarta"; }
}
