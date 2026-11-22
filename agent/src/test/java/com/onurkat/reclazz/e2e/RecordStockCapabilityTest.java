/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class RecordStockCapabilityTest {
    @TempDir Path dir;
    @Test void failedSMetadataSelfCheckRefusesBeforeApplicationNativeEntry()throws Exception {
        var failed=new RecordStockBatchAdmissionTest();failed.dir=Files.createDirectories(dir.resolve("probe"));failed.exercise(false,"probe");
        var control=new RecordStockBatchAdmissionTest();control.dir=Files.createDirectories(dir.resolve("control"));control.exercise(false,"normal");
    }
    @Test void privateProbeErrorIsCachedAcrossRealSaveAttempts()throws Exception {
        var failed=new RecordStockBatchAdmissionTest();failed.dir=Files.createDirectories(dir.resolve("error"));failed.exercise(false,"probeError");
        var control=new RecordStockBatchAdmissionTest();control.dir=Files.createDirectories(dir.resolve("control"));control.exercise(false,"normal");
    }

}
