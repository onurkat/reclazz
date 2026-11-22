/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StaticReferenceInspectionTest {
    static boolean initialized;
    static class Cold { static Object value=initialize();static Object initialize(){initialized=true;return new Object();} }
    @Test void readingColdReferenceDoesNotInitializeTheHolder()throws Exception {
        assertFalse(initialized);assertNull(StaticReferenceInspection.read(Cold.class.getDeclaredField("value")));assertFalse(initialized);
        assertNotNull(Cold.value);assertTrue(initialized);
    }
    @Test void sealedEngineRejectsAnUntrustedReader()throws Exception {
        LookupCapture.seal();try{assertThrows(SecurityException.class,()->StaticReferenceInspection.read(Cold.class.getDeclaredField("value")));}finally{LookupCapture.unsealForTests();}
    }
}
