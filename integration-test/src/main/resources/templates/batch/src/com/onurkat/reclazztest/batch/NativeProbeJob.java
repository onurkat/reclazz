package com.onurkat.reclazztest.batch;

import de.hybris.platform.cronjob.enums.CronJobResult;
import de.hybris.platform.cronjob.enums.CronJobStatus;
import de.hybris.platform.cronjob.model.CronJobModel;
import de.hybris.platform.servicelayer.cronjob.AbstractJobPerformable;
import de.hybris.platform.servicelayer.cronjob.PerformResult;
import org.springframework.stereotype.Component;

@Component("reclazzNativeProbeJob")
public class NativeProbeJob extends AbstractJobPerformable<CronJobModel> {
    public String version() { return "job-v1"; }
    @Override public PerformResult perform(CronJobModel cronJob) {
        ExecutionProbe.record(cronJob.getCode(), version());
        return new PerformResult(CronJobResult.SUCCESS, CronJobStatus.FINISHED);
    }
}
