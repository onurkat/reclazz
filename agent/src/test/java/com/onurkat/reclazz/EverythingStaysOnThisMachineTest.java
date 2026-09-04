/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The promise the welcome dialog makes, kept by construction.
 *
 * <p>It says "no telemetry, no analytics, no outbound", and that is true today
 * because nobody has written any. It is the kind of thing that stops being true
 * in one commit: a crash reporter, a version check, an update ping, each of them
 * a reasonable-looking addition that turns a tool running inside somebody's
 * production-shaped application into one that sends things out of it. For a
 * developer whose employer has to answer for what leaves the building under the
 * GDPR or the KVKK, "we read the source once" is not an answer.
 *
 * <p>So this reads the built classes. The one socket the agent opens is a
 * server bound to loopback, which the README describes and which nothing here
 * forbids; what it must not gain is the ability to open one outwards.
 */
class EverythingStaysOnThisMachineTest {

    /**
     * Dialling out, as calls rather than as types.
     *
     * <p>The type alone is the wrong question: {@code StatusServer} handles
     * {@code java.net.Socket} objects all day, and every one of them came back
     * from {@code ServerSocket.accept} on a loopback port. What it never does,
     * and what nothing here may start doing, is construct one.
     */
    private static final Set<String> DIALLING_OUT = Set.of(
            "java/net/Socket.<init>",
            "java/net/DatagramSocket.<init>",
            "java/net/URL.openConnection",
            "java/net/URL.openStream",
            "java/net/URI.toURL",
            "java/net/http/HttpClient.newHttpClient",
            "java/net/http/HttpClient.newBuilder",
            "java/nio/channels/SocketChannel.open",
            "java/nio/channels/DatagramChannel.open",
            "java/nio/channels/AsynchronousSocketChannel.open");

    /** Small enough to notice if the walk finds nothing. */
    private static final int AT_LEAST = 100;

    @Test
    void nothingInTheAgentCanOpenAConnectionOutwards() throws IOException {
        Path classes = builtClasses();
        List<String> offences = new ArrayList<>();
        int examined = 0;

        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                examined++;
                for (String called : callsMadeBy(Files.readAllBytes(file))) {
                    if (!DIALLING_OUT.contains(called)) continue;
                    offences.add(classes.relativize(file) + " calls " + called.replace('/', '.'));
                }
            }
        }

        int read = examined;
        assertTrue(read >= AT_LEAST,
                () -> "only " + read + " classes were read from " + classes
                        + "; a guard that finds nothing is not a guard");
        assertEquals(List.of(), offences,
                "Reclazz tells its users that nothing leaves their machine. Something here "
                        + "can now open a connection outwards, and that promise is in the "
                        + "welcome dialog, the README and the plugin description.");
    }

    /** Every method this class calls, as owner.name. */
    private static Set<String> callsMadeBy(byte[] bytecode) {
        ClassNode node = new ClassNode();
        new ClassReader(bytecode).accept(node, ClassReader.SKIP_FRAMES);
        Set<String> calls = new LinkedHashSet<>();
        for (MethodNode method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    calls.add(call.owner + "." + call.name);
                }
            }
        }
        return calls;
    }

    private static Path builtClasses() {
        Path here = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 6 && here != null; depth++) {
            for (String candidate : new String[]{
                    "agent/build/classes/java/main", "build/classes/java/main"}) {
                Path path = here.resolve(candidate);
                if (Files.isDirectory(path.resolve("com/onurkat/reclazz/agent"))) return path;
            }
            here = here.getParent();
        }
        throw new IllegalStateException("the agent's classes are not built; looked upward from "
                + Path.of("").toAbsolutePath());
    }
}
