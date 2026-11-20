/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import com.onurkat.reclazz.config.AgentConfig;
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

        // HYBRIS_BIN_DIR, Hybris's Tanuki Wrapper sets this as a -D system property,
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
        Home selected;
        Path explicit=config.getHybrisHome();
        if(explicit!=null) {
            Home nativeHome=hybrisBinHome();
            if(nativeHome!=null&&!canonical(explicit).equals(canonical(nativeHome.path())))
                StatusReporter.warn("hybrisHome="+explicit+" disagrees with HYBRIS_BIN_DIR installation "+nativeHome.path()
                        +"; the explicit override wins. You can drop hybrisHome= to use SAP Commerce startup detection.");
            selected=new Home(explicit,"hybrisHome=");
        } else selected=detectHome();
        if (selected == null) {
            StatusReporter.error("Cannot detect Hybris home. Set hybrisHome= in agent args, HYBRIS_BIN_DIR env var, or -DHYBRIS_BIN_DIR= system property.");
            return null;
        }
        return new HybrisPlatformContext(selected.path(),config,selected.source());
    }

    private static PlatformContext createSpringBootContext(AgentConfig config) {
        return new SpringBootContext(config);
    }

    /**
     * Auto-detect Hybris home directory.
     */
    private record Home(Path path,String source) { }
    private static Path canonical(Path path) {
        try{return path.toRealPath();}catch(java.io.IOException | SecurityException unresolved){return path.toAbsolutePath().normalize();}
    }
    private static Home candidate(Path home,String source) {
        return home!=null&&java.nio.file.Files.isDirectory(home.resolve("bin/platform"))?new Home(home,source):null;
    }
    private static Home binHome(String binDir,String source) {
        if(binDir==null||binDir.isBlank())return null;
        try {
            Path bin=Paths.get(binDir).toAbsolutePath().normalize();
            if(!java.nio.file.Files.isDirectory(bin.resolve("platform")))return null;
            Home home=candidate(bin.getParent(),source);if(home!=null)return home;
            // A link to bin itself need not have the installation as its lexical parent.
            return candidate(canonical(bin).getParent(),source);
        }catch(java.nio.file.InvalidPathException invalid){return null;}
    }
    private static Home hybrisBinHome() {
        Home env=binHome(System.getenv("HYBRIS_BIN_DIR"),"HYBRIS_BIN_DIR environment");
        return env!=null?env:binHome(System.getProperty("HYBRIS_BIN_DIR"),"HYBRIS_BIN_DIR system property");
    }
    private static Path installationAbovePlatform(Path platform) {
        Path bin=platform.getParent();return bin==null?null:bin.getParent();
    }
    private static Home detectHome() {
        Home nativeHome=hybrisBinHome();if(nativeHome!=null)return nativeHome;
        String platformHome=System.getProperty("platform.home");
        if(platformHome!=null&&!platformHome.isBlank()) {
            try {
                Home home=candidate(installationAbovePlatform(Paths.get(platformHome).toAbsolutePath().normalize()),"platform.home");
                if(home!=null)return home;
            }catch(java.nio.file.InvalidPathException invalid){/* Continue to other validated sources. */}
        }
        for(String entry:System.getProperty("java.class.path","").split(java.io.File.pathSeparator)) {
            if(entry.isBlank())continue;
            Path path;try{path=Paths.get(entry).toAbsolutePath().normalize();}catch(java.nio.file.InvalidPathException invalid){continue;}
            for(Path ancestor=path;ancestor!=null;ancestor=ancestor.getParent()) {
                if(!"bootstrap".equals(String.valueOf(ancestor.getFileName())))continue;
                Path platform=ancestor.getParent();
                if(platform!=null&&"platform".equals(String.valueOf(platform.getFileName()))) {
                    Home home=candidate(installationAbovePlatform(platform),"bootstrap classpath");if(home!=null)return home;
                }
            }
        }
        Path cwd=Paths.get(System.getProperty("user.dir"));
        for(int i=0;i<10&&cwd!=null;i++,cwd=cwd.getParent()) {
            Home home=candidate(cwd,"working directory");if(home!=null)return home;
        }
        return null;
    }
}
