/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.springboot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Reclazz Spring Boot starter. On startup it reports
 * whether the hot-reload agent is attached, and where the actuator is present a
 * companion configuration exposes a {@code reclazz} endpoint. It does not attach
 * the agent: a {@code -javaagent} must be present at JVM startup.
 *
 * <p>Enabled by default; set {@code reclazz.enabled=false} to switch it off, or
 * scope it to a dev profile in the usual Spring way.
 */
@AutoConfiguration
@EnableConfigurationProperties(ReclazzProperties.class)
@ConditionalOnProperty(prefix = "reclazz", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReclazzAutoConfiguration {

    @Bean
    public ReclazzStartupReporter reclazzStartupReporter(ReclazzProperties properties) {
        return new ReclazzStartupReporter(properties);
    }
}
