/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.gradle;

import java.util.LinkedHashMap;
import java.util.Map;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

/**
 * Prints, as one JSON line, whether the Reclazz agent is attached to a running
 * app and how it is doing. Meant for a person or a coding agent to check the
 * inner loop: {@code ./gradlew reclazzStatus}. It reads the loopback status
 * socket and asks nothing of the agent beyond its own state.
 */
public abstract class ReclazzStatusTask extends DefaultTask {

    private String portFile;
    private String port;
    private String hybrisHome;
    private String timeoutMs;

    @Option(option = "port-file", description = "Path to the agent's port file.")
    public void setPortFile(String portFile) {
        this.portFile = portFile;
    }

    @Option(option = "port", description = "Connect directly to this agent status port.")
    public void setPort(String port) {
        this.port = port;
    }

    @Option(option = "hybris-home", description = "SAP Commerce home, to find its port file.")
    public void setHybrisHome(String hybrisHome) {
        this.hybrisHome = hybrisHome;
    }

    @Option(option = "timeout-ms", description = "How long to wait for the agent to answer.")
    public void setTimeoutMs(String timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    @TaskAction
    public void report() {
        Map<String, String> opts = new LinkedHashMap<>();
        opts.put("baseDir", getProject().getProjectDir().getAbsolutePath());
        if (portFile != null) opts.put("portFile", portFile);
        if (port != null) opts.put("port", port);
        if (hybrisHome != null) opts.put("hybrisHome", hybrisHome);
        if (timeoutMs != null) opts.put("timeoutMs", timeoutMs);

        String json = AgentStatus.query(opts);
        // Clean single line for machine parsing.
        System.out.println(json);
    }
}
