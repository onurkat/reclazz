/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e.harness;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Owns exactly one temporary Docker broker; missing Docker/image is a failure. */
public final class RabbitBroker implements AutoCloseable {
    // Official rabbitmq:4.3.5-alpine multi-platform manifest. The pinned digest is
    // the provenance source of truth: the build pulls and verifies it, then tags it
    // locally as IMAGE before the gate. The test references the stable local tag,
    // never the digest, because under Docker's containerd image store a reference by
    // multi-platform INDEX digest (repo@sha256:<index>) intermittently fails
    // `docker image inspect`/`run --pull=never` even when the content is present,
    // while a local tag resolves reliably. See project_rabbit_digest_flake.
    public static final String IMAGE_DIGEST = "rabbitmq@sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd";
    public static final String IMAGE = "reclazz-rabbit-pinned:4.3.5-alpine";
    private final String name = "reclazz-rabbit-test-" + UUID.randomUUID();
    private final String password = UUID.randomUUID().toString();
    private CachingConnectionFactory connectionFactory;
    private int port;
    private boolean created;
    private final Thread cleanup = new Thread(this::removeQuietly, "reclazz-test-rabbit-cleanup");

    public static RabbitBroker start() throws Exception {
        var broker = new RabbitBroker();
        try {
            command(15, "docker", "version", "--format", "{{.Server.Version}}");
            // Never invoke a credential helper/download in a test. The build's
            // rabbitTest setup pulls IMAGE_DIGEST and tags it as IMAGE before the
            // gate; here we only inspect and run the stable local tag, offline.
            command(30, "docker", "image", "inspect", IMAGE);
            Runtime.getRuntime().addShutdownHook(broker.cleanup);
            broker.created = true; // also clean a run which created then timed out
            command(45, "docker", "run", "--detach", "--rm", "--pull=never", "--name", broker.name,
                    "--label", "reclazz.test=rabbit", "--publish", "127.0.0.1::5672",
                    "--env", "RABBITMQ_DEFAULT_USER=reclazz", "--env", "RABBITMQ_DEFAULT_PASS=" + broker.password, IMAGE);
            String binding = command(10, "docker", "port", broker.name, "5672/tcp").trim();
            broker.port = Integer.parseInt(binding.substring(binding.lastIndexOf(':') + 1));
            broker.connectionFactory = new CachingConnectionFactory("127.0.0.1", broker.port);
            broker.connectionFactory.setUsername("reclazz"); broker.connectionFactory.setPassword(broker.password);
            broker.connectionFactory.setConnectionTimeout(1000);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            Throwable last = null;
            while (System.nanoTime() < deadline) {
                try {
                    var connection = broker.connectionFactory.createConnection();
                    var channel = connection.createChannel(false); channel.close(); connection.close();
                    return broker;
                } catch (Exception unavailable) { last = unavailable; Thread.sleep(200); }
            }
            throw new IllegalStateException("RabbitMQ did not become ready: " + broker.logs(), last);
        } catch (Throwable failure) {
            try { broker.close(); } catch (Throwable cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }
    public CachingConnectionFactory connectionFactory() { return connectionFactory; }
    public int port() { return port; }
    public String password() { return password; }
    public String logs() throws Exception { return command(10, "docker", "logs", "--tail", "80", name); }
    @Override public void close() throws Exception {
        try { if (connectionFactory != null) connectionFactory.destroy(); }
        finally {
            if (created) { command(20, "docker", "rm", "--force", "--volumes", name); created = false; }
            Runtime.getRuntime().removeShutdownHook(cleanup);
        }
    }
    private void removeQuietly() {
        if (created) try { command(20, "docker", "rm", "--force", "--volumes", name); }
        catch (Exception failure) { System.err.println("Rabbit test cleanup failed for " + name + ": " + failure); }
    }
    private static String command(int seconds, String... args) throws Exception {
        Path output = Files.createTempFile("reclazz-rabbit-command-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS);
                throw new IllegalStateException("Rabbit test Docker command timed out: " + args[1]);
            }
            String text = Files.readString(output);
            if (process.exitValue() != 0) throw new IllegalStateException("Rabbit test requires Docker and the local image " + IMAGE + " (tagged from " + IMAGE_DIGEST + " by the rabbitTest setup); " + args[1] + " failed: " + text);
            return text;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }
}
