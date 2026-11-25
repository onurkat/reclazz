/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Compute expected fingerprints from the cited sources, once per registry per test worker. */
final class ScopedEvidence {
    private static final Map<Path, JsonNode> sources = new HashMap<>();
    private static final String REFRESH = "; run ./gradlew :agent:refreshEvidence if the change keeps the claim true";

    private ScopedEvidence() { }

    private static synchronized JsonNode read(Path repo, String registry) throws Exception {
        Path file = repo.resolve("docs/feature-evidence").resolve(registry);
        if (!sources.containsKey(file)) {
            String python = System.getProperty("os.name").startsWith("Windows") ? "python" : "python3";
            var process = new ProcessBuilder(python, repo.resolve("scripts/refresh-evidence.py").toString(),
                    "--snapshot", file.toString()).redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            if (process.waitFor() != 0) fail(new String(output, StandardCharsets.UTF_8));
            sources.put(file, new ObjectMapper().readTree(output));
        }
        return sources.get(file);
    }

    static void surface(Path repo, String registry, String id, JsonNode item) throws Exception {
        String detail = "surface drift: " + id + " " + item.path("path").asText()
                + " " + item.path("quote").asText() + REFRESH;
        for (JsonNode expected : read(repo, registry).path(id).path("surfaces")) {
            if (expected.path("path").equals(item.path("path")) && expected.path("quote").equals(item.path("quote"))) {
                assertEquals(expected.get("block_sha256"), item.get("block_sha256"), detail);
                return;
            }
        }
        fail(detail);
    }

    static void method(Path repo, String registry, String id, JsonNode test) throws Exception {
        String detail = "method drift: " + id + " " + test.path("symbol").asText() + REFRESH;
        for (JsonNode expected : read(repo, registry).path(id).path("tests")) {
            if (!expected.path("symbol").equals(test.path("symbol"))) continue;
            assertEquals(expected.path("path"), test.path("path"), detail);
            assertEquals(expected.get("method_sha256"), test.get("method_sha256"), detail);
            if (test.has("declaration")) {
                assertEquals(expected.path("declaration").path("method_sha256"),
                        test.get("declaration").get("method_sha256"), detail);
            }
            return;
        }
        fail(detail);
    }
}
