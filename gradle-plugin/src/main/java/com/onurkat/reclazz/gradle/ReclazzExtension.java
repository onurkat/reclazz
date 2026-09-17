/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.gradle;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

/**
 * Configuration for the Reclazz Gradle plugin, exposed as the {@code reclazz} block.
 *
 * <p>Everything has a working default, so a Spring Boot project needs only to apply
 * the plugin. Override any of these when the defaults do not fit.
 */
public abstract class ReclazzExtension {

    /** Whether the agent is attached at all. Default {@code true}. */
    public abstract Property<Boolean> getEnabled();

    /**
     * An explicit path to the agent jar. When set, it wins over {@link #getAgentVersion()}
     * and no dependency is resolved. Use this while the agent is not yet on a registry,
     * or to pin a locally built jar.
     */
    public abstract RegularFileProperty getAgentJar();

    /**
     * The agent version to resolve as {@code com.onurkat.reclazz:reclazz-agent:<version>}
     * when {@link #getAgentJar()} is not set. Defaults to the plugin version.
     */
    public abstract Property<String> getAgentVersion();

    /**
     * The agent platform, for example {@code spring}. Defaults to {@code spring} when the
     * Spring Boot plugin is applied, and is otherwise left to the agent's own default.
     */
    public abstract Property<String> getPlatform();

    /**
     * Directories the agent watches for recompiled classes. Defaults to the main source
     * set output when empty. Multiple entries are joined with a semicolon.
     */
    public abstract ListProperty<String> getWatchDirs();

    /**
     * Extra agent arguments as key/value pairs, appended as {@code key=value} and joined
     * with commas. These override the computed {@code platform} and {@code watchDirs}.
     */
    public abstract MapProperty<String, String> getArguments();

    /** Attach the agent to {@code Test} tasks. Default {@code true}. */
    public abstract Property<Boolean> getApplyToTest();

    /** Attach the agent to the {@code bootRun} task when present. Default {@code true}. */
    public abstract Property<Boolean> getApplyToBootRun();
}
