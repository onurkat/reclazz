package com.onurkat.reclazztest.services;

import com.onurkat.reclazztest.batch.ProbeScheduledMetadataState;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service("reclazzScheduledMetadataJob")
public class ProbeScheduledMetadataJob {
    @Scheduled(fixedDelay = 1000)
    public void tick() { ProbeScheduledMetadataState.record(this); }
}
