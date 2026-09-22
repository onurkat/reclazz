/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import java.io.IOException;
import java.net.Socket;

/** Own the socket before connect so cancellation also interrupts handshake and read. */
final class RequestCancellation {
    private boolean cancelled;
    private Socket socket;

    synchronized void attach(Socket value) throws IOException {
        if (cancelled) {
            value.close();
            throw new IOException("Request cancelled");
        }
        socket = value;
    }

    synchronized void detach(Socket value) { if (socket == value) socket = null; }
    synchronized boolean isCancelled() { return cancelled; }
    synchronized void cancel() {
        cancelled = true;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
}
