/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SECURITY.md says the agent and the plugin make no outbound network
 * requests and send nothing anywhere: the class names, file paths and
 * property keys they handle stay on the machine. That is a claim about
 * every future change too, so it is read off the sources: the only sockets
 * are the status socket, bound to loopback, and the plugin's client to it,
 * which names 127.0.0.1; nothing else opens a connection.
 */
class NoOutboundNetworkTest {

    /** Files allowed to touch sockets, and the loopback marker each must carry. */
    private static final Map<String, String> LOOPBACK_ONLY = Map.of(
            "agent/StatusServer.java", "InetAddress.getLoopbackAddress()",
            "plugin/reload/AgentStatusClient.kt", "\"127.0.0.1\"",
            "plugin/reload/ReloadManager.kt", "\"127.0.0.1\"");

    private static final List<String> CLIENT_APIS = List.of(
            "HttpClient", "HttpURLConnection", ".openConnection(", "openStream(",
            "URLConnection", "HttpRequest", "SocketChannel.open", "DatagramSocket");

    @Test
    void nothingShippedOpensAConnectionToAnywhereButLoopback() throws IOException {
        Path agentSources = AgentSources.root();
        Path repo = agentSources.getParent().getParent().getParent().getParent();
        Path pluginSources = repo.resolve("src/main/kotlin");
        List<String> problems = new ArrayList<>();

        for (Path root : List.of(agentSources, pluginSources)) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>) files.filter(p -> p.toString().matches(".*\\.(java|kt)"))::iterator) {
                    String text = Files.readString(file);
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    String key = rel.replaceFirst("^com/onurkat/reclazz/", "");
                    for (String api : CLIENT_APIS) {
                        if (text.contains(api)) problems.add(rel + " uses " + api);
                    }
                    boolean sockets = text.contains("new Socket(") || text.contains("Socket(\"")
                            || text.contains("new ServerSocket(");
                    if (sockets) {
                        String marker = LOOPBACK_ONLY.get(key);
                        if (marker == null) {
                            problems.add(rel + " opens a socket and is not one of the two loopback endpoints");
                        } else if (!text.contains(marker)) {
                            problems.add(rel + " opens a socket without " + marker);
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), problems, "SECURITY.md promises no outbound network; these would break it");
    }
}
