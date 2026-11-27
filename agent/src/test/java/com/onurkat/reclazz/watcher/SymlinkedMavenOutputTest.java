/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.watcher;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.SpringBootContext;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class SymlinkedMavenOutputTest {
    @TempDir Path temp;

    record Project(Path configured, Path output, SpringBootContext context, AgentConfig config) {}

    private Project project(boolean useAlias) throws IOException {
        Path real = Files.createDirectory(temp.resolve("project")).toRealPath();
        Path output = Files.createDirectories(real.resolve("target/classes"));
        Files.createDirectories(output.resolve("nested"));
        Files.createDirectories(real.resolve("src/main/resources"));
        Path configured = real;
        if (useAlias) {
            try {
                configured = Files.createSymbolicLink(temp.resolve("alias"), real);
            } catch (java.nio.file.FileSystemException e) {
                if (!System.getProperty("os.name").startsWith("Windows")) throw e;
                assumeTrue(false, "Cannot create the Windows project symlink: " + e);
            } catch (UnsupportedOperationException e) {
                assumeTrue(false, "Cannot create the project symlink: " + e);
            }
        }
        Path classes = configured.resolve("target/classes");
        AgentConfig config = AgentConfig.parse("watchDirs=" + classes + ",startupDelaySec=0,autoCompile=false");
        SpringBootContext context = new SpringBootContext(config);
        String previous = System.getProperty("java.class.path");
        try {
            System.setProperty("java.class.path", output.toString());
            context.initialize();
        } finally {
            System.setProperty("java.class.path", previous);
        }
        return new Project(classes, output, context, config);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sharedOutputKeepsItsClassRoleThroughAnAlias(boolean useAlias) throws Exception {
        Project p = project(useAlias);
        FileWatcher watcher = new FileWatcher(p.context(), p.config());
        var events = new LinkedBlockingQueue<ChangeEvent>();
        watcher.onFileChange(events::add);
        Thread thread = new Thread(watcher::startWatching);
        thread.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!watcher.health().state().equals("watching") && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals("watching", watcher.health().state());
            Path changed = p.output().resolve("nested/Fixture.class");
            try (var bytes = getClass().getResourceAsStream("SymlinkedMavenOutputTest.class")) {
                Files.copy(java.util.Objects.requireNonNull(bytes), changed);
            }
            ChangeEvent seen = events.poll(6, TimeUnit.SECONDS);
            assertNotNull(seen, "a compiled class save must be delivered");
            assertEquals("classes", seen.getSourceRoot());
            assertTrue(Files.isSameFile(changed, seen.getPath()));
            watcher.scanNow();
            assertTrue(events.isEmpty(), "a single class save must produce a single delivery");
            var evidence = watcher.doctorEvidence();
            assertEquals(1, java.util.stream.IntStream.range(0, evidence.directories().size())
                    .filter(i -> Path.of(evidence.directories().get(i)).endsWith("classes/nested"))
                    .filter(i -> evidence.directoryKinds().get(i).equals("classes")).count());
        } finally {
            watcher.stopWatching();
            thread.join(4000);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sharedOutputStillDeliversRuntimeResources(boolean useAlias) throws Exception {
        Project p = project(useAlias);
        String relative = "nested/messages.properties";
        Files.writeString(p.output().resolve(relative), "label=hello");
        assertEquals(relative, p.context().runtimeResourceName(p.configured().resolve(relative)));
        assertEquals(relative, p.context().runtimeResourceName(p.output().resolve(relative)));
        assertTrue(p.context().sourceResource(p.configured().getParent().getParent()
                .resolve("src/main/resources/source-only.xml")));
    }
}
