/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A repository older than UTF-8 being the default.
 *
 * <p>{@code Files.readString} is UTF-8 whatever the machine, which is right,
 * and is not a safe assumption about a file this agent did not write. A Java
 * source saved as ISO-8859-1, which is ordinary in a long-lived enterprise
 * repository and in every SAP Commerce project this was built for, made it
 * throw {@code MalformedInputException: Input length = 1}. Two scans read
 * source files: one looks for the package declaration, the other for a mention
 * of a changed constant, and the second answered "no mention" for every file it
 * could not decode. That is a false negative in the warning whose whole job is
 * to say which files still hold the old value of an inlined constant.
 */
class SourceTextTest {

    @TempDir
    Path tmp;

    @Test
    void aUtf8FileIsReadAsUtf8() throws IOException {
        Path file = tmp.resolve("Utf8.java");
        Files.writeString(file, "package com.acme;\n// sipariş\n", StandardCharsets.UTF_8);

        assertEquals("package com.acme;\n// sipariş\n", SourceText.readForScanning(file));
    }

    /**
     * The file that used to throw. ISO-8859-9 rather than ISO-8859-1, because
     * that is the one a Turkish repository is actually saved in and the one
     * that has the letters: 8859-1 has no dotted s, and writing this file in it
     * turned the word into ASCII and the test into nothing.
     */
    @Test
    void aTurkishSourceIsReadRatherThanRefused() throws IOException {
        Path file = tmp.resolve("Latin.java");
        Files.write(file, "package com.acme;\n// sipariş\n"
                .getBytes(java.nio.charset.Charset.forName("ISO-8859-9")));

        assertThrows(java.nio.charset.MalformedInputException.class,
                () -> Files.readString(file),
                "if this stops throwing the fallback below has nothing to be for");

        String text = SourceText.readForScanning(file);

        assertTrue(text.startsWith("package com.acme;"),
                () -> "the package declaration is ASCII and must survive: " + text);
    }

    /**
     * What the scans actually ask. The accented letters may come out wrong;
     * the answer to "does this file name that constant" does not.
     */
    @Test
    void anAsciiPatternStillMatchesInAFileThatIsNotUtf8() throws IOException {
        Path file = tmp.resolve("Dependent.java");
        Files.write(file, ("package com.acme;\n"
                + "// ürün kodu\n"
                + "class Dependent { String s = Limits.MAX_ITEMS + \"\"; }\n")
                .getBytes(java.nio.charset.Charset.forName("ISO-8859-9")));

        String text = SourceText.readForScanning(file);

        assertTrue(text.contains("MAX_ITEMS"),
                "a file that inlined the changed constant was being left out of the "
                        + "warning about exactly that, because it would not decode");
    }

    /** A file that is not there is a different problem, and still an error. */
    @Test
    void anUnreadableFileIsStillUnreadable() {
        assertThrows(NoSuchFileException.class,
                () -> SourceText.readForScanning(tmp.resolve("Absent.java")));
    }
}
