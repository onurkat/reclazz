/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.watcher;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.SpringBootContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WebRootWatchTest {
    @TempDir Path directory;

    @Test void boundsLargeTreesAndPreservesShallowViewsAndServedAssets() throws Exception {
        Path web = Files.createDirectory(directory.resolve("web"));
        Path tags = Files.createDirectories(web.resolve("WEB-INF/tags"));
        Path assets = Files.createDirectory(web.resolve("_ui"));
        assertEquals(384, WebRootDirectories.MAX_DIRECTORIES);
        for (int i = 0; i < WebRootDirectories.MAX_DIRECTORIES + 1; i++)
            Files.createDirectory(assets.resolve("package-" + i));
        Files.createDirectories(web.resolve("WEB-INF/classes/example"));
        Files.createDirectories(web.resolve("node_modules/dependency"));
        Path flat = Files.createDirectory(web.resolve("oversized"));
        for (int i = 0; i < 520; i++) Files.createFile(flat.resolve("asset-" + i));
        var dirs = WebRootDirectories.select(web, WebRootDirectories.MAX_DIRECTORIES,
                (path, failure) -> { throw new AssertionError(path.toString(), failure); })
                .stream().map(WebRootDirectories.Directory::path).toList();
        assertEquals(WebRootDirectories.MAX_DIRECTORIES, dirs.size());
        assertTrue(dirs.containsAll(List.of(web, tags, assets)));
        assertFalse(dirs.contains(flat));
        assertTrue(dirs.stream().noneMatch(path -> path.startsWith(web.resolve("node_modules"))
                || path.startsWith(web.resolve("WEB-INF/classes"))));
        assertTrue(WebRootDirectories.select(assets, 0, (path, failure) -> fail(failure)).isEmpty());
    }

    @Test void actualTemplatesAtDepthTakeTheSlotsBeforeStaticDirectories() throws Exception {
        Path root = Files.createDirectory(directory.resolve("store"));
        Path ui = Files.createDirectory(root.resolve("_ui"));
        for (int i = 0; i < 180; i++) Files.createDirectories(ui.resolve("assets-" + i));
        Set<Path> expected = new HashSet<>();
        for (String file : List.of("page.jsp", "fragment.jspf", "regular.tag", "xml.tagx")) {
            Path deep = Files.createDirectories(root.resolve("WEB-INF/views/store/layout/pages/" + file + "-dir"));
            Files.writeString(deep.resolve(file), "rendered");
            expected.add(deep);
        }
        var selected = WebRootDirectories.select(root, expected.size(), (path, failure) -> fail(failure));
        assertEquals(expected, new HashSet<>(selected.stream().map(WebRootDirectories.Directory::path).toList()));
    }

    @Test void deliversFileSavesAtStartupAndAfterChangingTheWebroot() throws Exception {
        Path classes = Files.createDirectories(directory.resolve("build/classes"));
        AgentConfig config = AgentConfig.parse("watchDirs=" + classes + ",startupDelaySec=0,debounceMs=0");
        class WebPlatform extends SpringBootContext {
            final Path webroot;
            WebPlatform(Path webroot) { super(config); this.webroot = webroot; }
            @Override public Map<String, List<Path>> getConfigurationFiles() {
                return Map.of("store", List.of(webroot.resolve("explicit.properties")));
            }
            @Override public Map<String, List<Path>> getWebRoots() {
                return Map.of("store", List.of(webroot));
            }
        }
        Path first = Files.createDirectories(directory.resolve("first"));
        Path next = Files.createDirectories(directory.resolve("next"));
        for (Path root : List.of(first, next)) Files.createDirectories(root.resolve("WEB-INF/tags"));
        for (Path root : List.of(first, next)) {
            Files.createDirectories(root.resolve("node_modules/pkg"));
            Files.createDirectories(root.resolve(".gradle/cache"));
        }
        var platform = new WebPlatform(first);
        platform.initialize();
        var saved = new LinkedBlockingQueue<Path>();
        var reloads = new LinkedBlockingQueue<ChangeEvent>();
        var watcher = new FileWatcher(platform, config) {
            @Override void evictWebResource(Path path) { saved.add(path); }
        };
        Path property = Files.writeString(first.resolve("explicit.properties"), "key=old");
        watcher.onFileChange(reloads::add);
        Thread thread = new Thread(watcher::startWatching);
        thread.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!watcher.doctorEvidence().state().equals("watching") && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("watching", watcher.doctorEvidence().state());
            Files.writeString(property, "key=changed");
            ChangeEvent explicit = reloads.poll(10, TimeUnit.SECONDS);
            assertNotNull(explicit);
            assertEquals(property, explicit.getPath());
            assertEquals("resources", explicit.getSourceRoot());
            for (Path root : List.of(first, next)) {
                if (root.equals(next)) {
                    var replacement = new WebPlatform(next);
                    replacement.initialize();
                    assertTrue(watcher.requestReconfigure(replacement, config.liveOptions(),
                            action -> { action.run(); return true; }, () -> {}, Runnable::run)
                            .toCompletableFuture().get(10, TimeUnit.SECONDS));
                }
                Files.writeString(root.resolve("node_modules/pkg/dependency.js"), "ignored");
                Files.writeString(root.resolve(".gradle/cache/generated.js"), "ignored");
                Set<Path> expected = new HashSet<>();
                for (String name : List.of("page.jsp", "WEB-INF/tags/value.tag", "include.jspf", "style.css")) {
                    expected.add(Files.writeString(root.resolve(name), "changed"));
                }
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
                while (!expected.isEmpty() && System.nanoTime() < deadline) {
                    Path seen = saved.poll(100, TimeUnit.MILLISECONDS);
                    if (seen != null) {
                        assertFalse(seen.startsWith(root.resolve("node_modules")));
                        assertFalse(seen.startsWith(root.resolve(".gradle")));
                        expected.remove(seen);
                    }
                }
                assertTrue(expected.isEmpty(), "unobserved files: " + expected);
            }
            assertTrue(reloads.stream().allMatch(event -> event.getPath().equals(property)),
                    "a static web resource triggered an ordinary reload");
        } finally {
            watcher.stopWatching();
            thread.join(5000);
        }
    }
}
