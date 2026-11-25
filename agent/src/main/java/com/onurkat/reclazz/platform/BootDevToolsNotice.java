/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.platform;

import com.onurkat.reclazz.ui.StatusReporter;
import java.util.concurrent.atomic.AtomicBoolean;

/** Observe the native restart watcher after refresh, without creating a bean or changing DevTools. */
public final class BootDevToolsNotice {
    private final AtomicBoolean reported = new AtomicBoolean();

    public void refreshed(Object context) {
        if (reported.get()) return;
        try {
            ClassLoader loader = (ClassLoader) context.getClass().getMethod("getClassLoader").invoke(context);
            Class<?> watcher = Class.forName("org.springframework.boot.devtools.classpath.ClassPathFileSystemWatcher", false, loader);
            Object factory = context.getClass().getMethod("getBeanFactory").invoke(context);
            String[] names = (String[]) factory.getClass()
                    .getMethod("getBeanNamesForType", Class.class, boolean.class, boolean.class)
                    .invoke(factory, watcher, false, false);
            for (String name : names) {
                Object singleton = factory.getClass().getMethod("getSingleton", String.class).invoke(factory, name);
                if (watcher.isInstance(singleton) && reported.compareAndSet(false, true)) {
                    StatusReporter.warn("Spring Boot DevTools will also restart the application on class changes. "
                            // ledger-exempt: DevTools initiates this restart itself.
                            + "Reclazz already applies class changes. Start the application JVM with -Dspring.devtools.restart.enabled=false to keep the running context.");
                    return;
                }
            }
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            // Standalone Spring contexts need neither Boot nor the optional DevTools dependency.
        }
    }
}
