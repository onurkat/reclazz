package com.onurkat.reclazz.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A save event is not evidence that anything changed, and where the work it
 * triggers is expensive that difference is worth money.
 *
 * Measured on a running 2211 server: clearing a cache costs nothing (0.05ms for
 * ZK's labels, 0.008ms for the platform's localizations), but the first lookup
 * afterwards pays for the rebuild, 3.95s and 0.83s respectively. The platform's
 * own build re-copies resource files, so a single ant build fires a modify
 * event for every localization file in every extension. Without this guard each
 * one of those buys another rebuild for whoever reads next.
 */
class ContentChangeGuardTest {

    @TempDir
    Path dir;

    private Path write(String name, String content) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    @Test
    void aFileSeenForTheFirstTimeCountsAsChanged() throws Exception {
        assertTrue(new ContentChangeGuard().changed(write("a.properties", "x=1")),
                "with nothing to compare against, doing the work is the safe answer");
    }

    @Test
    void savingTheSameContentAgainIsNotAChange() throws Exception {
        Path f = write("a.properties", "type.Product.name=Product");
        ContentChangeGuard guard = new ContentChangeGuard();

        assertTrue(guard.changed(f));
        assertFalse(guard.changed(f), "same bytes, so nothing to rebuild");
        assertFalse(guard.changed(f));
    }

    @Test
    void anEditIsSeen() throws Exception {
        Path f = write("a.properties", "type.Product.name=Product");
        ContentChangeGuard guard = new ContentChangeGuard();
        guard.changed(f);

        Files.writeString(f, "type.Product.name=Item");
        assertTrue(guard.changed(f));
    }

    /**
     * Reverting is as much a change as making one. Comparing against the last
     * value acted on, rather than a first-seen baseline, is what makes undo
     * behave like any other edit.
     */
    @Test
    void revertingToTheOriginalTextIsAChangeToo() throws Exception {
        Path f = write("a.properties", "greeting=Hello");
        ContentChangeGuard guard = new ContentChangeGuard();
        guard.changed(f);

        Files.writeString(f, "greeting=Merhaba");
        assertTrue(guard.changed(f));

        Files.writeString(f, "greeting=Hello");
        assertTrue(guard.changed(f), "the server is now serving Merhaba; this has to reload");
    }

    @Test
    void filesAreTrackedSeparately() throws Exception {
        Path a = write("a.properties", "x=1");
        Path b = write("b.properties", "x=1");
        ContentChangeGuard guard = new ContentChangeGuard();

        assertTrue(guard.changed(a));
        assertTrue(guard.changed(b), "identical content in another file is still its first sighting");
        assertFalse(guard.changed(a));
    }

    /**
     * Editors save in stages, so reading a file that is not there yet, or is
     * half written, is the normal case. Skipping the reload then would drop a
     * real edit, which costs more than an unnecessary rebuild.
     */
    @Test
    void anUnreadableFileIsTreatedAsChanged() {
        assertTrue(new ContentChangeGuard().changed(dir.resolve("never-written.properties")));
    }
}
