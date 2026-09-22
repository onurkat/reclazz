/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildOwnershipTest {
    private boolean signal(ReloadBuildTest.Fixture f, String owner, String state) {
        return f.queue.build(owner, state, () -> f.scan.run());
    }

    @Test void otherOwnersAndLegacySignalsCannotChangeAnOwnedHold() {
        for (String state : List.of("started", "ok", "failed")) {
            var f = new ReloadBuildTest.Fixture();
            assertTrue(signal(f,"alice","started")); f.write("A",1);
            assertFalse(signal(f,"bob",state));
            assertFalse(signal(f,null,state)); f.run(); assertTrue(f.applied.isEmpty());
            assertTrue(signal(f,"alice","failed"));
            assertFalse(signal(f,"bob","ok")); f.run(); assertTrue(f.applied.isEmpty());
            assertTrue(signal(f,"alice","started")); f.write("A",2);
            assertTrue(signal(f,"alice","ok")); f.run(); assertEquals(List.of(2),f.applied);
            assertTrue(signal(f,"bob","started"));
            assertFalse(signal(f,"alice","ok"));
        }
    }

    @Test void namedCompletionRequiresStartAndCannotTakeLegacyHold() {
        var f = new ReloadBuildTest.Fixture();
        assertFalse(signal(f,"alice","ok")); assertFalse(signal(f,"alice","failed"));
        f.build("started"); assertFalse(signal(f,"alice","started"));
        f.write("A",1); f.build("ok"); f.run(); assertEquals(List.of(1),f.applied);
        assertTrue(signal(f,"alice","started"));
        for(String invalid:List.of("","has space","a/b","x".repeat(65))) assertFalse(signal(f,invalid,"ok"));
        assertFalse(signal(f,"alice","bogus"));
    }

    @Test void queuedOkAndFailedCaptureRetainTheOwner() {
        for(boolean scanFailure:List.of(true,false)) {
            var f = new ReloadBuildTest.Fixture(); assertTrue(signal(f,"alice","started")); f.write("A",1);
            if(scanFailure) f.scan=()->{throw new IllegalStateException("scan failed");};
            else f.disk.remove(f.path("A"));
            assertTrue(signal(f,"alice","ok")); assertFalse(signal(f,"bob","started"));
            f.run(); assertTrue(f.applied.isEmpty()); assertFalse(signal(f,"bob","ok"));
            assertFalse(signal(f,null,"ok")); assertFalse(signal(f,"bob","started"));
            f.scan=()->{};f.disk.put(f.path("A"),new byte[]{2});
            assertTrue(signal(f,"alice","ok"));f.run();assertEquals(List.of(2),f.applied);
            assertTrue(signal(f,"bob","started"));
        }
    }

    @Test void newerSameOwnerFailureInvalidatesQueuedAcceptance() {
        var f=new ReloadBuildTest.Fixture();assertTrue(signal(f,"alice","started"));f.write("A",1);
        assertTrue(signal(f,"alice","ok"));assertTrue(signal(f,"alice","started"));
        f.write("A",2);assertTrue(signal(f,"alice","failed"));f.run();assertTrue(f.applied.isEmpty());
        assertFalse(signal(f,"bob","ok"));assertTrue(signal(f,"alice","ok"));f.run();assertEquals(List.of(2),f.applied);
    }

    @Test void simultaneousOwnersHaveExactlyOneWinner() throws Exception {
        var f=new ReloadBuildTest.Fixture();var start=new CountDownLatch(1);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var a=executor.submit(()->{start.await();return signal(f,"alice","started");});
            var b=executor.submit(()->{start.await();return signal(f,"bob","started");});
            start.countDown();assertNotEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
        } finally {executor.shutdownNow();}
    }

    @Test void socketGrammarAndRepliesPreserveOwnerAndRejectConflicts() {
        var f=new ReloadBuildTest.Fixture();var server=new StatusServer(0,null);
        server.setOwnedBuildListener((owner,state)->signal(f,owner,state));
        var replies=new ArrayList<String>();
        for(String invalid:List.of("BUILD started owner=a", "BUILD started request=x owner=a/b",
                "BUILD started request=x owner=", "BUILD started request=x owner=a owner=b",
                "BUILD started request=x request=y", "BUILD started owner=a owner=b")) server.handleCommand(invalid,replies::add);
        assertTrue(replies.isEmpty());
        server.handleCommand("BUILD started request=one owner=alice",replies::add);
        assertTrue(replies.remove(0).contains("BUILD_ACK one started owner=alice"));
        server.handleCommand("BUILD ok owner=bob request=two",replies::add);
        assertTrue(replies.remove(0).contains("BUILD_REJECTED two ok owner=bob"));
        server.handleCommand("BUILD ok request=legacy",replies::add);
        assertTrue(replies.remove(0).contains("BUILD_REJECTED legacy ok"));
        server.handleCommand("BUILD failed request=three owner=alice",replies::add);
        assertTrue(replies.remove(0).contains("BUILD_ACK three failed owner=alice"));
    }
    @Test void rejectionGoesOnlyToTheRequester() throws Exception {
        var port = java.nio.file.Files.createTempDirectory("owned-receipt").resolve("port");
        var server = new StatusServer(0, port);
        server.setOwnedBuildListener((owner, state) -> false);
        server.start();
        try (var requester = new java.net.Socket("127.0.0.1", Integer.parseInt(java.nio.file.Files.readString(port)));
             var observer = new java.net.Socket("127.0.0.1", requester.getPort())) {
            requester.setSoTimeout(2000); observer.setSoTimeout(2000);
            var in = new java.io.BufferedReader(StatusServer.readerFor(requester.getInputStream()));
            var other = new java.io.BufferedReader(StatusServer.readerFor(observer.getInputStream()));
            assertTrue(in.readLine().contains("CONNECTED"));
            assertTrue(other.readLine().contains("CONNECTED"));
            requester.getOutputStream().write("BUILD ok request=denied owner=bob\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            requester.getOutputStream().flush();
            assertTrue(in.readLine().contains("BUILD_REJECTED denied ok owner=bob"));
            observer.setSoTimeout(200);
            assertThrows(java.net.SocketTimeoutException.class, other::readLine);
        } finally {
            server.stop(); java.nio.file.Files.deleteIfExists(port); java.nio.file.Files.deleteIfExists(port.getParent());
        }
    }

}
