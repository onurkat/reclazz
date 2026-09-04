/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.onurkat.reclazz.plugin.reload.AgentVersionMatch
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The plugin in the IDE and the agent in the server are updated by two
 * different acts, and people do one without the other.
 *
 * The plugin updates when the IDE updates it. The jar a SAP Commerce server
 * attaches is named in `wrapper.conf`, which the platform regenerates only when
 * `ant` runs, and the staged copy under `~/.reclazz/agent` is refreshed by the
 * plugin but read by a process that started before it. So the ordinary state
 * after an upgrade is a new plugin talking to last month's agent, with the
 * fixes in the release notes absent from the thing that is actually running.
 *
 * The socket has carried a protocol version since the beginning and it has
 * always been 1, because the protocol has not changed; it answers a question
 * nobody asks. The release is what differs, so that is what the agent now
 * sends, in a field an older plugin ignores.
 */
class AgentVersionMatchTest {

    @Test
    fun `matching versions are not worth a word`() {
        assertNull(AgentVersionMatch.describe("1.1.0", "1.1.0"))
    }

    @Test
    fun `an older agent is named, with what actually changes it`() {
        val said = AgentVersionMatch.describe("1.1.0", "1.0.28")

        assertNotNull(said)
        assertTrue(said.contains("1.0.28") && said.contains("1.1.0"),
            "both numbers, because which is which is the whole question: $said")
        assertTrue(said.contains("restarts"),
            "updating the plugin is not what changes the agent, and saying so is the fix: $said")
    }

    /** The other direction happens too, and reads the same way. */
    @Test
    fun `a newer agent is named as well`() {
        assertNotNull(AgentVersionMatch.describe("1.0.28", "1.1.0"))
    }

    /**
     * Every agent before 1.1.0 sends no version at all, which is most of the
     * agents this will meet. "Older than 1.1.0" is not enough to act on, and a
     * version notice about a version nobody knows is noise.
     */
    @Test
    fun `an agent that does not report its version is left alone`() {
        assertNull(AgentVersionMatch.describe("1.1.0", ""))
        assertNull(AgentVersionMatch.describe("1.1.0", "unknown"),
            "which is what the agent says when it is running from a plain classpath")
    }

    @Test
    fun `a plugin that cannot name itself says nothing either`() {
        assertNull(AgentVersionMatch.describe("", "1.0.28"))
        assertNull(AgentVersionMatch.describe("?", "1.0.28"))
    }

    @Test
    fun `whitespace is not a difference`() {
        assertNull(AgentVersionMatch.describe("1.1.0", " 1.1.0 "))
    }
}
