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

    private static final String ADDED = "ReclazzAddedTypeProbe";
    private static final String ADDED_MODEL = "com.onurkat.reclazztest.model.ReclazzAddedTypeProbeModel";
    private static final String ADDED_JALO = "com.onurkat.reclazztest.jalo.ReclazzAddedTypeProbe";
    private boolean loadable(String name) {
        try { Class.forName(name, false, Registry.getApplicationContext().getClassLoader()); return true; }
        catch (ClassNotFoundException absent) { return false; }
    }
    @GetMapping("/new-type/facts")
    public Map<String, Object> newTypeFacts() throws Exception {
        var result = new LinkedHashMap<String, Object>();
        var types = TypeManager.getInstance().getAllComposedTypes();
        result.put("tenant", Registry.getCurrentTenant().getTenantID());
        result.put("jvmStart", java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime());
        result.put("collisions", types.stream().filter(t -> !ADDED.equals(t.getCode()) &&
                (t.getItemTypeCode() == 31992 || "ReclazzAddedTypeProbes".equalsIgnoreCase(t.getTable()))).count());
        boolean exists = types.stream().anyMatch(t -> ADDED.equals(t.getCode()));
        result.put("present", exists);
        // SAP caches failed class loads. Before generation, inspect resources without
        // attempting Class.forName: a preflight must not poison the platform loader.
        var loader = Registry.getApplicationContext().getClassLoader();
        result.put("modelResourcePresent", loader.getResource(ADDED_MODEL.replace('.', '/') + ".class") != null);
        result.put("jaloResourcePresent", loader.getResource(ADDED_JALO.replace('.', '/') + ".class") != null);
        if (exists) {
            result.put("modelLoaded", loadable(ADDED_MODEL)); result.put("jaloLoaded", loadable(ADDED_JALO));
        }
        var tables = new ArrayList<String>();
        try (var connection = Registry.getCurrentTenant().getDataSource().getConnection();
             var found = connection.getMetaData().getTables(null, null, null, new String[]{"TABLE"})) {
            while (found.next()) {
                String table = found.getString("TABLE_NAME");
                if (table.toUpperCase(Locale.ROOT).contains("RECLAZZADDEDTYPEPROBES")) tables.add(table);
            }
        }
        result.put("matchingTables", tables);
        if (exists) {
            var type = TypeManager.getInstance().getComposedType(ADDED);
            ItemModel model = modelService.create(ADDED);
            result.put("typeCode", type.getItemTypeCode()); result.put("table", type.getTable());
            result.put("jaloClass", type.getJaloClass().getName());
            result.put("modelClass", model.getClass().getName());
            result.put("modelLoader", model.getClass().getClassLoader().getClass().getName());
            modelService.detach(model);
        }
        return result;
    }
    private String newNonce(String nonce) {
        if (!nonce.matches("reclazz-c33a-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Invalid new-type nonce");
        return nonce;
    }
    private List<ItemModel> newRows(String nonce) {
        return searchService.<ItemModel>search("SELECT {pk} FROM {" + ADDED + "} WHERE {nonce}=?nonce",
                Map.of("nonce", newNonce(nonce))).getResult();
    }
    private Map<String, Object> newResult(ItemModel previous, ItemModel fresh, boolean database) throws Exception {
        var result = newTypeFacts();
        if (!ADDED_MODEL.equals(fresh.getClass().getName())) throw new IllegalStateException("Generated model required");
        result.put("pk", fresh.getPk().toString()); result.put("differentInstance", previous != fresh);
        result.put("nonce", fresh.getClass().getMethod("getNonce").invoke(fresh));
        result.put("value", fresh.getClass().getMethod("getValue").invoke(fresh));
        if (database) {
            String table = TypeManager.getInstance().getComposedType(ADDED).getTable();
            if (!"ReclazzAddedTypeProbes".equalsIgnoreCase(table) || !table.matches("[a-zA-Z0-9_]+"))
                throw new IllegalStateException("Owned deployment table required");
            try (var connection = Registry.getCurrentTenant().getDataSource().getConnection();
                 var statement = connection.prepareStatement("SELECT p_value FROM " + table + " WHERE PK=?")) {
                statement.setLong(1, Long.parseLong(fresh.getPk().toString()));
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("Persisted row missing");
                    result.put("databaseValue", rows.getString(1));
                    if (rows.next()) throw new IllegalStateException("Duplicate PK");
                }
            }
        }
        modelService.detach(fresh); return result;
    }
    @PostMapping("/new-type/roundtrip")
    public Map<String, Object> newRoundtrip(@RequestParam("nonce") String nonce) throws Exception {
        if (!newRows(nonce).isEmpty()) throw new IllegalStateException("Nonce already persisted");
        ItemModel original = modelService.create(ADDED);
        original.getClass().getMethod("setNonce", String.class).invoke(original, nonce);
        original.getClass().getMethod("setValue", String.class).invoke(original, "new-type-v1");
        modelService.save(original); modelService.detach(original);
        var found = newRows(nonce);
        if (found.size() != 1) throw new IllegalStateException("Owned row missing");
        return newResult(original, found.get(0), false);
    }
    @GetMapping("/new-type/roundtrip")
    public Map<String, Object> readNewType(@RequestParam("nonce") String nonce, @RequestParam("pk") String pk) throws Exception {
        var found = newRows(nonce);
        if (found.size() != 1 || !found.get(0).getPk().equals(PK.parse(pk))) throw new IllegalStateException("Owned persisted PK mismatch");
        ItemModel previous = found.get(0); modelService.detach(previous);
        var fresh = newRows(nonce);
        if (fresh.size() != 1 || !fresh.get(0).getPk().equals(PK.parse(pk))) throw new IllegalStateException("Owned persisted PK changed");
        return newResult(previous, fresh.get(0), true);
    }
}
