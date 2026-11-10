package com.onurkat.reclazztest.batch;

import de.hybris.platform.core.model.ReclazzSchemaProbeModel;
import de.hybris.platform.servicelayer.interceptor.InterceptorContext;
import de.hybris.platform.servicelayer.interceptor.PrepareInterceptor;
import java.util.regex.Pattern;

/** Native mapping is permanent; only this scenario's owned steps may change. */
public class ProbePrepareInterceptor implements PrepareInterceptor<ReclazzSchemaProbeModel> {
    private static final Pattern OWNED = Pattern.compile("^(reclazz-prep-[0-9a-f-]{36})-s([123])-(?:input|prepared-v[12])$");
    public String version() { return "v1"; }

    @Override public void onPrepare(ReclazzSchemaProbeModel model, InterceptorContext context) {
        String nonce = model.getNonce();
        if (nonce == null) return;
        var match = OWNED.matcher(nonce);
        if (!match.matches()) return;
        String version = version();
        // Count already-prepared values too: duplicate registration must fail.
        ProbePrepareState.record(match.group(1), Integer.parseInt(match.group(2)), version);
        if (nonce.endsWith("-input"))
            model.setNonce(match.group(1) + "-s" + match.group(2) + "-prepared-" + version);
    }
}
