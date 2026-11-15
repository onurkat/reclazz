package com.onurkat.reclazz.inttest.tests

import java.nio.file.Files
import java.nio.file.Path

/**
 * The fixture as a contract server should see it, or null while it is being rewritten.
 *
 * The live tests save with Files.write, which truncates the file and then writes it.
 * The contract servers poll every few milliseconds, so a read between those two steps
 * saw an empty file and counted it as a save of its own: the "wrong-owner" and
 * "reversed" modes then answered the next, real save with the receipt meant for the
 * first one, and the harness contract failed about one run in three.
 */
internal fun settledBytes(path: Path): ByteArray? {
    val first = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return null
    if (first.isEmpty()) return null
    Thread.sleep(1)
    val second = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return null
    return if (first.contentEquals(second)) second else null
}
