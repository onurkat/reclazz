package com.onurkat.reclazztest.services;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service("reclazzSchedulerTestService")
public class SchedulerTestService {

    private volatile String lastRun = "none";

    @Scheduled(fixedRate = 60000)
    public void tick() {
        lastRun = "v1:" + System.currentTimeMillis();
    }

    public String getLastRun() {
        return lastRun;
    }
}
