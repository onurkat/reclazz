/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.regex.*;

/** Exact producer-message observations. Never a request receipt or native behavior proof. */
final class SapOperationObservation {
    private static final String LEAF = "[A-Za-z0-9_$][A-Za-z0-9_$.-]{0,254}";
    private static final String FILE = "(?<resourceBasename>" + LEAF + "\\.(?:impex|xml))";
    private static final String EXT = "[A-Za-z0-9_][A-Za-z0-9_-]{0,127}";
    private static final String NUMBER = "(?:0|[1-9][0-9]{0,8})";
    private static final String TAG = "(?<targetTag>owner[1-9][0-9]{0,8}/config[1-9][0-9]{0,8}/type[1-9][0-9]{0,8})";
    private static final String ANY_TAG = "(?<targetTag>discovery|owner[1-9][0-9]{0,8}(?:/config[1-9][0-9]{0,8}/type[1-9][0-9]{0,8})?)";
    private static final String PRIVATE_DETAIL = ".{1,2048}";
    private static final String IMPORT_PRIVACY = ". The lines themselves are not printed here, because an ImpEx carries data about people; HAC shows them under ImpEx Import.";
    private static final String SOLR_END = "; normal platform listeners and bookkeeping apply; field freshness is not verified.";
    private record Rule(String level, Pattern pattern, String operation, String outcome, String reason, List<String> fields) { }
    private static final List<Rule> RULES = rules();

    private static List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        add(rules,"OK","ImpEx imported: " + FILE,"impex_import","applied","imported","resourceBasename");
        add(rules,"WARN","ImpEx not imported: " + FILE + q(" has a REMOVE or unresolved macro header at line ")
                + "(?<headerLine>" + NUMBER + ")" + q(". Auto-import will not delete data. Import it from HAC, or pass impexAllowRemove=true to the agent if you mean it."),
                "impex_import","refused","remove_or_unresolved_header","resourceBasename","headerLine");
        add(rules,"WARN","ImpEx " + FILE + q(" contains a REMOVE or unresolved macro header (line ")
                + "(?<headerLine>" + NUMBER + ")" + q("); importing because impexAllowRemove is set."),
                "impex_import","warning","remove_allowed","resourceBasename","headerLine");
        add(rules,"ERROR",q("ImpEx import had errors: some lines were not resolved in ") + FILE + q(IMPORT_PRIVACY),
                "impex_import","error","import_errors","resourceBasename");
        add(rules,"ERROR",q("ImpEx import had errors: ") + "(?<unresolvedLines>" + NUMBER + ") line(?<plural>s?)"
                + q(" could not be resolved in ") + FILE + q(IMPORT_PRIVACY),"impex_import","error","import_errors","resourceBasename","unresolvedLines");
        add(rules,"ERROR",q("Failed to import ImpEx ") + FILE + q(": ") + PRIVATE_DETAIL,"impex_import","error","import_exception","resourceBasename");
        add(rules,"ERROR",q("ImpEx not imported: ") + FILE + q(" is not UTF-8, and auto-import reads UTF-8 because it hands the content to the platform re-encoded. Save the file as UTF-8, or import it from HAC, which reads the encoding you choose there."),
                "impex_import","error","non_utf8","resourceBasename");
        add(rules,"ERROR",q("ImpEx file too large (") + NUMBER + q(" MB, max 50 MB): ") + FILE,"impex_import","error","file_too_large","resourceBasename");
        add(rules,"INFO",q("ImpEx file is empty, skipping: ") + FILE,"impex_import","skipped","empty_file","resourceBasename");
        add(rules,"WARN",q("ImpEx file not found: ") + FILE,"impex_import","skipped","missing_file","resourceBasename");
        exact(rules,"WARN","ImportService not reachable in any live Spring context, ImpEx import skipped.","impex_import","unavailable","import_service_unavailable");
        exact(rules,"WARN","ImpEx not imported: platform tenant unavailable; save again to retry.","impex_import","unavailable","tenant_unavailable");
        exact(rules,"WARN","Hybris import classes not available. ImpEx auto-import requires running server.","impex_import","unavailable","import_classes_unavailable");

        for (String subject : List.of("DTOs","model classes","DTOs + model classes")) {
            add(rules,"OK",q("Codegen regen+compile done for ") + EXT + q(", " + subject + " ready (") + NUMBER
                    + q("s). Structural reloader is applying new bytecode now."),"codegen","generated",
                    switch(subject) { case "DTOs" -> "regenerated_dto"; case "model classes" -> "regenerated_model"; default -> "regenerated_dto_and_model"; });
        }
        add(rules,"OK",q("Codegen regen+compile done for ") + EXT + q(" (") + NUMBER + q("s)"),"codegen","generated","regenerated_unspecified");
        add(rules,"ERROR",q("Codegen regen FAILED for ") + EXT + q(", ant exited non-zero. Run `ant build` manually for full output."),"codegen","error","ant_failed");
        exact(rules,"ERROR","Codegen: `ant build` timed out after 5 minutes and was stopped","codegen","error","ant_timeout");
        add(rules,"ERROR",q("Codegen: ant exit code ") + "-?" + NUMBER,"codegen","error","ant_exit");
        add(rules,"ERROR",q("Codegen: failed to invoke ant: ") + PRIVATE_DETAIL,"codegen","error","ant_invocation_failed");

        exact(rules,"OK","items.xml changed, ran Update Running System (schema only); the new attributes and enum values are live. No restart, no HAC click.","items_schema","applied","schema_updated");
        exact(rules,"WARN","items.xml changed, new attributes require a DB schema update + type system refresh. Open HAC → Platform → Update Running System (default URL: https://localhost:9002/hac/platform/update) to apply.","items_schema","manual_required","manual_schema_required");
        exact(rules,"WARN","New attributes on existing model instances will return null until HAC updatesystem runs and ModelService re-fetches from the DB.","items_schema","manual_required","model_refetch_required");
        exact(rules,"WARN","The same applies to new enumtype values: the JVM side reloads, but the platform can persist a value only after Update Running System (or an ImpEx) has created its EnumerationValue item.","items_schema","manual_required","enum_persistence_required");
        exact(rules,"WARN","Automatic Update Running System could not bind a tenant; apply it in HAC instead.","items_schema","unavailable","schema_tenant_unavailable");
        exact(rules,"WARN","Update Running System is already in progress; skipped the automatic schema update.","items_schema","unavailable","schema_update_busy");
        exact(rules,"WARN","Automatic Update Running System did not report success; apply it in HAC if the new attribute is missing.","items_schema","error","schema_success_unconfirmed");
        add(rules,"WARN",q("Automatic Update Running System could not run (") + PRIVATE_DETAIL + q("); apply it in HAC instead."),"items_schema","unavailable","automatic_update_unavailable");

        add(rules,"OK",q("Backoffice configuration reset as with F4 (") + "(?<services>" + NUMBER + ") service(?<plural>s?)"
                + q(") for ") + FILE + q(": open a new view to see it. Configuration changed in the UI was reset too."),
                "backoffice_reset","applied","f4_reset","resourceBasename","services");
        add(rules,"WARN",FILE + q(" saved, not applied; press F4 and reset cockpit configuration in Backoffice (backofficeConfigReset=false)."),
                "backoffice_reset","disabled","config_reset_disabled","resourceBasename");
        add(rules,"WARN",FILE + q(": Backoffice configuration reset failed in ") + ".{1,256}" + q(": ") + PRIVATE_DETAIL,
                "backoffice_reset","error","config_reset_failed","resourceBasename");

        for (boolean unverified : new boolean[]{false,true}) {
            String state = unverified ? "FINISHED_UNVERIFIED" : "FINISHED";
            String suffix = unverified ? "; native provider errors may have been skipped." : "; finished without native errors.";
            add(rules,"INFO",q("SAP Solr ") + TAG + q(": " + state + " ") + "(?:whole-type|partial properties=(?<properties>" + NUMBER + "))"
                    + q(" documents=") + "(?<documents>" + NUMBER + ")" + q(" durationMs=") + "(?<durationMs>" + NUMBER + ")"
                    + q(suffix + " nonPlatformListenerMetadata=") + "(?<listenerMetadata>unknown|" + NUMBER + ")" + q(SOLR_END),
                    "solr_reindex",unverified ? "finished_unverified" : "finished",unverified ? "native_errors_may_be_skipped" : "native_finished",
                    "targetTag","properties","documents","durationMs","listenerMetadata");
        }
        add(rules,"INFO",q("SAP Solr ") + TAG + q(": no stored documents; no indexing call."),"solr_reindex","skipped","no_stored_documents","targetTag");
        String reasons = "active-index-switched|ambiguous-config-name|ambiguous-property-name|ambiguous-tenant|ambiguous-type-identifier|config-replaced|configuration-changed-since-discovery|context-closed|cursor-cycle|custom-indexer-strategy|custom-listeners-factory|custom-scoped-provider|custom-search-provider|custom-selector|distributed-indexing|document-limit|document-limit-or-index-changed|factory-method-provider|factory-or-proxy-provider|incomplete-pk-snapshot|infrastructure-not-initialized|infrastructure-unavailable|invalid-stored-pk|missing-active-index|missing-cursor|missing-endpoints|missing-native-object|native-method-inaccessible|native-method-unavailable|native-operation-failed|non-loopback-endpoint|owning-context-unavailable|owning-tenant-unavailable|pagination-limit|partial-query-results|physical-index-changed|provider-mapping-changed|provider-unused|query-status|shared-physical-index|type-replaced|unknown-unique-key|unresolved-provider|unsupported-native-collection|unsupported-native-map|unsupported-server-mode|untyped-or-proxy-provider";
        add(rules,"INFO",q("SAP Solr ") + ANY_TAG + q(": FAILED (") + "(?:" + reasons + ")" + q("). Reclazz did not confirm a reindex of stored documents; validate and explicitly reindex affected documents if needed."),
                "solr_reindex","failed","native_failed","targetTag");
        return List.copyOf(rules);
    }

    static JsonObject describe(String level, String message) {
        if (message.length() > 16384 || message.codePoints().anyMatch(Character::isISOControl)) return null;
        for (Rule rule : RULES) {
            if (!rule.level().equals(level)) continue;
            Matcher match = rule.pattern().matcher(message);
            if (!match.matches()) continue;
            JsonObject result = new JsonObject();
            result.addProperty("operation",rule.operation()); result.addProperty("reportedOutcome",rule.outcome());
            result.addProperty("reasonCode",rule.reason()); result.addProperty("evidenceScope","agent_message");
            result.addProperty("applicationBehaviorConfirmed",false);
            try {
                for (String field : rule.fields()) {
                    String value = match.group(field);
                    if (value == null) continue;
                    if (field.equals("resourceBasename")) {
                        if (value.length() > 255 || (rule.operation().equals("impex_import") != value.endsWith(".impex"))) return null;
                        result.addProperty(field,value);
                    } else if (field.equals("targetTag")) result.addProperty(field,value);
                    else if (field.equals("listenerMetadata") && value.equals("unknown")) result.addProperty(field,value);
                    else {
                        long count = Long.parseLong(value);
                        if (count > Integer.MAX_VALUE || (Set.of("services","headerLine","properties","documents").contains(field) && count == 0)) return null;
                        if (Set.of("services","unresolvedLines").contains(field)
                                && !match.group("plural").equals(count == 1 ? "" : "s")) return null;
                        if (field.equals("documents") && count > 10000) return null;
                        result.addProperty(field,count);
                    }
                }
            } catch (NumberFormatException invalid) { return null; }
            if (rule.operation().equals("solr_reindex")) result.addProperty("fieldFreshnessConfirmed",false);
            if (rule.reason().equals("f4_reset")) result.addProperty("uiConfigurationReset",true);
            if (rule.operation().equals("codegen") && rule.outcome().equals("generated"))
                result.addProperty("generationKind",rule.reason().substring("regenerated_".length()));
            return result;
        }
        return null;
    }
    private static String q(String literal) { return Pattern.quote(literal); }
    private static void exact(List<Rule> rules, String level, String message, String operation, String outcome, String reason) {
        add(rules,level,q(message),operation,outcome,reason);
    }
    private static void add(List<Rule> rules, String level, String pattern, String operation, String outcome, String reason, String... fields) {
        rules.add(new Rule(level,Pattern.compile(pattern),operation,outcome,reason,List.of(fields)));
    }
    private SapOperationObservation() { }
}
