package de.hybris.platform.core;

/** Fault-injection boundary only. The interceptor registry and Spring below it remain real SAP/Spring. */
public class Registry {
    public static org.springframework.context.ApplicationContext context;
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
