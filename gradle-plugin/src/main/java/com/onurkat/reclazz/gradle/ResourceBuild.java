/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.gradle;

import java.io.File;
import java.util.List;
import org.gradle.api.logging.Logging;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.tooling.CancellationTokenSource;
import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.ResultHandler;

/** The running Gradle integration owns and cancels its own continuous resource task. */
public abstract class ResourceBuild implements BuildService<BuildServiceParameters.None>, AutoCloseable {
    private ProjectConnection connection;
    private CancellationTokenSource cancel;

    synchronized void start(File installation, File root, File userHome, String task, List<String> args) {
        if (connection != null) return;
        cancel = GradleConnector.newCancellationTokenSource();
        connection = GradleConnector.newConnector().useInstallation(installation).useGradleUserHomeDir(userHome)
                .forProjectDirectory(root).connect();
        connection.newBuild().forTasks(task).withArguments(args).withCancellationToken(cancel.token())
                .setStandardError(System.err).run(new ResultHandler<Void>() {
                    @Override public void onComplete(Void ignored) { }
                    @Override public void onFailure(GradleConnectionException failure) {
                        if (!cancel.token().isCancellationRequested())
                            Logging.getLogger(ResourceBuild.class).warn("Resource processing stopped ({}). Run {} to apply resource edits.", failure.getMessage(), task);
                    }
                });
    }

    @Override public synchronized void close() {
        if (connection == null) return;
        cancel.cancel();
        connection.close();
        connection = null;
    }
}
