/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.springboot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Reports, once the application is up, whether the Reclazz agent is attached, so
 * a developer or a coding agent knows the hot-reload loop is live, or gets the
 * exact command to turn it on. The starter cannot attach the agent itself: a
 * {@code -javaagent} has to be present at JVM startup.
 */
public class ReclazzStartupReporter {

    private static final Logger log = LoggerFactory.getLogger("com.onurkat.reclazz");

    private final ReclazzProperties properties;

    public ReclazzStartupReporter(ReclazzProperties properties) {
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        check(ReclazzAgentDetector.isAttached());
    }

    /** The decision, separated from the event so it can be tested directly. */
    void check(boolean attached) {
        if (attached) {
            log.info("Reclazz hot-reload agent is attached. Edit code, recompile, "
                    + "and changes reload in place, no restart.");
            return;
        }
        String message = "Reclazz starter is on the classpath, but the agent is not attached "
                + "to this JVM, so nothing will hot-reload. " + enableHint();
        if (properties.isFailOnMissingAgent()) {
            throw new IllegalStateException(message);
        }
        log.warn(message);
    }

    /** The copy-pasteable ways to attach the agent, agent-readable on purpose. */
    static String enableHint() {
        return "The agent must be a -javaagent at JVM startup. Attach it with a plain flag "
                + "(-javaagent:/path/to/reclazz-agent.jar=platform=spring), the Gradle plugin "
                + "(id \"com.onurkat.reclazz\"), or the Maven plugin (reclazz:prepare-agent). "
                + "See https://github.com/onurkat/reclazz/blob/main/docs/for-ai-agents.md";
    }
}
