/* Copyright 2026 Onur Kat; SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** The coordination of one bean's consumers on several brokers, with stand-in brokers. */
class ListenerRetirementTest {
    final List<String> events = new ArrayList<>();

    @AfterEach void clear() { RestartLedger.clear(); }

    /** A broker that records its validation and its retirement. */
    private java.util.Map.Entry<String, java.util.function.Supplier<ListenerRetirement>> broker(
            String name, boolean refuses, boolean retires, boolean commitSucceeds) {
        return ListenerRetirement.broker(name, () -> {
            events.add("prepare " + name);
            if (refuses) return ListenerRetirement.REFUSED;
            return ListenerRetirement.of(retires, () -> { events.add("commit " + name); return commitSucceeds; });
        });
    }

    @Test void everyBrokerValidatesBeforeAnyRetires() {
        assertTrue(ListenerRetirement.retireAll("app.Listener", List.of(
                broker("Kafka", false, true, true), broker("JMS", false, true, true), broker("Rabbit", false, true, true))));
        assertEquals(List.of("prepare Kafka", "prepare JMS", "prepare Rabbit", "commit Kafka", "commit JMS", "commit Rabbit"), events);
    }

    @Test void aRefusalOnAnyBrokerRetiresNothingAnywhere() {
        for (int refusing = 0; refusing < 3; refusing++) {
            events.clear();
            assertFalse(ListenerRetirement.retireAll("app.Listener", List.of(
                    broker("Kafka", refusing == 0, true, true), broker("JMS", refusing == 1, true, true),
                    broker("Rabbit", refusing == 2, true, true))));
            assertTrue(events.stream().noneMatch(e -> e.startsWith("commit")), events.toString());
            assertEquals(refusing + 1, events.size(), "validation stops at the refusing broker: " + events);
        }
    }

    @Test void aLaterFailureRecordsTheBrokersAlreadyRetired() {
        assertFalse(ListenerRetirement.retireAll("app.Listener", List.of(
                broker("Kafka", false, true, true), broker("JMS", false, false, true),
                broker("Rabbit", false, true, false))));
        assertEquals(List.of("prepare Kafka", "prepare JMS", "prepare Rabbit", "commit Kafka", "commit JMS", "commit Rabbit"), events);
        var notes = RestartLedger.digest();
        assertTrue(notes.stream().anyMatch(n -> n.contains("already retired on Kafka stay retired because the Rabbit retirement failed")),
                notes.toString());
        assertTrue(notes.stream().noneMatch(n -> n.contains("JMS stay retired") || n.contains("Kafka and JMS")),
                "a broker that retired nothing is not named: " + notes);
    }

    @Test void aFirstFailureHasNothingAlreadyRetiredToRecord() {
        assertFalse(ListenerRetirement.retireAll("app.Listener", List.of(
                broker("Kafka", false, true, false), broker("JMS", false, true, true))));
        assertEquals(List.of("prepare Kafka", "prepare JMS", "commit Kafka"), events);
        assertTrue(RestartLedger.digest().stream().noneMatch(n -> n.contains("already retired")), RestartLedger.digest().toString());
    }
}
