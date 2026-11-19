package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier

/** A body edit keeps the singleton: same identities, the counter and map keep counting, the response shows the new code. */
class KeepSingletonStateLiveTest(c: TestConfig, a: AgentEventClient, h: HttpVerifier) : BatchLiveTest("Singleton keeps state", c, a, h) {
    override fun scenario() {
        val keep = "$occ/reclazzkeep"
        fun versions(controller: String, service: String, inherited: String = "inherited-v1") = pollJson("$keep/version") {
            it["controllerVersion"]?.asText() == controller && it["serviceVersion"]?.asText() == service &&
                it["inheritedVersion"]?.asText() == inherited && it["inheritedConfigured"]?.asBoolean() == true
        }
        fun hit() = json(get("$keep/hit"))
        fun edit(relative: String, before: String, after: String, controller: String, service: String, inherited: String = "inherited-v1") {
            versions("controller-v1", "service-v1")
            val start = hit()
            val changed = replaceOnce(original(relative).toString(Charsets.UTF_8), "\"$before\"", "\"$after\"")
            var mark = 0L
            javaEdit(relative, changed, { versions("controller-v1", "service-v1") }, beforeWrite = { mark = it }) {
                if (relative.endsWith("KeepStateInheritedService.java")) {
                    val decisions = agentClient.getEvents().filter { it.sequence > mark && it.level == "RELOAD" && it.message.contains("com.onurkat.reclazztest.inherited.KeepStateInheritedService") }
                    check(decisions.any { it.message.contains("bean reclazzKeepStateInheritedService kept, state preserved") }) { "Missing actual keep-policy decision: $decisions" }
                }
                versions(controller, service, inherited)
                val next = hit()
                for (identity in listOf("controllerIdentity", "serviceIdentity", "inheritedIdentity"))
                    check(next[identity].asLong() == start[identity].asLong()) { "$identity changed after a body edit of $relative: $start -> $next" }
                check(next["count"].asInt() == start["count"].asInt() + 1) { "Controller counter reset after a body edit of $relative: $start -> $next" }
                for (field in listOf("map", "inheritedMap"))
                    check(next[field]["visits"].asInt() == start[field]["visits"].asInt() + 1) { "$field reset after a body edit of $relative: $start -> $next" }
            }
        }
        edit("src/com/onurkat/reclazztest/batch/KeepStateProbe.java", "service-v1", "service-v2", "controller-v1", "service-v2")
        edit("src/com/onurkat/reclazztest/occ/KeepStateOccController.java", "controller-v1", "controller-v2", "controller-v2", "service-v1")
        edit("src/com/onurkat/reclazztest/inherited/KeepStateInheritedService.java", "inherited-v1", "inherited-v2", "controller-v1", "service-v1", "inherited-v2")
    }
}
