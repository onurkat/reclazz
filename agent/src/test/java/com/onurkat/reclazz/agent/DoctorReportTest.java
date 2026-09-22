/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.nio.file.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DoctorReportTest {
    @TempDir Path dir;
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode query(StatusServer server) throws Exception {
        var replies = new ArrayList<String>();
        server.handleCommand("DOCTOR req_1", replies::add);
        assertEquals(1, replies.size());
        String message = json.readTree(replies.get(0)).get("message").asText();
        assertTrue(message.startsWith("DOCTOR_RESULT req_1 "));
        return json.readTree(message.substring("DOCTOR_RESULT req_1 ".length()));
    }

    @Test void reportsOnlyWiredCapabilitiesAndTheVerificationSessionWithoutMutating() throws Exception {
        var server = new StatusServer(0,null);
        JsonNode cold=query(server);
        assertEquals("observed",cold.get("status").asText());
        assertEquals("unavailable",cold.get("watcherState").asText());
        assertEquals("",cold.get("sessionId").asText());
        assertFalse(cold.get("buildOwnershipSupported").asBoolean());
        assertFalse(cold.get("verifySupported").asBoolean());
        assertFalse(cold.get("scanSupported").asBoolean());
        var ledger = ReloadVerificationTest.ledger();
        var f = new ReloadBuildTest.Fixture();
        f.queue.build("alice","started",()->fail("scan must not run"));
        server.setBuildListener(state -> fail("legacy build must not run"));
        assertFalse(query(server).get("buildOwnershipSupported").asBoolean());
        server.setOwnedBuildListener((owner,state)->{fail("build must not run");return false;});
        server.setScanner(()->fail("scan must not run"));
        server.setVerification(ledger);
        server.setDoctorContext(null,f.queue::buildHoldKind);
        JsonNode warm=query(server);
        assertEquals("named",warm.get("buildHold").asText());
        assertEquals("named",f.queue.buildHoldKind());
        assertTrue(warm.get("buildOwnershipSupported").asBoolean());
        assertTrue(warm.get("verifySupported").asBoolean());
        assertTrue(warm.get("scanSupported").asBoolean());
        assertEquals(ledger.sessionId(),warm.get("sessionId").asText());
        assertEquals(json.readTree(ledger.query("q","A","a".repeat(64))).get("sessionId"),warm.get("sessionId"));
        assertNotEquals(ReloadVerificationTest.ledger().sessionId(),ledger.sessionId());
        assertEquals(Long.toString(ProcessHandle.current().pid()),warm.get("pid").asText());
        assertEquals(System.getProperty("user.dir"),warm.get("workingDirectory").asText());
        assertFalse(warm.get("reloadConfirmed").asBoolean());
    }

    @Test void badTokensAreIgnoredAndOversizedEvidenceFailsClosed() throws Exception {
        var server=new StatusServer(0,null);var replies=new ArrayList<String>();
        for(String cmd:List.of("DOCTOR", "DOCTOR a b", "DOCTOR x/y", "DOCTOR "+"x".repeat(65))) server.handleCommand(cmd,replies::add);
        assertTrue(replies.isEmpty());
        server.setDoctorContext(null,()->"x".repeat(5000));
        JsonNode result=query(server);
        assertEquals("unavailable",result.get("status").asText());
        assertFalse(result.has("buildOwnershipSupported"));
        assertEquals("req_1",result.get("requestId").asText());
    }

    @Test void correlatedReplyIsNotBroadcast() throws Exception {
        Path port=dir.resolve("port");var server=new StatusServer(0,port);server.start();
        try(var requester=new Socket("127.0.0.1",Integer.parseInt(Files.readString(port)));
            var observer=new Socket("127.0.0.1",requester.getPort())) {
            requester.setSoTimeout(2000);observer.setSoTimeout(2000);
            var in=new BufferedReader(StatusServer.readerFor(requester.getInputStream()));
            var other=new BufferedReader(StatusServer.readerFor(observer.getInputStream()));
            assertTrue(in.readLine().contains("CONNECTED"));assertTrue(other.readLine().contains("CONNECTED"));
            requester.getOutputStream().write("DOCTOR live-token\n".getBytes(StandardCharsets.UTF_8));requester.getOutputStream().flush();
            String message=json.readTree(in.readLine()).get("message").asText();
            assertTrue(message.startsWith("DOCTOR_RESULT live-token "));
            observer.setSoTimeout(200);assertThrows(SocketTimeoutException.class,other::readLine);
        } finally {server.stop();}
    }
}
