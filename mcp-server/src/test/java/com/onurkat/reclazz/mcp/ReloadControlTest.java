/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReloadControlTest {
    static void reply(PrintWriter out, String command, String fields) {
        String[] words = command.split(" ");
        JsonObject event = new JsonObject(); event.addProperty("level", "INFO");
        event.addProperty("message", "RELOAD_STATE " + words[2].substring(8) + " " + words[1] + " " + fields);
        out.println(event);
    }

    @Test void missingUncorrelatedAndMalformedRepliesNeverConfirmControl() throws Exception {
        for (String fields : List.of("missing", "wrong-token", "wrong-action", "paused=false pendingClasses=0 pendingActions=0 buildHold=none",
                "paused=true pendingClasses=-1 pendingActions=0 buildHold=none", "paused=true pendingClasses=999999999999 pendingActions=0 buildHold=none",
                "paused=true pendingClasses=1 pendingActions=0 buildHold=other", "paused=true")) {
            try (var agent = new BuildSafetyTest.FakeAgent((in,out) -> {
                String command = in.readLine();
                if (fields.equals("missing")) return;
                if (fields.equals("wrong-token")) command = "RELOAD pause request=stale";
                if (fields.equals("wrong-action")) command = command.replace("pause", "status");
                reply(out, command, fields.startsWith("wrong") ? "paused=true pendingClasses=0 pendingActions=0 buildHold=none" : fields);
            }); var session = BuildSession.open(agent.opts())) {
                assertThrows(IOException.class, () -> session.reloadControl("pause"), fields);
                agent.verify();
            }
        }
    }

    @Test void unrelatedMessagesAreIgnoredUntilMatchingReceipt() throws Exception {
        try (var agent = new BuildSafetyTest.FakeAgent((in,out) -> {
            String command=in.readLine();
            reply(out, "RELOAD status request=stale", "paused=false pendingClasses=0 pendingActions=0 buildHold=none");
            reply(out, command, "paused=true pendingClasses=1 pendingActions=0 buildHold=legacy");
        }); var session = BuildSession.open(agent.opts())) {
            var result=session.reloadControl("status");
            assertTrue(result.get("paused").getAsBoolean());
            assertFalse(result.get("reloadConfirmed").getAsBoolean());
            agent.verify();
        }
    }

    @Test void invalidActionsFailBeforeOpeningConnection() {
        for (String action : List.of("null", "{}", "\"stop\"", "\"pause\\nSCAN\"")) {
            var request=JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"reclazz_reload_control\",\"arguments\":{\"action\":"+action+"}}}").getAsJsonObject();
            assertEquals(-32602, new McpServer().handle(request).getAsJsonObject("error").get("code").getAsInt());
        }
    }
}
