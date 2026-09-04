/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.hybris

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Replacing one of the user's files without ever leaving half of one.
 *
 * `Files.writeString` truncates the target and then writes into it, so between
 * those two things the file on disk is empty. These are not our files. They are
 * `95-local.properties` and the generated wrapper configs of somebody's SAP
 * Commerce installation, and a truncated `wrapper.conf` is a server that will
 * not start.
 *
 * There is a backup, taken once, the first time Reclazz writes to a file it did
 * not create. That protects the original and nothing since: every later write,
 * and there is one each time a setting changes, went straight at the live file.
 *
 * The content goes to a temporary file beside the target and is moved onto it.
 * A move within a directory is atomic on every filesystem this runs on; where
 * it is not, the fallback replaces without the guarantee, which is what the
 * previous code did every time. Either way the temporary file does not survive
 * the call.
 *
 * The agent has the same helper for the same reason; the two modules do not
 * share code.
 */
internal fun writeAtomically(target: Path, content: String) {
    val directory = target.toAbsolutePath().parent
        ?: throw java.io.IOException("no directory for $target")
    Files.createDirectories(directory)

    // Beside the target, because a move across filesystems is a copy and a copy
    // is the thing this exists to avoid.
    val temporary = Files.createTempFile(directory, ".${target.fileName}", ".reclazz-tmp")
    try {
        Files.writeString(temporary, content)
        carryPermissions(target, temporary)
        try {
            Files.move(
                temporary, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

/**
 * The replacement keeps the file's own permissions.
 *
 * `createTempFile` makes an owner-only file, which is right for a temporary one
 * and wrong for what this becomes. Measured on a real SAP Commerce
 * installation: `wrapper.conf` is 777 there, and handing it back as 600 would
 * leave a server another account starts unable to read its own configuration.
 * A file that did not exist before gets the ordinary 644 rather than the
 * temporary file's 600, for the same reason.
 *
 * Silently skipped where the filesystem has no POSIX permissions, which is
 * Windows, where there is nothing to preserve.
 */
private fun carryPermissions(target: Path, temporary: Path) {
    try {
        val permissions = if (Files.exists(target)) {
            Files.getPosixFilePermissions(target)
        } else {
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--")
        }
        Files.setPosixFilePermissions(temporary, permissions)
    } catch (_: UnsupportedOperationException) {
        // Nothing to carry.
    } catch (_: java.io.IOException) {
        // Nothing to carry.
    }
}
