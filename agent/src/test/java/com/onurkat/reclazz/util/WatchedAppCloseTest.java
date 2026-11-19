/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.util;
import com.onurkat.reclazz.e2e.harness.WatchedApp;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class WatchedAppCloseTest {
    static final class Child extends Process {
        boolean alive=true,interruptOnce,timeout,inputClosed,errorClosed,outputClosed;int kills,waits;
        final InputStream input=new ByteArrayInputStream(new byte[0]){@Override public void close(){inputClosed=true;}};
        final InputStream error=new ByteArrayInputStream(new byte[0]){@Override public void close(){errorClosed=true;}};
        final OutputStream output=new ByteArrayOutputStream(){@Override public void close(){outputClosed=true;}};
        @Override public OutputStream getOutputStream(){return output;}
        @Override public InputStream getInputStream(){return input;}
        @Override public InputStream getErrorStream(){return error;}
        @Override public void destroy(){kills++;}
        @Override public Process destroyForcibly(){kills++;return this;}
        @Override public boolean isAlive(){return alive;}
        @Override public int exitValue(){if(alive)throw new IllegalThreadStateException();return 0;}
        @Override public int waitFor()throws InterruptedException{waitFor(1,TimeUnit.SECONDS);return 0;}
        @Override public boolean waitFor(long value,TimeUnit unit)throws InterruptedException{
            waits++;if(interruptOnce){interruptOnce=false;throw new InterruptedException("injected shutdown interrupt");}
            if(timeout)return false;alive=false;return true;
        }
    }
    WatchedApp app(Child child)throws Exception {
        var constructor=WatchedApp.class.getDeclaredConstructor(Path.class,Path.class,String.class,Process.class);constructor.setAccessible(true);
        return constructor.newInstance(Path.of("."),Path.of("."),"",child);
    }
    @Test void timeoutIsNotReportedAsSuccessfulChildCleanup()throws Exception {
        var child=new Child();child.timeout=true;assertThrows(IllegalStateException.class,()->app(child).close());assertTrue(child.alive);
    }
    @Test void interruptionStillWaitsForExitAndRestoresInterruptStatus()throws Exception {
        var child=new Child();child.interruptOnce=true;
        try{app(child).close();assertFalse(child.alive);assertEquals(2,child.waits);assertTrue(Thread.currentThread().isInterrupted());}
        finally{Thread.interrupted();}
    }
    @Test void completedChildClosesAllOwnedStreams()throws Exception {
        var child=new Child();app(child).close();assertFalse(child.alive);assertTrue(child.inputClosed);assertTrue(child.errorClosed);assertTrue(child.outputClosed);
    }
}
