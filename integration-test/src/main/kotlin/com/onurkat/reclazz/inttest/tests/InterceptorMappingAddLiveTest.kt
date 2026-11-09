package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpResult
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult
import com.onurkat.reclazz.inttest.report.TestStatus
import java.nio.file.Files
import java.nio.file.Path

internal fun mappingFields(body: String): Map<String, String> {
    val pairs = body.split(';').map { it.substringBefore('=') to it.substringAfter('=', "") }
    check(pairs.map { it.first }.distinct().size == pairs.size) { "Duplicate mapping proof fields" }
    return pairs.toMap()
}

internal fun mappingOwnerStateFailure(response: HttpResult, owners: Int, target: String, type: String,
    identities: Map<String, String> = emptyMap()): String? {
    if (response.statusCode != 200 || owners <= 0) return "Owner state HTTP ${response.statusCode}"
    val rows = runCatching { response.body.lineSequence().filter { it.isNotBlank() }.map(::mappingFields).toList() }
        .getOrNull() ?: return "Malformed owner state"
    if (rows.size != owners || rows.map { it["owner"] }.toSet() != (1..owners).map(Int::toString).toSet())
        return "Expected $owners distinct owner states"
    for (row in rows) {
        val owner = row.getValue("owner")
        val expected = mapOf("mappingType" to type, "targetBean" to target,
            "targetClass" to "com.onurkat.reclazztest.interceptors.AddedMappingInterceptor",
            "registrySame" to "true", "registryCount" to "1", "mappingCount" to "1",
            "typeMappingCount" to "1", "targetCount" to "1")
        if (expected.any { (key,value) -> row[key] != value } ||
            (row["modelServices"]?.toIntOrNull() ?: 0) <= 0 ||
            (row["mappingIdentity"]?.toIntOrNull() ?: 0) == 0 ||
            (identities.isNotEmpty() && row["mappingIdentity"] != identities[owner]))
            return "Owner $owner has not reached target=$target type=$type: $row"
    }
    return null
}

internal fun awaitMappingOwners(verifier: HttpVerifier, endpoint: String, timeoutMs: Long, owners: Int,
    target: String, type: String, identities: Map<String,String> = emptyMap()): Map<String,String> {
    val response = verifier.poll(timeoutMs, "All $owners mapping owners target=$target type=$type", {
        verifier.get(endpoint, budgetMs=it)
    }) { mappingOwnerStateFailure(it,owners,target,type,identities) == null }
    return response.body.lineSequence().filter { it.isNotBlank() }.map(::mappingFields)
        .associate { it.getValue("owner") to it.getValue("mappingIdentity") }
}
internal fun registryIdentityFailure(response: HttpResult): String? {
    val fields = runCatching { mappingFields(response.body) }.getOrNull()
    val owners = fields?.get("xmlOwners")?.toIntOrNull() ?: 0
    return if (response.statusCode == 200 && fields != null && fields["sameRegistry"] == "true" &&
        owners > 0 && fields["ownerMatches"] == owners.toString()) null
    else "ModelService registry identity differs or an owner was not verified: HTTP ${response.statusCode}: ${response.body}"
}

internal fun mappingSaveFailure(response: HttpResult, nonce: String): String? {
    val fields = runCatching { mappingFields(response.body) }.getOrNull()
    val expected = mapOf("nonce" to nonce, "base" to "validated-v1:reclazz-probe-$nonce|calls=1",
        "addedCalls" to "1", "calledByCurrent" to "true", "ownedRecords" to "0",
        "bean" to "true", "definition" to "true", "registered" to "true", "ownedRegistered" to "1")
    val allOwners = fields != null && (fields["xmlOwners"]?.toIntOrNull() ?: 0) > 0 && fields["ownedDefinitions"] == fields["xmlOwners"]
    return if (response.statusCode == 200 && fields != null && allOwners && expected.all { (key,value) -> fields[key] == value }) null
        else "Expected one actual added-interceptor call from the current registered bean and no owned DB records; HTTP ${response.statusCode}: ${response.body}"
}

/** Bean creation is not proof of interceptor registry membership or modelService.save execution. */
open class InterceptorMappingAddLiveTest(config: TestConfig, agentClient: AgentEventClient, httpVerifier: HttpVerifier, name: String = "Interceptor mapping add")
    : BaseTest(name, config, agentClient, httpVerifier) {
    private val fileName = "reclazztest-mapping-probe-spring.xml"
    private val file = Path.of(config.extPath,"resources",fileName)
    protected val endpoint = "${config.testEndpointBase}/mapping-probe"
    private var expectedOwners = 0
    private var mappingIdentities: Map<String,String> = emptyMap()
    protected fun template(name: String) = javaClass.classLoader.getResourceAsStream("templates/$name")!!.use { it.readBytes() }
    protected fun writeXml(bytes: ByteArray, allowNoChanges: Boolean = false) {
        val ownerState = httpVerifier.get("$endpoint/state", budgetMs=config.eventTimeoutMs)
        check(ownerState.statusCode == 200) { "XML ownership HTTP ${ownerState.statusCode}" }
        val owners = mappingFields(ownerState.body)["xmlOwners"]?.toIntOrNull() ?: 0
        check(owners > 0 && (expectedOwners == 0 || owners == expectedOwners)) { "XML ownership changed: ${ownerState.body}" }
        expectedOwners = owners
        val mark = agentClient.cursor()
        if (writeChanged(file,bytes)) {
            val outcomes = agentClient.waitForSpringXmlOutcomes(config.eventTimeoutMs,fileName,mark,owners)
            check(outcomes.none { it.message.contains("require restart") }) { outcomes.joinToString { it.message } }
            check(allowNoChanges || outcomes.none { it.message.contains("no live-applicable") }) {
                "Requested XML change did not apply in every owner: $outcomes"
            }
        }
    }
    protected fun awaitRequestedState(target: String, type: String) {
        mappingIdentities = awaitMappingOwners(httpVerifier,"$endpoint/owner-state",config.eventTimeoutMs,
            expectedOwners,target,type,mappingIdentities)
    }
    protected fun verifyRegistry() {
        val proof = httpVerifier.get("$endpoint/registry-proof", budgetMs=config.eventTimeoutMs)
        println("    Registry identity proof: HTTP ${proof.statusCode}: ${proof.body}")
        registryIdentityFailure(proof)?.let { error(it) }
    }
    protected open val setupTemplate = "MappingProbe_spring_v2.xml.txt"
    protected open fun verifyMapping() {
        awaitRequestedState("reclazzAddedMappingInterceptor","Product")
        val nonce = java.util.UUID.randomUUID().toString()
        val response = httpVerifier.post("$endpoint/save",mapOf("nonce" to nonce),config.eventTimeoutMs)
        println("    Mapping save proof: HTTP ${response.statusCode}: ${response.body}")
        mappingSaveFailure(response,nonce)?.let { error(it) }
        verifyRegistry()
    }
    override fun run(): TestResult {
        expectedOwners = 0; mappingIdentities = emptyMap()
        val start = System.currentTimeMillis()
        val baseline = template("MappingProbe_spring_v1.xml.txt")
        var initialTotal: String? = null
        var failure: Exception? = null
        var ownsEdit = false
        try {
            verifyRegistry()
            check(Files.readAllBytes(file).contentEquals(baseline)) { "Mapping fixture is not the immutable candidate baseline" }
            val before = httpVerifier.get("$endpoint/state", budgetMs=config.eventTimeoutMs)
            check(before.statusCode == 200) { "Mapping fixture not deployed: HTTP ${before.statusCode}" }
            val fields = mappingFields(before.body)
            check(fields["bean"] == "false" && fields["definition"] == "false" && fields["ownedRegistered"] == "0" && fields["ownedDefinitions"]=="0" && (fields["xmlOwners"]?.toIntOrNull() ?: 0)>0) { "Dirty native mapping baseline: ${before.body}" }
            initialTotal = fields.getValue("total")
            ownsEdit = true
            writeXml(template(setupTemplate))
            httpVerifier.poll(config.eventTimeoutMs,"new XML bean materialization", {
                httpVerifier.get("$endpoint/state", budgetMs=it)
            }) {
                it.statusCode == 200 && mappingFields(it.body).let { state ->
                    state["bean"]=="true" && state["definition"]=="true" &&
                        (state["xmlOwners"]?.toIntOrNull() ?: 0)>0 && state["ownedDefinitions"]==state["xmlOwners"]
                }
            }
            verifyMapping()
        } catch (e: Exception) { failure=e }
        finally {
            if (ownsEdit) {
                try {
                    val cleanup = httpVerifier.post("$endpoint/cleanup",budgetMs=config.eventTimeoutMs)
                    check(cleanup.statusCode == 200) { "Owned mapping cleanup HTTP ${cleanup.statusCode}" }
                    val state = mappingFields(cleanup.body)
                    check(state["bean"]=="false" && state["definition"]=="false" && state["ownedRegistered"]=="0" && state["ownedDefinitions"]=="0" && state["editedBean"]=="false" && state["total"]==initialTotal) { "Owned mapping cleanup failed: ${cleanup.body}" }
                    // Remove our native definitions first; restoring the empty XML can then be a real no-op.
                    writeXml(baseline,allowNoChanges=true)
                    verifyRegistry()
            check(Files.readAllBytes(file).contentEquals(baseline)) { "Mapping XML bytes not restored" }
                } catch(e: Exception) {
                    failure=IllegalStateException(listOfNotNull(failure?.message,"Mapping restore: ${e.message}").joinToString("; "),failure ?: e)
                } finally {
                    // Preserve the source baseline even if native restoration failed; failure remains visible.
                    val mark = agentClient.cursor()
                    if (writeChanged(file,baseline)) {
                        try {
                            val outcomes = agentClient.waitForSpringXmlOutcomes(config.eventTimeoutMs,fileName,mark,expectedOwners)
                            check(outcomes.none { it.message.contains("require restart") }) { outcomes.joinToString { it.message } }
                        } catch (e: Exception) {
                            failure = IllegalStateException("${failure?.message}; final XML restore: ${e.message}",failure ?: e)
                        }
                    }
                }
            }
        }
        return if(failure==null) result(start,TestStatus.PASS,"added mapping invoked exactly once; native registry and bytes restored")
            else result(start,TestStatus.FAIL,failure.message ?: failure.toString())
    }
}
