/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class RecordStockScopeTest {
    @TempDir Path dir;
    @Test void changedInterfaceDoesNotSilentlyApplyOnlyComponents()throws Exception{refusalAndControl("interfaces");}
    @Test void changedVisibilityDoesNotSilentlyApplyOnlyComponents()throws Exception{refusalAndControl("visibility");}
    @Test void changedCallerFinalityDoesNotSilentlyApplyOnlyBodies()throws Exception{refusalAndControl("callerFinal");}
    @Test void changedCallerVisibilityDoesNotSilentlyApplyOnlyBodies()throws Exception{refusalAndControl("callerVisibility");}
    private void refusalAndControl(String mode)throws Exception{
        var refused=new RecordStockBatchAdmissionTest();refused.dir=Files.createDirectories(dir.resolve(mode));refused.exercise(false,mode);
        var control=new RecordStockBatchAdmissionTest();control.dir=Files.createDirectories(dir.resolve("control"));control.exercise(false,"normal");
    }
}
