/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.ui.StatusReporter;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * TCP server that broadcasts agent events as JSON lines to connected clients.
 * The IntelliJ plugin connects to this server to receive real-time status updates.
 * Binds to loopback only (127.0.0.1) for security.
 *
 * Protocol: one JSON object per line, terminated by newline.
 *
 * Clients may send one line back: {@code DIAGNOSE <class name>} or
 * {@code PENDING}. Both read state the agent already broadcasts and return
 * text, so they add no reach into the process, and the socket is loopback-only
 * as before. Anything else is ignored rather than answered, so a stray
 * connection cannot make the agent talk.
 */
public class StatusServer implements StatusReporter.StatusListener {

    private static final int MAX_CLIENTS = 5;
    private static final int PROTOCOL_VERSION = 1;
    private static final int ACCEPT_TIMEOUT_MS = 2000;
    private static final int HEARTBEAT_INTERVAL_SECONDS = 10;

    private final int requestedPort;
    private final Path portFile;
    private ServerSocket serverSocket;
    private final List<ClientConnection> clients = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;
    private ScheduledExecutorService heartbeatExecutor;

    /** Answers DIAGNOSE, when the agent has one to give. */
    private volatile java.util.function.Function<String, List<String>> diagnoser;

    public StatusServer(int port, Path portFile) {
        this.requestedPort = port;
        this.portFile = portFile;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(requestedPort, 5, InetAddress.getLoopbackAddress());
        serverSocket.setSoTimeout(ACCEPT_TIMEOUT_MS);
        running = true;

        int actualPort = serverSocket.getLocalPort();

        // Write the actual bound port to the port file (reject symlinks to prevent TOCTOU attacks)
        if (portFile != null) {
            try {
                Files.createDirectories(portFile.getParent());
                if (Files.exists(portFile) && Files.isSymbolicLink(portFile)) {
                    StatusReporter.warn("Port file is a symlink — refusing to write: " + portFile);
                } else {
                    // Written whole: the IDE polls this file with no lock
                    // between us, and a reader arriving mid-write gets whatever
                    // prefix has landed. "586" parses perfectly well and is not
                    // the port this agent is listening on.
                    com.onurkat.reclazz.util.AtomicWrite.string(
                            portFile, String.valueOf(actualPort));
                }
            } catch (IOException e) {
                StatusReporter.warn("Failed to write port file: " + com.onurkat.reclazz.ui.Failures.describe(e));
            }
        }

        Thread acceptThread = new Thread(com.onurkat.reclazz.util.Supervised.forever(
                "The status server",
                "The IDE cannot connect to this agent until the application is restarted. "
                        + "Reloading itself is unaffected.",
                this::acceptClients), "Reclazz-StatusServer");
        acceptThread.setDaemon(true);
        acceptThread.start();

        // Start heartbeat timer
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Reclazz-Heartbeat");
            t.setDaemon(true);
            return t;
        });
        // Wrapped, because scheduleAtFixedRate cancels every future execution
        // the first time its task throws, and says nothing about it. The IDE
        // then stops hearing from an agent that is perfectly well, decides it
        // has gone, and drops a connection it did not need to drop.
        heartbeatExecutor.scheduleAtFixedRate(
                com.onurkat.reclazz.util.Supervised.once("The heartbeat", this::sendHeartbeat),
                HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        StatusReporter.addListener(this);
        StatusReporter.info("Status server listening on 127.0.0.1:" + actualPort);
    }

    public void setDiagnoser(java.util.function.Function<String, List<String>> diagnoser) {
        this.diagnoser = diagnoser;
    }

    /**
     * Runs a client's command and sends the answer to every client, so the
     * report lands in the reload log the developer is already looking at.
     */
    void handleCommand(String line) {
        if (line == null) return;
        String trimmed = line.strip();
        if (trimmed.length() > MAX_COMMAND_LENGTH) return;

        try {
            if (trimmed.equalsIgnoreCase(PENDING)) {
                for (String reportLine : RestartLedger.digest()) {
                    StatusReporter.info(reportLine);
                }
                return;
            }
            if (!trimmed.regionMatches(true, 0, DIAGNOSE, 0, DIAGNOSE.length())) return;

            java.util.function.Function<String, List<String>> answering = diagnoser;
            if (answering == null) return;

            for (String reportLine : answering.apply(trimmed.substring(DIAGNOSE.length()).strip())) {
                StatusReporter.info(reportLine);
            }
        } catch (Exception e) {
            StatusReporter.warn("Could not answer " + trimmed + ": " + com.onurkat.reclazz.ui.Failures.describe(e));
        }
    }

    /** Number of currently connected status clients (diagnostics/tests). */
    public int getClientCount() {
        return clients.size();
    }

    public void stop() {
        running = false;
        StatusReporter.removeListener(this);
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        for (ClientConnection client : clients) {
            client.close();
        }
        clients.clear();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
        // Clean up port file
        if (portFile != null) {
            try {
                Files.deleteIfExists(portFile);
            } catch (IOException ignored) {}
        }
    }

    @Override
    public void onEvent(String level, String message) {
        String json;
        if ("STRUCTURAL_RELOAD".equals(level)) {
            json = String.format(
                    "{\"level\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\",\"type\":\"structural\"}",
                    escapeJson(level),
                    escapeJson(message),
                    Instant.now().toString()
            );
        } else {
            json = String.format(
                    "{\"level\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\"}",
                    escapeJson(level),
                    escapeJson(message),
                    Instant.now().toString()
            );
        }
        broadcast(json);
    }

    private void sendHeartbeat() {
        if (clients.isEmpty()) return;
        String json = String.format(
                "{\"level\":\"HEARTBEAT\",\"message\":\"\",\"timestamp\":\"%s\"}",
                Instant.now().toString()
        );
        broadcast(json);
    }

    private void acceptClients() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();

                if (clients.size() >= MAX_CLIENTS) {
                    clientSocket.close();
                    continue;
                }

                ClientConnection conn = new ClientConnection(clientSocket);
                clients.add(conn);

                // "agent" is additive: a reader that does not know the field
                // ignores it, which is how a plugin older than this agent keeps
                // working. It is here because PROTOCOL_VERSION has been 1 since
                // the beginning and answers a different question. What a
                // developer needs to know is which release is answering on this
                // port, since the jar a server attached and the plugin in the
                // IDE are updated by two different acts.
                String welcomeJson = String.format(
                        "{\"level\":\"CONNECTED\",\"message\":\"%s\",\"timestamp\":\"%s\","
                                + "\"version\":%d,\"agent\":\"%s\"}",
                        escapeJson("Agent status server connected"),
                        Instant.now().toString(),
                        PROTOCOL_VERSION,
                        escapeJson(AgentVersion.get())
                );
                conn.send(welcomeJson);

            } catch (SocketTimeoutException e) {
                // Expected — accept() timed out, loop back to check running flag
            } catch (IOException e) {
                if (running) {
                    StatusReporter.error("StatusServer accept error: " + com.onurkat.reclazz.ui.Failures.describe(e));
                }
            } catch (Throwable t) {
                // Anything that is not a socket problem cost this one
                // connection, and the loop goes round again. It used to end the
                // thread while the ServerSocket stayed bound, so the port file
                // pointed at a port that was open and that nobody was accepting
                // on: the IDE's reconnect found something to connect to,
                // forever, and never got in.
                if (running) {
                    StatusReporter.warn("A status client could not be accepted: "
                            + com.onurkat.reclazz.ui.Failures.describe(t)
                            + ". The status server is still listening.");
                }
            }
        }

        // Reached only when running went false, which is the shutdown hook.
        // Anything else that gets here has ended the one way into this agent.
        if (running) {
            com.onurkat.reclazz.util.Supervised.stoppedUnexpectedly("The status server",
                    "The IDE cannot connect to this agent until the application is restarted. "
                            + "Reloading itself is unaffected.");
        }
    }

    private void broadcast(String jsonLine) {
        List<ClientConnection> dead = new java.util.ArrayList<>();
        for (ClientConnection client : clients) {
            try {
                if (!client.send(jsonLine)) {
                    dead.add(client);
                }
            } catch (Throwable t) {
                dead.add(client);
            }
        }
        for (ClientConnection d : dead) {
            try {
                d.close();
            } catch (Throwable ignored) {
                // Closing a connection that is already gone is not news, and
                // this runs on the heartbeat's thread: a throw here used to
                // end the schedule for the rest of the session.
            }
        }
        clients.removeAll(dead);
    }

    private static final int MAX_MESSAGE_LENGTH = 4096;
    private static final int MAX_COMMAND_LENGTH = 512;
    private static final String DIAGNOSE = "DIAGNOSE";
    private static final String PENDING = "PENDING";

    /**
     * Package-private rather than private: what this returns is the line the
     * IDE parses, and a DIAGNOSE argument comes back inside it, so the
     * escaping is the thing that keeps a client from writing a status line of
     * its own choosing. StatusSocketSurfaceTest is what holds it to that.
     */
    /**
     * The encoding this socket speaks, on both ends and whatever the machine.
     *
     * <p>It carries the agent's own words to the IDE: class names, file paths,
     * the text of a failure. Both sides used to build their streams with no
     * charset, which means the default one, and the two are different JVMs.
     * The application's may be anything its start script chose, and SAP
     * Commerce installations do choose: with {@code -Dfile.encoding=ISO-8859-1}
     * on the server, "Reloaded com.acme.Siparis (Masaustu)" with its Turkish
     * letters intact left the agent as ISO-8859-1 and arrived in the IDE, whose
     * JVM is UTF-8, as "Sipari?" and "Masa\uFFFDst\uFFFD". Measured, not feared.
     *
     * <p>A protocol between two processes needs a stated encoding, so this is
     * it, in one place at each end.
     */
    static java.io.Writer writerFor(java.io.OutputStream out) {
        return new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The same, for what a client sends. */
    static java.io.Reader readerFor(java.io.InputStream in) {
        return new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8);
    }

    static String escapeJson(String value) {
        if (value == null) return "";
        if (value.length() > MAX_MESSAGE_LENGTH) {
            value = value.substring(0, MAX_MESSAGE_LENGTH) + "...(truncated)";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * One connected client. Writes happen on the client's OWN thread via a
     * bounded queue: a slow or hung reader must never stall the caller.
     *
     * Before this, {@code send} wrote and flushed synchronously on whatever
     * thread emitted the event — including the reload thread. A client that
     * stopped reading (suspended IDE, hung debugger) filled the TCP window
     * and blocked the entire hot-reload pipeline indefinitely.
     */
    private class ClientConnection {
        /**
         * Bounded on purpose: status events are advisory. A client that
         * cannot keep up drops events rather than growing the heap.
         */
        private static final int QUEUE_CAPACITY = 1000;

        final Socket socket;
        final PrintWriter writer;
        private final java.util.concurrent.BlockingQueue<String> outbox =
                new java.util.concurrent.ArrayBlockingQueue<>(QUEUE_CAPACITY);
        private final Thread writerThread;
        private volatile boolean alive = true;
        private final java.util.concurrent.atomic.AtomicLong dropped =
                new java.util.concurrent.atomic.AtomicLong();

        ClientConnection(Socket socket) throws IOException {
            this.socket = socket;
            this.writer = new PrintWriter(writerFor(socket.getOutputStream()), true);
            this.writerThread = new Thread(this::drainLoop, "Reclazz-StatusWriter");
            this.writerThread.setDaemon(true);
            this.writerThread.start();

            Thread readerThread = new Thread(this::readLoop, "Reclazz-StatusReader");
            readerThread.setDaemon(true);
            readerThread.start();
        }

        /**
         * Clients speak only to ask a question. The loop ends when they close,
         * which is also how the connection is noticed as gone.
         */
        private void readLoop() {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    readerFor(socket.getInputStream()))) {
                String line;
                while (alive && (line = reader.readLine()) != null) {
                    handleCommand(line);
                }
            } catch (Exception closed) {
                // A client that went away is not an error worth reporting.
            }
        }

        private void drainLoop() {
            try {
                while (alive) {
                    String line = outbox.poll(1, TimeUnit.SECONDS);
                    if (line == null) continue;
                    writer.println(line);
                    writer.flush();
                    if (writer.checkError()) {
                        alive = false;
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                alive = false;
            }
        }

        /**
         * Enqueue without blocking.
         *
         * @return false when the connection is dead (the caller drops it);
         *         a full queue is NOT fatal — the event is dropped instead.
         */
        boolean send(String line) {
            if (!alive) return false;
            if (!outbox.offer(line)) {
                long n = dropped.incrementAndGet();
                if (n == 1 || n % 1000 == 0) {
                    StatusReporter.warn("Status client is not keeping up — dropped "
                            + com.onurkat.reclazz.ui.Plural.of(n, "event")
                            + ". Reload is unaffected.");
                }
            }
            return true;
        }

        void close() {
            alive = false;
            writerThread.interrupt();
            try { writer.close(); } catch (Exception ignored) {}
            try { socket.close(); } catch (Exception ignored) {}
        }
    }
}
