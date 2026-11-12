package com.onurkat.reclazztest.batch;

import de.hybris.platform.processengine.action.AbstractProceduralAction;
import de.hybris.platform.processengine.model.BusinessProcessModel;

/** Executed only by the owned native process graph. */
public class ProbeProcessAction extends AbstractProceduralAction<BusinessProcessModel> {
    @Override
    public void executeAction(BusinessProcessModel process) {
        getProcessParameterHelper().setProcessParameter(process, "reclazzActionResult", "action-v1");
    }
}
