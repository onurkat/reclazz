/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ui;

/**
 * Counting things out loud.
 *
 * <p>Thirty-eight messages in this agent were written as "1 method(s)", which
 * is the shape a developer writes when the count is a variable and the sentence
 * is an afterthought. It is legible, and it is also the clearest signal in the
 * output that nobody read it back. Watched live in the IDE, a one-method edit
 * announced itself as {@code Structural reload: app.Greeter (v1, +1 method(s))}
 * beside a status bar reading {@code Reclazz: 1 reloads}: two surfaces, one
 * event, both ungrammatical about the smallest number there is.
 *
 * <p>The count decides the word: one is singular, everything else including
 * zero is plural. Irregular nouns pass their own plural, because "propertys" is
 * worse than the parenthesis ever was.
 */
public final class Plural {

    private Plural() {}

    /** {@code of(1, "method")} is "1 method", {@code of(0, "method")} is "0 methods". */
    public static String of(long count, String singular) {
        return count + " " + word(count, singular, singular + "s");
    }

    /** For nouns that do not take a plain s: {@code of(1, "property", "properties")}. */
    public static String of(long count, String singular, String plural) {
        return count + " " + word(count, singular, plural);
    }

    /** The noun alone, for sentences that carry the number somewhere else. */
    public static String word(long count, String singular, String plural) {
        return count == 1 ? singular : plural;
    }

    /** The noun alone, regular. */
    public static String word(long count, String singular) {
        return word(count, singular, singular + "s");
    }
}
