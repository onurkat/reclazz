/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import com.onurkat.reclazz.e2e.harness.BootLoggingStartupFixture;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
class BootLoggingStartupBaselineTest {
    @TempDir Path tmp;
    @Test void startupNamedAndRootRestoreFileLevelsLogback()throws Exception {BootLoggingStartupFixture.run(tmp,"logback","standard","2.7.18");}
    @Test void startupNamedAndRootRestoreFileLevelsLog4j()throws Exception {BootLoggingStartupFixture.run(tmp,"log4j","standard","2.7.18");}
    @ParameterizedTest @ValueSource(strings={"logback","log4j"})
    void debugDefaultSurvivesExplicitKeyRemoval(String backend)throws Exception {BootLoggingStartupFixture.run(tmp,backend,"debug","2.7.18");}
    @ParameterizedTest @ValueSource(strings={"logback","log4j"})
    void runtimeCallAndFailedStartupDoNotCreateStartupRecord(String backend)throws Exception {BootLoggingStartupFixture.run(tmp,backend,"runtime","2.7.18");}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"logback","log4j"})
    void startupBuiltinGroupRemovalRestoresItsMembers(String backend)throws Exception {BootLoggingStartupFixture.run(tmp,backend,"group","2.7.18");}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"logback","log4j"})
    void startupCustomGroupRemovalRestoresItsMembers(String backend)throws Exception {BootLoggingStartupFixture.run(tmp,backend,"customgroup","2.7.18");}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"logback","log4j"})
    void startupUndeclaredLoggerRestoresInheritance(String backend)throws Exception {BootLoggingStartupFixture.run(tmp,backend,"inherit","2.7.18");}
    @org.junit.jupiter.api.Test
    void logbackRemainsTheBackendWhenLog4jCoreIsAlsoOnTheClasspath()throws Exception {BootLoggingStartupFixture.run(tmp,"logback","dual","2.7.18");}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"logback","log4j"})
    void runtimeBuiltinGroupAddAndRemoveRestoresEachMember(String backend)throws Exception {BootLoggingStartupFixture.run(tmp,backend,"runtimegroup","2.7.18");}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"logback","log4j"})
    void runtimeCustomGroupAddAndRemoveRestoresEachMember(String backend)throws Exception {BootLoggingStartupFixture.run(tmp,backend,"runtimecustomgroup","2.7.18");}
}
