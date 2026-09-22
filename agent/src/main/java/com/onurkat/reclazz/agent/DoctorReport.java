/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.watcher.FileWatcher;
import java.util.List;
import java.util.stream.Collectors;

/** Evidence from this process; no version inference or mutation to probe support. */
final class DoctorReport {
    private DoctorReport() { }

    static String snapshot(String token, ReloadVerification verification, FileWatcher watcher,
                           String hold, boolean build, boolean scan) {
        FileWatcher.WatchEvidence watch = watcher == null
                ? new FileWatcher.WatchEvidence("unavailable", 0, 0, List.of(), false) : watcher.doctorEvidence();
        String json = "{" + field("requestId", token) + "," + field("status", "observed")
                + "," + field("detail", "Point-in-time evidence; compare JVM, directory and session before acting. Not reload proof.")
                + "," + field("sessionId", verification == null ? "" : verification.sessionId())
                + "," + field("agentVersion", AgentVersion.get())
                + "," + field("pid", Long.toString(ProcessHandle.current().pid()))
                + "," + field("javaVersion", System.getProperty("java.version", ""))
                + "," + field("vmName", System.getProperty("java.vm.name", ""))
                + "," + field("workingDirectory", System.getProperty("user.dir", ""))
                + "," + field("watcherState", watch.state())
                + "," + field("buildHold", hold)
                + ",\"watchedDirectoryCount\":" + watch.count()
                + ",\"unwatchableDirectoryCount\":" + watch.refused()
                + ",\"watchSampleTruncated\":" + watch.truncated()
                + ",\"watchedDirectories\":[" + watch.directories().stream().map(DoctorReport::quote).collect(Collectors.joining(",")) + "]"
                + ",\"buildOwnershipSupported\":" + build
                + ",\"verifySupported\":" + (verification != null)
                + ",\"scanSupported\":" + scan + ",\"reloadConfirmed\":false}";
        // StatusServer's outer message escaping must never truncate a JSON receipt.
        if (json.length() > 3900) throw new IllegalStateException("Doctor report exceeds bounded message size");
        return json;
    }

    static String unavailable(String token) {
        return "{" + field("requestId", token) + "," + field("status", "unavailable")
                + "," + field("detail", "Doctor evidence unavailable or exceeds bounded message size") + "}";
    }

    private static String field(String name, String value) { return quote(name) + ":" + quote(value); }
    private static String quote(String value) {
        if (value == null || value.length() > 512) throw new IllegalStateException("Doctor field unavailable or too long");
        return "\"" + StatusServer.escapeJson(value) + "\"";
    }
}
