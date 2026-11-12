package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.http.HttpResult
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal fun requireCmsJspHtml(response:HttpResult,mime:String?,marker:String) {
    check(response.statusCode==200 && mime?.substringBefore(';')?.trim()?.lowercase()=="text/html") {"JSP response is not HTML200"}
    check(response.body.trimStart().startsWith("<!doctype html>",ignoreCase=true))
    val matches=Regex("""<span id="reclazz-cms-view-proof">([^<]*)</span>""").findAll(response.body).toList()
    check(matches.size==1 && matches.single().groupValues[1]=="$marker &amp; &lt;owned&gt;") {"Wrong escaped JSP view proof"}
}

class CmsJspResourceLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):
    BaseTest("CMS native JSP resource save (outer recovery required)",c,a,h) {
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
        val base="/reclazztestcms/v2/test/batch/cms"
        val endpoint="$base/page-slot"
        val params=mapOf("nonce" to nonce)
        val root=Path.of(config.extPath).toRealPath().parent.resolve("reclazztestcms")
        val relative="web/webroot/WEB-INF/views/reclazz-page-slot.jsp"
        val source=root.resolve(relative);val original=CmsFixtureBaseline.original(relative)
        val changed=replaceOnce(original.toString(Charsets.UTF_8),"jsp-v1 &amp; &lt;owned&gt;","jsp-v2 &amp; &lt;owned&gt;").toByteArray()
        fun javaHashes()=Files.walk(root.resolve("web/src")).use { stream ->
            stream.filter {Files.isRegularFile(it)&&it.toString().endsWith(".java")}.toList().sorted().associate {
                root.relativize(it).toString() to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).joinToString("") { b -> "%02x".format(b) }
            }
        }
        val javaBefore=javaHashes();check(javaBefore.isNotEmpty())
        val facts=json(httpVerifier.get("$base/view-facts"))
        check(Files.isSameFile(Path.of(facts.path("viewPath").asText()),source)) {"Served JSP differs from edited source"}
        check(facts.path("jspClass").asText()=="org.apache.jasper.servlet.JspServlet")
        val init=facts.path("jspInit")
        check(init.path("development").asText()=="true"&&init.path("checkInterval").asText()=="0"&&init.path("modificationTestInterval").asText()=="1") {"Native Jasper settings differ"}
        val started=facts.path("jvmStart");check(started.isIntegralNumber&&started.asLong()>0)
        println("CMS_JSP_NATIVE_FACTS $facts")
        val control=httpVerifier.getWithContentType("$base/probe",params,config.httpTimeoutMs)
        requireCmsHtml(control.first,control.second,nonce,"cms-v1")
        val identities=mutableMapOf<String,CmsPageProof>()
        fun read(version:String,role:String)=httpVerifier.getWithContentType("$endpoint/render",params+mapOf("version" to version,"position" to role),config.httpTimeoutMs)
        fun phase(marker:String) {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
            var last:Exception?=null
            while(true) {
                try {val html=read("selected","target");requireCmsJspHtml(html.first,html.second,marker);break}
                catch(failure:Exception) {last=failure}
                check(System.nanoTime()<deadline) {"Native JSP save did not converge: ${last?.message}"}
                Thread.sleep(100)
            }
            for((version,role) in listOf("selected" to "target","other" to "target","selected" to "control","other" to "control")) {
                val html=read(version,role);requireCmsJspHtml(html.first,html.second,marker)
                val key="$version-$role";val javaMarker=if(key=="selected-target")"page-v1" else "page-stable"
                val proof=requireCmsPageHtml(html.first,html.second,nonce,version,role,javaMarker,identities[key]);identities.putIfAbsent(key,proof)
            }
            val again=httpVerifier.getWithContentType("$base/probe",params,config.httpTimeoutMs)
            requireCmsHtml(again.first,again.second,nonce,"cms-v1");check(again.first.body==control.first.body&&again.second==control.second) {"Independent view changed"}
            check(javaHashes()==javaBefore) {"JSP case edited Java"}
            check(json(httpVerifier.get("$base/view-facts")).path("jvmStart")==started) {"JVM restarted during JSP edit"}
            println("CMS_JSP_PHASE $marker javaUnchanged=true controlUnchanged=true identities=$identities")
        }
        withRestore({
            val begin=json(httpVerifier.post("$endpoint/begin",params));check(begin.path("nonce").asText()==nonce&&begin.path("catalogs").asInt()==1)
            phase("jsp-v1")
            val withheld=read("selected","target")
            val red=runCatching {requireCmsJspHtml(withheld.first,withheld.second,"jsp-v2")}.exceptionOrNull()
            check(red is IllegalStateException&&red.message=="Wrong escaped JSP view proof") {"Withheld JSP edit did not reject actual v1 HTML"}
            println("CMS_JSP_WITHHELD_RED expected=jsp-v2 observed=jsp-v1")
            withRestore({check(writeChanged(source,changed));phase("jsp-v2")}) {
                check(writeChanged(source,original));phase("jsp-v1")
            }
        }) {
            restoreAll({
                if(!Files.readAllBytes(source).contentEquals(original)) {writeChanged(source,original);phase("jsp-v1")}
                CmsFixtureBaseline(root).checkBytes();check(javaHashes()==javaBefore)
            },{
                val cleanup=json(httpVerifier.post("$endpoint/cleanup",params))
                check(cleanup.path("nonce").asText()==nonce&&cleanup.path("ownedRemaining").isIntegralNumber&&cleanup.path("ownedRemaining").asInt()==0&&cleanup.path("ownedPageRows").isIntegralNumber&&cleanup.path("ownedPageRows").asInt()==0)
                println("CMS_JSP_CLEANUP $cleanup")
            })
        }
    }
}
