/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e.harness;

/** Records actual native registry insertions, including ones later rolled back. */
public final class ListenerRegistryProbe {
    private ListenerRegistryProbe() { }

    public static String observe(String source, String registryExpression) {
        return source.replace("var server =", """
                var observedRegistry = %s;
                var observedField = observedRegistry.getClass().getDeclaredField("listenerContainers");
                observedField.setAccessible(true);
                var originalContainers = (java.util.Map<String,Object>) observedField.get(observedRegistry);
                observedField.set(observedRegistry, new java.util.concurrent.ConcurrentHashMap<String,Object>(originalContainers) {
                    @Override public Object put(String id, Object container) {
                        System.out.println("STAGED:" + id);
                        return super.put(id, container);
                    }
                });
                var server =""".formatted(registryExpression));
    }
}
