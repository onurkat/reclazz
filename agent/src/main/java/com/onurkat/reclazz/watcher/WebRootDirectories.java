/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.watcher;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.PriorityQueue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/** Keep the OS watcher's idle cost bounded on generated storefront asset trees. */
final class WebRootDirectories {
    static final int MAX_DIRECTORIES = 384;
    private static final int MAX_ENTRIES = 2048;
    private static final int MAX_PER_DIRECTORY = 512;
    private static final Set<String> TOOL_DIRECTORIES =
            Set.of("node_modules", ".git", ".gradle", ".cache", ".sass-cache", ".parcel-cache");

    record Directory(Path path, int files) {}

    static boolean excluded(Path path) {
        return TOOL_DIRECTORIES.contains(path.getFileName().toString())
                || path.endsWith("WEB-INF/classes") || path.endsWith("WEB-INF/lib");
    }

    static List<Directory> select(Path root, int remaining, BiConsumer<Path, IOException> onFailure) {
        if (remaining <= 0) return List.of();
        List<Directory> rendered = new ArrayList<>();
        List<Directory> other = new ArrayList<>();
        // Search conventional view trees before spending the discovery budget
        // in bundled assets. Only an actual template earns a preferred watch.
        var pending = new PriorityQueue<Path>(Comparator
                .comparingInt((Path dir) -> searchPriority(root.relativize(dir)))
                .thenComparingInt(Path::getNameCount).thenComparing(Comparator.naturalOrder()));
        pending.add(root);
        int inspected = 0;
        while (!pending.isEmpty() && rendered.size() < remaining && inspected < MAX_ENTRIES) {
            Path dir = pending.remove();
            if (excluded(dir)) continue;
            int count = 0, files = 0;
            boolean hasTemplate = false;
            List<Path> children = new ArrayList<>();
            try (var entries = Files.newDirectoryStream(dir)) {
                for (Path entry : entries) {
                    inspected++;
                    if (++count > MAX_PER_DIRECTORY || inspected > MAX_ENTRIES) break;
                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                        if (!excluded(entry)) children.add(entry);
                    } else {
                        files++;
                        String name = entry.getFileName().toString();
                        if (isTemplate(name) && Files.isRegularFile(entry)) hasTemplate = true;
                    }
                }
            } catch (DirectoryIteratorException failure) {
                onFailure.accept(dir, failure.getCause());
                continue;
            } catch (IOException failure) {
                onFailure.accept(dir, failure);
                continue;
            }
            if (count > MAX_PER_DIRECTORY || inspected > MAX_ENTRIES) continue;
            Directory candidate = new Directory(dir, files);
            if (hasTemplate) rendered.add(candidate);
            else if (other.size() < remaining) other.add(candidate);
            pending.addAll(children);
        }
        rendered.addAll(other.subList(0, Math.min(other.size(), remaining - rendered.size())));
        return rendered;
    }

    private static int searchPriority(Path relative) {
        if (relative.equals(Path.of("WEB-INF")) || relative.startsWith("WEB-INF/views")
                || relative.startsWith("WEB-INF/tags") || relative.startsWith("WEB-INF/jsp")) return 0;
        return relative.startsWith("_ui") ? 2 : 1;
    }

    private static boolean isTemplate(String name) {
        return name.endsWith(".jsp") || name.endsWith(".jspf") || name.endsWith(".tag") || name.endsWith(".tagx");
    }

    private WebRootDirectories() {}
}
