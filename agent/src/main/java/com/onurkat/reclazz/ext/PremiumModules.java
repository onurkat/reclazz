/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ext;

import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Core-owned registry. An absent module affects automation, never free startup. */
public final class PremiumModules implements PlatformChangeHandler, StructuralReloadHook {
    public static final int CONTRACT_VERSION = 1;
    private final PremiumAdmission admission;
    private final PlatformChangeHandler fallback = new AdviceOnlyChangeHandler();
    private final StructuralReloadHook structuralFallback = new NoopStructuralReloadHook();
    private volatile Map<Capability, Registration> registered = Map.of();

    private record Registration(PremiumModule module,
                                Map<Capability, PlatformChangeHandler> handlers,
                                StructuralReloadHook structural) { }

    public PremiumModules(PremiumAdmission admission) {
        this.admission = Objects.requireNonNull(admission);
    }

    /** Snapshot and validate everything before publishing any of a module's operations. */
    public synchronized boolean register(Supplier<? extends PremiumModule> factory) {
        try {
            PremiumModule module = Objects.requireNonNull(factory.get());
            if (module.contractVersion() != CONTRACT_VERSION) {
                StatusReporter.warn("Optional platform automation has an incompatible contract; free reload remains active.");
                return false;
            }
            Set<Capability> capabilities = Set.copyOf(module.capabilities());
            Map<Capability, PlatformChangeHandler> handlers = Map.copyOf(module.changeHandlers());
            StructuralReloadHook structural = Objects.requireNonNull(module.structuralReloadHook());
            if (!capabilities.containsAll(handlers.keySet())) throw new IllegalArgumentException("undeclared change handler");
            Map<Capability, Registration> next = new EnumMap<>(Capability.class);
            next.putAll(registered);
            Registration entry = new Registration(module, handlers, structural);
            for (Capability capability : capabilities) {
                if (next.putIfAbsent(capability, entry) != null) throw new IllegalArgumentException("duplicate " + capability);
            }
            registered = Map.copyOf(next);
            return true;
        } catch (Throwable failure) {
            // As at agent startup, an optional initializer cannot own free startup.
            StatusReporter.warn("Optional platform automation was not registered (" + failure
                    + "); free reload remains active.");
            return false;
        }
    }

    public boolean has(Capability capability) { return registered.containsKey(capability); }

    public boolean reindex(Class<?> type) {
        Registration entry = registered.get(Capability.SOLR_REINDEX);
        return entry != null && request(Capability.SOLR_REINDEX).granted()
                && entry.module().reindex(type);
    }

    public PremiumAdmission.Admission request(Capability capability) {
        if (!has(capability)) return new PremiumAdmission.Admission(false, "platform automation is unavailable");
        return admission.request(capability);
    }

    @Override public Outcome handle(Change change) {
        Registration entry = registered.get(change.capability());
        PlatformChangeHandler handler = entry == null ? null : entry.handlers().get(change.capability());
        if (handler == null || !request(change.capability()).granted()) return fallback.handle(change);
        return handler.handle(change);
    }

    @Override public void afterReload(Class<?> type) {
        Registration entry = registered.get(Capability.BACKOFFICE_RELOAD);
        if (entry == null || !request(Capability.BACKOFFICE_RELOAD).granted()) {
            structuralFallback.afterReload(type);
        } else {
            entry.structural().afterReload(type);
        }
    }

    public PremiumModule.PreparedRefresh prepareInterceptor(String className, List<Object> contexts) {
        Registration entry = registered.get(Capability.INTERCEPTOR_REFRESH);
        if (entry != null && request(Capability.INTERCEPTOR_REFRESH).granted()) {
            return entry.module().prepareInterceptor(className, contexts);
        }
        String reason = "Interceptor registration was not refreshed; restart required.";
        StatusReporter.warn(className + ": " + reason);
        RestartLedger.note(className, reason);
        return null;
    }
}
