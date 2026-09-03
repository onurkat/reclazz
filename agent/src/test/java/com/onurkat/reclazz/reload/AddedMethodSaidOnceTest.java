/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A warning that is true every time is information only the first time.
 *
 * <p>An added method the frameworks cannot see stays added, so the warning
 * about it is correct on every reload of that class, and correct is not the
 * same as worth printing. Measured over one integration run: the same two
 * getters on one class were named fifty-four times, four other methods
 * thirty-six to thirty-eight times each, and every repeated warning in the
 * whole log was this one. A developer editing a class all afternoon would get
 * the same sentence on every save.
 *
 * <p>Worth recording that this was a rule the same session had already applied
 * twice, to the class-file-version message and to the exclusion message, and
 * then not applied to this one. Saying it once is the habit; the habit is what
 * slipped.
 */
class AddedMethodSaidOnceTest {

    @BeforeEach
    void freshSession() {
        AddedMethodVisibility.resetForTests();
    }

    @Test
    void aMethodIsNamedOnceAndNotAgain() {
        assertTrue(AddedMethodVisibility.sayOnce("app.Dto", "getEmail()"));

        assertFalse(AddedMethodVisibility.sayOnce("app.Dto", "getEmail()"));
        assertFalse(AddedMethodVisibility.sayOnce("app.Dto", "getEmail()"));
    }

    @Test
    void anotherMethodOnTheSameClassIsItsOwnFirstTime() {
        AddedMethodVisibility.sayOnce("app.Dto", "getEmail()");

        assertTrue(AddedMethodVisibility.sayOnce("app.Dto", "getPhone()"),
                "a different method is a different thing to know");
    }

    @Test
    void theSameMethodNameOnAnotherClassIsAlsoItsOwn() {
        AddedMethodVisibility.sayOnce("app.Dto", "getEmail()");

        assertTrue(AddedMethodVisibility.sayOnce("app.Other", "getEmail()"));
    }

    /** A new session starts saying things again, which is what a restart is. */
    @Test
    void aFreshSessionSaysItAgain() {
        AddedMethodVisibility.sayOnce("app.Dto", "getEmail()");
        AddedMethodVisibility.resetForTests();

        assertTrue(AddedMethodVisibility.sayOnce("app.Dto", "getEmail()"));
    }

    /**
     * The memory is bounded, and past the bound it prefers repeating itself to
     * forgetting: a session that has added five hundred methods the frameworks
     * cannot see is not one to go quiet on.
     */
    @Test
    void pastItsBoundItKeepsSpeaking() {
        for (int i = 0; i < 600; i++) {
            AddedMethodVisibility.sayOnce("app.Class" + i, "method()");
        }

        assertTrue(AddedMethodVisibility.sayOnce("app.Late", "method()"));
        assertTrue(AddedMethodVisibility.sayOnce("app.Late", "method()"),
                "past the bound it says it again rather than pretending it already had");
    }
}
