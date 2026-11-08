/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazztest.services;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service("reclazzEventTestService")
public class EventTestService {

    private final ApplicationEventPublisher publisher;
    private volatile String lastEvent = "none";

    public EventTestService(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void triggerEvent() {
        publisher.publishEvent(new TestEvent("test-payload"));
    }

    @EventListener
    public void handleEvent(TestEvent event) {
        lastEvent = "handled-v1:" + event.getPayload();
    }

    public String getLastEvent() {
        return lastEvent;
    }

    public static class TestEvent {
        private final String payload;

        public TestEvent(String payload) {
            this.payload = payload;
        }

        public String getPayload() {
            return payload;
        }
    }
}
