/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.bootstrap.RequestGate;
import com.onurkat.reclazz.ui.StatusReporter;

/** Keeps the work on the reload queue while a long request prevents its application. */
final class RequestReloadBoundary {
    private RequestReloadBoundary() { }

    static void run(Runnable work) {
        RequestGate gate = RequestGate.global();
        try {
            boolean announced = false;
            while (!gate.tryBeginReload(1000)) {
                if (!announced) {
                    StatusReporter.info("Request boundary deferred: " + gate.waitingFor()
                            + ". New requests are admitted; the queued edit will retry at an idle boundary.");
                    announced = true;
                }
                gate.awaitIdle();
            }
            try {
                work.run();
            } finally {
                gate.endReload();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            StatusReporter.info("Request boundary wait interrupted; the queued edit was not applied.");
        }
    }
}
