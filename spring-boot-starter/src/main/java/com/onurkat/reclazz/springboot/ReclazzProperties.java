/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.springboot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Reclazz Spring Boot starter, under the {@code reclazz}
 * prefix. The starter does not attach the agent (a {@code -javaagent} must be on
 * the command line at JVM startup); it reports whether the agent is attached and,
 * where the actuator is present, exposes its status. These properties tune that.
 */
@ConfigurationProperties(prefix = "reclazz")
public class ReclazzProperties {

    /** Master switch for the starter's auto-configuration. */
    private boolean enabled = true;

    /**
     * Fail application startup when the agent is not attached to this JVM.
     * Off by default: a missing agent is reported, not fatal. Turn it on in a
     * dev profile where a hot-reload run is expected.
     */
    private boolean failOnMissingAgent = false;

    /** Connect to this agent status port directly; otherwise the port file is read. */
    private Integer port;

    /** Path to the agent port file; otherwise the usual locations are tried. */
    private String portFile;

    /** SAP Commerce home, used to locate its {@code .reclazz/agent.port}. */
    private String hybrisHome;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFailOnMissingAgent() {
        return failOnMissingAgent;
    }

    public void setFailOnMissingAgent(boolean failOnMissingAgent) {
        this.failOnMissingAgent = failOnMissingAgent;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getPortFile() {
        return portFile;
    }

    public void setPortFile(String portFile) {
        this.portFile = portFile;
    }

    public String getHybrisHome() {
        return hybrisHome;
    }

    public void setHybrisHome(String hybrisHome) {
        this.hybrisHome = hybrisHome;
    }
}
