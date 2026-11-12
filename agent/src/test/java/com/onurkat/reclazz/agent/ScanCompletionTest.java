package com.onurkat.reclazz.agent;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ScanCompletionTest {
    @TempDir Path dir;
    private final ObjectMapper json = new ObjectMapper();
    private StatusServer server() throws Exception {
        var s = new StatusServer(0, dir.resolve(UUID.randomUUID()+".port"));
        s.setVerification(new ReloadVerification(Map::of)); s.start(); return s;
    }
    private JsonNode result(String line, String token) throws Exception {
        var event=json.readTree(line);assertEquals("INFO",event.get("level").asText());
        String prefix="SCAN_RESULT "+token+" ";String message=event.get("message").asText();
        assertTrue(message.startsWith(prefix),message);
        var r=json.readTree(message.substring(prefix.length()));assertEquals(token,r.get("requestId").asText());
        assertFalse(r.get("sessionId").asText().isBlank());assertFalse(r.get("reloadConfirmed").asBoolean());return r;
    }
    @Test void countsZeroAndDeliveredEventsWithoutConfirmingReload() throws Exception {
        var s=server();try {
            for(int count:List.of(0,4)) {
                s.setScanCompleter(()->count);var replies=new ArrayList<String>();
                s.handleCommand("SCAN request=x",replies::add);assertEquals(1,replies.size());
                var r=result(replies.get(0),"x");assertEquals("queued",r.get("status").asText());assertEquals(count,r.get("enqueued").asInt());
            }
        }finally{s.stop();}
    }
    @Test void eachConcurrentRequestWaitsForItsOwnCallback() throws Exception {
        var s=server();var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        var replies=new CopyOnWriteArrayList<String>();
        s.setScanCompleter(()->{entered.countDown();await(release);return 2;});
        var a=CompletableFuture.runAsync(()->s.handleCommand("SCAN request=a",replies::add));
        var b=CompletableFuture.runAsync(()->s.handleCommand("SCAN request=b",replies::add));
        try {assertTrue(entered.await(3,TimeUnit.SECONDS));assertTrue(replies.isEmpty());assertFalse(a.isDone());assertFalse(b.isDone());
            release.countDown();a.get(3,TimeUnit.SECONDS);b.get(3,TimeUnit.SECONDS);assertEquals(2,replies.size());
            assertEquals(Set.of("a","b"),Set.of(result(replies.get(0),json.readTree(replies.get(0)).get("message").asText().split(" ")[1]).get("requestId").asText(),
                result(replies.get(1),json.readTree(replies.get(1)).get("message").asText().split(" ")[1]).get("requestId").asText()));
        }finally{release.countDown();s.stop();}
    }
    @Test void stopDuringCallbackCannotClaimQueued() throws Exception {
        var s=server();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var replies=new CopyOnWriteArrayList<String>();
        s.setScanCompleter(()->{entered.countDown();await(release);return 1;});
        var task=CompletableFuture.runAsync(()->s.handleCommand("SCAN request=stop",replies::add));
        try {assertTrue(entered.await(3,TimeUnit.SECONDS));s.stop();release.countDown();task.get(3,TimeUnit.SECONDS);
            assertEquals("unavailable",result(replies.get(0),"stop").get("status").asText());
        }finally{release.countDown();s.stop();}
    }
    @Test void unavailableOrFailedHookNeverInventsZero() throws Exception {
        var s=server();try {
            for(int mode:List.of(0,1,2)) {
                if(mode==1)s.setScanCompleter(()->{throw new IllegalStateException("failed");});
                if(mode==2)s.setScanCompleter(()->-1);
                var replies=new ArrayList<String>();s.handleCommand("SCAN request=fail",replies::add);
                var r=result(replies.get(0),"fail");assertEquals("unavailable",r.get("status").asText());assertFalse(r.has("enqueued"));
            }
        }finally{s.stop();}
    }
    @Test void malformedRequestsDoNothingAndBareScanStillNudges() throws Exception {
        var s=server();var calls=new AtomicInteger();s.setScanner(calls::incrementAndGet);s.setScanCompleter(()->{fail("invalid scan");return 0;});
        try {var replies=new ArrayList<String>();for(String bad:List.of("SCAN request=", "SCAN request=a/b", "SCAN request="+"a".repeat(65),"SCAN request=a extra", "SCAN x"))s.handleCommand(bad,replies::add);
            assertTrue(replies.isEmpty());assertEquals(0,calls.get());s.handleCommand("scan",replies::add);assertEquals(1,calls.get());assertTrue(replies.isEmpty());
        }finally{s.stop();}
    }
    @Test void disconnectedRequesterDoesNotBroadcastOrPreventAnotherRequest() throws Exception {
        Path port=dir.resolve("wire.port");var s=new StatusServer(0,port);s.setVerification(new ReloadVerification(Map::of));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        s.setScanCompleter(()->{if(calls.incrementAndGet()==1){entered.countDown();await(release);}return 1;});s.start();
        try(var first=new Socket("127.0.0.1",Integer.parseInt(Files.readString(port)));
            var second=new Socket("127.0.0.1",first.getPort());var observer=new Socket("127.0.0.1",first.getPort())) {
            first.setSoTimeout(3000);second.setSoTimeout(3000);observer.setSoTimeout(3000);
            var in=new BufferedReader(StatusServer.readerFor(second.getInputStream()));var other=new BufferedReader(StatusServer.readerFor(observer.getInputStream()));
            new BufferedReader(StatusServer.readerFor(first.getInputStream())).readLine();in.readLine();other.readLine();
            first.getOutputStream().write("SCAN request=gone\n".getBytes(StandardCharsets.UTF_8));first.getOutputStream().flush();assertTrue(entered.await(3,TimeUnit.SECONDS));first.close();
            second.getOutputStream().write("SCAN request=live\n".getBytes(StandardCharsets.UTF_8));second.getOutputStream().flush();
            assertEquals("queued",result(in.readLine(),"live").get("status").asText());release.countDown();
            observer.setSoTimeout(200);assertThrows(SocketTimeoutException.class,other::readLine);
        }finally{release.countDown();s.stop();}
    }
    private static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new IllegalStateException("latch deadline");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
}
