package com.onurkat.reclazztest.batch;

import de.hybris.platform.core.PK;
import de.hybris.platform.core.Registry;
import de.hybris.platform.core.model.ReclazzSchemaProbeModel;
import de.hybris.platform.jalo.JaloSession;
import de.hybris.platform.jalo.user.UserManager;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.type.TypeService;
import de.hybris.platform.tx.Transaction;
import de.hybris.platform.validation.daos.ConstraintDao;
import de.hybris.platform.validation.model.constraints.*;
import de.hybris.platform.validation.model.constraints.jsr303.*;
import de.hybris.platform.validation.services.ValidationService;
import java.util.*;
import java.util.concurrent.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.io.ByteArrayInputStream;
import de.hybris.platform.core.model.ItemModel;
import de.hybris.platform.servicelayer.impex.ImportService;
import de.hybris.platform.servicelayer.impex.impl.StreamBasedImpExResource;

/** Owned metadata experiment. Explicit engine rebuilds never count as automatic apply. */
public final class ProbeValidationState {
    private static Run active;
    private ProbeValidationState() { }
    public static String owned(String run) {
        if(!run.matches("^reclazz-c62-[0-9a-f-]{36}$")||!UUID.fromString(run.substring(12)).toString().equals(run.substring(12)))
            throw new IllegalArgumentException("Invalid owned run");
        return run;
    }
    public static synchronized boolean idle(){return active==null;}
    public static synchronized void open(String run,ModelService models,FlexibleSearchService search,TypeService types,ValidationService validation,ConstraintDao dao) {
        owned(run);if(active!=null)throw new IllegalStateException("Another validation probe owns the engine");
        active=new Run(run,models,search,types,validation,dao);active.worker.start();
    }
    private static synchronized Run require(String run){owned(run);if(active==null||!active.run.equals(run))throw new IllegalStateException("Wrong run");return active;}
    public static Map<String,Object> call(String run,String action) throws Exception {
        Run r=require(run);Command c=new Command(action);
        if(!r.worker.isAlive()||!r.commands.offer(c))throw new IllegalStateException("Worker unavailable");
        return c.reply.get(60,TimeUnit.SECONDS);
    }
    public static Map<String,Object> close(String run)throws Exception {
        Run r=require(run);Map<String,Object> result=call(run,"cleanup");r.worker.join(65000);
        if(r.worker.isAlive()||!r.sessionClosed||r.failure!=null)throw new IllegalStateException("Worker/session not restored: "+r.failure);
        synchronized(ProbeValidationState.class){if(active!=r)throw new IllegalStateException();active=null;}
        result.put("idle",true);result.put("workerAlive",false);result.put("sessionClosed",true);return result;
    }
    private record Command(String action,CompletableFuture<Map<String,Object>> reply) {Command(String action){this(action,new CompletableFuture<>());}}
    private static final class Run {
        final String run;final ModelService models;final FlexibleSearchService search;final TypeService types;final ValidationService validation;final ConstraintDao dao;
        final BlockingQueue<Command> commands=new ArrayBlockingQueue<>(2);final Thread worker;
        final Map<String,PK> pks=new LinkedHashMap<>();final Map<String,ReclazzSchemaProbeModel> held=new LinkedHashMap<>();
        final List<String> reloads=new ArrayList<>();List<String> activeBefore;String baseline,baselineNative;boolean begun,baselineReady,witnessed,lastStale;int calls;
        volatile String failure;volatile boolean sessionClosed;
        final Map<String,Set<PK>> artifactBefore=new LinkedHashMap<>();
        final Map<PK,ItemModel> importOwned=new LinkedHashMap<>();
        volatile ClassLoader watcherLoader;volatile Map<String,Object> watcherObservation=Map.of();
        Class<?> reporter,listenerType;Object listener;boolean diagnosticImported;
        ClassLoader hybrisLoader;volatile Thread watcherThread;volatile ClassLoader watcherBefore;volatile boolean watcherBeforeSeen;
        final List<Map<String,Object>> watcherTerminals=new CopyOnWriteArrayList<>();
        Run(String run,ModelService m,FlexibleSearchService s,TypeService t,ValidationService v,ConstraintDao d){this.run=run;models=m;search=s;types=t;validation=v;dao=d;worker=new Thread(this::work,"reclazz-validation-probe");worker.setDaemon(true);}
        String nonce(String role){return run+(role.equals("short")?"-aaaa":"-bbbbbbbb");}
        String id(String role){return run+"-"+role;}
        int length(){return nonce("short").length();}
        <T> List<T> all(String type){var q=new FlexibleSearchQuery("SELECT {pk} FROM {"+type+"}");q.setDisableSearchRestrictions(true);return search.<T>search(q).getResult();}
        List<String> activeGroups(){return validation.getActiveConstraintGroups().stream().map(g->g.getPk()+":"+g.getId()).sorted().toList();}
        String digest(List<String> rows)throws Exception {Collections.sort(rows);return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n",rows).getBytes(StandardCharsets.UTF_8)));}
        String foreign()throws Exception {
            List<String> rows=new ArrayList<>();
            for(AbstractConstraintModel c:this.<AbstractConstraintModel>all("AbstractConstraint"))if(!c.getId().startsWith(run+"-")) {
                String details=c.getPk()+":"+c.getModifiedtime().getTime()+":"+c.getId()+":"+c.getTarget()+":"+c.getAnnotation()+":"+c.isActive()+":"+c.getSeverity()+":"+c.getConstraintGroups().stream().map(g->g.getPk().toString()).sorted().toList();
                if(c instanceof AttributeConstraintModel a)details+=":"+a.getQualifier()+":"+a.getLanguages().stream().map(l->l.getPk().toString()).sorted().toList();
                if(c instanceof SizeConstraintModel a)details+=":"+a.getMin()+":"+a.getMax();
                if(c instanceof PatternConstraintModel a)details+=":"+a.getRegexp()+":"+a.getFlags().stream().map(Object::toString).sorted().toList();
                rows.add("constraint:"+details);
            }
            for(ConstraintGroupModel g:this.<ConstraintGroupModel>all("ConstraintGroup"))if(!g.getId().startsWith(run+"-"))rows.add("group:"+g.getPk()+":"+g.getModifiedtime().getTime()+":"+g.getId()+":"+g.getInterfaceName()+":"+g.getConstraints().stream().filter(c->!c.getId().startsWith(run+"-")).map(c->c.getPk().toString()).sorted().toList());
            for(ReclazzSchemaProbeModel p:this.<ReclazzSchemaProbeModel>all("ReclazzSchemaProbe"))if(!List.of(nonce("short"),nonce("long")).contains(p.getNonce()))rows.add("probe:"+p.getPk()+":"+p.getModifiedtime().getTime()+":"+p.getNonce());
            return digest(rows);
        }
        String baselineNative()throws Exception {
            ReclazzSchemaProbeModel probe=new ReclazzSchemaProbeModel();probe.setNonce(nonce("short"));
            List<String> rows=new ArrayList<>();for(var v:validation.validate(probe,new Class<?>[0]))rows.add(v.getProperty()+":"+v.getMessageTemplate()+":"+(v.getConstraintModel()==null?"annotation":v.getConstraintModel().getPk()));return digest(rows);
        }
        int ownedCount(){int count=0;for(AbstractConstraintModel c:this.<AbstractConstraintModel>all("AbstractConstraint"))if(c.getId().startsWith(run+"-"))count++;for(ConstraintGroupModel g:this.<ConstraintGroupModel>all("ConstraintGroup"))if(g.getId().startsWith(run+"-"))count++;for(ReclazzSchemaProbeModel p:this.<ReclazzSchemaProbeModel>all("ReclazzSchemaProbe"))if(List.of(nonce("short"),nonce("long")).contains(p.getNonce()))count++;return count;}
        void reload(String reason){if(!List.of("SETUP","WITNESS","CLEANUP").contains(reason))throw new IllegalArgumentException();validation.reloadValidationEngine();reloads.add(reason);}
        boolean nativeOwners() {
            var context=Registry.getApplicationContext();
            return context.getBean("validationService")==validation&&context.getBean("constraintDao")==dao&&context.getBean("modelService")==models&&context.getBean("typeService")==types&&context.getBean("flexibleSearchService")==search;
        }
        static final String PROVIDER="org.glassfish.jaxb.runtime.v2.ContextFactory";
        static final List<String> ARTIFACT_TYPES=List.of("ImpExMedia","ImpExImportCronJob","ImpExImportJob","JobLog","LogFile");
        ClassLoader nativeLoader(){return Registry.getApplicationContext().getClass().getClassLoader();}
        Class<?> provider(ClassLoader loader){try{return Class.forName(PROVIDER,false,loader);}catch(ClassNotFoundException|LinkageError e){return null;}}
        Map<String,Object> loader(ClassLoader loader){Class<?> found=provider(loader),expected=provider(nativeLoader());return Map.of("identity",System.identityHashCode(loader),"kind",loader==null?"bootstrap":loader.getClass().getName(),"providerVisible",found!=null,"providerSameAsNative",found!=null&&found==expected);}
        void armWatcher()throws Exception {
            if(!begun||listener!=null)throw new IllegalStateException("Observer already armed or no baseline");
            Class<?> holder=Class.forName("com.onurkat.reclazz.platform.ApplicationContextHolder",false,ClassLoader.getSystemClassLoader());
            Object selectedContext=null;
            for(Object context:(List<?>)holder.getMethod("getAllContexts").invoke(null)) {
                try {if((Boolean)context.getClass().getMethod("containsBean",String.class).invoke(context,"importService")) {
                    Object service=context.getClass().getMethod("getBean",String.class).invoke(context,"importService");
                    if(service!=null){selectedContext=context;break;}
                }}catch(ReflectiveOperationException unusable){/* Same skip rule as the importer; never choose a fallback loader. */}
            }
            if(selectedContext==null)throw new IllegalStateException("No import context for exact hybrisCl measurement");
            hybrisLoader=selectedContext.getClass().getClassLoader();
            reporter=Class.forName("com.onurkat.reclazz.ui.StatusReporter",false,ClassLoader.getSystemClassLoader());
            listenerType=Class.forName("com.onurkat.reclazz.ui.StatusReporter$StatusListener",false,reporter.getClassLoader());
            listener=Proxy.newProxyInstance(listenerType.getClassLoader(),new Class<?>[]{listenerType},(proxy,method,args)->{
                if(method.getName().equals("equals"))return proxy==args[0];
                if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                if(method.getName().equals("toString"))return "owned-validation-observer";
                if(method.getName().equals("onEvent")&&args[1] instanceof String text) {
                    String filename=null;for(int write=1;write<=2;write++){String candidate="reclazz-c62-"+run+"-"+write+".impex";if(text.contains(candidate)){filename=candidate;break;}}
                    if(filename!=null) {
                        Thread current=Thread.currentThread();
                        if(text.equals("ImpEx file changed: "+filename)){watcherThread=current;watcherBefore=current.getContextClassLoader();watcherBeforeSeen=true;}
                        boolean inside=Arrays.stream(current.getStackTrace()).anyMatch(frame->frame.getClassName().equals("com.onurkat.reclazz.hybris.impex.ImpexAutoImporter")&&frame.getMethodName().equals("importFile"));
                        if(inside&&(args[0].equals("OK")||args[0].equals("ERROR"))) {
                            watcherLoader=current.getContextClassLoader();
                            watcherObservation=Map.of("route","watcher","insideImportFile",true,"thread",current.getName(),"loader",loader(watcherLoader),"level",String.valueOf(args[0]),"file",filename,"sameAsHybrisLoader",watcherLoader==hybrisLoader);
                            watcherTerminals.add(watcherObservation);
                        }
                    }
                }
                return null;
            });reporter.getMethod("addListener",listenerType).invoke(null,listener);
        }
        void removeObserver()throws Exception {if(listener!=null){reporter.getMethod("removeListener",listenerType).invoke(null,listener);listener=null;}watcherLoader=null;watcherThread=null;watcherBefore=null;hybrisLoader=null;}
        Map<String,Object> watchObservation() {
            Thread observed=watcherThread;
            boolean outside=observed!=null&&Arrays.stream(observed.getStackTrace()).noneMatch(frame->frame.getClassName().equals("com.onurkat.reclazz.hybris.impex.ImpexAutoImporter")&&frame.getMethodName().equals("importFile"));
            return Map.of("run",run,"watcher",watcherObservation,"terminals",List.copyOf(watcherTerminals),"beforeSeen",watcherBeforeSeen,"beforeLoader",loader(watcherBefore),"hybrisLoader",loader(hybrisLoader),"outsideImportFile",outside,"tcclRestored",watcherBeforeSeen&&outside&&observed.getContextClassLoader()==watcherBefore,"directImports",diagnosticImported?1:0);
        }
        void captureImport(ItemModel item){
            if(item==null||models.isNew(item)||models.isRemoved(item))return;
            if(artifactBefore.values().stream().anyMatch(pks->pks.contains(item.getPk())))return;
            importOwned.put(item.getPk(),item);
        }
        void captureCron(de.hybris.platform.impex.model.cronjob.ImpExImportCronJobModel cron) {
            if(cron==null||models.isRemoved(cron)||artifactBefore.get("ImpExImportCronJob").contains(cron.getPk()))return;
            if(cron.getJobMedia()==null||!importOwned.containsKey(cron.getJobMedia().getPk()))throw new IllegalStateException("Import artifact ownership mismatch");
            captureImport(cron.getWorkMedia());captureImport(cron.getUnresolvedDataStore());
            for(var log:cron.getLogs())captureImport(log);for(var log:cron.getLogFiles())captureImport(log);
            captureImport(cron);
        }
        void captureWatcherArtifacts()throws Exception {
            if(watcherObservation.isEmpty())return;
            var mediaService=(de.hybris.platform.servicelayer.media.MediaService)Registry.getApplicationContext().getBean("mediaService");
            List<byte[]> expected=new ArrayList<>();for(int maximum:List.of(length()-1,length()+1))expected.add(("UPDATE SizeConstraint;id[unique=true];max\n;"+run+"-target;"+maximum+"\n").getBytes(StandardCharsets.UTF_8));
            for(de.hybris.platform.impex.model.ImpExMediaModel media:this.<de.hybris.platform.impex.model.ImpExMediaModel>all("ImpExMedia")) {
                if(artifactBefore.get("ImpExMedia").contains(media.getPk())||importOwned.containsKey(media.getPk()))continue;
                // A new PK alone is not ownership: compare bounded bytes to this unique run's exact script.
                try(var input=mediaService.getStreamFromMedia(media)) {byte[] actual=input.readNBytes(expected.stream().mapToInt(bytes->bytes.length).max().orElseThrow()+1);if(expected.stream().anyMatch(bytes->Arrays.equals(bytes,actual)))captureImport(media);}
                catch(de.hybris.platform.servicelayer.media.NoDataAvailableException noData){/* Not an owned script; never delete it by inference. */}
            }
            for(de.hybris.platform.impex.model.cronjob.ImpExImportCronJobModel cron:this.<de.hybris.platform.impex.model.cronjob.ImpExImportCronJobModel>all("ImpExImportCronJob"))
                if(cron.getJobMedia()!=null&&importOwned.containsKey(cron.getJobMedia().getPk()))captureCron(cron);
        }
        boolean artifactBaseline(){
            for(String type:ARTIFACT_TYPES){Set<PK> now=new HashSet<>();for(ItemModel item:this.<ItemModel>all(type))now.add(item.getPk());if(!now.equals(artifactBefore.get(type)))return false;}return true;
        }
        Map<String,Object> diagnostic(String mode)throws Exception {
            if(!begun||watcherObservation.isEmpty()||diagnosticImported||!List.of("inherited","watcher","native").contains(mode))throw new IllegalStateException("Diagnostic preflight/one import required");
            ClassLoader inherited=Thread.currentThread().getContextClassLoader(),nativeCl=nativeLoader();
            ClassLoader selected=mode.equals("native")?nativeCl:mode.equals("watcher")?watcherLoader:inherited;
            if(provider(nativeCl)==null)throw new IllegalStateException("Native provider unavailable");
            ImportService imports=(ImportService)Registry.getApplicationContext().getBean("importService");
            String script="UPDATE SizeConstraint;id[unique=true];max\n;"+run+"-target;"+(length()-1)+"\n";
            // Create the exact resource on the native worker before varying only import caller TCCL.
            var resource=new StreamBasedImpExResource(new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),"UTF-8");
            captureImport(resource.getMedia());resource.setCode(run+"-diagnostic-media");
            Map<String,Object> result=new LinkedHashMap<>();result.put("run",run);result.put("route","direct-native-control");result.put("mode",mode);result.put("watcher",watcherObservation);result.put("inherited",loader(inherited));result.put("selected",loader(selected));result.put("nativeLoader",loader(nativeCl));
            diagnosticImported=true;
            try {
                Thread.currentThread().setContextClassLoader(selected);
                var imported=imports.importData(resource);
                result.put("terminal",Map.of("successful",imported.isSuccessful(),"error",imported.isError(),"running",imported.isRunning(),"finished",imported.isFinished(),"unresolved",imported.hasUnresolvedLines()));
                captureCron(imported.getCronJob());
                result.put("capturedArtifactCount",importOwned.size());
            } finally {Thread.currentThread().setContextClassLoader(inherited);result.put("tcclRestored",Thread.currentThread().getContextClassLoader()==inherited);}
            result.put("scriptSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8))));result.put("snapshot",read());return result;
        }
        void begin(boolean partial)throws Exception {
            if(begun||baselineReady||ownedCount()!=0)throw new IllegalStateException("Dirty setup");
            if(!nativeOwners())throw new IllegalStateException("Native service owner mismatch");
            activeBefore=activeGroups();baselineNative=baselineNative();
            for(AbstractConstraintModel c:this.<AbstractConstraintModel>all("AbstractConstraint")) {
                if(c.isActive()&&validation.needReloadOfValidationEngine(c))throw new IllegalStateException("Unowned constraint needs reload");
                if(c instanceof AttributeConstraintModel a&&a.getTarget()==ReclazzSchemaProbeModel.class&&"nonce".equals(a.getQualifier())&&(a instanceof SizeConstraintModel||a instanceof PatternConstraintModel))throw new IllegalStateException("Unowned conflicting attribute constraint");
            }
            for(ConstraintGroupModel g:this.<ConstraintGroupModel>all("ConstraintGroup"))if(List.of(ProbeConstraintGroups.Target.class.getName(),ProbeConstraintGroups.Control.class.getName()).contains(g.getInterfaceName()))throw new IllegalStateException("Unowned fixed group marker");
            baseline=foreign();for(String type:ARTIFACT_TYPES){Set<PK> before=new HashSet<>();for(ItemModel item:this.<ItemModel>all(type))before.add(item.getPk());artifactBefore.put(type,before);}baselineReady=true;
            if(Transaction.current().isRunning())throw new IllegalStateException("Ambient transaction");Transaction tx=Transaction.current();tx.begin();
            try {
                for(String role:List.of("target","control")) {
                    ConstraintGroupModel g=models.create(ConstraintGroupModel.class);g.setId(id(role+"-group"));g.setInterfaceName((role.equals("target")?ProbeConstraintGroups.Target.class:ProbeConstraintGroups.Control.class).getName());models.save(g);pks.put(role+"-group",g.getPk());
                    if(dao.getTargetClass(g)!=(role.equals("target")?ProbeConstraintGroups.Target.class:ProbeConstraintGroups.Control.class))throw new IllegalStateException("Group class identity mismatch");
                }
                if(partial)throw new IllegalStateException("Injected partial setup after groups");
                for(String role:List.of("short","long")){ReclazzSchemaProbeModel p=models.create(ReclazzSchemaProbeModel.class);p.setNonce(nonce(role));models.save(p);pks.put(role,p.getPk());}
                SizeConstraintModel target=models.create(SizeConstraintModel.class);target.setId(id("target"));target.setActive(true);target.setTarget(ReclazzSchemaProbeModel.class);target.setType(types.getComposedTypeForCode("ReclazzSchemaProbe"));target.setDescriptor(types.getAttributeDescriptor("ReclazzSchemaProbe","nonce"));target.setQualifier("nonce");target.setConstraintGroups(Set.of(models.<ConstraintGroupModel>get(pks.get("target-group"))));target.setMin(0L);target.setMax((long)length()+1);models.save(target);pks.put("target",target.getPk());
                PatternConstraintModel control=models.create(PatternConstraintModel.class);control.setId(id("control"));control.setActive(true);control.setTarget(ReclazzSchemaProbeModel.class);control.setType(types.getComposedTypeForCode("ReclazzSchemaProbe"));control.setDescriptor(types.getAttributeDescriptor("ReclazzSchemaProbe","nonce"));control.setQualifier("nonce");control.setConstraintGroups(Set.of(models.<ConstraintGroupModel>get(pks.get("control-group"))));control.setRegexp(".*-bbbbbbbb$");control.setFlags(Set.of());models.save(control);pks.put("control",control.getPk());
                tx.commit();
            } finally{if(tx.isRunning())tx.rollback();models.detachAll();}
            reload("SETUP");for(String role:List.of("short","long"))held.put(role,models.get(pks.get(role)));begun=true;
        }
        Map<String,Object> violations(ReclazzSchemaProbeModel probe,String role,String group) {
            ConstraintGroupModel g=models.get(pks.get(group+"-group"));Class<?> marker=group.equals("target")?ProbeConstraintGroups.Target.class:ProbeConstraintGroups.Control.class;
            if(dao.getTargetClass(g)!=marker)throw new IllegalStateException("Group class identity changed");
            calls++;List<Map<String,Object>> result=new ArrayList<>();
            for(var v:validation.validate(probe,List.of(g))) {
                var raw=v.getConstraintViolation();var c=v.getConstraintModel();
                if(c==null||!c.getPk().equals(pks.get(group))||!"nonce".equals(raw.getPropertyPath().toString())||!"ReclazzSchemaProbe.nonce".equals(v.getQualifier())||raw.getRootBean()!=probe||!nonce(role).equals(raw.getInvalidValue()))throw new IllegalStateException("Wrong native constraint attribution: pk="+(c!=null&&c.getPk().equals(pks.get(group)))+", property="+"nonce".equals(raw.getPropertyPath().toString())+", qualifier="+"ReclazzSchemaProbe.nonce".equals(v.getQualifier())+", root="+(raw.getRootBean()==probe)+", value="+nonce(role).equals(raw.getInvalidValue()));
                result.add(Map.of("constraintPk",c.getPk().toString(),"property",raw.getPropertyPath().toString(),"qualifier",v.getQualifier(),"annotation",raw.getConstraintDescriptor().getAnnotation().annotationType().getName(),"invalid",String.valueOf(raw.getInvalidValue()),"rootPk",probe.getPk().toString()));
            }
            return Map.of("count",result.size(),"violations",result);
        }
        Map<String,Object> read()throws Exception {
            if(!begun||!nativeOwners())throw new IllegalStateException("Setup/service ownership changed");models.detachAll();
            if(!baseline.equals(foreign())||!activeBefore.equals(activeGroups()))throw new IllegalStateException("Unowned metadata/active groups changed");
            SizeConstraintModel target=models.get(pks.get("target"));PatternConstraintModel control=models.get(pks.get("control"));
            for(var c:List.of(target,control))if(c.getTarget()!=ReclazzSchemaProbeModel.class||!"nonce".equals(c.getQualifier())||!c.getType().getCode().equals("ReclazzSchemaProbe")||!c.getDescriptor().getQualifier().equals("nonce")||!c.isActive()||c.getConstraintGroups().size()!=1)throw new IllegalStateException("Native binding changed");
            if(!target.getConstraintGroups().iterator().next().getPk().equals(pks.get("target-group"))||!control.getConstraintGroups().iterator().next().getPk().equals(pks.get("control-group"))||!".*-bbbbbbbb$".equals(control.getRegexp())||!control.getFlags().isEmpty()||target.getMin()!=0L)throw new IllegalStateException("Owned control metadata changed");
            Map<String,Object> rows=new LinkedHashMap<>(),nativeResult=new LinkedHashMap<>();
            for(String role:List.of("short","long")){ReclazzSchemaProbeModel fresh=models.get(pks.get(role));if(fresh==held.get(role)||!nonce(role).equals(fresh.getNonce())||!nonce(role).equals(held.get(role).getNonce())||!fresh.getPk().equals(held.get(role).getPk()))throw new IllegalStateException("Probe identity/nonce changed");rows.put(role,Map.of("pk",fresh.getPk().toString(),"nonce",fresh.getNonce(),"differentFresh",true));}
            for(String path:List.of("held","fresh")) {
                Map<String,Object> groups=new LinkedHashMap<>();
                for(String group:List.of("target","control")){Map<String,Object> roles=new LinkedHashMap<>();for(String role:List.of("short","long")){ReclazzSchemaProbeModel p=path.equals("held")?held.get(role):models.get(pks.get(role));roles.put(role,violations(p,role,group));}groups.put(group,roles);}nativeResult.put(path,groups);
            }
            Map<?,?> h=(Map<?,?>)((Map<?,?>)nativeResult.get("held")).get("target"),f=(Map<?,?>)((Map<?,?>)nativeResult.get("fresh")).get("target");
            lastStale=target.getMax()==length()-1L&&((Number)((Map<?,?>)h.get("short")).get("count")).intValue()==0&&((Number)((Map<?,?>)f.get("short")).get("count")).intValue()==0;
            Map<String,Object> result=new LinkedHashMap<>();result.put("run",run);result.put("owned",ownedCount());result.put("rows",rows);result.put("native",nativeResult);result.put("target",Map.of("pk",target.getPk().toString(),"id",target.getId(),"min",target.getMin(),"max",target.getMax(),"needReload",validation.needReloadOfValidationEngine(target)));result.put("control",Map.of("pk",control.getPk().toString(),"id",control.getId(),"regexp",control.getRegexp(),"flags",control.getFlags().size()));result.put("foreignSha256",baseline);result.put("activeGroupsRestored",activeBefore.equals(activeGroups()));result.put("reloads",List.copyOf(reloads));result.put("calls",calls);result.put("workerAlive",worker.isAlive());result.put("serviceIdentity",System.identityHashCode(validation));result.put("groupIdentity",true);result.put("nativeOwners",true);return result;
        }
        Map<String,Object> witness()throws Exception {if(!lastStale||witnessed)throw new IllegalStateException("Witness requires a captured stale native result");witnessed=true;reload("WITNESS");return read();}
        Map<String,Object> cleanup()throws Exception {
            removeObserver();models.detachAll();captureWatcherArtifacts();int removed=0;
            for(ItemModel item:List.copyOf(importOwned.values()))if(item instanceof de.hybris.platform.impex.model.cronjob.ImpExImportCronJobModel cron) {
                var job=cron.getJob();
                if(job!=null&&!artifactBefore.get("ImpExImportJob").contains(job.getPk())) {
                    for(de.hybris.platform.cronjob.model.CronJobModel other:this.<de.hybris.platform.cronjob.model.CronJobModel>all("CronJob"))
                        if(other.getJob()!=null&&other.getJob().getPk().equals(job.getPk())&&!importOwned.containsKey(other.getPk()))throw new IllegalStateException("New import job has unowned references; refuse deletion");
                    captureImport(job);
                }
            }
            // Only exact returned artifacts, never rows selected by type/time difference.
            for(ItemModel item:importOwned.values().stream().filter(i->i instanceof de.hybris.platform.impex.model.cronjob.ImpExImportCronJobModel).toList())if(!models.isRemoved(item))models.remove(item);
            for(ItemModel item:importOwned.values())if(!models.isRemoved(item))models.remove(item);
            int capturedArtifacts=importOwned.size();importOwned.clear();
            for(String type:List.of("AbstractConstraint","ConstraintGroup","ReclazzSchemaProbe")) {
                List<de.hybris.platform.core.model.ItemModel> found=new ArrayList<>();
                for(Object value:all(type)) {
                    var m=(de.hybris.platform.core.model.ItemModel)value;
                    boolean ours=value instanceof AbstractConstraintModel c?c.getId().startsWith(run+"-"):value instanceof ConstraintGroupModel g?g.getId().startsWith(run+"-"):List.of(nonce("short"),nonce("long")).contains(((ReclazzSchemaProbeModel)value).getNonce());
                    if(ours){if(!pks.containsValue(m.getPk()))throw new IllegalStateException("Unknown owned-prefix PK; refuse deletion");found.add(m);}
                }
                removed+=found.size();models.removeAll(found);models.detachAll();
            }
            held.clear();if(baselineReady){reload("CLEANUP");if(!baseline.equals(foreign())||!baselineNative.equals(baselineNative())||!activeBefore.equals(activeGroups()))throw new IllegalStateException("Native baseline not restored");}
            if(ownedCount()!=0)throw new IllegalStateException("Owned rows remain");pks.clear();var result=new LinkedHashMap<String,Object>();result.put("run",run);result.put("owned",0);result.put("removed",removed);result.put("reloads",List.copyOf(reloads));result.put("baselineRestored",true);result.put("heldRemaining",held.size());result.put("observerRemoved",listener==null);result.put("capturedArtifactsRemoved",capturedArtifacts);result.put("artifactBaselineRestored",!baselineReady||artifactBaseline());return result;
        }
        void work() {
            JaloSession session=null;Command current=null;
            try {
                Registry.activateMasterTenantAndFailIfAlreadySet();session=JaloSession.getCurrentSession();session.setUser(UserManager.getInstance().getAdminEmployee());
                while(true) {
                    current=commands.poll(180,TimeUnit.SECONDS);if(current==null)throw new IllegalStateException("Probe lease expired; native recovery required");
                    if(Transaction.current().isRunning())throw new IllegalStateException("Worker retained a transaction");
                    try {
                        Map<String,Object> value=switch(current.action){case "begin"->{begin(false);yield read();}case "partial"->{begin(true);yield read();}case "read"->read();case "script-v1","script-v2"->{if(!begun)throw new IllegalStateException("Setup incomplete");yield Map.of("script","UPDATE SizeConstraint;id[unique=true];max\n;"+run+"-target;"+(current.action.equals("script-v1")?length()+1:length()-1)+"\n");}case "watch-arm"->{armWatcher();yield Map.of("run",run,"armed",true,"hybrisLoader",loader(hybrisLoader));}case "watch-observation"->{yield watchObservation();}case "import-inherited","import-watcher","import-native"->diagnostic(current.action.substring(7));case "witness"->witness();case "cleanup"->cleanup();default->throw new IllegalArgumentException("Unknown fixed action");};
                        current.reply.complete(value);if(current.action.equals("cleanup"))break;
                    }catch(Exception e){current.reply.completeExceptionally(e);}
                    current=null;
                }
            }catch(Throwable e){failure=e.toString();if(current!=null)current.reply.completeExceptionally(e);}
            finally{try{removeObserver();if(session!=null)session.close();sessionClosed=true;}catch(Throwable e){failure="session close: "+e;}finally{Registry.unsetCurrentTenant();}for(Command c:commands)c.reply.completeExceptionally(new IllegalStateException("Worker closed"));commands.clear();}
        }
    }
}
