/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
class RecordOldObjectsTest {
    @TempDir Path dir;
    private RecordNativeBatchAdmissionTest fixture(){var f=new RecordNativeBatchAdmissionTest();f.dir=dir;return f;}
    @Test void compatibleValuesDefaultsAndHashCollectionKeys()throws Exception{fixture().objectMethods(true);}
    @Test void hidesRetiredCanonicalPreservesAuxiliary()throws Exception{fixture().constructorView(true);}
    @Test void nonRecordSaveKeepsPerClassPath()throws Exception{fixture().ordinarySave(true);}
    @Test void retainedRepairFailureIsUnverifiedWithSurfaceLedger()throws Exception{fixture().repairFailure(true);}
}
