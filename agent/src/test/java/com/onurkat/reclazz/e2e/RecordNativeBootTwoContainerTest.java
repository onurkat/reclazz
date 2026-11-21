/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
class RecordNativeBootTwoContainerTest {
    @TempDir Path dir;
    @org.junit.jupiter.api.Test void configurationRepairFailureLeavesNativeSchemaAndUnverifiedReceipt()throws Exception{
        RecordNativeContainerAcceptance.run(dir,"add",true);
    }
    @ParameterizedTest @ValueSource(strings={"add","remove","retype","reorder"})
    void configurationAndRetainedCodecs(String edit)throws Exception{RecordNativeContainerAcceptance.run(dir,edit);}
}
