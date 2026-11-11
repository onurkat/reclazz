package de.hybris.platform.core;

/** Fault-injection boundary only. The interceptor registry and Spring below it remain real SAP/Spring. */
public class Registry {
    public static org.springframework.context.ApplicationContext context;
    public static final class Tenant {
        public Object getApplicationContext() { return context; }
    }
    private static final Tenant master = new Tenant();
    public static Tenant getMasterTenant() { return master; }
    public static java.util.Map<String,Tenant> getSlaveTenants() { return java.util.Map.of(); }
    public static Object runAsTenant(Tenant tenant, java.util.concurrent.Callable<?> action) throws Exception {
        if (tenant != master) throw new IllegalStateException("wrong tenant");
        boolean before = active;
        try { active = true; return action.call(); }
        finally { active = before; }
    }
    public static boolean active;
    public static boolean failActivation;
    public static int activations;
    public static boolean hasCurrentTenant() { return active; }
    public static void activateMasterTenant() {
        activations++;
        if (failActivation) throw new IllegalStateException("tenant unavailable");
        active = true;
    }
    public static org.springframework.context.ApplicationContext getApplicationContext() {
        if (!active) throw new IllegalStateException("no tenant on watcher thread");
        return context;
    }
}
