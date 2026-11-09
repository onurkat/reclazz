package com.onurkat.reclazztest.batch;

import com.onurkat.reclazztest.data.ReclazzProbeData;
import de.hybris.platform.converters.Populator;
import org.springframework.stereotype.Component;

@Component("reclazzProbePopulator")
public class ProbePopulator implements Populator<String, ReclazzProbeData> {
    @Override public void populate(String source, ReclazzProbeData target) {
        target.setCode("populator-v1");
        target.setLabel(source);
    }
}
