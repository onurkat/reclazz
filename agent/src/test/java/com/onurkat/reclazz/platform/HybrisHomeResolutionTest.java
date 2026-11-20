/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.platform;
import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HybrisHomeResolutionTest {
    @TempDir Path temp;
    final Map<String,String> previous=new HashMap<>();final List<String> events=new ArrayList<>();
    final StatusReporter.StatusListener listener=(level,message)->events.add(level+":"+message);
    @BeforeEach void setup(){
        assertNull(System.getenv("HYBRIS_BIN_DIR"),"run this focused test with HYBRIS_BIN_DIR unset");
        for(String key:List.of("HYBRIS_BIN_DIR","platform.home","java.class.path","user.dir"))previous.put(key,System.getProperty(key));
        System.clearProperty("HYBRIS_BIN_DIR");System.clearProperty("platform.home");System.setProperty("java.class.path","");System.setProperty("user.dir",temp.toString());StatusReporter.addListener(listener);
    }
    @AfterEach void restore(){StatusReporter.removeListener(listener);previous.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);});}
    Path home(String name)throws Exception {
        Path home=temp.resolve(name);Files.createDirectories(home.resolve("bin/platform"));Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/localextensions.xml"),"<hybrisconfig><extensions/></hybrisconfig>");return home;
    }
    Path initialize(String args)throws Exception {
        PlatformContext detected=PlatformDetector.detect(AgentConfig.parse(args));assertInstanceOf(HybrisPlatformContext.class,detected);detected.initialize();
        return ((HybrisPlatformContext)detected).getHybrisContext().getHybrisHome();
    }
    long disagreements(){return events.stream().filter(line->line.startsWith("WARN:")&&line.contains("HYBRIS_BIN_DIR")&&line.contains("hybrisHome=")).count();}
    @Test void systemPropertyOnlyDetectsHomeAndNamesItsSource()throws Exception {
        Path home=home("native");System.setProperty("HYBRIS_BIN_DIR",home.resolve("bin").toString());assertEquals(home,initialize(null));
        assertTrue(events.stream().anyMatch(line->line.contains("Hybris home: ")&&line.contains("from HYBRIS_BIN_DIR")));assertEquals(0,disagreements());
    }
    @Test void explicitHomeStillSelectsSapWithoutAnyStartupProperty()throws Exception {
        Path home=home("explicit");assertEquals(home,initialize("hybrisHome="+home));assertTrue(events.stream().anyMatch(line->line.contains("from hybrisHome=")));
    }
    @Test void matchingExplicitAndNativeDoNotWarn()throws Exception {
        Path home=home("same");System.setProperty("HYBRIS_BIN_DIR",home.resolve("bin").toString());assertEquals(home,initialize("hybrisHome="+home));assertEquals(0,disagreements());
    }
    @Test void differentExplicitWinsAndWarnsExactlyOnceWithBothHomes()throws Exception {
        Path explicit=home("explicit"),nativeHome=home("native");System.setProperty("HYBRIS_BIN_DIR",nativeHome.resolve("bin").toString());assertEquals(explicit,initialize("hybrisHome="+explicit));
        assertEquals(1,disagreements());String warning=events.stream().filter(line->line.startsWith("WARN:")&&line.contains("HYBRIS_BIN_DIR")).findFirst().orElseThrow();
        assertTrue(warning.contains(explicit.toString()));assertTrue(warning.contains(nativeHome.toString()));assertTrue(warning.contains("drop hybrisHome="));
    }
    @Test void canonicalAliasDoesNotWarn()throws Exception {
        Path home=home("real"),alias=temp.resolve("alias");Files.createSymbolicLink(alias,home);System.setProperty("HYBRIS_BIN_DIR",home.resolve("bin").toString());assertEquals(alias,initialize("hybrisHome="+alias));assertEquals(0,disagreements());
    }
    @Test void platformHomeUsesInstallationAboveBin()throws Exception {
        Path home=home("native");System.setProperty("platform.home",home.resolve("bin/platform").toString());assertEquals(home,initialize(null));
    }
    @Test void bootstrapClasspathUsesInstallationAboveBin()throws Exception {
        Path home=home("native");Path bootstrap=Files.createDirectories(home.resolve("bin/platform/bootstrap/bin"));System.setProperty("java.class.path",bootstrap.resolve("ybootstrap.jar").toString());assertEquals(home,initialize("platform=hybris"));
    }
    @Test void invalidCandidatesAreNotReturned()throws Exception {
        Path wrong=Files.createDirectories(temp.resolve("not-an-install"));System.setProperty("platform.home",wrong.resolve("bin/platform").toString());
        assertNull(PlatformDetector.detect(AgentConfig.parse("platform=hybris")));
    }
    @Test void siblingOfBinIsNotAcceptedAsHybrisBinDir()throws Exception {
        Path home=home("native");System.setProperty("HYBRIS_BIN_DIR",home.resolve("not-bin").toString());
        assertNull(PlatformDetector.detect(AgentConfig.parse("platform=hybris")));
    }
    @Test void binDirectoryAliasResolvesItsActualInstallation()throws Exception {
        Path home=home("native"),alias=temp.resolve("bin-alias");Files.createSymbolicLink(alias,home.resolve("bin"));System.setProperty("HYBRIS_BIN_DIR",alias.toString());
        assertEquals(home.toRealPath(),initialize(null));
    }

}
