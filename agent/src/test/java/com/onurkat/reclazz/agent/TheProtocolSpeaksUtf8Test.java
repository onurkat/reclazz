/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The status socket carries the agent's words to the IDE, and they have to
 * arrive as they were written.
 *
 * <p>Both ends built their streams with no charset, which means the default
 * one, and the two ends are different JVMs. The IDE's is UTF-8; the
 * application's belongs to whoever wrote its start script, and SAP Commerce
 * installations do set {@code -Dfile.encoding=ISO-8859-1}. Reproduced before
 * it was fixed: "Reloaded com.acme.Sipari&#351; (Masa&#252;st&#252;)" left an
 * ISO-8859-1 agent and reached a UTF-8 reader as "Sipari?" and
 * "Masa&#65533;st&#65533;". Nothing failed; the developer just read nonsense in
 * their tool window, on their machine and not on anybody else's.
 *
 * <p>The second test is the one that means anything. This JVM is UTF-8, so a
 * round trip here passes whether or not the charset is stated: it runs in a
 * child JVM started with a different default, because that is the machine the
 * question is about.
 */
class TheProtocolSpeaksUtf8Test {

    @Test
    void whatGoesOnTheWireIsUtf8() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Writer writer = StatusServer.writerFor(sink);
        try (PrintWriter out = new PrintWriter(writer, true)) {
            out.print(ProtocolCharsetProbe.MESSAGE);
        }

        assertArrayEquals(ProtocolCharsetProbe.MESSAGE.getBytes(StandardCharsets.UTF_8),
                sink.toByteArray());
    }

    @Test
    void whatComesOffItIsReadAsUtf8() throws Exception {
        Reader reader = StatusServer.readerFor(new ByteArrayInputStream(
                ProtocolCharsetProbe.MESSAGE.getBytes(StandardCharsets.UTF_8)));

        StringBuilder back = new StringBuilder();
        int c;
        while ((c = reader.read()) >= 0) back.append((char) c);

        assertEquals(ProtocolCharsetProbe.MESSAGE, back.toString());
    }

    /**
     * The real one. A JVM whose default charset is not UTF-8 is the only place
     * an unstated encoding shows itself, and this process is not one.
     */
    @Test
    void andOnAMachineWhoseDefaultIsNotUtf8() throws Exception {
        List<String> command = new ArrayList<>(List.of(
                java(), "-Dfile.encoding=ISO-8859-1",
                "-cp", System.getProperty("java.class.path"),
                ProtocolCharsetProbe.class.getName()));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        int exit = process.waitFor();

        assertNotEquals(2, exit,
                () -> "the child JVM refused to change its default charset, so this "
                        + "proves nothing: " + output);
        assertEquals(0, exit,
                () -> "the socket's bytes changed with the machine: " + output);
        assertTrue(output.contains("wroteUtf8=true") && output.contains("readUtf8=true"),
                () -> output);
    }

    private static String java() {
        return java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
