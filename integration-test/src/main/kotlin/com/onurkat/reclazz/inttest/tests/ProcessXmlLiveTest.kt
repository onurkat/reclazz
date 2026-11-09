package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.nio.file.Path
import java.util.UUID

abstract class ProcessLiveSupport(name:String,c:TestConfig,a:AgentEventClient,h:HttpVerifier):BatchLiveTest(name,c,a,h) {
    protected val nativeEndpoint get() = "$endpoint/native"
    protected fun graphs(name:String,start:String,count:Int?=null):JsonNode = pollJson("$nativeEndpoint/process-owners",mapOf("definition" to name)) {
        it.isArray && it.size()>0 && (count==null || it.size()==count) && it.all { row -> row["start"].asText()==start }
    }
    protected fun execute(name:String,message:String):String {
        val nonce=UUID.randomUUID().toString()
        return withRestore({
            val started=json(post("$nativeEndpoint/process-start",mapOf("nonce" to nonce,"definition" to name)))
            val completed=pollJson("$nativeEndpoint/process",mapOf("nonce" to nonce)) {
                it["state"].asText()=="SUCCEEDED" && it["message"].asText()==message
            }
            check(completed["version"]==started["version"]) { "Running process lost its pinned version" }
            completed["version"].asText()
        }) {
            requireOwnedCleanup(post("$nativeEndpoint/process-cleanup",mapOf("nonce" to nonce)))
        }
    }
    protected fun processWrite(path:Path,bytes:ByteArray,name:String,count:Int,refusal:Boolean=false,creation:Boolean=false) {
        val file=path.fileName.toString()
        val warning="SAP process definition $file MODIFIED: not applied; restart required."
        resourceWrite(path,bytes,listOf(file,name),{
            if(refusal) it.level=="WARN" && it.message.startsWith(warning)
            else (it.level=="INFO" && it.message.startsWith("SAP process $name reloaded from $file")) ||
                (creation && it.level=="OK" && it.message.startsWith("SAP process $name registered in ") && " from $file" in it.message)
        },{refusal && it.level=="WARN" && it.message.startsWith(warning)},expectedOutcomes=count)
    }
}
class ProcessXmlLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):ProcessLiveSupport("Process XML edit and malformed refusal",c,a,h) {
    override fun scenario() {
        val name="reclazz-process-existing";val relative="resources/process/$name.xml"
        val baseline=original(relative);val before=graphs(name,"v1");val count=before.size()
        val oldVersion=execute(name,"process-v1")
        val edited=replaceOnce(baseline.toString(Charsets.UTF_8),"start=\"v1\"","start=\"v2\"").toByteArray()
        fixtureEdit(relative,edited,{p,b->processWrite(p,b,name,count)}, {
            graphs(name,"v1",count);check(execute(name,"process-v1")==oldVersion)
        }) {
            val changed=graphs(name,"v2",count)
            check(changed.withIndex().all { (i,row)->row["version"]!=before[i]["version"] })
            val newVersion=execute(name,"process-v2");check(newVersion!=oldVersion)
            val path=Path.of(config.extPath,relative)
            processWrite(path,"<process>".toByteArray(),name,count,refusal=true)
            val afterRefusal=graphs(name,"v2",count)
            check(afterRefusal.withIndex().all { (i,row)->row["identity"]==changed[i]["identity"] && row["version"]==changed[i]["version"] })
            check(execute(name,"process-v2")==newVersion)
        }
    }
}
