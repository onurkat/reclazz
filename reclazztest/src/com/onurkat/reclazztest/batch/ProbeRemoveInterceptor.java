package com.onurkat.reclazztest.batch;
import de.hybris.platform.core.model.ReclazzSchemaProbeModel;
import de.hybris.platform.servicelayer.interceptor.RemoveInterceptor;
import de.hybris.platform.servicelayer.interceptor.InterceptorContext;

/** Records only explicitly scoped removal of this run's disposable row. */
public class ProbeRemoveInterceptor implements RemoveInterceptor<ReclazzSchemaProbeModel> {
    public String version() { return "v1"; }
    @Override public void onRemove(ReclazzSchemaProbeModel model,InterceptorContext context) {
        var s=ProbeRemoveState.scope();if(s==null) return;
        if(model.getClass()!=ReclazzSchemaProbeModel.class || !s.pk.equals(model.getPk()) ||
           !(s.run+"-s"+s.step).equals(model.getNonce())) throw new IllegalStateException("Foreign remove model");
        ProbeRemoveState.record(s,model,version());
    }
}
