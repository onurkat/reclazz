/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.util;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reading somebody else's source file when you do not know its encoding.
 *
 * <p>{@code Files.readString} is UTF-8 whatever the machine, which is the right
 * default and not a safe assumption about a file this agent did not write. A
 * Java source saved as ISO-8859-1 or windows-1254, which is ordinary in a
 * long-lived enterprise repository, makes it throw
 * {@code MalformedInputException: Input length = 1}. That is the whole message.
 *
 * <p>Two things read source files here and neither shows the text to anyone:
 * one looks for the package declaration, the other for a mention of a changed
 * constant. Both only care about ASCII, so a file that is not UTF-8 is decoded
 * as ISO-8859-1 instead, which maps every byte to a character and cannot fail.
 * The accented letters may come out wrong; the package name and the pattern
 * match do not, and those are the questions being asked.
 *
 * <p>Deliberately not used for ImpEx. That content is re-encoded and handed to
 * the platform's import service, so a wrong guess would put wrong characters in
 * the database. There, a file that is not UTF-8 is refused and said so.
 */
public final class SourceText {

    private SourceText() {
    }

    /**
     * The file's text, for a scan that only reads ASCII out of it.
     *
     * @throws IOException if the file cannot be read at all, which is a
     *                     different problem from how it is encoded
     */
    public static String readForScanning(Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException notUtf8) {
            // Every byte is a character in ISO-8859-1, so this cannot throw.
            return Files.readString(file, StandardCharsets.ISO_8859_1);
        }
    }
}
