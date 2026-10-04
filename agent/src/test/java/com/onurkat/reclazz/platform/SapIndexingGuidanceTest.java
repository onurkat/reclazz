/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import com.onurkat.reclazz.transform.TransformTestBase;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SapIndexingGuidanceTest extends TransformTestBase {
    @ParameterizedTest
    @ValueSource(strings = {"FieldValueProvider", "ValueResolver", "TypeValueResolver"})
    void inheritedDiamondContractAdvisesOnceWithoutRequiringRestart(String contract) {
        String name = "de.hybris.platform.solrfacetsearch.provider." + contract;
        var bytes = compile(new SourceFile(name,
                        "package de.hybris.platform.solrfacetsearch.provider; public interface " + contract + " {}"),
                new SourceFile("com.example.Provider", """
                        package com.example;
                        interface Left extends %s {}
                        interface Right extends %s {}
                        class Base implements Left, Right {}
                        public class Provider extends Base {}
                        """.formatted(name, name)));
        Class<?> type = defineAndLoad(bytes, "com.example.Provider");
        assertTrue(SapIndexingGuidance.isIndexingType(type));
        int restarts = RestartLedger.size();
        List<String> events = new ArrayList<>();
        StatusReporter.StatusListener listener = (level, message) -> events.add(level + ":" + message);
        StatusReporter.addListener(listener);
        try {
            SapIndexingGuidance.afterReload(type);
            assertEquals(1, events.size());
            assertTrue(events.get(0).startsWith("INFO:SAP indexing code com.example.Provider reloaded."));
            assertTrue(events.get(0).contains("Reclazz did not reindex stored documents"));
            assertTrue(events.get(0).contains("explicitly reindex affected documents if needed"));
            assertTrue(events.get(0).contains("affected indexes are not determined"));
            assertEquals(restarts, RestartLedger.size());
        } finally { StatusReporter.removeListener(listener); }
    }

    @Test void similarNamesAndOrdinaryTypesProduceNoAdvice() {
        var bytes = compile(new SourceFile("com.example.FieldValueProvider", """
                package com.example;
                public class FieldValueProvider { public String getFieldValues() { return "sample"; } }
                """));
        Class<?> lookalike = defineAndLoad(bytes, "com.example.FieldValueProvider");
        List<String> events = new ArrayList<>();
        StatusReporter.StatusListener listener = (level, message) -> events.add(message);
        StatusReporter.addListener(listener);
        try {
            for (Class<?> type : new Class<?>[]{null, String.class, lookalike}) {
                assertFalse(SapIndexingGuidance.isIndexingType(type));
                SapIndexingGuidance.afterReload(type);
            }
            assertTrue(events.isEmpty(), events.toString());
        } finally { StatusReporter.removeListener(listener); }
    }

    @Test void exactNameMustBeAnInterface() {
        String name = "de.hybris.platform.solrfacetsearch.provider.FieldValueProvider";
        var bytes = compile(new SourceFile(name,
                "package de.hybris.platform.solrfacetsearch.provider; public class FieldValueProvider {}"));
        assertFalse(SapIndexingGuidance.isIndexingType(defineAndLoad(bytes, name)));
    }
}
