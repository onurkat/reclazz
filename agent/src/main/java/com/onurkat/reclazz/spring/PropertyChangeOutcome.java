/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import java.util.List;

/** Only APPLIED permits accepting a file version. PARTIAL cannot promise rollback. */
public record PropertyChangeOutcome(State state, List<String> rebound, int valueFields,
                                    List<String> rebuilt, List<String> findings) {
    public enum State { REJECTED, UNCHECKABLE, NOT_RUN, APPLIED, PARTIAL }

    public PropertyChangeOutcome {
        rebound = List.copyOf(rebound);
        rebuilt = List.copyOf(rebuilt);
        findings = List.copyOf(findings);
    }

    static PropertyChangeOutcome held(State state, List<String> findings) {
        return new PropertyChangeOutcome(state, List.of(), 0, List.of(), findings);
    }
}
