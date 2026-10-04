/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import de.hybris.platform.servicelayer.model.attribute.DynamicAttributeHandler;
import org.springframework.stereotype.Component;

@Component
public class DynamicHandler implements DynamicAttributeHandler<String, ProbeItem> {
    public String get(ProbeItem model) {
        FlowApp.gets++;
        return "dynamic-v1:" + model.nonce;
    }
    public void set(ProbeItem model, String value) {
        FlowApp.sets++;
        model.stored = "set-v1:" + value;
    }
}
