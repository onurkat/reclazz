/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import java.lang.instrument.Instrumentation;
import com.onurkat.reclazz.transform.SapProcessDefinitionTransformer;

/** Isolated SDK harness: the production transformer, without starting a tenant or a watcher. */
public final class ProcessObserverAgent {
    public static void premain(String options, Instrumentation instrumentation) {
        if (!"late".equals(options)) instrumentation.addTransformer(new java.lang.instrument.ClassFileTransformer() {
            final SapProcessDefinitionTransformer delegate = new SapProcessDefinitionTransformer();
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    java.security.ProtectionDomain domain, byte[] bytes) {
                byte[] result = delegate.transform(loader, name, type, domain, bytes);
                if (name != null && (name.endsWith("/ProcessDefinitionsCache")
                        || name.endsWith("/ProcessDefinitionsProvider") || name.endsWith("/XMLProcessDefinitionsReader")))
                    System.out.println("SDK_OBSERVER " + name.substring(name.lastIndexOf('/') + 1) + " transformed=" + (result != null));
                return result;
            }
        }, false);
    }
}
