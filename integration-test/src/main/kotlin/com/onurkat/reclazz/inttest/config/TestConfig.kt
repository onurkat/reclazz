/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.inttest.config

data class TestConfig(
    val baseUrl: String = System.getenv("RECLAZZ_TEST_BASE_URL") ?: "https://localhost:9002",
    val extPath: String = System.getenv("RECLAZZ_TEST_EXT_PATH")
        ?: error("RECLAZZ_TEST_EXT_PATH must be set (path to reclazztest extension in Hybris)"),
    val portFile: String = System.getenv("RECLAZZ_TEST_PORT_FILE")
        ?: error("RECLAZZ_TEST_PORT_FILE must be set (path to agent port file)"),
    val httpTimeoutMs: Long = System.getenv("RECLAZZ_TEST_HTTP_TIMEOUT")?.toLongOrNull() ?: 10_000L,
    val eventTimeoutMs: Long = System.getenv("RECLAZZ_TEST_EVENT_TIMEOUT")?.toLongOrNull() ?: 30_000L,
    val settleDelayMs: Long = System.getenv("RECLAZZ_TEST_SETTLE_DELAY")?.toLongOrNull() ?: 2_000L,
    /**
     * Reload mode of the target JVM. "companion" (default — standard JVMs:
     * new members live on a hidden nestmate, invisible to reflection/MVC
     * scans) or "enhanced" (JBR/DCEVM: full reflective visibility).
     * Mode-sensitive tests assert the documented semantics of each mode.
     */
    val enhancedMode: Boolean = (System.getenv("RECLAZZ_TEST_MODE") ?: "companion") == "enhanced",
) {
    val testEndpointBase: String get() = "$baseUrl/reclazztest/v2/test"

    val webSrcDir: String get() = "$extPath/web/src"
    val srcDir: String get() = "$extPath/src"
    val impexDir: String get() = "$extPath/resources/impex"
}
