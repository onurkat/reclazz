/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A class compiled in memory has no class file to be traced back to, so the
 * trail names the .java file instead. javac names a top-level class's file
 * after it, inner classes included; a class declared in somebody else's file
 * is the one case the name cannot settle, and there the answer is the batch,
 * not a guess.
 */
class ReloadSourceTest {

    private static final List<Path> BATCH = List.of(
            Path.of("/src/demo/Cart.java"), Path.of("/src/demo/Order.java"));

    // sourceOf returns Path.toString(), which is the OS-native separator, so the
    // expected strings are built the same way rather than hardcoding '/' (which
    // failed on Windows, where the separator is '\').
    private static final String CART = Path.of("/src/demo/Cart.java").toString();
    private static final String ORDER = Path.of("/src/demo/Order.java").toString();

    @Test
    void aTopLevelClassIsTracedToTheFileNamedAfterIt() {
        assertEquals(ORDER, ReclazzAgent.sourceOf("demo.Order", BATCH));
    }

    @Test
    void anInnerClassIsTracedToItsOuterClassesFile() {
        assertEquals(CART, ReclazzAgent.sourceOf("demo.Cart$Line", BATCH));
        assertEquals(CART, ReclazzAgent.sourceOf("demo.Cart$1", BATCH));
    }

    @Test
    void aClassDeclaredInAnotherClassesFileNamesTheOnlyFileWhenThereIsOne() {
        assertEquals(CART,
                ReclazzAgent.sourceOf("demo.CartHelper", List.of(Path.of("/src/demo/Cart.java"))));
    }

    @Test
    void aClassDeclaredInAnotherClassesFileNamesTheBatchRatherThanGuessing() {
        assertEquals("one of 2 source files compiled together",
                ReclazzAgent.sourceOf("demo.CartHelper", BATCH));
    }
}
