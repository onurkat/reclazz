package com.onurkat.reclazztest.controllers;

import com.onurkat.reclazztest.batch.ProbePrepareInterceptor;
import com.onurkat.reclazztest.batch.ProbePrepareState;
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

/** Real native saves; pure reads never execute the callback or repeat a save. */
@RestController
@RequestMapping("/test/batch/prepare")
public class PrepareProbeController {
    private static final String TYPE = "ReclazzSchemaProbe";
    @Autowired private ModelService modelService;
    @Autowired private FlexibleSearchService searchService;

    private String owned(String run) {
        if (!"master".equals(Registry.getCurrentTenant().getTenantID()) || !run.matches("^reclazz-prep-[0-9a-f-]{36}$"))
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
    private ReclazzSchemaProbeModel create(String nonce) {
        ReclazzSchemaProbeModel row = modelService.create(TYPE);
        row.setNonce(nonce); modelService.save(row); return row;
    }
    private Map<String, Object> registry() throws Exception {
        var context = Registry.getApplicationContext();
        var registry = context.getBean("interceptorRegistry", DefaultInterceptorRegistry.class);
        Object service = modelService;
        if (service instanceof org.springframework.aop.framework.Advised)
            service = ((org.springframework.aop.framework.Advised) service).getTargetSource().getTarget();
        Object actual = service.getClass().getMethod("getInterceptorRegistry").invoke(service);
        var target = context.getBean("reclazzProbePrepareInterceptor", ProbePrepareInterceptor.class);
        var mapping = context.getBean("reclazzProbePrepareMapping", InterceptorMapping.class);
        long count = registry.getPrepareInterceptors(TYPE).stream().filter(value -> value == target).count();
        return Map.of("sameRegistry", actual == registry, "registryClass", registry.getClass().getName(),
                "registryIdentity", System.identityHashCode(registry), "targetCount", count,
                "mappingMatches", mapping.getInterceptor() == target && TYPE.equals(mapping.getTypeCode()),
                "version", target.version());
    }
    @GetMapping("/ready")
    public synchronized Map<String, Object> ready(@RequestParam("run") String run) throws Exception {
        owned(run); var result = new LinkedHashMap<String, Object>(registry()); result.put("run", run); return result;
    }
    @GetMapping("/count")
    public synchronized Map<String, Object> count(@RequestParam("run") String run) {
        var found = rows(run);
        return Map.of("run", run, "ownedRemaining", found.size(), "globalRemaining", globalCount(),
                "recorderRemaining", ProbePrepareState.entries(), "steps", ProbePrepareState.snapshot(run));
    }
    @PostMapping("/seed")
    public synchronized Map<String, Object> seed(@RequestParam("run") String run) {
        owned(run); if (globalCount() != 0 || ProbePrepareState.entries() != 0) throw new IllegalStateException("Dirty native baseline");
        var tx = begin();
        try { create(run + "-seed"); create(run + "-control"); tx.commit(); }
        finally { if (tx.isRunning()) tx.rollback(); modelService.detachAll(); }
        return count(run);
    }
    @PostMapping("/save")
    public synchronized Map<String, Object> save(@RequestParam("run") String run, @RequestParam("step") int step,
                                               @RequestParam("pk") String pk) {
        owned(run); if (step < 1 || step > 3 || ProbePrepareState.snapshot(run).containsKey(Integer.toString(step)))
            throw new IllegalArgumentException("Invalid or repeated step");
        var found = rows(run);
        var row = found.stream().filter(value -> value.getPk().equals(PK.parse(pk)) && !value.getNonce().endsWith("-control"))
                .findFirst().orElseThrow(() -> new IllegalStateException("Target PK not owned"));
        var tx = begin();
        try { row.setNonce(run + "-s" + step + "-input"); modelService.save(row); tx.commit(); }
        finally { if (tx.isRunning()) tx.rollback(); modelService.detachAll(); }
        return Map.of("run", run, "step", step, "pk", pk);
    }
    @GetMapping("/read")
    public synchronized Map<String, Object> read(@RequestParam("run") String run) throws Exception {
        modelService.detachAll(); var found = rows(run);
        var result = new LinkedHashMap<String, Object>(ready(run));
        result.put("rows", found.stream().map(row -> Map.of("pk", row.getPk().toString(), "nonce", row.getNonce())).toList());
        result.put("steps", ProbePrepareState.snapshot(run)); result.put("globalRemaining", globalCount());
        return result;
    }
    @PostMapping("/guard")
    public synchronized Map<String, Object> guard(@RequestParam("run") String run) {
        owned(run); if (globalCount() != 0 || ProbePrepareState.entries() != 0) throw new IllegalStateException("Dirty guard baseline");
        String id = run.substring("reclazz-prep-".length());
        var nonces = List.of("reclazz-dao-" + id + "-a-1", "reclazz-dyn-" + id + "-row", "reclazz-schema-" + id);
        var pks = new ArrayList<PK>(); var tx = begin(); boolean committed = false;
        try {
            for (String nonce : nonces) pks.add(create(nonce).getPk());
            tx.commit(); committed = true; modelService.detachAll();
            var observed = new ArrayList<String>();
            for (PK pk : pks) observed.add(((ReclazzSchemaProbeModel) modelService.get(pk)).getNonce());
            var proof = Map.<String, Object>of("run", run, "nonces", observed, "recorderRemaining", ProbePrepareState.entries());
            if (!observed.equals(nonces) || ProbePrepareState.entries() != 0 || globalCount() != 3)
                throw new IllegalStateException("Prepare mapping altered unrelated rows");
            return proof;
        } finally {
            if (tx.isRunning()) tx.rollback();
            // Only PKs created by this request, never a global delete.
            if (committed) for (PK pk : pks) modelService.remove(pk);
            modelService.detachAll();
            if (globalCount() != 0) throw new IllegalStateException("Guard cleanup left rows");
        }
    }
    @PostMapping("/cleanup")
    public synchronized Map<String, Object> cleanup(@RequestParam("run") String run) {
        var found = rows(run);
        try { modelService.removeAll(found); }
        finally { ProbePrepareState.clear(run); modelService.detachAll(); }
        var result = new LinkedHashMap<String, Object>(count(run)); result.put("removed", found.size()); return result;
    }
}
