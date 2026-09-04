/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.reload

/**
 * Whether the agent answering on the socket is the one this plugin ships.
 *
 * Two different acts keep them in step, and people do one without the other.
 * The plugin updates when the IDE updates it. The jar a SAP Commerce server
 * attaches is named in `wrapper.conf`, which the platform regenerates only when
 * `ant` runs, and the staged copy under `~/.reclazz/agent` is refreshed by the
 * plugin but read by a process that started before it. So the ordinary state
 * after an upgrade is a new plugin talking to last month's agent, with the
 * fixes in the release notes not present in the thing that is running.
 *
 * The wire has carried a protocol version since the beginning, and it has
 * always been 1, because the protocol has not changed. It answers a question
 * nobody was asking. The release is the one that differs.
 *
 * Nothing here is an error. A mismatch works; it just is not what the release
 * notes describe, and saying so once is the difference between an afternoon and
 * a restart.
 */
object AgentVersionMatch {

    /**
     * The sentence to show, or null when there is nothing worth saying.
     *
     * @param agentVersion what the agent reported, empty from any agent older
     *                     than 1.1.0, which did not send it. Silent in that
     *                     case: "older than 1.1.0" is not enough to act on, and
     *                     a version notice about a version nobody knows is
     *                     noise.
     */
    fun describe(pluginVersion: String, agentVersion: String): String? {
        val plugin = pluginVersion.trim()
        val agent = agentVersion.trim()
        if (agent.isEmpty() || agent == "unknown") return null
        if (plugin.isEmpty() || plugin == "?") return null
        if (plugin == agent) return null

        return "The running agent is $agent and this plugin is $plugin. It works, " +
            "and it is not what the $plugin release notes describe. The jar a server " +
            "attaches is the one named in its start script, so the agent changes when " +
            "that process restarts, not when the plugin updates."
    }
}
