/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real agent preflight/JNI/CAS boundaries, each with an applied control in a fresh child. */
class RecordPublicationFailureTest {
    @TempDir Path dir;
    @Test void beforeNativeKeepsOldRecordAndCaller()throws Exception{failureAndApplied("native");}
    @Test void activationFailureKeepsOldEpochAndCaller()throws Exception{failureAndApplied("activation");}
    private void failureAndApplied(String mode)throws Exception {
        var failed=new RecordStockBatchAdmissionTest();failed.dir=Files.createDirectories(dir.resolve("failed"));failed.exercise(false,mode);
        var control=new RecordStockBatchAdmissionTest();control.dir=Files.createDirectories(dir.resolve("control"));control.exercise(false,"normal");
    }
}
