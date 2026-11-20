/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.platform;

import com.onurkat.reclazz.config.AgentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScopeViewTest {
    @TempDir Path home;
    private static void field(Object target,String name,Object value)throws Exception {
        var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    @Test void targetViewsIncludeEveryScopedMapWithoutPublishingAndKeepGlobalClasspath()throws Exception {
        Path platform=Files.createDirectories(home.resolve("bin/platform"));Files.createDirectories(home.resolve("config"));
        Files.createDirectories(platform.resolve("bootstrap/modelclasses"));
        Map<String,ExtensionInfo> extensions=new LinkedHashMap<>();
        for(String name:List.of("a","b")){
            Path dir=home.resolve("bin/custom").resolve(name);
            for(String root:List.of("classes","src","resources","backoffice/classes"))Files.createDirectories(dir.resolve(root));
            extensions.put(name,new ExtensionInfo(name,dir,List.of(),true,false,true,true));
        }
        var hybris=new HybrisContext(home);field(hybris,"platformHome",platform);field(hybris,"configDir",home.resolve("config"));field(hybris,"extensions",extensions);
        var config=AgentConfig.parse("watchExtensions=a");var source=new HybrisPlatformContext(home,config);field(source,"hybrisContext",hybris);
        for(Set<String> names:List.of(Set.of("a"),Set.of("a","b"),Set.of("b"))){
            var view=new ScopeView(source,new AgentConfig.WatchScope(false,names));
            var classes=new HashSet<>(view.getClassOutputDirs().keySet());classes.remove("__platform_bootstrap__");
            var resources=new HashSet<>(view.getResourceDirs().keySet());resources.remove("__platform_config__");
            assertEquals(names,classes);assertEquals(names,resources);assertEquals(names,view.getSourceDirs().keySet());
            assertEquals(names,view.getBackofficeClassDirs().keySet());assertEquals(names,view.getConfigurationFiles().keySet());
            assertFalse(view.shouldWatch("__platform_bootstrap__"));assertFalse(view.shouldWatch("__platform_config__"));
            for(String name:List.of("a","b"))assertTrue(view.resolveClasspath().contains(extensions.get(name).getPath().resolve("classes").toString()));
            assertEquals(Set.of("a"),config.getWatchExtensions());assertEquals(Set.of("a"),source.getConfigurationFiles().keySet());
        }
    }
}
