/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
class RecordStockRepairReceiptTest {
    @TempDir Path dir;
    private RecordNativeBatchAdmissionTest fixture(){var f=new RecordNativeBatchAdmissionTest();f.dir=dir;return f;}
    @Test void installedShapeWarningIsModeNeutralOnStockJvm()throws Exception{fixture().repairFailure(true);}
    @Test void rejectedBodyEditKeepsCanonicalAndPhysicalReceipts()throws Exception{fixture().rejectedBodyReceipt(true);}
    @Test void pendingRepairRetriesUntilItSucceeds()throws Exception{fixture().pendingRepairRetry(true);}
    @Test void batchCompletionFailureRemainsUnverified()throws Exception{fixture().batchCompletionFailure(true);}
    @Test void recordRetryCannotClearCallersPendingConcern()throws Exception{fixture().callerRepairRetry(true);}
}
