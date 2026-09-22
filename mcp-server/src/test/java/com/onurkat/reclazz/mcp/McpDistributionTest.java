/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpDistributionTest {
    @TempDir Path dir;

    private Path installJar() throws Exception {
        Path original = Path.of(System.getProperty("reclazz.mcp.releaseJar"));
        String version = System.getProperty("reclazz.mcp.releaseVersion");
        assertEquals("reclazz-mcp-" + version + ".jar", original.getFileName().toString());
        Path installed = Files.createDirectories(dir.resolve("MCP install with spaces")).resolve(original.getFileName());
        return Files.copy(original, installed);
    }

    @Test
    void checksumNamesOnlyJarAndMatchesBytes() throws Exception {
        Path jar = installJar();
        String checksum = Files.readString(Path.of(System.getProperty("reclazz.mcp.releaseChecksum")));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        assertEquals(hash + "  " + jar.getFileName() + "\n", checksum);
        byte[] altered = Files.readAllBytes(jar);
        altered[altered.length / 2] ^= 1;
        assertNotEquals(hash, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(altered)));
    }

    @Test
    void installedJarSpeaksStdioAndReportsReleaseVersion() throws Exception {
        Path jar = installJar();
        JsonObject status = JsonParser.parseString("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                  "name":"reclazz_status","arguments":{}}}
                """).getAsJsonObject();
        status.getAsJsonObject("params").getAsJsonObject("arguments")
                .addProperty("portFile", dir.resolve("missing.port").toString());
        Run run = run(List.of("-jar", jar.toString()), """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                """ + status + "\n");
        assertEquals(0, run.exit, run.err);
        assertEquals("", run.err);
        List<String> lines = run.out.lines().toList();
        assertEquals(3, lines.size(), run.out);
        JsonObject init = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertEquals(1, init.get("id").getAsInt());
        assertEquals("2024-11-05", init.getAsJsonObject("result").get("protocolVersion").getAsString());
        JsonObject info = init.getAsJsonObject("result").getAsJsonObject("serverInfo");
        assertEquals("reclazz-mcp", info.get("name").getAsString());
        assertEquals(System.getProperty("reclazz.mcp.releaseVersion"), info.get("version").getAsString());
        JsonObject tools = JsonParser.parseString(lines.get(1)).getAsJsonObject();
        assertEquals(2, tools.get("id").getAsInt());
        Set<String> names = tools.getAsJsonObject("result").getAsJsonArray("tools").asList().stream()
                .map(tool -> tool.getAsJsonObject().get("name").getAsString()).collect(Collectors.toSet());
        assertEquals(Set.of("reclazz_status", "reclazz_build", "reclazz_verify", "reclazz_scan",
                "reclazz_pending", "reclazz_diagnose"), names);
        JsonObject response = JsonParser.parseString(lines.get(2)).getAsJsonObject();
        assertEquals(3, response.get("id").getAsInt());
        String text = response.getAsJsonObject("result").getAsJsonArray("content").get(0)
                .getAsJsonObject().get("text").getAsString();
        assertFalse(JsonParser.parseString(text).getAsJsonObject().get("attached").getAsBoolean());
    }

    @Test
    void installedJarNegotiatesFallbackBeforeListingTools() throws Exception {
        Run run = run(List.of("-jar", installJar().toString()), """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2099-01-01","capabilities":{},"clientInfo":{"name":"fallback-test","version":"1"}}}
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                """);
        assertEquals(0, run.exit, run.err);
        assertEquals("", run.err);
        List<String> lines = run.out.lines().toList();
        assertEquals(2, lines.size(), run.out);
        JsonObject init = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertEquals(1, init.get("id").getAsInt());
        assertEquals("2025-06-18", init.getAsJsonObject("result").get("protocolVersion").getAsString());
        JsonObject tools = JsonParser.parseString(lines.get(1)).getAsJsonObject();
        assertEquals(2, tools.get("id").getAsInt());
        assertEquals(6, tools.getAsJsonObject("result").getAsJsonArray("tools").size());
    }

    @Test
    void installedJarRecoversAfterMissingVersion() throws Exception {
        Run run = run(List.of("-jar", installJar().toString()), """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                {"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                {"jsonrpc":"2.0","id":3,"method":"ping"}
                """);
        assertEquals(0, run.exit, run.err);
        assertEquals("", run.err);
        List<String> lines = run.out.lines().toList();
        assertEquals(3, lines.size(), run.out);
        JsonObject invalid = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertTrue(invalid.has("error"), invalid.toString());
        assertEquals(1, invalid.get("id").getAsInt());
        assertEquals(-32602, invalid.getAsJsonObject("error").get("code").getAsInt());
        JsonObject init = JsonParser.parseString(lines.get(1)).getAsJsonObject();
        assertEquals(2, init.get("id").getAsInt());
        assertEquals("2024-11-05", init.getAsJsonObject("result").get("protocolVersion").getAsString());
        JsonObject ping = JsonParser.parseString(lines.get(2)).getAsJsonObject();
        assertEquals(3, ping.get("id").getAsInt());
        assertTrue(ping.has("result"));
    }

    @Test
    void installedJarContainsTerminalBuildEntryPoint() throws Exception {
        Run run = run(List.of("-cp", installJar().toString(), "com.onurkat.reclazz.mcp.BuildMain"), "");
        assertEquals(2, run.exit, run.err);
        assertEquals("", run.out);
        assertTrue(run.err.startsWith("Usage: java -cp"), run.err);
    }

    private record Run(int exit, String out, String err) { }

    private Run run(List<String> args, String input) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(args);
        Path out = dir.resolve("stdout.txt"), err = dir.resolve("stderr.txt");
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile())
                .redirectOutput(out.toFile()).redirectError(err.toFile());
        // Prove installation without an inherited development classpath or Java-agent flags.
        for (String key : List.of("CLASSPATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            builder.environment().remove(key);
        }
        Process child = builder.start();
        try {
            try (var stdin = child.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Packaged MCP did not exit after EOF");
            return new Run(child.exitValue(), Files.readString(out), Files.readString(err));
        } finally {
            child.destroyForcibly();
        }
    }
}
