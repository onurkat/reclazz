package com.onurkat.reclazztest.batch;

import de.hybris.platform.servicelayer.event.impl.AbstractEventListener;
import org.springframework.stereotype.Component;

@Component("reclazzNativeProbeListener")
public class NativeProbeListener extends AbstractEventListener<NativeProbeEvent> {
    @Override protected void onEvent(NativeProbeEvent event) {
        ExecutionProbe.record(event.getCode(), "event-v1");
    }
}
