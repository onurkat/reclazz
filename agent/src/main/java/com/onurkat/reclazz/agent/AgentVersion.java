/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

/**
 * Which release of the agent is actually running.
 *
 * <p>Worth asking because the answer is often not the one the developer would
 * give. The jar a server attaches is named in its start script or in
 * {@code wrapper.conf}, which SAP Commerce regenerates only when {@code ant}
 * runs, and the staged copy under {@code ~/.reclazz/agent} is refreshed by the
 * IDE plugin. Updating the plugin and restarting the server are two different
 * acts, and between them the IDE is one release and the running agent is
 * another, with nothing saying so.
 *
 * <p>Read from the jar's own manifest rather than compiled in, so it cannot
 * disagree with the file it was read from. Unknown when the classes are on a
 * plain classpath, which is how the tests run them.
 */
public final class AgentVersion {

    private static final String VERSION = read();

    private AgentVersion() {
    }

    /** The release, or {@code "unknown"} when there is no manifest to ask. */
    public static String get() {
        return VERSION;
    }

    private static String read() {
        try {
            String fromPackage = AgentVersion.class.getPackage().getImplementationVersion();
            if (fromPackage != null && !fromPackage.isBlank()) return fromPackage;
        } catch (Throwable ignored) {
            // A version is never worth failing to start over.
        }
        return "unknown";
    }
}
