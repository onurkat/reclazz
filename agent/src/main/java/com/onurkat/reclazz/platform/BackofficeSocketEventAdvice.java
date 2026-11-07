/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import java.util.List;

/** Shared free/premium advice; this does not install any native listener. */
public final class BackofficeSocketEventAdvice {
    private BackofficeSocketEventAdvice() { }

    public static void report(Class<?> type, List<BackofficeSocketEventDetector.Handler> handlers) {
        if (handlers == null) {
            String reason = "added Backoffice @SocketEvent actions are not registered in existing widgets; restart required";
            StatusReporter.warn(type.getName() + ": " + reason);
            RestartLedger.note(type.getName(), reason);
            return;
        }
        for (var handler : handlers) {
            String member = type.getName() + "." + handler.name() + handler.descriptor();
            switch (handler.binding()) {
                case RELOAD_VERIFIED -> StatusReporter.info(member + ": added @SocketEvent handler. "
                        + "Widget views composed after this reload bind it in the default MVC controller "
                        + "(public one-parameter native binding measured; zero-parameter binding checked in bytecode). "
                        + "Already open views keep their old bindings; compose a new view to use it. "
                        + "Widget definition/connection routing and a particular reopen UI action are not verified.");
                case NATIVE_INVALID -> {
                    String reason = member + ": cockpitng's default controller binds only public @SocketEvent methods "
                            + "with at most one parameter; this handler is not bound.";
                    StatusReporter.warn(reason);
                    RestartLedger.note(type.getName(), reason);
                }
                case RELOAD_UNVERIFIED -> {
                    String reason = member + ": added static @SocketEvent handler; native binding accepts this shape, "
                            + "but Reclazz has not verified it after a reload. Reopen the view and check it, or restart.";
                    StatusReporter.info(reason);
                    RestartLedger.note(type.getName(), reason);
                }
            }
        }
    }
}
