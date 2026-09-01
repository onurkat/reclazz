/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hiding a removed member, and taking the hiding back off.
 *
 * <p>Probing where the store actually lives, because the round trip failed in
 * a release and had no unit-level witness. The root-level filter needs the
 * JDK's own maps and refuses a throwaway classloader, so a reload test cannot
 * observe it; the store underneath it has neither restriction, and it is what
 * every instrumented call site reads.
 */
class RemovedMemberHidingTest {

    static class Sample {
        public String gone() {
            return "gone";
        }

        public String stays() {
            return "stays";
        }
    }

    @Test
    void aHiddenMethodIsNotAnsweredAndComesBackWhenUnhidden() throws Exception {
        assertNotNull(ReflectionBridge.getDeclaredMethod(Sample.class, "gone"));

        ReflectionBridge.hideRemovedMembers(Sample.class, Set.of(), Set.of("gone"));
        assertThrows(NoSuchMethodException.class,
                () -> ReflectionBridge.getDeclaredMethod(Sample.class, "gone"),
                "a scan asking through the bridge must stop seeing a removed member");
        assertNotNull(ReflectionBridge.getDeclaredMethod(Sample.class, "stays"),
                "and must keep seeing the ones that survived");

        ReflectionBridge.unhideRestoredMembers(Sample.class, Set.of(), Set.of("gone"));
        assertNotNull(ReflectionBridge.getDeclaredMethod(Sample.class, "gone"),
                "bringing the name back has to undo it, or the scan that maps an "
                + "endpoint cannot see the handler a save just restored");
    }

    /** Unhiding a name that was never hidden is what a declared-name sweep does. */
    @Test
    void unhidingANameThatWasNeverHiddenChangesNothing() throws Exception {
        ReflectionBridge.unhideRestoredMembers(Sample.class, Set.of(), Set.of("stays"));

        assertNotNull(ReflectionBridge.getDeclaredMethod(Sample.class, "stays"));
    }
}
