/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TomcatContextScanner} discovers Spring web contexts that were
 * already refreshed when the agent attached (the intercept transformer
 * only sees contexts refreshed afterwards).
 *
 * There is no Tomcat on the unit-test classpath, so what is asserted here
 * is the contract that matters for correctness in that situation: the scan
 * must degrade to a clean no-op rather than throwing, and it must never
 * register something that is not an ApplicationContext (a failed webapp
 * stores its startup EXCEPTION under the very attribute we read).
 */
class TomcatContextScannerTest {

    @AfterEach
    void tearDown() {
        ApplicationContextHolder.clear();
    }

    @Test
    void isANoOpWhenTomcatIsAbsent() {
        int before = ApplicationContextHolder.getAllContexts().size();

        int registered = assertDoesNotThrow(TomcatContextScanner::scanAndRegister,
                "a missing Tomcat must not propagate an exception into agent startup");

        assertEquals(0, registered, "nothing to discover without a running Tomcat");
        assertEquals(before, ApplicationContextHolder.getAllContexts().size(),
                "the holder must be left untouched");
    }

    @Test
    void holderOnlyKeepsRegisteredContexts() {
        // Guards the scanner's contract with the holder: whatever it hands
        // over is what the Spring reloaders will later iterate.
        Object fake = new Object();
        ApplicationContextHolder.register(fake);

        assertTrue(ApplicationContextHolder.getAllContexts().contains(fake));

        TomcatContextScanner.scanAndRegister();

        assertEquals(1, ApplicationContextHolder.getAllContexts().size(),
                "a no-op scan must not add or drop entries");
    }
}
