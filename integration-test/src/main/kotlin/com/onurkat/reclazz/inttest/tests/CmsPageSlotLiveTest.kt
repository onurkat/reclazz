package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.http.HttpResult
import java.nio.file.Files
import java.nio.file.Path

internal data class CmsPageProof(val componentPk:String,val pagePk:String,val slotPk:String)
internal fun requireCmsPageHtml(response:HttpResult,mime:String?,nonce:String,version:String,role:String,marker:String,expected:CmsPageProof?=null):CmsPageProof {
    check(response.statusCode==200 && mime?.substringBefore(';')?.trim()?.lowercase()=="text/html") {"CMS page response is not HTML200"}
    check(response.body.trimStart().startsWith("<!doctype html>",ignoreCase=true))
    val rows=Regex("""<div id="reclazz-cms-page-proof" data-nonce="([^"]+)" data-marker="([^"]+)" data-uid="([^"]+)" data-pk="([0-9]+)" data-version="([^"]+)" data-page="([^"]+)" data-page-pk="([0-9]+)" data-slot="([^"]+)" data-slot-pk="([0-9]+)" data-position="([^"]+)"><span class="content">([^<]*)</span></div>""").findAll(response.body).toList()
    check(rows.size==1) {"Missing/duplicate page slot proof"}
    val f=rows.single().groupValues
    check(f[1]==nonce&&f[2]==marker&&f[3]=="$nonce-$role"&&f[4].toLong()>0&&f[5]==version&&f[6]=="$nonce-page"&&f[7].toLong()>0&&f[8]=="$nonce-$role-slot"&&f[9].toLong()>0&&f[10]==role&&f[11]=="$version-page-$role") {"Wrong page/slot/component/marker: ${response.body}"}
    val result=CmsPageProof(f[4],f[7],f[9]);check(expected==null||result==expected) {"Persisted page/slot/component identity changed"}
    return result
}

class CmsPageSlotLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):
    BaseTest("CMS native page slot selection (outer recovery required)",c,a,h) {
    override fun run()=runGatedProfile(name,{
        val root=Path.of(config.extPath).toRealPath().parent.resolve("reclazztestcms")
        CmsFixtureBaseline(root).checkBytes()
        val nonce=System.getenv("RECLAZZ_CMS_NONCE")
        if(nonce==null||!nonce.matches(Regex("reclazz-cms-[a-f0-9-]{36}"))||System.getenv("RECLAZZ_CMS_RECOVERY").isNullOrBlank()) "Owned CMS profile/recovery required" else {
            val home=Path.of(System.getenv("RECLAZZ_TEST_HYBRIS_HOME") ?: error("CMS home missing")).toRealPath()
            check(home==cleanSapHybris(root.parent.parent)) {"Foreign CMS home"}
            check(Files.isSameFile(home.resolve("bin/custom/reclazztestcms"),root)) {"Foreign CMS link"}
            check(Files.isSameFile(home.resolve("bin/custom/reclazztest"),Path.of(config.extPath))) {"Foreign base fixture link"}
            val state=batchJson.readTree(Files.readString(Path.of(System.getenv("RECLAZZ_CMS_RECOVERY"),"state.json")))
            check(state.path("home").asText()==home.toString()&&state.path("snapshotsVerified").isBoolean&&state.path("snapshotsVerified").asBoolean()&&state.path("targets").size()==17) {"CMS recovery boundary missing"}
            val gates=json(httpVerifier.get("/reclazztestcms/v2/test/batch/cms/gates"))
            val expected=linkedMapOf("agentArgsFound" to true,"agentOptionsMatchConfig" to true,"autoCompile" to true,
                "autoSolrReindex" to false,"resetStatics" to false,"autoUpdateRunningSystem" to false,"impexAllowRemove" to false,
                "profileExtensionLoaded" to true,"cmsExtensionsLoaded" to true,"profileWatched" to true,"masterTenant" to true,
                "cmsModelAvailable" to true,"cmsTypeAvailable" to true,"nativeBeansPresent" to true,
                "nativeSuperclass" to true,"inheritedHandler" to true,"jspViewResolver" to true)
            profileGateReason(gates,expected)
        }
    },::scenario)
    private fun scenario() {
        val nonce=System.getenv("RECLAZZ_CMS_NONCE")!!
        val endpoint="/reclazztestcms/v2/test/batch/cms/page-slot"
        val params=mapOf("nonce" to nonce)
        val root=Path.of(config.extPath).toRealPath().parent.resolve("reclazztestcms")
        val relative="web/src/com/onurkat/reclazztestcms/controllers/ReclazzParagraphComponentController.java"
        val source=root.resolve(relative);val original=CmsFixtureBaseline.original(relative)
        val changed=replaceOnce(original.toString(Charsets.UTF_8),"\"page-v1\"","\"page-v2\"").toByteArray()
        val identities=mutableMapOf<String,CmsPageProof>()
        fun phase(marker:String) {
            for((version,role) in listOf("selected" to "target","other" to "target","selected" to "control","other" to "control")) {
                val expected=if(version=="selected"&&role=="target")marker else "page-stable"
                val (response,mime)=httpVerifier.getWithContentType("$endpoint/render",params+mapOf("version" to version,"position" to role),config.httpTimeoutMs)
                val key="$version-$role"
                val proof=requireCmsPageHtml(response,mime,nonce,version,role,expected,identities[key])
                identities.putIfAbsent(key,proof)
            }
            check(identities.values.map {it.componentPk}.toSet().size==4 && identities.values.map {it.slotPk}.toSet().size==4) {"Catalog/slot identities collapsed"}
            for(version in listOf("selected","other"))check(identities["$version-target"]!!.pagePk==identities["$version-control"]!!.pagePk) {"Target/control resolved different pages"}
            check(identities["selected-target"]!!.pagePk!=identities["other-target"]!!.pagePk) {"Catalog page identity collapsed"}
            println("CMS_PAGE_PHASE $marker persisted=4 identities=$identities")
        }
        fun apply(bytes:ByteArray) {
            val mark=agentClient.cursor();check(writeChanged(source,bytes))
            awaitSources(agentClient,listOf(source),mark,config.eventTimeoutMs)
        }
        withRestore({
            val begin=json(httpVerifier.post("$endpoint/begin",params));check(begin.path("nonce").asText()==nonce&&begin.path("catalogs").asInt()==1)
            phase("page-v1")
            val withheld=httpVerifier.getWithContentType("$endpoint/render",params+mapOf("version" to "selected","position" to "target"),config.httpTimeoutMs)
            val red=runCatching {requireCmsPageHtml(withheld.first,withheld.second,nonce,"selected","target","page-v2",identities["selected-target"])}.exceptionOrNull()
            check(red is IllegalStateException && red.message?.startsWith("Wrong page/slot/component/marker:")==true) {"Withheld renderer edit did not reject the actual page-v1 HTML"}
            println("CMS_PAGE_WITHHELD_RED expected=page-v2 observed=page-v1 assertion=Wrong page/slot/component/marker")
            withRestore({apply(changed);phase("page-v2")}) {apply(original);phase("page-v1")}
        }) {
            restoreAll({
                if(!Files.readAllBytes(source).contentEquals(original))apply(original)
                CmsFixtureBaseline(root).checkBytes()
            },{
                val cleanup=json(httpVerifier.post("$endpoint/cleanup",params))
                check(cleanup.path("nonce").asText()==nonce&&cleanup.path("ownedRemaining").isIntegralNumber&&cleanup.path("ownedRemaining").asInt()==0&&cleanup.path("ownedPageRows").isIntegralNumber&&cleanup.path("ownedPageRows").asInt()==0)
                println("CMS_PAGE_CLEANUP $cleanup")
            })
        }
    }
}
