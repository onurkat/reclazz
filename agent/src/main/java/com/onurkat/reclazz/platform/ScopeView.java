/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.platform;

import com.onurkat.reclazz.config.AgentConfig;

/** Target SAP roots over the existing discovery; constructing it never publishes configuration. */
public final class ScopeView extends HybrisPlatformContext {
    public ScopeView(HybrisPlatformContext source, AgentConfig.WatchScope target) {
        super(source, target);
    }
}
