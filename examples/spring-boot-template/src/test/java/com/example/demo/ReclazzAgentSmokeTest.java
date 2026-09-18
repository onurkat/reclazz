package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the template's wiring: this test JVM was started with the Reclazz agent,
 * which is what makes hot reload work for both the tests and `bootRun`. If the
 * build's agent wiring breaks, this fails instead of silently reloading nothing.
 */
class ReclazzAgentSmokeTest {

    @Test
    void agentIsAttachedToThisJvm() {
        List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        boolean attached = args.stream()
                .anyMatch(a -> a.startsWith("-javaagent") && a.contains("reclazz-agent"));
        assertThat(attached)
                .as("expected a -javaagent for reclazz-agent, got: %s", args)
                .isTrue();
    }
}
