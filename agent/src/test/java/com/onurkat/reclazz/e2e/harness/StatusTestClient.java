/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e.harness;

import com.onurkat.reclazz.protocol.StatusEndpoint;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;

public final class StatusTestClient {
    private StatusTestClient() { }

    public static Socket connect(Path portFile) throws IOException {
        StatusEndpoint endpoint = StatusEndpoint.read(portFile);
        Socket socket = new Socket("127.0.0.1", endpoint.port());
        try {
            endpoint.authenticate(socket.getOutputStream());
            return socket;
        } catch (IOException failure) {
            socket.close();
            throw failure;
        }
    }
}
