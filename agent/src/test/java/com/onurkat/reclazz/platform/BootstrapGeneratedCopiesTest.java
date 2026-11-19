/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapGeneratedCopiesTest {
    @TempDir Path tmp;
    Path platform() { return tmp.resolve("platform"); }
    Path extension() { return tmp.resolve("extension"); }
    void source(Path path) throws Exception { Files.createDirectories(path.getParent()); Files.writeString(path, "// source ownership fixture"); }
    Path generated() { return platform().resolve("bootstrap/gensrc/example/Dto.java"); }
    Path output(String name, String source) throws Exception {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/" + name, null, "java/lang/Object", null);
        if (source != null) writer.visitSource(source, null);
        writer.visitEnd();
        Path file = extension().resolve("classes/example/" + name + ".class");
        Files.createDirectories(file.getParent()); Files.write(file, writer.toByteArray()); return file;
    }
    int clean() { return BootstrapGeneratedCopies.remove(platform(), List.of(new ExtensionInfo("example", extension(), List.of(), true, false, true))); }

    @Test void removesOnlyBootstrapSourcedCopies() throws Exception {
        source(generated());
        Path top = output("Dto", "Dto.java"), nested = output("Dto$Nested", "Dto.java"), secondary = output("Secondary", "Dto.java");
        Path unknown = output("Unknown", "Unknown.java");
        assertEquals(3, clean());
        assertFalse(Files.exists(top)); assertFalse(Files.exists(nested)); assertFalse(Files.exists(secondary));
        assertTrue(Files.exists(unknown)); assertEquals(0, clean());
    }
    @Test void keepsClassWithOwnExtensionSource() throws Exception {
        source(generated()); source(extension().resolve("gensrc/example/Dto.java"));
        Path file = output("Dto", "Dto.java"); assertEquals(0, clean()); assertTrue(Files.exists(file));
    }
    @Test void keepsAmbiguousOwnSourceLayoutAndBackofficeGensrc() throws Exception {
        source(generated()); source(extension().resolve("backoffice/gensrc/unusual/Dto.java"));
        Path file = output("Secondary", "Dto.java"); assertEquals(0, clean()); assertTrue(Files.exists(file));
    }
    @Test void keepsUnknown() throws Exception {
        source(generated()); Path noSource = output("Dto", null), malformed = output("Broken", "Dto.java");
        Files.write(malformed, new byte[]{1,2,3}); assertEquals(0, clean());
        assertTrue(Files.exists(noSource)); assertTrue(Files.exists(malformed));
    }
    @Test void keepsSymlinkedOutput() throws Exception {
        source(generated()); Path file = output("Dto", "Dto.java"), target = tmp.resolve("saved.class");
        Files.move(file, target); Files.createSymbolicLink(file, target);
        assertEquals(0, clean()); assertTrue(Files.exists(target)); assertTrue(Files.isSymbolicLink(file));
    }
}
