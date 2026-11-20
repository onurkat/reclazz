/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import com.onurkat.reclazz.e2e.harness.BootLoggingStartupFixture;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
class BootLoggingStartupBaselineSixTest {
    @TempDir Path tmp;
    @Test void startupNamedAndRootRestoreFileLevelsLogback()throws Exception {BootLoggingStartupFixture.run(tmp,"logback","standard","3.3.5");}
    @Test void startupNamedAndRootRestoreFileLevelsLog4j()throws Exception {BootLoggingStartupFixture.run(tmp,"log4j","standard","3.3.5");}
    @ParameterizedTest @ValueSource(strings={"logback","log4j"})
    void debugDefaultSurvivesExplicitKeyRemoval(String backend)throws Exception {BootLoggingStartupFixture.run(tmp,backend,"debug","3.3.5");}
    @ParameterizedTest @ValueSource(strings={"logback","log4j"})
    void runtimeCallAndFailedStartupDoNotCreateStartupRecord(String backend)throws Exception {BootLoggingStartupFixture.run(tmp,backend,"runtime","3.3.5");}
}
