/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReclazzAgentDetectorTest {

    @Test
    void detectsAReclazzJavaagentFlag() {
        List<String> args = List.of("-Xmx512m",
                "-javaagent:/repo/com/onurkat/reclazz/reclazz-agent/1.3.0/reclazz-agent-1.3.0.jar=platform=spring");
        assertThat(ReclazzAgentDetector.isAttached(args)).isTrue();
        assertThat(ReclazzAgentDetector.agentFlag(args)).contains("reclazz-agent");
    }

    @Test
    void ignoresOtherJavaagents() {
        List<String> args = List.of("-javaagent:/somewhere/jacocoagent.jar", "-Dfoo=bar");
        assertThat(ReclazzAgentDetector.isAttached(args)).isFalse();
        assertThat(ReclazzAgentDetector.agentFlag(args)).isNull();
    }

    @Test
    void nullOrEmptyArgumentsMeanNotAttached() {
        assertThat(ReclazzAgentDetector.isAttached((List<String>) null)).isFalse();
        assertThat(ReclazzAgentDetector.isAttached(List.of())).isFalse();
    }
}
