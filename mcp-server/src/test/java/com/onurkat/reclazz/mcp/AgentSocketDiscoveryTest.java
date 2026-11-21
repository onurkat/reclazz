/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AgentSocketDiscoveryTest {
    @TempDir Path root;
    private Path home(String relative, int port) throws Exception {
        Path h = root.resolve(relative);
        Files.createDirectories(h.resolve("bin/platform"));
        Files.writeString(h.resolve("bin/platform/build.xml"), "<project/>");
        Files.createDirectories(h.resolve(".reclazz"));
        Files.writeString(h.resolve(".reclazz/agent.port"), Integer.toString(port));
        return h;
    }
    private AgentSocket.Result resolve(Map<String,String> extra) {
        var opts = new java.util.HashMap<>(extra); opts.put("baseDir",root.toString());
        var r = new AgentSocket.Result(); AgentSocket.resolvePort(opts,r); return r;
    }
    @Test void discoversHybrisChild() throws Exception {
        home("hybris",12345); var r=resolve(Map.of()); assertNull(r.reason); assertEquals(12345,r.port);
    }
    @Test void discoversCloudLayout() throws Exception {
        home("core-customize/hybris",12346); var r=resolve(Map.of()); assertNull(r.reason); assertEquals(12346,r.port);
    }
    @Test void requiresMarkerAndPort() throws Exception {
        Path h=home("hybris",12345); Files.delete(h.resolve("bin/platform/build.xml"));
        assertNotNull(resolve(Map.of()).reason);
        Files.writeString(h.resolve("bin/platform/build.xml"),"<project/>"); Files.delete(h.resolve(".reclazz/agent.port"));
        assertNotNull(resolve(Map.of()).reason);
    }
    @Test void explicitMissingAndInvalidNeverDiscoverAnotherInstall() throws Exception {
        home("hybris",12345);
        assertNotNull(resolve(Map.of("portFile","missing.port")).reason);
        assertNotNull(resolve(Map.of("hybrisHome","missing")).reason);
        Path bad=Files.writeString(root.resolve("bad.port"),"bad");
        assertNotNull(resolve(Map.of("portFile",bad.toString())).reason);
    }
    @Test void originalDefaultWinsAndInvalidDefaultDoesNotFallBack() throws Exception {
        home("hybris",12345); Files.createDirectories(root.resolve(".idea/reclazz"));
        Path port=Files.writeString(root.resolve(".idea/reclazz/agent.port"),"12347");
        assertEquals(12347,resolve(Map.of()).port);
        Files.writeString(port,"invalid");assertNotNull(resolve(Map.of()).reason);
    }
    @Test void twoHomesRequireExplicitChoice() throws Exception {
        home("hybris",12345); home("core-customize/hybris",12346);
        var r=resolve(Map.of()); assertNotNull(r.reason);assertTrue(r.reason.contains("ambiguous"),r.reason);
        assertEquals(12345,resolve(Map.of("portFile","hybris/.reclazz/agent.port")).port);
    }
    @Test void refusesSymlinkEscape() throws Exception {
        Path outside=Files.createTempDirectory("mcp-outside");
        try {
            Files.createDirectories(outside.resolve("bin/platform"));Files.writeString(outside.resolve("bin/platform/build.xml"),"<project/>");
            Files.createDirectories(outside.resolve(".reclazz"));Files.writeString(outside.resolve(".reclazz/agent.port"),"12345");
            try { Files.createSymbolicLink(root.resolve("hybris"),outside); }
            catch (UnsupportedOperationException|java.nio.file.FileSystemException denied) { org.junit.jupiter.api.Assumptions.assumeTrue(false,"symlink unavailable: "+denied.getClass().getSimpleName()); }
            assertNotNull(resolve(Map.of()).reason);
        } finally {
            try(var paths=Files.walk(outside)) { for(Path p:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(p); }
        }
    }
}
