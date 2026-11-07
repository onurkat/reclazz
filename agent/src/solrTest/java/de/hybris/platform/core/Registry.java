/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package de.hybris.platform.core;
import com.onurkat.reclazz.hybris.SolrNativeFixture;
import java.util.*;
import java.util.concurrent.Callable;
public final class Registry {
    public static Object getMasterTenant() { return SolrNativeFixture.master; }
    public static Map<String, Object> getSlaveTenants() { return Map.of(); }
    public static Object runAsTenant(Object tenant, Callable<?> work) throws Exception {
        Object previous = SolrNativeFixture.TENANT.get();
        try { SolrNativeFixture.TENANT.set(tenant); return work.call(); }
        finally { if (previous == null) SolrNativeFixture.TENANT.remove(); else SolrNativeFixture.TENANT.set(previous); }
    }
}
