/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.springboot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ReclazzStartupReporterTest {

    @Test
    void attachedIsFineAndSilentOfFailure() {
        ReclazzProperties props = new ReclazzProperties();
        props.setFailOnMissingAgent(true);
        ReclazzStartupReporter reporter = new ReclazzStartupReporter(props);
        assertThatCode(() -> reporter.check(true)).doesNotThrowAnyException();
    }

    @Test
    void missingAgentOnlyWarnsByDefault() {
        ReclazzStartupReporter reporter = new ReclazzStartupReporter(new ReclazzProperties());
        assertThatCode(() -> reporter.check(false)).doesNotThrowAnyException();
    }

    @Test
    void missingAgentFailsWhenConfiguredTo() {
        ReclazzProperties props = new ReclazzProperties();
        props.setFailOnMissingAgent(true);
        ReclazzStartupReporter reporter = new ReclazzStartupReporter(props);
        assertThatThrownBy(() -> reporter.check(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("-javaagent");
    }

    @Test
    void hintNamesTheThreeWaysToAttach() {
        assertThat(ReclazzStartupReporter.enableHint())
                .contains("-javaagent")
                .contains("com.onurkat.reclazz")
                .contains("prepare-agent");
    }
}
