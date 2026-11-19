/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import org.junit.jupiter.api.Test;
class WatcherJarReplacedIsReportedTest extends WatcherDeathIsReportedTest {
    @Test void overwrittenAgentJarIsReported() throws Exception { exercise(true); }
}
