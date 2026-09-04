/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One answer to the question every framework reloader opens with.
 *
 * <p>"Find the method or field this Spring version declares somewhere up the
 * hierarchy, and open it" was written six times under five names. Four of the
 * copies let anything other than a missing member escape; one turned it into a
 * null. One did not walk at all: it used {@code getMethod}, so it saw public
 * methods inherited from interfaces that the walking copies missed, and missed
 * the private ones they found. Twelve call sites were relying on that
 * difference without anyone having written it down.
 *
 * <p>So the shared one has to be a superset of all of them, and these are the
 * cases where they disagreed.
 */
class ReflectTest {

    interface WithDefault {
        default String fromAnInterface() {
            return "default";
        }
    }

    static class Parent {
        private String hidden = "parent's";
        private String onlyOnParent() {
            return "up one";
        }
    }

    static class Child extends Parent implements WithDefault {
        private String own = "child's";
    }

    // ── methods ──────────────────────────────────────────────────────────

    @Test
    void aPrivateMethodOnASuperclassIsFound() {
        Method found = Reflect.findMethod(Child.class, "onlyOnParent");

        assertNotNull(found, "getDeclaredMethod does not look at supertypes, and the method "
                + "wanted is usually on the abstract class two levels up");
    }

    /**
     * The case the {@code getMethod} copy had and the walking copies did not.
     * Spring's own configuration types declare plenty of these.
     */
    @Test
    void aDefaultMethodOnAnInterfaceIsFoundToo() {
        assertNotNull(Reflect.findMethod(Child.class, "fromAnInterface"),
                "a superclass walk alone never reaches an interface, and twelve call sites "
                        + "were relying on the copy that did");
    }

    @Test
    void aMethodThatIsNotThereIsNullRatherThanAThrow() {
        assertNull(Reflect.findMethod(Child.class, "noSuchThing"),
                "a reloader asking whether this Spring version has a method is asking "
                        + "something it can be told no to");
        assertNull(Reflect.findMethod(Child.class, "onlyOnParent", String.class),
                "the same name with the wrong parameters is not the same method");
    }

    // ── fields ───────────────────────────────────────────────────────────

    @Test
    void aPrivateFieldIsReadFromWhereverItIsDeclared() {
        Child child = new Child();

        assertEquals("child's", Reflect.readField(child, "own"));
        assertEquals("parent's", Reflect.readField(child, "hidden"),
                "the field the reloader wants is usually the framework's, not the subclass's");
    }

    @Test
    void aFieldThatIsNotThereReadsAsNull() {
        assertNull(Reflect.readField(new Child(), "noSuchField"));
        assertNull(Reflect.readField(null, "own"), "and nothing has no fields");
    }

    /**
     * The write reports whether it happened, because a cache that was not
     * cleared is the difference between a reload landing and a reload that only
     * looks like it did.
     */
    @Test
    void aWriteSaysWhetherItWentIn() {
        Child child = new Child();

        assertTrue(Reflect.writeField(child, "hidden", "replaced"));
        assertEquals("replaced", Reflect.readField(child, "hidden"),
                "and it went to the field on the superclass, which is where it was declared");

        assertFalse(Reflect.writeField(child, "noSuchField", "x"));
        assertFalse(Reflect.writeField(null, "hidden", "x"));
    }
}
