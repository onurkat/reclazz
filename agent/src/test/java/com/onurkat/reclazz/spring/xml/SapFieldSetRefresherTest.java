/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SapFieldSetRefresherTest {
    @AfterEach void clear() { ApplicationContextHolder.clear(); }

    @Test void ordinaryObjectsCannotTriggerAnOccRefresh() {
        assertFalse(SapFieldSetRefresher.isMapping(null));
        assertFalse(SapFieldSetRefresher.isMapping(new Object()));
        // An empty mutation set must not inspect or invoke the context at all.
        assertTrue(SapFieldSetRefresher.refresh(new Object(), Set.of()).isEmpty());
    }

    @Test void aContextWithoutTheSapSdkDoesNotLoadOrChangeOtherBeans() {
        try (var context = new GenericApplicationContext()) {
            Object original = new Object();
            context.getBeanFactory().registerSingleton("original", original);
            context.refresh(); ApplicationContextHolder.register(context);
            assertTrue(SapFieldSetRefresher.refresh(context, Set.of(original)).isEmpty());
            assertSame(original, context.getBean("original"));
        }
    }

    @Test void aContextInspectionFailureIsReportedRatherThanSwallowed() {
        var failures = SapFieldSetRefresher.refresh(new Object(), Set.of(new Object()));
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).startsWith("OCC field selection refresh failed:"));
    }
}
