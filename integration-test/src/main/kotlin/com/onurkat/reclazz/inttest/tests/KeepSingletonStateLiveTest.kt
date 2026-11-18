package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier

/** A body edit keeps the singleton: same identities, the counter and map keep counting, the response shows the new code. */
class KeepSingletonStateLiveTest(c: TestConfig, a: AgentEventClient, h: HttpVerifier) : BatchLiveTest("Singleton keeps state", c, a, h) {
    override fun scenario() {
        val keep = "$occ/reclazzkeep"
        fun versions(controller: String, service: String) = pollJson("$keep/version") {
            it["controllerVersion"]?.asText() == controller && it["serviceVersion"]?.asText() == service
        }
        fun hit() = json(get("$keep/hit"))
        fun edit(relative: String, before: String, after: String, controller: String, service: String) {
            versions("controller-v1", "service-v1")
            val start = hit()
            val changed = replaceOnce(original(relative).toString(Charsets.UTF_8), "\"$before\"", "\"$after\"")
            javaEdit(relative, changed, { versions("controller-v1", "service-v1") }) {
                versions(controller, service)
                val next = hit()
                for (identity in listOf("controllerIdentity", "serviceIdentity"))
                    check(next[identity].asLong() == start[identity].asLong()) { "$identity changed after a body edit of $relative: $start -> $next" }
                check(next["count"].asInt() == start["count"].asInt() + 1) { "Controller counter reset after a body edit of $relative: $start -> $next" }
                check(next["map"]["visits"].asInt() == start["map"]["visits"].asInt() + 1) { "Service map reset after a body edit of $relative: $start -> $next" }
            }
        }
        edit("src/com/onurkat/reclazztest/batch/KeepStateProbe.java", "service-v1", "service-v2", "controller-v1", "service-v2")
        edit("src/com/onurkat/reclazztest/occ/KeepStateOccController.java", "controller-v1", "controller-v2", "controller-v2", "service-v1")
    }
}
