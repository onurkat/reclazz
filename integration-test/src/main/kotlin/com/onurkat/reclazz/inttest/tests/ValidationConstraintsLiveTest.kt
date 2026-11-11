package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.nio.file.*
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Native constraint acceptance and separate research controls; a witness is never automatic apply. */
class ValidationConstraintsLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier,
    private val withheld:Boolean=System.getenv("RECLAZZ_CONSTRAINT_WITHHOLD_SAVE")=="true",
    private val partial:Boolean=System.getenv("RECLAZZ_CONSTRAINT_PARTIAL_SETUP")=="true",
    private val diagnostic:String=System.getenv("RECLAZZ_CONSTRAINT_DIAGNOSTIC").orEmpty(),
    private val providerOnly:Boolean=System.getenv("RECLAZZ_CONSTRAINT_WATCHER_PROVIDER")=="true",
    private val fingerprintOnly:Boolean=System.getenv("RECLAZZ_CONSTRAINT_FINGERPRINT")=="true",
    private val engineEffects:Boolean=System.getenv("RECLAZZ_CONSTRAINT_ENGINE_EFFECTS")=="true")
    :BatchLiveTest(if(engineEffects) "Native validation constraints after ImpEx" else if(fingerprintOnly) "Constraint effect fingerprint (StageA)" else if(providerOnly) "ImpEx application provider loader" else "Persisted validation constraints (Stage1)",c,a,h) {
    override fun scenario() {
        val run="reclazz-c62-${UUID.randomUUID()}";val params=mapOf("run" to run);val url="$endpoint/validation-constraints"
        val length=(run+"-aaaa").length;val roles=listOf("short","long");val groups=listOf("target","control")
        val paths=listOf("src/com/onurkat/reclazztest/batch/ProbeConstraintGroups.java","src/com/onurkat/reclazztest/batch/ProbeValidationState.java","web/src/com/onurkat/reclazztest/controllers/ValidationConstraintsProbeController.java")
        for(path in paths) {
            val original=javaClass.classLoader.getResourceAsStream("templates/constraints-stage1/$path")!!.use {it.readBytes()}
            check(Files.readAllBytes(Path.of(config.extPath,path)).contentEquals(original)) {"Dirty constraint fixture $path"}
        }
        var begun=false;var attempted=false;var calls=0;var foreign="";var service=0;var writes=0
        val ids=mutableMapOf<String,String>();val files=mutableListOf<Path>();val eventStart=agentClient.cursor()
        fun number(n:JsonNode,k:String,v:Long){check(n[k]?.isIntegralNumber==true&&n[k].asLong()==v){"$k expected $v: $n"}}
        fun yes(n:JsonNode,k:String){check(n[k]?.isBoolean==true&&n[k].asBoolean()){"$k false: $n"}}
        fun owned(n:JsonNode){check(n["run"]?.asText()==run){"Wrong run: $n"}}
        fun common(n:JsonNode,reloads:List<String>,record:Boolean=false):JsonNode {
            owned(n);number(n,"owned",6);yes(n,"workerAlive");yes(n,"activeGroupsRestored");yes(n,"groupIdentity");yes(n,"nativeOwners")
            check(n["reloads"]?.isArray==true&&n["reloads"].map {it.asText()}==reloads){"Explicit reload during measured window: $n"}
            val fingerprint=n["foreignSha256"]?.asText().orEmpty();check(fingerprint.matches(Regex("[a-f0-9]{64}")))
            check(n["serviceIdentity"]?.isIntegralNumber==true)
            if(record){foreign=fingerprint;service=n["serviceIdentity"].asInt()}
            else check(foreign==fingerprint&&service==n["serviceIdentity"].asInt()) {"Foreign metadata/service changed: $n"}
            number(n,"calls",(calls+8).toLong());calls+=8
            for(role in roles) {
                val row=n["rows"][role];yes(row,"differentFresh")
                check(row["nonce"]?.asText()==run+if(role=="short") "-aaaa" else "-bbbbbbbb") {"Probe nonce changed: $n"}
                val pk=row["pk"]?.asText().orEmpty();check(pk.isNotBlank());if(record)ids[role]=pk else check(ids[role]==pk){"Probe PK changed: $n"}
            }
            for(group in groups) {
                val node=n[group];val pk=node["pk"]?.asText().orEmpty();check(pk.isNotBlank());if(record)ids[group]=pk else check(ids[group]==pk){"Constraint PK changed: $n"}
                check(node["id"]?.asText()=="$run-$group")
            }
            number(n["target"],"min",0);check(n["target"]["max"]?.isIntegralNumber==true);check(n["target"]["needReload"]?.isBoolean==true)
            check(n["control"]["regexp"]?.asText()==".*-bbbbbbbb$");number(n["control"],"flags",0)
            for(path in listOf("held","fresh"))for(group in groups)for(role in roles) {
                val node=n["native"][path][group][role];val violations=node["violations"]
                check(node["count"]?.isIntegralNumber==true&&violations?.isArray==true&&node["count"].asInt()==violations.size())
                for(v in violations)check(v["constraintPk"]?.asText()==ids[group]&&v["property"]?.asText()=="nonce"&&v["qualifier"]?.asText()=="ReclazzSchemaProbe.nonce"&&v["annotation"]?.asText()=="jakarta.validation.constraints.${if(group=="target") "Size" else "Pattern"}"&&v["invalid"]?.asText()==n["rows"][role]["nonce"].asText()&&v["rootPk"]?.asText()==ids[role]){"Wrong native violation: $n"}
            }
            return n
        }
        fun matrix(n:JsonNode,v2:Boolean):Boolean = listOf("held","fresh").all {path ->
            n["native"][path]["target"]["short"]["count"].asInt()==(if(v2) 1 else 0) &&
                n["native"][path]["target"]["long"]["count"].asInt()==1 &&
                n["native"][path]["control"]["short"]["count"].asInt()==1 &&n["native"][path]["control"]["long"]["count"].asInt()==0
        }
        fun read(reloads:List<String> = listOf("SETUP"))=common(json(get("$url/read",params)),reloads)
        var terminalNanos=0L;var terminalUtc=""
        fun import(version:String,allowRefusal:Boolean=false):Boolean {
            val body=get("$url/script",params+mapOf("version" to version));check(body.statusCode==200)
            val path=Path.of(config.impexDir,"reclazz-c62-$run-${++writes}.impex");check(!Files.exists(path));val cursor=agentClient.cursor()
            Files.createDirectories(path.parent);Files.writeString(path,body.body,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);files.add(path)
            val end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(config.eventTimeoutMs)
            while(true) {
                val arrivals=agentClient.getEvents().filter {it.sequence>cursor};resourceFailure(arrivals,listOf(path.fileName.toString())) {false}?.let {if(allowRefusal&&"ImpEx import had errors" in it)return false else error(it)}
                if(arrivals.any {it.level=="OK"&&it.message=="ImpEx imported: ${path.fileName}"}){
                    if(engineEffects) {
                        val notices=arrivals.count {it.level=="INFO"&&it.message=="Validation engine reloaded after ImpEx changed constraints: ${path.fileName}"}
                        check(notices==(if(version.startsWith("unrelated-"))0 else 1)){"Wrong automatic reload notices for $version: $notices"}
                        println("    Validation automatic reload receipt: "+batchJson.writeValueAsString(mapOf("run" to run,"version" to version,"file" to path.fileName.toString(),"reloads" to notices)))
                    }
                    terminalNanos=System.nanoTime();terminalUtc=java.time.Instant.now().toString();break
                }
                check(System.nanoTime()<end){"Missing exact import terminal for ${path.fileName}"};Thread.sleep(10)
            }
            // The native observation follows the accepted file, not a service-save substitute.
            return true
        }
        withRestore({
            println("    Validation owned run: $run");attempted=true
            val first=common(json(post("$url/begin",params+mapOf("mode" to if(partial) "partial" else "normal"))),listOf("SETUP"),true);begun=true
            number(first["target"],"max",(length+1).toLong());check(matrix(first,false)){"Native baseline matrix incorrect: $first"};println("    Validation baseline: $first")
            if(withheld) {
                val source=get("$url/script",params+mapOf("version" to "v2"));check(source.statusCode==200)
                val scratch=Files.createTempFile("reclazz-c62-withheld-",".impex")
                try {Files.writeString(scratch,source.body);val unchanged=read();number(unchanged["target"],"max",(length+1).toLong());check(matrix(unchanged,false));println("    Validation withheld native: $unchanged");check(matrix(unchanged,true)){"WITHHELD_SAVE_RED: native v2 matrix absent"}}
                finally {Files.deleteIfExists(scratch)}
            }
            if(providerOnly) {
                check(!withheld&&!partial&&diagnostic.isEmpty())
                val armed=json(post("$url/diagnostic",params+mapOf("action" to "watch-arm")));owned(armed);yes(armed,"armed")
                val hybris=armed["hybrisLoader"];yes(hybris,"providerVisible");yes(hybris,"providerSameAsNative")
                check(hybris["identity"]?.isIntegralNumber==true&&hybris["kind"]?.isTextual==true){"Missing exact hybrisCl identity"}
                println("    Validation provider preflight: $armed")
                var beforeLoader:JsonNode?=null
                fun watched(version:String):JsonNode {
                    check(import(version)){"Provider import refused"}
                    val end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(config.eventTimeoutMs)
                    var observation:JsonNode
                    while(true) {
                        observation=json(post("$url/diagnostic",params+mapOf("action" to "watch-observation")));owned(observation)
                        if(observation["terminals"]?.isArray==true&&observation["terminals"].size()==writes&&observation["outsideImportFile"]?.asBoolean()==true)break
                        check(System.nanoTime()<end){"Missing real watcher terminal/restoration receipt $writes"};Thread.sleep(10)
                    }
                    yes(observation,"beforeSeen");yes(observation,"outsideImportFile");yes(observation,"tcclRestored");number(observation,"directImports",0)
                    check(observation["hybrisLoader"]==hybris){"Importer context loader changed"}
                    val before=observation["beforeLoader"];check(before["identity"]?.isIntegralNumber==true&&before["kind"]?.isTextual==true)
                    if(beforeLoader==null)beforeLoader=before else check(beforeLoader==before){"Watcher original loader changed between writes"}
                    val receipt=observation["terminals"].last();yes(receipt,"insideImportFile");yes(receipt,"sameAsHybrisLoader")
                    check(receipt["route"]?.asText()=="watcher"&&receipt["thread"]?.asText()=="Reclazz-Reloader"&&receipt["level"]?.asText()=="OK"&&receipt["file"]?.asText()==files.last().fileName.toString()){ "Wrong watcher import receipt $writes" }
                    check(receipt["loader"]==hybris){"TCCL is not exact importer hybrisCl"};yes(receipt["loader"],"providerVisible");yes(receipt["loader"],"providerSameAsNative")
                    println("    Validation provider watcher receipt $writes: $observation")
                    return read()
                }
                val changed=watched("v2");number(changed["target"],"max",(length-1).toLong());check(matrix(changed,false)||matrix(changed,true)){"Unexpected provider-control native matrix"};println("    Validation provider persisted v2: $changed")
                val restored=watched("v1");number(restored["target"],"max",(length+1).toLong());check(matrix(restored,false)||matrix(restored,true)){"Unexpected provider-control restore matrix"};println("    Validation provider persisted v1: $restored")
                check(writes==2);println("IMPEX_PROVIDER_OUTCOME WATCHER_ROUND_TRIP");return@withRestore
            }
            if(diagnostic.isNotEmpty()) {
                check(diagnostic in listOf("inherited","watcher","native"));check(!withheld&&!partial)
                val armed=json(post("$url/diagnostic",params+mapOf("action" to "watch-arm")));owned(armed);yes(armed,"armed")
                check(!import("v2",true)){"Watcher unexpectedly accepted; revise diagnostic comparison"}
                val observed=json(post("$url/diagnostic",params+mapOf("action" to "watch-observation")));owned(observed)
                val watch=observed["watcher"];yes(watch,"insideImportFile");check(watch["route"]?.asText()=="watcher"&&watch["thread"]?.asText()=="Reclazz-Reloader"&&watch["level"]?.asText()=="ERROR"){"Missing real import caller observation: $observed"}
                println("    Validation watcher diagnostic: $observed")
                val before=read();number(before["target"],"max",(length+1).toLong());check(matrix(before,false))
                val control=json(post("$url/diagnostic",params+mapOf("action" to "import-$diagnostic")));owned(control);println("    Validation import diagnostic: $control")
                check(control["route"]?.asText()=="direct-native-control"&&control["mode"]?.asText()==diagnostic){"Control mislabeled watcher success"};yes(control,"tcclRestored")
                for(node in listOf(control["inherited"],control["selected"],control["nativeLoader"],watch["loader"])) {check(node["identity"]?.isIntegralNumber==true&&node["kind"]?.isTextual==true&&node["providerVisible"]?.isBoolean==true&&node["providerSameAsNative"]?.isBoolean==true){"Missing loader evidence: $control"}}
                yes(control["nativeLoader"],"providerVisible");yes(control["nativeLoader"],"providerSameAsNative")
                val expectedLoader=when(diagnostic){"watcher"->watch["loader"];"native"->control["nativeLoader"];else->control["inherited"]}
                check(control["selected"]==expectedLoader&&control["watcher"]==watch){"Wrong selected/watcher loader identity"}
                val script=get("$url/script",params+mapOf("version" to "v2"));check(script.statusCode==200)
                val sha=java.security.MessageDigest.getInstance("SHA-256").digest(script.body.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
                check(control["scriptSha256"]?.asText()==sha){"Different direct import bytes"}
                val terminal=control["terminal"];for(key in listOf("successful","error","running","finished","unresolved"))check(terminal[key]?.isBoolean==true){"Missing terminal $key"}
                yes(terminal,"finished");check(!terminal["running"].asBoolean()) {"Direct import still running"}
                val measured=common(control["snapshot"],listOf("SETUP"));println("    Validation measured: $measured")
                if(terminal["error"].asBoolean()||!terminal["successful"].asBoolean()||terminal["unresolved"].asBoolean()) {
                    number(measured["target"],"max",(length+1).toLong());check(matrix(measured,false));println("VALIDATION_DIAGNOSTIC DIRECT_IMPORT_REFUSED");error("DIRECT_IMPORT_REFUSED: diagnostic evidence only")
                }
                number(measured["target"],"max",(length-1).toLong())
                if(matrix(measured,false)) {
                    println("VALIDATION_STALE_CAPTURED_BEFORE_WITNESS")
                    val witness=common(json(post("$url/witness",params)),listOf("SETUP","WITNESS"));println("    Validation explicit witness: $witness");number(witness["target"],"max",(length-1).toLong());check(matrix(witness,true)){"Direct control witness failed"}
                    println("VALIDATION_DIAGNOSTIC DIRECT_CONTROL_STALE_UNTIL_WITNESS");error("DIRECT_CONTROL_STALE: not watcher success")
                }
                check(matrix(measured,true));println("VALIDATION_DIAGNOSTIC DIRECT_CONTROL_APPLIED");error("DIRECT_CONTROL_ONLY: not watcher success")
            }
            // The existing observer also attributes exact owned ImpEx artifacts for cleanup.
            val observer=json(post("$url/diagnostic",params+mapOf("action" to "watch-arm")))
            owned(observer);yes(observer,"armed");yes(observer["hybrisLoader"],"providerVisible")
            println("    Validation engine observer: $observer")
            fun engineWindow(version:String,v2:Boolean):JsonNode? {
                val start=terminalNanos;check(start>0)
                val deadline=start+TimeUnit.MILLISECONDS.toNanos(config.eventTimeoutMs)
                var samples=0;var last:JsonNode?=null
                fun elapsed()=(System.nanoTime()-start)/1_000_000.0
                fun summary(outcome:String) {
                    println("    Validation window: "+batchJson.writeValueAsString(mapOf("run" to run,"phase" to version,"terminalUtc" to terminalUtc,"budgetMs" to config.eventTimeoutMs,"elapsedMs" to elapsed(),"samples" to samples,"outcome" to outcome)))
                    System.out.flush()
                }
                while(true) {
                    val remaining=deadline-System.nanoTime()
                    if(remaining<=0)break
                    val budget=minOf(2000L,TimeUnit.NANOSECONDS.toMillis(remaining)).coerceAtLeast(1)
                    val response=try {httpVerifier.get("$url/read",params,budget)}
                    catch(e:Exception){error("Measurement read failed within $version window: ${e.message}")}
                    check(System.nanoTime()<=deadline){"MEASUREMENT_DEADLINE_EXCEEDED: $version response arrived after deadline"}
                    val observed=common(json(response),listOf("SETUP"))
                    number(observed["target"],"max",(length+if(v2)-1 else 1).toLong())
                    println("    Validation sample: "+batchJson.writeValueAsString(mapOf("run" to run,"phase" to version,"elapsedMs" to elapsed(),"sample" to ++samples,"snapshot" to observed)))
                    System.out.flush();last=observed
                    check(matrix(observed,false)||matrix(observed,true)){"Unexpected native matrix: $observed"}
                    if(matrix(observed,v2)){summary("APPLIED");return observed}
                    val left=deadline-System.nanoTime()
                    if(left>0)TimeUnit.NANOSECONDS.sleep(minOf(TimeUnit.MILLISECONDS.toNanos(250),left))
                }
                check(last!=null){"No complete native samples in $version window"}
                summary("STALE");return null
            }
            var fingerprintService:Int?=null
            fun fingerprint(label:String,nonce:String,maximum:Long):JsonNode {
                val f=json(post("$url/diagnostic",params+mapOf("action" to "constraint-fingerprint")));owned(f);yes(f,"nativeOwners")
                number(f,"ownedConstraintCount",2);number(f,"ownedGroupCount",2)
                check(f["serviceIdentity"]?.isIntegralNumber==true){"Missing fingerprint service identity"}
                if(fingerprintService==null)fingerprintService=f["serviceIdentity"].asInt() else check(fingerprintService==f["serviceIdentity"].asInt()){"Fingerprint service changed"}
                for(root in listOf("AbstractConstraint","ConstraintGroup")) {
                    val row=f["roots"]?.get(root);check(row?.get("count")?.isIntegralNumber==true&&row["count"].asLong()>=2){"Invalid fingerprint count"}
                    check(row?.get("maxModifiedMillis")?.isIntegralNumber==true){"Invalid fingerprint max"}
                    val empty=f["empty"]?.get(root);check(empty?.get("count")?.isIntegralNumber==true&&empty["count"].asLong()==0L&&empty["maxModifiedMillis"]?.isNull==true){"Invalid empty aggregate"}
                }
                check(f["short"]?.get("pk")?.asText()==ids["short"]&&f["short"]["nonce"]?.asText()==nonce){"Unrelated owned write did not persist"}
                number(f["target"],"max",maximum);check(f["target"]["modifiedMillis"]?.isIntegralNumber==true){"Missing target timestamp"}
                check(f["queryMs"]?.isNumber==true&&f["queryMs"].asDouble().isFinite()&&f["queryMs"].asDouble()>=0){"Invalid fingerprint query timing"}
                println("    Validation fingerprint $label: $f");return f
            }
            fun fingerprintChanged(before:JsonNode,after:JsonNode) {
                check(before["roots"]["ConstraintGroup"]==after["roots"]["ConstraintGroup"]){"Constraint groups changed"}
                check(before["roots"]["AbstractConstraint"]["count"]==after["roots"]["AbstractConstraint"]["count"]){"Constraint count changed on UPDATE"}
                check(before["roots"]["AbstractConstraint"]["maxModifiedMillis"]!=after["roots"]["AbstractConstraint"]["maxModifiedMillis"]&&before["target"]["modifiedMillis"]!=after["target"]["modifiedMillis"]){"Constraint fingerprint unchanged"}
            }
            if(fingerprintOnly) {
                check(!withheld&&!partial&&diagnostic.isEmpty()&&!providerOnly&&!engineEffects)
                val f0=fingerprint("baseline",run+"-aaaa",(length+1).toLong())
                import("unrelated-v2");val unrelated=fingerprint("unrelated-v2",run+"-zzzz",(length+1).toLong())
                check(unrelated["roots"]==f0["roots"]){"Unrelated import changed constraint fingerprint"}
                import("unrelated-v1");val f1=fingerprint("unrelated-v1",run+"-aaaa",(length+1).toLong());check(f1["roots"]==f0["roots"]){"Unrelated restore changed constraint fingerprint"}
                import("v2");val f2=fingerprint("v2",run+"-aaaa",(length-1).toLong());fingerprintChanged(f1,f2)
                check(engineWindow("v2",true)==null){"Fingerprint primary unexpectedly automatic"}
                println("VALIDATION_STALE_CAPTURED_BEFORE_WITNESS");System.out.flush()
                val witness=common(json(post("$url/witness",params)),listOf("SETUP","WITNESS"));check(matrix(witness,true)){"Fingerprint witness failed"}
                val cost=witness["witnessCost"];check(cost?.isObject==true&&cost["reason"]?.asText()=="WITNESS"){"Missing witness cost"}
                for(key in listOf("reloadCallMs","firstHeldValidationMs","heldPassMs","freshPassMs"))check(cost[key]?.isNumber==true&&cost[key].asDouble().isFinite()&&cost[key].asDouble()>=0){"Invalid witness cost $key"}
                println("    Validation explicit witness: $witness")
                import("v1");val f3=fingerprint("v1",run+"-aaaa",(length+1).toLong());fingerprintChanged(f2,f3)
                val restored=read(listOf("SETUP","WITNESS"));check(matrix(restored,false)||matrix(restored,true)){"Invalid post-restore matrix"};println("    Validation fingerprint restored: $restored")
                println("VALIDATION_FINGERPRINT_OUTCOME EFFECTS_AND_COST_OBSERVED");return@withRestore
            }
            val effectBefore=if(engineEffects) {
                check(!withheld&&!partial&&diagnostic.isEmpty()&&!providerOnly)
                val initial=fingerprint("baseline",run+"-aaaa",(length+1).toLong())
                import("unrelated-v2");val unrelated=fingerprint("unrelated-v2",run+"-zzzz",(length+1).toLong())
                check(unrelated["roots"]==initial["roots"]){"Unrelated import changed constraint fingerprint"}
                import("unrelated-v1");val restored=fingerprint("unrelated-v1",run+"-aaaa",(length+1).toLong())
                check(restored["roots"]==initial["roots"]){"Unrelated restore changed constraint fingerprint"};restored
            } else null
            import("v2");val changed=engineWindow("v2",true)
            if(changed==null) {
                println("VALIDATION_STALE_CAPTURED_BEFORE_WITNESS");System.out.flush()
                val witness=common(json(post("$url/witness",params)),listOf("SETUP","WITNESS"));number(witness["target"],"max",(length-1).toLong());println("    Validation explicit witness: $witness")
                if(matrix(witness,true)){println("VALIDATION_OUTCOME STALE_ONLY_AFTER_EXPLICIT_RELOAD");error("AUTOMATIC_APPLY_RED: persisted v2; native v1 until explicit witness")}
                println("VALIDATION_OUTCOME NEITHER");error("Explicit witness also failed; no product-gap conclusion")
            }
            val effectV2=if(engineEffects)fingerprint("v2",run+"-aaaa",(length-1).toLong()).also {fingerprintChanged(effectBefore!!,it)} else null
            println("    Validation measured: $changed")
            import("v1");val restored=engineWindow("v1",false)
            check(restored!=null){"RESTORE_AUTOMATIC_APPLY_RED: persisted v1; native v2 through restore deadline"}
            if(engineEffects)fingerprintChanged(effectV2!!,fingerprint("v1",run+"-aaaa",(length+1).toLong()))
            println("    Validation restored: $restored");println("VALIDATION_OUTCOME AUTOMATIC_ROUND_TRIP")
        }) {
            restoreAll({for(file in files)Files.deleteIfExists(file)}, {
                if(attempted) {
                    val cleanup=json(post("$url/cleanup",params));owned(cleanup);yes(cleanup,"idle");yes(cleanup,"sessionClosed");yes(cleanup,"baselineRestored");number(cleanup,"owned",0);number(cleanup,"heldRemaining",0)
                    check(cleanup["workerAlive"]?.isBoolean==true&&!cleanup["workerAlive"].asBoolean());if(begun)number(cleanup,"removed",6)
                    println("    Validation cleanup: $cleanup")
                    yes(cleanup,"observerRemoved")
                    val artifacts=cleanup["artifactDiagnostics"];check(artifacts?.isObject==true){"Missing artifact diagnostics"}
                    check(artifacts["baselineChecked"]?.isBoolean==true&&artifacts["restored"]?.isBoolean==true){"Missing artifact diagnostic flags"}
                    val types=artifacts["types"];check(types?.isObject==true){"Missing artifact types"}
                    if(artifacts["baselineChecked"].asBoolean()) {
                        val names=setOf("ImpExMedia","ImpExImportCronJob","ImpExImportJob","JobLog","LogFile")
                        check(types.fieldNames().asSequence().toSet()==names){"Missing artifact type inventory"}
                        val empty=java.security.MessageDigest.getInstance("SHA-256").digest(byteArrayOf()).joinToString(""){"%02x".format(it)}
                        check(artifacts["ownedReferenceInspectionComplete"]?.isBoolean==true){"Missing owned reference inspection"}
                        var restored=true // Global deltas and attribution completeness are diagnostic only.
                        for(type in names) {
                            val row=types[type]
                            fun count(key:String):Long {check(row[key]?.isIntegralNumber==true&&row[key].asLong()>=0){"Invalid artifact $key"};return row[key].asLong()}
                            val before=count("beforeCount");val now=count("currentCount");val added=count("addedCount");val removed=count("removedCount")
                            check(now==before+added-removed&&added<=now&&removed<=before){"Artifact count mismatch"}
                            for((key,n) in listOf("addedSha256" to added,"removedSha256" to removed)) {
                                val hash=row[key]?.asText().orEmpty();check(hash.matches(Regex("[a-f0-9]{64}"))&&(n!=0L||hash==empty)){"Invalid artifact $key"}
                            }
                            val captured=count("capturedCount");val remaining=count("ownedRemainingCount");val gone=count("ownedRemovedCount")
                            check(captured==remaining+gone&&remaining<=added){"Artifact ownership count mismatch"}
                            val basis=row["ownershipBasis"];check(basis?.isObject==true){"Missing artifact ownership basis"}
                            val reasons=setOf("exact-owned-script-bytes","returned-resource","owned-cron-work-media","owned-cron-unresolved-media","owned-cron-log","owned-cron-log-file","owned-job-media-relationship","owned-cron-job-reference")
                            var attributed=0L
                            for((reason,value) in basis.fields().asSequence()){check(reason in reasons&&value.isIntegralNumber&&value.asLong() in 1..captured){"Invalid artifact ownership basis"};attributed+=value.asLong()}
                            check(attributed>=captured){"Missing captured artifact attribution"}
                            val foreignAdded=row["foreignAdded"];val foreignRemoved=row["foreignRemoved"]
                            check(foreignAdded?.isArray==true&&foreignRemoved?.isArray==true){"Missing foreign artifact attribution"}
                            fun identities(entries:JsonNode) {
                                val ids=entries.map {it["identitySha256"]?.asText().orEmpty()}
                                check(ids.toSet().size==ids.size&&ids.all {it.matches(Regex("[a-f0-9]{64}"))}){"Invalid foreign artifact identities"}
                            }
                            identities(foreignAdded);identities(foreignRemoved)
                            for(entry in foreignAdded) {
                                check(entry["classification"]?.asText()=="FOREIGN_ADDED"&&entry["basis"]?.asText()=="native-owner-relations-and-reference-scan"){"Unresolved foreign owner basis"}
                                yes(entry,"ownerResolved");yes(entry,"referencesChecked");number(entry,"ownedReferenceCount",0)
                                val owners=entry["ownerTypes"];check(owners?.isArray==true&&owners.size()>0&&owners.all {it.asText() in setOf("CronJob","Job","Media","Item")}){"Missing foreign owner types"}
                            }
                            for(entry in foreignRemoved) {
                                check(entry["classification"]?.asText()=="FOREIGN_REMOVED"&&entry["basis"]?.asText()=="pre-existing-not-referenced-by-owned"){"Invalid foreign removal basis"};number(entry,"ownedReferenceCount",0)
                            }
                            val unresolved=count("unresolvedAddedCount");val ownedAdded=count("ownedReferencingAddedCount");val ownedRemoved=count("ownedReferencedRemovedCount")
                            check(added==foreignAdded.size()+unresolved+ownedAdded&&removed==foreignRemoved.size()+ownedRemoved&&remaining<=ownedAdded){"Artifact attribution count mismatch"}
                            if(remaining!=0L)restored=false
                        }
                        check(artifacts["restored"].asBoolean()==restored){"Artifact diagnostic restoration mismatch"}
                    } else check(!begun&&types.size()==0&&artifacts["restored"].asBoolean()){"Unchecked artifact baseline after setup"}
                    check(cleanup["artifactBaselineRestored"]?.isBoolean==true&&cleanup["artifactBaselineRestored"]==artifacts["restored"]){"Artifact baseline/diagnostic mismatch"}
                    yes(cleanup,"artifactBaselineRestored")
                }
            }, {
                val end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(minOf(config.eventTimeoutMs,2000))
                do {resourceFailure(agentClient.getEvents().filter {it.sequence>eventStart},files.map {it.fileName.toString()}){false}?.let{error(it)};Thread.sleep(10)}while(System.nanoTime()<end)
                check(files.none {Files.exists(it)}){"Owned constraint import file leaked"}
            }, {
                for(path in paths){val original=javaClass.classLoader.getResourceAsStream("templates/constraints-stage1/$path")!!.use{it.readBytes()};check(Files.readAllBytes(Path.of(config.extPath,path)).contentEquals(original))}
            })
        }
    }
}
