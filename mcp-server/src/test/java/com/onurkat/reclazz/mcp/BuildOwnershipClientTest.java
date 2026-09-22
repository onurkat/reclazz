/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BuildOwnershipClientTest {
    @TempDir Path dir;

    @Test void oldOrWrongOwnerReceiptsNeverStartCompiler() throws Exception {
        for(String owner:List.of("", " owner=someone-else")) {
            Path marker=dir.resolve(owner.isEmpty()?"old":"wrong");
            try(var agent=new BuildSafetyTest.FakeAgent((in,out)->{
                String[] parts=in.readLine().split(" ");
                assertEquals("owner=alice",parts[3]);
                out.println("{\"level\":\"INFO\",\"message\":\"BUILD_ACK "+parts[2].substring(8)+" started"+owner+"\"}");
            })) {
                var opts=agent.opts();opts.put("owner","alice");
                assertNotEquals(0,BuildMain.execute(opts,BuildSafetyTest.child(marker,0)));
                assertFalse(Files.exists(marker));agent.verify();
            }
        }
    }

    @Test void explicitCliOwnerIsUsedForStartAndCompletion() throws Exception {
        Path marker=dir.resolve("compiled");
        try(var agent=new BuildSafetyTest.FakeAgent((in,out)->{
            String start=in.readLine();assertTrue(start.endsWith(" owner=recovery"));BuildSafetyTest.ack(out,start);
            String end=in.readLine();assertTrue(end.startsWith("BUILD ok "));assertTrue(end.endsWith(" owner=recovery"));BuildSafetyTest.ack(out,end);
        })) {
            var argv=new ArrayList<>(List.of("--port",""+agent.server.getLocalPort(),"--owner","recovery","--"));
            argv.addAll(BuildSafetyTest.child(marker,0));assertEquals(0,BuildMain.run(argv.toArray(String[]::new)));
            assertTrue(Files.exists(marker));agent.verify();
        }
    }

    @Test void mcpRequiresValidOwnerAndCorrelatesReturnedOwner() throws Exception {
        for(JsonElement invalid:List.of(JsonNull.INSTANCE,new JsonPrimitive(""),new JsonPrimitive("a/b"),
                new JsonPrimitive("x".repeat(65)),new JsonPrimitive(true))) {
            JsonObject request=request();request.getAsJsonObject("params").getAsJsonObject("arguments").add("owner",invalid);
            assertEquals(-32602,new McpServer().handle(request).getAsJsonObject("error").get("code").getAsInt());
        }
        JsonObject missing=request();missing.getAsJsonObject("params").getAsJsonObject("arguments").remove("owner");
        assertEquals(-32602,new McpServer().handle(missing).getAsJsonObject("error").get("code").getAsInt());
        try(var agent=new BuildSafetyTest.FakeAgent((in,out)->{
            String command=in.readLine();assertTrue(command.endsWith(" owner=alice"));BuildSafetyTest.ack(out,command);
        })) {
            McpServer server=new McpServer();server.handle(JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}").getAsJsonObject());
            JsonObject r=request();r.getAsJsonObject("params").getAsJsonObject("arguments").addProperty("port",""+agent.server.getLocalPort());
            JsonObject result=server.handle(r).getAsJsonObject("result");assertFalse(result.get("isError").getAsBoolean());
            assertEquals("alice",result.getAsJsonObject("structuredContent").get("owner").getAsString());agent.verify();
        }
    }

    private JsonObject request(){return JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"reclazz_build\",\"arguments\":{\"state\":\"started\",\"owner\":\"alice\",\"port\":\"1\"}}}").getAsJsonObject();}
}
