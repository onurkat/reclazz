/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where the guards that read this agent's own source find it.
 *
 * <p>Several tests here work by reading the sources rather than by calling
 * them, because what they check is a rule about how the code is written: that
 * a warning is remembered for the restart summary, that a message is not
 * printed raw, that a name is spelled in one place. Each of them had grown its
 * own copy of the same two things, the walk up to the root and the decision
 * about what to do when it is not there, and the copies had drifted.
 *
 * <p>Two of them answered "not there" with {@code Assumptions.assumeTrue},
 * which skips. A skipped guard is a guard that reports nothing wrong, on
 * exactly the machine where the layout was different from the one it was
 * written on. This throws instead. A guard is either checking something or it
 * is broken, and there is no third state worth a green tick.
 *
 * <p>{@link #javaFiles()} carries a floor for the same reason: a walk that
 * finds nothing satisfies every "no offenders" assertion ever written.
 */
public final class AgentSources {

    /**
     * The agent has well over a hundred source files. The floor is far below
     * that on purpose: it is here to catch an empty or wrong directory, not to
     * need editing whenever a file is added or removed.
     */
    private static final int AT_LEAST = 80;

    /** A file that has to be in the root, so a wrong directory is not accepted. */
    private static final String MARKER = "com/onurkat/reclazz/agent/RestartLedger.java";

    private AgentSources() {
    }

    /** The agent's {@code src/main/java}, wherever the test was launched from. */
    public static Path root() {
        Path here = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 6 && here != null; depth++) {
            for (String candidate : new String[]{"agent/src/main/java", "src/main/java"}) {
                Path path = here.resolve(candidate);
                if (Files.isRegularFile(path.resolve(MARKER))) return path;
            }
            here = here.getParent();
        }
        throw new IllegalStateException(
                "the agent's sources are not reachable from " + Path.of("").toAbsolutePath()
                        + ". A guard that reads them cannot run, and skipping it would "
                        + "report that it found nothing wrong.");
    }

    /** Every Java file under the root, with the floor already checked. */
    public static List<Path> javaFiles() {
        try (Stream<Path> files = Files.walk(root())) {
            List<Path> found = files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            if (found.size() < AT_LEAST) {
                throw new IllegalStateException("only " + found.size() + " source files under "
                        + root() + "; a guard that reads nothing passes for the wrong reason");
            }
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One file's lines, since every one of these guards reads line by line. */
    public static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
