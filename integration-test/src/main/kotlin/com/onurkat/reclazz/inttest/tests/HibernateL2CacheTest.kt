/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult

class HibernateL2CacheTest(
    config: TestConfig,
    agentClient: AgentEventClient,
    httpVerifier: HttpVerifier,
) : BaseTest("Hibernate L2 cache", config, agentClient, httpVerifier) {

    override fun run(): TestResult = result(
        System.currentTimeMillis(), com.onurkat.reclazz.inttest.report.TestStatus.SKIP,
        "The Commerce fixture has no Hibernate ORM SessionFactory/L2 provider. " +
            "A DAO constant is not cache evidence; run the separate Hibernate L2 regression suite."
    )
}
