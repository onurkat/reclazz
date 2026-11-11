package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestStatus
import com.sun.net.httpserver.HttpServer
import java.net.*
import java.nio.file.*
import java.util.concurrent.TimeUnit

private class ConstraintContractServer(root:Path,private val mode:String):AutoCloseable {
    private val socket=ServerSocket(0,1,InetAddress.getLoopbackAddress())
    val portFile=Files.createTempFile("reclazz-c62-contract-",".port").also {Files.writeString(it,socket.localPort.toString())}
    private val http=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    private var connection:Socket?=null
    private lateinit var writer:java.io.PrintWriter
    @Volatile private var running=true
    @Volatile var cleaned=false
    var witnessed=false;var directCalls=0;private var providerWrites=0;private val providerReceipts=mutableListOf<Map<String,Any>>();private var run="";private var calls=0;private var max=0;private var engine=1;private var imported=false
    private fun watch():Map<String,Any> = mapOf("route" to "watcher","insideImportFile" to (mode!="diag-no-observation"),"thread" to "Reclazz-Reloader","level" to "ERROR","loader" to loader(2,false))
    private fun loader(id:Int,visible:Boolean):Map<String,Any> = mapOf("identity" to id,"kind" to "contract.Loader","providerVisible" to visible,"providerSameAsNative" to visible)
    private val reloads=mutableListOf("SETUP")
    private val directory=Files.createDirectories(root.resolve("resources/impex"))
    private val watcher:Thread
    private fun nonce(role:String)=run+if(role=="short") "-aaaa" else "-bbbbbbbb"
    @Synchronized private fun snapshot():Map<String,Any> {
        calls+=8
        val rows=listOf("short","long").associateWith {role->mapOf("pk" to if(mode=="changed-pk"&&imported&&role=="short") "foreign-pk" else "pk-$role","nonce" to if(mode=="changed-nonce"&&imported&&role=="short") "foreign" else nonce(role),"differentFresh" to true)}
        val native=listOf("held","fresh").associateWith {
            listOf("target","control").associateWith {group->listOf("short","long").associateWith {role->
                val count=if(group=="target") {if(role=="long"||engine==2) 1 else 0} else if(role=="short"&&mode!="empty-control") 1 else 0
                val violations=if(count==0)emptyList()else listOf(mapOf("constraintPk" to if(mode=="wrong-constraint-pk") "wrong" else "pk-$group","property" to "nonce","qualifier" to if(mode=="wrong-qualifier") "other" else "ReclazzSchemaProbe.nonce","annotation" to if(mode=="wrong-annotation") "jakarta.validation.constraints.NotNull" else "jakarta.validation.constraints.${if(group=="target") "Size" else "Pattern"}","invalid" to nonce(role),"rootPk" to "pk-$role"))
                mapOf("count" to count,"violations" to violations)
            }}
        }
        return mapOf("run" to run,"owned" to 6,"workerAlive" to true,"activeGroupsRestored" to true,"groupIdentity" to (mode!="wrong-group-class"),"nativeOwners" to (mode!="wrong-native-owner"),"foreignSha256" to "a".repeat(64),"serviceIdentity" to 1,"reloads" to reloads.toList(),"calls" to calls,"rows" to rows,"native" to native,
            "target" to mapOf("pk" to "pk-target","id" to "$run-target","min" to 0,"max" to max,"needReload" to (engine==1&&max==nonce("short").length-1)),
            "control" to mapOf("pk" to "pk-control","id" to "$run-control","regexp" to if(mode=="changed-control"&&imported) ".*" else ".*-bbbbbbbb$","flags" to 0))
    }
    init {
        Thread({connection=socket.accept();writer=java.io.PrintWriter(connection!!.getOutputStream(),true);emit("CONNECTED","constraint-contract");emit("OK","ready")},"constraint-contract-connect").apply {isDaemon=true;start()}
        http.createContext("/") {x->
            try {
                val query=if(x.requestMethod=="POST")x.requestBody.readBytes().toString(Charsets.UTF_8)else x.requestURI.rawQuery.orEmpty()
                val params=query.split('&').filter {it.isNotEmpty()}.associate {it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='),Charsets.UTF_8)}
                var status=200;var text:String?=null
                val result=synchronized(this) {
                    when(x.requestURI.path.substringAfterLast('/')) {
                        "begin"->{run=params.getValue("run");max=nonce("short").length+1;if(params["mode"]=="partial")status=500;snapshot()}
                        "script"->{text="UPDATE SizeConstraint;id[unique=true];max\n;$run-target;${nonce("short").length+if(params["version"]=="v2") -1 else 1}\n";emptyMap<String,Any>()}
                        "diagnostic"->{when(params["action"]) {
                            "watch-arm"->mapOf("run" to run,"armed" to true,"hybrisLoader" to loader(1,mode!="provider-no-hybris-provider"))
                            "watch-observation"->if(mode.startsWith("provider-"))mapOf("run" to run,"watcher" to (providerReceipts.lastOrNull()?:emptyMap<String,Any>()),"terminals" to providerReceipts.toList(),"beforeSeen" to (mode!="provider-no-before"),"beforeLoader" to loader(2,false),"hybrisLoader" to loader(1,true),"outsideImportFile" to true,"tcclRestored" to (mode!="provider-unrestored"),"directImports" to if(mode=="provider-direct-substitute")1 else 0)else mapOf("run" to run,"watcher" to watch())
                            else->{
                                directCalls++
                                val selected=if(mode=="diag-wrong-loader")loader(2,false) else loader(1,true)
                                val script="UPDATE SizeConstraint;id[unique=true];max\n;$run-target;${nonce("short").length-1}\n"
                                max=if(mode=="diag-missing-persisted"||mode=="diag-refused")nonce("short").length+1 else nonce("short").length-1
                                engine=if(mode=="diag-applied"||mode=="diag-mislabeled")2 else 1
                                val sha=java.security.MessageDigest.getInstance("SHA-256").digest(script.toByteArray()).joinToString(""){"%02x".format(it)}
                                mapOf("run" to run,"route" to if(mode=="diag-mislabeled") "watcher" else "direct-native-control","mode" to "native","watcher" to watch(),"inherited" to loader(1,true),"selected" to selected,"nativeLoader" to loader(1,mode!="diag-no-provider"),"tcclRestored" to (mode!="diag-tccl-leak"),"scriptSha256" to if(mode=="diag-wrong-bytes") "b".repeat(64) else sha,"terminal" to mapOf("successful" to (mode!="diag-refused"),"error" to (mode=="diag-refused"),"running" to (mode=="diag-running"),"finished" to (mode!="diag-running"),"unresolved" to (mode=="diag-refused")),"snapshot" to snapshot())
                            }
                        }}
                        "witness"->{witnessed=true;reloads.add("WITNESS");if(mode!="witness-broken")engine=2;snapshot()}
                        "cleanup"->{cleaned=true;mapOf("run" to run,"owned" to if(mode=="owned-leak")1 else 0,"removed" to if(mode=="partial")0 else 6,"idle" to true,"sessionClosed" to (mode!="worker-leak"),"workerAlive" to (mode=="worker-leak"),"heldRemaining" to 0,"baselineRestored" to true,"observerRemoved" to (mode!="diag-observer-leak"&&mode!="provider-observer-leak"),"artifactBaselineRestored" to (mode!="diag-artifact-leak"&&mode!="provider-artifact-leak"),"reloads" to reloads+"CLEANUP")}
                        else->snapshot()
                    }
                }
                val bytes=(text?:batchJson.writeValueAsString(result)).toByteArray();x.sendResponseHeaders(status,bytes.size.toLong());x.responseBody.use{it.write(bytes)}
            }catch(e:Exception){x.sendResponseHeaders(500,-1);x.close()}
        };http.start()
        watcher=Thread({
            val seen=mutableSetOf<Path>()
            while(running) {
                if(::writer.isInitialized)Files.list(directory).use {paths->paths.filter{it.toString().endsWith(".impex")&&!seen.contains(it)}.forEach {file->
                    val text=runCatching{Files.readString(file)}.getOrDefault("");val value=Regex(";([0-9]+)\\n$").find(text)?.groupValues?.get(1)?.toIntOrNull()
                    if(value!=null) {
                        seen.add(file);if(mode.startsWith("provider-")) {
                            synchronized(this){providerWrites++;if(!(mode=="provider-missing-persisted-2"&&providerWrites==2))max=value
                                providerReceipts.add(mapOf("route" to if(mode=="provider-wrong-route")"direct-native-control" else "watcher","insideImportFile" to (mode!="provider-no-inside"),"thread" to "Reclazz-Reloader","level" to "OK","file" to file.fileName.toString(),"loader" to loader(if(mode=="provider-wrong-hybris")999 else 1,true),"sameAsHybrisLoader" to (mode!="provider-wrong-hybris")))
                            }
                            emit("OK","ImpEx imported: ${if(mode=="provider-missing-terminal-2"&&providerWrites==2) "earlier-" else ""}${file.fileName}");return@forEach
                        };if(mode.startsWith("diag-")){emit("ERROR","ImpEx import had errors: some lines were not resolved in ${file.fileName}.");return@forEach};synchronized(this){imported=true;if(mode!="missing-persisted")max=value;if(mode !in setOf("stale","witness-broken"))engine=if(value<nonce("short").length)2 else 1}
                        emit("OK","ImpEx imported: ${if(mode=="old-terminal") "earlier-" else ""}${file.fileName}")
                    }
                }}
                Thread.sleep(3)
            }
        },"constraint-contract-watch").apply {isDaemon=true;start()}
    }
    val baseUrl get()="http://127.0.0.1:${http.address.port}"
    @Synchronized fun emit(level:String,message:String){writer.println(batchJson.writeValueAsString(mapOf("level" to level,"message" to message,"timestamp" to "contract")))}
    override fun close(){running=false;watcher.join(1000);check(!watcher.isAlive);http.stop(0);connection?.close();socket.close();Files.deleteIfExists(portFile)}
}

fun runValidationConstraintsHarnessContracts():Int {
    val modes=listOf("automatic","stale","witness-broken","withheld","wrong-constraint-pk","wrong-qualifier","wrong-annotation","empty-control","changed-control","changed-pk","changed-nonce","old-terminal","missing-persisted","wrong-group-class","wrong-native-owner","partial","worker-leak","owned-leak","diag-stale","diag-applied","diag-refused","diag-no-observation","diag-wrong-loader","diag-no-provider","diag-tccl-leak","diag-wrong-bytes","diag-running","diag-missing-persisted","diag-mislabeled","diag-observer-leak","diag-artifact-leak","provider-normal","provider-no-hybris-provider","provider-no-before","provider-unrestored","provider-direct-substitute","provider-wrong-route","provider-no-inside","provider-wrong-hybris","provider-missing-persisted-2","provider-missing-terminal-2","provider-observer-leak","provider-artifact-leak")
    for(mode in modes) {
        val root=Files.createTempDirectory("reclazz-c62-harness-")
        val files=listOf("src/com/onurkat/reclazztest/batch/ProbeConstraintGroups.java","src/com/onurkat/reclazztest/batch/ProbeValidationState.java","web/src/com/onurkat/reclazztest/controllers/ValidationConstraintsProbeController.java")
        try {
            for(rel in files){val p=root.resolve(rel);Files.createDirectories(p.parent);Files.write(p,ValidationConstraintsLiveTest::class.java.classLoader.getResourceAsStream("templates/constraints-stage1/$rel")!!.use{it.readBytes()})}
            val before=files.associateWith{Files.readAllBytes(root.resolve(it))}
            ConstraintContractServer(root,mode).use {server->
                val client=AgentEventClient(server.portFile.toString());client.connect()
                try {
                    val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                    while(client.getEvents().none{it.level=="OK"}){check(System.nanoTime()<end);Thread.sleep(5)}
                    val config=TestConfig(server.baseUrl,root.toString(),server.portFile.toString(),eventTimeoutMs=350)
                    val result=ValidationConstraintsLiveTest(config,client,HttpVerifier(server.baseUrl,1000),mode=="withheld",mode=="partial",if(mode.startsWith("diag-")) "native" else "",mode.startsWith("provider-")).run()
                    check(result.status==if(mode=="automatic"||mode=="provider-normal")TestStatus.PASS else TestStatus.FAIL){"$mode: $result"}
                    check(server.cleaned){"$mode missed cleanup"}
                    if(mode=="stale")check(server.witnessed&&"AUTOMATIC_APPLY_RED" in result.notes){"Explicit witness became automatic pass: $result"}
                    if(mode=="witness-broken")check(server.witnessed&&"Explicit witness also failed" in result.notes){result}
                    if(mode=="withheld")check(!server.witnessed&&"WITHHELD_SAVE_RED" in result.notes){result}
                    if(mode.startsWith("provider-")){check(server.directCalls==0&&!server.witnessed){"Provider fix used a direct import or engine witness"}}
                    val providerGuard=mapOf("provider-no-hybris-provider" to "providerVisible false","provider-no-before" to "beforeSeen false","provider-unrestored" to "tcclRestored false","provider-direct-substitute" to "directImports expected 0","provider-wrong-route" to "Wrong watcher import receipt","provider-no-inside" to "insideImportFile false","provider-wrong-hybris" to "sameAsHybrisLoader false","provider-missing-persisted-2" to "max expected 54","provider-missing-terminal-2" to "Missing exact import terminal","provider-observer-leak" to "observerRemoved false","provider-artifact-leak" to "artifactBaselineRestored false")[mode]
                    if(providerGuard!=null)check(providerGuard in result.notes){"$mode did not reach $providerGuard: $result"}
                    val guard=mapOf("diag-no-observation" to "insideImportFile false","diag-wrong-loader" to "Wrong selected/watcher loader identity","diag-no-provider" to "providerVisible false","diag-tccl-leak" to "tcclRestored false","diag-wrong-bytes" to "Different direct import bytes","diag-running" to "finished false","diag-missing-persisted" to "max expected","diag-mislabeled" to "Control mislabeled watcher success","diag-observer-leak" to "observerRemoved false","diag-artifact-leak" to "artifactBaselineRestored false")[mode]
                    if(guard!=null)check(guard in result.notes){"$mode did not reach its intended guard $guard: $result"}
                    if(mode=="diag-stale")check(server.witnessed&&"DIRECT_CONTROL_STALE" in result.notes){result}
                    if(mode=="diag-applied")check(!server.witnessed&&"DIRECT_CONTROL_ONLY" in result.notes){"Direct import became watcher PASS: $result"}
                    if(mode=="diag-refused")check(!server.witnessed&&"DIRECT_IMPORT_REFUSED" in result.notes){result}
                    for(rel in files)check(Files.readAllBytes(root.resolve(rel)).contentEquals(before.getValue(rel)))
                    Files.list(root.resolve("resources/impex")).use{paths->check(paths.findAny().isEmpty){"Owned import file leaked: $mode"}}
                    println("CONSTRAINT_HARNESS PASS $mode")
                }finally{client.disconnect()}
            }
        }finally{root.toFile().deleteRecursively()}
    }
    return modes.size
}
fun main(){println("CONSTRAINT_HARNESS: ${runValidationConstraintsHarnessContracts()} cases, 0 failures")}
