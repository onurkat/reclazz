/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ext;

import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;

/** Free discovery can explain a manual step without performing platform automation. */
public final class AdviceOnlyChangeHandler implements PlatformChangeHandler {
    @Override public Outcome handle(Change change) {
        String file = change.path().getFileName().toString();
        if (change.capability() == Capability.PROCESS_RELOAD) {
            StatusReporter.warn("SAP process definition " + file + " " + change.eventType()
                    + ": not applied; restart required. Process definition hot reload is unsupported; "
                    + "no running process was changed or replayed.");
            RestartLedger.note(file, "SAP process definition resource changed but was not applied; "
                    + "validate the resource and restart to load it");
            return Outcome.ADVICE;
        }
        String reason = switch (change.capability()) {
            case CODEGEN -> "Automatic code generation is unavailable; run `ant build` manually to apply.";
            case RUNNING_SYSTEM_UPDATE -> "Run Update Running System in HAC to apply the schema change.";
            case IMPEX_IMPORT -> "Import the changed ImpEx file manually in HAC.";
            case INTERCEPTOR_REFRESH -> "Interceptor registration was not refreshed; restart required.";
            case CONFIG_APPLY -> "Platform properties were not applied; restart required.";
            case LOCALIZATION_RELOAD -> "Localization text was not applied; restart required.";
            case BACKOFFICE_RELOAD -> "Backoffice configuration was not applied; restart required.";
            case SAP_MAPPER -> "SAP mapper bindings were not refreshed; restart required.";
            case SOLR_REINDEX -> "Reclazz did not reindex stored documents; explicitly reindex affected documents if needed.";
            case PROCESS_RELOAD -> throw new AssertionError("Process diagnostic is handled above");
        };
        if (change.capability() == Capability.CODEGEN) StatusReporter.info("Codegen XML changed: " + file);
        StatusReporter.warn(file + ": " + reason);
        RestartLedger.note(file, reason);
        return Outcome.ADVICE;
    }
}
