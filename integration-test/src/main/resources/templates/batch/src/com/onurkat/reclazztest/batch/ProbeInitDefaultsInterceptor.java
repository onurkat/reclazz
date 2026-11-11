package com.onurkat.reclazztest.batch;

import de.hybris.platform.core.model.ReclazzSchemaProbeModel;
import de.hybris.platform.servicelayer.interceptor.InitDefaultsInterceptor;
import de.hybris.platform.servicelayer.interceptor.InterceptorContext;

/** Only a controller-owned native create can authorize a default mutation. */
public class ProbeInitDefaultsInterceptor implements InitDefaultsInterceptor<ReclazzSchemaProbeModel> {
    public String version() { return "v1"; }
    @Override public void onInitDefaults(ReclazzSchemaProbeModel model, InterceptorContext context) {
        var scope = ProbeInitDefaultsState.scope();
        if (scope == null) return;
        if (model.getClass() != ReclazzSchemaProbeModel.class) throw new IllegalStateException("Unexpected model type");
        String version = version();
        ProbeInitDefaultsState.record(scope, model, version);
        model.setNonce(scope.run + "-s" + scope.step + "-default-" + version);
    }
}
