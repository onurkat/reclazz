package com.onurkat.reclazztest.controllers;

import de.hybris.platform.core.PK;
import de.hybris.platform.core.Registry;
import de.hybris.platform.core.Tenant;
import de.hybris.platform.core.model.ItemModel;
import de.hybris.platform.jalo.type.TypeManager;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.type.TypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Confined to one synthetic type; the outer driver owns stopped-DB rollback. */
@RestController
@RequestMapping("/test/batch/items")
public class ItemsSchemaController {
    private static final String TYPE = "ReclazzSchemaProbe";
    @Autowired private ModelService modelService;
    @Autowired private TypeService typeService;
    @Autowired private FlexibleSearchService searchService;

    private String owned(String nonce) {
        if (!nonce.matches("reclazz-schema-[a-z0-9-]{1,60}")) throw new IllegalArgumentException("Invalid synthetic nonce");
        return nonce;
    }
    private List<ItemModel> rows(String nonce) {
        return searchService.<ItemModel>search("SELECT {pk} FROM {" + TYPE + "} WHERE {nonce}=?nonce", Map.of("nonce", owned(nonce))).getResult();
    }
    @GetMapping("/facts")
    public Map<String, Object> facts() throws ClassNotFoundException {
        var result = new LinkedHashMap<String, Object>();
        var tenants = new ArrayList<Tenant>(); tenants.add(Registry.getMasterTenant()); tenants.addAll(Registry.getSlaveTenants().values());
        result.put("tenants", tenants.stream().map(t -> Map.of("id", t.getTenantID(), "db", t.getConfig().getParameter("db.url"),
                "prefix", t.getConfig().getParameter("db.tableprefix"))).toList());
        var types = TypeManager.getInstance().getAllComposedTypes();
        result.put("collisions", types.stream().filter(t -> !TYPE.equals(t.getCode()) && (t.getItemTypeCode() == 31991 ||
                "ReclazzSchemaProbes".equalsIgnoreCase(t.getTable()))).count());
        boolean exists = types.stream().anyMatch(t -> TYPE.equals(t.getCode()));
        result.put("typePresent", exists);
        result.put("tenant", Registry.getCurrentTenant().getTenantID());
        if (exists) {
            result.put("modelLoaded", Class.forName("de.hybris.platform.core.model.ReclazzSchemaProbeModel", true, Registry.getApplicationContext().getClassLoader()) != null);
            var nativeType = TypeManager.getInstance().getComposedType(TYPE);
            var type = typeService.getComposedTypeForCode(TYPE);
            result.put("typeCode", nativeType.getItemTypeCode()); result.put("table", nativeType.getTable());
            result.put("batchValuePresent", typeService.hasAttribute(type, "batchValue"));
            result.put("rows", searchService.search("SELECT {pk} FROM {" + TYPE + "}").getResult().size());
            if (typeService.hasAttribute(type, "batchValue")) {
                var descriptor = typeService.getAttributeDescriptor(type, "batchValue");
                result.put("qualifier", descriptor.getQualifier()); result.put("attributeType", descriptor.getAttributeType().getCode());
            }
        }
        return result;
    }
    @GetMapping("/health")
    public Map<String, Integer> health() throws Exception {
        var result = new LinkedHashMap<String, Integer>();
        for (Tenant tenant : List.of(Registry.getMasterTenant(), Registry.getSlaveJunitTenant())) {
            // A fresh thread avoids reusing the HTTP request's master Jalo session in junit.
            var task = new java.util.concurrent.FutureTask<Integer>(() -> Registry.runAsTenant(tenant, () ->
                    Registry.getApplicationContext().getBean("flexibleSearchService", FlexibleSearchService.class)
                            .search("SELECT {pk} FROM {Title}").getResult().size()));
            Thread thread = new Thread(task, "reclazz-items-tenant-health"); thread.setDaemon(true); thread.start();
            result.put(tenant.getTenantID(), task.get(20, java.util.concurrent.TimeUnit.SECONDS));
        }
        return result;
    }
    @PostMapping("/roundtrip")
    public Map<String, Object> roundtrip(@RequestParam("nonce") String nonce) {
        owned(nonce);
        if (!rows(nonce).isEmpty()) throw new IllegalStateException("Nonce already persisted");
        ItemModel original = modelService.create(TYPE);
        modelService.setAttributeValue(original, "nonce", nonce);
        modelService.setAttributeValue(original, "batchValue", "schema-v2");
        modelService.save(original);
        PK pk = original.getPk(); modelService.detach(original);
        ItemModel fetched = modelService.get(pk);
        var result = new LinkedHashMap<String, Object>();
        result.put("nonce", nonce); result.put("differentInstance", original != fetched); result.put("pk", pk.toString());
        result.put("value", modelService.getAttributeValue(fetched, "batchValue")); modelService.detach(fetched);
        return result;
    }
    @GetMapping("/roundtrip")
    public Map<String, Object> read(@RequestParam("nonce") String nonce, @RequestParam("pk") String pk) {
        owned(nonce);
        List<ItemModel> found = rows(nonce);
        if (found.size() != 1 || !found.get(0).getPk().equals(PK.parse(pk))) throw new IllegalStateException("Owned persisted PK mismatch");
        ItemModel previous = found.get(0); modelService.detach(previous);
        ItemModel fresh = modelService.get(PK.parse(pk));
        var query = new FlexibleSearchQuery("SELECT {batchValue} FROM {" + TYPE + "} WHERE {pk}=?pk");
        query.addQueryParameter("pk", fresh.getPk()); query.setResultClassList(List.of(String.class));
        var result = new LinkedHashMap<String, Object>(); result.put("nonce", nonce); result.put("pk", pk);
        result.put("differentInstance", previous != fresh); result.put("value", modelService.getAttributeValue(fresh, "batchValue"));
        result.put("databaseValue", searchService.search(query).getResult().get(0));
        modelService.detach(fresh); return result;
    }
}
