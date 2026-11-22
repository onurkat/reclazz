/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
class RecordStockBootTwoContainerTest {
    @TempDir Path dir;
    @Test void addedComponentRebindsConfigurationAndRetainedCodecs()throws Exception{RecordNativeContainerAcceptance.runStock(dir,"add",false);}
    @ParameterizedTest @ValueSource(strings={"remove","retype","reorder","generic","annotations"})
    void configurationAndRetainedCodecs(String edit)throws Exception{RecordNativeContainerAcceptance.runStock(dir,edit,false);}
    @Test void configurationFailureLeavesCommittedSchemaAndUnverifiedReceipt()throws Exception{RecordNativeContainerAcceptance.runStock(dir,"add",true);}
}
