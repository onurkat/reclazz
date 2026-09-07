/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers what each property file said last time, so a save can be read as
 * the edit it was rather than as a claim about the whole file.
 *
 * Comparing a saved file against the running configuration sounds equivalent
 * and is not, because the configuration is not a copy of any one file:
 *
 * The platform expands {@code ${...}} as it loads, so a line the developer
 * never touched reads as different for as long as it holds a placeholder, and
 * writing it back replaces a working path with the literal characters.
 *
 * The platform also layers files, and the last one to define a key wins. A key
 * set in {@code 10-local.properties} and overridden in {@code 95-local
 * .properties} reads as different in the first file forever, so saving that
 * file would quietly undo the override.
 *
 * Both were measured on a running server: saving one added line to a
 * config/dev/props file reported nine keys applied, two of them the SSO
 * keystore and metadata locations, whose values became the raw
 * {@code file:${HYBRIS_CONFIG_DIR}/...} text.
 *
 * Diffing the file against its own previous content has neither problem: an
 * untouched line is identical to itself no matter what the server made of it.
 */
public final class PropertyFileSnapshots {

    private final Map<Path, Map<String, String>> lastSeen = new ConcurrentHashMap<>();

    /**
     * Records the file as it is now without treating anything as a change.
     *
     * Used at startup, where the running configuration already reflects these
     * files: the server read them minutes ago. Without it the first save after
     * a start would be measured against nothing and every key in the file would
     * look new.
     */
    public void baseline(Path file) {
        Map<String, String> content = read(file);
        if (content != null) lastSeen.put(file, Map.copyOf(content));
    }

    /** A single read carried unchanged through checking, application and acceptance. */
    public record Candidate(Path file, Map<String, String> content, Map<String, String> changed) {
        public Candidate {
            content = Map.copyOf(content);
            changed = Map.copyOf(changed);
        }
    }

    /** Null means unreadable; neither a read nor a rejection advances the baseline. */
    public Candidate pending(Path file) {
        Map<String, String> now = read(file);
        if (now == null) return null;
        Map<String, String> previous = lastSeen.get(file);
        Map<String, String> changed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : now.entrySet()) {
            if (previous == null || !Objects.equals(previous.get(entry.getKey()), entry.getValue()))
                changed.put(entry.getKey(), entry.getValue());
        }
        return new Candidate(file, now, changed);
    }

    public void accept(Candidate candidate) {
        lastSeen.put(candidate.file(), candidate.content());
    }

    /** The version currently recorded, or null when the file is unknown. */
    public Map<String, String> current(Path file) {
        return lastSeen.get(file);
    }

    /**
     * A half-written file is the normal case: editors save in stages. Returning
     * null leaves the previous version recorded, so the next save compares
     * against something real instead of against a truncated read.
     */
    private static Map<String, String> read(Path file) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (Throwable t) {
            return null;
        }

        Map<String, String> map = new HashMap<>(p.size() * 2);
        for (String key : p.stringPropertyNames()) {
            map.put(key, p.getProperty(key));
        }
        return map;
    }
}
