/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.platform.codegen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedPublicationTest {
    @TempDir Path temp;
    private Path live(String name) { return temp.resolve("platform/bootstrap").resolve(name); }
    private Path marker() { return temp.resolve("private/pending"); }
    private GeneratedPublication publication() { return new GeneratedPublication(temp.resolve("platform"), marker()); }
    private Path write(Path target, String value) throws IOException {
        Files.createDirectories(target.getParent());
        return Files.writeString(target, value);
    }
    private GeneratedPublication.Change change(String name, String content) throws IOException {
        Path input = content == null ? null : write(temp.resolve("stage").resolve(name), content);
        return new GeneratedPublication.Change(Path.of(name), GeneratedPublication.digest(live(name)), input);
    }

    @Test void publishesAndRetiresOnlyTheCompleteGeneration() throws IOException {
        write(live("bin/models.jar"), "old");
        publication().publish(List.of(change("gensrc/x/Y.java", "src"), change("bin/models.jar", "new")));
        assertFalse(Files.exists(marker()));
        assertEquals("src", Files.readString(live("gensrc/x/Y.java")));
        assertEquals("new", Files.readString(live("bin/models.jar")));
    }

    @Test void coldRecoveryRemovesNewFilesAndRestoresModifiedAndDeletedOutputs() throws IOException {
        write(live("modelclasses/x/Y.class"), "old-class");
        write(live("gensrc/x/X.java"), "deleted-source");
        write(live("bin/models.jar"), "old-jar");
        var transaction = publication();
        var changes = transaction.prepare(List.of(change("gensrc/x/Z.java", "new-source"),
                change("modelclasses/x/Y.class", "new-class"), change("gensrc/x/X.java", null), change("bin/models.jar", "new-jar")));
        for (int i=0; i<3; i++) transaction.publishFile(changes.get(i));
        assertEquals("new-class", Files.readString(live("modelclasses/x/Y.class")));
        assertFalse(Files.exists(live("gensrc/x/X.java")));
        publication().recover();
        publication().recover();
        assertFalse(Files.exists(live("gensrc/x/Z.java")));
        assertEquals("old-class", Files.readString(live("modelclasses/x/Y.class")));
        assertEquals("deleted-source", Files.readString(live("gensrc/x/X.java")));
        assertEquals("old-jar", Files.readString(live("bin/models.jar")));
    }

    @Test void refusesToOverwriteAnIndependentEditDuringRecovery() throws IOException {
        write(live("bin/models.jar"), "old");
        var tx=publication();
        var entry=tx.prepare(List.of(change("bin/models.jar", "new"))).get(0);
        tx.publishFile(entry);
        write(live("bin/models.jar"), "external");
        assertThrows(IOException.class, () -> publication().recover());
        assertEquals("external", Files.readString(live("bin/models.jar")));
        assertTrue(Files.exists(marker()));
    }

    @Test void aChangedStagedInputFailsBeforeReplacingItsDestination() throws IOException {
        var tx=publication();
        var prepared=tx.prepare(List.of(change("modelclasses/x/Y.class", "one"), change("bin/models.jar", "two")));
        tx.publishFile(prepared.get(0));
        write(prepared.get(1).change().staged(), "different");
        assertThrows(IOException.class, () -> tx.publishFile(prepared.get(1)));
        publication().recover();
        assertFalse(Files.exists(live("modelclasses/x/Y.class")));
        assertFalse(Files.exists(live("bin/models.jar")));
    }

    @Test void startupRecoversTheInstallationMarkerBeforeScanningGeneratedClasses() throws Exception {
        Path home = temp.resolve("shop");
        Path platform = home.resolve("bin/platform");
        Path jar = write(platform.resolve("bootstrap/bin/models.jar"), "before");
        Path staged = write(temp.resolve("stage/models.jar"), "after");
        var tx = GeneratedPublication.installation(platform);
        try (var lease = tx.acquire()) {
            tx.publishFile(tx.prepare(List.of(new GeneratedPublication.Change(
                    Path.of("bin/models.jar"), GeneratedPublication.digest(jar), staged))).get(0));
            assertThrows(IOException.class, () -> GeneratedPublication.recoverAtStartup(platform));
            assertEquals("after", Files.readString(jar));
        }
        Files.createDirectories(home.resolve("config"));
        var context = new com.onurkat.reclazz.platform.HybrisPlatformContext(
                home, com.onurkat.reclazz.config.AgentConfig.parse(null));
        context.initialize();
        assertEquals("before", Files.readString(jar));
        assertFalse(Files.exists(platform.resolve(".reclazz-codegen/pending")));
    }
}
