/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onurkat.reclazz.AgentSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Rejects omitted, stale or falsely promoted evidence, rather than counting test filenames. */
class P1RegistryCompletenessTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> VERDICTS = Set.of("PROVEN_WITH_SCOPE", "PARTIAL", "UNVERIFIED", "EXPECTED", "FAILED");
    private static final Path REPO = AgentSources.root().getParent().getParent().getParent().getParent();

    private static JsonNode registry() throws Exception {
        return JSON.readTree(Files.readString(REPO.resolve("docs/feature-evidence/p1-core.json")));
    }

    @Test
    void currentRegistryHasEveryPlannedCellAndVerifiableProvenance() throws Exception {
        validate(registry());
    }

    @Test
    void missingRequiredRecordFieldIsRejected() throws Exception {
        JsonNode changed = registry().deepCopy();
        ((ObjectNode) changed.path("claims").get(0)).remove("observable");
        AssertionError error = assertThrows(AssertionError.class, () -> validate(changed));
        assertTrue(error.getMessage().contains("observable"), error.getMessage());
    }

    @Test
    void omittedCellIsRejectedEvenWhenAllOtherRowsAreValid() throws Exception {
        JsonNode changed = registry().deepCopy();
        ((ArrayNode) changed.path("claims")).remove(0);
        AssertionError error = assertThrows(AssertionError.class, () -> validate(changed));
        assertTrue(error.getMessage().contains("cell set"), error.getMessage());
    }

    @Test
    void unrunLaneCannotBePromotedToProven() throws Exception {
        for (String verdict : List.of("PROVEN_WITH_SCOPE", "PARTIAL", "EXPECTED", "FAILED")) {
            JsonNode changed = registry().deepCopy();
            ((ObjectNode) changed.path("claims").get(0)).put("verdict", verdict);
            String lane = changed.path("required_lanes").get(0).asText();
            ((ObjectNode) changed.path("lanes").path(lane)).put("status", "UNRUN");
            AssertionError error = assertThrows(AssertionError.class, () -> validate(changed));
            assertTrue(error.getMessage().contains("unrun lane"), error.getMessage());
        }
    }

    @Test
    void componentObservationCannotCertifyWatcherBehavior() throws Exception {
        JsonNode changed = registry().deepCopy();
        ObjectNode claim = (ObjectNode) changed.path("claims").get(0);
        claim.put("verdict", "PROVEN_WITH_SCOPE");
        ((ObjectNode) claim.path("tests").get(0)).put("kind", "component");
        AssertionError error = assertThrows(AssertionError.class, () -> validate(changed));
        assertTrue(error.getMessage().contains("component"), error.getMessage());
    }

    @Test
    void changedSurfaceDigestIsRejected() throws Exception {
        JsonNode changed = registry().deepCopy();
        ((ObjectNode) changed.path("claims").get(0).path("shipped_surfaces").get(0))
                .put("sha256", "0".repeat(64));
        AssertionError error = assertThrows(AssertionError.class, () -> validate(changed));
        assertTrue(error.getMessage().contains("surface drift"), error.getMessage());
    }

    @Test
    void nonexistentTestMethodIsRejected() throws Exception {
        JsonNode changed = registry().deepCopy();
        ((ObjectNode) changed.path("claims").get(0).path("tests").get(0))
                .put("symbol", "com.onurkat.reclazz.e2e.SuperCallAfterChildReloadTest#nonexistentProofMethod");
        AssertionError error = assertThrows(AssertionError.class, () -> validate(changed));
        assertTrue(error.getMessage().contains("real test method"), error.getMessage());
    }

    private static void validate(JsonNode registry) throws Exception {
        assertEquals(1, registry.path("format_version").asInt());
        assertEquals("P1", text(registry, "package"));
        Set<String> expected = new HashSet<>();
        for (String line : Files.readAllLines(REPO.resolve("docs/feature-evidence/p1-core-cells.txt"))) {
            if (!line.isBlank() && !line.startsWith("#")) assertTrue(expected.add(line.trim()), "duplicate planned cell");
        }
        assertTrue(expected.size() >= 30, "empty or incomplete planned cell manifest");
        JsonNode claims = registry.path("claims");
        assertTrue(claims.isArray() && !claims.isEmpty(), "empty claim registry");
        Set<String> ids = new HashSet<>();
        String readable = Files.readString(REPO.resolve("docs/feature-evidence.md"));
        for (JsonNode claim : claims) {
            String id = text(claim, "id");
            assertTrue(ids.add(id), "duplicate claim " + id);
            assertTrue(readable.contains("### " + id + "\n"), "missing human-readable row " + id);
            for (String field : List.of("statement", "opt_in", "bounds", "observable", "control",
                    "reviewer", "limitation", "missing_environment", "next_action")) text(claim, field);
            String verdict = text(claim, "verdict");
            assertTrue(VERDICTS.contains(verdict), "unknown verdict " + id);
            assertTrue(claim.path("shipped_surfaces").isArray() && !claim.path("shipped_surfaces").isEmpty());
            for (JsonNode surface : claim.path("shipped_surfaces")) {
                Path file = localFile(text(surface, "path"));
                assertEquals(sha256(file), text(surface, "sha256"), "surface drift: " + file);
                List<String> lines = Files.readAllLines(file);
                int line = surface.path("line").asInt();
                assertTrue(line > 0 && line <= lines.size(), "invalid surface line " + id);
                assertTrue(lines.get(line - 1).contains(text(surface, "quote")), "surface location drift " + id);
            }
            JsonNode tests = claim.path("tests");
            assertTrue(tests.isArray(), "tests must be explicit, including an empty UNVERIFIED mapping");
            for (JsonNode test : tests) {
                String symbol = text(test, "symbol");
                String[] parts = symbol.split("#", -1);
                assertEquals(2, parts.length, "test symbol requires class#method");
                Path file = localFile(text(test, "path"));
                assertEquals(sha256(file), text(test, "source_sha256"), "test source drift: " + symbol);
                text(test, "assertions");
                String kind = text(test, "kind");
                assertTrue(Set.of("watcher", "component").contains(kind));
                Class<?> type = Class.forName(parts[0], false, P1RegistryCompletenessTest.class.getClassLoader());
                boolean exists = java.util.Arrays.stream(type.getDeclaredMethods()).anyMatch(method ->
                        method.getName().equals(parts[1]) && (method.isAnnotationPresent(Test.class)
                                || method.isAnnotationPresent(ParameterizedTest.class)));
                assertTrue(exists, "not a real test method: " + symbol);
            }
            if (!verdict.equals("UNVERIFIED")) {
                assertFalse(tests.isEmpty(), "observed claim needs observing test methods");
                if (verdict.equals("PROVEN_WITH_SCOPE")) {
                    for (JsonNode test : tests) assertEquals("watcher", text(test, "kind"), "component cannot prove watcher");
                }
                assertTrue(claim.path("execution_lanes").isArray() && !claim.path("execution_lanes").isEmpty());
                Set<String> used = new HashSet<>();
                for (JsonNode required : registry.path("required_lanes")) used.add(required.asText());
                Set<String> recorded = new HashSet<>();
                for (JsonNode name : claim.path("execution_lanes")) recorded.add(name.asText());
                assertEquals(used, recorded, "missing or extra required lane " + id);
                for (String name : used) {
                    JsonNode lane = registry.path("lanes").path(name);
                    assertEquals("PASSED", lane.path("status").asText(), "unrun lane cannot prove " + id);
                    assertEquals(0, lane.path("exit_code").asInt(-1));
                    for (String key : List.of("command", "runtime", "artifact_sha256", "raw_evidence", "utc", "base_commit")) text(lane, key);
                    assertTrue(lane.path("artifact_sha256").asText().matches("[a-f0-9]{64}"));
                    assertTrue(lane.path("counts").isObject() && !lane.path("counts").isEmpty());
                    for (JsonNode test : tests) {
                        String symbol = test.path("symbol").asText();
                        boolean found = false;
                        for (JsonNode observation : lane.path("cases")) {
                            if (observation.path("symbol").asText().equals(symbol)) {
                                found = true;
                                assertEquals("PASSED", observation.path("status").asText(), "failed/skipped proof " + symbol);
                            }
                        }
                        assertTrue(found, "test not executed in claimed lane: " + symbol + " " + name);
                    }
                }
            } else if (tests.isEmpty()) {
                assertEquals("UNVERIFIED", verdict, "missing test cannot establish a limitation or failure");
            }
        }
        assertEquals(expected, ids, "registry cell set differs from the planned manifest");
    }

    private static String text(JsonNode node, String field) {
        assertTrue(node.path(field).isTextual() && !node.path(field).asText().isBlank(), "missing/empty " + field);
        return node.path(field).asText();
    }
    private static Path localFile(String relative) {
        Path path = REPO.resolve(relative).normalize();
        assertTrue(path.startsWith(REPO) && Files.isRegularFile(path), "missing/escaping source " + relative);
        return path;
    }
    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
