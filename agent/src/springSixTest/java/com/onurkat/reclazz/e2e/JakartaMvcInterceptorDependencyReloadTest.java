/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

/** Same desired native MVC lifecycle behavior on the isolated Jakarta graph. */
class JakartaMvcInterceptorDependencyReloadTest extends MvcInterceptorDependencyReloadTest {
    @Override protected String servletNamespace() { return "jakarta"; }
}
