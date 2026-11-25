/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.watcher;

/** Monotonic, observational watcher liveness. No application work runs here. */
public record WatcherHealth(String state, long quietMillis, String cause) {
    public static WatcherHealth observe(Thread thread, String state, long beat, long now, long pollMillis, String cause) {
        long quiet=Math.max(0,(now-beat)/1_000_000);
        String result=state.equals("stopped") || (thread!=null && !thread.isAlive()) ? "stopped"
                : thread==null || state.equals("starting") ? "starting"
                : quiet>=Math.max(30_000,3*pollMillis) ? "stalled" : "watching";
        return new WatcherHealth(result,quiet,cause==null ? "unknown" : cause);
    }
    public String line() {
        if(state.equals("stopped"))return "file watcher stopped ("+cause+"); nothing reloads until the server restarts";
        if(state.equals("stalled"))return "file watcher has not completed a poll for "+quietMillis/1000+" s";
        return "file watcher "+state;
    }
    public boolean unhealthy() { return state.equals("stopped") || state.equals("stalled"); }
}
