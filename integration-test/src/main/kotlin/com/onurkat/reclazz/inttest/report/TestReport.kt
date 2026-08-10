/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.inttest.report

enum class TestStatus {
    PASS, FAIL, ERROR, SKIP
}

data class TestResult(
    val name: String,
    val status: TestStatus,
    val durationMs: Long,
    val notes: String = ""
)

class TestReport {
    private val results = mutableListOf<TestResult>()

    fun add(result: TestResult) {
        results.add(result)
    }

    fun print() {
        println()
        println("=== Reclazz Integration Test Report ===")
        println("%-40s | %-7s | %-10s | %s".format("Test", "Status", "Duration", "Notes"))
        println("-".repeat(40) + "-+-" + "-".repeat(7) + "-+-" + "-".repeat(10) + "-+-" + "-".repeat(30))

        for (r in results) {
            val statusStr = when (r.status) {
                TestStatus.PASS -> "\u001B[32mPASS\u001B[0m   "
                TestStatus.FAIL -> "\u001B[31mFAIL\u001B[0m   "
                TestStatus.ERROR -> "\u001B[31mERROR\u001B[0m  "
                TestStatus.SKIP -> "\u001B[33mSKIP\u001B[0m   "
            }
            val duration = "%.1fs".format(r.durationMs / 1000.0)
            println("%-40s | %s | %-10s | %s".format(r.name, statusStr, duration, r.notes))
        }

        val passed = results.count { it.status == TestStatus.PASS }
        val total = results.size
        println()
        println("Total: $passed/$total passed (${if (total > 0) passed * 100 / total else 0}%)")
        println()
    }

    fun hasFailures(): Boolean = results.any { it.status != TestStatus.PASS && it.status != TestStatus.SKIP }
}
