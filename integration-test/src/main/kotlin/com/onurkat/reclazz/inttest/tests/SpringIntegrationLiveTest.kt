package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier

/** An edited Spring Integration service-activator answers new messages without a restart, once per owner. */
class SpringIntegrationLiveTest(c: TestConfig, a: AgentEventClient, h: HttpVerifier)
    : BatchLiveTest("Spring Integration endpoint edit", c, a, h) {
    override fun scenario() {
        val file = "reclazztest-si-probe-spring.xml"; val relative = "resources/$file"
        val before = json(get("$endpoint/si")); check(before.isArray && before.size() > 0)
        val count = before.size()
        fun verify(prefix: String): JsonNode = pollJson("$endpoint/si") { rows -> rows.size() == count && rows.all { row ->
            row["subscribers"].asInt() == 1 && row["replies"].size() == 4 && row["replies"].all { it.asText() == "$prefix:x" }
        } }
        verify("A")
        val edited = replaceOnce(original(relative).toString(Charsets.UTF_8), "expression=\"'A:' + payload\"", "expression=\"'B:' + payload\"")
        fixtureEdit(relative, edited.toByteArray(), { p, b -> xmlWrite(p, b, count) }, { verify("A") }) {
            println("SPRING_INTEGRATION_EDIT ${verify("B")}")
        }
    }
}
