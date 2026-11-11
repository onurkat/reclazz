package com.onurkat.reclazztest.controllers;

import com.onurkat.reclazztest.batch.ProbeRemoveInterceptor;
import com.onurkat.reclazztest.batch.ProbeRemoveState;
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

/** Native scoped removal with independent committed persistence checks. */
@RestController
@RequestMapping("/test/batch/remove")
public class RemoveProbeController {
    private static final String TYPE = "ReclazzSchemaProbe";
    @Autowired private ModelService modelService;
    @Autowired private FlexibleSearchService searchService;

    private String owned(String run) {
        if (!"master".equals(Registry.getCurrentTenant().getTenantID()) || !run.matches("^reclazz-remove-[0-9a-f-]{36}$"))
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
        var target = context.getBean("reclazzProbeRemoveInterceptor", ProbeRemoveInterceptor.class);
        var mapping = context.getBean("reclazzProbeRemoveMapping", InterceptorMapping.class);
        long count = registry.getRemoveInterceptors(TYPE).stream().filter(value -> value == target).count();
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
                "heldCount",ProbeRemoveState.held(),"scopeCount",ProbeRemoveState.scopes(),
                "recorderRemaining",ProbeRemoveState.held(),"steps",ProbeRemoveState.snapshot(run));
    }
    private long persisted(PK pk) {
        var q=new FlexibleSearchQuery("SELECT COUNT({pk}) FROM {"+TYPE+"} WHERE {pk}=?pk");
        q.addQueryParameter("pk",pk);q.setDisableSearchRestrictions(true);q.setResultClassList(List.of(Long.class));
        return ((Number)searchService.search(q).getResult().get(0)).longValue();
    }
    @PostMapping("/seed")
    public synchronized Map<String,Object> seed(@RequestParam("run") String run) {
        owned(run);ProbeRemoveState.own(run);
        if(globalCount()!=0 || ProbeRemoveState.held()!=0) throw new IllegalStateException("Dirty seed");
        var tx=begin();var targets=new ArrayList<PK>();
        try {
            for(int step=0;step<3;step++) {
                ReclazzSchemaProbeModel m=modelService.create(TYPE);m.setNonce(run+"-s"+step);modelService.save(m);targets.add(m.getPk());
            }
            ReclazzSchemaProbeModel c=modelService.create(TYPE);c.setNonce(run+"-control");modelService.save(c);
            tx.commit();ProbeRemoveState.seed(run,targets,c.getPk());
            return Map.of("run",run,"pks",targets.stream().map(Object::toString).toList(),"controlPk",c.getPk().toString());
        } finally {if(tx.isRunning()) tx.rollback();modelService.detachAll();}
    }
    @PostMapping("/remove")
    public synchronized Map<String,Object> remove(@RequestParam("run") String run,@RequestParam("step") int step,
            @RequestParam(value="omit",defaultValue="false") boolean omit) {
        owned(run);var pk=ProbeRemoveState.target(run,step);modelService.detachAll();
        var tx=begin();
        try {
            ReclazzSchemaProbeModel model=modelService.get(pk);String nonce=model.getNonce();
            if(!nonce.equals(run+"-s"+step) || modelService.isNew(model)) throw new IllegalStateException("Not an owned persisted target");
            ProbeRemoveState.begin(run,step);
            if(!omit) modelService.remove(model);
            tx.commit();
            return Map.of("run",run,"step",step,"pk",pk.toString(),"nonce",nonce,
                "recordedSame",ProbeRemoveState.recordedSame(run,step,model),"isRemoved",modelService.isRemoved(model),
                "steps",ProbeRemoveState.snapshot(run));
        } finally {try {if(tx.isRunning()) tx.rollback();} finally {ProbeRemoveState.end();modelService.detachAll();}}
    }
    @GetMapping("/persisted")
    public synchronized Map<String,Object> persisted(@RequestParam("run") String run,@RequestParam("step") int step) {
        owned(run);modelService.detachAll();var before=ProbeRemoveState.snapshot(run);
        try {
            var pk=ProbeRemoveState.target(run,step);var cpk=ProbeRemoveState.control(run);
            ReclazzSchemaProbeModel c=modelService.get(cpk);
            return Map.of("run",run,"step",step,"pk",pk.toString(),"targetRemaining",persisted(pk),
                "controlPk",cpk.toString(),"controlNonce",c.getNonce(),"controlRemaining",persisted(cpk),
                "recorderUnchanged",before.equals(ProbeRemoveState.snapshot(run)),"steps",ProbeRemoveState.snapshot(run));
        } finally {modelService.detachAll();}
    }
    @PostMapping("/guard")
    public synchronized Map<String,Object> guard(@RequestParam("run") String run) {
        owned(run);if(globalCount()!=0 || ProbeRemoveState.held()!=0) throw new IllegalStateException("Dirty guard");
        String id=run.substring("reclazz-remove-".length());
        var nonces=List.of("reclazz-dao-"+id+"-a-1","reclazz-dyn-"+id+"-row","reclazz-schema-"+id,
            "reclazz-prep-"+id+"-seed","reclazz-init-"+id+"-s0-default-v1","reclazz-load-"+id+"-target");
        var pks=new ArrayList<PK>();var tx=begin();boolean committed=false;
        var before=ProbeRemoveState.snapshot(run);
        try {
            for(String nonce:nonces) {ReclazzSchemaProbeModel m=modelService.create(TYPE);m.setNonce(nonce);modelService.save(m);pks.add(m.getPk());}
            tx.commit();committed=true;modelService.detachAll();
            for(PK pk:pks) modelService.remove(pk);
            if(!before.equals(ProbeRemoveState.snapshot(run)) || pks.stream().anyMatch(pk->persisted(pk)!=0)) throw new IllegalStateException("Unscoped guard changed");
            return Map.of("run",run,"nonces",nonces,"removed",pks.size(),"recorderRemaining",0);
        } finally {
            if(tx.isRunning()) tx.rollback();
            try {if(committed) for(PK pk:pks) if(persisted(pk)!=0) modelService.remove(pk);}
            finally {modelService.detachAll();}
        }
    }
    @PostMapping("/cleanup")
    public synchronized Map<String,Object> cleanup(@RequestParam("run") String run) {
        owned(run);ProbeRemoveState.own(run);
        if(ProbeRemoveState.scopes()!=0) throw new IllegalStateException("Leaked remove scope");
        var before=ProbeRemoveState.snapshot(run);int removed=0;boolean unchanged=false;
        try {
            var found=rows(run);removed=found.size();modelService.removeAll(found);
            unchanged=before.equals(ProbeRemoveState.snapshot(run));
            if(!unchanged) throw new IllegalStateException("Cleanup produced measured callback");
        } finally {ProbeRemoveState.clear(run);modelService.detachAll();}
        var out=new LinkedHashMap<String,Object>(count(run));out.put("removed",removed);out.put("recorderUnchanged",unchanged);return out;
    }
}
