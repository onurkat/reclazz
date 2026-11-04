/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ext;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.platform.PropertyFileSnapshots;
import java.lang.instrument.Instrumentation;
import java.util.Collection;
import java.util.Map;

/** Only the existing automation inputs and core logger/admission callbacks. */
public interface PremiumContext {
    PlatformContext platform();
    AgentConfig config();
    PropertyFileSnapshots propertySnapshots();
    Instrumentation instrumentation();
    int applyLoggerLevels(Map<String, String> content, Collection<String> keys);
    boolean requestRunningSystemUpdate();
    /** Configuration keys the platform now answers with new values; consumers that read them once read again. */
    default void propertiesApplied(Collection<String> keys) { }
}
