/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.inttest.tests

internal fun legacyStatusGreeting(message: String): String = batchJson.writeValueAsString(
    mapOf("level" to "CONNECTED", "message" to message, "version" to 1, "timestamp" to "contract")
)
