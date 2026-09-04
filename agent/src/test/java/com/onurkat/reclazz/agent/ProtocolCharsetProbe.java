/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Runs inside a JVM whose default charset is not UTF-8, which is the only
 * place the question can be answered. Started by
 * {@link TheProtocolSpeaksUtf8Test}; prints one line and exits non-zero if the
 * bytes on the wire depended on the machine.
 */
public final class ProtocolCharsetProbe {

    /** Turkish letters and a German one: three bytes that differ between encodings. */
    static final String MESSAGE = "Reloaded com.acme.Sipariş (Masaüstü)";

    public static void main(String[] args) throws Exception {
        Charset here = Charset.defaultCharset();
        if (StandardCharsets.UTF_8.equals(here)) {
            System.out.println("PROBE useless: this JVM's default is already UTF-8");
            System.exit(2);
        }

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Writer writer = StatusServer.writerFor(sink);
        try (PrintWriter out = new PrintWriter(writer, true)) {
            out.println(MESSAGE);
        }
        byte[] onTheWire = sink.toByteArray();
        byte[] expected = (MESSAGE + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);

        Reader reader = StatusServer.readerFor(
                new ByteArrayInputStream(MESSAGE.getBytes(StandardCharsets.UTF_8)));
        StringBuilder back = new StringBuilder();
        int c;
        while ((c = reader.read()) >= 0) back.append((char) c);

        boolean wroteUtf8 = Arrays.equals(onTheWire, expected);
        boolean readUtf8 = MESSAGE.contentEquals(back);
        System.out.println("PROBE default=" + here + " wroteUtf8=" + wroteUtf8
                + " readUtf8=" + readUtf8);
        System.exit(wroteUtf8 && readUtf8 ? 0 : 1);
    }
}
