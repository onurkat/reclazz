/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.springboot;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@link ReclazzEndpoint} when the Spring Boot actuator is on the
 * classpath. Split from {@link ReclazzAutoConfiguration} so an app without the
 * actuator never loads an actuator type. The {@code @ConditionalOnClass} guard is
 * read from bytecode, so this class is skipped, not failed, when the actuator is
 * absent.
 */
@AutoConfiguration(after = ReclazzAutoConfiguration.class)
@ConditionalOnClass(Endpoint.class)
@ConditionalOnProperty(prefix = "reclazz", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ReclazzProperties.class)
public class ReclazzEndpointAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ReclazzEndpoint reclazzEndpoint(ReclazzProperties properties) {
        return new ReclazzEndpoint(properties);
    }
}
