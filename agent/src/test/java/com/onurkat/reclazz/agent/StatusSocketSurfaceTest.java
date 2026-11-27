/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.e2e.harness.StatusTestClient;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one socket the agent opens, and what can be done to it.
 *
 * <p>It is bound to loopback and answers two read-only questions, which is what
 * the README promises anyone deciding whether to run this on a shared machine.
 * Probed against a live agent rather than assumed: an unknown command is
 * ignored, so is a blank one, a hundred-kilobyte line is refused and the server
 * is still answering afterwards, and a class name shaped like a path traversal
 * or an absolute path reads nothing and is told there is no class by that name.
 *
 * <p>The part worth a test rather than a probe is the reflection. A DIAGNOSE
 * argument comes back inside the JSON the IDE parses, so an argument carrying
 * quotes could forge a field and put a line of somebody else's choosing into
 * the developer's status view, which is the one thing this surface could be
 * made to do. It cannot, and this is what keeps it that way.
 */
class StatusSocketSurfaceTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path statusDirectory;

    @Test
    void publishesPrivateCredentialsBeforeServingAnyEvents(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var file = dir.resolve("port");
        var server = new StatusServer(0, file);
        server.start();
        try {
            var lines = java.nio.file.Files.readAllLines(file);
            assertEquals(2, lines.size());
            assertTrue(lines.get(1).matches("token=[0-9a-f]{64}"));
            if (java.nio.file.Files.getFileStore(dir).supportsFileAttributeView("posix"))
                assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                        java.nio.file.Files.getPosixFilePermissions(file));
            try (var socket = new java.net.Socket("127.0.0.1", Integer.parseInt(lines.get(0)))) {
                socket.setSoTimeout(200);
                server.onEvent("INFO", "private test event");
                assertThrows(java.net.SocketTimeoutException.class, () -> socket.getInputStream().read());
            }
        } finally { server.stop(); }
    }

    @Test
    void commandsWaitForAuthenticationWhileOtherClientsCanConnect() throws Exception {
        var file = statusDirectory.resolve("endpoint");
        var status = new StatusServer(0, file);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        status.setHealthReporter(() -> List.of("calls=" + calls.incrementAndGet()));
        status.start();
        try {
            var endpoint = com.onurkat.reclazz.protocol.StatusEndpoint.read(file);
            try (var silent = new java.net.Socket("127.0.0.1", endpoint.port())) {
                for (String first : List.of("HEALTH", "AUTH " + "0".repeat(64))) {
                    try (var refused = new java.net.Socket("127.0.0.1", endpoint.port())) {
                        refused.setSoTimeout(4000);
                        refused.getOutputStream().write((first + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        assertEquals(-1, refused.getInputStream().read());
                        assertEquals(0, calls.get());
                    }
                }
                try (var allowed = StatusTestClient.connect(file)) {
                    allowed.setSoTimeout(4000);
                    var reader = new java.io.BufferedReader(StatusServer.readerFor(allowed.getInputStream()));
                    assertTrue(reader.readLine().contains("CONNECTED"));
                    allowed.getOutputStream().write("HEALTH\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    assertTrue(reader.readLine().contains("calls=1"));
                    status.onEvent("INFO", "post-auth event");
                    assertTrue(reader.readLine().contains("post-auth event"));
                }
                silent.setSoTimeout(4000);
                assertEquals(-1, silent.getInputStream().read());
            }
        } finally { status.stop(); }
    }

    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC, org.junit.jupiter.api.condition.OS.LINUX})
    @Test
    void authenticatesInAGroupWritableProjectDirectory() throws Exception {
        var shared = java.nio.file.Files.createDirectory(statusDirectory.resolve("shared"));
        java.nio.file.Files.setAttribute(shared, "unix:mode", 02775);
        checkPrivateConnection(shared.resolve("agent.port"));
    }

    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC, org.junit.jupiter.api.condition.OS.LINUX})
    @Test
    void authenticatesWithAPrivateFileInAForeignOwnedDirectory() throws Exception {
        var shared = java.nio.file.Path.of("/tmp").toRealPath();
        org.junit.jupiter.api.Assumptions.assumeFalse(java.nio.file.Files.getOwner(shared).equals(
                java.nio.file.Files.getOwner(statusDirectory)), "Requires a writable directory owned by a different account");
        checkPrivateConnection(shared.resolve("reclazz-status-test-" + java.util.UUID.randomUUID() + ".port"));
    }

    private void checkPrivateConnection(java.nio.file.Path file) throws Exception {
        var status = new StatusServer(0, file);
        status.start();
        try (var socket = StatusTestClient.connect(file)) {
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                    java.nio.file.Files.getPosixFilePermissions(file));
            socket.setSoTimeout(3000);
            var reader = new java.io.BufferedReader(StatusServer.readerFor(socket.getInputStream()));
            assertTrue(reader.readLine().contains("CONNECTED"));
        } finally { status.stop(); }
        assertFalse(java.nio.file.Files.exists(file));
    }

    @Test
    void aRestartReplacesCredentialsAndOldCleanupPreservesTheNewFile() throws Exception {
        var file = statusDirectory.resolve("endpoint");
        java.nio.file.Files.writeString(file, "3210");
        boolean posix = java.nio.file.Files.getFileStore(file).supportsFileAttributeView("posix");
        if (posix) java.nio.file.Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        var first = new StatusServer(0, file);
        var second = new StatusServer(0, file);
        first.start();
        try {
            String before = java.nio.file.Files.readAllLines(file).get(1);
            second.start();
            assertFalse(before.equals(java.nio.file.Files.readAllLines(file).get(1)), "Restart must rotate the credential");
            first.stop();
            assertTrue(java.nio.file.Files.isRegularFile(file));
            if (posix) {
                assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), java.nio.file.Files.getPosixFilePermissions(file));
            } else {
                var acl = java.nio.file.Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class).getAcl();
                var user = file.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
                java.util.function.Predicate<java.nio.file.attribute.UserPrincipal> trusted = principal ->
                        principal.equals(user) || principal.getName().equalsIgnoreCase("NT AUTHORITY\\SYSTEM")
                                || principal.getName().equalsIgnoreCase("BUILTIN\\Administrators");
                assertTrue(trusted.test(java.nio.file.Files.getOwner(file)));
                assertFalse(acl.isEmpty());
                assertTrue(acl.stream().filter(entry -> entry.type() == java.nio.file.attribute.AclEntryType.ALLOW)
                        .allMatch(entry -> trusted.test(entry.principal())));
                assertTrue(acl.stream().anyMatch(entry -> entry.principal().equals(user)
                        && entry.type() == java.nio.file.attribute.AclEntryType.ALLOW
                        && entry.permissions().contains(java.nio.file.attribute.AclEntryPermission.READ_DATA)));
            }
        } finally { first.stop(); second.stop(); }
        assertFalse(java.nio.file.Files.exists(file));
    }

    @Test
    void missingPrivateFileDisablesListenerAndDanglingLinksStayIntact() throws Exception {
        assertThrows(java.io.IOException.class, () -> new StatusServer(0, null).start());
        assertTrue(StatusServer.listenerState().contains("disabled"));
        var link = statusDirectory.resolve("link");
        try {
            java.nio.file.Files.createSymbolicLink(link, statusDirectory.resolve("absent"));
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable");
        }
        assertThrows(java.io.IOException.class, () -> new StatusServer(0, link).start());
        assertTrue(java.nio.file.Files.isSymbolicLink(link));
        try (var children = java.nio.file.Files.list(statusDirectory)) { assertEquals(1, children.count()); }
    }

    private static StatusServer server() {
        return new StatusServer(0, null);
    }

    @Test
    void verificationValidatesItsGrammarAndRepliesOnlyToRequester() throws Exception {
        StatusServer status = server();
        var replies = new java.util.ArrayList<String>();
        String hash = "a".repeat(64);
        status.handleCommand("VERIFY request A " + hash, replies::add);
        assertTrue(replies.isEmpty(), "unavailable ledger must not invent a result");
        status.setVerification(new ReloadVerification(java.util.Map::of));
        for (String bad : List.of("VERIFY", "VERIFY x A", "VERIFY x A " + hash + " extra",
                "VERIFY a/b A " + hash, "VERIFY x ../A " + hash, "VERIFY x A nope",
                "VERIFY x A " + "A".repeat(64), "VERIFY x A\u0001 " + hash)) {
            status.handleCommand(bad, replies::add);
        }
        assertTrue(replies.isEmpty());
        var port = java.nio.file.Files.createTempDirectory("verify-socket").resolve("port");
        status = new StatusServer(0, port);
        status.setVerification(new ReloadVerification(java.util.Map::of));
        status.start();
        try (var requester = StatusTestClient.connect(port);
             var observer = StatusTestClient.connect(port)) {
            requester.setSoTimeout(2000); observer.setSoTimeout(2000);
            var in = new java.io.BufferedReader(StatusServer.readerFor(requester.getInputStream()));
            var other = new java.io.BufferedReader(StatusServer.readerFor(observer.getInputStream()));
            assertTrue(in.readLine().contains("CONNECTED")); assertTrue(other.readLine().contains("CONNECTED"));
            requester.getOutputStream().write(("VERIFY request-1 A " + hash + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            requester.getOutputStream().flush();
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String message = mapper.readTree(in.readLine()).get("message").asText();
            assertTrue(message.startsWith("VERIFY_RESULT request-1 "));
            var result = mapper.readTree(message.substring("VERIFY_RESULT request-1 ".length()));
            assertEquals("request-1", result.get("requestId").asText());
            assertEquals(hash, result.get("expectedSha256").asText());
            assertEquals("not_observed", result.get("status").asText());
            observer.setSoTimeout(200);
            assertThrows(java.net.SocketTimeoutException.class, other::readLine);
        } finally {
            status.stop(); java.nio.file.Files.deleteIfExists(port); java.nio.file.Files.deleteIfExists(port.getParent());
        }
    }

    @Test
    void repeatedReceiptQueriesReleaseClientSlotsWithoutABroadcast() throws Exception {
        var port = java.nio.file.Files.createTempDirectory("verify-poll").resolve("port");
        StatusServer status = new StatusServer(0, port);
        status.setVerification(new ReloadVerification(java.util.Map::of)); status.start();
        try {
            for (int i = 0; i < 12; i++) {
                try (var socket = StatusTestClient.connect(port)) {
                    socket.setSoTimeout(2000);
                    var in = new java.io.BufferedReader(StatusServer.readerFor(socket.getInputStream()));
                    assertTrue(in.readLine().contains("CONNECTED"));
                    socket.getOutputStream().write(("VERIFY poll A " + "a".repeat(64) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                    assertTrue(in.readLine().contains("VERIFY_RESULT poll"));
                }
                // Let the socket reader observe EOF; no heartbeat/broadcast is needed.
                Thread.sleep(25);
            }
        } finally { status.stop(); java.nio.file.Files.deleteIfExists(port.getParent()); }
    }

    @Test
    void invalidOrUnavailableBuildSignalsNeverGetReceipts() {
        StatusServer status = server();
        java.util.List<String> replies = new java.util.ArrayList<>();
        status.handleCommand("BUILD started request=valid", replies::add);
        assertTrue(replies.isEmpty());
        AtomicInteger calls = new AtomicInteger();
        status.setBuildListener(state -> calls.incrementAndGet());
        for (String input : List.of("BUILD nope request=x", "BUILD ok extra", "BUILD ok request=",
                "BUILD started request=a/b", "BUILD started request=x extra")) {
            status.handleCommand(input, replies::add);
        }
        assertEquals(0, calls.get());
        assertTrue(replies.isEmpty());
        status.setBuildListener(state -> { throw new IllegalStateException("not installed"); });
        status.handleCommand("BUILD started request=valid", replies::add);
        assertTrue(replies.isEmpty());
    }

    @Test
    void buildReceiptFollowsTheHoldAndGoesOnlyToItsRequester() throws Exception {
        java.nio.file.Path port = java.nio.file.Files.createTempDirectory("build-ack").resolve("port");
        StatusServer status = new StatusServer(0, port);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        status.setBuildListener(state -> {
            assertEquals("started", state);
            entered.countDown();
            try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        });
        status.start();
        try (java.net.Socket socket = StatusTestClient.connect(port);
             java.net.Socket observer = StatusTestClient.connect(port)) {
            socket.setSoTimeout(2000);
            observer.setSoTimeout(2000);
            var in = new java.io.BufferedReader(StatusServer.readerFor(socket.getInputStream()));
            var other = new java.io.BufferedReader(StatusServer.readerFor(observer.getInputStream()));
            assertTrue(in.readLine().contains("CONNECTED"));
            assertTrue(other.readLine().contains("CONNECTED"));
            socket.getOutputStream().write("BUILD started request=receipt-1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS), "BUILD request was not accepted");
            socket.setSoTimeout(200);
            observer.setSoTimeout(200);
            assertThrows(java.net.SocketTimeoutException.class, in::readLine, "ack escaped before hold");
            release.countDown();
            socket.setSoTimeout(2000);
            assertTrue(in.readLine().contains("BUILD_ACK receipt-1 started"));
            assertThrows(java.net.SocketTimeoutException.class, other::readLine, "ack was broadcast");
        } finally {
            release.countDown(); status.stop();
            java.nio.file.Files.deleteIfExists(port);
            java.nio.file.Files.deleteIfExists(port.getParent());
        }
    }

    @Test
    void buildAcceptsOnlyItsThreeStatesCaseInsensitively() {
        StatusServer server = server();
        java.util.List<String> received = new java.util.ArrayList<>();
        server.setBuildListener(received::add);
        for (String line : List.of("BUILD started", "BUILD ok", "build OK", "BUILD failed",
                "BUILD nonsense", "BUILDX ok", "BUILD", "BUILD ok extra")) server.handleCommand(line);
        assertEquals(List.of("started", "ok", "OK", "failed"), received);
    }

    @Test
    void anUnknownCommandIsIgnored() {
        StatusServer status = server();
        AtomicInteger asked = new AtomicInteger();
        status.setDiagnoser(name -> {
            asked.incrementAndGet();
            return List.of("answered");
        });

        status.handleCommand("SHUTDOWN");
        status.handleCommand("RELOAD com.acme.Order");
        status.handleCommand("");
        status.handleCommand("   ");
        status.handleCommand(null);

        assertEquals(0, asked.get(),
                "the socket answered something that is not one of its two questions");
    }

    /**
     * Refused rather than truncated: half a command is not a command, and the
     * cap is what keeps a client from making the agent hold whatever it felt
     * like sending.
     */
    @Test
    void anOversizedCommandIsRefused() {
        StatusServer status = server();
        AtomicInteger asked = new AtomicInteger();
        status.setDiagnoser(name -> {
            asked.incrementAndGet();
            return List.of("answered");
        });

        status.handleCommand("DIAGNOSE " + "A".repeat(100_000));

        assertEquals(0, asked.get(), "a hundred-kilobyte command was accepted");
    }

    @Test
    void diagnoseReachesTheDiagnoserWithTheNameItWasGiven() {
        StatusServer status = server();
        AtomicReference<String> asked = new AtomicReference<>();
        status.setDiagnoser(name -> {
            asked.set(name);
            return List.of("answered");
        });

        status.handleCommand("DIAGNOSE com.acme.Order");

        assertEquals("com.acme.Order", asked.get());
    }

    /** A name is a name: nothing opens it, so nothing is read by asking. */
    @Test
    void aPathShapedNameIsJustAName() {
        StatusServer status = server();
        AtomicReference<String> asked = new AtomicReference<>();
        status.setDiagnoser(name -> {
            asked.set(name);
            return List.of("no class by that name");
        });

        status.handleCommand("DIAGNOSE ../../../../etc/passwd");

        assertEquals("../../../../etc/passwd", asked.get(),
                "the name is handed on as text and looked up among watched classes");
    }

    /**
     * The reflection that matters. Whatever the argument was, it comes back
     * inside the message and has to stay there, because the other end parses
     * these lines and shows them to the developer as the agent's own words.
     */
    @Test
    void anArgumentCannotForgeAFieldInTheLineTheIdeReads() {
        String hostile = "x\",\"level\":\"ERROR\",\"message\":\"forged";

        String line = "{\"level\":\"INFO\",\"message\":\""
                + StatusServer.escapeJson("Reclazz diagnosis for " + hostile) + "\"}";

        assertTrue(line.contains("\\\"level\\\""),
                () -> "the quotes were not escaped, so the field is forgeable: " + line);
        assertEquals(1, unescapedOccurrences(line, "\"level\":"),
                () -> "a second level field appeared in the line: " + line);
    }

    @Test
    void controlCharactersAndBackslashesSurviveAsText() {
        String escaped = StatusServer.escapeJson("a\nb\tc\\def" + (char) 1);

        assertFalse(escaped.contains("\n"), "a raw newline would end the line early");
        assertFalse(escaped.contains("\t"), "a raw tab is not valid inside a JSON string");
        assertTrue(escaped.contains("\\\\d"), () -> "backslash not escaped: " + escaped);
        assertTrue(escaped.contains("\\u0001"), () -> "control character not escaped: " + escaped);
    }

    /** Occurrences that are not preceded by a backslash, which is the point. */
    private static int unescapedOccurrences(String line, String needle) {
        int count = 0;
        int at = 0;
        while ((at = line.indexOf(needle, at)) >= 0) {
            if (at == 0 || line.charAt(at - 1) != '\\') count++;
            at += needle.length();
        }
        return count;
    }

    /**
     * Through the socket rather than {@code handleCommand}: the cap on a
     * command used to be checked after the whole line had been read, and a
     * line that never ends is never whole. The client here sends a megabyte
     * with no newline; the server must close on it, and must still be
     * accepting the next client afterwards.
     */
    @Test
    void aLineThatNeverEndsDropsTheConnectionAndNothingElse() throws Exception {
        java.nio.file.Path portFile = statusDirectory.resolve("port");
        java.nio.file.Files.deleteIfExists(portFile);
        StatusServer status = new StatusServer(0, portFile);
        status.start();
        try {

            try (java.net.Socket flooder = StatusTestClient.connect(portFile)) {
                flooder.setSoTimeout(10_000);
                java.io.InputStream in = flooder.getInputStream();
                assertTrue(readLine(in).contains("CONNECTED"), "the welcome line comes first");

                byte[] chunk = new byte[64 * 1024];
                java.util.Arrays.fill(chunk, (byte) 'A');
                boolean writeRefused = false;
                try {
                    for (int i = 0; i < 16; i++) {          // one megabyte, no newline
                        flooder.getOutputStream().write(chunk);
                    }
                    flooder.getOutputStream().flush();
                } catch (java.io.IOException serverHungUp) {
                    writeRefused = true;
                }
                boolean closed = writeRefused;
                if (!closed) {
                    try {
                        closed = in.read() == -1;
                    } catch (java.io.IOException reset) {
                        closed = true;
                    }
                }
                assertTrue(closed, "a megabyte without a newline must end the connection");
            }

            try (java.net.Socket next = StatusTestClient.connect(portFile)) {
                next.setSoTimeout(10_000);
                assertTrue(readLine(next.getInputStream()).contains("CONNECTED"),
                        "the server is still accepting after dropping the flooder");
            }
        } finally {
            status.stop();
        }
    }

    private static String readLine(java.io.InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') sb.append((char) c);
        return sb.toString();
    }
}
