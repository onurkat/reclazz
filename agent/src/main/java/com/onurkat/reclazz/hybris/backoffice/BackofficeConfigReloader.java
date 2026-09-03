/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris.backoffice;

import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Applies a saved {@code *-backoffice-config.xml} to the running backoffice.
 *
 * <p>cockpitng reads the view configuration once and answers every later
 * question from the caches inside {@code DefaultCockpitConfigurationService},
 * so editing the file changed nothing until the next full backoffice
 * redeploy. That service implements cockpitng's own {@code Resettable}
 * contract, whose {@code reset()} drops exactly those caches, and that is
 * what is called here: the framework's own reset, on the framework's own
 * bean, in whichever web contexts carry one. {@code resetToDefaults()} is
 * deliberately never called; it erases the customisations users made in the
 * running backoffice, which is not what saving a file means.
 *
 * <p>What the next read actually sees depends on where the running backoffice
 * loads configuration from. With cockpitng's classpath resource loading it is
 * the extension's file, and the save is live on the next view open; where the
 * configuration was packaged into the module archive at build time, the
 * packaged copy answers until the next build, and the message says so rather
 * than claiming the save arrived.
 *
 * <p>All reflective, no backoffice dependency; a server without cockpitng has
 * no such bean and nothing here runs.
 */
public final class BackofficeConfigReloader {

    private BackofficeConfigReloader() {
    }

    private static final String RESETTABLE = "com.hybris.cockpitng.core.util.Resettable";
    private static final String SERVICE_TYPE =
            "com.hybris.cockpitng.core.config.CockpitConfigurationService";

    /**
     * Reset the cockpit configuration caches in every context that has them.
     *
     * @return how many configuration services were reset
     */
    public static int reload(String fileName, List<Object> applicationContexts) {
        int reset = 0;
        for (Object appContext : applicationContexts) {
            try {
                ClassLoader loader = appContext.getClass().getClassLoader();
                Class<?> serviceType;
                Class<?> resettable;
                try {
                    serviceType = Class.forName(SERVICE_TYPE, false, loader);
                    resettable = Class.forName(RESETTABLE, false, loader);
                } catch (ClassNotFoundException notBackoffice) {
                    continue;                       // this context is not a backoffice
                }

                Method getBeanNames = appContext.getClass()
                        .getMethod("getBeanNamesForType", Class.class);
                String[] names = (String[]) getBeanNames.invoke(appContext, serviceType);
                if (names == null) continue;

                Method getBean = appContext.getClass().getMethod("getBean", String.class);
                for (String name : names) {
                    Object service = getBean.invoke(appContext, name);
                    if (service == null || !resettable.isInstance(service)) continue;
                    resettable.getMethod("reset").invoke(service);
                    reset++;
                }
            } catch (Throwable oneContext) {
                // A context that cannot be asked keeps its cache; the report
                // below counts only what was actually reset.
            }
        }

        if (reset > 0) {
            StatusReporter.success("Backoffice configuration cache reset ("
                    + com.onurkat.reclazz.ui.Plural.of(reset, "service") + ") for " + fileName + ": the next view open re-reads "
                    + "the configuration.");
            StatusReporter.info("If the running backoffice reads this configuration from "
                    + "the packaged module archive rather than the extension folder, the "
                    + "packaged copy still answers until the next build.");
        }
        return reset;
    }
}
