package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ScanCompletionTest {
    @TempDir Path dir;
    private final ObjectMapper json=new ObjectMapper();
    @Test void receiptMeansQueuedWhilePauseKeepsTheOldBehavior() throws Exception {
        Path port=dir.resolve("agent.port");
        try(var app=WatchedApp.in(dir).agentArgs("startupDelaySec=1,debounceMs=5000,portFile="+port)
                .with("A",unit(1)).with("App","""
                    package app;
                    public class App { public static void main(String[] args) throws Exception {
                        A a=new A(); while(true){System.out.println("VALUE="+a.value());Thread.sleep(25);}
                    }}
                    """).start()) {
            app.awaitOrFail("VALUE=1","initial behavior");app.awaitOrFail("] Watching 1 director","watcher ready");
            try(var socket=new Socket("127.0.0.1",Integer.parseInt(Files.readString(port).trim()))) {
                socket.setSoTimeout(5000);var in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                send(socket,"DOCTOR identity");String doctor=message(in,"DOCTOR_RESULT identity ");
                String session=json.readTree(doctor.substring("DOCTOR_RESULT identity ".length())).get("sessionId").asText();
                send(socket,"RELOAD pause request=hold");assertTrue(message(in,"RELOAD_STATE hold pause ").contains("paused=true"));
                app.rewrite("A",unit(2));send(socket,"SCAN request=scan-1");
                String prefix="SCAN_RESULT scan-1 ";JsonNode receipt=json.readTree(message(in,prefix).substring(prefix.length()));
                assertEquals("queued",receipt.get("status").asText());assertEquals("scan-1",receipt.get("requestId").asText());
                assertEquals(session,receipt.get("sessionId").asText());assertTrue(receipt.get("enqueued").asInt()>=1,receipt::toString);
                assertFalse(receipt.get("reloadConfirmed").asBoolean());
                send(socket,"RELOAD status request=state");String state=message(in,"RELOAD_STATE state status ");
                assertTrue(state.contains("paused=true pendingClasses=1"),state);
                assertFalse(app.output().stream().anyMatch(line->line.contains("VALUE=2")),app::tail);
                send(socket,"RELOAD resume request=release");message(in,"RELOAD_STATE release resume ");
                app.awaitOrFail("VALUE=2","separate actual application result after resume");
            }
        }
    }
    private String message(BufferedReader in,String prefix) throws Exception {
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(6);
        while(System.nanoTime()<until){String line=in.readLine();assertNotNull(line);String m=json.readTree(line).path("message").asText();if(m.startsWith(prefix))return m;}
        throw new AssertionError("Missing correlated reply: "+prefix);
    }
    private static void send(Socket socket,String command) throws Exception {socket.getOutputStream().write((command+"\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();}
    private static String unit(int version){return "package app; public class A { public int value(){return "+version+";} }";}
}
