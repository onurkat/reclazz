/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.springboot;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;

/**
 * Detects whether the Reclazz agent is attached to this JVM. The agent must be a
 * {@code -javaagent} present at JVM startup, so the reliable signal is the JVM's
 * own input arguments: a {@code -javaagent} entry that names the reclazz agent.
 * This asks nothing of the agent and touches no network.
 */
public final class ReclazzAgentDetector {

    private ReclazzAgentDetector() {
    }

    /** The {@code -javaagent} flag that loaded the Reclazz agent, or null if none. */
    public static String agentFlag(List<String> jvmArguments) {
        if (jvmArguments == null) {
            return null;
        }
        for (String arg : jvmArguments) {
            if (arg != null
                    && arg.startsWith("-javaagent")
                    && arg.toLowerCase(Locale.ROOT).contains("reclazz")) {
                return arg;
            }
        }
        return null;
    }

    /** Whether the given JVM arguments include the Reclazz agent. */
    public static boolean isAttached(List<String> jvmArguments) {
        return agentFlag(jvmArguments) != null;
    }

    /** Whether the Reclazz agent is attached to the running JVM. */
    public static boolean isAttached() {
        return isAttached(ManagementFactory.getRuntimeMXBean().getInputArguments());
    }

    /** The {@code -javaagent} flag on the running JVM, or null if the agent is absent. */
    public static String agentFlag() {
        return agentFlag(ManagementFactory.getRuntimeMXBean().getInputArguments());
    }
}
