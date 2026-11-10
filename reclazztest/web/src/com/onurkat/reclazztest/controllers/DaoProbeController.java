package com.onurkat.reclazztest.controllers;

import com.onurkat.reclazztest.batch.ProbeQueryDao;
import de.hybris.platform.core.Registry;
import de.hybris.platform.core.model.ItemModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.tx.Transaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Only validated UUID-owned rows in the existing synthetic type may be mutated. */
@RestController
@RequestMapping("/test/batch/dao")
public class DaoProbeController {
    private static final String TYPE = "ReclazzSchemaProbe";
    @Autowired private ModelService modelService;
    @Autowired private FlexibleSearchService searchService;

    private String owned(String run) {
        if (!run.matches("^reclazz-dao-[0-9a-f-]{36}$")) throw new IllegalArgumentException("Invalid owned run");
        return run;
    }
    private List<ItemModel> rows(String run) {
        var query = new FlexibleSearchQuery("SELECT {pk} FROM {" + TYPE + "} WHERE {nonce} LIKE ?p ORDER BY {nonce}");
        query.addQueryParameter("p", owned(run) + "-%"); query.setDisableSearchRestrictions(true);
        return searchService.<ItemModel>search(query).getResult();
    }
    @GetMapping("/count")
    public Map<String, Object> count(@RequestParam("run") String run) {
        List<ItemModel> found = rows(run);
        var query = new FlexibleSearchQuery("SELECT COUNT({pk}) FROM {" + TYPE + "}");
        query.setDisableSearchRestrictions(true); query.setResultClassList(List.of(Long.class));
        return Map.of("run", run, "ownedRemaining", found.size(),
                "nonces", found.stream().map(row -> row.<String>getProperty("nonce")).toList(),
                "globalRemaining", ((Number) searchService.search(query).getResult().get(0)).longValue());
    }
    @PostMapping("/seed")
    public synchronized Map<String, Object> seed(@RequestParam("run") String run) {
        owned(run);
        Transaction tx = Transaction.current();
        if (tx.isRunning()) throw new IllegalStateException("Seed requires its own transaction");
        tx.begin();
        try {
            if (!rows(run).isEmpty()) throw new IllegalStateException("Run already persisted");
            for (String suffix : List.of("-a-1", "-a-2", "-b-1", "-c-1")) {
                ItemModel row = modelService.create(TYPE);
                modelService.setAttributeValue(row, "nonce", run + suffix); modelService.save(row);
            }
            tx.commit();
        } finally {
            try { if (tx.isRunning()) tx.rollback(); }
            finally { modelService.detachAll(); }
        }
        return count(run);
    }
    @GetMapping("/find")
    public Map<String, Object> find(@RequestParam("run") String run) {
        owned(run);
        ProbeQueryDao dao = Registry.getApplicationContext().getBean("reclazzProbeQueryDao", ProbeQueryDao.class);
        return Map.of("run", run, "nonces", dao.find(run), "beanIdentity", System.identityHashCode(dao));
    }
    @PostMapping("/cleanup")
    public synchronized Map<String, Object> cleanup(@RequestParam("run") String run) {
        List<ItemModel> found = rows(run);
        modelService.removeAll(found); modelService.detachAll();
        var result = new LinkedHashMap<>(count(run)); result.put("removed", found.size());
        return result;
    }
}
