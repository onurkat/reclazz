/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import com.onurkat.reclazz.agent.AgentConfig;
import com.onurkat.reclazz.ui.StatusReporter;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Auto-detects the runtime platform and creates the appropriate PlatformContext.
 *
 * Detection order:
 * 1. Explicit platform= agent argument (hybris, spring, generic)
 * 2. Hybris: platform.home sysprop or HYBRIS_BIN_DIR env or hybrisHome= arg
 * 3. Spring Boot: SpringApplication class on classpath
 * 4. Fallback: generic Java application
 */
public class PlatformDetector {

    /**
     * Detect the runtime platform and create the appropriate context.
     */
    public static PlatformContext detect(AgentConfig config) {
        String explicit = config.getPlatform();

        if ("hybris".equalsIgnoreCase(explicit)) {
            StatusReporter.info("Platform: Hybris (explicit)");
            return createHybrisContext(config);
        }

        if ("spring".equalsIgnoreCase(explicit)) {
            StatusReporter.info("Platform: Spring Boot (explicit)");
            return createSpringBootContext(config);
        }

        if ("generic".equalsIgnoreCase(explicit)) {
            StatusReporter.info("Platform: Generic Java (explicit)");
            return createSpringBootContext(config);
        }

        // Auto-detection
        return autoDetect(config);
    }

    private static PlatformContext autoDetect(AgentConfig config) {
        // Check for Hybris first (most specific)
        if (isHybrisEnvironment(config)) {
            StatusReporter.info("Platform: Hybris (auto-detected)");
            return createHybrisContext(config);
        }

        // Check for Spring Boot
        if (isSpringBootEnvironment()) {
            StatusReporter.info("Platform: Spring Boot (auto-detected)");
            return createSpringBootContext(config);
        }

        // Fallback: generic (still uses Spring Boot context for directory watching)
        StatusReporter.info("Platform: Generic Java (fallback)");
        return createSpringBootContext(config);
    }

    /**
     * Detect Hybris environment via system properties, env vars, or agent config.
     */
    private static boolean isHybrisEnvironment(AgentConfig config) {
        // Explicit hybrisHome in agent args
        if (config.getHybrisHome() != null) {
            return true;
        }

        // HYBRIS_BIN_DIR — Hybris's Tanuki Wrapper sets this as a -D system property,
        // not an environment variable, so we have to check both.
        if (System.getenv("HYBRIS_BIN_DIR") != null) {
            return true;
        }
        if (System.getProperty("HYBRIS_BIN_DIR") != null) {
            return true;
        }

        // platform.home system property (set by Hybris startup scripts)
        if (System.getProperty("platform.home") != null) {
            return true;
        }

        return false;
    }

    /**
     * Detect Spring Boot environment by checking for SpringApplication on classpath.
     */
    private static boolean isSpringBootEnvironment() {
        try {
            Class.forName("org.springframework.boot.SpringApplication", false,
                    Thread.currentThread().getContextClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            // Not Spring Boot
        }

        try {
            Class.forName("org.springframework.boot.SpringApplication", false,
                    ClassLoader.getSystemClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            // Not Spring Boot
        }

        return false;
    }

    private static PlatformContext createHybrisContext(AgentConfig config) {
        Path hybrisHome = config.getHybrisHome();
        if (hybrisHome == null) {
            hybrisHome = detectHybrisHome();
        }
        if (hybrisHome == null) {
            StatusReporter.error("Cannot detect Hybris home. Set hybrisHome= in agent args, HYBRIS_BIN_DIR env var, or -DHYBRIS_BIN_DIR= system property.");
            return null;
        }
        return new HybrisPlatformContext(hybrisHome, config);
    }

    private static PlatformContext createSpringBootContext(AgentConfig config) {
        return new SpringBootContext(config);
    }

    /**
     * Auto-detect Hybris home directory.
     */
    private static Path detectHybrisHome() {
        // Try HYBRIS_BIN_DIR — check both env var and system property,
        // since Hybris's Tanuki Wrapper sets it as -D system property.
        String binDir = System.getenv("HYBRIS_BIN_DIR");
        if (binDir == null) {
            binDir = System.getProperty("HYBRIS_BIN_DIR");
        }
        if (binDir != null) {
            Path platformDir = Paths.get(binDir, "platform");
            if (platformDir.toFile().exists()) {
                return Paths.get(binDir).getParent();
            }
        }

        // Try platform.home system property
        String platformHome = System.getProperty("platform.home");
        if (platformHome != null) {
            return Paths.get(platformHome).getParent();
        }

        // Try classpath — Hybris always has platform/bootstrap/bin on classpath
        String classpath = System.getProperty("java.class.path", "");
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            Path p = Paths.get(entry);
            // Look for .../platform/bootstrap/bin/... pattern
            for (Path ancestor = p; ancestor != null; ancestor = ancestor.getParent()) {
                if ("bootstrap".equals(String.valueOf(ancestor.getFileName()))) {
                    Path platform = ancestor.getParent();
                    if (platform != null && "platform".equals(String.valueOf(platform.getFileName()))) {
                        Path hybrisHome = platform.getParent();
                        if (hybrisHome != null && hybrisHome.toFile().exists()) {
                            return hybrisHome;
                        }
                    }
                }
            }
        }

        // Walk up from CWD
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path candidate = cwd;
        for (int i = 0; i < 10; i++) {
            Path platform = candidate.resolve("bin").resolve("platform");
            if (platform.toFile().exists()) {
                return candidate;
            }
            candidate = candidate.getParent();
            if (candidate == null) break;
        }

        return null;
    }
}
