package com.onurkat.reclazztest.batch;
import de.hybris.platform.core.model.ReclazzSchemaProbeModel;
import de.hybris.platform.servicelayer.interceptor.LoadInterceptor;
import de.hybris.platform.servicelayer.interceptor.InterceptorContext;

/** Observes only an authorized native load; never changes or saves the model. */
public class ProbeLoadInterceptor implements LoadInterceptor<ReclazzSchemaProbeModel> {
    public String version() { return "v1"; }
    @Override public void onLoad(ReclazzSchemaProbeModel model, InterceptorContext context) {
        var scope = ProbeLoadState.scope();
        if (scope == null) return;
        if (model.getClass() != ReclazzSchemaProbeModel.class ||
            !scope.pk.equals(model.getPk()) || !(scope.run+"-target").equals(model.getNonce()))
            throw new IllegalStateException("Foreign load model");
        ProbeLoadState.record(scope,model,version());
    }
}
