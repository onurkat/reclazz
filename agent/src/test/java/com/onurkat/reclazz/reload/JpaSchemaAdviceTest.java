/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What to do about a field the persistence mapping did not pick up.
 *
 * <p>The first version of that message told everyone the same thing, restart
 * and add the column, and that is right for one configuration out of three.
 * Measured on Spring Boot 3.3 with Hibernate, changing the same entity three
 * times with only the schema setting different.
 *
 * <p>The one that matters most is validate: a restart there does not fix
 * anything, it stops the application from starting, because Hibernate compares
 * the mapping against the database on the way up and refuses when a mapped
 * column is missing. A developer told to "restart" walks into that.
 */
class JpaSchemaAdviceTest {

    @Test
    void withUpdateARestartIsTheWholeFix() {
        String advice = JpaSchemaAdvice.adviceFor("update");

        assertTrue(advice.contains("A restart is enough"), advice);
        assertFalse(advice.contains("add the column"),
                "sending them to write DDL that Hibernate is about to write itself "
                + "is work for nothing");
    }

    @Test
    void createAndCreateDropAreTheSameCase() {
        assertTrue(JpaSchemaAdvice.adviceFor("create").contains("A restart is enough"));
        assertTrue(JpaSchemaAdvice.adviceFor("create-drop").contains("A restart is enough"));
    }

    /** The dangerous one: restarting first is exactly the wrong order. */
    @Test
    void withValidateTheColumnHasToComeFirst() {
        String advice = JpaSchemaAdvice.adviceFor("validate");

        assertTrue(advice.contains("refuse to start"),
                "the developer has to know a restart breaks the application here: " + advice);
        assertTrue(advice.contains("Add the column first"), advice);
    }

    @Test
    void withNoneTheColumnIsTheirs() {
        String advice = JpaSchemaAdvice.adviceFor("none");

        assertTrue(advice.contains("add the column"), advice);
        assertTrue(advice.contains("nothing creates it for you"), advice);
    }

    /**
     * Spring Boot does not publish the property when it is none, and an
     * application that configures nothing publishes nothing either. Guessing a
     * branch from silence would be how the validate warning reaches someone it
     * does not apply to.
     */
    @Test
    void anUnreadableSettingFallsBackToTheInstructionThatIsAlwaysSafe() {
        String advice = JpaSchemaAdvice.adviceFor(null);

        assertTrue(advice.contains("Restart, and add the column"), advice);
        assertFalse(advice.contains("refuse to start"),
                "claiming the application will not start, when that was never read, "
                + "would send someone hunting a problem they do not have");
    }

    /** An unfamiliar value is not a reason to invent advice. */
    @Test
    void anUnknownSettingIsTreatedAsCreatingNothing() {
        String advice = JpaSchemaAdvice.adviceFor("something-new");

        assertTrue(advice.contains("add the column"), advice);
    }
}
