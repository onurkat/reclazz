package com.onurkat.reclazztest.controllers;

import com.onurkat.reclazztest.batch.DynamicLabelHandler;
import de.hybris.platform.core.Registry;
import de.hybris.platform.core.model.ReclazzSchemaProbeModel;
import de.hybris.platform.jalo.type.TypeManager;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.tx.Transaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Serialized master-tenant probes; no handler/provider replacement shortcuts. */
@RestController
@RequestMapping("/test/batch/dynamic")
public class DynamicProbeController {
    private static final String TYPE = "ReclazzSchemaProbe";
    private static final ConcurrentHashMap<String, ReclazzSchemaProbeModel> HELD = new ConcurrentHashMap<>();
    @Autowired private ModelService modelService;
    @Autowired private FlexibleSearchService searchService;

    private String owned(String run) {
        if (!"master".equals(Registry.getCurrentTenant().getTenantID()) || !run.matches("^reclazz-dyn-[0-9a-f-]{36}$"))
            throw new IllegalArgumentException("Invalid owned master run");
        return run;
    }
    private List<ReclazzSchemaProbeModel> rows(String run) {
        var query = new FlexibleSearchQuery("SELECT {pk} FROM {" + TYPE + "} WHERE {nonce}=?nonce");
        query.addQueryParameter("nonce", owned(run) + "-row"); query.setDisableSearchRestrictions(true);
        return searchService.<ReclazzSchemaProbeModel>search(query).getResult();
    }
    private ReclazzSchemaProbeModel fresh(String run) {
        owned(run);
        var held = HELD.get(run);
        if (held == null) throw new IllegalStateException("No warmed retained model");
        // Request context only: the held object's original provider stays intact.
        modelService.detachAll();
        var found = rows(run);
        if (found.size() != 1 || found.get(0) == held || !found.get(0).getPk().equals(held.getPk()))
            throw new IllegalStateException("Fresh native model identity/PK mismatch");
        return found.get(0);
    }
    @GetMapping("/count")
    public synchronized Map<String, Object> count(@RequestParam("run") String run) {
        var found = rows(run);
        var query = new FlexibleSearchQuery("SELECT COUNT({pk}) FROM {" + TYPE + "}");
        query.setDisableSearchRestrictions(true); query.setResultClassList(List.of(Long.class));
        return Map.of("run", run, "ownedRemaining", found.size(),
                "globalRemaining", ((Number)searchService.search(query).getResult().get(0)).longValue(),
                "nonces", found.stream().map(ReclazzSchemaProbeModel::getNonce).toList(),
                "heldRemaining", HELD.size(), "mapRemaining", DynamicLabelHandler.entries());
    }
    @PostMapping("/seed")
    public synchronized Map<String, Object> seed(@RequestParam("run") String run) {
        owned(run);
        Transaction tx = Transaction.current();
        if (tx.isRunning()) throw new IllegalStateException("Seed requires its own transaction");
        tx.begin();
        try {
            if (!rows(run).isEmpty() || HELD.containsKey(run)) throw new IllegalStateException("Run already persisted");
            ReclazzSchemaProbeModel row = modelService.create(TYPE);
            row.setNonce(run + "-row"); modelService.save(row); tx.commit();
            HELD.put(run, row);
            if (!("dyn-v1:" + run + "-row").equals(row.getDynamicLabel()))
                throw new IllegalStateException("Native dynamic warm-up is not baseline v1");
        } finally { if (tx.isRunning()) tx.rollback(); modelService.detachAll(); }
        return count(run);
    }
    @GetMapping("/read")
    public synchronized Map<String, Object> read(@RequestParam("run") String run) throws Exception {
        var fresh = fresh(run); var held = HELD.get(run);
        try {
            var descriptor = TypeManager.getInstance().getComposedType(TYPE).getAttributeDescriptor("dynamicLabel");
            var result = new LinkedHashMap<String, Object>();
            result.put("run", run); result.put("held", held.getDynamicLabel()); result.put("fresh", fresh.getDynamicLabel());
            result.put("heldIdentity", System.identityHashCode(held)); result.put("freshIdentity", System.identityHashCode(fresh));
            result.put("differentInstance", held != fresh); result.put("pk", held.getPk().toString()); result.put("freshPk", fresh.getPk().toString());
            result.put("modelClass", fresh.getClass().getName()); result.put("handler", descriptor.getAttributeHandler());
            result.put("nativeDynamic", !descriptor.isProperty() && "reclazzDynamicLabelHandler".equals(descriptor.getAttributeHandler()));
            return result;
        } finally { modelService.detach(fresh); }
    }
    @PostMapping("/set")
    public synchronized Map<String, Object> set(@RequestParam("run") String run, @RequestParam("token") String token) {
        owned(run);
        if (!token.matches(Pattern.quote(run) + "-t[123]")) throw new IllegalArgumentException("Invalid owned step token");
        var fresh = fresh(run);
        try {
            fresh.setDynamicLabel(token);
            return Map.of("run", run, "token", token, "pk", fresh.getPk().toString(),
                    "value", Objects.requireNonNull(DynamicLabelHandler.observed(fresh.getPk().toString())));
        } finally { modelService.detach(fresh); }
    }
    @PostMapping("/cleanup")
    public synchronized Map<String, Object> cleanup(@RequestParam("run") String run) {
        owned(run); var found = rows(run); var held = HELD.remove(run);
        try { modelService.removeAll(found); }
        finally {
            if (held != null) DynamicLabelHandler.clear(held.getPk().toString());
            for (var row : found) DynamicLabelHandler.clear(row.getPk().toString());
            modelService.detachAll();
        }
        var result = new LinkedHashMap<String, Object>(count(run)); result.put("removed", found.size()); return result;
    }
}
