/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import de.hybris.platform.servicelayer.event.events.AbstractEvent;
import de.hybris.platform.servicelayer.event.impl.EventScope;

/** A genuine SAP AbstractEvent, locally scoped so the real cluster/tenant guard passes. */
public class ProbeEvent extends AbstractEvent {
    public final String nonce;

    public ProbeEvent(String nonce) {
        this.nonce = nonce;
        EventScope scope = new EventScope();
        scope.setTenantId("junit");
        setScope(scope);
    }
}
