/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import de.hybris.platform.cronjob.enums.CronJobResult;
import de.hybris.platform.cronjob.enums.CronJobStatus;
import de.hybris.platform.cronjob.model.CronJobModel;
import de.hybris.platform.servicelayer.cronjob.AbstractJobPerformable;
import de.hybris.platform.servicelayer.cronjob.PerformResult;
import org.springframework.stereotype.Component;

@Component
public class ProbeJob extends AbstractJobPerformable<CronJobModel> {
    public PerformResult perform(CronJobModel model) {
        FlowApp.jobs++;
        FlowApp.lastJob = "job-v1:" + model.getCode();
        return new PerformResult(CronJobResult.SUCCESS, CronJobStatus.FINISHED);
    }
}
