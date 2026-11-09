package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier

abstract class SpringXmlLiveTest(config: TestConfig, client: AgentEventClient, http: HttpVerifier, private val kind: String)
    : BatchLiveTest("Spring XML $kind", config, client, http) {
    override fun scenario() {
        val file = "reclazztest-batch-spring.xml"; val relative = "resources/$file"
        val before = json(get("$endpoint/xml")); check(before.isArray && before.size() > 0)
        val count = before.size()
        fun verify(value: String, ref: String, constructed: String, recreated: Boolean = false): JsonNode =
            pollJson("$endpoint/xml") { rows -> rows.size() == count && rows.withIndex().all { (i, row) ->
                row["owner"] == before[i]["owner"] && row["value"].asText() == value && row["reference"].asText() == ref &&
                    row["constructed"].asText() == constructed && row["holderIdentity"] == row["constructedIdentity"] &&
                    row["stableIdentity"] == before[i]["stableIdentity"] &&
                    (!recreated || row["constructedIdentity"] != before[i]["constructedIdentity"])
            } }
        verify("v1", "A", "constructor-v1")
        val baseline = original(relative).toString(Charsets.UTF_8)
        val edited = when (kind) {
            "property" -> replaceOnce(baseline, "name=\"value\" value=\"v1\"", "name=\"value\" value=\"v2\"")
            "reference" -> replaceOnce(baseline, "name=\"reference\" ref=\"reclazzBatchA\"", "name=\"reference\" ref=\"reclazzBatchB\"")
            else -> replaceOnce(baseline, "value=\"constructor-v1\"", "value=\"constructor-v2\"")
        }
        fixtureEdit(relative, edited.toByteArray(), { p,b -> xmlWrite(p,b,count,listOf(file,"reclazzBatch")) },
            { verify("v1","A","constructor-v1") }) {
            verify(if (kind == "property") "v2" else "v1", if (kind == "reference") "B" else "A",
                if (kind == "constructor") "constructor-v2" else "constructor-v1", kind == "constructor")
        }
    }
}
class SpringXmlPropertyLiveTest(c: TestConfig,a: AgentEventClient,h: HttpVerifier): SpringXmlLiveTest(c,a,h,"property")
class SpringXmlRefLiveTest(c: TestConfig,a: AgentEventClient,h: HttpVerifier): SpringXmlLiveTest(c,a,h,"reference")
class SpringXmlRecreateLiveTest(c: TestConfig,a: AgentEventClient,h: HttpVerifier): SpringXmlLiveTest(c,a,h,"constructor")
