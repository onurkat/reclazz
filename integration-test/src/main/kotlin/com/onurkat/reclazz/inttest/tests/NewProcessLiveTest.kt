package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

abstract class NewProcessLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier,private val fileFirst:Boolean)
    :ProcessLiveSupport(if(fileFirst) "New process file first" else "New process bean first and recovery",c,a,h) {
    override fun scenario() {
        val suffix=UUID.randomUUID().toString().replace("-","")
        val name="reclazz-process-$suffix";val bean="reclazzBatchProcessResource$suffix"
        val file=Path.of(config.extPath,"resources/process/$name.xml")
        val relative="resources/reclazztest-process-resources-spring.xml"
        val source=Path.of(config.extPath,relative);val baseline=original(relative)
        check(!Files.exists(file) && Files.readAllBytes(source).contentEquals(baseline)) { "Dirty new-process baseline" }
        val count=graphs("reclazz-process-existing","v1").size() // Warm every observed native owner before admission.
        val observed=owners("reclazztest-process-resources-spring.xml")
        val bases=observed.map {it["processBaseUri"].asText()}.toSet();check(bases.size==1)
        val admittedFile=Path.of(java.net.URI.create(bases.single()).resolve("$name.xml"))
        check(Files.isSameFile(admittedFile.parent,file.parent)) {"Observed process resource root is not the owned fixture directory"}
        val graph="""<process xmlns="http://www.hybris.de/xsd/processdefinition" name="$name" start="gate" processClass="de.hybris.platform.processengine.model.BusinessProcessModel"><wait id="gate" then="done" prependProcessCode="true"><event>reclazzBatchContinue</event></wait><end id="done" state="SUCCEEDED">new-process</end></process>"""
        val xml=replaceOnce(baseline.toString(Charsets.UTF_8),"</beans>",
            "<bean id=\"$bean\" class=\"de.hybris.platform.processengine.definition.ProcessDefinitionResource\"><property name=\"resource\" value=\"${admittedFile.toUri()}\"/></bean>\n</beans>")
        withRestore({
            val mark=agentClient.cursor()
            if(fileFirst) Files.writeString(file,graph) // Not registered yet: no process-apply outcome is claimed here.
            xmlWrite(source,xml.toByteArray(),count,listOf(source.fileName.toString(),bean))
            if(!fileFirst) {
                processWrite(file,"<process>".toByteArray(),name,count,refusal=true)
                processWrite(file,graph.toByteArray(),name,count,creation=true)
            } else {
                val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(config.eventTimeoutMs)
                while(true) {
                    val events=agentClient.getEvents().filter {it.sequence>mark}
                    resourceFailure(events,listOf(file.fileName.toString(),name)) {false}?.let {error(it)}
                    if(events.count {it.level=="OK" && it.message.startsWith("SAP process $name registered in ") && " from ${file.fileName}" in it.message}>=count) break
                    check(System.nanoTime()<deadline) {"No native new-process registration in every owner: $events"};Thread.sleep(20)
                }
            }
            graphs(name,"gate",count);execute(name,"new-process")
        }) {
            restoreAll({
                val response=post("$nativeEndpoint/process-resource-cleanup",mapOf("bean" to bean))
                requireOwnedCleanup(response,"ownedResourcesRemaining")
                val cleanup=json(response)
                check(cleanup["ownedResourcesRemaining"].asInt()==0 && cleanup["owners"].asInt()==count)
            },{xmlWrite(source,baseline,count)}, {Files.deleteIfExists(file)}, {
                check(!Files.exists(file) && Files.readAllBytes(source).contentEquals(baseline))
                graphs("reclazz-process-existing","v1",count)
            })
        }
    }
}
class NewProcessFileFirstLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):NewProcessLiveTest(c,a,h,true)
class NewProcessBeanFirstLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):NewProcessLiveTest(c,a,h,false)
