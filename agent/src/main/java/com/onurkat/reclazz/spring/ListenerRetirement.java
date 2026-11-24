package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * One broker's validated part of a listener bean refresh. Validation retires
 * nothing; {@link #commit()} stops and unregisters that broker's consumers.
 * A bean that owns Rabbit, JMS and Kafka consumers is validated against all
 * three brokers before any of them retires a consumer.
 */
interface ListenerRetirement {
    /** Nothing of this broker is involved, or its optional stack is absent. */
    ListenerRetirement NONE = of(false, () -> true);
    /** Validation refused the refresh and already said why. */
    ListenerRetirement REFUSED = of(false, () -> false);

    /** @return false when the retirement failed and the bean must not be recreated */
    boolean commit();

    /** Whether {@link #commit()} stops at least one consumer. */
    boolean retiresConsumers();

    static ListenerRetirement of(boolean retiresConsumers, BooleanSupplier commit) {
        return new ListenerRetirement() {
            @Override public boolean commit() { return commit.getAsBoolean(); }
            @Override public boolean retiresConsumers() { return retiresConsumers; }
        };
    }

    static Map.Entry<String, Supplier<ListenerRetirement>> broker(String name, Supplier<ListenerRetirement> prepare) {
        return Map.entry(name, prepare);
    }

    /**
     * Validates every broker in order, and only when none refused, retires each
     * broker's consumers in the same order.
     *
     * <p>Between validation and retirement the reloaders' monitors are free.
     * That is safe because class reloads run on the single reload worker, and
     * each retirement re-checks lifecycle and registry ownership itself.
     *
     * <p>The retirements are not one transaction: when a later broker fails
     * after an earlier one already retired consumers, those stay retired and
     * the bean is not recreated. That is recorded here, naming the brokers.
     *
     * @return true when the bean may be recreated
     */
    static boolean retireAll(String owner, List<Map.Entry<String, Supplier<ListenerRetirement>>> brokers) {
        List<Map.Entry<String, ListenerRetirement>> prepared = new ArrayList<>();
        for (var broker : brokers) {
            ListenerRetirement part = broker.getValue().get();
            if (part == REFUSED) return false;
            prepared.add(Map.entry(broker.getKey(), part));
        }
        List<String> retired = new ArrayList<>();
        for (var part : prepared) {
            if (!part.getValue().commit()) {
                if (!retired.isEmpty()) {
                    String reason = "listener consumers already retired on " + String.join(" and ", retired)
                            + " stay retired because the " + part.getKey() + " retirement failed; the bean was not recreated";
                    StatusReporter.warn("Listener reload for " + owner + ": " + reason + ". Restart to restore them.");
                    RestartLedger.note(owner, reason);
                }
                return false;
            }
            if (part.getValue().retiresConsumers()) retired.add(part.getKey());
        }
        return true;
    }
}
