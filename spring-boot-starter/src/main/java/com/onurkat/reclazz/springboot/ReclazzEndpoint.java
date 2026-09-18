/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.springboot;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * A {@code reclazz} actuator endpoint. A read reports whether the agent is
 * attached to this JVM and, if its loopback status socket answers, its version
 * and health (reloads, failures, latency). A coding agent or a dashboard can
 * poll {@code /actuator/reclazz} to see the inner loop without leaving the app.
 */
@Endpoint(id = "reclazz")
public class ReclazzEndpoint {

    private final ReclazzProperties properties;

    public ReclazzEndpoint(ReclazzProperties properties) {
        this.properties = properties;
    }

    @ReadOperation
    public Map<String, Object> reclazz() {
        Map<String, Object> result = new LinkedHashMap<>();
        String flag = ReclazzAgentDetector.agentFlag();
        boolean attached = flag != null;
        result.put("attached", attached);
        if (flag != null) {
            result.put("agentFlag", flag);
        }

        AgentStatusClient.Status status = AgentStatusClient.query(properties);
        if (status.reachable) {
            result.put("agent", status.agent);
            result.put("protocol", status.protocol);
            result.put("port", status.port);
            result.put("health", status.lines);
        } else if (attached) {
            result.put("statusSocket", "unreachable: " + status.reason);
        }
        return result;
    }
}
