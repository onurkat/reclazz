package com.onurkat.reclazztest.controllers;

import de.hybris.platform.core.model.ItemModel;
import de.hybris.platform.processengine.BusinessProcessService;
import de.hybris.platform.processengine.model.BusinessProcessModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Nonce-owned native process operations; never invokes an action directly. */
@RestController
@RequestMapping("/test/batch/process-action")
public class ProcessActionProbeController {
    private static final String DEFINITION = "reclazz-process-action";
    @Autowired private BusinessProcessService businessProcessService;
    @Autowired private ModelService modelService;
    @Autowired private FlexibleSearchService searchService;

    private String code(String nonce) {
        if (!nonce.matches("[a-z0-9-]{1,60}")) throw new IllegalArgumentException("Invalid owned nonce");
        return "reclazz-action-" + nonce;
    }
    private BusinessProcessModel owned(String nonce) {
        BusinessProcessModel p = businessProcessService.getProcess(code(nonce));
        modelService.refresh(p);
        if (!DEFINITION.equals(p.getProcessDefinitionName())) throw new IllegalStateException("Owned definition mismatch");
        return p;
    }
    private long remaining(String code) {
        var q = new FlexibleSearchQuery("SELECT COUNT({pk}) FROM {BusinessProcess} WHERE {code}=?code");
        q.addQueryParameter("code", code); q.setDisableSearchRestrictions(true); q.setResultClassList(List.of(Long.class));
        return ((Number) searchService.search(q).getResult().get(0)).longValue();
    }
    private boolean terminal(BusinessProcessModel p) {
        return Set.of("SUCCEEDED", "FAILED", "ERROR").contains(String.valueOf(p.getState()));
    }
    @PostMapping("/start")
    public Map<String, Object> start(@RequestParam("nonce") String nonce) {
        if (remaining(code(nonce)) != 0) throw new IllegalStateException("Owned nonce already exists");
        BusinessProcessModel p = businessProcessService.createProcess(code(nonce), DEFINITION);
        businessProcessService.startProcess(p);
        return read(nonce);
    }
    @GetMapping("/read")
    public Map<String, Object> read(@RequestParam("nonce") String nonce) {
        var p = owned(nonce);
        var rows = p.getContextParameters().stream().filter(v -> "reclazzActionResult".equals(v.getName())).toList();
        if (rows.size() > 1) throw new IllegalStateException("Duplicate owned action result");
        return Map.of("nonce", nonce, "code", p.getCode(), "pk", p.getPk().toString(),
            "version", String.valueOf(p.getProcessDefinitionVersion()), "state", String.valueOf(p.getState()),
            "actions", p.getCurrentTasks().stream().map(t -> t.getAction()).toList(),
            "result", rows.isEmpty() ? "" : String.valueOf(rows.get(0).getValue()));
    }
    @PostMapping("/release")
    public Map<String, Object> release(@RequestParam("nonce") String nonce) {
        businessProcessService.triggerEvent(owned(nonce), "reclazzActionContinue");
        return Map.of("nonce", nonce, "released", true);
    }
    @PostMapping("/cleanup")
    public Map<String, Object> cleanup(@RequestParam("nonce") String nonce) throws InterruptedException {
        if (remaining(code(nonce)) == 0) return Map.of("nonce", nonce, "ownedRemaining", 0L);
        var p = owned(nonce);
        var children = new ArrayList<ItemModel>();
        children.addAll(p.getCurrentTasks()); children.addAll(p.getTaskLogs()); children.addAll(p.getContextParameters());
        if (!terminal(p)) {
            businessProcessService.restartProcess(p, "cleanup");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            do {
                modelService.refresh(p);
                if (terminal(p)) break;
                if (System.nanoTime() >= deadline) throw new IllegalStateException("Owned cleanup process not terminal");
                Thread.sleep(20);
            } while (true);
        }
        children.addAll(p.getCurrentTasks()); children.addAll(p.getTaskLogs()); children.addAll(p.getContextParameters());
        // A native restart may already have removed its old task; remove only surviving rows.
        if (!children.isEmpty()) {
            var q = new FlexibleSearchQuery("SELECT {pk} FROM {Item} WHERE {pk} IN (?owned)");
            q.addQueryParameter("owned", children.stream().map(ItemModel::getPk).distinct().toList());
            q.setDisableSearchRestrictions(true);
            modelService.removeAll(searchService.search(q).getResult());
        }
        modelService.remove(p);
        long residue = remaining(code(nonce));
        if (!children.isEmpty()) {
            var q = new FlexibleSearchQuery("SELECT COUNT({pk}) FROM {Item} WHERE {pk} IN (?owned)");
            q.addQueryParameter("owned", children.stream().map(ItemModel::getPk).distinct().toList());
            q.setDisableSearchRestrictions(true); q.setResultClassList(List.of(Long.class));
            residue += ((Number) searchService.search(q).getResult().get(0)).longValue();
        }
        return Map.of("nonce", nonce, "ownedRemaining", residue);
    }
}
