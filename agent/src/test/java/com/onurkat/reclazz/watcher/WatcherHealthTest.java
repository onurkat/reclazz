/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.watcher;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WatcherHealthTest {
    private WatcherHealth observe(String state,long age) { return WatcherHealth.observe(Thread.currentThread(),state,0,age*1_000_000,100,null); }
    @Test void runningWhenIdleWithFreshHeartbeat(){assertEquals("watching",observe("watching",100).state());}
    @Test void stoppedWhenThreadEnds(){assertEquals("stopped",WatcherHealth.observe(new Thread(),"watching",0,0,100,null).state());}
    @Test void stoppedWhenWatchStateStopped(){assertEquals("stopped",observe("stopped",0).state());}
    @Test void stalledWhenHeartbeatOld(){assertEquals("stalled",observe("watching",30_000).state());}
    @Test void healthTextRunning(){assertFalse(lines(observe("watching",1)).get(0).startsWith("file watcher stopped"));}
    @Test void healthTextStalled(){assertTrue(lines(observe("watching",30_000)).get(0).startsWith("file watcher has not completed"));}
    @Test void healthTextStopped(){assertTrue(lines(observe("stopped",0)).get(0).startsWith("file watcher stopped (unknown)"));}
    private java.util.List<String> lines(WatcherHealth h){return com.onurkat.reclazz.agent.SessionReport.lines(1,0,0,3,false,h);}
}
