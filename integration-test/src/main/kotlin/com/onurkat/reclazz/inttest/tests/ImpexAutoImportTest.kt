/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult
import com.onurkat.reclazz.inttest.report.TestStatus

class ImpexAutoImportTest(
    config: TestConfig,
    agentClient: AgentEventClient,
    httpVerifier: HttpVerifier,
) : BaseTest("ImpEx auto-import", config, agentClient, httpVerifier) {

    /**
     * Importing an ImpEx is not synchronous with saving one. The agent hands
     * the file to the platform's own ImpEx cronjob, which runs on its own
     * schedule, so "the agent reported the import" and "the data is readable"
     * are two different moments.
     *
     * This test used to sleep for a fixed settle delay and read once. It
     * usually worked and then reported `Expected 'Reclazz Test Title v2', got
     * 'Reclazz Test Title v1'` on a run where the import was in the log twice,
     * five seconds apart: the read had simply landed between them. A fixed
     * sleep against an asynchronous job is a coin toss whose odds change with
     * the machine.
     *
     * So it waits for the agent to say it imported, which is the signal the
     * rest of the suite is built on, and then polls the value rather than
     * guessing when it will be there. A slow import costs seconds; it no
     * longer costs a false failure.
     */
    override fun run(): TestResult {
        val start = System.currentTimeMillis()
        return try {
            agentClient.clearEvents()
            writeTemplate(
                "${config.impexDir}/test-data.impex",
                "test-data_v2.impex.txt"
            )

            // ImpEx does not produce a COMPILE or RELOAD event; the agent says
            // so in its own words when the import has been handed over.
            val imported = agentClient.waitForEvent("OK", config.eventTimeoutMs, "ImpEx imported")
                ?: return result(start, TestStatus.FAIL,
                    "Timeout waiting for the agent to report the ImpEx import")

            val deadline = System.currentTimeMillis() + IMPORT_VISIBLE_TIMEOUT_MS
            var lastBody: String? = null
            while (System.currentTimeMillis() < deadline) {
                lastBody = httpVerifier.get("${config.testEndpointBase}/impex-title").body
                if (lastBody == EXPECTED_TITLE) {
                    return result(start, TestStatus.PASS)
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }

            result(start, TestStatus.FAIL,
                "Imported ('${imported.message}') but '$EXPECTED_TITLE' was not readable within " +
                    "${IMPORT_VISIBLE_TIMEOUT_MS}ms; last read '$lastBody'")
        } catch (e: Exception) {
            result(start, TestStatus.ERROR, e.message ?: e.toString())
        }
    }

    private companion object {
        const val EXPECTED_TITLE = "Reclazz Test Title v2"

        /**
         * Generous on purpose. The cost of waiting too long is a slower suite;
         * the cost of waiting too little is a failure that says the product is
         * broken when it is not, which is the more expensive mistake.
         */
        const val IMPORT_VISIBLE_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 500L
    }
}
