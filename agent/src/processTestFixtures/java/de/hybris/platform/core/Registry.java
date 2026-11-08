/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package de.hybris.platform.core;
import com.onurkat.reclazz.hybris.ProcessTenantFixture;
import java.util.*;
import java.util.concurrent.Callable;
/** Isolated process-test execution scope; not the SAP tenant implementation. */
public final class Registry {
    public static Object getMasterTenant() { return ProcessTenantFixture.tenants.isEmpty() ? null : ProcessTenantFixture.tenants.get(0); }
    public static Map<String,Object> getSlaveTenants() {
        var result = new LinkedHashMap<String,Object>();
        for (int i=1;i<ProcessTenantFixture.tenants.size();i++) result.put("example"+i,ProcessTenantFixture.tenants.get(i));
        return result;
    }
    public static Object getCurrentTenant() { return ProcessTenantFixture.CURRENT.get(); }
    public static Object runAsTenant(Object tenant, Callable<?> work) throws Exception {
        Object previous = getCurrentTenant();
        try { ProcessTenantFixture.CURRENT.set(tenant); return work.call(); }
        finally { if (previous==null) ProcessTenantFixture.CURRENT.remove(); else ProcessTenantFixture.CURRENT.set(previous); }
    }
}
