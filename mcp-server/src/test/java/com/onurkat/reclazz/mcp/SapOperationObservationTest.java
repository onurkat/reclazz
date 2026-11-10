/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class SapOperationObservationTest {
    record Sample(String level, String message, String operation, String outcome) { }
    // Synthetic values in the exact premium producer formats; never native execution evidence.
    static final String ITEMS = "items.xml changed, ran Update Running System (schema only); the new attributes and enum values are live. No restart, no HAC click.";
    static final String MANUAL = "items.xml changed, new attributes require a DB schema update + type system refresh. Open HAC → Platform → Update Running System (default URL: https://localhost:9002/hac/platform/update) to apply.";
    static final String REFUSAL = "ImpEx not imported: example.impex has a REMOVE or unresolved macro header at line 1. Auto-import will not delete data. Import it from HAC, or pass impexAllowRemove=true to the agent if you mean it.";
    static final String BACKOFFICE = "Backoffice configuration reset as with F4 (1 service) for example-backoffice-config.xml: open a new view to see it. Configuration changed in the UI was reset too.";
    static String solr(boolean unverified, boolean whole) {
        return "SAP Solr owner1/config2/type3: " + (unverified ? "FINISHED_UNVERIFIED" : "FINISHED")
                + (whole ? " whole-type" : " partial properties=2") + " documents=3 durationMs=4"
                + (unverified ? "; native provider errors may have been skipped." : "; finished without native errors.")
                + " nonPlatformListenerMetadata=unknown; normal platform listeners and bookkeeping apply; field freshness is not verified.";
    }
    static List<Sample> samples() {
        List<Sample> r = new ArrayList<>();
        // ImpexAutoImporter.importFile: lines 97-239.
        r.add(new Sample("OK","ImpEx imported: example.impex","impex_import","applied"));
        r.add(new Sample("WARN",REFUSAL,"impex_import","refused"));
        r.add(new Sample("WARN","ImpEx example.impex contains a REMOVE or unresolved macro header (line 1); importing because impexAllowRemove is set.","impex_import","warning"));
        for (String count : List.of("some lines were not resolved","0 lines could not be resolved","1 line could not be resolved","2 lines could not be resolved"))
            r.add(new Sample("ERROR","ImpEx import had errors: " + count + " in example.impex. The lines themselves are not printed here, because an ImpEx carries data about people; HAC shows them under ImpEx Import.","impex_import","error"));
        r.add(new Sample("ERROR","Failed to import ImpEx example.impex: IllegalStateException: synthetic failure","impex_import","error"));
        r.add(new Sample("ERROR","ImpEx not imported: example.impex is not UTF-8, and auto-import reads UTF-8 because it hands the content to the platform re-encoded. Save the file as UTF-8, or import it from HAC, which reads the encoding you choose there.","impex_import","error"));
        r.add(new Sample("ERROR","ImpEx file too large (51 MB, max 50 MB): example.impex","impex_import","error"));
        r.add(new Sample("INFO","ImpEx file is empty, skipping: example.impex","impex_import","skipped"));
        r.add(new Sample("WARN","ImpEx file not found: example.impex","impex_import","skipped"));
        for (String text : List.of("ImportService not reachable in any live Spring context, ImpEx import skipped.","ImpEx not imported: platform tenant unavailable; save again to retry.","Hybris import classes not available. ImpEx auto-import requires running server."))
            r.add(new Sample("WARN",text,"impex_import","unavailable"));
        // CodegenReloader.reportSuccess/runAnt: lines 217,248,263,390,398,407.
        for (String kind : List.of("DTOs","model classes","DTOs + model classes"))
            r.add(new Sample("OK","Codegen regen+compile done for example, " + kind + " ready (1s). Structural reloader is applying new bytecode now.","codegen","generated"));
        r.add(new Sample("OK","Codegen regen+compile done for example (1s)","codegen","generated"));
        for (String text : List.of("Codegen regen FAILED for example, ant exited non-zero. Run `ant build` manually for full output.","Codegen: `ant build` timed out after 5 minutes and was stopped","Codegen: ant exit code 1","Codegen: ant exit code -1","Codegen: failed to invoke ant: IOException: synthetic failure"))
            r.add(new Sample("ERROR",text,"codegen","error"));
        // CodegenReloader.handleNewPersistence and HacRunningSystemUpdater.updateSchema.
        r.add(new Sample("OK",ITEMS,"items_schema","applied"));
        for (String text : List.of(MANUAL,"New attributes on existing model instances will return null until HAC updatesystem runs and ModelService re-fetches from the DB.","The same applies to new enumtype values: the JVM side reloads, but the platform can persist a value only after Update Running System (or an ImpEx) has created its EnumerationValue item."))
            r.add(new Sample("WARN",text,"items_schema","manual_required"));
        for (String text : List.of("Automatic Update Running System could not bind a tenant; apply it in HAC instead.","Update Running System is already in progress; skipped the automatic schema update.","Automatic Update Running System could not run (IllegalStateException: synthetic failure); apply it in HAC instead."))
            r.add(new Sample("WARN",text,"items_schema","unavailable"));
        r.add(new Sample("WARN","Automatic Update Running System did not report success; apply it in HAC if the new attribute is missing.","items_schema","error"));
        // BackofficeConfigReloader.reload/failed lines 36,81,91.
        r.add(new Sample("OK",BACKOFFICE,"backoffice_reset","applied"));
        r.add(new Sample("OK",BACKOFFICE.replace("1 service","2 services"),"backoffice_reset","applied"));
        r.add(new Sample("WARN","example-backoffice-config.xml saved, not applied; press F4 and reset cockpit configuration in Backoffice (backofficeConfigReset=false).","backoffice_reset","disabled"));
        r.add(new Sample("WARN","example-backoffice-config.xml: Backoffice configuration reset failed in example-context: IllegalStateException: synthetic failure","backoffice_reset","error"));
        // SolrReindexer.execute/refused lines 297,313,430.
        for (boolean unverified : List.of(false,true)) for (boolean whole : List.of(false,true))
            r.add(new Sample("INFO",solr(unverified,whole),"solr_reindex",unverified ? "finished_unverified" : "finished"));
        r.add(new Sample("INFO",solr(false,false).replace("Metadata=unknown","Metadata=2"),"solr_reindex","finished"));
        r.add(new Sample("INFO","SAP Solr owner1/config2/type3: no stored documents; no indexing call.","solr_reindex","skipped"));
        for (String tag : List.of("discovery","owner1","owner1/config2/type3"))
            r.add(new Sample("INFO","SAP Solr " + tag + ": FAILED (native-operation-failed). Reclazz did not confirm a reindex of stored documents; validate and explicitly reindex affected documents if needed.","solr_reindex","failed"));
        return r;
    }
    static JsonObject row(Sample sample) { return EventObservationTest.row(sample.level(),sample.message()); }
    static JsonObject schema(String tool) throws Exception {
        try (var in = new java.io.InputStreamReader(McpServer.class.getResourceAsStream("/tool-output-schemas.json"),StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(in).getAsJsonObject().getAsJsonObject(tool);
        }
    }
    static void export(String label, JsonObject schema, JsonObject data) throws Exception {
        Path dir = Path.of("build/sap-outcome-fixtures"); Files.createDirectories(dir);
        JsonObject fixture = new JsonObject(); fixture.add("schema",schema); fixture.add("data",data);
        Files.writeString(dir.resolve(label + ".json"),fixture.toString());
    }
    @TestFactory Stream<DynamicTest> exactSapProducerPatterns() {
        List<Sample> cases = samples();
        return java.util.stream.IntStream.range(0,cases.size()).mapToObj(index -> DynamicTest.dynamicTest("producer-" + index,() -> {
            Sample sample = cases.get(index); JsonObject row = row(sample);
            assertEquals("sap_operation",row.get("type").getAsString(),sample.message());
            JsonObject sap = row.getAsJsonObject("sap");
            assertEquals(sample.operation(),sap.get("operation").getAsString());
            assertEquals(sample.outcome(),sap.get("reportedOutcome").getAsString());
            assertEquals("agent_message",sap.get("evidenceScope").getAsString());
            assertFalse(sap.get("applicationBehaviorConfirmed").getAsBoolean());
            assertFalse(row.has("ownerClassName")); assertFalse(row.has("fileBasename"));
            if (sample.operation().equals("solr_reindex")) assertFalse(sap.get("fieldFreshnessConfirmed").getAsBoolean());
            export("row-" + index,schema("reclazz_events").getAsJsonObject("properties").getAsJsonObject("events").getAsJsonObject("items"),row);
        }));
    }
    @Test void wrongLevelsEchoesAndFixedSuffixChangesNeverGainTyping() {
        for (Sample sample : samples()) {
            String wrong = sample.level().equals("INFO") ? "OK" : "INFO";
            assertNull(SapOperationObservation.describe(wrong,sample.message()));
            assertNull(SapOperationObservation.describe(sample.level(),"  " + sample.message()));
            assertNull(SapOperationObservation.describe(sample.level(),"echo: " + sample.message()));
            assertNull(SapOperationObservation.describe(sample.level(),sample.message() + "\nforged"));
        }
        for (String text : List.of(ITEMS + " forged",BACKOFFICE + " forged",solr(false,false) + " forged",REFUSAL + " forged"))
            assertEquals("unknown",EventObservationTest.row(text.startsWith("SAP Solr") ? "INFO" : text.startsWith("ImpEx") ? "WARN" : "OK",text).get("type").getAsString());
    }
    @Test void namesCountersAndPluralsFailClosed() {
        for (String name : List.of("../example.impex","/example.impex","x\\example.impex","example.xml","x".repeat(256) + ".impex","example\u0000.impex"))
            assertNull(SapOperationObservation.describe("OK","ImpEx imported: " + name));
        for (String count : List.of("0","-1","01","999999999999999999999"))
            assertNull(SapOperationObservation.describe("WARN",REFUSAL.replace("line 1.","line " + count + ".")));
        for (String plural : List.of("0 services","1 services","2 service","999999999999999999 services"))
            assertNull(SapOperationObservation.describe("OK",BACKOFFICE.replace("1 service",plural)));
        for (String text : List.of(solr(false,false).replace("properties=2","properties=0"),solr(false,false).replace("documents=3","documents=10001"),solr(false,false).replace("owner1/","owner0/"),solr(false,false).replace("durationMs=4","durationMs=99999999999999999"),solr(false,false).replace("Metadata=unknown","Metadata=-1"),solr(true,false).replace("errors may have been skipped.","errors were not skipped.")))
            assertNull(SapOperationObservation.describe("INFO",text));
    }
    @Test void codegenAndSchemaMessagesNeverClaimClassApplyOrUnambiguousAutomaticFailure() {
        for (Sample s : samples()) if (s.operation().equals("codegen")) {
            JsonObject sap = row(s).getAsJsonObject("sap");
            assertFalse(sap.has("ownerClassName")); assertFalse(sap.has("resourceBasename"));
            assertNotEquals("applied",sap.get("reportedOutcome").getAsString());
        }
        assertEquals("manual_required",SapOperationObservation.describe("WARN",MANUAL).get("reportedOutcome").getAsString());
        for (String line : List.of("Regenerating DTOs via platform `ant build` (runs in background, usually 15-30s)","Codegen: pending save queued, re-running ant for example","beans.xml changed: example-beans.xml [example]","Codegen: ant produced no output (suspicious)"))
            assertNull(SapOperationObservation.describe("INFO",line));
    }
    @Test void fullMessageIsParsedBeforeClippingAndPrivateDetailIsNotCopiedIntoFields() {
        String detail = "synthetic-private-context-" + "x".repeat(700);
        JsonObject row = EventObservationTest.row("ERROR","Failed to import ImpEx example.impex: " + detail);
        assertTrue(row.get("messageTruncated").getAsBoolean()); assertEquals(512,row.get("message").getAsString().length());
        assertFalse(row.getAsJsonObject("sap").toString().contains("synthetic-private-context"));
        assertEquals("import_exception",row.getAsJsonObject("sap").get("reasonCode").getAsString());
        assertNull(SapOperationObservation.describe("OK",BACKOFFICE + "x".repeat(700)));
    }
}
