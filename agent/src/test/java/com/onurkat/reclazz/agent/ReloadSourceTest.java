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

    @Test
    void aTopLevelClassIsTracedToTheFileNamedAfterIt() {
        assertEquals("/src/demo/Order.java", ReclazzAgent.sourceOf("demo.Order", BATCH));
    }

    @Test
    void anInnerClassIsTracedToItsOuterClassesFile() {
        assertEquals("/src/demo/Cart.java", ReclazzAgent.sourceOf("demo.Cart$Line", BATCH));
        assertEquals("/src/demo/Cart.java", ReclazzAgent.sourceOf("demo.Cart$1", BATCH));
    }

    @Test
    void aClassDeclaredInAnotherClassesFileNamesTheOnlyFileWhenThereIsOne() {
        assertEquals("/src/demo/Cart.java",
                ReclazzAgent.sourceOf("demo.CartHelper", List.of(Path.of("/src/demo/Cart.java"))));
    }

    @Test
    void aClassDeclaredInAnotherClassesFileNamesTheBatchRatherThanGuessing() {
        assertEquals("one of 2 source files compiled together",
                ReclazzAgent.sourceOf("demo.CartHelper", BATCH));
    }
}
