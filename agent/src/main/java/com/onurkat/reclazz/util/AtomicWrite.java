/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Replacing a file without ever leaving half of one.
 *
 * <p>{@code Files.writeString} truncates the target and then writes into it, so
 * between those two things the file on disk is empty, and if anything goes
 * wrong in between it stays that way. For a file this agent owns that is a
 * nuisance; for one it is a guest in, it is somebody's configuration.
 *
 * <p>The other half is the reader. The port file is written by the agent and
 * polled by the IDE, with no lock between them, so a reader arriving mid-write
 * gets whatever prefix has landed: "586" is a perfectly parseable port number
 * and it is not the one the agent is listening on.
 *
 * <p>So the content goes to a temporary file beside the target and is moved
 * onto it. A move within a directory is atomic on every filesystem this runs
 * on; where it is not, the fallback replaces without the guarantee, which is
 * what the old code did every time. Either way the temporary file does not
 * survive the call.
 */
public final class AtomicWrite {

    private AtomicWrite() {
    }

    /** Writes UTF-8 text, replacing whatever is there, all at once. */
    public static void string(Path target, String content) throws IOException {
        bytes(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The replacement keeps the file's own permissions.
     *
     * <p>{@code createTempFile} makes an owner-only file, which is right for a
     * temporary one and wrong for what this becomes. Measured on a real SAP
     * Commerce installation: {@code wrapper.conf} is 777 there, and handing it
     * back as 600 would leave a server that another account starts unable to
     * read its own configuration. A file that did not exist before gets the
     * ordinary 644 rather than the temporary file's 600, for the same reason.
     *
     * <p>Silently skipped where the filesystem has no POSIX permissions, which
     * is Windows, where there is nothing to preserve.
     */
    private static void carryPermissions(Path target, Path temporary) {
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions =
                    Files.exists(target)
                            ? Files.getPosixFilePermissions(target)
                            : java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--");
            Files.setPosixFilePermissions(temporary, permissions);
        } catch (UnsupportedOperationException | IOException notPosix) {
            // Nothing to carry.
        }
    }

    /** The same for bytes. */
    public static void bytes(Path target, byte[] content) throws IOException {
        Path directory = target.toAbsolutePath().getParent();
        if (directory == null) throw new IOException("no directory for " + target);
        Files.createDirectories(directory);

        // Beside the target, because a move across filesystems is a copy and a
        // copy is the thing this exists to avoid.
        Path temporary = Files.createTempFile(directory, "." + target.getFileName(), ".reclazz-tmp");
        try {
            Files.write(temporary, content);
            carryPermissions(target, temporary);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notHere) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // Only reached when the move did not happen; a temporary file left
            // in somebody's config directory is its own small mess.
            Files.deleteIfExists(temporary);
        }
    }
}
