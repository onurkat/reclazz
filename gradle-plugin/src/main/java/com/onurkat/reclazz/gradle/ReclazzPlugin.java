/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.gradle;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.Plugin;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.testing.Test;

/**
 * Attaches the Reclazz hot-reload agent to a project's run and test JVMs.
 *
 * <p>Applying the plugin is enough for a Spring Boot project: {@code bootRun} and the test
 * tasks start with {@code -javaagent:reclazz-agent.jar=platform=spring,watchDirs=<main output>},
 * so an edit and a build are picked up in place with no restart and no hand-written flag.
 * The {@code reclazz} extension overrides any of it.
 */
public class ReclazzPlugin implements Plugin<Project> {

    static final String SPRING_BOOT_PLUGIN = "org.springframework.boot";
    static final String AGENT_ARTIFACT = "com.onurkat.reclazz:reclazz-agent:";
    static final String BOOT_RUN_TASK = "bootRun";

    @Override
    public void apply(Project project) {
        ReclazzExtension ext = project.getExtensions().create("reclazz", ReclazzExtension.class);
        ext.getEnabled().convention(true);
        ext.getAgentVersion().convention(project.provider(ReclazzPlugin::pluginVersion));
        ext.getApplyToTest().convention(true);
        ext.getApplyToBootRun().convention(true);

        if (project == project.getRootProject()) {
            var safe = project.getTasks().register("reclazzSafeBuild", ReclazzSafeBuildTask.class, task -> {
                task.setGroup("reclazz");
                task.setDescription("Run a whole child build under an acknowledged, owned Reclazz hold.");
                task.getTimeoutMs().convention(5000);
                task.getBuildArguments().convention(List.of("build"));
                task.getBuildDirectory().convention(project.getLayout().getProjectDirectory());
                task.getGradleExecutable().convention(new File(project.getGradle().getGradleHomeDir(),
                        "bin/gradle" + (System.getProperty("os.name").startsWith("Windows") ? ".bat" : "")).getAbsolutePath());
                task.notCompatibleWithConfigurationCache("Whole-build safety requires validating the outer task graph");
            });
            project.getGradle().getTaskGraph().whenReady(graph -> {
                if (!graph.hasTask(safe.get())) return;
                if (graph.getAllTasks().size() != 1 || project.getGradle().getStartParameter().isContinuous()
                        || project.getGradle().getStartParameter().isConfigurationCacheRequested()) {
                    throw new GradleException("Invoke reclazzSafeBuild alone, without dependencies/finalizers, continuous mode or configuration cache");
                }
            });
        }

        Configuration agentConf = project.getConfigurations().create("reclazzAgent");
        agentConf.setCanBeConsumed(false);
        agentConf.setCanBeResolved(true);
        agentConf.setVisible(false);
        agentConf.defaultDependencies(deps -> {
            if (!ext.getAgentJar().isPresent()) {
                deps.add(project.getDependencies().create(AGENT_ARTIFACT + ext.getAgentVersion().get()));
            }
        });

        ConfigurableFileCollection agentJar = project.getObjects().fileCollection();
        agentJar.from(project.provider(() -> {
            if (!ext.getEnabled().get()) return List.of();
            return List.of(ext.getAgentJar().isPresent()
                    ? ext.getAgentJar().getAsFile().get() : agentConf.getSingleFile());
        }));

        Provider<String> argumentString = project.provider(() -> buildArguments(project, ext));

        project.getTasks().withType(Test.class).configureEach(task -> {
            if (ext.getApplyToTest().get()) {
                task.getJvmArgumentProviders().add(newProvider(project, ext, agentJar, argumentString));
            }
        });
        project.getTasks().withType(JavaExec.class).configureEach(task -> {
            if (BOOT_RUN_TASK.equals(task.getName()) && ext.getApplyToBootRun().get()) {
                task.getJvmArgumentProviders().add(newProvider(project, ext, agentJar, argumentString));
            }
        });

        project.getTasks().register("reclazzStatus", ReclazzStatusTask.class, task -> {
            task.setGroup("reclazz");
            task.setDescription("Print, as JSON, whether the Reclazz agent is attached and how it is doing.");
        });
    }

    private AgentArgumentProvider newProvider(Project project, ReclazzExtension ext,
            ConfigurableFileCollection agentJar, Provider<String> argumentString) {
        AgentArgumentProvider provider = project.getObjects().newInstance(AgentArgumentProvider.class);
        provider.getEnabled().set(ext.getEnabled());
        provider.getAgentJar().from(agentJar);
        provider.getArgumentString().set(argumentString);
        return provider;
    }

    private String buildArguments(Project project, ReclazzExtension ext) {
        Map<String, String> args = new LinkedHashMap<>();
        if (ext.getPlatform().isPresent()) {
            args.put("platform", ext.getPlatform().get());
        } else if (project.getPlugins().hasPlugin(SPRING_BOOT_PLUGIN)) {
            args.put("platform", "spring");
        }
        List<String> watchDirs = ext.getWatchDirs().getOrElse(List.of());
        if (watchDirs.isEmpty()) {
            String main = mainOutput(project);
            if (main != null) {
                args.put("watchDirs", main);
            }
        } else {
            args.put("watchDirs", String.join(";", watchDirs));
        }
        args.putAll(ext.getArguments().getOrElse(Map.of()));
        return args.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }

    private static String pluginVersion() {
        try (var in = ReclazzPlugin.class.getResourceAsStream("version.properties")) {
            if (in == null) throw new GradleException("Reclazz plugin version resource is missing");
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version");
            if (version == null || version.isBlank() || version.contains("${")) {
                throw new GradleException("Reclazz plugin version resource is invalid");
            }
            return version;
        } catch (IOException e) {
            throw new GradleException("Cannot read Reclazz plugin version", e);
        }
    }

    private static String mainOutput(Project project) {
        SourceSetContainer sourceSets = project.getExtensions().findByType(SourceSetContainer.class);
        if (sourceSets == null) {
            return null;
        }
        SourceSet main = sourceSets.findByName(SourceSet.MAIN_SOURCE_SET_NAME);
        if (main == null) {
            return null;
        }
        Set<File> dirs = main.getOutput().getClassesDirs().getFiles();
        return dirs.isEmpty() ? null : dirs.iterator().next().getAbsolutePath();
    }
}
