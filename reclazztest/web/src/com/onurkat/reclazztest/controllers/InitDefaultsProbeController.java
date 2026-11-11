package com.onurkat.reclazztest.controllers;

import com.onurkat.reclazztest.batch.ProbeInitDefaultsInterceptor;
import com.onurkat.reclazztest.batch.ProbeInitDefaultsState;
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

/** Native create and save are separate; reads never invoke lifecycle work. */
@RestController
@RequestMapping("/test/batch/init-defaults")
public class InitDefaultsProbeController {
    private static final String TYPE = "ReclazzSchemaProbe";
    @Autowired private ModelService modelService;
    @Autowired private FlexibleSearchService searchService;

    private String owned(String run) {
        if (!"master".equals(Registry.getCurrentTenant().getTenantID()) || !run.matches("^reclazz-init-[0-9a-f-]{36}$"))
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
        var target = context.getBean("reclazzProbeInitDefaultsInterceptor", ProbeInitDefaultsInterceptor.class);
        var mapping = context.getBean("reclazzProbeInitDefaultsMapping", InterceptorMapping.class);
        long count = registry.getInitDefaultsInterceptors(TYPE).stream().filter(value -> value == target).count();
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
                "heldCount",ProbeInitDefaultsState.held(),"scopeCount",ProbeInitDefaultsState.scopes(),
                "recorderRemaining",ProbeInitDefaultsState.entries(),"steps",ProbeInitDefaultsState.snapshot(run));
    }
    private Map<String,Object> heldProof(String run, int step) {
        var model = ProbeInitDefaultsState.model(run,step);
        return Map.of("run",run,"step",step,"nonce",Objects.toString(model.getNonce(),""),
                "isNew",modelService.isNew(model),"identity",System.identityHashCode(model),
                "pk",model.getPk() == null ? "" : model.getPk().toString(),
                "steps",ProbeInitDefaultsState.snapshot(run));
    }
    @PostMapping("/create")
    public synchronized Map<String,Object> create(@RequestParam("run") String run,@RequestParam("step") int step) {
        owned(run); ProbeInitDefaultsState.begin(run,step);
        try {
            ReclazzSchemaProbeModel model = modelService.create(TYPE);
            ProbeInitDefaultsState.retain(run,step,model);
            return heldProof(run,step);
        } finally { ProbeInitDefaultsState.end(); modelService.detachAll(); }
    }
    @GetMapping("/held")
    public synchronized Map<String,Object> held(@RequestParam("run") String run,@RequestParam("step") int step) {
        owned(run); return heldProof(run,step);
    }
    @PostMapping("/save")
    public synchronized Map<String,Object> save(@RequestParam("run") String run,@RequestParam("step") int step) {
        owned(run); var model = ProbeInitDefaultsState.model(run,step);
        if (!modelService.isNew(model)) throw new IllegalStateException("Repeated save");
        var tx = begin();
        try { modelService.save(model); tx.commit(); }
        finally { if (tx.isRunning()) tx.rollback(); modelService.detachAll(); }
        return heldProof(run,step);
    }
    @GetMapping("/read")
    public synchronized Map<String,Object> read(@RequestParam("run") String run,@RequestParam("step") int step) {
        owned(run); var original = ProbeInitDefaultsState.model(run,step);
        if (modelService.isNew(original) || original.getPk() == null) throw new IllegalStateException("Not persisted");
        modelService.detachAll();
        ReclazzSchemaProbeModel fresh = modelService.get(original.getPk());
        return Map.of("run",run,"step",step,"pk",fresh.getPk().toString(),"nonce",fresh.getNonce(),
                "detachedRead",fresh != original,"steps",ProbeInitDefaultsState.snapshot(run));
    }
    @PostMapping("/guard")
    public synchronized Map<String,Object> guard(@RequestParam("run") String run) {
        owned(run); if (globalCount()!=0 || ProbeInitDefaultsState.held()!=0 || ProbeInitDefaultsState.entries()!=0)
            throw new IllegalStateException("Dirty guard baseline");
        String id=run.substring("reclazz-init-".length());
        var nonces=List.of("reclazz-dao-"+id+"-a-1","reclazz-dyn-"+id+"-row","reclazz-schema-"+id,"reclazz-prep-"+id+"-seed");
        var pks=new ArrayList<PK>(); var tx=begin(); boolean committed=false;
        try {
            for (String nonce:nonces) {
                ReclazzSchemaProbeModel model=modelService.create(TYPE);
                if (model.getNonce()!=null || ProbeInitDefaultsState.entries()!=0) throw new IllegalStateException("Unscoped create changed");
                model.setNonce(nonce); modelService.save(model); pks.add(model.getPk());
            }
            tx.commit(); committed=true; modelService.detachAll();
            var observed=new ArrayList<String>();
            for(PK pk:pks) observed.add(((ReclazzSchemaProbeModel)modelService.get(pk)).getNonce());
            if(!observed.equals(nonces) || ProbeInitDefaultsState.entries()!=0 || globalCount()!=4)
                throw new IllegalStateException("Unrelated rows changed");
            return Map.of("run",run,"nonces",observed,"recorderRemaining",ProbeInitDefaultsState.entries());
        } finally {
            if(tx.isRunning()) tx.rollback();
            if(committed) for(PK pk:pks) modelService.remove(pk);
            modelService.detachAll();
            if(globalCount()!=0) throw new IllegalStateException("Guard cleanup left rows");
        }
    }
    @PostMapping("/cleanup")
    public synchronized Map<String,Object> cleanup(@RequestParam("run") String run) {
        owned(run); int removed=0;
        try { var found=rows(run); removed=found.size(); modelService.removeAll(found); }
        finally {
            // Drop retained references even when persistence removal fails.
            try { for(var model:ProbeInitDefaultsState.models(run)) modelService.detach(model); }
            finally { ProbeInitDefaultsState.clear(run); modelService.detachAll(); }
        }
        var result=new LinkedHashMap<String,Object>(count(run)); result.put("removed",removed); return result;
    }
}
