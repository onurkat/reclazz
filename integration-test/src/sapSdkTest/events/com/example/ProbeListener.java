/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import de.hybris.platform.servicelayer.event.impl.AbstractEventListener;

/** A genuine SAP AbstractEventListener subclass; onEvent is the reload surface. */
public class ProbeListener extends AbstractEventListener<ProbeEvent> {
    private final Dependency dependency;

    public ProbeListener(Dependency dependency) { this.dependency = dependency; }

    @Override
    protected void onEvent(ProbeEvent event) {
        EventApp.deliveries++;
        EventApp.lastResult = "listener-v1:" + dependency.value() + ":" + event.nonce;
    }
}
