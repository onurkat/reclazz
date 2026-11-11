package com.onurkat.reclazztest.controllers;

import com.onurkat.reclazztest.batch.ProbeLoadInterceptor;
import com.onurkat.reclazztest.batch.ProbeLoadState;
import de.hybris.platform.core.PK;
import de.hybris.platform.core.Registry;
import de.hybris.platform.core.model.ReclazzSchemaProbeModel;
import de.hybris.platform.servicelayer.interceptor.impl.DefaultInterceptorRegistry;
import de.hybris.platform.servicelayer.interceptor.impl.InterceptorMapping;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.tx.Transaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Native model-context fresh loads and same-context warm controls. */
@RestController
@RequestMapping("/test/batch/load")
public class LoadProbeController {
    private static final String TYPE = "ReclazzSchemaProbe";
    @Autowired private ModelService modelService;
    @Autowired private FlexibleSearchService searchService;

    private String owned(String run) {
        if (!"master".equals(Registry.getCurrentTenant().getTenantID()) || !run.matches("^reclazz-load-[0-9a-f-]{36}$"))
            throw new IllegalArgumentException("Invalid owned master run");
        return run;
    }
    private List<ReclazzSchemaProbeModel> rows(String run) {
        var query = new FlexibleSearchQuery("SELECT {pk} FROM {" + TYPE + "} WHERE {nonce} LIKE ?prefix ORDER BY {nonce}");
        query.addQueryParameter("prefix", owned(run) + "-%"); query.setDisableSearchRestrictions(true);
        return searchService.<ReclazzSchemaProbeModel>search(query).getResult();
    }
    private long globalCount() {
        var query = new FlexibleSearchQuery("SELECT COUNT({pk}) FROM {" + TYPE + "}");
        query.setDisableSearchRestrictions(true); query.setResultClassList(List.of(Long.class));
        return ((Number) searchService.search(query).getResult().get(0)).longValue();
    }
    private Transaction begin() {
        Transaction tx = Transaction.current();
        if (tx.isRunning()) throw new IllegalStateException("Probe requires its own transaction");
        tx.begin(); return tx;
    }
    private Map<String, Object> registry() throws Exception {
        var context = Registry.getApplicationContext();
        var registry = context.getBean("interceptorRegistry", DefaultInterceptorRegistry.class);
        Object service = modelService;
        if (service instanceof org.springframework.aop.framework.Advised)
            service = ((org.springframework.aop.framework.Advised) service).getTargetSource().getTarget();
        Object actual = service.getClass().getMethod("getInterceptorRegistry").invoke(service);
        var target = context.getBean("reclazzProbeLoadInterceptor", ProbeLoadInterceptor.class);
        var mapping = context.getBean("reclazzProbeLoadMapping", InterceptorMapping.class);
        long count = registry.getLoadInterceptors(TYPE).stream().filter(value -> value == target).count();
        return Map.of("sameRegistry", actual == registry, "registryClass", registry.getClass().getName(),
                "registryIdentity", System.identityHashCode(registry), "targetCount", count,
                "mappingMatches", mapping.getInterceptor() == target && TYPE.equals(mapping.getTypeCode()),
                "version", target.version());
    }
    @GetMapping("/ready")
    public synchronized Map<String,Object> ready(@RequestParam("run") String run) throws Exception {
        owned(run); var result = new LinkedHashMap<String,Object>(registry());
        result.putAll(count(run)); return result;
    }
    @GetMapping("/count")
    public synchronized Map<String,Object> count(@RequestParam("run") String run) {
        owned(run);
        return Map.of("run",run,"ownedRemaining",rows(run).size(),"globalRemaining",globalCount(),
                "heldCount",ProbeLoadState.held(),"scopeCount",ProbeLoadState.scopes(),
                "recorderRemaining",ProbeLoadState.held(),"steps",ProbeLoadState.snapshot(run));
    }
    private Map<String,Object> proof(String run,int step,ReclazzSchemaProbeModel model) {
        return Map.of("run",run,"step",step,"pk",model.getPk().toString(),"nonce",model.getNonce(),
            "isNew",modelService.isNew(model),"steps",ProbeLoadState.snapshot(run));
    }
    @PostMapping("/seed")
    public synchronized Map<String,Object> seed(@RequestParam("run") String run) {
        owned(run);ProbeLoadState.own(run);
        if(globalCount()!=0 || ProbeLoadState.held()!=0) throw new IllegalStateException("Dirty seed");
        var tx=begin();
        try {
            ReclazzSchemaProbeModel a=modelService.create(TYPE),b=modelService.create(TYPE);
            a.setNonce(run+"-target");b.setNonce(run+"-control");modelService.save(a);modelService.save(b);
            tx.commit();ProbeLoadState.seed(run,a.getPk(),b.getPk());
            return Map.of("run",run,"pk",a.getPk().toString(),"controlPk",b.getPk().toString());
        } finally {if(tx.isRunning()) tx.rollback();modelService.detachAll();}
    }
    private void releaseAttached(ReclazzSchemaProbeModel... models) {
        // Detach while the context that installed each listener is still current.
        ProbeLoadState.end();
        RuntimeException failure=null;
        var seen=Collections.newSetFromMap(new IdentityHashMap<ReclazzSchemaProbeModel,Boolean>());
        try {
            for(var model:models) if(model!=null && seen.add(model)) {
                try {modelService.detach(model);}
                catch(RuntimeException e) {if(failure==null) failure=e;else failure.addSuppressed(e);}
            }
            if(failure!=null) throw failure;
        } finally {modelService.detachAll();}
    }
    @PostMapping("/load")
    public synchronized Map<String,Object> load(@RequestParam("run") String run,@RequestParam("step") int step,
            @RequestParam(value="reuse",defaultValue="false") boolean reuse) {
        owned(run);modelService.detachAll();
        ReclazzSchemaProbeModel reused=reuse?ProbeLoadState.model(run,0):null;
        ReclazzSchemaProbeModel fresh=null;
        try {
            if(reused!=null) modelService.attach(reused);
            ProbeLoadState.begin(run,step);
            fresh=modelService.get(ProbeLoadState.target(run));
            boolean recorded=ProbeLoadState.model(run,step)==fresh;
            var before=ProbeLoadState.snapshot(run);
            ReclazzSchemaProbeModel warm=modelService.get(ProbeLoadState.target(run));
            var out=new LinkedHashMap<String,Object>(proof(run,step,fresh));
            out.put("recordedSame",recorded);out.put("freshDistinct",ProbeLoadState.distinct(run,step,fresh));
            out.put("warmSame",warm==fresh);out.put("warmUnchanged",before.equals(ProbeLoadState.snapshot(run)));
            return out;
        } finally {releaseAttached(fresh,reused,ProbeLoadState.recorded(run,step));}
    }
    @PostMapping("/warm")
    public synchronized Map<String,Object> warm(@RequestParam("run") String run) {
        owned(run);modelService.detachAll();var old=ProbeLoadState.model(run,0);
        var before=ProbeLoadState.snapshot(run);
        try {
            modelService.attach(old);ProbeLoadState.begin(run,-1);
            ReclazzSchemaProbeModel warm=modelService.get(ProbeLoadState.target(run));
            var out=new LinkedHashMap<String,Object>(proof(run,0,warm));
            out.put("warmSame",warm==old);out.put("warmUnchanged",before.equals(ProbeLoadState.snapshot(run)));return out;
        } finally {releaseAttached(old);}
    }
    @GetMapping("/persisted")
    public synchronized Map<String,Object> persisted(@RequestParam("run") String run) {
        owned(run);modelService.detachAll();var before=ProbeLoadState.snapshot(run);
        try {
            ReclazzSchemaProbeModel a=modelService.get(ProbeLoadState.target(run)),b=modelService.get(ProbeLoadState.control(run));
            return Map.of("run",run,"pk",a.getPk().toString(),"controlPk",b.getPk().toString(),
                "nonce",a.getNonce(),"controlNonce",b.getNonce(),"recorderUnchanged",before.equals(ProbeLoadState.snapshot(run)),"steps",ProbeLoadState.snapshot(run));
        } finally {modelService.detachAll();}
    }
    @PostMapping("/guard")
    public synchronized Map<String,Object> guard(@RequestParam("run") String run) {
        owned(run);if(globalCount()!=0 || ProbeLoadState.held()!=0) throw new IllegalStateException("Dirty guard");
        String id=run.substring("reclazz-load-".length());
        var nonces=List.of("reclazz-dao-"+id+"-a-1","reclazz-dyn-"+id+"-row","reclazz-schema-"+id,"reclazz-prep-"+id+"-seed","reclazz-init-"+id+"-s0-default-v1");
        var pks=new ArrayList<PK>();var tx=begin();boolean committed=false;
        try {
            for(String nonce:nonces) {ReclazzSchemaProbeModel model=modelService.create(TYPE);model.setNonce(nonce);modelService.save(model);pks.add(model.getPk());}
            tx.commit();committed=true;modelService.detachAll();var observed=new ArrayList<String>();
            for(PK pk:pks) {ReclazzSchemaProbeModel first=modelService.get(pk);if(modelService.get(pk)!=first) throw new IllegalStateException("Guard not warm");observed.add(first.getNonce());}
            if(!nonces.equals(observed) || ProbeLoadState.held()!=0) throw new IllegalStateException("Guard changed");
            return Map.of("run",run,"nonces",observed,"recorderRemaining",0);
        } finally {if(tx.isRunning()) tx.rollback();try {if(committed) for(PK pk:pks) modelService.remove(pk);} finally {modelService.detachAll();}}
    }
    @PostMapping("/cleanup")
    public synchronized Map<String,Object> cleanup(@RequestParam("run") String run) {
        owned(run);ProbeLoadState.own(run);int removed=0;
        try {var found=rows(run);removed=found.size();modelService.removeAll(found);}
        finally {ProbeLoadState.clear(run);modelService.detachAll();}
        var out=new LinkedHashMap<String,Object>(count(run));out.put("removed",removed);return out;
    }
}
