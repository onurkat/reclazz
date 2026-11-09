package com.onurkat.reclazztest.batch;

import de.hybris.platform.servicelayer.event.events.AbstractEvent;

/** Ordinary locally published platform event; no cluster-aware interface. */
public class NativeProbeEvent extends AbstractEvent {
    private final String code;
    public NativeProbeEvent(String code) { this.code = code; }
    public String getCode() { return code; }
}
