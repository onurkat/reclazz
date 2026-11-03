/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * An application started with portFile=... in its -javaagent flag has the
 * agent's port in that file, and the actuator endpoint found no agent at all
 * unless the same path was repeated in reclazz.port-file.
 */
class AgentPortFileFromFlagTest {

    @Test
    void readsThePortFileArgumentOfThePlainFlag() {
        assertThat(AgentStatusClient.portFileFromAgentFlag(
                "-javaagent:/opt/reclazz-agent.jar=platform=spring,watchDirs=/a;/b,portFile=/work/agent.port,debounceMs=100"))
                .isEqualTo(Paths.get("/work/agent.port"));
    }

    @Test
    void readsThePortFileArgumentOfTheQuotedSapWrapperFlag() {
        assertThat(AgentStatusClient.portFileFromAgentFlag(
                "-javaagent:\"/Users/dev/.reclazz/agent/reclazz-agent.jar=hybrisHome=/hy,statusPort=0,"
                        + "portFile=/Users/dev/project/.idea/reclazz/agent.port,autoCompile=true\""))
                .isEqualTo(Paths.get("/Users/dev/project/.idea/reclazz/agent.port"));
    }

    @Test
    void keepsACommaInsideThePathAndIgnoresFlagsWithoutOne() {
        assertThat(AgentStatusClient.portFileFromAgentFlag(
                "-javaagent:/opt/agent.jar=portFile=/work/a,b/agent.port,verbose=true"))
                .isEqualTo(Paths.get("/work/a,b/agent.port"));
        assertThat(AgentStatusClient.portFileFromAgentFlag("-javaagent:/opt/agent.jar=verbose=true")).isNull();
        assertThat(AgentStatusClient.portFileFromAgentFlag("-javaagent:/opt/agent.jar")).isNull();
        assertThat(AgentStatusClient.portFileFromAgentFlag(null)).isNull();
    }
}
