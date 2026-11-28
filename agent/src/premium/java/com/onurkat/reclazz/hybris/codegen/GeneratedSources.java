/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris.codegen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Content identities for the native DTO source set in a private generation. */
final class GeneratedSources {
    private static final Pattern STAMP = Pattern.compile(
            "(?m)^( ?\\* --- Generated at )[A-Z][a-z]{2} [0-9]{1,2}, [0-9]{4}, [0-9]{1,2}:[0-9]{2}:[0-9]{2}(?: |\\xE2\\x80\\xAF)[AP]M *(---)?(?=\\r?$)");
    private GeneratedSources() { }

    static Map<Path, String> inventory(Path root) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing generated source directory");
        Map<Path, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (Files.isSymbolicLink(path)) throw new IOException("Redirected generated source");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!path.toString().endsWith(".java") || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Unknown generated output");
                result.put(root.relativize(path), sourceDigest(path));
            }
        }
        return Map.copyOf(result);
    }

    static List<Path> changes(Map<Path, String> baseline, Map<Path, String> staged) throws IOException {
        if (!staged.keySet().containsAll(baseline.keySet())) throw new IOException("A generated DTO source was removed");
        List<Path> changed = new ArrayList<>();
        staged.forEach((path, digest) -> {
            if (!digest.equals(baseline.get(path))) changed.add(path);
        });
        if (changed.size() > 50) throw new IOException("More than 50 generated sources changed");
        return List.copyOf(changed);
    }

    private static String sourceDigest(Path source) throws IOException {
        byte[] bytes = Files.readAllBytes(source);
        // This single-byte view round-trips every original byte, even for non-UTF-8 source files.
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        int start = 0;
        if (text.startsWith("/*")) {
            int firstEnd = text.indexOf("*/");
            if (firstEnd >= 0 && text.substring(0, firstEnd).contains("Copyright")
                    && !text.substring(0, firstEnd).contains("WARNING: THIS FILE IS GENERATED")) {
                start = firstEnd + 2;
                while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
            }
        }
        if (text.startsWith("/*", start)) {
            int end = text.indexOf("*/", start + 2);
            if (end >= 0) {
                String banner = text.substring(start, end);
                if (banner.contains("WARNING: THIS FILE IS GENERATED")) {
                    var stamp = STAMP.matcher(banner);
                    if (stamp.find()) text = text.substring(0, start)
                            + stamp.replaceFirst("$1<timestamp>$2") + text.substring(end);
                }
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.ISO_8859_1));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
