/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoctorTest {
    static JsonObject evidence(String command) {
        JsonObject d=new JsonObject();d.addProperty("requestId",command.substring("DOCTOR ".length()));
        d.addProperty("status","observed");d.addProperty("detail","Point-in-time evidence");
        for(String k:List.of("sessionId","agentVersion","javaVersion","vmName"))d.addProperty(k,"evidence");
        d.addProperty("pid","123");d.addProperty("workingDirectory","/target/project");
        d.addProperty("watcherState","watching");d.addProperty("buildHold","none");
        d.addProperty("watchedDirectoryCount",1);d.addProperty("unwatchableDirectoryCount",0);
        d.addProperty("watchSampleTruncated",false);JsonArray paths=new JsonArray();paths.add("/target/project/classes");d.add("watchedDirectories",paths);
        for(String k:List.of("buildOwnershipSupported","verifySupported","scanSupported"))d.addProperty(k,true);
        d.addProperty("reloadConfirmed",false);return d;
    }
    static void send(PrintWriter out,String command,JsonObject d) {
        JsonObject event=new JsonObject();event.addProperty("level","INFO");
        event.addProperty("message","DOCTOR_RESULT "+command.substring(7)+" "+d);out.println(event);
    }
    static JsonObject call(McpServer s,int port) {
        JsonObject r=JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"reclazz_doctor\",\"arguments\":{\"timeoutMs\":\"200\"}}}").getAsJsonObject();
        r.getAsJsonObject("params").getAsJsonObject("arguments").addProperty("port",""+port);return s.handle(r).getAsJsonObject("result");
    }
    static JsonObject data(JsonObject result) {return JsonParser.parseString(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();}

    @Test void oldMalformedUncorrelatedAndOversizedRepliesNeverInventSupport() throws Exception {
        for(String mode:List.of("old","token","missing","type","complete","oversize","garbage","unavailable")) {
            try(var agent=new BuildSafetyTest.FakeAgent((in,out)->{
                String c=in.readLine();assertTrue(c.startsWith("DOCTOR "));
                JsonObject d=evidence(c);
                switch(mode) {
                    case "old" -> {out.println("{\"level\":\"INFO\",\"message\":\"Health ok\"}");return;}
                    case "unavailable" -> d.addProperty("status","unavailable");
                    case "token" -> d.addProperty("requestId","wrong");
                    case "missing" -> d.remove("buildOwnershipSupported");
                    case "type" -> d.addProperty("verifySupported","true");
                    case "complete" -> d.addProperty("reloadConfirmed",true);
                    case "oversize" -> d.addProperty("detail","x".repeat(20000));
                    case "garbage" -> {out.println("{bad");return;}
                }
                send(out,c,d);
            })) {
                JsonObject result=call(new McpServer(),agent.server.getLocalPort());assertTrue(result.get("isError").getAsBoolean(),mode);
                JsonObject d=data(result);assertEquals("unavailable",d.get("status").getAsString());
                assertFalse(d.has("buildOwnershipSupported"));assertFalse(d.get("reloadConfirmed").getAsBoolean());agent.verify();
            }
        }
    }

    @Test void incompleteSnapshotExplainsNextChecksWithoutPretendingReady() throws Exception {
        try(var agent=new BuildSafetyTest.FakeAgent((in,out)->{
            String c=in.readLine();JsonObject d=evidence(c);d.addProperty("watcherState","starting");
            d.addProperty("buildOwnershipSupported",false);d.addProperty("verifySupported",false);d.addProperty("sessionId","");
            d.addProperty("buildHold","named");d.addProperty("watchedDirectoryCount",2);d.addProperty("watchSampleTruncated",true);
            d.addProperty("unwatchableDirectoryCount",1);send(out,c,d);
        })) {
            JsonObject result=call(new McpServer(),agent.server.getLocalPort());assertFalse(result.get("isError").getAsBoolean());
            JsonObject d=data(result);assertEquals("/target/project",d.get("workingDirectory").getAsString());
            assertEquals(System.getProperty("user.dir"),d.get("clientWorkingDirectory").getAsString());
            String actions=d.get("nextActions").toString();
            for(String expected:List.of("startup","refused watches","incomplete","original owner","matching agent"))assertTrue(actions.contains(expected),actions);
            assertFalse(d.has("ready"));agent.verify();
        }
    }

    @Test void endpointErrorsStayWithinTheAdvertisedDetailBound() {
        JsonObject request=JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"reclazz_doctor\",\"arguments\":{}}}").getAsJsonObject();
        request.getAsJsonObject("params").getAsJsonObject("arguments").addProperty("port","x".repeat(1000));
        JsonObject result=new McpServer().handle(request).getAsJsonObject("result");
        assertTrue(result.get("isError").getAsBoolean());
        assertTrue(data(result).get("detail").getAsString().length()<=512);
        assertFalse(data(result).has("buildOwnershipSupported"));
    }

    @Test void inconsistentDirectoryEvidenceIsRejected() {
        for(String key:List.of("watchedDirectoryCount","unwatchableDirectoryCount","watchedDirectories","watchSampleTruncated")) {
            JsonObject d=evidence("DOCTOR x");d.add(key,JsonNull.INSTANCE);
            assertThrows(IOException.class,()->DoctorResult.parse(d.toString(),"x"));
        }
        JsonObject d=evidence("DOCTOR x");d.addProperty("watchedDirectoryCount",2);
        assertThrows(IOException.class,()->DoctorResult.parse(d.toString(),"x"));
    }
}
