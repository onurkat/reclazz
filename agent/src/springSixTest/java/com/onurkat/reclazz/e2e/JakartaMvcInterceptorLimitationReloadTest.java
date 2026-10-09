/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

/** Same documented limitation on the isolated Jakarta graph. */
class JakartaMvcInterceptorLimitationReloadTest extends MvcInterceptorLimitationReloadTest {
    @Override protected String servletNamespace() { return "jakarta"; }
}
