/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ReclazzAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ReclazzAutoConfiguration.class, ReclazzEndpointAutoConfiguration.class));

    @Test
    void registersTheStartupReporterAndEndpointByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ReclazzStartupReporter.class);
            assertThat(context).hasSingleBean(ReclazzProperties.class);
            // The actuator is on the test classpath, so the endpoint is present.
            assertThat(context).hasSingleBean(ReclazzEndpoint.class);
        });
    }

    @Test
    void disabledByPropertyRegistersNothing() {
        runner.withPropertyValues("reclazz.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ReclazzStartupReporter.class);
            assertThat(context).doesNotHaveBean(ReclazzEndpoint.class);
        });
    }

    @Test
    void propertiesBind() {
        runner.withPropertyValues("reclazz.fail-on-missing-agent=true", "reclazz.port=54321")
                .run(context -> {
                    ReclazzProperties props = context.getBean(ReclazzProperties.class);
                    assertThat(props.isFailOnMissingAgent()).isTrue();
                    assertThat(props.getPort()).isEqualTo(54321);
                });
    }
}
