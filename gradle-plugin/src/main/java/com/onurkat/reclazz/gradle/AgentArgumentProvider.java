/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.gradle;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.process.CommandLineArgumentProvider;

/**
 * Builds the single {@code -javaagent:<jar>=<args>} JVM argument, lazily, so the agent jar
 * and the argument string are only resolved when the task actually runs. Declared as task
 * inputs so a run task stays correct when the jar or the arguments change.
 */
public abstract class AgentArgumentProvider implements CommandLineArgumentProvider {

    @Input
    public abstract Property<Boolean> getEnabled();

    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getAgentDir();

    @Classpath
    public abstract ConfigurableFileCollection getAgentJar();

    @Input
    public abstract Property<String> getArgumentString();

    @Override
    public Iterable<String> asArguments() {
        if (!getEnabled().get()) {
            return Collections.emptyList();
        }
        File jar;
        try {
            jar = com.onurkat.reclazz.launchpath.AgentJarPath.forLaunch(getAgentJar().getSingleFile().toPath(), getAgentDir().getOrNull()).toFile();
        } catch (java.io.IOException e) {
            throw new org.gradle.api.GradleException(e.getMessage(), e);
        }
        String args = getArgumentString().getOrElse("");
        if (!args.chars().allMatch(c -> c < 128)) {
            args = "reclazz-utf8:" + Base64.getUrlEncoder().withoutPadding().encodeToString(args.getBytes(StandardCharsets.UTF_8));
        }
        String flag = "-javaagent:" + jar.getAbsolutePath() + (args.isEmpty() ? "" : "=" + args);
        return List.of(flag);
    }
}
