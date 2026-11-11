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
    val importTimes=mutableListOf<Long>();var witnessTime=0L;var measuredReads=0;var armed=false;private var phaseReads=0
    private var pendingEngine=1;private var applyAt=Long.MAX_VALUE;private var fpModified=100L;private var fpShort:String?=null
    private fun watch():Map<String,Any> = mapOf("route" to "watcher","insideImportFile" to (mode!="diag-no-observation"),"thread" to "Reclazz-Reloader","level" to "ERROR","loader" to loader(2,false))
    private fun loader(id:Int,visible:Boolean):Map<String,Any> = mapOf("identity" to id,"kind" to "contract.Loader","providerVisible" to visible,"providerSameAsNative" to visible)
    private val reloads=mutableListOf("SETUP")
    private val directory=Files.createDirectories(root.resolve("resources/impex"))
    private val watcher:Thread
    private fun nonce(role:String)=run+if(role=="short") "-aaaa" else "-bbbbbbbb"
    private fun artifactDiagnostics():Map<String,Any> {
        val empty=java.security.MessageDigest.getInstance("SHA-256").digest(byteArrayOf()).joinToString(""){"%02x".format(it)}
        val types=listOf("ImpExMedia","ImpExImportCronJob","ImpExImportJob","JobLog","LogFile").associateWith {type->
            val added=if(((mode in setOf("diag-artifact-leak","provider-artifact-leak","timed-artifact-leak","artifact-foreign-addition","artifact-foreign-owner","artifact-owned-reference"))&&type=="JobLog")||(mode=="artifact-owned-residue"&&type=="ImpExMedia"))1 else 0
            val removed=if(mode in setOf("artifact-foreign-removal","artifact-removed-owned-reference")&&type=="LogFile")1 else 0
            val owned=if((mode=="artifact-owned-residue"&&type=="ImpExMedia")||(mode in setOf("diag-artifact-leak","provider-artifact-leak","timed-artifact-leak")&&type=="JobLog"))1 else 0
            val addedForeign=if(mode=="artifact-foreign-owner"&&added==1)listOf(mapOf("classification" to "FOREIGN_ADDED","identitySha256" to "c".repeat(64),"ownerTypes" to listOf("CronJob"),"ownerResolved" to true,"referencesChecked" to true,"ownedReferenceCount" to 0,"basis" to "native-owner-relations-and-reference-scan"))else emptyList()
            val removedForeign=if(mode=="artifact-foreign-removal"&&removed==1)listOf(mapOf("classification" to "FOREIGN_REMOVED","identitySha256" to "d".repeat(64),"basis" to "pre-existing-not-referenced-by-owned","ownedReferenceCount" to 0))else emptyList()
            val ownedAdded=if(added==1&&(owned==1||mode=="artifact-owned-reference"))1 else 0
            mapOf("beforeCount" to 1,"currentCount" to 1+added-removed,"addedCount" to added,"removedCount" to removed,
                "addedSha256" to if(mode=="artifact-inconsistent"&&type=="JobLog")"not-a-hash" else if(added==0)empty else "a".repeat(64),"removedSha256" to if(removed==0)empty else "b".repeat(64),
                "capturedCount" to owned,"ownedRemainingCount" to owned,"ownedRemovedCount" to 0,"ownershipBasis" to if(owned==1)mapOf("exact-owned-script-bytes" to 1)else emptyMap<String,Int>(),
                "foreignAdded" to addedForeign,"foreignRemoved" to removedForeign,"unresolvedAddedCount" to added-addedForeign.size-ownedAdded,"ownedReferencingAddedCount" to ownedAdded,"ownedReferencedRemovedCount" to removed-removedForeign.size)
        }
        return mapOf("baselineChecked" to true,"restored" to (mode !in setOf("artifact-owned-residue","diag-artifact-leak","provider-artifact-leak","timed-artifact-leak")),"types" to types,"ownedReferenceInspectionComplete" to (mode!="artifact-reference-inspection"))
    }
    private fun fingerprint():Map<String,Any> {
        val roots=listOf("AbstractConstraint","ConstraintGroup").associateWith {type->mapOf<String,Any?>("count" to if(mode=="fp-count-type")2.5 else 2L,"maxModifiedMillis" to if(mode=="fp-max-type")"bad" else if(type=="AbstractConstraint")fpModified else 100L)}
        val empty=listOf("AbstractConstraint","ConstraintGroup").associateWith {mapOf<String,Any?>("count" to 0L,"maxModifiedMillis" to if(mode=="fp-empty")1L else null)}
        return mapOf("run" to run,"roots" to roots,"empty" to empty,"nativeOwners" to (mode!="fp-owner"),"serviceIdentity" to 1,"ownedConstraintCount" to if(mode=="fp-subtype")1 else 2,"ownedGroupCount" to 2,"queryMs" to 0.1,"short" to mapOf("pk" to "pk-short","nonce" to (fpShort?:nonce("short"))),"target" to mapOf("max" to max,"modifiedMillis" to fpModified))
    }
    @Synchronized private fun snapshot():Map<String,Any> {
        calls+=8
        if(imported)measuredReads++
        if(mode=="timed-delayed"&&imported&&++phaseReads>=2&&System.nanoTime()>=applyAt)engine=pendingEngine
        if(mode=="timed-extra-reload"&&imported&&"UNEXPECTED" !in reloads)reloads.add("UNEXPECTED")
        val rows=listOf("short","long").associateWith {role->mapOf("pk" to if(mode=="changed-pk"&&imported&&role=="short") "foreign-pk" else "pk-$role","nonce" to if(mode=="changed-nonce"&&imported&&role=="short") "foreign" else nonce(role),"differentFresh" to true)}
        val native=listOf("held","fresh").associateWith {
            listOf("target","control").associateWith {group->listOf("short","long").associateWith {role->
                val count=if(group=="target") {if(role=="long"||(engine==2&&!(mode=="timed-mixed-matrix"&&imported&&it=="fresh"))) 1 else 0} else if(role=="short"&&mode!="empty-control") 1 else 0
                val violations=if(count==0)emptyList()else listOf(mapOf("constraintPk" to if(mode=="wrong-constraint-pk") "wrong" else "pk-$group","property" to "nonce","qualifier" to if(mode=="wrong-qualifier") "other" else "ReclazzSchemaProbe.nonce","annotation" to if(mode=="wrong-annotation") "jakarta.validation.constraints.NotNull" else "jakarta.validation.constraints.${if(group=="target") "Size" else "Pattern"}","invalid" to nonce(role),"rootPk" to "pk-$role"))
                mapOf("count" to count,"violations" to violations)
            }}
        }
        val result=mutableMapOf<String,Any>("run" to run,"owned" to 6,"workerAlive" to true,"activeGroupsRestored" to true,"groupIdentity" to (mode!="wrong-group-class"),"nativeOwners" to (mode!="wrong-native-owner"),"foreignSha256" to "a".repeat(64),"serviceIdentity" to 1,"reloads" to reloads.toList(),"calls" to calls,"rows" to rows,"native" to native,
            "target" to mapOf("pk" to "pk-target","id" to "$run-target","min" to 0,"max" to max,"needReload" to (engine==1&&max==nonce("short").length-1)),
            "control" to mapOf("pk" to "pk-control","id" to "$run-control","regexp" to if(mode=="changed-control"&&imported) ".*" else ".*-bbbbbbbb$","flags" to 0))
        if(witnessed&&mode!="fp-cost-missing")result["witnessCost"]=mapOf("reason" to "WITNESS","reloadCallMs" to if(mode=="fp-cost-negative")-1.0 else 0.1,"firstHeldValidationMs" to 1.2,"heldPassMs" to 1.5,"freshPassMs" to 0.5)
        return result
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
                        "script"->{if(params["version"].orEmpty().startsWith("unrelated-")){text="UPDATE ReclazzSchemaProbe;pk[unique=true];nonce\n;pk-short;"+(if(params["version"]=="unrelated-v2")run+"-zzzz" else nonce("short"))+"\n"}else text="UPDATE SizeConstraint;id[unique=true];max\n;$run-target;${nonce("short").length+if(params["version"]=="v2") -1 else 1}\n";emptyMap<String,Any>()}
                        "diagnostic"->{when(params["action"]) {
                            "constraint-fingerprint"->{if(mode=="fp-query-failure"){status=500;emptyMap<String,Any>()}else fingerprint()}
                            "watch-arm"->{armed=true;mapOf("run" to run,"armed" to true,"hybrisLoader" to loader(1,mode!="provider-no-hybris-provider"))}
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
                        "witness"->{witnessTime=System.nanoTime();witnessed=true;reloads.add("WITNESS");if(mode!="witness-broken")engine=2;snapshot()}
                        "cleanup"->{cleaned=true;mapOf("run" to run,"owned" to if(mode=="owned-leak")1 else 0,"removed" to if(mode=="partial")0 else 6,"idle" to true,"sessionClosed" to (mode!="worker-leak"),"workerAlive" to (mode=="worker-leak"),"heldRemaining" to 0,"baselineRestored" to true,"observerRemoved" to (mode!="diag-observer-leak"&&mode!="provider-observer-leak"&&mode!="timed-observer-leak"),"artifactBaselineRestored" to artifactDiagnostics()["restored"]!!,"artifactDiagnostics" to artifactDiagnostics(),"reloads" to reloads+"CLEANUP")}
                        else->{if(mode=="timed-late-response"&&imported)Thread.sleep(500);snapshot()}
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
                    if(text.startsWith("UPDATE ReclazzSchemaProbe;pk[unique=true];nonce\n;pk-short;")&&text.endsWith("\n")) {
                        val value=text.lineSequence().drop(1).first().substringAfterLast(';')
                        check(value in listOf(nonce("short"),run+"-zzzz"));seen.add(file)
                        synchronized(this){if(mode!="fp-noop")fpShort=value;if(mode=="fp-unrelated-change")fpModified++}
                        if(mode=="engine-unrelated")emit("INFO","Validation engine reloaded after ImpEx changed constraints: ${file.fileName}")
                        emit("OK","ImpEx imported: ${file.fileName}");return@forEach
                    }
                    if(value!=null) {
                        seen.add(file);if(mode.startsWith("provider-")) {
                            synchronized(this){providerWrites++;if(!(mode=="provider-missing-persisted-2"&&providerWrites==2))max=value
                                providerReceipts.add(mapOf("route" to if(mode=="provider-wrong-route")"direct-native-control" else "watcher","insideImportFile" to (mode!="provider-no-inside"),"thread" to "Reclazz-Reloader","level" to "OK","file" to file.fileName.toString(),"loader" to loader(if(mode=="provider-wrong-hybris")999 else 1,true),"sameAsHybrisLoader" to (mode!="provider-wrong-hybris")))
                            }
                            emit("OK","ImpEx imported: ${if(mode=="provider-missing-terminal-2"&&providerWrites==2) "earlier-" else ""}${file.fileName}");return@forEach
                        };if(mode.startsWith("diag-")){emit("ERROR","ImpEx import had errors: some lines were not resolved in ${file.fileName}.");return@forEach};synchronized(this){
                            imported=true;if(mode!="fp-unchanged")fpModified+=100;importTimes.add(System.nanoTime());if(mode!="missing-persisted")max=value
                            val next=if(value<nonce("short").length)2 else 1
                            if(mode=="timed-delayed"){pendingEngine=next;phaseReads=0;applyAt=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(100)}
                            else if(!mode.startsWith("fp-")&&mode !in setOf("stale","witness-broken")&&!(mode=="timed-restore-stale"&&next==1))engine=next
                        }
                        if(mode.startsWith("engine-")&&mode!="engine-missing")repeat(if(mode=="engine-double")2 else 1){emit("INFO","Validation engine reloaded after ImpEx changed constraints: ${file.fileName}")}
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

fun runValidationConstraintsHarnessContracts(only:Set<String> = emptySet()):Int {
    val modes=listOf("automatic","stale","witness-broken","withheld","wrong-constraint-pk","wrong-qualifier","wrong-annotation","empty-control","changed-control","changed-pk","changed-nonce","old-terminal","missing-persisted","wrong-group-class","wrong-native-owner","partial","worker-leak","owned-leak","diag-stale","diag-applied","diag-refused","diag-no-observation","diag-wrong-loader","diag-no-provider","diag-tccl-leak","diag-wrong-bytes","diag-running","diag-missing-persisted","diag-mislabeled","diag-observer-leak","diag-artifact-leak","provider-normal","provider-no-hybris-provider","provider-no-before","provider-unrestored","provider-direct-substitute","provider-wrong-route","provider-no-inside","provider-wrong-hybris","provider-missing-persisted-2","provider-missing-terminal-2","provider-observer-leak","provider-artifact-leak","timed-delayed","timed-restore-stale","timed-late-response","timed-mixed-matrix","timed-extra-reload","timed-artifact-leak","timed-observer-leak","artifact-foreign-addition","artifact-foreign-removal","artifact-owned-residue","artifact-inconsistent","fp-normal","fp-owner","fp-subtype","fp-count-type","fp-max-type","fp-empty","fp-query-failure","fp-noop","fp-unrelated-change","fp-unchanged","fp-cost-missing","fp-cost-negative","engine-normal","engine-unrelated","engine-missing","engine-double","artifact-foreign-owner","artifact-owned-reference","artifact-removed-owned-reference","artifact-reference-inspection").filter {only.isEmpty()||it in only}
    check(only.isEmpty()||modes.toSet()==only){"Unknown contract selection"}
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
                    val result=ValidationConstraintsLiveTest(config,client,HttpVerifier(server.baseUrl,1000),mode=="withheld",mode=="partial",if(mode.startsWith("diag-")) "native" else "",mode.startsWith("provider-"),mode.startsWith("fp-"),mode.startsWith("engine-")).run()
                    check(result.status==if(mode=="automatic"||mode=="provider-normal"||mode=="timed-delayed"||mode=="fp-normal"||mode=="engine-normal"||mode=="artifact-foreign-owner"||mode=="artifact-foreign-removal"||mode=="artifact-foreign-addition"||mode=="artifact-owned-reference"||mode=="artifact-removed-owned-reference"||mode=="artifact-reference-inspection")TestStatus.PASS else TestStatus.FAIL){"$mode: $result"}
                    check(server.cleaned){"$mode missed cleanup"}
                    if(mode=="timed-delayed")check(server.importTimes.size==2&&server.measuredReads>=4&&!server.witnessed){"Delayed v1/v2 was not actually measured"}
                    if(mode=="timed-delayed"||mode=="stale")check(server.armed){"Primary observer was not armed for exact artifact cleanup"}
                    if(!mode.startsWith("diag-"))check(server.directCalls==0){"Primary scenario substituted direct import"}
                    val fpGuard=mapOf("fp-owner" to "nativeOwners false","fp-subtype" to "ownedConstraintCount expected 2","fp-count-type" to "Invalid fingerprint count","fp-max-type" to "Invalid fingerprint max","fp-empty" to "Invalid empty aggregate","fp-query-failure" to "Expected HTTP200, got 500","fp-noop" to "Unrelated owned write did not persist","fp-unrelated-change" to "Unrelated import changed constraint fingerprint","fp-unchanged" to "Constraint fingerprint unchanged","fp-cost-missing" to "Missing witness cost","fp-cost-negative" to "Invalid witness cost reloadCallMs")[mode]
                    if(fpGuard!=null)check(fpGuard in result.notes){"$mode missed $fpGuard: $result"}
                    if(mode.startsWith("engine-")&&mode!="engine-normal")check("Wrong automatic reload notices" in result.notes){"$mode missed reload guard: $result"}
                    if(mode.startsWith("artifact-")&&mode in setOf("artifact-owned-residue","artifact-inconsistent"))check((if(mode=="artifact-inconsistent")"Invalid artifact addedSha256" else "artifactBaselineRestored false") in result.notes){"$mode wrong diagnostic rejection: $result"}
                    val timingGuard=mapOf("timed-restore-stale" to "RESTORE_AUTOMATIC_APPLY_RED","timed-late-response" to "Measurement read failed within v2 window","timed-mixed-matrix" to "Unexpected native matrix","timed-extra-reload" to "Explicit reload during measured window","timed-artifact-leak" to "artifactBaselineRestored false","timed-observer-leak" to "observerRemoved false")[mode]
                    if(timingGuard!=null)check(timingGuard in result.notes){"$mode did not reach $timingGuard: $result"}
                    if(mode=="stale")check(server.measuredReads>=2&&TimeUnit.NANOSECONDS.toMillis(server.witnessTime-server.importTimes.single())>=350){"Witness ran before stale window completed"}
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
fun main(args:Array<String>){println("CONSTRAINT_HARNESS: ${runValidationConstraintsHarnessContracts(args.toSet())} cases, 0 failures")}
