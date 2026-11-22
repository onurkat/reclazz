/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
class RecordStockCallerChainTest {
    @TempDir Path dir;
    private RecordNativeBatchAdmissionTest fixture(){var f=new RecordNativeBatchAdmissionTest();f.dir=dir;return f;}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void watcherAndCompilerPublishWholeCallerFactoryChain(boolean auto)throws Exception{fixture().callerChain(auto,true);}
    @Test void laterStaleCallerIsRefusedAndCurrentCallerApplies()throws Exception{fixture().staleCaller(true);}
    @Test void preWatchBodyCannotUpgradeTheRecordOrigin()throws Exception{fixture().preWatchBody(true);}
    @Test void duplicateDefiningLoadersRemainRefused()throws Exception{fixture().unsupportedScope("ambiguous","not uniquely loaded",true);}
}
